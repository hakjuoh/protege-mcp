package org.protege.mcp.tools;

import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/** Factory for {@link SyncToolSpecification}s (a name + JSON-schema {@link Tool} plus its handler). */
public final class ToolSpecs {

    private ToolSpecs() {
    }

    public static SyncToolSpecification of(String name, String description, Map<String, Object> inputSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        Tool tool = Tool.builder(name, inputSchema).description(description).build();
        return SyncToolSpecification.builder().tool(tool).callHandler(handler).build();
    }
}
