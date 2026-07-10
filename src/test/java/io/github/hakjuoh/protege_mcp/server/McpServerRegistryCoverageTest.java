package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Supplementary branch coverage for {@link McpServerRegistry}'s two pure, package-private election
 * helpers ({@code electAndStartIfNoOwner} and {@code promoteSuccessor(Collection)}).
 *
 * <p>{@link EditorKitSwapHandoffTest} already pins the end-to-end swap/hand-off scenario, the no-op
 * single-window close, and the original bind-failure witness. This file fills the remaining
 * enumerated branches from the plan: empty collections, candidate-in-collection running vs. idle,
 * multiple servers, {@code start()} exception propagation vs. swallowing, ordered fallback across
 * failing successors, and {@code null} entries in the collection. The public wrappers that read the
 * static {@code CONTROLLERS} map take a live {@link org.protege.editor.core.editorkit.EditorKit} and
 * are runtime-only, so they are exercised only indirectly through the delegated helpers.
 */
class McpServerRegistryCoverageTest {

    /**
     * Minimal {@link ManagedServer} whose {@code start()} either succeeds (records running + counts
     * invocations) or always throws, per the {@code failing} flag. No shared-port coupling here —
     * these tests probe the election branches directly rather than the OS bind model. By default a
     * running server models the common case of holding its configured port; the port-taking
     * constructor builds the fallback shape (running with {@code bound != configured}) or the
     * ephemeral-by-choice shape ({@code configured == 0}).
     */
    static final class CountingServer implements ManagedServer {
        private static final int DEFAULT_CONFIGURED_PORT = 8123;

        private boolean running;
        private final boolean failing;
        private final int configuredPort;
        private int boundPort;
        int startAttempts;

        CountingServer(boolean initiallyRunning) {
            this(initiallyRunning, false);
        }

        CountingServer(boolean initiallyRunning, boolean failing) {
            this(initiallyRunning, failing, DEFAULT_CONFIGURED_PORT,
                    initiallyRunning ? DEFAULT_CONFIGURED_PORT : 0);
        }

        CountingServer(boolean initiallyRunning, boolean failing, int configuredPort, int boundPort) {
            this.running = initiallyRunning;
            this.failing = failing;
            this.configuredPort = configuredPort;
            this.boundPort = boundPort;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void start() throws Exception {
            startAttempts++;
            if (failing) {
                throw new IOException("cannot bind (test-forced failure)");
            }
            running = true;
            // A promoted start claims the (now free) configured port, like the real controller.
            boundPort = configuredPort;
        }

        @Override
        public int getBoundPort() {
            return running ? boundPort : 0;
        }

        @Override
        public int getConfiguredPort() {
            return configuredPort;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // electAndStartIfNoOwner(Collection, candidate)
    // ---------------------------------------------------------------------------------------------

    @Test
    void electStartsCandidateWhenCollectionIsEmpty() throws Exception {
        CountingServer candidate = new CountingServer(false);

        McpServerRegistry.electAndStartIfNoOwner(new ArrayList<>(), candidate);

        assertTrue(candidate.isRunning(), "with no registered servers the candidate must bind");
        assertEquals(1, candidate.startAttempts, "candidate.start() should be called exactly once");
    }

    @Test
    void electStartsCandidateWhenNoRegisteredServerIsRunning() throws Exception {
        CountingServer idlePeer = new CountingServer(false);
        CountingServer candidate = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(idlePeer, candidate));

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertTrue(candidate.isRunning(), "no running owner exists, so the candidate must start");
        assertEquals(1, candidate.startAttempts, "candidate should start once");
        assertEquals(0, idlePeer.startAttempts, "election must never start a non-candidate peer");
    }

    @Test
    void electDefersWhenAnotherServerAlreadyOwnsThePort() throws Exception {
        CountingServer owner = new CountingServer(true);
        CountingServer candidate = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(owner, candidate));

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertFalse(candidate.isRunning(), "candidate must defer to the existing owner");
        assertEquals(0, candidate.startAttempts, "candidate.start() must not be called when deferring");
    }

    @Test
    void electDefersWhenAnyOfMultipleServersIsRunning() throws Exception {
        CountingServer idleA = new CountingServer(false);
        CountingServer runningB = new CountingServer(true);
        CountingServer idleC = new CountingServer(false);
        CountingServer candidate = new CountingServer(false);
        List<ManagedServer> registered =
                new ArrayList<>(Arrays.asList(idleA, runningB, idleC, candidate));

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertFalse(candidate.isRunning(), "one running server among many still makes the candidate defer");
        assertEquals(0, candidate.startAttempts, "candidate must not attempt to bind");
    }

    @Test
    void electIgnoresCandidateOwnRunningStateAndStartsWhenNoOtherOwner() throws Exception {
        // The candidate is present in the collection AND already flagged running; the != candidate
        // guard must skip itself, and with no OTHER running server it still calls start() again.
        CountingServer candidate = new CountingServer(true);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(candidate));

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertEquals(1, candidate.startAttempts,
                "the candidate must not count itself as another owner, so start() is invoked");
        assertTrue(candidate.isRunning());
    }

