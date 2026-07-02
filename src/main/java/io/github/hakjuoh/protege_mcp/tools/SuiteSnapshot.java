package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;

import org.protege.editor.owl.model.OWLModelManager;

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
        SparqlTools.Snapshot asserted = SparqlTools.snapshot(mm, false);
        SparqlTools.Snapshot inferred = null;
        String inferredError = null;
        if (needInferred) {
            try {
                inferred = SparqlTools.snapshot(mm, true);
            } catch (RuntimeException e) {
                inferredError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
        return new SuiteSnapshot(asserted, inferred, inferredError);
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
