package io.github.hakjuoh.protege_mcp.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Complete optimistic-concurrency envelope for one per-window ontology workspace. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ModelRevision(
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("session_revision") long sessionRevision,
        @JsonProperty("semantic_fingerprint") String semanticFingerprint,
        @JsonProperty("document_fingerprint") String documentFingerprint) {

    public ModelRevision {
        workspaceId = ContractValues.workspaceId(workspaceId);
        if (sessionRevision < 0) {
            throw new IllegalArgumentException("session_revision must not be negative");
        }
        semanticFingerprint = ContractValues.fingerprint(semanticFingerprint, "semantic_fingerprint");
        documentFingerprint = ContractValues.fingerprint(documentFingerprint, "document_fingerprint");
    }
}
