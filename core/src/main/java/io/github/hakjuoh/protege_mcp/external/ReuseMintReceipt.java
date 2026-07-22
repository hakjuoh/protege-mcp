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

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

/** Immutable receipt proving the ontology step of a mint-and-map reuse saga. */
public record ReuseMintReceipt(String proposalFingerprint, String entityIri,
        ModelRevision baseRevision, ModelRevision mintedRevision,
        String mappingSetId, String mappingSetLicense, String configuredPolicyPath,
        String receiptFingerprint) {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    public ReuseMintReceipt {
        proposalFingerprint = fingerprint(proposalFingerprint, "proposal fingerprint");
        entityIri = ProviderValues.absoluteIri(entityIri, "minted entity IRI", 4_096);
        if (baseRevision == null || mintedRevision == null) {
            throw new IllegalArgumentException("mint receipt revisions are required");
        }
        if (!baseRevision.workspaceId().equals(mintedRevision.workspaceId())) {
            throw new IllegalArgumentException("mint receipt revisions belong to different workspaces");
        }
        mappingSetId = optionalIri(mappingSetId, "mapping set IRI");
        mappingSetLicense = optionalIri(mappingSetLicense, "mapping set license");
        configuredPolicyPath = optionalText(configuredPolicyPath, "configured policy path", 4_096);
        String computed = compute(proposalFingerprint, entityIri, baseRevision, mintedRevision,
                mappingSetId, mappingSetLicense, configuredPolicyPath);
        if (receiptFingerprint == null) receiptFingerprint = computed;
        if (!computed.equals(receiptFingerprint)) {
            throw new IllegalArgumentException("mint receipt fingerprint does not match its fields");
        }
    }

    public static ReuseMintReceipt create(String proposalFingerprint, String entityIri,
            ModelRevision baseRevision, ModelRevision mintedRevision,
            String mappingSetId, String mappingSetLicense, String configuredPolicyPath) {
        return new ReuseMintReceipt(proposalFingerprint, entityIri,
                baseRevision, mintedRevision, mappingSetId, mappingSetLicense,
                configuredPolicyPath, null);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposal_fingerprint", proposalFingerprint);
        result.put("entity_iri", entityIri);
        result.put("base_revision", revision(baseRevision));
        result.put("minted_revision", revision(mintedRevision));
        if (mappingSetId != null) result.put("mapping_set_id", mappingSetId);
        if (mappingSetLicense != null) result.put("mapping_set_license", mappingSetLicense);
        if (configuredPolicyPath != null) {
            result.put("configured_policy_path", configuredPolicyPath);
        }
        result.put("receipt_fingerprint", receiptFingerprint);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> revision(ModelRevision value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace_id", value.workspaceId());
        result.put("session_revision", value.sessionRevision());
        result.put("semantic_fingerprint", value.semanticFingerprint());
        result.put("document_fingerprint", value.documentFingerprint());
        return Collections.unmodifiableMap(result);
    }

    private static String compute(String proposalFingerprint, String entityIri,
            ModelRevision base, ModelRevision minted, String mappingSetId,
            String mappingSetLicense, String configuredPolicyPath) {
        return digest(List.of(proposalFingerprint, entityIri,
                base.workspaceId(), Long.toString(base.sessionRevision()),
                base.semanticFingerprint(), base.documentFingerprint(),
                minted.workspaceId(), Long.toString(minted.sessionRevision()),
                minted.semanticFingerprint(), minted.documentFingerprint(),
                value(mappingSetId), value(mappingSetLicense), value(configuredPolicyPath)));
    }

    private static String fingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be sha256:<64 lowercase hex>");
        }
        return value;
    }

    private static String optionalIri(String value, String field) {
        return value == null ? null : ProviderValues.absoluteIri(value, field, 4_096);
    }

    private static String optionalText(String value, String field, int maximum) {
        if (value == null) return null;
        ProviderValues.wellFormed(value, field);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String digest(List<String> values) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
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
}
