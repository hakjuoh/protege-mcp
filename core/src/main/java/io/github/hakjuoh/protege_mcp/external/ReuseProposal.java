package io.github.hakjuoh.protege_mcp.external;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;

/** Immutable typed proposal evidence; constructing or rendering it never changes project state. */
public record ReuseProposal(ProviderResult providerResult,
        ReuseProposalInputIdentity inputIdentity, ReuseOperation operation,
        String proposalFingerprint) {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = ContractJson.mapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ReuseProposal {
        if (providerResult == null || inputIdentity == null || operation == null) {
            throw new IllegalArgumentException("proposal evidence, identity, and operation are required");
        }
        if (!inputIdentity.matches(providerResult)) {
            throw new IllegalArgumentException("proposal identity does not match provider evidence");
        }
        operation.validateAgainst(providerResult);
        String computed = computeFingerprint(providerResult, inputIdentity, operation);
        if (proposalFingerprint == null) proposalFingerprint = computed;
        if (!FINGERPRINT.matcher(proposalFingerprint).matches()
                || !computed.equals(proposalFingerprint)) {
            throw new IllegalArgumentException("proposal fingerprint does not match its inputs");
        }
    }

    public static ReuseProposal create(ProviderResult providerResult,
            ReuseProposalInputIdentity inputIdentity, ReuseOperation operation) {
        return new ReuseProposal(providerResult, inputIdentity, operation, null);
    }

    public ReuseAction action() {
        return operation.action();
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_result", providerResult.toJson());
        result.put("input_identity", inputIdentity.toJson());
        result.put("action", action().wire());
        result.put("suggested_operations", operation.toJson());
        result.put("proposal_fingerprint", proposalFingerprint);
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String toString() {
        return "ReuseProposal[redacted=true]";
    }

    private static String computeFingerprint(ProviderResult providerResult,
            ReuseProposalInputIdentity identity, ReuseOperation operation) {
        final byte[] operationBytes;
        try {
            operationBytes = JSON.writeValueAsBytes(operation.toJson());
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("reuse operation is not serializable", impossible);
        }
        return digest(providerResult.resultFingerprint(), identity.inputFingerprint(),
                operation.action().wire(), operationBytes);
    }

    private static String digest(String resultFingerprint, String inputFingerprint,
            String action, byte[] operation) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        update(digest, resultFingerprint.getBytes(StandardCharsets.UTF_8));
        update(digest, inputFingerprint.getBytes(StandardCharsets.UTF_8));
        update(digest, action.getBytes(StandardCharsets.UTF_8));
        update(digest, operation);
        StringBuilder result = new StringBuilder("sha256:");
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0x0f, 16));
        }
        return result.toString();
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
