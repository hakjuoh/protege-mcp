package io.github.hakjuoh.protege_mcp.reasoner;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLAxiom;

/** Private immutable inference artifact produced by preview and consumed by commit. */
public final class MaterializationArtifact {
    private final String artifactId;
    private final String artifactFingerprint;
    private final String artifactDigest;
    private final String materializationDigest;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final MaterializationRequest request;
    private final MaterializationInputIdentity inputIdentity;
    private final Set<OWLAxiom> axioms;
    private final Map<String, CategoryResult> categoryResults;
    private final long canonicalBytes;

    MaterializationArtifact(String artifactId, String artifactFingerprint,
            String artifactDigest, String materializationDigest, Instant createdAt,
            Instant expiresAt, MaterializationRequest request,
            MaterializationInputIdentity inputIdentity, Set<OWLAxiom> axioms,
            Map<String, CategoryResult> categoryResults, long canonicalBytes) {
        this.artifactId = bounded(artifactId, "artifactId", 128);
        this.artifactFingerprint = digest(artifactFingerprint, "artifactFingerprint");
        this.artifactDigest = digest(artifactDigest, "artifactDigest");
        this.materializationDigest = digest(materializationDigest, "materializationDigest");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("artifact expiry must follow creation");
        }
        this.request = Objects.requireNonNull(request, "request");
        this.inputIdentity = Objects.requireNonNull(inputIdentity, "inputIdentity");
        this.axioms = Collections.unmodifiableSet(new LinkedHashSet<>(axioms));
        this.categoryResults = Collections.unmodifiableMap(
                new LinkedHashMap<>(categoryResults));
        if (canonicalBytes < 0) throw new IllegalArgumentException("canonicalBytes is negative");
        this.canonicalBytes = canonicalBytes;
    }

    public String artifactId() {
        return artifactId;
    }

    public String artifactFingerprint() {
        return artifactFingerprint;
    }

    public String artifactDigest() {
        return artifactDigest;
    }

    public String materializationDigest() {
        return materializationDigest;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public MaterializationRequest request() {
        return request;
    }

    public MaterializationInputIdentity inputIdentity() {
        return inputIdentity;
    }

    public Set<OWLAxiom> axioms() {
        return axioms;
    }

    public long canonicalBytes() {
        return canonicalBytes;
    }

    public Map<String, Object> report() {
        List<String> requested = request.categories().stream()
                .map(MaterializationCategory::value).toList();
        List<String> supported = categoryResults.values().stream()
                .filter(CategoryResult::supported).map(CategoryResult::category).toList();
        List<String> produced = categoryResults.values().stream()
                .filter(result -> result.producedAxioms() > 0)
                .map(CategoryResult::category).toList();
        List<String> skipped = categoryResults.values().stream()
                .filter(result -> result.producedAxioms() == 0)
                .map(CategoryResult::category).toList();
        List<Map<String, Object>> categories = categoryResults.values().stream()
                .map(CategoryResult::toMap).toList();
        long asserted = categoryResults.values().stream()
                .mapToLong(CategoryResult::assertedCollisions).sum();
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifact_id", artifactId);
        artifact.put("artifact_fingerprint", artifactFingerprint);
        artifact.put("artifact_digest", artifactDigest);
        artifact.put("materialization_digest", materializationDigest);
        artifact.put("created_at", createdAt.toString());
        artifact.put("expires_at", expiresAt.toString());
        artifact.put("axiom_count", axioms.size());
        artifact.put("canonical_bytes", canonicalBytes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ready");
        out.put("preview_only", true);
        out.put("complete", true);
        out.put("requested_categories", requested);
        out.put("supported_categories", supported);
        out.put("produced_categories", produced);
        out.put("skipped_categories", skipped);
        out.put("categories", categories);
        out.put("asserted_collision_count", asserted);
        out.put("input_identity", inputIdentity.toMap());
        out.put("provenance", request.provenance().toMap());
        out.put("destination_plan", request.destination().toMap());
        out.put("limits", request.limits().toMap());
        out.put("artifact", artifact);
        out.put("live_state_changed", false);
        return Collections.unmodifiableMap(out);
    }

    /** Complete per-category accounting; overflow and enumeration failure never create this record. */
    public record CategoryResult(String category, boolean supported, long enumeratedAxioms,
            long producedAxioms, long assertedCollisions, long canonicalBytes,
            boolean truncated, String contentDigest, String provenanceIri) {
        public CategoryResult {
            MaterializationCategory.fromValue(category);
            if (!supported || enumeratedAxioms < 0 || producedAxioms < 0
                    || assertedCollisions < 0 || canonicalBytes < 0
                    || producedAxioms + assertedCollisions != enumeratedAxioms
                    || truncated) {
                throw new IllegalArgumentException("invalid successful category accounting");
            }
            contentDigest = digest(contentDigest, "contentDigest");
            if (provenanceIri == null || provenanceIri.isBlank()
                    || provenanceIri.length() > 512) {
                throw new IllegalArgumentException("invalid provenance IRI");
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("category", category);
            out.put("status", producedAxioms == 0 ? "empty" : "produced");
            out.put("supported", supported);
            out.put("enumerated_axioms", enumeratedAxioms);
            out.put("produced_axioms", producedAxioms);
            out.put("asserted_collisions", assertedCollisions);
            out.put("canonical_bytes", canonicalBytes);
            out.put("truncated", truncated);
            out.put("content_digest", contentDigest);
            out.put("provenance_iri", provenanceIri);
            return Collections.unmodifiableMap(out);
        }
    }

    private static String bounded(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be bounded");
        }
        return value;
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }
}
