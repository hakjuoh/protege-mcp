package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** Method-level tests for the F5 aggregate gate {@link QcSuiteTools} (stages composed headless). */
class QcSuiteToolsTest {

    private static final String NS = "http://example.org/f5#";

    // ------------------------------------------------------------------ stage / gate parsing

    @Test
    void stagesDefaultToReasonerProfileStructural() {
        assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("reasoner", "profile", "structural")),
                QcSuiteTools.normalizeStages(null));
    }

    @Test
    void unknownStageThrows() {
        assertThrows(ToolArgException.class,
                () -> QcSuiteTools.normalizeStages(Arrays.asList("reasoner", "bogus")));
    }

    @Test
    void publishedStagesSchemaNamesEveryRunnableStage() {
        // Schema-driven MCP clients discover the stage set ONLY from this description, so it must
        // enumerate every member of the accepted stage list (notably governance).
        ToolRegistry registry = new ToolRegistry();
        QcSuiteTools.register(registry, new ToolContext(null, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>)
                registry.build().get(0).tool().inputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> stages = (Map<String, Object>) props.get("stages");
        String description = String.valueOf(stages.get("description"));
        for (String stage : List.of("reasoner", "profile", "governance", "structural",
                "invariants", "cqs", "shacl")) {
            assertTrue(description.contains(stage),
                    "the stages description must name '" + stage + "': " + description);
        }
    }

    @Test
    void failOnValidates() {
        assertEquals("error", QcSuiteTools.normalizeFailOn(null));
        assertEquals("warn", QcSuiteTools.normalizeFailOn("Warn"));
        assertThrows(ToolArgException.class, () -> QcSuiteTools.normalizeFailOn("critical"));
    }

    @Test
    void levelAndThresholdOrder() {
        assertTrue(QcSuiteTools.level(QcSuiteTools.FAIL) > QcSuiteTools.level(QcSuiteTools.WARN));
        assertTrue(QcSuiteTools.level(QcSuiteTools.WARN) > QcSuiteTools.level(QcSuiteTools.PASS));
        assertTrue(QcSuiteTools.threshold("none") > QcSuiteTools.threshold("error"));
    }

    // ------------------------------------------------------------------ aggregate

    @Test
    void allPassStagesGatePass() {
        List<QcSuiteTools.StageResult> stages = Arrays.asList(
                new QcSuiteTools.StageResult("a", true, QcSuiteTools.PASS, null, null),
                new QcSuiteTools.StageResult("b", true, QcSuiteTools.PASS, null, null));
        assertEquals("pass", gate(QcSuiteTools.aggregate(stages, "error")));
    }

    @Test
    void failStageTripsErrorGate() {
        List<QcSuiteTools.StageResult> stages = Arrays.asList(
                new QcSuiteTools.StageResult("a", true, QcSuiteTools.PASS, null, null),
                new QcSuiteTools.StageResult("b", true, QcSuiteTools.FAIL, null, null));
        assertEquals("fail", gate(QcSuiteTools.aggregate(stages, "error")));
    }

    @Test
    void warnStageOnlyTripsWarnGate() {
        List<QcSuiteTools.StageResult> stages = Collections.singletonList(
                new QcSuiteTools.StageResult("a", true, QcSuiteTools.WARN, null, null));
        assertEquals("pass", gate(QcSuiteTools.aggregate(stages, "error")),
                "a warn stage does not trip fail_on=error");
        assertEquals("fail", gate(QcSuiteTools.aggregate(stages, "warn")),
                "a warn stage trips fail_on=warn");
    }

    @Test
    void skippedStagesDoNotCountTowardTheGate() {
        List<QcSuiteTools.StageResult> stages = Arrays.asList(
                QcSuiteTools.StageResult.skipped("reasoner", "no results"),
                new QcSuiteTools.StageResult("structural", true, QcSuiteTools.PASS, null, null));
        Map<String, Object> r = structured(QcSuiteTools.aggregate(stages, "error"));
        assertEquals("pass", r.get("gate"));
        assertEquals(1, r.get("stages_ran"), "only the ran stage counts");
    }

    @Test
    void strictRequiredSkipAndExecutionFailureAreErrorsNotFailures() {
        QcSuiteTools.SuiteExecution skipped = execution(List.of(
                QcSuiteTools.StageResult.skipped("reasoner", "none selected"),
                new QcSuiteTools.StageResult("structural", true, QcSuiteTools.FAIL, Map.of(), null)));
        Map<String, Object> result = QcSuiteTools.strictResult(skipped,
                new java.util.LinkedHashSet<>(List.of("reasoner", "structural")), "error",
                1, "sha256:" + "2".repeat(64), true);
        assertEquals("error", result.get("gate"), "execution inability takes precedence over violations");
        assertEquals(1, result.get("stages_skipped"));

        QcSuiteTools.SuiteExecution errored = execution(List.of(
                QcSuiteTools.StageResult.errored("shacl", "bad shapes")));
        assertEquals("error", QcSuiteTools.strictResult(errored, Set.of("shacl"), "error",
                1, null, true).get("gate"));
    }

    @Test
    void strictOptionalSkipDoesNotControlGateAndInfoThresholdIsHonored() {
        QcSuiteTools.SuiteExecution execution = execution(List.of(
                QcSuiteTools.StageResult.skipped("shacl", "optional"),
                new QcSuiteTools.StageResult("structural", true, QcSuiteTools.INFO, Map.of(), null)));
        Map<String, Object> pass = QcSuiteTools.strictResult(execution, Set.of("structural"), "warn",
                1, null, true);
        assertEquals("pass", pass.get("gate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) pass.get("validation_snapshot");
        assertEquals(List.of("structural"), snapshot.get("stages"),
                "a skipped stage must not be claimed as having consumed the snapshot");
        Map<String, Object> fail = QcSuiteTools.strictResult(execution, Set.of("structural"), "info",
                1, null, true);
        assertEquals("fail", fail.get("gate"));
    }

    @Test
    void strictUnscheduledRequiredStageCannotVacuouslyPass() {
        Map<String, Object> result = QcSuiteTools.strictResult(execution(Collections.emptyList()),
                Set.of("invariants"), "error", 1, null, true);
        assertEquals("error", result.get("gate"));
        assertEquals(1, result.get("stages_skipped"));
    }

    @Test
    void strictGateErrorsWhenOntologyChangesAcrossClassificationBoundary() {
        String hash = "sha256:" + "3".repeat(64);
        var fingerprint = new io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint(
                1, hash, hash, "cross_restart", true, List.of());
        var execution = new QcSuiteTools.SuiteExecution(List.of(
                new QcSuiteTools.StageResult("structural", true, QcSuiteTools.PASS, Map.of(), null)),
                fingerprint, false, "HermiT", null);
        Map<String, Object> result = QcSuiteTools.strictResult(execution, Set.of("structural"),
                "warn", 1, null, true);
        assertEquals("error", result.get("gate"));
        assertEquals(false, result.get("snapshot_consistent"));
        assertTrue(String.valueOf(result.get("findings")).contains("snapshot.changed"));
    }

    // ------------------------------------------------------------------ structural stage

    @Test
    void structuralStageWarnsOnModellingSmell() throws Exception {
        OWLModelManager mm = ontology(false);   // Foo has no label → missing_label finding
        QcSuiteTools.StageResult r = QcSuiteTools.structuralStage(mm);
        assertTrue(r.ran);
        assertEquals("warn", r.verdict, "a structural smell is a warn, not a hard fail");
    }

    @Test
    void structuralStagePassesWhenClean() throws Exception {
        OWLModelManager mm = ontology(true);    // all classes labelled + in hierarchy
        QcSuiteTools.StageResult r = QcSuiteTools.structuralStage(mm);
        assertEquals("pass", r.verdict);
    }

    // ------------------------------------------------------------------ invariants stage

    @Test
    void invariantsStageSkippedWhenNoneSupplied() throws Exception {
        SuiteSnapshot snap = snapshot(false);
        QcSuiteTools.StageResult r = QcSuiteTools.invariantsStage(snap, Collections.emptyList(),
                1000, 30_000);
        assertFalse(r.ran);
    }

    @Test
    void invariantsStageFailsOnErrorViolation() throws Exception {
        SuiteSnapshot snap = snapshot(false);   // Foo unlabelled
        List<Invariants.Invariant> invs = Collections.singletonList(new Invariants.Invariant(
                "labelled", "every class needs a label", "error",
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }", false));
        QcSuiteTools.StageResult r = QcSuiteTools.invariantsStage(snap, invs, 1000, 30_000);
        assertTrue(r.ran);
        assertEquals("fail", r.verdict);
    }

    @Test
    void invariantsStageFailsClosedWhenInferredCheckCannotRun() throws Exception {
        // include_inferred=true but the suite snapshot has no inferred materialisation (no reasoner):
        // the fail-closed invariant must propagate to a FAIL stage verdict, never a silent pass.
        SuiteSnapshot snap = snapshot(false);
        List<Invariants.Invariant> invs = Collections.singletonList(new Invariants.Invariant(
                "inf", "inference-only pattern", "error", "SELECT ?c WHERE { ?c a owl:Class }", true));
        QcSuiteTools.StageResult r = QcSuiteTools.invariantsStage(snap, invs, 1000, 30_000);
        assertTrue(r.ran);
        assertEquals("fail", r.verdict, "the un-runnable inferred check fails the stage closed");
        assertEquals(0, r.summary.get("violations"), "it is not a violation");
        assertEquals(1, r.summary.get("errors"), "it is surfaced as a check that could not run");
    }

    @Test
    void invariantsStageWarnSeverityFailClosedSurfacesAsWarnNotPass() throws Exception {
        // A WARN-severity un-runnable check (worst=2 < error threshold 3) must NOT collapse to PASS: it has
        // to surface as WARN so the suite's fail_on=warn can trip it. Regression for the run_qc_suite
        // false-pass where a warn/info fail-closed invariant was swallowed.
        SuiteSnapshot snap = snapshot(false);
        List<Invariants.Invariant> invs = Collections.singletonList(new Invariants.Invariant(
                "inf-warn", "inference-only, warn", "warn", "SELECT ?c WHERE { ?c a owl:Class }", true));
        QcSuiteTools.StageResult r = QcSuiteTools.invariantsStage(snap, invs, 1000, 30_000);
        assertTrue(r.ran);
        assertEquals("warn", r.verdict, "a warn-severity check that could not run is a WARN, never PASS");
        assertEquals(1, r.summary.get("errors"));
        // aggregate then honours the SUITE fail_on over the WARN verdict.
        assertEquals("fail", gate(QcSuiteTools.aggregate(Collections.singletonList(r), "warn")),
                "fail_on=warn trips on the WARN stage");
        assertEquals("pass", gate(QcSuiteTools.aggregate(Collections.singletonList(r), "error")),
                "fail_on=error does not — the author declared it warn severity");
    }

    // ------------------------------------------------------------------ cqs stage

    @Test
    void cqsStageSkippedWhenNoQuestions() throws Exception {
        SuiteSnapshot snap = snapshot(false);
        QcSuiteTools.StageResult r = QcSuiteTools.cqsStage(snap, Collections.emptyList(), 1000, 30_000);
        assertFalse(r.ran);
    }

    @Test
    void cqsStageFailsWhenACqFails() throws Exception {
        SuiteSnapshot snap = snapshot(false);
        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = "needs-individual";
        cq.query = "SELECT ?i WHERE { ?i a owl:NamedIndividual }";   // none exist → nonEmpty fails
        cq.expected = Expectation.nonEmpty();
        cq.includeInferred = false;
        QcSuiteTools.StageResult r = QcSuiteTools.cqsStage(snap, Arrays.asList(cq), 1000, 30_000);
        assertTrue(r.ran);
        assertEquals("fail", r.verdict);
    }

    @Test
    void cqsStageSurfacesADegradationCaveat() throws Exception {
        // include_inferred=true with no reasoner: the CQ still runs over the asserted triples (owl:Class
        // exists → passes) but CqRunner degrades with a caveat. The suite must surface that caveat, not
        // report an unqualified green.
        SuiteSnapshot snap = snapshot(false);
        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = "inferred-cq";
        cq.query = "SELECT ?c WHERE { ?c a owl:Class }";
        cq.expected = Expectation.nonEmpty();
        cq.includeInferred = true;
        QcSuiteTools.StageResult r = QcSuiteTools.cqsStage(snap, Arrays.asList(cq), 1000, 30_000);
        assertTrue(r.ran);
        assertEquals("pass", r.verdict, "it passes on the asserted triples");
        assertEquals(1, r.summary.get("caveats"), "the degradation caveat is surfaced, not silent");
    }

    // ------------------------------------------------------------------ fixtures

    private OWLModelManager ontology(boolean clean) throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/f5"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLClass foo = df.getOWLClass(IRI.create(NS + "Foo"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLDeclarationAxiom(foo));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), animal.getIRI(),
                df.getOWLLiteral("Animal")));
        if (clean) {
            // Label Foo and place it in the hierarchy so no structural smell remains.
            m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), foo.getIRI(),
                    df.getOWLLiteral("Foo")));
            m.addAxiom(o, df.getOWLSubClassOfAxiom(foo, animal));
        }
        return FakeModelManager.over(o);
    }

    private SuiteSnapshot snapshot(boolean clean) throws OWLOntologyCreationException {
        return SuiteSnapshot.capture(ontology(clean), false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(io.modelcontextprotocol.spec.McpSchema.CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    private static String gate(io.modelcontextprotocol.spec.McpSchema.CallToolResult r) {
        return String.valueOf(structured(r).get("gate"));
    }

    private static QcSuiteTools.SuiteExecution execution(List<QcSuiteTools.StageResult> results) {
        String hash = "sha256:" + "1".repeat(64);
        return new QcSuiteTools.SuiteExecution(results,
                new io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint(
                        1, hash, hash, "cross_restart", true, List.of()), true, null, null);
    }
}
