package io.github.hakjuoh.protege_mcp.external;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Owner-only, durable atomic local credential store. It is intentionally not exposed as an MCP
 * tool. The embedded SHA-256 value is a corruption checksum; owner-only permissions are the
 * authenticity boundary.
 */
public final class OwnerCredentialStore {

    private static final byte[] MAGIC = "PMCPCRD2".getBytes(StandardCharsets.US_ASCII);
    private static final int DIGEST_BYTES = 32;
    private static final int INCARNATION_BYTES = 16;
    private static final int MAX_SECRET_BYTES = 8 * 1_024;
    private static final int MAX_ID_BYTES = 64;
    private static final int MAX_FILE_BYTES = MAGIC.length + Integer.BYTES + MAX_ID_BYTES
            + INCARNATION_BYTES + Long.BYTES + Integer.BYTES + MAX_SECRET_BYTES + DIGEST_BYTES;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path root;

    public OwnerCredentialStore() throws ProviderFailure {
        this(ProviderLocalPaths.credentials());
    }

    OwnerCredentialStore(Path root) throws ProviderFailure {
        this.root = OwnerOnlyFiles.prepareDirectory(root);
        OwnerOnlyFiles.withLock(this.root, "credentials.lock", () -> null);
    }

    public long rotate(String credentialId, byte[] secret) throws ProviderFailure {
        String id = id(credentialId);
        if (secret == null) throw invalid();
        byte[] snapshot = secret.clone();
        try {
            validateSecret(snapshot);
            return OwnerOnlyFiles.withLock(root, "credentials.lock",
                    () -> rotateLocked(id, snapshot));
        } finally {
            Arrays.fill(snapshot, (byte) 0);
        }
    }

