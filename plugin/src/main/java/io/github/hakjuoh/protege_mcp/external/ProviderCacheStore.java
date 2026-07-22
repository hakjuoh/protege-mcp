package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Atomic owner-only persistence, HMAC identities, expiry, and LRU bounds for provider evidence. */
final class ProviderCacheStore {

    enum Kind { SEARCH, INSPECT }

    private static final String DATA_FILE = "responses.bin";
    private static final String KEY_FILE = "query-hmac.key";
    private static final String LOCK_FILE = "cache.lock";
    private static final byte[] DATA_MAGIC = "PMCPCHE2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_MAGIC = "PMCPHMK1".getBytes(StandardCharsets.US_ASCII);
    private static final int DIGEST_BYTES = 32;
    private static final int HMAC_KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final int maxBytes;

    ProviderCacheStore(Path root, Clock clock, Duration ttl, int maxEntries, int maxBytes)
            throws ProviderFailure {
        this.root = OwnerOnlyFiles.prepareDirectory(root);
        this.clock = clock;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        OwnerOnlyFiles.withLock(this.root, LOCK_FILE, () -> {
            byte[] key = loadKeyLocked();
            Arrays.fill(key, (byte) 0);
            return null;
        });
    }

    Optional<byte[]> get(Kind kind, String scope, List<String> identity,
            byte[] credentialCanary, byte[] queryCanary) throws ProviderFailure {
        return OwnerOnlyFiles.withLock(root, LOCK_FILE, () -> {
            State state = loadStateLocked();
            long now = now();
            boolean changed = observeClockAndExpiry(state, now);
            String key = identityKey(kind, scope, identity);
            Entry found = state.entries().stream().filter(entry -> entry.kind() == kind
                            && entry.scope().equals(scope) && entry.key().equals(key))
                    .findFirst().orElse(null);
            if (found != null && !ProviderCacheSafety.safe(
                    found.payloadInternal(), credentialCanary, queryCanary)) {
                state.entries().remove(found);
                found = null;
                changed = true;
            }
            if (found != null) {
                found.access(allocateAccess(state));
                changed = true;
            }
            if (changed) {
                if (state.entries().isEmpty()) discardDataLocked();
                else writeStateLocked(state);
            }
            return found == null ? Optional.empty() : Optional.of(found.payloadCopy());
        });
    }

    boolean put(Kind kind, String scope, List<String> identity, byte[] payload,
            byte[] credentialCanary, byte[] queryCanary) throws ProviderFailure {
        if (payload == null || payload.length > ProviderCacheCodec.MAX_PAYLOAD_BYTES
                || !ProviderCacheSafety.safe(payload, credentialCanary, queryCanary)) return false;
        return OwnerOnlyFiles.withLock(root, LOCK_FILE,
                () -> putLocked(kind, scope, identity, payload));
    }

    private boolean putLocked(Kind kind, String scope, List<String> identity, byte[] payload)
            throws ProviderFailure {
        State state = loadStateLocked();
        long now = now();
        observeClockAndExpiry(state, now);
        String key = identityKey(kind, scope, identity);
        state.entries().removeIf(entry -> entry.kind() == kind
                && entry.scope().equals(scope) && entry.key().equals(key));
        long lifetime = ttl.toMillis();
        Entry candidate = new Entry(kind, scope, key, now, lifetime, expiresAt(now),
                allocateAccess(state), payload);
        state.entries().add(candidate);
        while (state.entries().size() > maxEntries || encodedSize(state) > maxBytes) {
            Entry oldest = state.entries().stream().min(Comparator.comparingLong(Entry::access))
                    .orElse(null);
            if (oldest == null || oldest == candidate) return false;
            state.entries().remove(oldest);
        }
        writeStateLocked(state);
        return true;
    }

    private String identityKey(Kind kind, String scope, List<String> identity)
            throws ProviderFailure {
        byte[] ownerKey = loadKeyLocked();
        try {
            return hmac(ownerKey, kind, scope, identity);
        } finally {
            Arrays.fill(ownerKey, (byte) 0);
        }
    }

