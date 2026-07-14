package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * One point-in-time RDF snapshot of the active ontology, shared by every query in a suite (the
 * competency-question runner, {@code verify_ontology}, {@code run_qc_suite}).
 *
 * <p>{@link SparqlTools#snapshot} serialises the whole imports closure into a fresh throwaway Jena-ready
 * OWL ontology on the EDT on <em>each</em> call, so running N queries by snapshotting per query is O(N)
 * full copies that block the EDT on a BFO/IOF-scale ontology. Instead a suite {@linkplain #capture
 * captures} the model <em>once</em> (the asserted closure, plus — only when some query needs it — one
 * inferred materialisation), inside a single {@link OntologyAccess#compute} hop so every query sees the
 * same instant, then {@linkplain #execute evaluates} each query off the EDT against the pre-built snapshot.
 *
 * <p>Because the inferred materialisation needs a classified reasoner (which may be absent), its build is
 * best-effort: a failure is captured in {@link #inferredError()} and any query that asked for inferences
 * degrades to a caveat rather than throwing.
 */
final class SuiteSnapshot {

    private final SparqlTools.Snapshot asserted;
    private final SparqlTools.Snapshot inferred;   // nullable — absent, or could not be built
    private final String inferredError;            // nullable — why the inferred snapshot is absent

    private SuiteSnapshot(SparqlTools.Snapshot asserted, SparqlTools.Snapshot inferred,
            String inferredError) {
        this.asserted = asserted;
        this.inferred = inferred;
        this.inferredError = inferredError;
    }

    /**
     * Capture the model on the EDT: always the asserted closure, plus one inferred materialisation when
     * {@code needInferred} is set and a classified reasoner is available. Must be called inside a single
     * {@code compute(mm)} hop so the suite sees one coherent instant. Never throws for a missing reasoner —
     * that becomes {@link #inferredError()}.
     */
    static SuiteSnapshot capture(OWLModelManager mm, boolean needInferred) {
        OWLOntology active = mm.getActiveOntology();
        SuiteSnapshot snapshot = captureIsolated(active, active.getImportsClosure(),
                SparqlTools.prefixMap(mm, active));
        if (!needInferred) {
            return snapshot;
        }
        try {
            return snapshot.withInferences(SparqlTools.requireReasoner(mm));
        } catch (RuntimeException e) {
            return snapshot.withInferenceError(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Build the asserted query graph from one already-isolated closure. Inferences are intentionally
     * deferred until the caller has constructed a configuration-equivalent reasoner over this same graph.
     */
    static SuiteSnapshot captureIsolated(OWLOntology isolatedActive,
            Set<OWLOntology> isolatedClosure, Map<String, String> prefixes) {
        SparqlTools.Snapshot asserted = SparqlTools.snapshot(isolatedActive.getOntologyID(), isolatedClosure,
                prefixes, null, false);
        return new SuiteSnapshot(asserted, null, null);
    }

    /** Materialize inferences with a reasoner whose root is this snapshot's flattened ontology. */
    SuiteSnapshot withInferences(OWLReasoner reasoner) {
        SparqlTools.Snapshot materialized = SparqlTools.snapshot(asserted.ontology().getOntologyID(),
                Set.of(asserted.ontology()), asserted.prefixes(), reasoner, true);
        return new SuiteSnapshot(asserted, materialized, null);
    }

    /** Record why requested inferred data could not be produced, without discarding asserted data. */
    SuiteSnapshot withInferenceError(String error) {
        return new SuiteSnapshot(asserted, null, error);
    }

    /** Asserted, flattened OWL snapshot used by profile validation. */
    OWLOntology assertedOntology() {
        return asserted.ontology();
    }

    /** Immutable serialized asserted graph used by SHACL validation. */
    byte[] assertedTurtle() {
        return SparqlTools.toTurtleBytes(asserted.ontology());
    }

    /** True when an inferred materialisation is available for {@code include_inferred} queries. */
    boolean inferredAvailable() {
        return inferred != null;
    }

    /** Why an inferred snapshot could not be built (e.g. no reasoner), or {@code null}. */
    String inferredError() {
        return inferredError;
    }

    /**
     * The inferred snapshot's own note, if any — e.g. "inferred property assertions were skipped because
     * the ABox is large" (SparqlTools' MAX_INFERRED_PROPERTY_PRODUCT cap). {@code null} when there is no
     * inferred snapshot or it carried no note.
     */
    String inferredNote() {
        return inferred == null ? null : inferred.note();
    }

    /**
     * Evaluate {@code query} off the EDT against the appropriate snapshot: the inferred one when
     * {@code includeInferred} is set <em>and</em> it was built, else the asserted one. Returns the raw
     * {@link SparqlTools#execute} result map (SELECT/ASK/CONSTRUCT/DESCRIBE). SPARQL UPDATE/SERVICE are
     * rejected inside {@code execute}.
     */
    Map<String, Object> execute(String query, boolean includeInferred, int limit, long timeoutMs) {
        SparqlTools.Snapshot snap = includeInferred && inferred != null ? inferred : asserted;
        return SparqlTools.execute(snap.ontology(), snap.prefixes(), query, limit, timeoutMs);
    }
}
