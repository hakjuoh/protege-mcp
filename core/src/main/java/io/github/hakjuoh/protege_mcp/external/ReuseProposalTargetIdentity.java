package io.github.hakjuoh.protege_mcp.external;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Opaque binding to the canonical project, policy source, and mapping target of a proposal. */
public record ReuseProposalTargetIdentity(String projectRootFingerprint,
        String policySourceFingerprint, String mappingTargetFingerprint,
        boolean mappingExists, String targetFingerprint) {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAX_IDENTITY_BYTES = 32 * 1_024;

    public ReuseProposalTargetIdentity {
        projectRootFingerprint = fingerprint(projectRootFingerprint,
                "project_root_fingerprint");
        policySourceFingerprint = fingerprint(policySourceFingerprint,
                "policy_source_fingerprint");
        mappingTargetFingerprint = fingerprint(mappingTargetFingerprint,
                "mapping_target_fingerprint");
        String computed = digest(List.of(projectRootFingerprint, policySourceFingerprint,
                mappingTargetFingerprint, Boolean.toString(mappingExists)));
        if (targetFingerprint == null) targetFingerprint = computed;
        if (!computed.equals(targetFingerprint)) {
            throw new IllegalArgumentException("target fingerprint does not match its fields");
        }
    }

    public static ReuseProposalTargetIdentity create(String canonicalProjectRoot,
            String canonicalPolicySource, String canonicalMappingTarget,
            boolean mappingExists) {
        return new ReuseProposalTargetIdentity(identity(canonicalProjectRoot, "project root"),
                identity(canonicalPolicySource, "policy source"),
                identity(canonicalMappingTarget, "mapping target"), mappingExists, null);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_root_fingerprint", projectRootFingerprint);
        result.put("policy_source_fingerprint", policySourceFingerprint);
        result.put("mapping_target_fingerprint", mappingTargetFingerprint);
        result.put("mapping_exists", mappingExists);
        result.put("target_fingerprint", targetFingerprint);
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String toString() {
        return "ReuseProposalTargetIdentity[redacted=true]";
    }

    private static String identity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " identity is required");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_IDENTITY_BYTES) {
            throw new IllegalArgumentException(field + " identity is too large");
        }
        return digest(List.of(value));
    }

    private static String digest(List<String> values) {
        final MessageDigest digest;
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
        StringBuilder result = new StringBuilder("sha256:");
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }

    private static String fingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be sha256 followed by lowercase hex");
        }
        return value;
    }
}