    public CredentialLease open(String credentialId) throws ProviderFailure {
        String id = id(credentialId);
        byte[] encoded;
        try {
            encoded = OwnerOnlyFiles.read(root, file(id), MAX_FILE_BYTES);
        } catch (ProviderFailure failure) {
            if (failure.code().equals("provider_store_missing")) throw missing();
            throw failure;
        }
        try {
            return decode(id, encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    public void delete(String credentialId) throws ProviderFailure {
        String id = id(credentialId);
        OwnerOnlyFiles.withLock(root, "credentials.lock", () -> {
            OwnerOnlyFiles.delete(root, file(id));
            return null;
        });
    }

    private long rotateLocked(String id, byte[] secret) throws ProviderFailure {
        long generation;
        byte[] incarnation;
        try {
            if (OwnerOnlyFiles.exists(root, file(id))) {
                try (CredentialLease lease = open(id)) {
                    generation = Math.addExact(lease.generation(), 1);
                    incarnation = lease.copyIncarnation();
                }
            } else {
                generation = 1;
                incarnation = new byte[INCARNATION_BYTES];
                RANDOM.nextBytes(incarnation);
            }
        } catch (ArithmeticException exhausted) {
            throw new ProviderFailure("provider_credential_invalid",
                    "Owner credential generation is exhausted", false);
        }
        byte[] encoded = encode(id, incarnation, generation, secret);
        try {
            OwnerOnlyFiles.write(root, file(id), encoded);
            return generation;
        } finally {
            Arrays.fill(encoded, (byte) 0);
            Arrays.fill(incarnation, (byte) 0);
        }
    }

    private static byte[] encode(String id, byte[] incarnation, long generation, byte[] secret) {
        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        int contentLength = MAGIC.length + Integer.BYTES + idBytes.length
                + INCARNATION_BYTES + Long.BYTES + Integer.BYTES + secret.length;
        byte[] encoded = new byte[contentLength + DIGEST_BYTES];
        ByteBuffer value = ByteBuffer.wrap(encoded);
        value.put(MAGIC).putInt(idBytes.length).put(idBytes).put(incarnation)
                .putLong(generation).putInt(secret.length).put(secret);
        value.put(digest(encoded, 0, contentLength));
        return encoded;
    }

    private static CredentialLease decode(String id, byte[] encoded) throws ProviderFailure {
        byte[] incarnation = null;
        byte[] secret = null;
        try {
            if (encoded.length < MAGIC.length + Integer.BYTES + 1 + INCARNATION_BYTES
                    + Long.BYTES + Integer.BYTES + 1 + DIGEST_BYTES) {
                throw invalid();
            }
            int contentLength = encoded.length - DIGEST_BYTES;
            byte[] expected = digest(encoded, 0, contentLength);
            byte[] supplied = Arrays.copyOfRange(encoded, contentLength, encoded.length);
            if (!MessageDigest.isEqual(expected, supplied)) throw invalid();
            ByteBuffer value = ByteBuffer.wrap(encoded, 0, contentLength);
            byte[] magic = new byte[MAGIC.length];
            value.get(magic);
            if (!Arrays.equals(MAGIC, magic)) throw invalid();
            int idLength = value.getInt();
            if (idLength < 1 || idLength > MAX_ID_BYTES || value.remaining() < idLength) {
                throw invalid();
            }
            byte[] idBytes = new byte[idLength];
            value.get(idBytes);
            if (!id.equals(new String(idBytes, StandardCharsets.US_ASCII))) throw invalid();
            incarnation = new byte[INCARNATION_BYTES];
            value.get(incarnation);
            long generation = value.getLong();
            int length = value.getInt();
            if (generation < 1 || length < 1 || length > MAX_SECRET_BYTES
                    || value.remaining() != length) throw invalid();
            secret = new byte[length];
            value.get(secret);
            validateSecret(secret);
            CredentialLease lease = new CredentialLease(id, generation, incarnation, secret);
            incarnation = null;
            secret = null;
            return lease;
        } catch (ProviderFailure typed) {
            throw typed;
        } catch (RuntimeException malformed) {
            throw invalid();
        } finally {
            if (incarnation != null) Arrays.fill(incarnation, (byte) 0);
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
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

    private static void validateSecret(byte[] secret) throws ProviderFailure {
        if (secret == null || secret.length < 1 || secret.length > MAX_SECRET_BYTES) throw invalid();
        for (byte value : secret) {
            int character = value & 0xff;
            if (character < 0x20 || character > 0x7e) throw invalid();
        }
    }

    private static String id(String value) throws ProviderFailure {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) throw invalid();
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String file(String id) {
        return id + ".cred";
    }

    private static ProviderFailure invalid() {
        return new ProviderFailure("provider_credential_invalid",
                "Owner credential record is invalid", false);
    }

    private static ProviderFailure missing() {
        return new ProviderFailure("provider_credential_missing",
                "Owner credential is not available", false);
    }

    public static final class CredentialLease implements AutoCloseable {
        private final String id;
        private final long generation;
        private byte[] incarnation;
        private byte[] secret;

        private CredentialLease(String id, long generation, byte[] incarnation, byte[] secret) {
            this.id = id;
            this.generation = generation;
            this.incarnation = incarnation;
            this.secret = secret;
        }

        public String id() {
            return id;
        }

        public long generation() {
            return generation;
        }

        public synchronized String scopeFingerprint() throws ProviderFailure {
            requireOpen();
            byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer value = ByteBuffer.allocate(Integer.BYTES + idBytes.length
                    + Long.BYTES + incarnation.length);
            value.putInt(idBytes.length).put(idBytes).putLong(generation).put(incarnation);
            return "sha256:" + hex(digest(value.array(), 0, value.array().length));
        }

        synchronized byte[] copySecret() throws ProviderFailure {
            requireOpen();
            return secret.clone();
        }

        private synchronized byte[] copyIncarnation() throws ProviderFailure {
            requireOpen();
            return incarnation.clone();
        }

        private void requireOpen() throws ProviderFailure {
            if (secret == null) {
                throw new ProviderFailure("provider_credential_closed",
                        "Owner credential lease is closed", false);
            }
        }

        @Override
        public synchronized void close() {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
                secret = null;
                Arrays.fill(incarnation, (byte) 0);
                incarnation = null;
            }
        }

        @Override
        public String toString() {
            return "CredentialLease[id=" + id + ", generation=" + generation + ", redacted=true]";
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
}
