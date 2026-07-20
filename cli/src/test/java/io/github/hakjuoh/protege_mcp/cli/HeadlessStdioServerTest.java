package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolCatalog;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;

class HeadlessStdioServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void lineBoundsResetAtNewlinesAndRejectBeforeWritingAnOversizedResponse() throws Exception {
        var input = new HeadlessStdioServer.BoundedLineInputStream(new ByteArrayInputStream(
                "1234\n5678\n".getBytes(StandardCharsets.UTF_8)), 4);
        assertEquals("1234\n5678\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));

        var tooLarge = new HeadlessStdioServer.BoundedLineInputStream(new ByteArrayInputStream(
                "12345\n".getBytes(StandardCharsets.UTF_8)), 4);
        assertThrows(IOException.class, tooLarge::readAllBytes);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        AtomicInteger aborts = new AtomicInteger();
        var output = new HeadlessStdioServer.BoundedLineOutputStream(destination, 4,
                aborts::incrementAndGet);
        assertThrows(IOException.class,
                () -> output.write("12345\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, destination.size(), "an oversized JSON-RPC line must not be partially visible");
        assertEquals(1, aborts.get());

        AtomicInteger brokenAborts = new AtomicInteger();
        PrintStream swallowing = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("broken pipe");
            }
        });
        var broken = new HeadlessStdioServer.BoundedLineOutputStream(
                swallowing, 100, brokenAborts::incrementAndGet);
        broken.write("{}\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, broken::flush,
                "PrintStream's swallowed broken-pipe state must become a transport failure");
        assertEquals(1, brokenAborts.get());
    }

    @Test
    void malformedOrUnterminatedProtocolInputFailsWithoutLeavingTheServerAlive() {
        for (byte[] input : new byte[][] {
                "{not-json}\n".getBytes(StandardCharsets.UTF_8),
                "{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                        .getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_8),
                new byte[] {(byte) 0xff, '\n'}}) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> assertThrows(IOException.class, () -> HeadlessStdioServer.serve(
                            new ByteArrayInputStream(input), new ByteArrayOutputStream(),
                            new HeadlessToolService(Path.of("missing-policy.yaml"),
                                    new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC()),
                            HeadlessToolService.DEFAULT_CAPABILITIES),
                            () -> "input=" + java.util.Arrays.toString(input)));
        }
    }

    @Test
    void realSdkStdioHandshakeListsAndCallsOnlyTheHeadlessSubset() throws Exception {
        PipedInputStream serverInput = new PipedInputStream(64 * 1024);
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        PipedInputStream clientInput = new PipedInputStream(64 * 1024);
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger exit = new AtomicInteger(-1);
        HeadlessToolService service = new HeadlessToolService(Path.of("missing-policy.yaml"),
                new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
        Thread server = new Thread(() -> {
            try {
                exit.set(HeadlessStdioServer.serve(serverInput, serverOutput, service,
                        HeadlessToolService.DEFAULT_CAPABILITIES));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "headless-stdio-test");
        server.start();

        PrintWriter requests = new PrintWriter(new OutputStreamWriter(
                clientOutput, StandardCharsets.UTF_8), true);
        BufferedReader responses = new BufferedReader(new InputStreamReader(
                clientInput, StandardCharsets.UTF_8));
        requests.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
        JsonNode initialized = JSON.readTree(responses.readLine());
        assertEquals(1, initialized.path("id").asInt());
        assertEquals("protege-mcp-headless",
                initialized.path("result").path("serverInfo").path("name").asText());
        requests.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        requests.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode listed = JSON.readTree(responses.readLine());
        assertEquals(HeadlessToolCatalog.definitions().size(),
                listed.path("result").path("tools").size());
        assertTrue(listed.toString().contains(HeadlessToolCatalog.SURFACE_TOOL));
        assertTrue(!listed.toString().contains("create_class"));

        requests.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_headless_capabilities\",\"arguments\":{}}}");
        JsonNode called = JSON.readTree(responses.readLine());
        assertEquals(true, called.path("result").path("structuredContent")
                .path("project_confined").asBoolean());
        assertEquals("deny", called.path("result").path("structuredContent")
                .path("network").asText());

        clientOutput.close();
        server.join(5_000L);
        assertTrue(!server.isAlive(), "stdio server must terminate after client EOF");
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(0, exit.get());
    }
}
