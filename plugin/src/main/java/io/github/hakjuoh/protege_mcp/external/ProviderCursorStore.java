package io.github.hakjuoh.protege_mcp.external;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Restart-erased opaque cursor store for provider pagination. */
public final class ProviderCursorStore implements AutoCloseable {

    private static final String KIND = "cursor";
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_PER_PRINCIPAL = 32;
    public static final int DEFAULT_MAX_BACKEND = 256;
    public static final int DEFAULT_MAX_BYTES = 256 * 1_024;

    private final ScopedEphemeralRegistry<ProviderSearchRequest> entries;

    public ProviderCursorStore() {
        this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), DEFAULT_TTL,
                DEFAULT_MAX_PER_PRINCIPAL,
                DEFAULT_MAX_BACKEND, DEFAULT_MAX_BYTES);
    }

    ProviderCursorStore(Clock clock, Duration ttl, int maxPerPrincipal, int maxBackend,
            int maxBytes) {
        this(ticker(clock), ttl, maxPerPrincipal, maxBackend, maxBytes);
    }

    ProviderCursorStore(LongSupplier ticker, Duration ttl, int maxPerPrincipal, int maxBackend,
            int maxBytes) {
        entries = new ScopedEphemeralRegistry<>(KIND, ticker, ttl, maxPerPrincipal,
                maxBackend, maxBytes, ProviderCursorStore::encodedSize);
    }

    /** Store a complete normalized next-page request and return a random public token. */
    public String issue(ProviderSessionScope scope, ProviderSearchRequest nextRequest)
            throws ProviderFailure {
        requireContinuation(nextRequest);
        return entries.issue(scope, nextRequest);
    }

    /** Exclusively claim one cursor; close rolls back, while advance/complete consumes it. */
    public Claim claim(ProviderSessionScope scope, String token) throws ProviderFailure {
        return new Claim(entries.claim(scope, token));
    }

    public int revokeClient(String clientId) {
        return entries.revokeClient(clientId);
    }

    public int revokeGrant(String clientId, String grantId) {
        return entries.revokeGrant(clientId, grantId);
    }

    public int clearWorkspace(String workspaceId) {
        return entries.clearWorkspace(workspaceId);
    }

    public void clear() {
        entries.clear();
    }

    int activeCount() throws ProviderFailure {
        return entries.activeCount();
    }

    @Override
    public void close() {
        entries.close();
    }

    private static void requireContinuation(ProviderSearchRequest request) throws ProviderFailure {
        if (request == null || request.continuation() == null) {
            throw new ProviderFailure(KIND + "_invalid",
                    "Provider cursor requires a continuation request", false);
        }
    }

    private static int encodedSize(ProviderSearchRequest request) {
        long size = 256;
        size += bytes(request.providerId()) + bytes(request.query()) + bytes(request.language());
        size += bytes(request.continuation());
        for (String ontology : request.ontologies()) size += Integer.BYTES + bytes(ontology);
        if (size > Integer.MAX_VALUE) throw new IllegalArgumentException("cursor size overflow");
        return (int) size;
    }

    private static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static LongSupplier ticker(Clock clock) {
        Clock required = Objects.requireNonNull(clock, "clock");
        return required::millis;
    }

    /** Thread-safe exclusive claim with one terminal advance, completion, or rollback. */
    public static final class Claim implements AutoCloseable {
        private ScopedEphemeralRegistry.Claim<ProviderSearchRequest> claim;

        private Claim(ScopedEphemeralRegistry.Claim<ProviderSearchRequest> claim) {
            this.claim = claim;
        }

        public synchronized ProviderSearchRequest request() throws ProviderFailure {
            return current().value();
        }

        /** Consume this cursor and atomically install its next continuation. */
        public synchronized String advance(ProviderSearchRequest nextRequest) throws ProviderFailure {
            requireContinuation(nextRequest);
            String token = current().replace(nextRequest);
            claim = null;
            return token;
        }

        /** Consume a terminal cursor without installing a successor. */
        public synchronized void complete() throws ProviderFailure {
            current().complete();
            claim = null;
        }

        @Override
        public synchronized void close() {
            if (claim == null) return;
            claim.close();
            claim = null;
        }

        private synchronized ScopedEphemeralRegistry.Claim<ProviderSearchRequest> current() {
            if (claim == null) throw new IllegalStateException("provider cursor claim is closed");
            return claim;
        }

        @Override
        public String toString() {
            return "ProviderCursorClaim[redacted=true]";
        }
    }
}
