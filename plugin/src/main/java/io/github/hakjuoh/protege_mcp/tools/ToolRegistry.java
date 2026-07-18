package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.github.hakjuoh.protege_mcp.catalog.McpCatalog;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Collects the {@link SyncToolSpecification}s contributed by the {@link ToolProvider}s during catalog
 * assembly. It replaces the per-provider {@code new ArrayList<>()} / {@code add(...)} / {@code return}
 * boilerplate with a single fluent sink: each {@link #tool} call is delegated to {@link ToolSpecs#of},
 * so the (validated) factory remains the single point where a specification is constructed.
 *
 * <p>A registry is single-use per {@link ToolCatalog#buildAll}: the providers register into one shared
 * instance in declaration order and {@link #build()} yields the accumulated list.
 */
public final class ToolRegistry {

    private final List<SyncToolSpecification> specs = new ArrayList<>();

    /**
     * Register a handler by name, resolving its description and input schema from the shared JSON
     * catalog.
     */
    public ToolRegistry tool(String name,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        McpCatalog.ToolDefinition definition = McpCatalog.get().tool(name);
        return tool(definition.name(), definition.description(), definition.inputSchema(), handler);
    }

    /**
     * Register explicitly supplied metadata. Retained for focused factory tests and extensions that
     * are not part of the built-in catalog. Every registered handler crosses the same guarded
     * execution boundary so providers only implement their processing logic.
     */
    public ToolRegistry tool(String name, String description, Map<String, Object> inputSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> guarded =
                (exchange, request) -> Tools.guard(() -> handler.apply(exchange, request));
        specs.add(ToolSpecs.of(name, description, inputSchema, guarded));
        return this;
    }

    /** The specifications collected so far, in registration order (the registry's own mutable list). */
    public List<SyncToolSpecification> build() {
        return specs;
    }
}
