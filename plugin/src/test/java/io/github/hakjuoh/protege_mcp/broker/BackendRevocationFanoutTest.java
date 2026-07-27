package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

class BackendRevocationFanoutTest {

    @Test
    void quarantinedEndpointPreventsFalseFenceConfirmation() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String processId = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("quarantined", closedPort, "secret", "title", 1, 1)));
        registry.unregister(processId);

        BackendRevocationFanout fanout = new BackendRevocationFanout(registry);
        BackendRevocationFanout.Result result = fanout.revokeGrant("client", "grant");

        assertFalse(result.confirmed());
        assertEquals(List.of("quarantined"), result.failedWindowIds());
        registry.reap(8_000, pid -> false);
        assertTrue(fanout.executeGrant("client", "grant").confirmed());
    }

    @Test
    void anEndpointAgedOutOfQuarantineStaysUnacknowledged() throws Exception {
        // Its pid was handed to some other process, so liveness can never disprove it and only the age
        // bound releases the record. There is no endpoint left to send a fence to and no evidence it
        // ever stopped, so the only honest result is the one from before the bound existed: unconfirmed,
        // naming the window - which is what makes the servlet answer 503 instead of "revoked".
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String processId = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("aged", closedPort, "secret", "title", 1, 1)));
        registry.unregister(processId);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(8_000, pid -> true);
        assertTrue(registry.revocationWindows().isEmpty(), "the endpoint is no longer tracked");

        BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                .revokeClient("client");

        assertFalse(result.confirmed());
        assertEquals(1, result.windows(), "an untracked obligation is still a window in the count");
        assertEquals(List.of("aged"), result.failedWindowIds());
    }

    @Test
    void anAgedOutEndpointBlocksConfirmationEvenWhenALiveOneCarriesTheSameWindowId() throws Exception {
        // A window that reconnects keeps its id, so the obligation left behind by an endpoint that aged
        // out unattested can collide with a live endpoint that does acknowledge. The live one answering
        // says nothing about the one that was never proven stopped, so the collision must not be read as
        // the obligation having been met.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String aged = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("shared", closedPort, "secret", "old", 1, 1)));
        registry.unregister(aged);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(8_000, pid -> true);

        com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "{\"commit_fence_confirmed\":true}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        backend.start();
        try {
            registry.register(12, "1.0", "token", List.of(new InstanceRegistry.Window(
                    "shared", backend.getAddress().getPort(), "secret", "new", 2, 2)));

            BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                    .revokeClient("client");

            assertFalse(result.confirmed(), "the aged-out obligation is still owed");
            assertEquals(1, result.acknowledged(), "the live endpoint did acknowledge");
            assertEquals(2, result.windows(), "both the live endpoint and the aged-out one are counted");
            assertEquals(List.of("shared"), result.failedWindowIds());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void anEndpointRegisteredAgainAfterAgeingOutIsOneWindowNotTwo() throws Exception {
        // An equal endpoint key is the same live endpoint registering again - id, port and per-window
        // secret are minted together and never reused - so the obligation left behind when its record aged
        // out and the target it is now describe one window. Counting the aged record beside the live target
        // would report two windows unfenced where there is one, which overstates what is still serving the
        // credential exactly as badly as understating it hides it.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            // Answers, but never confirms the fence: the obligation stays owed either way.
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        backend.start();
        try {
            InstanceRegistry.Window endpoint = new InstanceRegistry.Window(
                    "same", backend.getAddress().getPort(), "secret", "title", 1, 1);
            String processId = registry.register(11, "1.0", "token", List.of(endpoint));
            registry.unregister(processId);
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
            registry.reap(8_000, pid -> true);
            registry.register(12, "1.0", "token", List.of(endpoint));

            BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                    .revokeClient("client");

            assertFalse(result.confirmed(), "the endpoint never confirmed the fence");
            assertEquals(1, result.windows(), "one endpoint is one window, however its record got here");
            assertEquals(0, result.acknowledged());
            assertEquals(1, result.unacknowledged());
            assertEquals(List.of("same"), result.failedWindowIds());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void anEndpointThatAcknowledgedBeforeAgeingOutIsNotOwedTheFenceItAlreadyLanded() throws Exception {
        // The record can age out while the fence POST to it is still in flight: a retired endpoint whose
        // server is still listening answers, and the answer arrives after the age bound released it. Proof
        // does not expire with the record it came through - reading the release as an unmet obligation
        // would count that one window twice and leave a fence that demonstrably landed unconfirmable.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            // Age the endpoint out while its own fence request is being served.
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
            registry.reap(8_000, pid -> true);
            byte[] body = "{\"commit_fence_confirmed\":true}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        backend.start();
        try {
            String processId = registry.register(11, "1.0", "token", List.of(
                    new InstanceRegistry.Window("in-flight", backend.getAddress().getPort(),
                            "secret", "title", 1, 1)));
            registry.unregister(processId);

            BackendRevocationFanout fanout = new BackendRevocationFanout(registry);
            BackendRevocationFanout.Result result = fanout.revokeClient("client");

            assertTrue(result.confirmed(), "the endpoint proved the fence landed before its record went");
            assertEquals(1, result.windows(), "one window, counted once");
            assertEquals(1, result.acknowledged());
            assertEquals(List.of(), result.failedWindowIds());
            assertEquals(0, result.unacknowledged());

            // The proof is this revocation's. Another credential's fence never reached that endpoint, and
            // there is nothing left to send it to, so the obligation is still owed for the next one.
            BackendRevocationFanout.Result other = fanout.revokeClient("other-client");
            assertFalse(other.confirmed(), "no fence for the other credential ever reached it");
            assertEquals(List.of("in-flight"), other.failedWindowIds());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void proofOfALandedFenceOutlivesTheRecordAcrossTheDurableRetries() throws Exception {
        // The tombstone stays in the journal and the broker retries it every second for as long as it
        // lives, so the endpoint that acknowledged will eventually age out of quarantine between two
        // retries. Forgetting its acknowledgement then would turn a fence that demonstrably landed into
        // an obligation nothing can ever meet again: every later retry, and every later read of the same
        // revocation, would answer 503 for a window that is gone and was fenced before it went.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "{\"commit_fence_confirmed\":true}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        backend.start();
        try {
            String processId = registry.register(11, "1.0", "token", List.of(
                    new InstanceRegistry.Window("fenced", backend.getAddress().getPort(),
                            "secret", "title", 1, 1)));
            registry.unregister(processId);

            BackendRevocationFanout fanout = new BackendRevocationFanout(registry);
            assertTrue(fanout.revokeClient("client").confirmed(), "the endpoint acknowledged the fence");

            // Its pid is still alive, so only the age bound releases the record - and the broker's
            // maintenance retry runs across that moment.
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
            registry.reap(8_000, pid -> true);
            fanout.retryPending();

            BackendRevocationFanout.Result again = fanout.executeClient("client");
            assertTrue(again.confirmed(), "the acknowledgement is not forgotten with the record");
            assertEquals(0, again.windows(), "no window is still owed this fence");
            assertEquals(List.of(), again.failedWindowIds());

            BackendRevocationFanout.Result other = fanout.revokeClient("other-client");
            assertFalse(other.confirmed(), "the released endpoint is still owed every other fence");
            assertEquals(List.of("fenced"), other.failedWindowIds());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void anEndpointWhoseProcessDiedWhileTheFenceWasInFlightIsNotOwedIt() throws Exception {
        // A pid the OS reports dead is the one event this registry reads as proof that a backend
        // stopped: its endpoints are dropped outright rather than retired, and a fanout that runs a
        // moment later confirms. The same death during the fanout's own batch has to read the same way.
        // Holding the endpoint in the call's cumulative set would report a window as owing a fence that
        // has nowhere left to land - a 503 for a process the kernel has already reaped.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            // The window's process dies while its own fence request is being served.
            registry.reap(8_000, pid -> false);
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        backend.start();
        try {
            registry.register(11, "1.0", "token", List.of(
                    new InstanceRegistry.Window("died", backend.getAddress().getPort(),
                            "secret", "title", 1, 1)));

            BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                    .revokeClient("client");

            assertTrue(registry.revocationWindows().isEmpty(), "a dead pid leaves no endpoint behind");
            assertEquals(0, registry.unattestedEndpointCount(), "it was proven stopped, not aged out");
            assertTrue(result.confirmed(), "nothing is left there to serve the credential");
            assertEquals(0, result.windows(), "the window that died is not owed a fence");
            assertEquals(0, result.unacknowledged());
            assertEquals(List.of(), result.failedWindowIds());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void changedEndpointWithTheSameWindowIdRequiresBothIncarnations() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
        int oldPort;
        int newPort;
        try (ServerSocket oldSocket = new ServerSocket(0);
                ServerSocket newSocket = new ServerSocket(0)) {
            oldPort = oldSocket.getLocalPort();
            newPort = newSocket.getLocalPort();
        }
        String processId = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("shared", oldPort, "old-secret", "old", 1, 1)));
        registry.heartbeat(processId, "token", List.of(
                new InstanceRegistry.Window("shared", newPort, "new-secret", "new", 2, 2)));

        BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                .revokeClient("client");

        assertFalse(result.confirmed());
        assertEquals(2, result.windows());
        assertEquals(List.of("shared", "shared"), result.failedWindowIds());
    }

    @Test
    void aJournalOwesTheWindowRatherThanEachIncarnationOfIt() {
        // What a journal can hand on is what a successor could name and settle. An incarnation's identity is
        // its port and its per-window secret: no successor can verify either, and this file will not hold a
        // secret - so the record is the window of a process, which is what both proofs that settle it are
        // about and what a result names a failure by. Two incarnations of one window are therefore one
        // durable obligation where the registry that watched them go counted two, and it stays owed until
        // that window is reachable again or its process is gone - which settles both at once, so no
        // incarnation is ever released by the folding.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        long pid = 444;
        String processId = registry.register(pid, "1.0", "token", List.of(
                new InstanceRegistry.Window("same", 5_101, "old-secret", "title", 1, 1)));
        registry.heartbeat(processId, "token", List.of(
                new InstanceRegistry.Window("same", 5_102, "new-secret", "title", 2, 2)));
        registry.unregister(processId);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(0, alive -> true);
        assertEquals(2, registry.unattestedEndpointCount(), "the registry watched two endpoints go");

        AtomicReference<String> state = new AtomicReference<>();
        new BackendRevocationFanout(registry, state::get, state::set).clearForQuiescentShutdown();
        assertEquals("{\"version\":1,\"unattested\":[{\"window_id\":\"same\",\"pid\":444}],"
                + "\"revocations\":[]}", state.get(), "one record, for the window");

        InstanceRegistry successor = new InstanceRegistry(now::get);
        BackendRevocationFanout next = new BackendRevocationFanout(successor, state::get, state::set);
        BackendRevocationFanout.Result owed = next.revokeClient("credential");
        assertFalse(owed.confirmed(), "a window released unproven is still owed a fence");
        assertEquals(1, owed.windows(), "the one window the journal names");

        next.dischargeSettledObligations(alive -> true);
        assertTrue(state.get().contains("\"window_id\":\"same\""),
                "alive and out of reach, so still owed: " + state.get());

        successor.register(pid, "1.0", "token", List.of(
                new InstanceRegistry.Window("same", 5_103, "newer-secret", "title", 3, 3)));
        next.dischargeSettledObligations(alive -> true);
        assertFalse(state.get().contains("unattested"),
                "the window is a fence target again, so both incarnations are answered: " + state.get());
    }

    @Test
    void moreWindowsCanBeOwedAFenceThanTheRegistryCanStillName() {
        // The ids kept for endpoints that aged out unattested are bounded, so past that bound a result
        // names fewer windows than are owed a fence. How many that is has to come from the count and not
        // from the length of that list, or a partial revocation is reported as smaller than it was.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int owed = InstanceRegistry.MAX_UNATTESTED_WINDOWS + 1;
        int created = 0;
        for (long pid = 21; created < owed; pid++) {
            List<InstanceRegistry.Window> windows = new ArrayList<>();
            while (windows.size() < InstanceRegistry.MAX_WINDOWS_PER_PROCESS && created < owed) {
                windows.add(new InstanceRegistry.Window(
                        "w-" + created, 1_024 + created, "secret", "title", 1, 1));
                created++;
            }
            registry.unregister(registry.register(pid, "1.0", "token", windows));
        }
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(8_000, pid -> true);
        assertTrue(registry.revocationWindows().isEmpty(), "no endpoint is tracked any more");

        BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                .revokeClient("client");

        assertFalse(result.confirmed());
        assertEquals(owed, result.windows(), "every aged-out obligation is still a window");
        assertEquals(InstanceRegistry.MAX_UNATTESTED_WINDOWS, result.failedWindowIds().size(),
                "the ids it can still name are bounded");
        assertEquals(owed, result.unacknowledged(),
                "a window that never acknowledged counts whether or not it can be named");
    }

    @Test
    void anEndpointThatComesBackAndAgesOutAgainIsStillOneObligation() throws Exception {
        // A window whose heartbeats stall is reaped, returns on the next beat as the very same endpoint,
        // and can stall again. Each release is the same obligation - one endpoint never proven stopped - so
        // counting releases instead of endpoints would leave a window that no acknowledgement can account
        // for and no id can name twice, and a fanout holding that endpoint's proof would then report a
        // fence as confirmed while still counting a window as owed it.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        InstanceRegistry.Window window =
                new InstanceRegistry.Window("returning", closedPort, "secret", "title", 1, 1);
        for (int release = 0; release < 2; release++) {
            registry.unregister(registry.register(11, "1.0", "token", List.of(window)));
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
            registry.reap(8_000, pid -> true);
        }
        assertEquals(1, registry.unattestedEndpointCount(), "one endpoint is one obligation");

        BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                .revokeClient("client");

        assertFalse(result.confirmed());
        assertEquals(1, result.windows(), "counted once, like the id it can name");
        assertEquals(1, result.unacknowledged());
        assertEquals(List.of("returning"), result.failedWindowIds());
    }

    @Test
    void aFenceIsConfirmedOnlyWhenEveryCountedWindowAcknowledged() {
        // The count and the names can legitimately disagree - obligations past the registry's id bound are
        // owed without being nameable - so confirmation has to follow the count. Reading it off the empty
        // name list would claim a fence for a window that nothing acknowledged.
        BackendRevocationFanout.Result unnamed = new BackendRevocationFanout.Result(2, 1, List.of());

        assertFalse(unnamed.confirmed(), "a window owed the fence is owed it whether or not it is named");
        assertEquals(1, unnamed.unacknowledged());
        assertTrue(new BackendRevocationFanout.Result(2, 2, List.of()).confirmed(),
                "both windows acknowledged");
    }

    @Test
    void aWindowThatRegistersAfterARevocationIsFencedByTheDurableRetry() throws Exception {
        // A result describes the windows that existed while it ran, so a window registering after it cannot
        // be in it. What keeps that from being a gap is the journal: the tombstone outlives the call and the
        // broker's maintenance tick retries it against whatever is registered then, so the late window is
        // fenced within a beat - and until it is, the credential itself is already gone from the OAuth store
        // and its session pins are refused, so nothing new can be admitted for it anywhere.
        InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry);
        assertTrue(fanout.revokeClient("client").confirmed(), "no window is registered yet");

        List<String> fenced = java.util.Collections.synchronizedList(new ArrayList<>());
        com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            fenced.add(exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst(
                            io.github.hakjuoh.protege_mcp.server.BrokerControlServlet
                                    .BROKER_SECRET_HEADER)
                    + " " + new String(exchange.getRequestBody().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
            byte[] body = "{\"commit_fence_confirmed\":true}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        backend.start();
        try {
            registry.register(11, "1.0", "token", List.of(new InstanceRegistry.Window(
                    "late", backend.getAddress().getPort(), "window-secret", "title", 1, 1)));

            fanout.retryPending();

            assertEquals(List.of("/broker-control/revoke-client window-secret "
                            + "{\"client_id\":\"client\"}"), fenced,
                    "the retry fences the window that registered after the revocation returned");
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void failedWriteAheadLeavesTokenAndMemoryJournalUntouched() {
        InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry, () -> null,
                json -> { throw new IllegalStateException("disk unavailable"); });

        assertThrows(IllegalStateException.class,
                () -> fanout.prepareGrant("client", "grant"));
        assertEquals(0, fanout.pendingCount());
    }

    @Test
    void reconstructedJournalReplaysOAuthDeletionAndCompactsAtQuiescentShutdown() {
        AtomicReference<String> state = new AtomicReference<>();
        InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
        BackendRevocationFanout first = new BackendRevocationFanout(
                registry, state::get, state::set);
        OAuthStore oauth = new OAuthStore(() -> "static", () -> null, ignored -> { });
        OAuthStore.Client client = oauth.registerClient(List.of("http://localhost/cb"), "app");
        OAuthStore.Tokens tokens = oauth.issueTokens(client.clientId, "mcp", null);
        String grantId = oauth.authenticate(tokens.accessToken).grantId();
        first.prepareGrant(client.clientId, grantId);

        BackendRevocationFanout restarted = new BackendRevocationFanout(
                registry, state::get, state::set);
        assertEquals(1, restarted.pendingCount());
        restarted.replayOAuthRevocations(oauth);
        assertFalse(oauth.isValidAccessToken(tokens.accessToken));

        restarted.clearForQuiescentShutdown();
        assertEquals(0, restarted.pendingCount());
        BackendRevocationFanout afterCompaction = new BackendRevocationFanout(
                registry, state::get, state::set);
        assertEquals(0, afterCompaction.pendingCount());
    }

    @Test
    void quiescentCompactionRequiresConfirmedOauthPersistence() {
        AtomicReference<String> state = new AtomicReference<>();
        BackendRevocationFanout fanout = new BackendRevocationFanout(
                new InstanceRegistry(System::currentTimeMillis), state::get, state::set);
        fanout.prepareGrant("client", "grant");
        OAuthStore failing = new OAuthStore(() -> "static", () -> null,
                ignored -> { throw new IllegalStateException("oauth disk unavailable"); }, true, 0);

        assertThrows(IllegalStateException.class,
                () -> fanout.replayOAuthRevocations(failing));
        assertThrows(IllegalStateException.class, fanout::clearForQuiescentShutdown);
        assertEquals(1, fanout.pendingCount());
        assertTrue(state.get().contains("grant"));
    }

    @Test
    void anObligationNeverProvenStoppedKeepsTheJournalPastAQuiescentShutdown() throws Exception {
        // An empty registry says nothing is registered now, not that everything it once held has stopped.
        // Compacting the tombstone there hands the next broker nothing to replay, so a backend that
        // refused this fence for its whole quarantine and then comes back is never given it.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String processId = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("wedged", closedPort, "secret", "title", 1, 1)));
        registry.unregister(processId);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(8_000, pid -> true);
        assertEquals(1, registry.unattestedEndpointCount(), "released on age, never proven stopped");

        AtomicReference<String> state = new AtomicReference<>();
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry, state::get, state::set);
        fanout.prepareClient("client");
        fanout.replayOAuthRevocations(new OAuthStore(() -> "static", () -> null, ignored -> { }));
        assertTrue(registry.sealIfShouldExit(0, 0, fanout::clearForQuiescentShutdown),
                "an empty registry past its linger does seal");

        assertEquals(1, fanout.pendingCount(), "the fence is still owed, so the journal stays");
        assertTrue(state.get().contains("client"), "and stays durable for the next broker");

        com.sun.net.httpserver.HttpServer returned = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        List<String> fenced = new ArrayList<>();
        returned.createContext("/", exchange -> {
            fenced.add(exchange.getRequestURI().getPath());
            byte[] body = "{\"commit_fence_confirmed\":true}".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        returned.start();
        try {
            InstanceRegistry successorRegistry = new InstanceRegistry(now::get);
            BackendRevocationFanout successor = new BackendRevocationFanout(
                    successorRegistry, state::get, state::set);
            assertEquals(1, successor.pendingCount(), "the next broker loads what was owed");
            successorRegistry.register(11, "1.0", "token", List.of(new InstanceRegistry.Window(
                    "wedged", returned.getAddress().getPort(), "secret", "title", 2, 2)));

            successor.retryPending();

            assertEquals(1, fenced.size(), "the endpoint that came back is fenced by the successor");
        } finally {
            returned.stop(0);
        }
    }

    @Test
    void theWindowTotalSaturatesSoNoFenceIsConfirmedByOverflow() {
        // The aged-out tally saturates instead of wrapping, so this sum is the last place the total could
        // turn negative - and a negative total reads as "nothing unacknowledged", confirming a fence for
        // every window that never answered one.
        assertEquals(3, BackendRevocationFanout.totalWindows(1, 2));
        assertEquals(1, BackendRevocationFanout.totalWindows(1, -5),
                "a tally already discounted below zero owes nothing extra");
        assertEquals(Integer.MAX_VALUE, BackendRevocationFanout.totalWindows(2, Integer.MAX_VALUE));
        assertFalse(new BackendRevocationFanout.Result(
                        BackendRevocationFanout.totalWindows(2, Integer.MAX_VALUE), 2, List.of())
                        .confirmed(),
                "a saturated total still owes every window it could not name");
    }

    @Test
    void anObligationLeftUnprovenSurvivesTheBrokerThatRecordedIt() {
        // The broker that released the endpoint keeps the journal, but its memory of why ends with it. A
        // successor loads the tombstones into an empty registry that is honest about having held nothing,
        // so its own quiet shutdown would compact away an obligation nothing ever discharged - and the
        // backend that comes back after that is never fenced. The obligation travels in the journal.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        String processId = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("wedged", 5_555, "secret", "title", 1, 1)));
        registry.unregister(processId);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(8_000, pid -> true);
        assertEquals(1, registry.unattestedEndpointCount(), "released on age, never proven stopped");

        AtomicReference<String> state = new AtomicReference<>();
        BackendRevocationFanout first = new BackendRevocationFanout(registry, state::get, state::set);
        first.prepareClient("client");
        assertFalse(state.get().contains("unattested"),
                "an ordinary journal is the document it always was: " + state.get());
        first.replayOAuthRevocations(new OAuthStore(() -> "static", () -> null, ignored -> { }));
        first.clearForQuiescentShutdown();
        assertEquals(1, first.pendingCount(), "the fence is still owed, so the journal stays");
        assertTrue(state.get().contains("\"window_id\":\"wedged\""),
                "and which endpoint owes it is recorded with it: " + state.get());
        assertTrue(state.get().contains("\"pid\":11"),
                "named by the pid a successor can ask the OS about: " + state.get());

        InstanceRegistry successorRegistry = new InstanceRegistry(now::get);
        BackendRevocationFanout successor = new BackendRevocationFanout(
                successorRegistry, state::get, state::set);
        assertEquals(0, successorRegistry.unattestedEndpointCount(),
                "the successor's own registry never held that endpoint");
        assertEquals(1, successor.pendingCount(), "the next broker loads what was owed");
        successor.replayOAuthRevocations(new OAuthStore(() -> "static", () -> null, ignored -> { }));
        successor.clearForQuiescentShutdown();

        assertEquals(1, successor.pendingCount(), "no successor may compact what it cannot discharge");
        assertTrue(state.get().contains("client"), "and it stays durable for the one after it");
        successor.prepareClient("later-client");
        assertTrue(state.get().contains("\"window_id\":\"wedged\""),
                "a later tombstone rewrites the journal without dropping the obligation: " + state.get());
    }

    @Test
    void aFenceOwedByAnEarlierGenerationIsNeverConfirmedByASuccessor() {
        // The obligation survives the restart in the journal, so the answer about it has to survive too:
        // the successor's registry never held that endpoint and can name no window that owes the fence, and
        // a result that counted only what it can name would answer "confirmed" - HTTP 200,
        // commit_fence_confirmed:true - to a caller asking whether the credential is dead everywhere, with
        // no window having acknowledged anything at all.
        String owedJournal = "{\"version\":1,"
                + "\"unattested\":[{\"window_id\":\"wedged\",\"pid\":4194304}],\"revocations\":"
                + "[{\"kind\":\"client\",\"client_id\":\"owed\"}]}";
        AtomicReference<String> state = new AtomicReference<>(owedJournal);
        InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
        BackendRevocationFanout successor = new BackendRevocationFanout(
                registry, state::get, state::set);
        assertEquals(1, successor.pendingCount(), "the tombstone loads");
        assertEquals(0, registry.unattestedEndpointCount(),
                "and this registry is honest about never having held the endpoint that owes it");

        BackendRevocationFanout.Result result = successor.executeClient("owed");

        assertFalse(result.confirmed(), "no window acknowledged a fence nothing was sent");
        assertEquals(1, result.windows(), "the obligation the journal carries is one window owed");
        assertEquals(1, result.unacknowledged(), "and it is counted where the servlet reports it");

        // The same journal without the record: nothing was left unproven, so an empty registry owes nothing
        // and the fanout says so. The record is what blocks confirmation, not the empty registry.
        AtomicReference<String> discharged = new AtomicReference<>(
                "{\"version\":1,\"revocations\":[{\"kind\":\"client\",\"client_id\":\"owed\"}]}");
        BackendRevocationFanout.Result clean = new BackendRevocationFanout(
                new InstanceRegistry(System::currentTimeMillis), discharged::get, discharged::set)
                .executeClient("owed");
        assertTrue(clean.confirmed(), "a journal that records no unproven release confirms as before");
        assertEquals(0, clean.windows());
    }

    @Test
    void anUnprovenReleaseIsRecordedEvenWithNoTombstoneToKeep() {
        // The obligation is about an endpoint, not about a credential: the fence it owes is owed to every
        // revocation that comes after it, including ones no broker has made yet. A journal written only when
        // something happened to be pending at shutdown would hand the obligation on after a busy generation
        // and drop it after a quiet one, and the endpoint's standing does not depend on which it was.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        String processId = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("wedged", 5_555, "secret", "title", 1, 1)));
        registry.unregister(processId);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(8_000, pid -> true);

        AtomicReference<String> state = new AtomicReference<>();
        BackendRevocationFanout quiet = new BackendRevocationFanout(registry, state::get, state::set);
        assertEquals(0, quiet.pendingCount(), "this generation revoked nothing at all");
        quiet.clearForQuiescentShutdown();

        assertTrue(state.get() != null && state.get().contains("\"window_id\":\"wedged\""),
                "the unproven release is still recorded: " + state.get());
        assertTrue(state.get().contains("\"pid\":11"), "with the pid that owns it: " + state.get());

        BackendRevocationFanout successor = new BackendRevocationFanout(
                new InstanceRegistry(now::get), state::get, state::set);
        BackendRevocationFanout.Result result = successor.revokeClient("later-credential");

        assertFalse(result.confirmed(), "a credential minted later is not fenced there either");
        assertEquals(1, result.windows(), "the obligation counts as the window it is");
        assertTrue(state.get().contains("\"window_id\":\"wedged\""),
                "and writing that tombstone keeps it: " + state.get());
    }

    @Test
    void aSettledObligationIsDischargedSoTheJournalIsNotOwedForever() {
        // A record nothing can clear is not a record but a latch: every revocation this machine ever makes
        // again would answer "not confirmed", the journal could never be compacted, and it would fill to
        // capacity and start refusing revocations outright. Two things settle an obligation, both the
        // registry's own - the pid is gone, so nothing serves any endpoint of it any more; or that window is
        // one a fence can still be delivered to here, so the durable retry reaches it and this registry
        // carries the obligation from now on. A pid still alive with that window nowhere in reach is
        // neither, and stays owed: a registration reports the windows a process has now, and a window
        // missing from one is exactly what this registry refuses to read as stopped.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        long returned = 4_711;
        registry.register(returned, "1.0", "token", List.of(
                new InstanceRegistry.Window("back", 5_556, "secret", "title", 1, 1)));
        AtomicReference<String> state = new AtomicReference<>("{\"version\":1,\"unattested\":["
                + "{\"window_id\":\"gone\",\"pid\":4194304},"
                + "{\"window_id\":\"back\",\"pid\":" + returned + "},"
                + "{\"window_id\":\"left-behind\",\"pid\":" + returned + "},"
                + "{\"window_id\":\"elsewhere\",\"pid\":777}],\"revocations\":"
                + "[{\"kind\":\"client\",\"client_id\":\"owed\"}]}");
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry, state::get, state::set);
        assertEquals(1, fanout.pendingCount(), "the tombstone loads with the obligations");

        fanout.dischargeSettledObligations(pid -> pid != 4_194_304L);

        assertFalse(state.get().contains("\"window_id\":\"gone\""),
                "a pid the OS no longer has owes nothing: " + state.get());
        assertFalse(state.get().contains("\"window_id\":\"back\""),
                "and the window that is a fence target here again is not an obligation: " + state.get());
        assertTrue(state.get().contains("\"window_id\":\"left-behind\""),
                "but its process registering other windows proves nothing about it: " + state.get());
        assertTrue(state.get().contains("\"window_id\":\"elsewhere\""),
                "the one still running somewhere unregistered is still owed: " + state.get());
        assertTrue(state.get().contains("\"client_id\":\"owed\""),
                "and the tombstone the discharge rewrote around is intact: " + state.get());

        BackendRevocationFanout.Result partly = fanout.executeClient("owed");
        assertEquals(3, partly.windows(), "the live endpoint plus the two obligations left");
        assertEquals(3, partly.unacknowledged(), "none of them acknowledged this fence");
        assertEquals(List.of("back"), partly.failedWindowIds(),
                "an obligation of a generation now gone is counted, not named");

        fanout.dischargeSettledObligations(pid -> false);
        assertFalse(state.get().contains("unattested"),
                "with every obligation settled the journal is an ordinary one again: " + state.get());

        fanout.replayOAuthRevocations(new OAuthStore(() -> "static", () -> null, ignored -> { }));
        fanout.clearForQuiescentShutdown();
        assertEquals(0, fanout.pendingCount(), "and now the quiet shutdown may compact the tombstone");

        BackendRevocationFanout.Result clean = new BackendRevocationFanout(
                new InstanceRegistry(now::get), state::get, state::set).revokeClient("after");
        assertTrue(clean.confirmed(),
                "a later credential is fenced everywhere, because nothing is owed any more");
        assertEquals(0, clean.windows());
    }

    @Test
    void anObligationTooLateToBeNamedTravelsAsItsProcess() {
        // Past the bound on names an obligation the registry could only count would end with the
        // generation that counted it: a successor loads the ones that were named, finds their pids gone,
        // discharges them and answers "revoked everywhere" - while an endpoint no one ever proved stopped
        // may still be serving. The pid is all that is left to say about such a window, and the whole of
        // what can settle it, so it travels as the process that owed it.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int endpoints = InstanceRegistry.MAX_UNATTESTED_WINDOWS + 1;
        long lastPid = 0;
        for (int index = 0; index < endpoints; index++) {
            lastPid = 1_000 + index;
            String handle = registry.register(lastPid, "1.0", "token", List.of(
                    new InstanceRegistry.Window("w" + index, 5_000 + index, "secret", "title", 1, 1)));
            registry.unregister(handle);
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
            registry.reap(0, alive -> true);
        }
        long stillRunning = lastPid;
        assertEquals(endpoints, registry.unattestedEndpointCount(), "every one of them aged out unproven");
        assertEquals(List.of(stillRunning), registry.unattestedProcessObligations(),
                "and the one past the bound is named by its process");

        AtomicReference<String> state = new AtomicReference<>();
        new BackendRevocationFanout(registry, state::get, state::set).clearForQuiescentShutdown();
        assertTrue(state.get().contains("\"unattested_processes\":[" + stillRunning + "]"),
                "which is what the journal hands on for it: " + state.get());

        InstanceRegistry successorRegistry = new InstanceRegistry(now::get);
        BackendRevocationFanout successor = new BackendRevocationFanout(
                successorRegistry, state::get, state::set);
        assertEquals(endpoints, successor.revokeClient("credential").windows(),
                "a successor owes a fence for all of them, named or not");

        successor.dischargeSettledObligations(pid -> pid == stillRunning);
        BackendRevocationFanout.Result owed = successor.revokeClient("credential");
        assertFalse(owed.confirmed(), "the process that owes the unnamable window is still alive");
        assertEquals(1, owed.windows(), "and that obligation is the one left");
        assertTrue(state.get().contains("\"unattested_processes\":[" + stillRunning + "]"),
                "still carried, now that every named one is settled: " + state.get());

        // A record that names only the process has one proof and not two: it never said which window
        // overflowed, so no registration here can be that window coming back.
        successorRegistry.register(stillRunning, "1.0", "token", List.of(
                new InstanceRegistry.Window("unrelated", 5_900, "secret", "title", 4, 4)));
        successor.dischargeSettledObligations(pid -> true);
        assertTrue(state.get().contains("unattested_processes"),
                "another window of that process settles nothing: " + state.get());

        successor.dischargeSettledObligations(pid -> false);
        assertFalse(state.get().contains("unattested"),
                "the process is gone, so no endpoint of it serves anything: " + state.get());
    }

    @Test
    void anObligationNoBoundCanRecordIsNeverConfirmedAway() throws Exception {
        // Past both bounds there is nothing left to record an obligation in, so the registry keeps the
        // endpoint rather than a note about it. Everything downstream then behaves as it did before any
        // bound existed: the fence is actually sent, the window comes back unacknowledged, and the
        // quiescent shutdown that would hand a journal to a successor cannot happen while it is held -
        // so no later generation is in a position to report a fence nothing installed.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        int recordable = InstanceRegistry.MAX_UNATTESTED_WINDOWS * 2;
        for (int index = 0; index <= recordable; index++) {
            boolean last = index == recordable;
            String handle = registry.register(1_000 + index, "1.0", "token", List.of(
                    new InstanceRegistry.Window(last ? "unrecordable" : "w" + index,
                            last ? closedPort : 5_000 + index, "secret", "title", 1, 1)));
            registry.unregister(handle);
            now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
            registry.reap(0, alive -> true);
        }
        assertEquals(List.of("unrecordable"),
                registry.revocationWindows().stream().map(window -> window.id).toList(),
                "the one nothing could record is still an endpoint");

        AtomicReference<String> state = new AtomicReference<>();
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry, state::get, state::set);
        BackendRevocationFanout.Result result = fanout.revokeClient("credential");

        assertFalse(result.confirmed(), "an endpoint that may still be serving was fenced, not assumed");
        assertTrue(result.failedWindowIds().contains("unrecordable"),
                "and it is named in the failures: " + result.failedWindowIds());
        assertEquals(recordable + 1, result.windows(),
                "counted with the recorded obligations, exactly once");
        assertFalse(registry.sealIfShouldExit(0, 0),
                "no quiescent shutdown while it is held, so no journal claims it settled");

        // Its process dying is proof, and proof works as it always did - only then does the endpoint go.
        // Only that process dies here: the pids the recorded obligations name are all still running, so
        // what they are owed is what is left over, unchanged by the release of the one held record.
        registry.reap(0, pid -> pid != 1_000 + recordable);
        assertTrue(registry.revocationWindows().isEmpty(), "released on death, owing nothing");
        assertEquals(recordable, fanout.revokeClient("credential").windows(),
                "the recorded obligations are all that is left");
    }

    @Test
    void onlyABooleanConfirmationCountsAsAFence() throws Exception {
        // A reply shape this broker does not recognise is not a backend that installed a fence. Coercing
        // "true" or 1 would read one into a body that never claimed it, on the one answer that decides
        // whether a credential can still be presented somewhere.
        for (String body : List.of("{\"commit_fence_confirmed\":\"true\"}",
                "{\"commit_fence_confirmed\":1}", "{\"commit_fence_confirmed\":\"yes\"}")) {
            InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
            com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/", exchange -> {
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            backend.start();
            try {
                registry.register(11, "1.0", "token", List.of(new InstanceRegistry.Window(
                        "coercible", backend.getAddress().getPort(), "secret", "title", 1, 1)));

                BackendRevocationFanout.Result result = new BackendRevocationFanout(registry)
                        .revokeClient("client");

                assertFalse(result.confirmed(), body + " is not an acknowledgement");
                assertEquals(List.of("coercible"), result.failedWindowIds());
            } finally {
                backend.stop(0);
            }
        }
    }

    @Test
    void anEndpointNoFenceRequestCanBeBuiltForIsReportedNotThrown() {
        // A registration is refused before it can carry a secret like this, so nothing honest reaches here
        // - but the tombstone is durable before the first POST, so letting the failure out would abort the
        // fanout with no accounting and then throw again on every retry, taking the broker's whole
        // maintenance pass with it.
        InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
        registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("unaddressable", 5_555, "abc\ndef", "title", 1, 1)));
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry);

        BackendRevocationFanout.Result result = fanout.revokeClient("client");

        assertFalse(result.confirmed(), "an endpoint that cannot be reached did not acknowledge");
        assertEquals(List.of("unaddressable"), result.failedWindowIds());
        fanout.retryPending();
        assertEquals(1, fanout.pendingCount(), "and the retry is a no-op, not a thrown maintenance tick");
    }

    @Test
    void malformedOversizedAndOverCapacityJournalsFailClosed() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
        assertThrows(IllegalStateException.class,
                () -> new BackendRevocationFanout(registry, () -> "{not-json", ignored -> { }));
        assertThrows(IllegalStateException.class,
                () -> new BackendRevocationFanout(registry, () -> "", ignored -> { }));
        assertThrows(IllegalStateException.class,
                () -> new BackendRevocationFanout(registry, () -> " \n\t", ignored -> { }));
        assertThrows(IllegalStateException.class, () -> new BackendRevocationFanout(registry,
                () -> "x".repeat(BackendRevocationFanout.MAX_JOURNAL_BYTES + 1), ignored -> { }));
        // A journal is one document. A file carrying a second one after it was rewritten by something that
        // is not this broker - or truncated onto a longer previous version - and reading only the prefix
        // would load the wrong set of tombstones and silently drop every revocation the rest records.
        assertThrows(IllegalStateException.class, () -> new BackendRevocationFanout(registry,
                () -> "{\"version\":1,\"revocations\":[]}{\"version\":1,\"revocations\":"
                        + "[{\"kind\":\"client\",\"client_id\":\"dropped\"}]}", ignored -> { }));
        assertThrows(IllegalStateException.class, () -> new BackendRevocationFanout(registry,
                () -> "{\"version\":1,\"revocations\":[]} trailing", ignored -> { }));
        // The obligations that say a fence is still owed decide whether these tombstones may ever be
        // compacted and whether a revocation can confirm, so each has to be a record this broker can act
        // on: a window id to name it by and a pid to ask the OS about. A bare flag, a count, or an entry
        // with no usable pid records an obligation nothing could ever settle - and coercing one would
        // answer by guessing a question the record exists to answer.
        for (String unattested : List.of("true", "\"true\"", "1", "null", "{}",
                "[{\"window_id\":\"w\"}]", "[{\"pid\":11}]", "[{\"window_id\":\"w\",\"pid\":0}]",
                "[{\"window_id\":\"w\",\"pid\":-11}]", "[{\"window_id\":\"w\",\"pid\":\"11\"}]",
                "[{\"window_id\":\"w\",\"pid\":11.5}]", "[{\"window_id\":\"\",\"pid\":11}]",
                "[{\"window_id\":\"  \",\"pid\":11}]")) {
            assertThrows(IllegalStateException.class, () -> new BackendRevocationFanout(registry,
                    () -> "{\"version\":1,\"unattested\":" + unattested + ",\"revocations\":"
                            + "[{\"kind\":\"client\",\"client_id\":\"owed\"}]}", ignored -> { }),
                    "unattested:" + unattested + " is not a journal");
        }
        // An empty list is a document that says what it says: no generation left an endpoint unproven,
        // exactly as the absent field does. Refusing it would brick a broker on its own written state.
        assertEquals(1, new BackendRevocationFanout(registry,
                () -> "{\"version\":1,\"unattested\":[],\"revocations\":"
                        + "[{\"kind\":\"client\",\"client_id\":\"owed\"}]}", ignored -> { })
                .pendingCount(), "an empty obligation list loads like no list at all");
        // More obligations than a generation could ever have named is not this broker's journal either.
        StringBuilder tooMany = new StringBuilder("{\"version\":1,\"unattested\":[");
        for (int index = 0; index <= BackendRevocationFanout.MAX_UNATTESTED_OBLIGATIONS; index++) {
            if (index > 0) tooMany.append(',');
            tooMany.append("{\"window_id\":\"w").append(index).append("\",\"pid\":")
                    .append(index + 1).append('}');
        }
        String oversized = tooMany.append("],\"revocations\":[]}").toString();
        assertThrows(IllegalStateException.class,
                () -> new BackendRevocationFanout(registry, () -> oversized, ignored -> { }),
                "an obligation list past the registry's own bound is not a journal");

        // The obligations carried as a process are read the same way, and for the same reason: the pid is
        // the whole of what can ever settle one, so anything that is not a pid the OS can be asked about
        // is a record nothing could discharge - and a bare flag or a count would be a fence obligation of
        // some unnamed size, which is not something a successor can act on either.
        for (String byProcess : List.of("true", "\"true\"", "1", "null", "{}", "[\"11\"]", "[0]",
                "[-11]", "[11.5]", "[null]", "[[11]]")) {
            assertThrows(IllegalStateException.class, () -> new BackendRevocationFanout(registry,
                    () -> "{\"version\":1,\"unattested_processes\":" + byProcess + ",\"revocations\":"
                            + "[{\"kind\":\"client\",\"client_id\":\"owed\"}]}", ignored -> { }),
                    "unattested_processes:" + byProcess + " is not a journal");
        }
        assertEquals(1, new BackendRevocationFanout(registry,
                () -> "{\"version\":1,\"unattested_processes\":[],\"revocations\":"
                        + "[{\"kind\":\"client\",\"client_id\":\"owed\"}]}", ignored -> { })
                .pendingCount(), "an empty process list loads like no list at all");
        StringBuilder tooManyProcesses = new StringBuilder("{\"version\":1,\"unattested_processes\":[");
        for (int index = 0; index <= BackendRevocationFanout.MAX_UNATTESTED_OBLIGATIONS; index++) {
            if (index > 0) tooManyProcesses.append(',');
            tooManyProcesses.append(index + 1);
        }
        String oversizedProcesses = tooManyProcesses.append("],\"revocations\":[]}").toString();
        assertThrows(IllegalStateException.class,
                () -> new BackendRevocationFanout(registry, () -> oversizedProcesses, ignored -> { }),
                "more processes than a generation could have folded is not a journal either");

        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.createObjectNode().put("version", 1);
        var revocations = root.putArray("revocations");
        for (int index = 0; index < BackendRevocationFanout.MAX_PENDING; index++) {
            revocations.addObject().put("kind", "grant").put("client_id", "client")
                    .put("grant_id", "grant-" + index);
        }
        AtomicReference<String> state = new AtomicReference<>(root.toString());
        BackendRevocationFanout full = new BackendRevocationFanout(
                registry, state::get, state::set);
        assertEquals(BackendRevocationFanout.MAX_PENDING, full.pendingCount());
        assertThrows(IllegalStateException.class,
                () -> full.prepareGrant("client", "one-more-grant"));
        assertEquals(BackendRevocationFanout.MAX_PENDING, full.pendingCount());
        assertTrue(state.get().contains("grant-1023"));
    }

    @Test
    void anAskedForShutdownWritesDownWhatTheIdleOneWouldHave() throws Exception {
        // A generation does not only end by timing out. The likelier way is being asked: a window running a
        // newer plugin finds an idle broker of the older one and tells it to go. That ends the same memory
        // the linger would have, obligations included, so it has to hand them on the same way - or the
        // successor loads a journal that never heard of an endpoint released on age alone and confirms a
        // fence for it. The wiring is the whole of this test: the compaction runs on one path already.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        String processId = registry.register(11, "1.0", "token", List.of(
                new InstanceRegistry.Window("asked", 5_557, "secret", "title", 1, 1)));
        registry.unregister(processId);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(0, pid -> true);
        assertEquals(1, registry.unattestedEndpointCount(), "released on age, never proven stopped");

        AtomicReference<String> state = new AtomicReference<>();
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry, state::get, state::set);
        fanout.prepareClient("client");
        AtomicBoolean exited = new AtomicBoolean();
        EmbeddedHttpServer server = new EmbeddedHttpServer();
        server.addServlet(new InternalApiServlet("dir-secret", registry, () -> null,
                () -> exited.set(true), null, new ActiveProxyRequests(), fanout), "/internal/*", false);
        int port = server.start(0);
        try {
            HttpResponse<String> asked = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/shutdown"))
                            .header(InternalApiServlet.SECRET_HEADER, "dir-secret")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, asked.statusCode(), "an idle broker does go when asked: " + asked.body());
        } finally {
            server.stop();
        }

        assertTrue(exited.get(), "and the exit is what it acknowledged");
        assertTrue(state.get().contains("\"window_id\":\"asked\""),
                "the obligation went into the journal before it went: " + state.get());
        assertTrue(state.get().contains("\"pid\":11"),
                "named by the pid a successor can ask the OS about: " + state.get());

        InstanceRegistry successorRegistry = new InstanceRegistry(now::get);
        assertFalse(new BackendRevocationFanout(successorRegistry, state::get, state::set)
                        .revokeClient("credential").confirmed(),
                "so the next broker cannot report that endpoint fenced");
    }

    @Test
    void anObligationTheJournalCannotNameTravelsAsItsProcess() {
        // The journal's bound is its own, and it fills from every generation that wrote to it: a file
        // already carrying a full complement of names has no room for this generation's, whose own naming
        // bound is untouched and says nothing about the file. Folding it onto its pid is the same ladder the
        // registry climbs and the same trade: it keeps the proof that always arrives, the process ending,
        // and gives up the window-came-back one, so the obligation travels - and stands longer - rather than
        // being dropped for want of a name.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry filling = new InstanceRegistry(now::get);
        AtomicReference<String> state = new AtomicReference<>();
        for (int index = 0; index < BackendRevocationFanout.MAX_UNATTESTED_OBLIGATIONS; index++) {
            ageOut(filling, now, 2_000 + index, "named" + index, 5_000 + index);
        }
        new BackendRevocationFanout(filling, state::get, state::set).clearForQuiescentShutdown();
        assertEquals(BackendRevocationFanout.MAX_UNATTESTED_OBLIGATIONS,
                new BackendRevocationFanout(new InstanceRegistry(now::get), state::get, state::set)
                        .revokeClient("credential").windows(),
                "the journal is full of names and no processes");

        InstanceRegistry next = new InstanceRegistry(now::get);
        ageOut(next, now, 8_888, "nameless-in-the-journal", 6_700);
        BackendRevocationFanout second = new BackendRevocationFanout(next, state::get, state::set);
        second.clearForQuiescentShutdown();

        assertTrue(state.get().contains("\"unattested_processes\":[8888]"),
                "recorded as the process that owed it: " + state.get());
        InstanceRegistry successorRegistry = new InstanceRegistry(now::get);
        BackendRevocationFanout successor = new BackendRevocationFanout(
                successorRegistry, state::get, state::set);
        assertEquals(BackendRevocationFanout.MAX_UNATTESTED_OBLIGATIONS + 1,
                successor.revokeClient("credential").windows(),
                "so a successor owes a fence for it as well as for every named one");
    }

    @Test
    void anObligationNeitherJournalBoundCanTakeRefusesTheShutdownInsteadOfBeingDropped() {
        // Both of the journal's bounds full, from generations now gone, and this one has an obligation of
        // its own. There is no room to write it and no third place to put it, so the write refuses and the
        // shutdown refuses with it: this broker stays up holding the obligation in the memory that still
        // fences it, which is what the registry does with an endpoint no bound can record. Writing nothing
        // and going anyway is the one outcome that ends with a successor confirming a fence for an endpoint
        // that was never proven stopped.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry filling = new InstanceRegistry(now::get);
        AtomicReference<String> state = new AtomicReference<>();
        for (int index = 0; index < BackendRevocationFanout.MAX_UNATTESTED_OBLIGATIONS * 2; index++) {
            ageOut(filling, now, 2_000 + index, "w" + index, 5_000 + index);
        }
        new BackendRevocationFanout(filling, state::get, state::set).clearForQuiescentShutdown();
        String full = state.get();
        assertTrue(full.contains("\"unattested_processes\":["), "both bounds are on file: " + full);

        InstanceRegistry next = new InstanceRegistry(now::get);
        ageOut(next, now, 7_777, "unrecordable-anywhere", 6_800);
        BackendRevocationFanout second = new BackendRevocationFanout(next, state::get, state::set);

        assertThrows(IllegalStateException.class, second::clearForQuiescentShutdown,
                "nothing can record it, so nothing may forget it");
        assertEquals(full, state.get(), "and the journal it could not add to is untouched");
        assertThrows(IllegalStateException.class,
                () -> next.sealForRequestedShutdown(second::clearForQuiescentShutdown),
                "so an asked-for shutdown does not get its seal either");
        assertTrue(next.shutdownEligible(),
                "the registry is not sealed - a broker that could not write its obligation down stays up");
        assertFalse(second.revokeClient("credential").confirmed(),
                "which is what keeps the fence honest: it is still owed here");
    }

    @Test
    void aWindowOwedByBothTheJournalAndThisRegistryIsCountedOnce() {
        // The journal carries an obligation for one window of pid 4242. That window comes back here and is
        // released unproven a second time, so this registry names it too - and the record is still on file,
        // because what discharges it is the maintenance tick's pass and not the registration itself. One
        // window owes one fence. Counting the record and the tally both reports two unfenced windows where
        // the result can name only one, which reads as a second endpoint out there nobody can account for.
        AtomicLong now = new AtomicLong(1_000);
        AtomicReference<String> state = new AtomicReference<>(
                "{\"version\":1,\"unattested\":[{\"window_id\":\"returned\",\"pid\":4242}],"
                        + "\"revocations\":[]}");
        InstanceRegistry registry = new InstanceRegistry(now::get);
        BackendRevocationFanout fanout = new BackendRevocationFanout(registry, state::get, state::set);
        ageOut(registry, now, 4_242, "returned", 6_900);
        assertEquals(1, registry.unattestedEndpointCount(), "this registry is owed that fence as well now");

        BackendRevocationFanout.Result result = fanout.revokeClient("credential");

        assertFalse(result.confirmed(), "the fence is owed either way");
        assertEquals(List.of("returned"), result.failedWindowIds(), "and one window is what owes it");
        assertEquals(1, result.windows(), "counted once, by the record that outlives this broker");
        assertEquals(1, result.unacknowledged(), "which is the number the servlet reports");

        // Another window of the same process is another obligation: the record on file says nothing about
        // it - a registration is not a report that the process's other endpoints stopped - so both are owed
        // and both are counted. Only the one the journal already names is not counted twice.
        ageOut(registry, now, 4_242, "second-window", 6_901);
        BackendRevocationFanout.Result both = fanout.revokeClient("later-credential");
        assertEquals(2, both.windows(), "two windows owe fences here");
        assertEquals(List.of("returned", "second-window"), both.failedWindowIds(),
                "and the result names both of them");
    }

    @Test
    void anObligationTheRegistryDischargedIsNotHandedOnByTheNextShutdown() {
        // The proof can arrive long after the record aged out: the instance wedged past its retention and
        // then exited. After the pass that reads its pid dead this registry owes nothing for that window,
        // and a seal that wrote it down anyway would put a settled obligation into the file a successor
        // loads - one that successor can no longer get back to the proof for, since the pid it would ask
        // the OS about is a number the OS may have handed to something else by then. Every revocation on
        // this machine would answer unconfirmed from there on, which is the latch this journal exists to
        // avoid.
        AtomicLong now = new AtomicLong(1_000);
        InstanceRegistry registry = new InstanceRegistry(now::get);
        AtomicReference<String> state = new AtomicReference<>();
        ageOut(registry, now, 5_150, "exited", 6_100);
        ageOut(registry, now, 5_151, "wedged", 6_101);
        assertEquals(2, registry.unattestedEndpointCount(), "two windows aged out unproven");

        registry.reap(0, pid -> pid != 5_150L);

        BackendRevocationFanout fanout = new BackendRevocationFanout(registry, state::get, state::set);
        assertTrue(registry.sealIfShouldExit(0, 0, fanout::clearForQuiescentShutdown),
                "nothing is registered and nothing is held, so this generation may end");
        assertEquals("{\"version\":1,\"unattested\":[{\"window_id\":\"wedged\",\"pid\":5151}],"
                        + "\"revocations\":[]}", state.get(),
                "the settled one is not handed on, the one still running is");

        BackendRevocationFanout.Result owed = new BackendRevocationFanout(
                new InstanceRegistry(now::get), state::get, state::set).revokeClient("credential");
        assertFalse(owed.confirmed(), "the successor is still owed the fence nothing proved");
        assertEquals(1, owed.windows(), "and owed it once, for the window genuinely outstanding");
        assertEquals(List.of(), owed.failedWindowIds(),
                "an obligation of a generation now gone is counted, not named");
    }

    @Test
    void aWindowTheJournalOwesAndThisBrokerIsFencingRightNowIsCountedOnce() throws Exception {
        // The record was handed on by an earlier generation and the window it names is registered here, so
        // this fanout is sending the fence to the very endpoint the record is about. Counting the target and
        // the record both answers "one more window out there is unfenced" for a window that is right here and
        // acknowledged it - and the caller reads that as 503 until a maintenance tick discharges the record,
        // which is the same reachability this call already had in front of it. One window owes one fence.
        AtomicLong now = new AtomicLong(1_000);
        AtomicReference<String> state = new AtomicReference<>(
                "{\"version\":1,\"unattested\":[{\"window_id\":\"returned\",\"pid\":4242}],"
                        + "\"revocations\":[]}");
        InstanceRegistry registry = new InstanceRegistry(now::get);
        AtomicBoolean fenced = new AtomicBoolean();
        com.sun.net.httpserver.HttpServer backend = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            fenced.set(true);
            byte[] bytes = "{\"commit_fence_confirmed\":true}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        backend.start();
        try {
            int port = backend.getAddress().getPort();
            registry.register(4_242, "1.0", "token", List.of(
                    new InstanceRegistry.Window("returned", port, "window-secret", "title", 1, 1)));
            BackendRevocationFanout fanout =
                    new BackendRevocationFanout(registry, state::get, state::set);

            BackendRevocationFanout.Result result = fanout.revokeClient("credential");

            assertTrue(fenced.get(), "the fence went to the window the record is about");
            assertEquals(1, result.windows(), "which is one window, counted once");
            assertEquals(1, result.acknowledged(), "and it acknowledged");
            assertEquals(List.of(), result.failedWindowIds(), "so nothing is named as owing it");
            assertTrue(result.confirmed(), "the credential is fenced everywhere it could be");

            // Another window of the same process is not what the record is about: a registration says
            // nothing about the process's other endpoints, so an unreachable one still owes its fence.
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            registry.register(4_242, "1.0", "token", List.of(
                    new InstanceRegistry.Window("returned", port, "window-secret", "title", 1, 1),
                    new InstanceRegistry.Window("second-window", closedPort, "other-secret", "title", 1, 1)));
            BackendRevocationFanout.Result both = fanout.revokeClient("later-credential");
            assertFalse(both.confirmed(), "the second window never answered");
            assertEquals(2, both.windows(), "two windows, still counted once each");
            assertEquals(List.of("second-window"), both.failedWindowIds(), "and only one owes a fence");
        } finally {
            backend.stop(0);
        }
    }

    /** Retire one endpoint of {@code pid} and let it leave quarantine on age alone. */
    private static void ageOut(InstanceRegistry registry, AtomicLong now, long pid, String windowId,
            int port) {
        String handle = registry.register(pid, "1.0", "token", List.of(
                new InstanceRegistry.Window(windowId, port, "secret-" + windowId, "title", 1, 1)));
        registry.unregister(handle);
        now.addAndGet(InstanceRegistry.MAX_QUARANTINE_MS + 1);
        registry.reap(0, alive -> true);
    }
}
