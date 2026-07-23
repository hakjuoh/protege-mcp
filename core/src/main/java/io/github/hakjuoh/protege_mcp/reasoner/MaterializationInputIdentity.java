package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

/** Complete identity rechecked when a preview artifact is committed. */
public record MaterializationInputIdentity(
        ModelRevision modelRevision,
        String closureFingerprint,
        String importLockDigest,
        String mappingRevision,
        String policyDigest,
        String policyAssetDigest,
        String policyPath,
        ReasonerIdentity reasonerIdentity) {

    public MaterializationInputIdentity {
        Objects.requireNonNull(modelRevision, "modelRevision");
        closureFingerprint = digest(closureFingerprint, "closureFingerprint");
        importLockDigest = optionalDigest(importLockDigest, "importLockDigest");
        mappingRevision = optionalDigest(mappingRevision, "mappingRevision");
        policyDigest = digest(policyDigest, "policyDigest");
        policyAssetDigest = digest(policyAssetDigest, "policyAssetDigest");
        if (policyPath != null && (policyPath.isBlank() || policyPath.length() > 4096)) {
            throw new IllegalArgumentException("policyPath must be null or bounded");
        }
        Objects.requireNonNull(reasonerIdentity, "reasonerIdentity");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("workspace_id", modelRevision.workspaceId());
        revision.put("session_revision", modelRevision.sessionRevision());
        revision.put("semantic_fingerprint", modelRevision.semanticFingerprint());
        revision.put("document_fingerprint", modelRevision.documentFingerprint());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model_revision", revision);
        out.put("closure_fingerprint", closureFingerprint);
        out.put("import_lock_digest", importLockDigest);
        out.put("mapping_revision", mappingRevision);
        out.put("policy_digest", policyDigest);
        out.put("policy_asset_digest", policyAssetDigest);
        out.put("policy_path", policyPath);
        out.put("reasoner", reasonerIdentity.toMap());
        return out;
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }

    private static String optionalDigest(String value, String name) {
        return value == null ? null : digest(value, name);
    }
}
