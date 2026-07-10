package io.github.hakjuoh.protege_mcp.server;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.protege.editor.core.editorkit.EditorKit;

/**
 * Maps each open {@link EditorKit} (Protégé window) to its {@link McpServerController}, so the
 * status view and preferences panel can reach the server owned by the hook in the same window.
 *
 * <p>The MCP server binds a single process-wide port, but a controller is created per window. The
 * helpers below elect a single port owner across windows so overlapping EditorKit lifecycles — e.g.
 * when Protégé swaps EditorKits to open an ontology into an empty window — never leave the server
 * torn down while a window is still open. The election logic is factored into package-private
 * overloads that take an explicit collection so it can be unit-tested without a live controller.
 */
public final class McpServerRegistry {

    private static final Map<EditorKit, McpServerController> CONTROLLERS = new ConcurrentHashMap<>();

    private McpServerRegistry() {
    }

    public static void register(EditorKit editorKit, McpServerController controller) {
        CONTROLLERS.put(editorKit, controller);
    }

    public static McpServerController get(EditorKit editorKit) {
        return CONTROLLERS.get(editorKit);
    }

    public static void unregister(EditorKit editorKit) {
        CONTROLLERS.remove(editorKit);
    }

    /**
     * Auto-start helper for {@link io.github.hakjuoh.protege_mcp.McpServerHook#initialise()}. Makes auto-start
     * <em>defer</em> to whichever controller already owns the single process-wide port: a freshly
     * opened window stays idle but registered, ready to be promoted by {@link #promoteSuccessor()} if
     * the current owner is later closed — instead of fighting over the bind and being left dead.
     * A server running on an ephemeral <em>fallback</em> port does not count as the owner: the new
     * window starts and re-claims the configured port if it has been freed since (and merely falls
     * back itself if it is still busy, so the candidate can never be left dead).
     *
     * <p>{@code synchronized} so the owner check and the bind are atomic with respect to a concurrent
     * {@link #promoteSuccessor()}; only one controller is ever elected to bind at a time.
     */
    public static synchronized void startIfNoOwner(McpServerController candidate) throws Exception {
        electAndStartIfNoOwner(CONTROLLERS.values(), candidate);
    }

    /**
     * Hand the single process-wide port to another open window after the current owner has stopped.
     * Invoked off the EDT once the owning controller's {@link McpServerController#stop()} has returned
     * (so the OS port is released). Without this, closing the window that happened to bind the port —
     * e.g. when Protégé swaps EditorKits to open a different ontology — tears the server down even
     * though another window is still open and could keep serving.
     */
    public static synchronized void promoteSuccessor() {
        promoteSuccessor(CONTROLLERS.values());
    }

    /** Pure election logic behind {@link #startIfNoOwner(McpServerController)}; unit-tested directly. */
    static void electAndStartIfNoOwner(Collection<? extends ManagedServer> registered, ManagedServer candidate)
            throws Exception {
        for (ManagedServer other : registered) {
            if (other != candidate && other.isRunning() && !other.isBrokerManaged()
                    && (other.getConfiguredPort() == 0 || other.getBoundPort() == other.getConfiguredPort())) {
                return; // a window already holds the configured port (or ephemeral-by-choice) — stay idle
            }
        }
        candidate.start();
    }

    /**
     * Pure hand-off logic behind {@link #promoteSuccessor()}; unit-tested directly.
     *
     * <p>A running server only satisfies the hand-off when it holds its <em>configured</em> port (or
     * the user configured port {@code 0}, ephemeral by choice). A server running on an ephemeral
     * <em>fallback</em> port — started lazily for the chat while another window owned the configured
     * port — does not: an idle window is still promoted so the configured port the user gave to
     * external MCP clients gets re-claimed. Running servers are never restarted onto the freed port,
     * because an in-flight chat turn may be streaming through them.
     */
    static void promoteSuccessor(Collection<? extends ManagedServer> registered) {
        for (ManagedServer s : registered) {
            if (s.isRunning() && !s.isBrokerManaged()
                    && (s.getConfiguredPort() == 0 || s.getBoundPort() == s.getConfiguredPort())) {
                return; // the configured port is already served (or ephemeral-by-choice) — done
            }
        }
        for (ManagedServer s : registered) {
            if (s.isRunning()) {
                continue; // never restart a live fallback server out from under an active chat
            }
            try {
                s.start();
                return;
            } catch (Exception e) {
                // This server could not take the port (still releasing, or unhealthy); try the next
                // candidate. start() has already logged the failure.
            }
        }
    }
}
