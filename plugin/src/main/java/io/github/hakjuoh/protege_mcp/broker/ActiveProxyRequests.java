package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

/**
 * Race-safe ownership of requests currently forwarded by the broker.
 *
 * <p>A reservation is made before connecting to a backend and the response body is attached later.
 * Consequently, revocation cannot miss a request in the gap between authentication and receiving
 * the upstream response: it either closes an attached stream or makes the later attach fail closed.
 */
final class ActiveProxyRequests {

    private static final int MAX_PRINCIPAL_TOMBSTONES = 2_048;
    private static final int MAX_SESSION_TOMBSTONES = 4_096;
    private final Object lock = new Object();
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Reservation> active = new HashMap<>();
    // Tombstones intentionally live for the broker process lifetime. Removing one on a timer would
    // let a request paused between authentication and open() resume after expiry and bypass the
    // revocation fence. Client/session ids are unique and the broker exits after its last instance.
    private final Set<String> revokedClients = new HashSet<>();
    private final Set<String> revokedGrants = new HashSet<>();
    private final Set<String> terminatedSessions = new HashSet<>();

    Reservation open(AuthenticatedPrincipal principal, String sessionId) {
        synchronized (lock) {
            if (revokedClients.contains(principal.clientId())
                    || revokedGrants.contains(grantKey(
                            principal.clientId(), principal.grantId()))
                    || (sessionId != null && terminatedSessions.contains(sessionId))) {
                return null;
            }
            Reservation reservation = new Reservation(ids.incrementAndGet(), principal.clientId(),
                    blankToNull(principal.grantId()), blankToNull(sessionId));
            active.put(reservation.id, reservation);
            return reservation;
        }
    }

    int terminateClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return 0;
        }
        return terminate(clientId, null, null, true, false);
    }

    void prepareClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("client id is required");
        }
        synchronized (lock) {
            remember(revokedClients, clientId);
        }
    }

    int terminateGrant(String clientId, String grantId) {
        if (clientId == null || clientId.isBlank() || grantId == null || grantId.isBlank()) {
            return 0;
        }
        return terminate(clientId, grantId, null, false, true);
    }

    void prepareGrant(String clientId, String grantId) {
        if (clientId == null || clientId.isBlank() || grantId == null || grantId.isBlank()) {
            throw new IllegalArgumentException("grant identity is required");
        }
        synchronized (lock) {
            remember(revokedGrants, grantKey(clientId, grantId));
        }
    }

    int terminateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        return terminate(null, null, sessionId, false, false);
    }

    void prepareSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("session id is required");
        }
        synchronized (lock) {
            remember(terminatedSessions, sessionId);
        }
    }

    void closeAll() {
        List<InputStream> bodies = new ArrayList<>();
        synchronized (lock) {
            for (Reservation reservation : active.values()) {
                InputStream body = reservation.closeUnderLock();
                if (body != null) {
                    bodies.add(body);
                }
            }
            active.clear();
        }
        bodies.forEach(ActiveProxyRequests::closeQuietly);
    }

    private int terminate(String clientId, String grantId, String sessionId,
            boolean rememberClient, boolean rememberGrant) {
        List<InputStream> bodies = new ArrayList<>();
        int terminated = 0;
        synchronized (lock) {
            if (rememberClient) {
                remember(revokedClients, clientId);
            } else if (rememberGrant) {
                remember(revokedGrants, grantKey(clientId, grantId));
            } else {
                remember(terminatedSessions, sessionId);
            }
            for (Reservation reservation : active.values()) {
                if ((clientId != null && clientId.equals(reservation.clientId))
                        && (grantId == null || grantId.equals(reservation.grantId))
                        || (sessionId != null && sessionId.equals(reservation.sessionId))) {
                    if (!reservation.closed) {
                        terminated++;
                    }
                    InputStream body = reservation.closeUnderLock();
                    if (body != null) {
                        bodies.add(body);
                    }
                }
            }
        }
        bodies.forEach(ActiveProxyRequests::closeQuietly);
        return terminated;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String grantKey(String clientId, String grantId) {
        if (clientId == null || grantId == null) return "";
        return clientId.length() + ":" + clientId + grantId.length() + ":" + grantId;
    }

    private void remember(Set<String> target, String value) {
        if (target.contains(value)) return;
        int size = target == terminatedSessions
                ? terminatedSessions.size() : revokedClients.size() + revokedGrants.size();
        int maximum = target == terminatedSessions
                ? MAX_SESSION_TOMBSTONES : MAX_PRINCIPAL_TOMBSTONES;
        if (size >= maximum) {
            throw new IllegalStateException("proxy revocation tombstone capacity is exhausted");
        }
        target.add(value);
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // Best-effort cancellation; the proxy pump also observes normal upstream closure.
        }
    }

    final class Reservation implements AutoCloseable {
        private final long id;
        private final String clientId;
        private final String grantId;
        private final String sessionId;
        private InputStream body;
        private boolean closed;

        private Reservation(long id, String clientId, String grantId, String sessionId) {
            this.id = id;
            this.clientId = clientId;
            this.grantId = grantId;
            this.sessionId = sessionId;
        }

        /** Returns false and closes {@code upstreamBody} when revocation won the race. */
        boolean attach(InputStream upstreamBody) {
            boolean accepted;
            synchronized (lock) {
                accepted = !closed;
                if (accepted) {
                    body = upstreamBody;
                }
            }
            if (!accepted) {
                closeQuietly(upstreamBody);
            }
            return accepted;
        }

        @Override
        public void close() {
            InputStream toClose;
            synchronized (lock) {
                active.remove(id);
                toClose = closeUnderLock();
            }
            if (toClose != null) {
                closeQuietly(toClose);
            }
        }

        private InputStream closeUnderLock() {
            if (closed) {
                return null;
            }
            closed = true;
            InputStream result = body;
            body = null;
            return result;
        }
    }
}
