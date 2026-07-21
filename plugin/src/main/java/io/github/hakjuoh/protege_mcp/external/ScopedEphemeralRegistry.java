package io.github.hakjuoh.protege_mcp.external;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/** Bounded process-memory registry with exact scope, expiry, revocation, and atomic claim/replace. */
final class ScopedEphemeralRegistry<T> implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_LENGTH = 43;
    private static final long MAX_AGGREGATE_BYTES = 64L * 1_024 * 1_024;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final String kind;
    private final LongSupplier ticker;
    private final long ttlMillis;
    private final int maxPerPrincipal;
    private final int maxBackend;
    private final int maxValueBytes;
    private final ToIntFunction<T> size;
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private boolean closed;
    private boolean tickerStarted;
    private boolean tickerInvalid;
    private long tickerOrigin;
    private long lastElapsed;

    ScopedEphemeralRegistry(String kind, LongSupplier ticker, Duration ttl, int maxPerPrincipal,
            int maxBackend, int maxValueBytes, ToIntFunction<T> size) {
        validateConfiguration(kind, ticker, ttl, maxPerPrincipal, maxBackend, maxValueBytes,
                size);
        this.kind = kind;
        this.ticker = ticker;
        this.ttlMillis = ttl.toMillis();
        this.maxPerPrincipal = maxPerPrincipal;
        this.maxBackend = maxBackend;
        this.maxValueBytes = maxValueBytes;
        this.size = size;
    }

    synchronized String issue(ProviderSessionScope scope, T value) throws ProviderFailure {
        requireOpen();
        requireScope(scope);
        checkedSize(value);
        long now = now();
        cleanup(now);
        if (entries.size() >= maxBackend || principalCount(scope) >= maxPerPrincipal) {
            throw failure("quota_exceeded", "Provider " + kind + " quota is exhausted");
        }
        String token = token();
        entries.put(token, new Entry<>(scope, value, expiresAt(now)));
        return token;
    }

    synchronized Claim<T> claim(ProviderSessionScope scope, String token) throws ProviderFailure {
        requireOpen();
        requireScope(scope);
        String key = requireToken(token);
        long now = now();
        Entry<T> entry = entries.get(key);
        if (entry == null || !entry.scope.equals(scope)) throw invalid();
        if (entry.expiresAt <= now) {
            entries.remove(key, entry);
            throw failure("expired", "Provider " + kind + " expired");
        }
        cleanup(now);
        if (entry.claim != null) {
            throw failure("in_use", "Provider " + kind + " is already in use");
        }
        Object marker = new Object();
        entry.claim = marker;
        return new Claim<>(this, key, entry, marker);
    }

    synchronized int revokeClient(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("client id is required");
        return remove(entry -> entry.scope.clientId().equals(clientId));
    }

    synchronized int revokeGrant(String clientId, String grantId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("client id is required");
        }
        String normalizedGrant = grantId == null || grantId.isBlank() ? "" : grantId;
        return remove(entry -> entry.scope.clientId().equals(clientId)
                && entry.scope.grantId().equals(normalizedGrant));
    }

    synchronized int clearWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspace id is required");
        }
        return remove(entry -> entry.scope.workspaceId().equals(workspaceId));
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int activeCount() throws ProviderFailure {
        requireOpen();
        cleanup(now());
        return entries.size();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        clear();
        closed = true;
    }

    private synchronized String replace(String token, Entry<T> expected, Object marker,
            T successor) throws ProviderFailure {
        requireOpen();
        long now = now();
        Entry<T> current = entries.get(token);
        if (current != expected || current.claim != marker) {
            throw invalid();
        }
        if (current.expiresAt <= now) {
            entries.remove(token, current);
            throw failure("expired", "Provider " + kind + " expired before completion");
        }
        cleanup(now);
        if (successor == null) {
            entries.remove(token, current);
            return null;
        }
        checkedSize(successor);
        long successorExpiry = expiresAt(now);
        String nextToken = token();
        entries.remove(token, current);
        entries.put(nextToken, new Entry<>(current.scope, successor, successorExpiry));
        return nextToken;
    }

    private synchronized T value(String token, Entry<T> expected, Object marker)
            throws ProviderFailure {
        requireOpen();
        long now = now();
        Entry<T> current = entries.get(token);
        if (current != expected || current.claim != marker) {
            throw invalid();
        }
        if (current.expiresAt <= now) {
            entries.remove(token, current);
            throw failure("expired", "Provider " + kind + " expired while in use");
        }
        cleanup(now);
        return current.value;
    }

    private synchronized void release(String token, Entry<T> expected, Object marker) {
        Entry<T> current = entries.get(token);
        if (current == expected && current.claim == marker) {
            current.claim = null;
        }
    }

    private int checkedSize(T value) throws ProviderFailure {
        if (value == null) throw invalid();
        final int bytes;
        try {
            bytes = size.applyAsInt(value);
        } catch (RuntimeException invalid) {
            throw failure("invalid", "Provider " + kind + " state is invalid");
        }
        if (bytes < 1 || bytes > maxValueBytes) {
            throw failure("too_large", "Provider " + kind + " state is too large");
        }
        return bytes;
    }

    private long now() throws ProviderFailure {
        if (tickerInvalid) throw invalid();
        try {
            long raw = ticker.getAsLong();
            if (!tickerStarted) {
                tickerStarted = true;
                tickerOrigin = raw;
                lastElapsed = 0;
                return 0;
            }
            long elapsed = Math.subtractExact(raw, tickerOrigin);
            if (elapsed < lastElapsed) return invalidateTicker();
            lastElapsed = elapsed;
            return elapsed;
        } catch (RuntimeException invalid) {
            return invalidateTicker();
        }
    }

    private long invalidateTicker() throws ProviderFailure {
        entries.clear();
        tickerInvalid = true;
        throw failure("invalid", "Provider " + kind + " timer is invalid");
    }

    private long expiresAt(long now) throws ProviderFailure {
        try {
            return Math.addExact(now, ttlMillis);
        } catch (ArithmeticException overflow) {
            throw failure("invalid", "Provider " + kind + " expiry is invalid");
        }
    }

    private void cleanup(long now) {
        entries.values().removeIf(entry -> entry.expiresAt <= now);
    }

    private int principalCount(ProviderSessionScope scope) {
        String principal = scope.principalKey();
        int count = 0;
        for (Entry<T> entry : entries.values()) {
            if (entry.scope.principalKey().equals(principal)) count++;
        }
        return count;
    }

    private int remove(Predicate<Entry<T>> predicate) {
        int before = entries.size();
        entries.values().removeIf(predicate);
        return before - entries.size();
    }

    private String token() throws ProviderFailure {
        for (int attempt = 0; attempt < 16; attempt++) {
            byte[] bytes = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(bytes);
            String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Arrays.fill(bytes, (byte) 0);
            if (!entries.containsKey(candidate)) return candidate;
        }
        throw failure("invalid", "Provider " + kind + " token generation failed");
    }

    private String requireToken(String token) throws ProviderFailure {
        if (token == null || token.length() != TOKEN_LENGTH
                || !TOKEN_PATTERN.matcher(token).matches()) throw invalid();
        return token;
    }

    private void requireOpen() throws ProviderFailure {
        if (closed) throw failure("invalid", "Provider " + kind + " store is closed");
    }

    private static void requireScope(ProviderSessionScope scope) throws ProviderFailure {
        if (scope == null) throw new ProviderFailure("provider_scope_invalid",
                "Provider session scope is missing", false);
    }

    private ProviderFailure invalid() {
        return failure("invalid", "Provider " + kind + " is invalid or unavailable");
    }

    private ProviderFailure failure(String suffix, String message) {
        return new ProviderFailure(kind + "_" + suffix, message, false);
    }

    private static <T> void validateConfiguration(String kind, LongSupplier ticker, Duration ttl,
            int maxPerPrincipal, int maxBackend, int maxValueBytes, ToIntFunction<T> size) {
        if (kind == null || !kind.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("ephemeral registry kind is invalid");
        }
        if (ticker == null || size == null) {
            throw new IllegalArgumentException("ephemeral registry functions are required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(Duration.ofHours(1)) > 0 || ttl.toMillis() < 1) {
            throw new IllegalArgumentException("ephemeral registry ttl is invalid");
        }
        if (maxPerPrincipal < 1 || maxBackend < maxPerPrincipal || maxBackend > 4_096) {
            throw new IllegalArgumentException("ephemeral registry entry limits are invalid");
        }
        if (maxValueBytes < 1 || maxValueBytes > 1024 * 1024
                || (long) maxBackend * maxValueBytes > MAX_AGGREGATE_BYTES) {
            throw new IllegalArgumentException("ephemeral registry memory limit is invalid");
        }
    }

    private static final class Entry<T> {
        private final ProviderSessionScope scope;
        private final T value;
        private final long expiresAt;
        private Object claim;

        private Entry(ProviderSessionScope scope, T value, long expiresAt) {
            this.scope = Objects.requireNonNull(scope);
            this.value = Objects.requireNonNull(value);
            this.expiresAt = expiresAt;
        }
    }

    /** One exclusive-use marker. Its methods serialize terminal transitions per claim. */
    static final class Claim<T> implements AutoCloseable {
        private ScopedEphemeralRegistry<T> owner;
        private final String token;
        private final Entry<T> entry;
        private Object marker;

        private Claim(ScopedEphemeralRegistry<T> owner, String token, Entry<T> entry,
                Object marker) {
            this.owner = owner;
            this.token = token;
            this.entry = entry;
            this.marker = marker;
        }

        synchronized T value() throws ProviderFailure {
            requireActive();
            return owner.value(token, entry, marker);
        }

        synchronized String replace(T successor) throws ProviderFailure {
            requireActive();
            String next = owner.replace(token, entry, marker, successor);
            finish();
            return next;
        }

        synchronized void complete() throws ProviderFailure {
            replace(null);
        }

        @Override
        public synchronized void close() {
            if (owner == null) return;
            owner.release(token, entry, marker);
            finish();
        }

        private synchronized void requireActive() {
            if (owner == null) throw new IllegalStateException("ephemeral claim is closed");
        }

        private synchronized void finish() {
            owner = null;
            marker = null;
        }
    }
}
