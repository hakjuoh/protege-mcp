package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    @Test
    void staleButLiveProcessPreventsQuiescentCompactionUntilItsPidDies() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        now.addAndGet(10_000);
        assertEquals(1, registry.reap(8_000, pid -> true));
        assertEquals(0, registry.processCount());
        assertTrue(registry.listWindows().isEmpty());
        assertEquals(List.of("w1"), registry.revocationWindows().stream()
                .map(window -> window.id).toList());
        now.addAndGet(120_000);
        assertFalse(registry.shouldExit(0, 0));
        assertFalse(registry.sealIfShouldExit(0, 0));

        registry.reap(8_000, pid -> false);
        assertTrue(registry.revocationWindows().isEmpty());
        assertTrue(registry.shouldExit(0, 0));
        assertTrue(registry.sealIfShouldExit(0, 0));
    }

    @Test
    void unregisterQuarantinesRevocationEndpointsUntilPidDeath() {
        String processId = registry.register(11, "1.0", "tok",
                List.of(window("w1", 5001, 1, 1)));

        registry.unregister(processId);

        assertEquals(0, registry.processCount());
        assertTrue(registry.listWindows().isEmpty());
        assertEquals(List.of("w1"), registry.revocationWindows().stream()
                .map(window -> window.id).toList());
        now.addAndGet(120_000);
        assertFalse(registry.shouldExit(0, 0));

        registry.reap(8_000, pid -> false);
        assertTrue(registry.revocationWindows().isEmpty());
        assertTrue(registry.shouldExit(0, 0));
    }

    @Test
    void heartbeatRetainsRemovedAndReplacedEndpointIncarnationsForRevocationOnly() {
        String processId = registry.register(11, "1.0", "tok", List.of(
                window("removed", 5001, 1, 1),
                new InstanceRegistry.Window("changed", 5002, "old-secret", "old", 2, 2)));

        assertTrue(registry.heartbeat(processId, "tok", List.of(
                new InstanceRegistry.Window("changed", 5003, "new-secret", "new", 3, 3))));

        assertEquals(List.of("changed"), registry.listWindows().stream()
                .map(window -> window.id).toList());
        assertEquals(3, registry.revocationWindows().size());
        assertEquals(1, registry.revocationWindows().stream()
                .filter(window -> window.port == 5001).count());
        assertEquals(1, registry.revocationWindows().stream()
                .filter(window -> "old-secret".equals(window.secret)).count());
        assertEquals(1, registry.revocationWindows().stream()
                .filter(window -> "new-secret".equals(window.secret)).count());
    }

    @Test
    void samePidReregistrationDoesNotDiscardRetiredEndpoints() {
        String first = registry.register(11, "1.0", "tok",
                List.of(window("old", 5001, 1, 1)));
        registry.unregister(first);

        String second = registry.register(11, "1.0", "tok", List.of());

        assertEquals(List.of("old"), registry.revocationWindows().stream()
                .map(window -> window.id).toList());
        assertFalse(registry.shutdownEligible());
        registry.unregister(second);
        registry.reap(8_000, pid -> false);
        assertTrue(registry.revocationWindows().isEmpty());
        assertTrue(registry.shutdownEligible());
    }

    @Test
    void retiredEndpointCapacityFailsClosedWithoutReplacingActiveRouting() {
        String processId = registry.register(11, "1.0", "tok", List.of());
        for (int batch = 0; batch < InstanceRegistry.MAX_PROCESSES; batch++) {
            List<InstanceRegistry.Window> current = new java.util.ArrayList<>();
            for (int index = 0; index < InstanceRegistry.MAX_WINDOWS_PER_PROCESS; index++) {
                int ordinal = batch * InstanceRegistry.MAX_WINDOWS_PER_PROCESS + index;
                current.add(window("window-" + ordinal, 10_000 + ordinal, ordinal, ordinal));
            }
            assertTrue(registry.heartbeat(processId, "tok", current));
            assertTrue(registry.heartbeat(processId, "tok", List.of()));
        }
        assertEquals(InstanceRegistry.MAX_QUARANTINED_WINDOWS,
                registry.revocationWindows().size());
        InstanceRegistry.Window retained = window("retained", 9_999, 1, 1);
        assertTrue(registry.heartbeat(processId, "tok", List.of(retained)));

        assertThrows(IllegalStateException.class,
                () -> registry.heartbeat(processId, "tok", List.of()));
        assertEquals(List.of("retained"), registry.listWindows().stream()
                .map(window -> window.id).toList());
    }

    @Test
    void aPayloadThatWouldLoseAWellFormedEndpointIsRefusedWhole() {
        // A dropped endpoint is one the broker can neither route to nor fence, while a revocation would
        // report every window acknowledged - so neither a second entry reusing an id nor a list past the
        // bound may be absorbed silently. Refusing costs nothing: the payload is indexed before anything
        // is retired or replaced, so the registry keeps exactly the window set it already had.
        List<InstanceRegistry.Window> collapsing = List.of(
                new InstanceRegistry.Window("dup", 5001, "secret-old", "first", 1, 1),
                new InstanceRegistry.Window("dup", 5002, "secret-new", "second", 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(11, "1.0", "tok", collapsing));
        assertEquals(0, registry.processCount(), "the refused registration left nothing behind");

        String processId = registry.register(11, "1.0", "tok", List.of(window("kept", 5001, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.heartbeat(processId, "tok", collapsing));
        List<InstanceRegistry.Window> overBound = new java.util.ArrayList<>();
        for (int index = 0; index <= InstanceRegistry.MAX_WINDOWS_PER_PROCESS; index++) {
            overBound.add(window("window-" + index, 10_000 + index, index, index));
        }
        assertThrows(IllegalArgumentException.class,
                () -> registry.heartbeat(processId, "tok", overBound));

        assertEquals(List.of("kept"), registry.listWindows().stream().map(w -> w.id).toList(),
                "a refused heartbeat neither replaces nor retires the reported windows");
        assertEquals(1, registry.revocationWindows().size(), "and invents no extra obligation");
    }

    @Test
    void aWindowIdAnotherLiveProcessHoldsIsRefusedToo() {
        // A window id names one endpoint for the whole broker: windowById answers with the first process
        // that has it, so accepting a collision would make routing - and every session pinned by id -
        // depend on map order, and /instances would advertise two endpoints under one path. Protege
        // windows carry random ids, so this is something else talking to the broker.
        String first = registry.register(11, "1.0", "tok", List.of(window("shared", 5001, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(22, "1.0", "tok", List.of(window("shared", 5002, 2, 2))));
        assertEquals(1, registry.processCount(), "the refused registration left nothing behind");
        assertEquals(Optional.of(5001),
                registry.windowById("shared").map(window -> window.port),
                "the endpoint that had the id keeps it");

        String other = registry.register(22, "1.0", "tok", List.of(window("its-own", 5002, 2, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.heartbeat(other, "tok", List.of(window("shared", 5002, 2, 2))));
        assertEquals(java.util.Set.of("shared", "its-own"), registry.listWindows().stream()
                .map(window -> window.id).collect(java.util.stream.Collectors.toSet()),
                "a refused heartbeat leaves both processes' window sets as they were");

        // The same id from the same process is that process re-reporting its own window, not a collision.
        assertTrue(registry.heartbeat(first, "tok", List.of(window("shared", 5001, 1, 1))));
        assertEquals(2, registry.windowCount());
    }

    @Test
    void totalProcessCapacityBoundsAggregateWindowsAndUncertainPids() {
        for (int index = 0; index < InstanceRegistry.MAX_PROCESSES; index++) {
            registry.register(1_000 + index, "1.0", "tok", List.of());
        }
        assertThrows(IllegalStateException.class,
                () -> registry.register(9_999, "1.0", "tok", List.of()));

        now.addAndGet(10_000);
        registry.reap(8_000, pid -> true);
        assertEquals(0, registry.processCount());
        assertThrows(IllegalStateException.class,
                () -> registry.register(9_999, "1.0", "tok", List.of()));
    }

    // ---- idle exit (the broker's self-termination) ------------------------------------------------

    @Test
    void shouldExitAfterLingerOnceLastInstanceLeaves() {
        String p = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        assertFalse(registry.shouldExit(15_000, 60_000), "non-empty registry never exits");

        registry.unregister(p);
        assertFalse(registry.shouldExit(15_000, 60_000), "within the linger the broker waits");
        registry.reap(8_000, pid -> false);

        now.addAndGet(15_001);
        assertTrue(registry.shouldExit(15_000, 60_000),
                "no referencing instance past the linger - the broker must exit");
    }

    @Test
    void shouldExitAfterBootGraceWhenNothingEverRegistered() {
        assertFalse(registry.shouldExit(15_000, 60_000));
        now.addAndGet(60_001);
        assertTrue(registry.shouldExit(15_000, 60_000),
                "an orphan broker that never saw a registration must not linger forever");
    }

    @Test
    void quiescentShutdownSealRejectsLateRegistration() {
        now.addAndGet(60_001);
        assertTrue(registry.sealIfShouldExit(15_000, 60_000));
        assertThrows(IllegalStateException.class,
                () -> registry.register(11, "1.0", "tok", List.of()));
        assertFalse(registry.heartbeat("missing", "tok", List.of()));
    }

    @Test
    void failedQuiescentCompactionDoesNotSealRegistration() {
        now.addAndGet(60_001);
        assertThrows(IllegalStateException.class,
                () -> registry.sealIfShouldExit(15_000, 60_000,
                        () -> { throw new IllegalStateException("journal not durable"); }));
        assertTrue(registry.register(11, "1.0", "tok", List.of()).length() > 0,
                "a failed compaction must leave registration open");
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
        registry.reap(8_000, pid -> false);
        // The linger matters exactly now, after the last unregister - the reported value must
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
        registry.pinSession("late-principal-session", "w1", owner);
        assertTrue(registry.windowForSession("late-principal-session").isEmpty(),
                "a response arriving after client revocation cannot recreate its pin");
    }

    @Test
    void grantRevocationDropsOnlyMatchingSessionPins() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.pinSession("session-a", "w1",
                AuthenticatedPrincipal.oauthAdmin("client", "Client", "grant-a"));
        registry.pinSession("session-b", "w1",
                AuthenticatedPrincipal.oauthAdmin("client", "Client", "grant-b"));

        assertEquals(1, registry.dropSessionsForGrant("client", "grant-a"));
        assertTrue(registry.windowForSession("session-a").isEmpty());
        assertTrue(registry.windowForSession("session-b").isPresent());
        registry.pinSession("late-session-a", "w1",
                AuthenticatedPrincipal.oauthAdmin("client", "Client", "grant-a"));
        registry.pinSession("late-session-b", "w1",
                AuthenticatedPrincipal.oauthAdmin("client", "Client", "grant-b"));
        assertTrue(registry.windowForSession("late-session-a").isEmpty(),
                "a response arriving after revocation cannot recreate the exact grant pin");
        assertTrue(registry.windowForSession("late-session-b").isPresent());
    }

    @Test
    void sessionRevocationTombstonesHaveAFailClosedCapacityBound() {
        for (int index = 0; index < 2_048; index++) {
            registry.prepareGrantRevocation("client", "grant-" + index);
        }
        registry.prepareGrantRevocation("client", "grant-0");
        assertThrows(IllegalStateException.class,
                () -> registry.prepareClientRevocation("another-client"));
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

    // ---- recoverable reaps and same-pid re-registration -------------------------------------------

    @Test
    void aStaleReapKeepsTheSessionPinnedToAWindowThatCanStillComeBack() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.pinSession("sess-A", "w1");
        now.addAndGet(10_000);

        assertEquals(1, registry.reap(8_000, pid -> true), "a stalled heartbeat is still reaped");
        assertTrue(registry.windowForSession("sess-A").isEmpty(),
                "while the window is only quarantined nothing routes to it");

        // The instance is alive, so its very next beat re-registers the same window id.
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        assertEquals("w1", registry.windowForSession("sess-A").orElseThrow().id,
                "a transient heartbeat gap must not cost a live client its MCP session");
    }

    @Test
    void aSessionIsForgottenOnceItsWindowsProcessIsGoneForGood() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.pinSession("sess-A", "w1");
        now.addAndGet(10_000);
        registry.reap(8_000, pid -> true);

        registry.reap(8_000, pid -> false);
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        assertTrue(registry.windowForSession("sess-A").isEmpty(),
                "a pin whose owning process died must not be reused by a window id that recurs");
    }

    @Test
    void anEvictedQuarantineDropsItsSessionPinsWithNoProcessBeingReaped() {
        String processId = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.pinSession("sess-A", "w1");
        registry.unregister(processId);

        assertEquals(0, registry.reap(8_000, pid -> false),
                "nothing is left in the active table to reap");
        assertTrue(registry.revocationWindows().isEmpty());
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        assertTrue(registry.windowForSession("sess-A").isEmpty(),
                "the pin must go with the quarantine eviction, not wait for a process reap");
    }

    @Test
    void reRegisteringTheSamePidReplacesItsOwnEarlierRegistration() {
        String first = registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        String second = registry.register(11, "1.0", "tok", List.of(window("w2", 5002, 2, 2)));

        assertNotEquals(first, second);
        assertEquals(1, registry.processCount(),
                "a process that registers again must not leave its previous registration active");
        assertEquals(List.of("w2"), registry.listWindows().stream()
                .map(window -> window.id).toList());
        assertTrue(registry.windowById("w1").isEmpty(),
                "routing must stop resolving a window only the superseded registration knew");
        assertEquals(List.of("w1"), registry.revocationWindows().stream()
                .map(window -> window.id).filter("w1"::equals).toList(),
                "the dropped endpoint stays reachable for revocation while the pid lives");
        assertFalse(registry.heartbeat(first, "tok", List.of()),
                "the superseded handle must be told to re-register");
        assertTrue(registry.heartbeat(second, "tok", List.of(window("w2", 5002, 2, 2))));
    }

    @Test
    void repeatedSamePidRegistrationNeverExhaustsProcessCapacity() {
        for (int index = 0; index <= InstanceRegistry.MAX_PROCESSES; index++) {
            registry.register(11, "1.0", "tok", List.of());
        }
        assertEquals(1, registry.processCount());
        assertEquals(1, registry.retainedProcessCount(),
                "one restarting instance must not spend the whole machine's capacity");
    }

    @Test
    void registrationWithoutAnOsPidIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(0, "1.0", "tok", List.of()),
                "a pid of 0 can never be proven dead, so it would pin the broker open forever");
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(-1, "1.0", "tok", List.of()));
        assertEquals(0, registry.processCount());
        assertEquals(0, registry.retainedProcessCount());
    }

    // ---- quarantine retention ---------------------------------------------------------------------

    @Test
    void aQuarantineIsHeldForItsWholeRetentionWindow() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        now.addAndGet(10_000);
        assertEquals(1, registry.reap(8_000, pid -> true),
                "a stalled instance whose pid still lives is quarantined, not forgotten");

        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS - 10_000);
        assertEquals(0, registry.reap(8_000, pid -> true));
        assertEquals(1, registry.retainedProcessCount(),
                "up to the bound a returning instance's token must still be revocable");
        assertFalse(registry.revocationWindows().isEmpty());
        assertFalse(registry.shouldExit(0, 60_000),
                "the broker stays up while it still owes a revocation");
    }

    @Test
    void aQuarantineHeldOpenByAReusedPidEventuallyExpires() {
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.pinSession("sess-A", "w1");
        now.addAndGet(10_000);
        registry.reap(8_000, pid -> true);

        // The instance is long dead but the kernel handed its pid to some other process, so the
        // liveness probe can never turn false again. Only the age bound can release the quarantine.
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        assertEquals(0, registry.reap(8_000, pid -> true), "nothing active is left to reap");

        assertEquals(0, registry.retainedProcessCount(),
                "a reused pid must not pin the quarantine for the life of the machine");
        assertTrue(registry.revocationWindows().isEmpty(),
                "the expired endpoint stops being carried for revocation too");
        assertTrue(registry.windowForSession("sess-A").isEmpty(),
                "its orphaned session pin goes with it");
        assertTrue(registry.shouldExit(0, 60_000),
                "the broker can finally honour idle exit");
    }

    @Test
    void anEndpointRetiredAgainStartsItsRetentionAgain() {
        // A window closed and reopened on the same port and secret is retired, live, then retired once
        // more. Retention has to run from the latest retirement: dated from the first one, an endpoint
        // that just stopped serving would expire immediately and lose its revocation fence.
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        String handle = registry.register(11, "1.0", "tok", List.of());
        assertEquals(1, registry.revocationWindows().size(), "retired once");

        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS - 1_000);
        assertTrue(registry.heartbeat(handle, "tok", List.of(window("w1", 5001, 1, 1))),
                "the endpoint is serving again");
        assertTrue(registry.heartbeat(handle, "tok", List.of()), "and is retired a second time");

        now.addAndGet(2_000);
        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS, pid -> true);
        assertEquals(1, registry.revocationWindows().size(),
                "the second retirement is only seconds old, so the fence must still be there");

        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS);
        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS, pid -> true);
        assertTrue(registry.revocationWindows().isEmpty(), "and then it expires on its own age");
    }

    @Test
    void anEndpointReleasedOnAgeAloneStaysOwedWhileItsProcessLives() {
        // Age is not evidence. Before the bound existed the endpoint stayed and its fence POST failed,
        // so a revocation came back unconfirmed; dropping the record must not turn that same situation
        // into a confirmed fence for a window that may still be serving.
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.register(11, "1.0", "tok", List.of());

        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS, pid -> true);

        assertTrue(registry.revocationWindows().isEmpty(), "the record is freed");
        assertEquals(List.of("w1"), registry.unattestedRevocationWindowIds(),
                "the obligation is not");
        assertEquals(1, registry.unattestedEndpointCount());

        for (int pass = 0; pass < 5; pass++) {
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS * 10);
            registry.reap(InstanceRegistry.MAX_QUARANTINE_MS, pid -> true);
        }
        assertEquals(List.of("w1"), registry.unattestedRevocationWindowIds(),
                "no amount of time turns 'we stopped tracking it' into proof that it stopped");
        assertEquals(1, registry.unattestedEndpointCount(), "and it is still counted");
    }

    @Test
    void aProcessProvenGoneLaterDischargesWhatItsAgedWindowLeftOwed() {
        // The proof can arrive after the record has aged out: the instance wedged past its retention,
        // then exited. A pid the kernel has reaped serves nothing, which is the same proof every other
        // part of this fence accepts - the reap order above is written so that a death proven in the
        // ageing pass itself pre-empts the obligation, and this is that same death one pass later.
        // Latched instead, the tally would only ever grow, and every revocation this broker was asked
        // for afterwards would answer unconfirmed with nothing anywhere left to fence.
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.register(12, "1.0", "tok2", List.of(window("w2", 5002, 1, 1)));
        registry.register(11, "1.0", "tok", List.of());
        registry.register(12, "1.0", "tok2", List.of());

        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS, pid -> true);
        assertEquals(2, registry.unattestedEndpointCount(), "both aged out unproven");

        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS, pid -> pid != 11);

        assertEquals(List.of("w2"), registry.unattestedRevocationWindowIds(),
                "the one whose process is gone is discharged, the one still running is not");
        assertEquals(1, registry.unattestedEndpointCount(), "and the tally says the same");

        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS, pid -> false);
        assertEquals(List.of(), registry.unattestedRevocationWindowIds(), "then so is the other");
        assertEquals(0, registry.unattestedEndpointCount(),
                "nothing is owed once no process that owed it is left");
    }

    @Test
    void aProcessProvenGoneDischargesTheObligationsFoldedOntoIt() {
        // An obligation past the naming bound is its pid and nothing else, so that pid dying is the whole
        // of what settles it. Only one such entry stands for however many of the process's windows folded
        // onto it, and how many those were is not recorded - so the tally keeps counting the rest. That
        // over-reports what is unfenced, which keeps revocations unconfirmed: the safe way to be wrong.
        exhaustBothUnattestedBounds();
        long folded = 1_000 + InstanceRegistry.MAX_UNATTESTED_WINDOWS;
        ageOutOneEndpoint(folded, "second-window", 6_600);
        int owed = registry.unattestedEndpointCount();
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS * 2 + 1, owed,
                "two of that process's windows overflowed onto one entry, both counted");

        registry.reap(0, pid -> pid != folded);

        assertFalse(registry.unattestedProcessObligations().contains(folded),
                "the entry is settled by the only thing that could settle it");
        assertEquals(owed - 1, registry.unattestedEndpointCount(),
                "and one record comes off the tally, leaving the others it covered over-reported");
    }

    @Test
    void aDeathProvenInTheSameReapPassLeavesNoObligationBehind() {
        // The pass that crosses the retention bound can be the very pass that reads the pid dead: reaps
        // run on the broker's maintenance loop, which the retry of a fence to a draining backend can hold
        // up for minutes. Releasing on age first would mint an obligation for a process the kernel has
        // already reaped - and then answer 503 for every other credential for the life of the broker.
        registry.register(11, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.register(11, "1.0", "tok", List.of());

        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(8_000, pid -> pid != 11);

        assertTrue(registry.revocationWindows().isEmpty(), "the endpoint is gone either way");
        assertEquals(List.of(), registry.unattestedRevocationWindowIds(),
                "a window whose process the kernel reaped is not owed a fence");
        assertEquals(0, registry.unattestedEndpointCount(),
                "age must never pre-empt a death this pass can prove");
    }

    @Test
    void pastTheUnattestedIdBoundTheCountStillTellsTheTruth() {
        // The ids are what a fanout can name, and that list has to stay bounded. The count is what says
        // a fence is unconfirmed, so it must keep rising after the naming stops - a capped count would
        // start confirming fences for endpoints that were never proven stopped.
        String handle = registry.register(11, "1.0", "tok", List.of());
        int endpoints = InstanceRegistry.MAX_UNATTESTED_WINDOWS + 5;
        for (int index = 0; index < endpoints; index++) {
            registry.heartbeat(handle, "tok", List.of(window("w" + index, 5_000 + index, 1, 1)));
            registry.heartbeat(handle, "tok", List.of());
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        }
        registry.heartbeat(handle, "tok", List.of());

        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS,
                registry.unattestedRevocationWindowIds().size(), "the named ids stay bounded");
        assertEquals(endpoints, registry.unattestedEndpointCount(),
                "every endpoint that aged out unproven is still counted");
        assertEquals(List.of(11L), registry.unattestedProcessObligations(),
                "and the ones past the bound are still named by the process that owed them");
    }

    @Test
    void anUnnamableObligationIsStillNamedByItsProcess() {
        // A count is a truth this generation can act on and nothing else can: a journal cannot hand on
        // "five more, unspecified", and a successor told nothing would confirm a fence for an endpoint
        // that may still be serving. The pid is what is left of such an obligation and the whole of what
        // settles it, so the overflow is folded onto the process rather than dropped.
        String handle = registry.register(11, "1.0", "tok", List.of());
        String other = registry.register(12, "1.0", "tok2", List.of());
        for (int index = 0; index < InstanceRegistry.MAX_UNATTESTED_WINDOWS; index++) {
            registry.heartbeat(handle, "tok", List.of(window("w" + index, 5_000 + index, 1, 1)));
            registry.heartbeat(handle, "tok", List.of());
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        }
        registry.heartbeat(handle, "tok", List.of());
        assertEquals(List.of(), registry.unattestedProcessObligations(),
                "nothing has overflowed while every window still has an entry");

        // One more from each process, now that no window can be named.
        registry.heartbeat(handle, "tok", List.of(window("over-11", 6_001, 1, 1)));
        registry.heartbeat(other, "tok2", List.of(window("over-12", 6_002, 1, 1)));
        registry.heartbeat(handle, "tok", List.of());
        registry.heartbeat(other, "tok2", List.of());
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.heartbeat(handle, "tok", List.of());

        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS,
                registry.unattestedObligations().size(), "the named obligations stay bounded");
        assertEquals(Set.of(11L, 12L), Set.copyOf(registry.unattestedProcessObligations()),
                "both processes owe a fence for a window nothing here can name");
        assertEquals(2, registry.unattestedProcessObligations().size(),
                "one entry per process, however many of its windows overflowed");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS + 2,
                registry.unattestedEndpointCount(), "and the count is unchanged by any of it");
    }

    @Test
    void anObligationNoBoundCanRecordKeepsItsEndpointInsteadOfLosingIt() {
        // Both ways of writing an obligation down are bounded, and at the end of both there is nothing
        // left to write it in. Releasing the record there is the one outcome that is not allowed: an
        // endpoint that was never proven stopped forgotten, and the next fence reported confirmed with
        // nothing left to have confirmed it. So the endpoint stays retired-but-held - which is what it
        // was before any of these bounds existed, and strictly more than an obligation record, because a
        // backend still listening actually receives the fence.
        exhaustBothUnattestedBounds();
        ageOutOneEndpoint(9_999, "unrecordable", 6_500);

        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS,
                registry.unattestedObligations().size(), "every name is taken");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS,
                registry.unattestedProcessObligations().size(), "and so is every process entry");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS * 2,
                registry.unattestedEndpointCount(),
                "the last one is not an obligation this broker recorded");
        assertEquals(List.of("unrecordable"),
                registry.revocationWindows().stream().map(window -> window.id).toList(),
                "it is still an endpoint, and a fanout still fences it");
        assertFalse(registry.unattestedProcessObligations().contains(9_999L),
                "not filed under its process either - the process bound is what was full");

        // Nothing later releases it: age is what it already has, and the bounds do not un-fill.
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS * 100);
        registry.reap(0, pid -> true);
        assertEquals(1, registry.revocationWindows().size(),
                "held for as long as its process lives and nothing can record it");
        assertFalse(registry.shouldExit(0, 0),
                "and this broker does not idle out while it holds one - retention is the cost");
        assertFalse(registry.shutdownEligible(), "nor honour a shutdown request");

        // The pid dying is proof, and proof still settles it: a backend that is gone serves nothing, so
        // the record goes with no obligation left behind. Retention is bounded by the process, not
        // permanent. Only this endpoint's process dies here, so what the other pids are owed is untouched
        // and the count below is about the held record alone.
        registry.reap(0, pid -> pid != 9_999L);
        assertTrue(registry.revocationWindows().isEmpty(), "death releases it, as it always did");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS * 2,
                registry.unattestedEndpointCount(), "and owes nothing for it");
    }

    @Test
    void aProcessAlreadyRecordedAbsorbsItsNextUnnamableWindow() {
        // A process entry says "some endpoint of this pid was never proven stopped", and that is as true
        // of two of its windows as of one - the pid is the whole of what settles either. So a process
        // already recorded takes its next overflow whatever the bound says: holding the endpoint instead
        // would keep the broker alive for an obligation that is already written down.
        exhaustBothUnattestedBounds();
        long recorded = 1_000 + InstanceRegistry.MAX_UNATTESTED_WINDOWS;
        assertTrue(registry.unattestedProcessObligations().contains(recorded),
                "that pid is one of the ones the process bound is full of");

        ageOutOneEndpoint(recorded, "second-window", 6_600);

        assertTrue(registry.revocationWindows().isEmpty(),
                "its next window folds onto the entry that is already there");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS,
                registry.unattestedProcessObligations().size(),
                "one entry per process, however many of its windows overflowed");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS * 2 + 1,
                registry.unattestedEndpointCount(),
                "counted like every other, which keeps the revocation unconfirmable");
    }

    @Test
    void aProcessAlreadyHoldingAnUnrecordableEndpointDoesNotPinASecondOne() {
        // Retention has to grow with processes, not with windows: a held record answers to the pid being
        // gone, and that is equally the proof for every other endpoint of the same process, so a second
        // one buys a revocation nothing it did not already have while costing another quarantine slot and
        // another reason the broker cannot idle out. So the second is released and counted, exactly like
        // an overflow folded onto a process already recorded.
        exhaustBothUnattestedBounds();
        ageOutOneEndpoint(9_999, "held", 6_500);
        assertEquals(List.of("held"),
                registry.revocationWindows().stream().map(window -> window.id).toList(),
                "the first one nothing could record is held");
        int owedForTheFirst = registry.unattestedEndpointCount();

        ageOutOneEndpoint(9_999, "second-unrecordable", 6_501);

        assertEquals(List.of("held"),
                registry.revocationWindows().stream().map(window -> window.id).toList(),
                "the second is released: one record per process is what the proof is owed at");
        assertEquals(owedForTheFirst + 1, registry.unattestedEndpointCount(),
                "counted on the way out, so the revocation stays unconfirmable for it too");

        // Two of a process's windows ageing out in the same pass cannot both be kept either.
        InstanceRegistry same = new InstanceRegistry(now::get);
        for (int index = 0; index < InstanceRegistry.MAX_UNATTESTED_WINDOWS * 2; index++) {
            String filler = same.register(1_000 + index, "1.0", "tok",
                    List.of(window("f" + index, 5_000 + index, 1, 1)));
            same.unregister(filler);
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
            same.reap(0, alive -> true);
        }
        String together = same.register(8_888, "1.0", "tok",
                List.of(window("a", 6_600, 1, 1), window("b", 6_601, 1, 1)));
        same.unregister(together);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        same.reap(0, alive -> true);

        assertEquals(1, same.revocationWindows().size(),
                "one held, whichever of the two the pass reached first");
    }

    @Test
    void windowsCountedAgainstAHeldRecordAreDischargedByTheSameProofItIs() {
        // Past both bounds a window released because its process already holds a record writes nothing
        // down: there is no name and no process entry left to write it in, and what stands for the process
        // is an endpoint of it kept in quarantine. It is still counted, because it was never proven
        // stopped - but the proof the held record answers to is its proof too, and the count has to come
        // back off when that proof arrives. Latched instead, this broker would answer every revocation
        // asked of it afterwards unconfirmed, for the rest of its life, with nothing left to fence.
        exhaustBothUnattestedBounds();
        int recorded = registry.unattestedEndpointCount();
        ageOutOneEndpoint(9_999, "held", 6_500);
        ageOutOneEndpoint(9_999, "counted-against-it", 6_501);
        ageOutOneEndpoint(9_999, "counted-again", 6_502);
        assertEquals(List.of("held"),
                registry.revocationWindows().stream().map(window -> window.id).toList(),
                "one record per process is held, and the other two were released against its proof");
        assertEquals(recorded + 2, registry.unattestedEndpointCount(),
                "both released windows are counted, the held one is an endpoint rather than a count");

        // Time alone changes none of it: the process is still there, so nothing has been proven.
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS * 10);
        registry.reap(0, pid -> true);
        assertEquals(recorded + 2, registry.unattestedEndpointCount(),
                "age is not evidence for these either");

        registry.reap(0, pid -> pid != 9_999L);

        assertTrue(registry.revocationWindows().isEmpty(), "the held record goes on the proof, as ever");
        assertEquals(recorded, registry.unattestedEndpointCount(),
                "and every window counted against that proof goes with it");
    }

    /** Age out one endpoint of {@code pid} with nothing proving the process stopped. */
    private void ageOutOneEndpoint(long pid, String windowId, int port) {
        String handle = registry.register(pid, "1.0", "tok", List.of(window(windowId, port, 1, 1)));
        registry.unregister(handle);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(0, alive -> true);
    }

    /** Fill both bounds on what an unattested obligation can be recorded as: names, then processes. */
    private void exhaustBothUnattestedBounds() {
        for (int index = 0; index < InstanceRegistry.MAX_UNATTESTED_WINDOWS * 2; index++) {
            ageOutOneEndpoint(1_000 + index, "w" + index, 5_000 + index);
        }
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS,
                registry.unattestedObligations().size(), "names full");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS,
                registry.unattestedProcessObligations().size(), "processes full");
    }

    @Test
    void anObligationCarriesThePidThatCanSettleIt() {
        // The window id is how a result names an obligation; the pid is the only handle on the endpoint
        // that outlives this registry, so it is what a journal hands to a successor, what the OS can be
        // asked about, and what the same process registering again matches. Recorded without it the
        // obligation could be written but never discharged - a fence owed for the life of the machine.
        String handle = registry.register(4711, "1.0", "tok", List.of(window("w1", 5001, 1, 1)));
        registry.heartbeat(handle, "tok", List.of());
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.heartbeat(handle, "tok", List.of());
        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS * 4, pid -> true);

        assertEquals(List.of(new InstanceRegistry.UnattestedEndpoint("w1", 4711)),
                registry.unattestedObligations(), "named, and owned by the pid that can settle it");
        assertEquals(List.of("w1"), registry.unattestedRevocationWindowIds(),
                "the ids a result names are the same set");
    }

    @Test
    void onlyTheWindowComingBackSaysAnythingAboutThatWindow() {
        // The proof that settles an obligation has to be about the endpoint that owes the fence. A
        // registration reports the windows a process has now, and this registry deliberately reads one
        // missing from it as retired-but-unproven rather than stopped - so the same pid registering other
        // windows says nothing about the one that aged out, and offering it as a proof would discharge a
        // fence obligation on the strength of a registration that never mentioned it.
        String handle = registry.register(4711, "1.0", "tok", List.of(window("gone", 5001, 1, 1)));
        registry.heartbeat(handle, "tok", List.of());
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.heartbeat(handle, "tok", List.of(window("other", 5002, 2, 2)));
        registry.reap(InstanceRegistry.MAX_QUARANTINE_MS * 4, pid -> true);

        assertEquals(List.of(new InstanceRegistry.UnattestedEndpoint("gone", 4711)),
                registry.unattestedObligations(), "the aged-out endpoint is the obligation");
        assertEquals(Set.of(new InstanceRegistry.UnattestedEndpoint("other", 4711)),
                registry.fenceableEndpoints(),
                "and the window a fence can still reach is the other one, not it");

        // The same window id back at a new port and secret is a later incarnation of that window, which
        // exists only because the one before it was stopped: reachable again, and this registry's own
        // obligation from here.
        registry.heartbeat(handle, "tok", List.of(window("gone", 5003, 3, 3)));
        assertTrue(registry.fenceableEndpoints()
                        .contains(new InstanceRegistry.UnattestedEndpoint("gone", 4711)),
                "the window is a fence target again");

        // A quarantined incarnation counts too: revocationTargets() sends to quarantine, so a fence
        // still lands there, and this registry records the obligation itself if it ages out here.
        registry.heartbeat(handle, "tok", List.of());
        assertTrue(registry.fenceableEndpoints()
                        .contains(new InstanceRegistry.UnattestedEndpoint("gone", 4711)),
                "a retired-but-quarantined endpoint is still reachable");
    }

    @Test
    void theUnattestedCountSaturatesRatherThanWrappingNegative() {
        // The count is read as "is a fence still owed" and added to a fanout's window total. An int that
        // wrapped would answer the first question with "nothing owed" - compacting a live obligation away
        // at the next quiet shutdown - and make the second report fewer windows than were fenced. Saturated
        // it stays owed and unconfirmable, which is the direction to be wrong in.
        assertEquals(2, InstanceRegistry.saturatingIncrement(1));
        assertEquals(Integer.MAX_VALUE, InstanceRegistry.saturatingIncrement(Integer.MAX_VALUE - 1));
        assertEquals(Integer.MAX_VALUE, InstanceRegistry.saturatingIncrement(Integer.MAX_VALUE),
                "one past the end is still every obligation, never a negative count");
    }

    @Test
    void dischargingObligationsCannotTakeTheCountBelowWhatIsOwed() {
        // Proven death takes settled obligations off the tally, and that subtraction has the same one
        // direction it can be wrong in: too many owed keeps a revocation unconfirmed, too few reports a
        // fence nothing installed. So it never goes below zero, and a tally that saturated is not touched
        // at all - past that point it is no longer a sum of one-obligation-each increments to subtract from.
        assertEquals(3, InstanceRegistry.saturatingDecrement(5, 2));
        assertEquals(0, InstanceRegistry.saturatingDecrement(2, 2));
        assertEquals(5, InstanceRegistry.saturatingDecrement(5, 0), "a pass that settled nothing changes nothing");
        assertEquals(0, InstanceRegistry.saturatingDecrement(2, 7),
                "more records than the tally can account for still stops at nothing owed");
        assertEquals(Integer.MAX_VALUE, InstanceRegistry.saturatingDecrement(Integer.MAX_VALUE, 9),
                "a saturated count stays saturated: it stopped being a number of obligations");
    }

    @Test
    void anExhaustedQuarantineIsReleasedRatherThanRefusingEveryRetirement() {
        // Capacity is checked as endpoints retire, and that check used to run before anything could
        // expire: once full, every register and heartbeat that closed a window threw - including the
        // ones whose own expiry pass was the only thing that would have drained it. Nothing could then
        // register or close a window again for as long as the broker lived.
        String handle = registry.register(11, "1.0", "tok", List.of());
        for (int index = 0; index < InstanceRegistry.MAX_QUARANTINED_WINDOWS; index++) {
            assertTrue(registry.heartbeat(handle, "tok",
                    List.of(window("w" + index, 5_000 + index, 1, 1))));
            assertTrue(registry.heartbeat(handle, "tok", List.of()));
        }
        assertEquals(InstanceRegistry.MAX_QUARANTINED_WINDOWS, registry.revocationWindows().size());

        assertTrue(registry.heartbeat(handle, "tok", List.of(window("more", 6_000, 1, 1))));
        assertThrows(IllegalStateException.class,
                () -> registry.heartbeat(handle, "tok", List.of()),
                "while every retained endpoint is still young the bound is a real refusal");

        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        assertTrue(registry.heartbeat(handle, "tok", List.of()),
                "an expired quarantine must be released by the very call the bound was refusing");
        assertEquals(1, registry.revocationWindows().size(),
                "only the endpoint that just retired is still held");
    }
}
