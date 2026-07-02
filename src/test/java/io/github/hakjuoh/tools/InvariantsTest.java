package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** Method-level + snapshot tests for the F4 invariant engine {@link Invariants}. */
class InvariantsTest {

    private static final String NS = "http://example.org/f4#";

    // ------------------------------------------------------------------ parse

    @Test
    void parseRequiresSparqlPerItem() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("message", "no sparql here");
        assertThrows(ToolArgException.class, () -> Invariants.parse(Arrays.asList(bad)));
    }

    @Test
    void parseFillsDefaults() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("sparql", "ASK { ?s ?p ?o }");
        Invariants.Invariant inv = Invariants.parse(Arrays.asList(q)).get(0);
        assertEquals("invariant-1", inv.id, "id defaults to invariant-N");
        assertEquals("error", inv.severity, "severity defaults to error");
        assertFalse(inv.includeInferred);
    }

    @Test
    void severityNormalizes() {
        assertEquals("warn", Invariants.normalizeSeverity("Warning"));
        assertEquals("info", Invariants.normalizeSeverity("INFO"));
        assertThrows(ToolArgException.class, () -> Invariants.normalizeSeverity("critical"));
    }

    @Test
    void failOnNormalizes() {
        assertEquals("error", Invariants.normalizeFailOn(null));
        assertEquals("warn", Invariants.normalizeFailOn("Warn"));
        assertThrows(ToolArgException.class, () -> Invariants.normalizeFailOn("fatal"));
    }

    // ------------------------------------------------------------------ judgeExec (pure)

    @Test
    void judgeExecFlagsSelectRowsAsViolations() {
        Invariants.Invariant inv = new Invariants.Invariant("x", "msg", "error", "SELECT ...", false);
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("query_type", "SELECT");
        exec.put("count", 2);
        exec.put("bindings", Arrays.asList(binding("http://ex/A"), binding("http://ex/B")));
        Map<String, Object> r = Invariants.judgeExec(inv, exec, 1000);
        assertTrue((boolean) r.get("violated"));
        assertEquals(2, r.get("violation_count"));
        assertTrue(r.containsKey("violations"), "the raw nodeJson bindings are the evidence");
    }

    @Test
    void judgeExecEmptySelectIsNoViolation() {
        Invariants.Invariant inv = new Invariants.Invariant("x", "msg", "error", "SELECT ...", false);
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("query_type", "SELECT");
        exec.put("count", 0);
        exec.put("bindings", new ArrayList<>());
        assertFalse((boolean) Invariants.judgeExec(inv, exec, 1000).get("violated"));
    }

    @Test
    void judgeExecAskTrueIsViolation() {
        Invariants.Invariant inv = new Invariants.Invariant("x", "msg", "warn", "ASK ...", false);
        Map<String, Object> yes = new LinkedHashMap<>();
        yes.put("query_type", "ASK");
        yes.put("boolean", true);
        assertTrue((boolean) Invariants.judgeExec(inv, yes, 1000).get("violated"));
        Map<String, Object> no = new LinkedHashMap<>();
        no.put("query_type", "ASK");
        no.put("boolean", false);
        assertFalse((boolean) Invariants.judgeExec(inv, no, 1000).get("violated"));
    }

    // ------------------------------------------------------------------ run + gate (snapshot)

    @Test
    void runFlagsViolationAndGatesBySeverity() throws Exception {
        SuiteSnapshot snap = snapshotWithUnlabelledClass();
        // Foo has no rdfs:label → this SELECT returns it → a violation.
        List<Invariants.Invariant> invs = Arrays.asList(new Invariants.Invariant("labelled",
                "every class needs a label", "error",
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }", false));

        Map<String, Object> failErr = Invariants.run(snap, invs, 1000, 30_000, "error");
        assertEquals(1, failErr.get("violations"));
        assertEquals("fail", failErr.get("gate"), "an error violation trips fail_on=error");

        Map<String, Object> failNone = Invariants.run(snap, invs, 1000, 30_000, "none");
        assertEquals("pass", failNone.get("gate"), "fail_on=none never gates");
    }

    @Test
    void runDoesNotGateWhenSeverityBelowThreshold() throws Exception {
        SuiteSnapshot snap = snapshotWithUnlabelledClass();
        List<Invariants.Invariant> invs = Arrays.asList(new Invariants.Invariant("labelled",
                "warn only", "warn",
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }", false));
        Map<String, Object> result = Invariants.run(snap, invs, 1000, 30_000, "error");
        assertEquals(1, result.get("violations"), "the pattern is still reported");
        assertEquals("pass", result.get("gate"), "a warn violation does not trip fail_on=error");
    }

    @Test
    void runIsolatesAMalformedInvariantAndGatesFailClosed() throws Exception {
        SuiteSnapshot snap = snapshotWithUnlabelledClass();
        List<Invariants.Invariant> invs = Arrays.asList(
                new Invariants.Invariant("broken", "bad sparql", "error", "SELECT {{{ not sparql", false));
        Map<String, Object> result = Invariants.run(snap, invs, 1000, 30_000, "error");
        assertEquals(0, result.get("violations"), "a query that cannot run is not a violation");
        assertEquals(1, result.get("errors"), "the failed check is counted");
        assertEquals("fail", result.get("gate"),
                "a check that could not run must NOT report pass — the gate fails fail-closed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("invariants");
        assertTrue(items.get(0).containsKey("error"), "the failure is reported, not thrown");
    }

    @Test
    void includeInferredWithoutReasonerFailsClosed() throws Exception {
        SuiteSnapshot snap = snapshotWithUnlabelledClass();   // built with needInferred=false → none available
        // include_inferred=true, but no inferred snapshot exists AND the pattern matches on asserted
        // triples: the OLD behaviour degraded to asserted-only and could report pass — this asserts the
        // new fail-closed contract (a check that cannot run is never a false pass).
        List<Invariants.Invariant> invs = Arrays.asList(new Invariants.Invariant("inf",
                "inference-only pattern", "error",
                "SELECT ?c WHERE { ?c a owl:Class }", true));
        Map<String, Object> result = Invariants.run(snap, invs, 1000, 30_000, "error");
        assertEquals(0, result.get("violations"),
                "an invariant that could not run over inferred triples is not counted as a violation");
        assertEquals(1, result.get("errors"), "it is counted as a check that could not run");
        assertEquals("fail", result.get("gate"),
                "an include_inferred invariant with no reasoner fails closed — it never silently degrades "
                        + "to the asserted triples and reports a pass");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("invariants");
        assertFalse((boolean) items.get(0).get("violated"));
        assertTrue(items.get(0).containsKey("error"), "the reason is surfaced, not silent");
        assertTrue(String.valueOf(items.get(0).get("error")).contains("run_reasoner"),
                "the message points the author at the missing classification");
        assertFalse(items.get(0).containsKey("caveats"),
                "the fail-closed report is an error, not a soft caveat");
    }

    @Test
    void includeInferredFailClosedDoesNotGateWhenFailOnNone() throws Exception {
        SuiteSnapshot snap = snapshotWithUnlabelledClass();
        List<Invariants.Invariant> invs = Arrays.asList(new Invariants.Invariant("inf",
                "inference-only pattern", "error", "SELECT ?c WHERE { ?c a owl:Class }", true));
        Map<String, Object> result = Invariants.run(snap, invs, 1000, 30_000, "none");
        assertEquals(1, result.get("errors"), "still reported as an un-runnable check");
        assertEquals("pass", result.get("gate"), "fail_on=none never gates, even a fail-closed error");
    }

    @Test
    void runRejectsGraphProducingInvariantFailClosed() throws Exception {
        SuiteSnapshot snap = snapshotWithUnlabelledClass();
        // A CONSTRUCT that matches (Foo/Bar are classes): the OLD code treated its turtle as violation
        // evidence; verify_ontology invariants must be SELECT/ASK, so it is now rejected fail-closed.
        List<Invariants.Invariant> invs = Arrays.asList(new Invariants.Invariant("graph",
                "graph-producing form", "error",
                "CONSTRUCT { ?c a owl:Class } WHERE { ?c a owl:Class }", false));
        Map<String, Object> result = Invariants.run(snap, invs, 1000, 30_000, "error");
        assertEquals(0, result.get("violations"), "a CONSTRUCT is not a violation detector");
        assertEquals(1, result.get("errors"), "it is rejected, counted like a check that could not run");
        assertEquals("fail", result.get("gate"), "a rejected invariant fails closed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("invariants");
        assertFalse((boolean) items.get(0).get("violated"));
        String error = String.valueOf(items.get(0).get("error"));
        assertTrue(error.contains("SELECT") && error.contains("ASK"),
                "the message tells the author to use SELECT/ASK");
        assertTrue(error.contains("CONSTRUCT"), "the message names the offending form");
    }

    @Test
    void runPassesWhenNoPatternMatches() throws Exception {
        SuiteSnapshot snap = snapshotWithUnlabelledClass();
        List<Invariants.Invariant> invs = Arrays.asList(new Invariants.Invariant("none",
                "no NamedIndividual allowed", "error",
                "SELECT ?i WHERE { ?i a owl:NamedIndividual }", false));
        Map<String, Object> result = Invariants.run(snap, invs, 1000, 30_000, "error");
        assertEquals(0, result.get("violations"));
        assertEquals("pass", result.get("gate"));
    }

    // ------------------------------------------------------------------ fixtures

    private SuiteSnapshot snapshotWithUnlabelledClass() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/f4"));
        OWLClass foo = df.getOWLClass(IRI.create(NS + "Foo"));
        OWLClass bar = df.getOWLClass(IRI.create(NS + "Bar"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(foo));                       // Foo: no label
        m.addAxiom(o, df.getOWLDeclarationAxiom(bar));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), bar.getIRI(),
                df.getOWLLiteral("Bar")));
        OWLModelManager mm = FakeModelManager.over(o);
        return SuiteSnapshot.capture(mm, false);
    }

    private static Map<String, Object> binding(String iri) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("type", "uri");
        cell.put("value", iri);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", cell);
        return row;
    }
}
