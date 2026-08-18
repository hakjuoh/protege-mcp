package io.github.hakjuoh.protege_mcp.tools;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Shared read-only and interactive-confirmation gate for every ontology mutation. */
final class WriteAccessPolicy {
    private WriteAccessPolicy() {}

    static CallToolResult check(ToolContext ctx, String summary) {
        if (ctx.controller().isReadOnly()) {
            return readOnlyDenied();
        }
        if (ctx.controller().isConfirmWrites()) {
            WriteConfirmer confirmer = ctx.confirmer();
            if (confirmer == null || !confirmer.confirm(summary)) {
                return Tools.error("Write declined by the user.");
            }
        }
        return null;
    }

    static CallToolResult readOnlyDenied() {
        return Tools.error(
                "Server is in read-only mode; writes are disabled "
                        + "(toggle in Protégé ▸ Preferences ▸ MCP).");
    }
}
