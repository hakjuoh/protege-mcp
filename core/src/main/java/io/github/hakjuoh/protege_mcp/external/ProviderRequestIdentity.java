package io.github.hakjuoh.protege_mcp.external;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Length-framed identity for binding private continuation state to one normalized request. */
final class ProviderRequestIdentity {

    private ProviderRequestIdentity() { }

    static String digest(ProviderSearchRequest request, String profile) {
        List<String> values = new ArrayList<>();
        values.add(request.providerId());
        values.add(profile);
        values.add(request.query());
        values.add(Integer.toString(request.ontologies().size()));
        values.addAll(request.ontologies());
        values.add(request.language());
        values.add(Integer.toString(request.limit()));
        return digest(values);
    }

    static String digest(List<String> values) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(values.size()).array());
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        StringBuilder result = new StringBuilder(digest.getDigestLength() * 2);
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }
}