    @Test
    void electWithCandidateInCollectionButIdleStartsWhenNoOtherOwner() throws Exception {
        CountingServer candidate = new CountingServer(false);
        CountingServer idlePeer = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(idlePeer, candidate));

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertTrue(candidate.isRunning(), "self is idle and no other owner, so candidate binds");
        assertEquals(1, candidate.startAttempts);
    }

    @Test
    void electPropagatesExceptionFromCandidateStart() {
        CountingServer candidate = new CountingServer(false, true); // start() throws
        List<ManagedServer> registered = new ArrayList<>();

        IOException thrown = assertThrows(IOException.class,
                () -> McpServerRegistry.electAndStartIfNoOwner(registered, candidate),
                "electAndStartIfNoOwner must let candidate.start() failures propagate");
        assertTrue(thrown.getMessage().contains("cannot bind"), "the original failure must propagate");
        assertEquals(1, candidate.startAttempts, "start() was attempted once before throwing");
    }

    @Test
    void electToleratesNullEntriesWhenComparingAgainstCandidate() throws Exception {
        // A null registered entry must be filtered by the (other != candidate) comparison without an
        // NPE, because isRunning() is short-circuited only when the entry equals the candidate — here
        // null != candidate is true, so isRunning() would be called on null. Guard: put the running
        // owner FIRST so the loop returns before reaching the null (documents the actual branch order).
        CountingServer owner = new CountingServer(true);
        List<ManagedServer> registered = new ArrayList<>();
        registered.add(owner);
        registered.add(null);
        CountingServer candidate = new CountingServer(false);

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertFalse(candidate.isRunning(), "the running owner short-circuits before the null is examined");
        assertEquals(0, candidate.startAttempts);
    }

    // ---------------------------------------------------------------------------------------------
    // promoteSuccessor(Collection)
    // ---------------------------------------------------------------------------------------------

    @Test
    void promoteReturnsImmediatelyWhenAServerIsAlreadyRunning() {
        CountingServer running = new CountingServer(true);
        CountingServer idle = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(running, idle));

        McpServerRegistry.promoteSuccessor(registered);

        assertEquals(0, running.startAttempts, "an already-running server must not be restarted");
        assertEquals(0, idle.startAttempts, "no promotion should occur while someone already serves");
        assertFalse(idle.isRunning());
    }

    @Test
    void promoteReturnsWhenRunningServerAppearsLaterInCollection() {
        // Mixed order: idle first, running second — the first loop must scan the whole collection and
        // find the running server before attempting any start.
        CountingServer idle = new CountingServer(false);
        CountingServer running = new CountingServer(true);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(idle, running));

        McpServerRegistry.promoteSuccessor(registered);

        assertEquals(0, idle.startAttempts, "an idle server must not be started when another serves");
    }

    @Test
    void promoteStartsFirstServerWhenNoneAreRunning() {
        CountingServer first = new CountingServer(false);
        CountingServer second = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(first, second));

        McpServerRegistry.promoteSuccessor(registered);

        assertTrue(first.isRunning(), "the first candidate must take the freed port");
        assertEquals(1, first.startAttempts);
        assertEquals(0, second.startAttempts, "promotion stops after the first successful start");
        assertFalse(second.isRunning());
    }

    @Test
    void promoteFallsThroughToNextServerWhenFirstStartFails() {
        CountingServer failing = new CountingServer(false, true); // start() throws
        CountingServer healthy = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(failing, healthy));

        McpServerRegistry.promoteSuccessor(registered);

        assertEquals(1, failing.startAttempts, "the first candidate is attempted");
        assertFalse(failing.isRunning(), "the failing candidate never comes up");
        assertTrue(healthy.isRunning(), "promotion falls through to the next healthy candidate");
        assertEquals(1, healthy.startAttempts);
    }

    @Test
    void promoteSwallowsExceptionsWhenEveryServerFailsToStart() {
        CountingServer failA = new CountingServer(false, true);
        CountingServer failB = new CountingServer(false, true);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(failA, failB));

        // Per javadoc, start() failures are caught and logged, never propagated.
        McpServerRegistry.promoteSuccessor(registered);

        assertEquals(1, failA.startAttempts, "the first failing candidate is attempted");
        assertEquals(1, failB.startAttempts, "the second failing candidate is also attempted");
        assertFalse(failA.isRunning());
        assertFalse(failB.isRunning());
    }

    @Test
    void promoteOnEmptyCollectionIsANoop() {
        // Both loops iterate zero times; nothing to promote and no error (also covered in the
        // existing test, retained here for the pure-method matrix completeness).
        List<ManagedServer> registered = new ArrayList<>();

        assertDoesNotThrow(() -> McpServerRegistry.promoteSuccessor(registered),
                "promoteSuccessor on an empty collection must be a no-op, not throw");
    }

    @Test
    void promotePrefersEarlierHealthyServerOverLaterFailingOne() {
        CountingServer healthy = new CountingServer(false);
        CountingServer failing = new CountingServer(false, true);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(healthy, failing));

        McpServerRegistry.promoteSuccessor(registered);

        assertTrue(healthy.isRunning(), "the first healthy candidate wins the port");
        assertEquals(1, healthy.startAttempts);
        assertEquals(0, failing.startAttempts, "later candidates are not tried once one succeeds");
    }

    // ---------------------------------------------------------------------------------------------
    // electAndStartIfNoOwner(Collection, candidate) — ephemeral-fallback servers
    // ---------------------------------------------------------------------------------------------

    @Test
    void electStartsCandidateWhenOnlyAFallbackServerIsRunning() throws Exception {
        // A fallback-bound server is not the configured-port owner: a newly opened window must start
        // and re-claim the configured port if it has been freed (it merely falls back itself if not).
        CountingServer runningFallback = new CountingServer(true, false, 8123, 54321);
        CountingServer candidate = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(runningFallback, candidate));

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertTrue(candidate.isRunning(), "the new window must not defer to a fallback-bound server");
        assertEquals(1, candidate.startAttempts);
        assertEquals(8123, candidate.getBoundPort(), "the new window claims the configured port");
        assertEquals(0, runningFallback.startAttempts, "the live fallback server is untouched");
    }

    @Test
    void electStillDefersToAnEphemeralByChoiceRunningServer() throws Exception {
        // configured == 0 means every bind is ephemeral by design — a running server IS the owner.
        CountingServer runningEphemeral = new CountingServer(true, false, 0, 54321);
        CountingServer candidate = new CountingServer(false, false, 0, 0);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(runningEphemeral, candidate));

        McpServerRegistry.electAndStartIfNoOwner(registered, candidate);

        assertFalse(candidate.isRunning(), "ephemeral-by-choice: any running server ends the election");
        assertEquals(0, candidate.startAttempts);
    }

    // ---------------------------------------------------------------------------------------------
    // promoteSuccessor(Collection) — ephemeral-fallback servers (configured port busy at their start)
    // ---------------------------------------------------------------------------------------------

    @Test
    void promoteSkipsRunningFallbackServerAndStartsAnIdleWindowToReclaimConfiguredPort() {
        // Field shape: the chat lazily started this window's server while another window owned the
        // configured port, so it runs on an ephemeral fallback port. When the owner closes, that
        // fallback server must NOT satisfy the hand-off — an idle window re-claims the configured
        // port for external MCP clients, and the live fallback server is left untouched.
        CountingServer runningFallback = new CountingServer(true, false, 8123, 54321);
        CountingServer idle = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(runningFallback, idle));

        McpServerRegistry.promoteSuccessor(registered);

        assertEquals(0, runningFallback.startAttempts, "a live fallback server must never be restarted");
        assertTrue(runningFallback.isRunning(), "the fallback server keeps serving its window's chat");
        assertTrue(idle.isRunning(), "an idle window must be promoted to re-claim the configured port");
        assertEquals(1, idle.startAttempts);
        assertEquals(8123, idle.getBoundPort(), "the promoted window claims the configured port");
    }

    @Test
    void promoteLeavesLoneRunningFallbackServerUntouched() {
        // Only one window remains and its server runs on a fallback port: nothing idle to promote,
        // and the live server must not be restarted out from under an active chat session.
        CountingServer runningFallback = new CountingServer(true, false, 8123, 54321);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(runningFallback));

        McpServerRegistry.promoteSuccessor(registered);

        assertEquals(0, runningFallback.startAttempts, "no restart of the only (live) server");
        assertTrue(runningFallback.isRunning());
        assertEquals(54321, runningFallback.getBoundPort(), "the fallback bind is left as-is");
    }

    @Test
    void promoteTreatsEphemeralByChoiceServerAsSatisfyingTheHandoff() {
        // The user configured port 0 (ephemeral by choice): any running server satisfies the
        // hand-off — there is no fixed port to re-claim, so no idle window may be started.
        CountingServer runningEphemeral = new CountingServer(true, false, 0, 54321);
        CountingServer idle = new CountingServer(false, false, 0, 0);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(runningEphemeral, idle));

        McpServerRegistry.promoteSuccessor(registered);

        assertEquals(0, runningEphemeral.startAttempts);
        assertEquals(0, idle.startAttempts, "ephemeral-by-choice: a running server ends the hand-off");
        assertFalse(idle.isRunning());
    }

    @Test
    void electAndPromoteShareTheSameManagedServerContract() throws Exception {
        // Cross-check: a server started via election is seen as the running owner by a subsequent
        // promotion, so promotion becomes a no-op — the two helpers agree on isRunning() semantics.
        CountingServer owner = new CountingServer(false);
        CountingServer waiting = new CountingServer(false);
        List<ManagedServer> registered = new ArrayList<>(Arrays.asList(owner, waiting));

        McpServerRegistry.electAndStartIfNoOwner(registered, owner);
        assertTrue(owner.isRunning());

        McpServerRegistry.promoteSuccessor(registered);
        assertEquals(0, waiting.startAttempts, "promotion must defer to the just-elected owner");
        assertFalse(waiting.isRunning());
    }
}
