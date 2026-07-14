package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

/**
 * The broker's reference count and routing brain, driven by an injected clock: registration /
 * heartbeat / unregister / reaping, the idle-exit decision the user's lifecycle spec hinges on, the
 * default-window selection and MCP session pinning.
 */
class InstanceRegistryTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final InstanceRegistry registry = new InstanceRegistry(now::get);

    private static InstanceRegistry.Window window(String id, int port, long focusedAt, long registeredAt) {
        return new InstanceRegistry.Window(id, port, "secret-" + id, "title-" + id, focusedAt, registeredAt);
    }

    // ---- reference count ------------------------------------------------------------------------

    @Test
    void registerHeartbeatUnregisterDriveTheReferenceCount() {
        assertEquals(0, registry.processCount());
        String p1 = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        String p2 = registry.register(22, "1.0", "tok", List.of(window("w2", 5002, 2, 2)));
        assertEquals(2, registry.processCount());
        assertEquals(2, registry.windowCount());

        assertTrue(registry.heartbeat(p1, "tok", List.of(window("w1", 5001, 1, 1))));
        registry.unregister(p1);
        assertEquals(1, registry.processCount());
        registry.unregister(p2);
        assertEquals(0, registry.processCount());
    }

    @Test
    void heartbeatForUnknownProcessSaysReRegister() {
        assertFalse(registry.heartbeat("no-such-process", "tok", List.of()),
                "a broker that lost the process (restart) must tell the instance to re-register");
    }

    @Test
    void reapDropsStaleAndDeadPidProcesses() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        String fresh = registry.register(22, "1.0", "tok", List.of(window("w2", 5002, 2, 2)));

        now.addAndGet(10_000); // first process never heartbeats again
        registry.heartbeat(fresh, "tok", List.of(window("w2", 5002, 2, 2)));

        assertEquals(1, registry.reap(8_000, pid -> true), "stale heartbeat must be reaped");
        assertEquals(1, registry.processCount());

        assertEquals(1, registry.reap(8_000, pid -> false), "a dead OS pid must be reaped regardless");
        assertEquals(0, registry.processCount());
    }

    // ---- idle exit (the broker's self-termination) ------------------------------------------------

    @Test
    void shouldExitAfterLingerOnceLastInstanceLeaves() {
        String p = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        assertFalse(registry.shouldExit(15_000, 60_000), "non-empty registry never exits");

        registry.unregister(p);
        assertFalse(registry.shouldExit(15_000, 60_000), "within the linger the broker waits");

        now.addAndGet(15_001);
        assertTrue(registry.shouldExit(15_000, 60_000),
                "no referencing instance past the linger — the broker must exit");
    }

    @Test
    void shouldExitAfterBootGraceWhenNothingEverRegistered() {
        assertFalse(registry.shouldExit(15_000, 60_000));
        now.addAndGet(60_001);
        assertTrue(registry.shouldExit(15_000, 60_000),
                "an orphan broker that never saw a registration must not linger forever");
    }

    @Test
    void reRegistrationCancelsAPendingIdleExit() {
        String p = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.unregister(p);
        now.addAndGet(10_000);
        registry.register(33, "1.0", "tok", List.of(window("w3", 5003, 3, 3)));
        now.addAndGet(60_000);
        assertFalse(registry.shouldExit(15_000, 60_000), "a new instance keeps the broker alive");
    }

    // ---- requested linger (the user's preference, carried per register/heartbeat) ------------------

    @Test
    void effectiveLingerIsTheDefaultUntilAnInstanceReportsOne() {
        assertEquals(15_000, registry.effectiveLingerMs(15_000),
                "before any report the spawn-time default applies");
    }

    @Test
    void noteRequestedLingerOverridesTheDefaultIncludingZero() {
        registry.noteRequestedLinger(60_000);
        assertEquals(60_000, registry.effectiveLingerMs(15_000));
        registry.noteRequestedLinger(0);
        assertEquals(0, registry.effectiveLingerMs(15_000),
                "0 (exit immediately on last disconnect) is a legitimate reported value");
    }

    @Test
    void noteRequestedLingerIgnoresNegativesAndCapsOversizedValues() {
        registry.noteRequestedLinger(30_000);
        registry.noteRequestedLinger(-1);
        assertEquals(30_000, registry.effectiveLingerMs(15_000),
                "a payload without a linger (older plugin, -1) must not clobber the last report");
        registry.noteRequestedLinger(Long.MAX_VALUE);
        assertEquals(InstanceRegistry.MAX_REQUESTED_LINGER_MS, registry.effectiveLingerMs(15_000),
                "a corrupt/oversized report must not pin the broker forever");
    }

    @Test
    void requestedLingerSurvivesTheRegistryEmptyingAndDrivesTheExit() {
        String p = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.noteRequestedLinger(0);
        registry.unregister(p);
        // The linger matters exactly now, after the last unregister — the reported value must
        // still be in force even though no process remains to re-report it.
        assertTrue(registry.shouldExit(registry.effectiveLingerMs(15_000), 60_000),
                "with a reported linger of 0 the broker exits on the first tick after emptying");
    }

    // ---- routing ----------------------------------------------------------------------------------

    @Test
    void defaultWindowPrefersMostRecentFocusThenRegistration() {
        registry.register(11, "1.0", "tok", List.of(window("older", 5001, 100, 1)));
        registry.register(22, "1.0", "tok", List.of(window("focused", 5002, 900, 2)));
        assertEquals("focused", registry.defaultWindow().orElseThrow().id);

        registry.register(33, "1.0", "tok", List.of(window("tie-newer", 5003, 900, 5)));
        assertEquals("tie-newer", registry.defaultWindow().orElseThrow().id,
                "equal focus falls back to the most recently registered window");
    }

    @Test
    void sessionPinningRoutesToThePinnedWindowUntilItDisappears() {
        String p1 = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.register(22, "1.0", "tok", List.of(window("w2", 5002, 999, 2)));

        registry.pinSession("sess-A", "w1");
        assertEquals("w1", registry.windowForSession("sess-A").orElseThrow().id,
                "a pinned session must not follow the default-window selection");

        registry.unregister(p1);
        assertTrue(registry.windowForSession("sess-A").isEmpty(),
                "a session pinned to a vanished window resolves to empty (client re-initializes)");
    }

    @Test
    void unpinSessionForgetsThePin() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.pinSession("sess-A", "w1");
        registry.unpinSession("sess-A");
        assertTrue(registry.windowForSession("sess-A").isEmpty());
    }

    @Test
    void principalBoundSessionRejectsCrossClientReplayAndRevocationDropsPins() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        AuthenticatedPrincipal owner = AuthenticatedPrincipal.oauthAdmin("client-a", "A", "grant-a");
        AuthenticatedPrincipal attacker = AuthenticatedPrincipal.oauthAdmin("client-b", "B", "grant-b");
        registry.pinSession("principal-session", "w1", owner);
        assertTrue(registry.sessionOwnedBy("principal-session", owner));
        assertFalse(registry.sessionOwnedBy("principal-session", attacker));
        assertEquals(1, registry.dropSessionsForPrincipal("client-a"));
        assertTrue(registry.windowForSession("principal-session").isEmpty());
    }

    @Test
    void latestTokenComesFromTheMostRecentlySeenProcess() {
        String p1 = registry.register(11, "1.0", "tok-old", List.of(window("w1", 5001, 1, 1)));
        now.addAndGet(5);
        registry.register(22, "1.0", "tok-new", List.of(window("w2", 5002, 2, 2)));
        assertEquals("tok-new", registry.latestToken());

        now.addAndGet(5);
        registry.heartbeat(p1, "tok-regenerated", List.of(window("w1", 5001, 1, 1)));
        assertEquals("tok-regenerated", registry.latestToken(),
                "a heartbeat carrying a regenerated token must win immediately");
    }

    @Test
    void matchesAnyTokenAcceptsEveryRegisteredProcessToken() {
        registry.register(11, "1.0", "tok-a", List.of(window("w1", 5001, 1, 1)));
        registry.register(22, "1.0", "tok-b", List.of(window("w2", 5002, 2, 2)));
        assertTrue(registry.matchesAnyToken("tok-a"),
                "during a token-regeneration propagation both current tokens must authenticate");
        assertTrue(registry.matchesAnyToken("tok-b"));
        assertFalse(registry.matchesAnyToken("tok-unknown"));
        assertFalse(registry.matchesAnyToken(null));
        assertFalse(registry.matchesAnyToken(""));
    }

    @Test
    void listWindowsIsNewestFirstAcrossProcesses() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 10)));
        registry.register(22, "1.0", "tok", List.of(window("w2", 5002, 2, 20)));
        List<InstanceRegistry.Window> all = registry.listWindows();
        assertEquals(2, all.size());
        assertEquals("w2", all.get(0).id);
    }

    @Test
    void emptyRegistryHasNoDefaultWindow() {
        assertEquals(Optional.empty(), registry.defaultWindow());
    }
}
