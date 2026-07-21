package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;
import java.util.function.BiFunction;

import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Factory for {@link SyncToolSpecification}s (a name + JSON-schema {@link Tool} plus its handler).
 *
 * <p>The handler is installed RAW — exactly as supplied, with no error boundary. Register tools
 * through {@link ToolRegistry}, whose overloads wrap every handler in the shared
 * exception-to-MCP-error guard; call this factory directly only from focused tests that assert the
 * unwrapped handler contract.
 */
public final class ToolSpecs {

    private ToolSpecs() {
    }

    static SyncToolSpecification of(String name, String description, Map<String, Object> inputSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        return of(name, description, inputSchema, ToolContractSchemas.legacySuccessSchema(),
                ToolContractSchemas.errorSchema(), handler);
    }

    public static SyncToolSpecification of(String name, String description,
            Map<String, Object> inputSchema, Map<String, Object> outputSchema,
            Map<String, Object> errorSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        if (inputSchema == null) throw new IllegalArgumentException("input schema is required");
        Map<String, Object> input = ImmutableJson.map(inputSchema);
        Map<String, Object> output = ImmutableJson.map(outputSchema);
        Map<String, Object> error = ImmutableJson.map(errorSchema);
        Tool tool = Tool.builder(name, input).description(description)
                .outputSchema(ToolContractSchemas.wireOutputSchema(output))
                .meta(Map.of(ToolContractSchemas.SUCCESS_SCHEMA_META_KEY, output,
                        ToolContractSchemas.ERROR_SCHEMA_META_KEY, error))
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler(handler).build();
    }
}
