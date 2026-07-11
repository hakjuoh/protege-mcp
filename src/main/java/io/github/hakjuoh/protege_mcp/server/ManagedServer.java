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

    /**
     * True while running as a backend of the shared broker. Such a server never represents the
     * user-facing configured port, so it must satisfy neither the standalone owner election nor the
     * close-time hand-off — otherwise a broker backend would suppress the standalone fallback.
     */
    boolean isBrokerManaged();

    /**
     * True while the user has explicitly stopped this server (the view's Stop button) and has not
     * started it again. A latched server must never be started on the user's behalf — not by the
     * close-time hand-off and not by the chat's lazy start; only an explicit Start clears the latch.
     * Default false: only the production controller carries the latch.
     */
    default boolean isUserStopped() {
        return false;
    }
}
