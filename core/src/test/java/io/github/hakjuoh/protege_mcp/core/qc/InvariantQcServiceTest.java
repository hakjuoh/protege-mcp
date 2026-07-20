package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

class InvariantQcServiceTest {

    @Test
    void evaluatesViolationsAtTheirDeclaredSeverity() throws Exception {
        QuerySnapshot snapshot = snapshotWithClass();
        var invariant = new InvariantQcService.Invariant("any-class", "class found", "warning",
                "ASK { ?c a <http://www.w3.org/2002/07/owl#Class> }", false);

        InvariantQcService.Result result = InvariantQcService.evaluate(
                snapshot, List.of(invariant), 100, 10_000);

        assertEquals(QcStageVerdict.WARNING, result.execution().verdict());
        assertEquals(1, result.execution().details().get("violations"));
        assertEquals(1, result.gatingIdentities().size());
        assertEquals(result.execution().details().get("identity_digest"),
                InvariantQcService.evaluate(snapshot, List.of(invariant), 100, 10_000)
                        .execution().details().get("identity_digest"));
    }

    @Test
    void skipsAnEmptyInvariantSetWithoutInventingAVerdict() throws Exception {
        InvariantQcService.Result result = InvariantQcService.evaluate(
                snapshotWithClass(), List.of(), 100, 10_000);

        assertEquals(QcStageVerdict.SKIPPED, result.execution().verdict());
        assertFalse(result.execution().verdict().ran());
        assertTrue(result.gatingIdentities().isEmpty());
    }

    @Test
    void inferredChecksFailClosedWhenNoInferredSnapshotExists() throws Exception {
        var invariant = new InvariantQcService.Invariant("inferred", null, "error",
                "ASK { ?c a <http://www.w3.org/2002/07/owl#Class> }", true);

        Map<String, Object> run = InvariantQcService.run(
                snapshotWithClass(), List.of(invariant), 100, 10_000, "error");

        assertEquals(0, run.get("violations"));
        assertEquals(1, run.get("errors"));
        assertEquals("fail", run.get("gate"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) run.get("invariants")).get(0);
        assertTrue(String.valueOf(row.get("error")).contains("fails closed"));
        assertFalse(row.containsKey("caveats"));
    }

    @Test
    void rejectsGraphProducingChecksAsErrors() throws Exception {
        var invariant = new InvariantQcService.Invariant("graph", null, "error",
                "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", false);

        Map<String, Object> run = InvariantQcService.run(
                snapshotWithClass(), List.of(invariant), 100, 10_000, "error");

        assertEquals(1, run.get("errors"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) run.get("invariants")).get(0);
        assertTrue(String.valueOf(row.get("error")).contains("SELECT/ASK"));
        assertTrue(String.valueOf(row.get("error")).contains("CONSTRUCT"));
    }

    @Test
    void projectsErroredChecksAtWarnAndInfoSeverity() throws Exception {
        QuerySnapshot snapshot = snapshotWithClass();
        var warn = new InvariantQcService.Invariant("warn-broken", null, "warn",
                "SELECT {{{ not sparql", false);
        var info = new InvariantQcService.Invariant("info-broken", null, "info",
                "SELECT {{{ not sparql", false);

        assertEquals(QcStageVerdict.WARNING,
                InvariantQcService.evaluate(snapshot, List.of(warn), 100, 10_000)
                        .execution().verdict());
        assertEquals(QcStageVerdict.INFO,
                InvariantQcService.evaluate(snapshot, List.of(info), 100, 10_000)
                        .execution().verdict());
    }

    @Test
    void countsOnlyRecognizedInferenceCaveats() {
        Map<String, Object> missing = Map.of("caveats",
                List.of("include_inferred requested but unavailable"));
        Map<String, Object> truncated = Map.of("caveats",
                List.of("ran_with_caveat: inferences truncated — cap reached"));
        Map<String, Object> unrelated = Map.of("caveats", List.of("another caveat"));

        assertEquals(2, InvariantQcService.countInferenceCaveats(
                List.of(missing, truncated, unrelated)));
    }

    @Test
    void findingIdentitiesIgnoreVolatileExecutionTime() {
        Map<String, Object> first = failedCqRow(1);
        Map<String, Object> second = failedCqRow(9999);

        assertEquals(InvariantQcService.resultIdentities(List.of(first), "pass"),
                InvariantQcService.resultIdentities(List.of(second), "pass"));
    }

    @Test
    void snapshotRequiresTheActiveOntologyInItsClosure() throws Exception {
        OWLOntology active = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/active"));

        assertThrows(IllegalArgumentException.class,
                () -> QuerySnapshot.capture(active, Set.of(), Map.of()));
    }

    private static QuerySnapshot snapshotWithClass() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/invariants"));
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(
                        IRI.create("https://example.org/invariants#Term"))));
        return QuerySnapshot.capture(ontology, Set.of(ontology), Map.of());
    }

    private static Map<String, Object> failedCqRow(long milliseconds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "cq-1");
        row.put("pass", false);
        row.put("ms", milliseconds);
        return row;
    }
}
