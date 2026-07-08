package io.github.hakjuoh.protege_mcp.server;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.github.hakjuoh.protege_mcp.tools.Prompts;

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
    public static final String SERVER_VERSION = "0.4.2";

    private static final String MCP_ENDPOINT = "/mcp";

    private static final String INSTRUCTIONS =
            "Tools to read and edit the ontology currently open in Protégé Desktop. Every tool returns "
                    + "a JSON object (also mirrored as text). Edits go through Protégé's OWLModelManager, "
                    + "so they appear in the GUI immediately and can be undone with undo_change.\n\n"
                    + "Recommended workflow:\n"
                    + "1. Orient: call get_ontology_context for an overview, or get_entity_context for a "
                    + "single term's neighbourhood (annotations, parents/children, domain/range, ...).\n"
                    + "2. Identify entities by IRI where possible; use search_entities / get_entity to "
                    + "resolve a name to an IRI (a display name can be ambiguous or create a new entity "
                    + "from a typo).\n"
                    + "3. Before editing, preview with preview_changes to see the diff and any new "
                    + "entities; then apply with the write tools (writes may be gated by a read-only "
                    + "switch or a confirmation dialog).\n"
                    + "4. Verify: call run_reasoner before reading inferred results, and validate_ontology "
                    + "for modelling-quality issues.\n"
                    + "To query the ontology, sparql_schema lists the queryable vocabulary (prefixes, "
                    + "classes, properties, example queries) and sparql_validate checks a draft query "
                    + "before sparql_query runs it.\n"
                    + "The MCP prompts (audit_ontology, explain_class, add_subclass_safely, "
                    + "find_and_fix_unsatisfiable, author_sparql_query, model_domain) package these "
                    + "workflows.";

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
                .capabilities(ServerCapabilities.builder().tools(false).prompts(false).build())
                .instructions(INSTRUCTIONS)
                .immediateExecution(true)
                .validateToolInputs(false)
                .jsonMapper(jsonMapper)
                .jsonSchemaValidator(schemaValidator)
                .tools(tools)
                .prompts(Prompts.all())
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
