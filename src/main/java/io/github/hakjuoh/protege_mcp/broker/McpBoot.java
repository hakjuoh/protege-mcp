package io.github.hakjuoh.protege_mcp.broker;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.McpServerRegistry;

/**
 * The one place that decides how a window's MCP server comes up: broker-first (attach this window as
 * an ephemeral backend of the shared cross-process broker, spawning the broker when this is the
 * first instance), degrading to the standalone per-process mode — the owner election plus
 * ephemeral-port fallback — whenever the broker preference is off or no broker can be reached or
 * spawned. Used by the auto-start hook and by the chat's lazy start so the two paths cannot drift.
 */
public final class McpBoot {

    private McpBoot() {
    }

    /** Auto-start path ({@code McpServerHook}): broker-first, then the standalone election. */
    public static void autoStart(McpServerController controller) throws Exception {
        if (McpConfig.load().isSharedBroker() && BrokerLink.get().attach(controller)) {
            return;
        }
        McpServerRegistry.startIfNoOwner(controller);
    }

    /**
     * Chat path: this window needs a running server NOW (the chat's CLI connects back into it).
     * Broker-first for consistency; the standalone start below falls back to an ephemeral port by
     * itself when the configured port is busy, so this never leaves the chat without a server for
     * a merely-busy port.
     *
     * <p>Except when the user said no: a server the user explicitly stopped (the view's Stop button)
     * is never restarted behind their back — this throws instead, with a message that names the way
     * back (Start). Broker failures never take this branch; they degrade to the standalone start.
     */
    public static void ensureStarted(McpServerController controller) throws Exception {
        if (controller.isRunning()) {
            return;
        }
        if (controller.isUserStopped()) {
            throw new IllegalStateException("the MCP server in this window was stopped with its Stop "
                    + "button — press Start in the MCP Server view to use the assistant again");
        }
        if (McpConfig.load().isSharedBroker() && BrokerLink.get().attach(controller)) {
            return;
        }
        controller.start();
    }
}
