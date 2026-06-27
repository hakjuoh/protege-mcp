package io.github.hakjuoh.protege_mcp.server;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Builds and owns the MCP sync server and its Streamable-HTTP transport.
 *
 * <p>The transport provider is itself a {@code jakarta.servlet.http.HttpServlet}; {@link
 * EmbeddedHttpServer} hosts it. The JSON mapper is constructed explicitly (rather than relying on
 * the SDK's {@code ServiceLoader} default) because service discovery is unreliable across the
 * bundle classloader. {@code immediateExecution(true)} keeps handlers on the transport thread — we
 * marshal to the EDT ourselves via {@link OntologyAccess} — and {@code validateToolInputs(false)}
 * skips the embedded JSON-schema validator (handlers validate required arguments themselves).
 */
public final class McpServerManager {

    public static final String SERVER_NAME = "protege-mcp";
    public static final String SERVER_VERSION = "0.1.2";

    private static final String MCP_ENDPOINT = "/mcp";

    private static final String INSTRUCTIONS =
            "Tools to read and edit the ontology currently open in Protégé Desktop. Edits go through "
                    + "Protégé's OWLModelManager, so they appear in the GUI immediately and can be "
                    + "undone with undo_change. Identify entities by IRI where possible; use "
                    + "search_entities / get_entity to discover IRIs. Call run_reasoner before reading "
                    + "inferred results.";

    private McpSyncServer server;
    private HttpServletStreamableServerTransportProvider transport;

    /** Build the transport + sync server and register all tool specifications. */
    public void build(List<SyncToolSpecification> tools) {
        // Construct the JSON mapper AND the JSON-schema validator explicitly. The SDK otherwise
        // resolves both via java.util.ServiceLoader (McpJsonDefaults), which fails under Protégé's
        // OSGi classloading ("No JsonSchemaValidatorSupplier available"). The server's build() runs
        // validateSyncToolSchemas() even with validateToolInputs(false), so a validator is required.
        ObjectMapper objectMapper = new ObjectMapper();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(objectMapper);
        JsonSchemaValidator schemaValidator = new DefaultJsonSchemaValidator(objectMapper);

        this.transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(MCP_ENDPOINT)
                .disallowDelete(false)
                .build();

        this.server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .instructions(INSTRUCTIONS)
                .immediateExecution(true)
                .validateToolInputs(false)
                .jsonMapper(jsonMapper)
                .jsonSchemaValidator(schemaValidator)
                .tools(tools)
                .build();
    }

    /** The transport servlet to register with the embedded HTTP host. */
    public HttpServletStreamableServerTransportProvider getTransportServlet() {
        return transport;
    }

    public String getEndpointPath() {
        return MCP_ENDPOINT;
    }

    /** Gracefully shut down the MCP server (best effort). */
    public void close() {
        if (server != null) {
            try {
                server.closeGracefully();
            } catch (RuntimeException e) {
                server.close();
            } finally {
                server = null;
            }
        }
    }
}
