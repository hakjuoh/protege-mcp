package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;

/** Real request exchange fixtures for exercising registry authorization in tool tests. */
final class ToolTestExchange {

    private ToolTestExchange() { }

    static McpSyncServerExchange localAdmin() {
        McpTransportContext context = McpTransportContext.create(Map.of(
                AuthenticatedPrincipal.CONTEXT_KEY, AuthenticatedPrincipal.staticAdmin()));
        return new McpSyncServerExchange(new McpAsyncServerExchange(
                "tool-test-session", null, null, null, context));
    }
}
