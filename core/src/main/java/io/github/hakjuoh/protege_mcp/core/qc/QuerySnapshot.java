package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceFingerprints;

/**
 * One immutable asserted/inferred RDF query snapshot shared by headless QC stages.
 *
 * <p>Interactive callers must capture the active ontology and its closure inside one model-manager
 * read/compute operation. That preserves a coherent point in time and keeps the potentially expensive
 * ontology copy outside repeated query execution.
 */
public final class QuerySnapshot {

    private final RdfQueryService.Snapshot asserted;
    private final RdfQueryService.Snapshot inferred;
    private final String inferredError;
    private final String sourceClosureFingerprint;

    private QuerySnapshot(RdfQueryService.Snapshot asserted,
            RdfQueryService.Snapshot inferred, String inferredError,
            String sourceClosureFingerprint) {
        this.asserted = java.util.Objects.requireNonNull(asserted, "asserted");
        this.inferred = inferred;
        this.inferredError = inferredError;
        this.sourceClosureFingerprint = java.util.Objects.requireNonNull(
                sourceClosureFingerprint, "sourceClosureFingerprint");
    }

    public static QuerySnapshot capture(OWLOntology active, Set<OWLOntology> closure,
            Map<String, String> prefixes) {
        if (active == null || closure == null || !closure.contains(active)) {
            throw new IllegalArgumentException("closure must contain the active ontology");
        }
        return new QuerySnapshot(RdfQueryService.snapshot(active.getOntologyID(), closure,
                prefixes, null, false), null, null, WorkspaceFingerprints.closure(closure));
    }

    public QuerySnapshot withInferences(OWLReasoner reasoner) {
        RdfQueryService.Snapshot materialized = RdfQueryService.snapshot(
                asserted.ontology().getOntologyID(), Set.of(asserted.ontology()),
                asserted.prefixes(), reasoner, true);
        return new QuerySnapshot(asserted, materialized, null, sourceClosureFingerprint);
    }

    public QuerySnapshot withInferenceError(String error) {
        return new QuerySnapshot(asserted, null, error, sourceClosureFingerprint);
    }

    boolean capturedFrom(OWLOntology root) {
        return root != null && sourceClosureFingerprint.equals(
                WorkspaceFingerprints.closure(root.getImportsClosure()));
    }

    public OWLOntology assertedOntology() {
        return asserted.ontology();
    }

    public byte[] assertedTurtle() {
        return RdfQueryService.toTurtleBytes(asserted.ontology());
    }

    public boolean inferredAvailable() {
        return inferred != null;
    }

    public String inferredError() {
        return inferredError;
    }

    public String inferredNote() {
        return inferred == null ? null : inferred.note();
    }

    public Map<String, Object> execute(String query, boolean includeInferred,
            int limit, long timeoutMs) {
        RdfQueryService.Snapshot snapshot = includeInferred && inferred != null
                ? inferred : asserted;
        return RdfQueryService.execute(snapshot.ontology(), snapshot.prefixes(),
                query, limit, timeoutMs);
    }
}
