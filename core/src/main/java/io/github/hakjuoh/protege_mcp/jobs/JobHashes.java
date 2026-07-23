package io.github.hakjuoh.protege_mcp.jobs;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

final class JobHashes {
    private JobHashes() {
    }

    static String digest(String... values) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        for (String value : values) {
            byte[] bytes = (value == null ? "<absent>" : value)
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return "sha256:" + hex(digest.digest());
    }

    static String digest(byte[] bytes) {
        try {
            return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static String requireDigest(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    static <T extends Comparable<? super T>> Set<T> immutableSortedSet(
            Collection<? extends T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(values)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }
}
