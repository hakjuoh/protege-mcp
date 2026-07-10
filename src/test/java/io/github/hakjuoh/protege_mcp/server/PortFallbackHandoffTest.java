package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reproduces the second field incident around the single configured port: with the owner election in
 * place, a <em>non-owner</em> window's Ontology Assistant still called {@code controller.start()}
 * directly and died on {@code Failed to bind to /127.0.0.1:8123} ("Could not start Claude: ...") —
 * the same happened for every window of a second Protégé <em>process</em>, whose auto-start lost the
 * bind to the first process. The chat requires a server bound to its own window's ontologies, so
 * re-using the foreign owner is not an option; the fix makes {@code start()} fall back to an
 * ephemeral port ({@code EmbeddedHttpServer#startWithFallback}). This file models that bind
 * semantics with fakes and pins the cross-window consequences in {@link McpServerRegistry}; the real
 * Jetty-level fallback is pinned in {@code EmbeddedHttpServerTest}.
 */
class PortFallbackHandoffTest {

    static final int CONFIGURED_PORT = 8123;

    /** The one configured port: at most one server can hold it at a time. */
    static final class SharedPort {
        FallbackFakeServer owner;
    }

    /**
     * Stand-in for the post-fix {@link McpServerController}: start() claims the configured port when
     * free, otherwise falls back to a distinct ephemeral port instead of throwing.
     */
    static final class FallbackFakeServer implements ManagedServer {
        private static int nextEphemeral = 50_000;

        private final SharedPort port;
        private boolean running;
        private int boundPort;
        int startAttempts;

        FallbackFakeServer(SharedPort port) {
            this.port = port;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void start() {
            startAttempts++;
            if (port.owner == null || port.owner == this) {
                port.owner = this;
                boundPort = CONFIGURED_PORT;
            } else {
                boundPort = ++nextEphemeral; // the configured port is taken — ephemeral fallback
            }
            running = true;
        }

        @Override
        public int getBoundPort() {
            return running ? boundPort : 0;
        }

        @Override
        public int getConfiguredPort() {
            return CONFIGURED_PORT;
        }

        @Override
        public boolean isBrokerManaged() {
            return false; // this file models the standalone (non-broker) mode
        }

        boolean isPortFallback() {
            return running && boundPort != CONFIGURED_PORT;
        }

        void stop() {
            if (port.owner == this) {
                port.owner = null;
            }
            running = false;
            boundPort = 0;
        }
    }

    @Test
    void chatInNonOwnerWindowGetsAFallbackServerInsteadOfABindFailure() {
        SharedPort port = new SharedPort();
        FallbackFakeServer ownerWindow = new FallbackFakeServer(port);
        FallbackFakeServer chatWindow = new FallbackFakeServer(port);

        ownerWindow.start(); // boot auto-start owns the configured port
        chatWindow.start();  // ChatView's lazy start in the second window — pre-fix this threw

        assertTrue(chatWindow.isRunning(), "the chat window must get a working server of its own");
        assertTrue(chatWindow.isPortFallback(), "…on an ephemeral fallback port");
        assertNotEquals(ownerWindow.getBoundPort(), chatWindow.getBoundPort(),
                "the two live servers listen on distinct ports");
        assertTrue(ownerWindow.isRunning(), "the owner is undisturbed");
        assertFalse(ownerWindow.isPortFallback());
    }

    @Test
    void closingTheOwnerHandsTheConfiguredPortToAnIdleWindowNotTheLiveFallbackServer() {
        SharedPort port = new SharedPort();
        FallbackFakeServer ownerWindow = new FallbackFakeServer(port);
        FallbackFakeServer chatWindow = new FallbackFakeServer(port);
        FallbackFakeServer idleWindow = new FallbackFakeServer(port);

        ownerWindow.start();
        chatWindow.start(); // running on a fallback port
        List<ManagedServer> registered = new ArrayList<>(List.of(chatWindow, idleWindow));

        // The owner window closes: McpServerHook.dispose() stops it, then promotes a successor.
        ownerWindow.stop();
        McpServerRegistry.promoteSuccessor(registered);

        assertTrue(idleWindow.isRunning(), "the idle window re-claims the configured port");
        assertSame(idleWindow, port.owner);
        assertEquals(CONFIGURED_PORT, idleWindow.getBoundPort());
        assertEquals(1, chatWindow.startAttempts,
                "the live fallback server is not restarted — its chat session survives the hand-off");
        assertTrue(chatWindow.isPortFallback(), "…and keeps serving on its fallback port");
    }

    @Test
    void openingANewWindowAfterTheOwnerClosedReclaimsTheConfiguredPort() throws Exception {
        // The gap promoteSuccessor alone cannot cover: the owner closes while the ONLY survivor is a
        // live fallback server (nothing idle to promote), so the configured port is briefly unserved.
        // The next window the user opens must re-claim it on auto-start instead of deferring to the
        // fallback server forever.
        SharedPort port = new SharedPort();
        FallbackFakeServer ownerWindow = new FallbackFakeServer(port);
        FallbackFakeServer chatWindow = new FallbackFakeServer(port);

        ownerWindow.start();
        chatWindow.start(); // fallback
        ownerWindow.stop();
        McpServerRegistry.promoteSuccessor(new ArrayList<>(List.of(chatWindow)));
        assertTrue(chatWindow.isPortFallback(), "nothing idle to promote — the port stays unserved");

        FallbackFakeServer newWindow = new FallbackFakeServer(port);
        List<ManagedServer> registered = new ArrayList<>(List.of(chatWindow, newWindow));
        McpServerRegistry.electAndStartIfNoOwner(registered, newWindow);

        assertTrue(newWindow.isRunning(), "the new window must not defer to the fallback server");
        assertEquals(CONFIGURED_PORT, newWindow.getBoundPort(), "…and re-claims the configured port");
        assertSame(newWindow, port.owner);
        assertEquals(1, chatWindow.startAttempts, "the live fallback server is never restarted");
    }

    @Test
    void secondProcessScenarioEveryWindowFallsBackAndStaysAlive() throws Exception {
        // A second Protégé process: from ITS registry's point of view no controller is running, but
        // the OS-level configured port is owned by the first process — modelled by a foreign owner
        // that is not in this registry at all.
        SharedPort port = new SharedPort();
        FallbackFakeServer foreignProcess = new FallbackFakeServer(port);
        foreignProcess.start();

        FallbackFakeServer window = new FallbackFakeServer(port);
        List<ManagedServer> registered = new ArrayList<>(List.of(window));

        // Auto-start in the new process elects this window (no local owner) and start() falls back.
        McpServerRegistry.electAndStartIfNoOwner(registered, window);

        assertTrue(window.isRunning(), "the second process gets a working server");
        assertTrue(window.isPortFallback(), "…on an ephemeral port, since the OS port is foreign-owned");
        assertTrue(foreignProcess.isRunning(), "the first process is undisturbed");
    }
}
