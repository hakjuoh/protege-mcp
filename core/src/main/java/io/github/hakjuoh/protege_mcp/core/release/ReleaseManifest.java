package io.github.hakjuoh.protege_mcp.core.release;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;

/**
 * The {@code manifest_version: 1} release manifest. Deterministic given its inputs — the only
 * non-reproducible field, {@code created_at}, is a caller-supplied timestamp, so two builds of the
 * same release with the same timestamp are byte-identical.
 *
 * <p>A manifest is only well-formed for a release whose ontology is within the canonicalization
 * guarantees. When {@code releaseStable} is false (an anonymous-individual {@code session_only}
 * fingerprint), building a manifest is refused with {@link UnstableFingerprintException} rather than
 * stamping a stability the fingerprint cannot back. The public contract is documented under
 * {@code run_release_gate} and {@code prepare_release} in {@code docs/tools/quality.md}.
 */
public final class ReleaseManifest {

    public static final int MANIFEST_VERSION = 1;

    private ReleaseManifest() {
    }

    /** Refusal to manifest an ontology outside the canonicalization guarantees. */
    public static final class UnstableFingerprintException extends RuntimeException {
        public UnstableFingerprintException(String message) {
            super(message);
        }
    }

    /** One artifact entry: a release-output-relative path plus its digest and byte length. */
    public record Artifact(String path, String sha256, long bytes) { }

    /** The optional baseline comparison record embedded in the manifest. */
    public record Baseline(String versionIri, String report) { }

    /**
     * Build the manifest map (ready for {@link #toJson}). {@code createdAt} is an ISO-8601 UTC
     * timestamp string; {@code versionIri}/{@code importLockSha256}/{@code baseline} may be null.
     */
    public static Map<String, Object> build(String projectId, String ontologyIri, String versionIri,
            String createdAt, String policySha256, String importLockSha256, String semanticFingerprint,
            boolean releaseStable, List<Artifact> artifacts, String qcGate, String qcReport,
            Baseline baseline) {
        if (!releaseStable) {
            throw new UnstableFingerprintException("release fingerprint is session-only "
                    + "(anonymous individuals); a stable manifest cannot be produced");
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifest_version", MANIFEST_VERSION);
        manifest.put("project_id", projectId);
        manifest.put("ontology_iri", ontologyIri);
        manifest.put("version_iri", versionIri);
        manifest.put("created_at", createdAt);
        manifest.put("policy_sha256", policySha256);
        manifest.put("import_lock_sha256", importLockSha256);
        manifest.put("semantic_fingerprint", semanticFingerprint);
        List<Map<String, Object>> artifactList = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", artifact.path());
            entry.put("sha256", artifact.sha256());
            entry.put("bytes", artifact.bytes());
            artifactList.add(entry);
        }
        manifest.put("artifacts", artifactList);
        Map<String, Object> qc = new LinkedHashMap<>();
        qc.put("gate", qcGate);
        qc.put("report", qcReport);
        manifest.put("qc", qc);
        if (baseline != null) {
            Map<String, Object> baselineMap = new LinkedHashMap<>();
            baselineMap.put("version_iri", baseline.versionIri());
            baselineMap.put("report", baseline.report());
            manifest.put("baseline", baselineMap);
        }
        return manifest;
    }

    /** Canonical pretty JSON of a manifest map, deterministic byte-for-byte. */
    public static String toJson(Map<String, Object> manifest) {
        try {
            return ContractJson.mapper().copy()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render release manifest", e);
        }
    }
}