    private State loadStateLocked() throws ProviderFailure {
        if (!OwnerOnlyFiles.exists(root, DATA_FILE)) return new State(1, 0, new ArrayList<>());
        byte[] encoded;
        try {
            encoded = OwnerOnlyFiles.read(root, DATA_FILE, maxBytes);
        } catch (ProviderFailure failure) {
            discardDataLocked();
            return new State(1, 0, new ArrayList<>());
        }
        try {
            return decodeState(encoded);
        } catch (IOException | RuntimeException invalid) {
            discardDataLocked();
            return new State(1, 0, new ArrayList<>());
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private void writeStateLocked(State state) throws ProviderFailure {
        byte[] encoded;
        try {
            encoded = encodeState(state);
        } catch (IOException | RuntimeException invalid) {
            throw cacheFailure("Provider cache state exceeds its bounds");
        }
        try {
            OwnerOnlyFiles.write(root, DATA_FILE, encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private void discardDataLocked() throws ProviderFailure {
        OwnerOnlyFiles.delete(root, DATA_FILE);
    }

    private byte[] loadKeyLocked() throws ProviderFailure {
        if (!OwnerOnlyFiles.exists(root, KEY_FILE)) {
            byte[] key = new byte[HMAC_KEY_BYTES];
            RANDOM.nextBytes(key);
            byte[] encoded = encodeKey(key);
            try {
                OwnerOnlyFiles.write(root, KEY_FILE, encoded);
            } finally {
                Arrays.fill(encoded, (byte) 0);
                Arrays.fill(key, (byte) 0);
            }
        }
        byte[] encoded = OwnerOnlyFiles.read(root, KEY_FILE,
                KEY_MAGIC.length + HMAC_KEY_BYTES + DIGEST_BYTES);
        try {
            return decodeKey(encoded);
        } catch (RuntimeException invalid) {
            throw cacheFailure("Owner query HMAC key is invalid");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private long now() throws ProviderFailure {
        try {
            long value = clock.instant().toEpochMilli();
            if (value < 0) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) {
            throw cacheFailure("Provider cache clock is invalid");
        }
    }

    private long expiresAt(long now) throws ProviderFailure {
        try {
            return Math.addExact(now, ttl.toMillis());
        } catch (ArithmeticException overflow) {
            throw cacheFailure("Provider cache expiry is invalid");
        }
    }

    private static boolean observeClockAndExpiry(State state, long now) {
        boolean changed = false;
        if (now < state.highWater()) {
            changed = !state.entries().isEmpty() || state.highWater() != now;
            state.entries().clear();
            state.highWater(now);
            return changed;
        }
        if (now > state.highWater()) {
            state.highWater(now);
            changed = true;
        }
        return state.entries().removeIf(entry -> !entry.validAt(now)) || changed;
    }

    private static long allocateAccess(State state) throws ProviderFailure {
        long value = state.nextAccess();
        if (value < 1 || value == Long.MAX_VALUE) {
            throw cacheFailure("Provider cache LRU counter is exhausted");
        }
        state.nextAccess(value + 1);
        return value;
    }

    private byte[] encodeState(State state) throws IOException {
        int size = encodedSize(state);
        if (size > maxBytes) throw new IOException("cache state exceeds bound");
        byte[] encoded = new byte[size];
        ByteBuffer value = ByteBuffer.wrap(encoded);
        value.put(DATA_MAGIC).putLong(state.nextAccess()).putLong(state.highWater())
                .putInt(state.entries().size());
        for (Entry entry : state.entries()) {
            value.put((byte) entry.kind().ordinal()).putLong(entry.createdAt())
                    .putLong(entry.lifetimeMillis()).putLong(entry.expiresAt())
                    .putLong(entry.access());
            put(value, entry.scope().getBytes(StandardCharsets.US_ASCII));
            put(value, entry.key().getBytes(StandardCharsets.US_ASCII));
            put(value, entry.payloadInternal());
        }
        int contentLength = value.position();
        value.put(digest(encoded, 0, contentLength));
        return encoded;
    }

    private State decodeState(byte[] encoded) throws IOException {
        if (encoded.length < DATA_MAGIC.length + Long.BYTES * 2 + Integer.BYTES + DIGEST_BYTES
                || encoded.length > maxBytes) throw new IOException("cache state size is invalid");
        int contentLength = encoded.length - DIGEST_BYTES;
        if (!MessageDigest.isEqual(digest(encoded, 0, contentLength),
                Arrays.copyOfRange(encoded, contentLength, encoded.length))) {
            throw new IOException("cache checksum mismatch");
        }
        ByteBuffer value = ByteBuffer.wrap(encoded, 0, contentLength);
        byte[] magic = new byte[DATA_MAGIC.length];
        value.get(magic);
        if (!Arrays.equals(DATA_MAGIC, magic)) throw new IOException("cache magic mismatch");
        long nextAccess = value.getLong();
        long highWater = value.getLong();
        int count = value.getInt();
        if (nextAccess < 1 || highWater < 0 || count < 0 || count > maxEntries) {
            throw new IOException("cache header invalid");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        long maximumAccess = 0;
        for (int index = 0; index < count; index++) {
            int ordinal = value.get() & 0xff;
            if (ordinal >= Kind.values().length) throw new IOException("cache kind invalid");
            long entryCreated = value.getLong();
            long entryLifetime = value.getLong();
            long entryExpiry = value.getLong();
            long access = value.getLong();
            String scope = ascii(get(value, 80), "sha256:[0-9a-f]{64}");
            String key = ascii(get(value, 96), "hmac-sha256:[0-9a-f]{64}");
            byte[] payload = get(value, ProviderCacheCodec.MAX_PAYLOAD_BYTES);
            try {
                if (!validLifetime(entryCreated, entryLifetime, entryExpiry) || access < 1
                        || !ProviderCacheSafety.safe(payload, null, null)) {
                    throw new IOException("cache entry invalid");
                }
                Kind kind = Kind.values()[ordinal];
                validatePayload(kind, payload);
                if (!unique.add(kind + "\n" + scope + "\n" + key)) {
                    throw new IOException("cache entry duplicated");
                }
                entries.add(new Entry(kind, scope, key, entryCreated, entryLifetime,
                        entryExpiry, access, payload));
                maximumAccess = Math.max(maximumAccess, access);
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
        }
        if (value.hasRemaining() || nextAccess <= maximumAccess) {
            throw new IOException("cache tail invalid");
        }
        return new State(nextAccess, highWater, entries);
    }

    private static void validatePayload(Kind kind, byte[] payload) throws IOException {
        if (kind == Kind.SEARCH) ProviderCacheCodec.decodePage(payload);
        else ProviderCacheCodec.decodeResult(payload);
    }

    private int encodedSize(State state) {
        long size = DATA_MAGIC.length + Long.BYTES * 2 + Integer.BYTES + DIGEST_BYTES;
        for (Entry entry : state.entries()) {
            size += 1L + Long.BYTES * 4 + Integer.BYTES * 3
                    + entry.scope().length() + entry.key().length()
                    + entry.payloadInternal().length;
        }
        if (size > Integer.MAX_VALUE) throw new IllegalArgumentException("cache size overflow");
        return (int) size;
    }

    private static String hmac(byte[] ownerKey, Kind kind, String scope, List<String> values)
            throws ProviderFailure {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ownerKey, "HmacSHA256"));
            update(mac, kind.name());
            update(mac, scope);
            update(mac, Integer.toString(values.size()));
            for (String value : values) update(mac, value);
            return "hmac-sha256:" + hex(mac.doFinal());
        } catch (Exception unavailable) {
            throw cacheFailure("Owner query HMAC is unavailable");
        }
    }

    private static void update(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
        Arrays.fill(bytes, (byte) 0);
    }

    private static byte[] encodeKey(byte[] key) {
        byte[] encoded = new byte[KEY_MAGIC.length + HMAC_KEY_BYTES + DIGEST_BYTES];
        ByteBuffer value = ByteBuffer.wrap(encoded);
        value.put(KEY_MAGIC).put(key);
        value.put(digest(encoded, 0, KEY_MAGIC.length + HMAC_KEY_BYTES));
        return encoded;
    }

    private static byte[] decodeKey(byte[] encoded) {
        if (encoded.length != KEY_MAGIC.length + HMAC_KEY_BYTES + DIGEST_BYTES) {
            throw new IllegalArgumentException();
        }
        int contentLength = encoded.length - DIGEST_BYTES;
        if (!MessageDigest.isEqual(digest(encoded, 0, contentLength),
                Arrays.copyOfRange(encoded, contentLength, encoded.length))) {
            throw new IllegalArgumentException();
        }
        ByteBuffer value = ByteBuffer.wrap(encoded, 0, contentLength);
        byte[] magic = new byte[KEY_MAGIC.length];
        value.get(magic);
        if (!Arrays.equals(KEY_MAGIC, magic)) throw new IllegalArgumentException();
        byte[] key = new byte[HMAC_KEY_BYTES];
        value.get(key);
        return key;
    }

    private static void put(ByteBuffer target, byte[] value) {
        target.putInt(value.length).put(value);
    }

    private static byte[] get(ByteBuffer source, int maximum) throws IOException {
        if (source.remaining() < Integer.BYTES) throw new IOException("cache field missing");
        int length = source.getInt();
        if (length < 1 || length > maximum || source.remaining() < length) {
            throw new IOException("cache field invalid");
        }
        byte[] value = new byte[length];
        source.get(value);
        return value;
    }

    private static String ascii(byte[] bytes, String pattern) throws IOException {
        String value = new String(bytes, StandardCharsets.US_ASCII);
        if (!value.matches(pattern)) throw new IOException("cache identity invalid");
        return value;
    }

    private static byte[] digest(byte[] value, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(value, offset, length);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            int unsigned = item & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }

    private static ProviderFailure cacheFailure(String message) {
        return new ProviderFailure("provider_cache_invalid", message, false);
    }

    private static boolean validLifetime(long createdAt, long lifetime, long expiresAt) {
        if (createdAt < 0 || lifetime < 1 || lifetime > Duration.ofHours(24).toMillis()) {
            return false;
        }
        try {
            return Math.addExact(createdAt, lifetime) == expiresAt;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static final class Entry {
        private final Kind kind;
        private final String scope;
        private final String key;
        private final long createdAt;
        private final long lifetimeMillis;
        private final long expiresAt;
        private long access;
        private final byte[] payload;

        private Entry(Kind kind, String scope, String key, long createdAt, long lifetimeMillis,
                long expiresAt, long access, byte[] payload) {
            this.kind = kind;
            this.scope = scope;
            this.key = key;
            this.createdAt = createdAt;
            this.lifetimeMillis = lifetimeMillis;
            this.expiresAt = expiresAt;
            this.access = access;
            this.payload = payload.clone();
        }

        Kind kind() { return kind; }
        String scope() { return scope; }
        String key() { return key; }
        long createdAt() { return createdAt; }
        long lifetimeMillis() { return lifetimeMillis; }
        long expiresAt() { return expiresAt; }
        boolean validAt(long now) {
            return validLifetime(createdAt, lifetimeMillis, expiresAt)
                    && createdAt <= now && expiresAt > now;
        }
        long access() { return access; }
        void access(long value) { access = value; }
        byte[] payloadInternal() { return payload; }
        byte[] payloadCopy() { return payload.clone(); }
    }

    private static final class State {
        private long nextAccess;
        private long highWater;
        private final List<Entry> entries;

        private State(long nextAccess, long highWater, List<Entry> entries) {
            this.nextAccess = nextAccess;
            this.highWater = highWater;
            this.entries = entries;
        }

        long nextAccess() { return nextAccess; }
        void nextAccess(long value) { nextAccess = value; }
        long highWater() { return highWater; }
        void highWater(long value) { highWater = value; }
        List<Entry> entries() { return entries; }
    }
}
