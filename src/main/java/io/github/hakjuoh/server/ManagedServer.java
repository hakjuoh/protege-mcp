package io.github.hakjuoh.server;

/**
 * The slice of a per-window MCP server that {@link McpServerRegistry} needs in order to elect a
 * single owner of the one process-wide port. {@link McpServerController} is the production
 * implementation; tests provide a lightweight fake so the cross-window election logic can be
 * verified without booting Jetty or a Protégé EditorKit.
 */
interface ManagedServer {

    boolean isRunning();

    void start() throws Exception;
}
