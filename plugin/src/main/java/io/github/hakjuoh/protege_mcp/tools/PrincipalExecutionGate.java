package io.github.hakjuoh.protege_mcp.tools;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

/**
 * Per-client execution fence that linearizes credential revocation with tool completion.
 *
 * <p>Each accepted tool holds a shared lease for its whole guarded handler and audit completion.
 * Revocation takes that client's fair exclusive lock, waits for older leases, records a permanent
 * tombstone, and only then returns. Thus a non-interruptible operation may finish before revocation
 * completes, but no result can commit after the successful revocation boundary.
 */
public final class PrincipalExecutionGate {

    // Tombstones intentionally last for this ToolContext's lifetime. Client/grant ids are unique;
    // removing them on a timer would let a request paused after authentication resume after expiry.
    // The whole context (and this bounded desktop-session state) is discarded on server restart.
    private final ConcurrentHashMap<String, ClientState> clients = new ConcurrentHashMap<>();

    public Lease acquire(AuthenticatedPrincipal principal) {
        if (principal == null) {
            throw new ToolArgException("Authorization denied: authenticated principal is missing.");
        }
        ClientState state = clients.computeIfAbsent(principal.clientId(), ignored -> new ClientState());
        if (state.clientRevocationPending || (principal.grantId() != null
                && state.pendingGrants.contains(principal.grantId()))) {
            throw new ToolArgException("Authorization denied: this client or grant was revoked.");
        }
        state.lock.readLock().lock();
        if (state.clientRevocationPending || state.clientRevoked
                || (principal.grantId() != null && (state.pendingGrants.contains(principal.grantId())
                || state.revokedGrants.contains(principal.grantId())))) {
            state.lock.readLock().unlock();
            throw new ToolArgException("Authorization denied: this client or grant was revoked.");
        }
        state.active.incrementAndGet();
        return new Lease(state);
    }

    public Revocation revokeClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required");
        }
        ClientState state = clients.computeIfAbsent(clientId, ignored -> new ClientState());
        state.clientRevocationPending = true;
        int observedActive = state.active.get();
        long started = System.nanoTime();
        state.lock.writeLock().lock();
        try {
            state.clientRevoked = true;
        } finally {
            state.lock.writeLock().unlock();
        }
        return new Revocation(observedActive, elapsedMillis(started));
    }

    public Revocation revokeGrant(String clientId, String grantId) {
        if (clientId == null || clientId.isBlank() || grantId == null || grantId.isBlank()) {
            throw new IllegalArgumentException("clientId and grantId are required");
        }
        ClientState state = clients.computeIfAbsent(clientId, ignored -> new ClientState());
        state.pendingGrants.add(grantId);
        int observedActive = state.active.get();
        long started = System.nanoTime();
        state.lock.writeLock().lock();
        try {
            state.revokedGrants.add(grantId);
        } finally {
            state.lock.writeLock().unlock();
        }
        return new Revocation(observedActive, elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static final class ClientState {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private final Set<String> revokedGrants = new HashSet<>();
        private final Set<String> pendingGrants = ConcurrentHashMap.newKeySet();
        private final AtomicInteger active = new AtomicInteger();
        private volatile boolean clientRevocationPending;
        private boolean clientRevoked;
    }

    public static final class Lease implements AutoCloseable {
        private ClientState state;

        private Lease(ClientState state) {
            this.state = state;
        }

        @Override
        public void close() {
            ClientState current = state;
            if (current == null) {
                return;
            }
            state = null;
            current.active.decrementAndGet();
            current.lock.readLock().unlock();
        }
    }

    public record Revocation(int observedActive, long waitMillis) { }
}
