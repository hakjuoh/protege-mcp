package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;

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
}
