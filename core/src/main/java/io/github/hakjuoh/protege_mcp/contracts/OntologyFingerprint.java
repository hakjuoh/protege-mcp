package io.github.hakjuoh.protege_mcp.contracts;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of canonical ontology fingerprinting.
 *
 * <p>{@code releaseStable} is deliberately separate from the two digests. A digest is still useful as
 * a same-session conflict token when anonymous individuals are present, but OWLAPI {@code NodeID}s are
 * parser-local identifiers and therefore cannot support a cross-restart release attestation.
 */
public record OntologyFingerprint(
        @JsonProperty("canonicalization_version") int canonicalizationVersion,
        @JsonProperty("semantic_fingerprint") String semanticFingerprint,
        @JsonProperty("document_fingerprint") String documentFingerprint,
        @JsonProperty("stability") String stability,
        @JsonProperty("release_stable") boolean releaseStable,
        @JsonProperty("warnings") List<String> warnings) {

    public OntologyFingerprint {
        if (canonicalizationVersion < 1) {
            throw new IllegalArgumentException("canonicalization_version must be at least 1");
        }
        semanticFingerprint = ContractValues.fingerprint(semanticFingerprint, "semantic_fingerprint");
        documentFingerprint = ContractValues.fingerprint(documentFingerprint, "document_fingerprint");
        stability = ContractValues.nonBlank(stability, "stability");
        if (!"cross_restart".equals(stability) && !"session_only".equals(stability)) {
            throw new IllegalArgumentException("stability must be cross_restart or session_only");
        }
        if (releaseStable != "cross_restart".equals(stability)) {
            throw new IllegalArgumentException("release_stable must agree with stability");
        }
        warnings = ContractValues.strings(warnings, "warnings", true);
        if (releaseStable && !warnings.isEmpty()) {
            throw new IllegalArgumentException("a release-stable fingerprint must not carry warnings");
        }
        if (!releaseStable && warnings.isEmpty()) {
            throw new IllegalArgumentException("a session-only fingerprint must explain its limitation");
        }
    }
}
