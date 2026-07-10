package io.github.hakjuoh.protege_mcp.server;

/**
 * The slice of a per-window MCP server that {@link McpServerRegistry} needs in order to elect a
 * single owner of the one process-wide port. {@link McpServerController} is the production
 * implementation; tests provide a lightweight fake so the cross-window election logic can be
 * verified without booting Jetty or a Protégé EditorKit.
 */
interface ManagedServer {

    boolean isRunning();

    void start() throws Exception;

    /** The port this server is actually listening on ({@code 0} when stopped). */
    int getBoundPort();

    /** The port the user configured at the time this server started ({@code 0} = ephemeral by choice). */
    int getConfiguredPort();
}
