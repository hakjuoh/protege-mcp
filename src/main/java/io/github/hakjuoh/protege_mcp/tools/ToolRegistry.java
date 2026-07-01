package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

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
     * Register one tool: its {@code name}, {@code description}, JSON-schema {@code inputSchema} and its
     * call {@code handler}. Returns {@code this} so calls can chain.
     */
    public ToolRegistry tool(String name, String description, Map<String, Object> inputSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        specs.add(ToolSpecs.of(name, description, inputSchema, handler));
        return this;
    }

    /** The specifications collected so far, in registration order (the registry's own mutable list). */
    public List<SyncToolSpecification> build() {
        return specs;
    }
}
