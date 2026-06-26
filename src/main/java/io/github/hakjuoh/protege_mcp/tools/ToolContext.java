package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;

/**
 * Everything a tool handler needs: the EDT-marshalling {@link OntologyAccess} and the owning
 * {@link McpServerController} (for live read-only / write-confirmation gates).
 */
public final class ToolContext {

    private final OntologyAccess access;
    private final McpServerController controller;

    public ToolContext(OntologyAccess access, McpServerController controller) {
        this.access = access;
        this.controller = controller;
    }

    public OntologyAccess access() {
        return access;
    }

    public McpServerController controller() {
        return controller;
    }
}
