package io.github.hakjuoh.protege_mcp.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A standards-facing RDF dataset identity, independent of the OWL workspace fingerprint. */
public record RdfDatasetFingerprint(
        @JsonProperty("canonicalization_algorithm") String canonicalizationAlgorithm,
        @JsonProperty("hash_algorithm") String hashAlgorithm,
        @JsonProperty("scope") String scope,
        @JsonProperty("rdf_dataset_fingerprint") String rdfDatasetFingerprint,
        @JsonProperty("canonical_nquads_bytes") long canonicalNQuadsBytes) {

    public RdfDatasetFingerprint {
        if (!RdfDatasetFingerprints.CANONICALIZATION_ALGORITHM.equals(canonicalizationAlgorithm)) {
            throw new IllegalArgumentException("canonicalization_algorithm must be RDFC-1.0");
        }
        if (!RdfDatasetFingerprints.HASH_ALGORITHM.equals(hashAlgorithm)) {
            throw new IllegalArgumentException("hash_algorithm must be SHA-256");
        }
        if (!RdfDatasetFingerprints.SCOPE.equals(scope)) {
            throw new IllegalArgumentException("scope must be root-ontology");
        }
        rdfDatasetFingerprint = ContractValues.fingerprint(
                rdfDatasetFingerprint, "rdf_dataset_fingerprint");
        if (canonicalNQuadsBytes < 0) {
            throw new IllegalArgumentException("canonical_nquads_bytes must not be negative");
        }
    }
}
