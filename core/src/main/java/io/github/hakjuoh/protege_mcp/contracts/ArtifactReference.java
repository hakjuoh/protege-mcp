package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Checksum-addressed report or release artifact referenced by a gate result. */
public record ArtifactReference(
        @JsonProperty("path") String path,
        @JsonProperty("sha256") String sha256,
        @JsonProperty("bytes") long bytes,
        @JsonProperty("details") Map<String, Object> details) {

    public ArtifactReference {
        path = ContractValues.nonBlank(path, "path");
        sha256 = ContractValues.digest(sha256, "sha256");
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        details = ContractValues.map(details);
    }
}
