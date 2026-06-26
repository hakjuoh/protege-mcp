package org.protege.mcp.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reproduces the field incident where opening an ontology into the empty launch window took the MCP
 * server down, and pins the {@link McpServerRegistry} hand-off that fixes it.
 *
 * <p>Field timeline (Protégé log): the launch window auto-started and bound port 8123; opening
 * {@code mnist-mlp.rdf} into that empty window made Protégé swap EditorKits, so a <em>second</em>
 * controller auto-started while the first still owned the port → {@code Failed to bind to
 * 127.0.0.1:8123}; the original (empty) EditorKit was then disposed and stopped its server, leaving
 * nothing listening. The fix: a new window defers to the current owner ({@code startIfNoOwner}) and
 * the port is handed to a surviving window when the owner is disposed ({@code promoteSuccessor}).
 *
 * <p>The single process-wide port is modelled by {@link SharedPort}; {@link PortExclusivityTest}
 * confirms that model matches real OS bind semantics.
 */
class EditorKitSwapHandoffTest {

    /** The one process-wide port: at most one server can hold it at a time. */
    static final class SharedPort {
        ManagedServer owner;
    }

    /** Stand-in for {@link McpServerController}: start() collides like Jetty when the port is taken. */
    static final class FakeServer implements ManagedServer {
        private final SharedPort port;
        private boolean running;

        FakeServer(SharedPort port) {
            this.port = port;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void start() throws Exception {
            if (port.owner != null && port.owner != this) {
                throw new IOException("Address already in use"); // mirrors the Jetty bind failure
            }
            port.owner = this;
            running = true;
        }

        void stop() {
            if (port.owner == this) {
                port.owner = null;
            }
            running = false;
        }
    }

    @Test
    void serverSurvivesEditorKitSwapWhenOpeningOntologyIntoEmptyWindow() throws Exception {
        SharedPort port = new SharedPort();
        FakeServer launchWindow = new FakeServer(port); // empty untitled-ontology window from boot
        FakeServer openedWindow = new FakeServer(port); // new EditorKit created to host mnist-mlp.rdf
        List<ManagedServer> registered = new ArrayList<>();

        // 1) Boot: the launch window auto-starts and binds the port.
        registered.add(launchWindow);
        McpServerRegistry.electAndStartIfNoOwner(registered, launchWindow);
        assertTrue(launchWindow.isRunning(), "boot auto-start should bind the port");

        // 2) Opening the ontology spins up a new EditorKit that auto-starts WHILE the launch window
        //    still owns the port. Pre-fix this threw BindException and gave up; now it must defer.
        registered.add(openedWindow);
        assertDoesNotThrow(() -> McpServerRegistry.electAndStartIfNoOwner(registered, openedWindow),
                "the second window must defer to the owner, not fight over the bind");
        assertFalse(openedWindow.isRunning(), "second window stays idle while the first owns the port");
        assertTrue(launchWindow.isRunning(), "the first window keeps serving across the swap");

        // 3) The empty launch EditorKit is disposed: unregister, stop, then hand the port off.
        registered.remove(launchWindow);
        launchWindow.stop();
        McpServerRegistry.promoteSuccessor(registered);

        // 4) The server survived the swap — the surviving window now owns the port, no outage.
        assertTrue(openedWindow.isRunning(), "the port must be handed to the surviving window");
        assertSame(openedWindow, port.owner);
    }

    @Test
    void unconditionalStartDuringSwapReproducesTheOriginalBindFailure() throws Exception {
        // Witness for the original defect: the old McpServerHook.initialise() called controller.start()
        // with no owner check, so the second window's start collided exactly like the field log.
        SharedPort port = new SharedPort();
        FakeServer owner = new FakeServer(port);
        FakeServer secondWindow = new FakeServer(port);

        owner.start();
        IOException bind = assertThrows(IOException.class, secondWindow::start);
        assertTrue(bind.getMessage().contains("Address already in use"));
        assertTrue(owner.isRunning());
        assertFalse(secondWindow.isRunning());
    }

    @Test
    void deferringSecondWindowLetsItTakeOverOnlyAfterTheOwnerReleasesThePort() throws Exception {
        SharedPort port = new SharedPort();
        FakeServer owner = new FakeServer(port);
        FakeServer waiting = new FakeServer(port);
        List<ManagedServer> registered = new ArrayList<>(List.of(owner, waiting));

        McpServerRegistry.electAndStartIfNoOwner(registered, owner);

        // While the owner holds the port, promotion is a no-op (someone already serves).
        McpServerRegistry.promoteSuccessor(registered);
        assertSame(owner, port.owner);
        assertFalse(waiting.isRunning());

        // Once the owner releases it, the next promotion succeeds.
        registered.remove(owner);
        owner.stop();
        McpServerRegistry.promoteSuccessor(registered);
        assertTrue(waiting.isRunning());
    }

    @Test
    void promoteSuccessorWithNoOtherWindowIsANoop() {
        // The common single-window close: nothing left to promote, and no error.
        assertDoesNotThrow(() -> McpServerRegistry.promoteSuccessor(new ArrayList<>()));
    }
}
