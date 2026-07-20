package io.github.hakjuoh.protege_mcp.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema;

/** Lifecycle and byte bounds around the SDK's newline-delimited stdio transport. */
final class HeadlessStdioServer {

    static final int MAX_INBOUND_MESSAGE_BYTES = 1_048_576;
    static final int MAX_OUTBOUND_MESSAGE_BYTES = 8_388_608;

    private HeadlessStdioServer() {
    }

    static int serve(InputStream input, OutputStream output, HeadlessToolService service,
            Set<String> capabilities) throws IOException, InterruptedException {
        ObjectMapper objectMapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        BoundedLineInputStream boundedInput = new BoundedLineInputStream(
                input, MAX_INBOUND_MESSAGE_BYTES, jsonMapper);
        BoundedLineOutputStream boundedOutput = new BoundedLineOutputStream(
                output, MAX_OUTBOUND_MESSAGE_BYTES, boundedInput::abort);
        JsonSchemaValidator schemaValidator = new DefaultJsonSchemaValidator(objectMapper);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                jsonMapper, boundedInput, boundedOutput);
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("protege-mcp-headless", Main.VERSION)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .instructions("Offline, project-confined ontology QC, import-lock, and release tools. "
                        + "Call get_headless_capabilities before assuming a live Protégé operation exists.")
                // Stdio has one blocking reader. Inline handlers stop that reader until the result is
                // ready, providing transport backpressure and preventing queued writes from racing.
                .immediateExecution(true)
                .validateToolInputs(true)
                .jsonMapper(jsonMapper)
                .jsonSchemaValidator(schemaValidator)
                .tools(HeadlessToolRegistry.build(service, capabilities,
                        MAX_INBOUND_MESSAGE_BYTES, MAX_OUTBOUND_MESSAGE_BYTES))
                .build();
        try {
            try {
                boundedInput.awaitTermination();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            // An outbound failure deliberately aborts stdin; report the initiating failure rather
            // than the derivative closed-input exception.
            IOException violation = boundedOutput.violation();
            if (violation == null) violation = boundedInput.violation();
            if (violation != null) throw violation;
            return 0;
        } finally {
            boundedInput.abort();
            try {
                server.closeGracefully();
            } catch (RuntimeException closeFailure) {
                server.close();
            }
        }
    }

    /** Counts UTF-8 bytes between newlines before the SDK's unbounded BufferedReader sees them. */
    static final class BoundedLineInputStream extends InputStream {
        private final InputStream delegate;
        private final int maximum;
        private final CountDownLatch terminated = new CountDownLatch(1);
        private final AtomicReference<IOException> violation = new AtomicReference<>();
        private final McpJsonMapper jsonMapper;
        private byte[] framed = new byte[0];
        private int offset;
        private boolean eof;

        BoundedLineInputStream(InputStream delegate, int maximum) {
            this(delegate, maximum, null);
        }

        BoundedLineInputStream(InputStream delegate, int maximum, McpJsonMapper jsonMapper) {
            if (delegate == null || maximum <= 0) throw new IllegalArgumentException("invalid input bound");
            this.delegate = delegate;
            this.maximum = maximum;
            this.jsonMapper = jsonMapper;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return 0;
            try {
                if (this.offset >= framed.length && !frameNextLine()) return -1;
                int count = Math.min(length, framed.length - this.offset);
                System.arraycopy(framed, this.offset, bytes, offset, count);
                this.offset += count;
                return count;
            } catch (IOException failure) {
                violation.compareAndSet(null, failure);
                terminated.countDown();
                throw failure;
            }
        }

        private boolean frameNextLine() throws IOException {
            if (eof) return false;
            ByteArrayOutputStream line = new ByteArrayOutputStream(Math.min(maximum, 8192));
            while (true) {
                int value = delegate.read();
                if (value == -1) {
                    eof = true;
                    if (line.size() == 0) {
                        terminated.countDown();
                        return false;
                    }
                    throw new IOException("stdio inbound message is not newline-terminated");
                }
                if (value == '\n') {
                    validate(line.toByteArray());
                    line.write(value);
                    framed = line.toByteArray();
                    offset = 0;
                    return true;
                }
                if (line.size() >= maximum) {
                    throw new IOException("stdio inbound message exceeds " + maximum + " bytes");
                }
                line.write(value);
            }
        }

        private void validate(byte[] line) throws IOException {
            if (jsonMapper == null) return;
            try {
                String json = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(line)).toString();
                McpSchema.deserializeJsonRpcMessage(jsonMapper, json);
            } catch (IOException | RuntimeException malformed) {
                throw new IOException("invalid stdio JSON-RPC message", malformed);
            }
        }

        void abort() {
            terminated.countDown();
            try {
                delegate.close();
            } catch (IOException ignored) {
                // The recorded output violation is the useful failure.
            }
        }

        void awaitTermination() throws InterruptedException {
            terminated.await();
        }

        IOException violation() {
            return violation.get();
        }
    }

    /** Rejects a complete outbound write before any over-limit JSON-RPC line becomes visible. */
    static final class BoundedLineOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final int maximum;
        private final Runnable abortInput;
        private final AtomicReference<IOException> violation = new AtomicReference<>();
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(8192);

        BoundedLineOutputStream(OutputStream delegate, int maximum, Runnable abortInput) {
            if (delegate == null || maximum <= 0 || abortInput == null) {
                throw new IllegalArgumentException("invalid output bound");
            }
            this.delegate = delegate;
            this.maximum = maximum;
            this.abortInput = abortInput;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            try {
                for (int index = offset; index < offset + length; index++) {
                    if (bytes[index] == '\n') {
                        line.writeTo(delegate);
                        delegate.write('\n');
                        line.reset();
                    } else if (line.size() >= maximum) {
                        throw new IOException(
                                "stdio outbound message exceeds " + maximum + " bytes");
                    } else {
                        line.write(bytes[index]);
                    }
                }
            } catch (IOException failure) {
                violation.compareAndSet(null, failure);
                abortInput.run();
                throw failure;
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            try {
                delegate.flush();
                if (delegate instanceof java.io.PrintStream stream && stream.checkError()) {
                    throw new IOException("stdio output stream failed");
                }
            } catch (IOException failure) {
                violation.compareAndSet(null, failure);
                abortInput.run();
                throw failure;
            }
        }

        IOException violation() {
            return violation.get();
        }
    }
}
