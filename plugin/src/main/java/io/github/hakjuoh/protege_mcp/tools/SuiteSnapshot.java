package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.core.qc.QuerySnapshot;

/** Source-compatible plugin adapter for the shared asserted/inferred query snapshot. */
final class SuiteSnapshot {

    private final QuerySnapshot shared;

    private SuiteSnapshot(QuerySnapshot shared) {
        this.shared = shared;
    }

    static SuiteSnapshot capture(OWLModelManager modelManager, boolean needInferred) {
        OWLOntology active = modelManager.getActiveOntology();
        SuiteSnapshot snapshot = captureIsolated(active, active.getImportsClosure(),
                SparqlTools.prefixMap(modelManager, active));
        if (!needInferred) return snapshot;
        try {
            return snapshot.withInferences(SparqlTools.requireReasoner(modelManager));
        } catch (RuntimeException error) {
            return snapshot.withInferenceError(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    static SuiteSnapshot captureIsolated(OWLOntology active, Set<OWLOntology> closure,
            Map<String, String> prefixes) {
        try {
            return new SuiteSnapshot(QuerySnapshot.capture(active, closure, prefixes));
        } catch (io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    SuiteSnapshot withInferences(OWLReasoner reasoner) {
        try {
            return new SuiteSnapshot(shared.withInferences(reasoner));
        } catch (io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    SuiteSnapshot withInferenceError(String error) {
        return new SuiteSnapshot(shared.withInferenceError(error));
    }

    OWLOntology assertedOntology() {
        return shared.assertedOntology();
    }

    byte[] assertedTurtle() {
        try {
            return shared.assertedTurtle();
        } catch (io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    boolean inferredAvailable() {
        return shared.inferredAvailable();
    }

    String inferredError() {
        return shared.inferredError();
    }

    String inferredNote() {
        return shared.inferredNote();
    }

    Map<String, Object> execute(String query, boolean includeInferred, int limit, long timeoutMs) {
        try {
            return shared.execute(query, includeInferred, limit, timeoutMs);
        } catch (io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService.QueryException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    QuerySnapshot shared() {
        return shared;
    }
}
