package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.semanticweb.owlapi.model.AddOntologyAnnotation;

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
    void legacyLimitUsesTheSameBoundsAsProjectQc() {
        assertThrows(ToolArgException.class,
                () -> QcRunConfig.legacy(Map.of("limit", -1)));
        assertThrows(ToolArgException.class,
                () -> QcRunConfig.legacy(Map.of("limit", 10_001)));
    }

    @Test
    void explicitRequiredStagesStayNarrowWhenStrictMissingIsAlsoSet() {
        QcRunConfig explicit = QcRunConfig.legacy(Map.of(
                "stages", List.of("structural", "cqs"),
                "required_stages", List.of("structural")));
        assertEquals(Set.of("structural"), QcSuiteTools.legacyRequiredStages(explicit, true));

        QcRunConfig fallback = QcRunConfig.legacy(Map.of(
                "stages", List.of("structural", "cqs")));
        assertEquals(Set.of("structural", "cqs"),
                QcSuiteTools.legacyRequiredStages(fallback, true));
    }

    @Test
    void truncatedRequiredCountCqBecomesAnErroredFailClosedStage() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "cq-truncation"));
        for (int i = 0; i < 1001; i++) {
            var cls = manager.getOWLDataFactory().getOWLClass(IRI.create(NS + "C" + i));
            manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(cls));
        }
        SuiteSnapshot snapshot = SuiteSnapshot.captureIsolated(ontology, Set.of(ontology), Map.of());
        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = "bounded-count";
        cq.query = "SELECT ?s WHERE { ?s a <http://www.w3.org/2002/07/owl#Class> }";
        cq.includeInferred = false;
        cq.expected = Expectation.count("<=", 1000);
        cq.convention = Cq.CONV_MANIFEST;

        QcStageResult stage = QcSuiteTools.cqsStage(snapshot, List.of(cq), 1000, 30_000);

        assertEquals("fail", stage.verdict);
        assertEquals(1, stage.summary.get("errors"));
    }

    @Test
    void projectAnnotationCqsRejectMalformedEntriesAndDuplicateIds() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "annotation-cqs"));
        var df = manager.getOWLDataFactory();
        var property = df.getOWLAnnotationProperty(IRI.create(Cq.ANNOTATION_IRI));
        String valid = "{\"id\":\"same\",\"query\":\"ASK {}\",\"expected\":\"nonEmpty\"}";
        manager.applyChange(new AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(property, df.getOWLLiteral(valid))));
        manager.applyChange(new AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(property, df.getOWLLiteral("not-json"))));
        assertThrows(ToolArgException.class,
                () -> QcSuiteTools.loadAnnotationCqs(FakeModelManager.over(ontology)));

        manager.applyChange(new org.semanticweb.owlapi.model.RemoveOntologyAnnotation(ontology,
                df.getOWLAnnotation(property, df.getOWLLiteral("not-json"))));
        String duplicate = "{\"id\":\"same\",\"text\":\"duplicate\","
                + "\"query\":\"ASK {}\",\"expected\":\"nonEmpty\"}";
        manager.applyChange(new AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(property, df.getOWLLiteral(duplicate))));
        assertThrows(ToolArgException.class,
                () -> QcSuiteTools.loadAnnotationCqs(FakeModelManager.over(ontology)));
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
        List<QcStageResult> stages = Arrays.asList(
                new QcStageResult("a", true, QcSuiteTools.PASS, null, null),
                new QcStageResult("b", true, QcSuiteTools.PASS, null, null));
        assertEquals("pass", gate(QcSuiteTools.aggregate(stages, "error")));
    }

    @Test
    void failStageTripsErrorGate() {
        List<QcStageResult> stages = Arrays.asList(
                new QcStageResult("a", true, QcSuiteTools.PASS, null, null),
                new QcStageResult("b", true, QcSuiteTools.FAIL, null, null));
        assertEquals("fail", gate(QcSuiteTools.aggregate(stages, "error")));
    }

    @Test
    void warnStageOnlyTripsWarnGate() {
        List<QcStageResult> stages = Collections.singletonList(
                new QcStageResult("a", true, QcSuiteTools.WARN, null, null));
        assertEquals("pass", gate(QcSuiteTools.aggregate(stages, "error")),
                "a warn stage does not trip fail_on=error");
        assertEquals("fail", gate(QcSuiteTools.aggregate(stages, "warn")),
                "a warn stage trips fail_on=warn");
    }

    @Test
    void skippedStagesDoNotCountTowardTheGate() {
        List<QcStageResult> stages = Arrays.asList(
                QcStageResult.skipped("reasoner", "no results"),
                new QcStageResult("structural", true, QcSuiteTools.PASS, null, null));
        Map<String, Object> r = structured(QcSuiteTools.aggregate(stages, "error"));
        assertEquals("pass", r.get("gate"));
        assertEquals(1, r.get("stages_ran"), "only the ran stage counts");
    }

    @Test
    void strictRequiredSkipAndExecutionFailureAreErrorsNotFailures() {
        QcSuiteExecution skipped = execution(List.of(
                QcStageResult.skipped("reasoner", "none selected"),
                new QcStageResult("structural", true, QcSuiteTools.FAIL, Map.of(), null)));
        Map<String, Object> result = QcSuiteTools.strictResult(skipped,
                new java.util.LinkedHashSet<>(List.of("reasoner", "structural")), "error",
                1, "sha256:" + "2".repeat(64), true);
        assertEquals("error", result.get("gate"), "execution inability takes precedence over violations");
        assertEquals(1, result.get("stages_skipped"));

        QcSuiteExecution errored = execution(List.of(
                QcStageResult.errored("shacl", "bad shapes")));
        assertEquals("error", QcSuiteTools.strictResult(errored, Set.of("shacl"), "error",
                1, null, true).get("gate"));
    }

    @Test
    void strictOptionalSkipDoesNotControlGateAndInfoThresholdIsHonored() {
        QcSuiteExecution execution = execution(List.of(
                QcStageResult.skipped("shacl", "optional"),
                new QcStageResult("structural", true, QcSuiteTools.INFO, Map.of(), null)));
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
        var execution = new QcSuiteExecution(List.of(
                new QcStageResult("structural", true, QcSuiteTools.PASS, Map.of(), null)),
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
        QcStageResult r = QcSuiteTools.structuralStage(mm);
        assertTrue(r.ran);
        assertEquals("warn", r.verdict, "a structural smell is a warn, not a hard fail");
    }

    @Test
    void structuralStagePassesWhenClean() throws Exception {
        OWLModelManager mm = ontology(true);    // all classes labelled + in hierarchy
        QcStageResult r = QcSuiteTools.structuralStage(mm);
        assertEquals("pass", r.verdict);
    }

    // ------------------------------------------------------------------ invariants stage

    @Test
    void invariantsStageSkippedWhenNoneSupplied() throws Exception {
        SuiteSnapshot snap = snapshot(false);
        QcStageResult r = QcSuiteTools.invariantsStage(snap, Collections.emptyList(),
                1000, 30_000);
        assertFalse(r.ran);
    }

    @Test
    void invariantsStageFailsOnErrorViolation() throws Exception {
        SuiteSnapshot snap = snapshot(false);   // Foo unlabelled
        List<Invariants.Invariant> invs = Collections.singletonList(new Invariants.Invariant(
                "labelled", "every class needs a label", "error",
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }", false));
        QcStageResult r = QcSuiteTools.invariantsStage(snap, invs, 1000, 30_000);
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
        QcStageResult r = QcSuiteTools.invariantsStage(snap, invs, 1000, 30_000);
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
        QcStageResult r = QcSuiteTools.invariantsStage(snap, invs, 1000, 30_000);
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
        QcStageResult r = QcSuiteTools.cqsStage(snap, Collections.emptyList(), 1000, 30_000);
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
        QcStageResult r = QcSuiteTools.cqsStage(snap, Arrays.asList(cq), 1000, 30_000);
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
        QcStageResult r = QcSuiteTools.cqsStage(snap, Arrays.asList(cq), 1000, 30_000);
        assertTrue(r.ran);
        assertEquals("pass", r.verdict, "it passes on the asserted triples");
        assertEquals(1, r.summary.get("caveats"), "the degradation caveat is surfaced, not silent");
    }

    @Test
    void checksLessStagesDistinguishDifferentFailuresAtTheSameCount() throws Exception {
        SuiteSnapshot snap = snapshot(false);
        Invariants.Invariant missingLabel = new Invariants.Invariant("missing-label", "label", "error",
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }", false);
        Invariants.Invariant anyClass = new Invariants.Invariant("any-class", "class", "error",
                "ASK { ?c a owl:Class }", false);
        QcStageResult firstInvariant = QcSuiteTools.invariantsStage(snap,
                List.of(missingLabel), 1000, 30_000);
        QcStageResult secondInvariant = QcSuiteTools.invariantsStage(snap,
                List.of(anyClass), 1000, 30_000);
        assertEquals(firstInvariant.summary.get("violations"), secondInvariant.summary.get("violations"));
        assertNotEquals(firstInvariant.summary.get("identity_digest"),
                secondInvariant.summary.get("identity_digest"));

        CompetencyQuestion noIndividuals = failingNonEmptyCq("no-individuals",
                "SELECT ?i WHERE { ?i a owl:NamedIndividual }");
        CompetencyQuestion noProperties = failingNonEmptyCq("no-properties",
                "SELECT ?p WHERE { ?p a owl:ObjectProperty }");
        QcStageResult firstCq = QcSuiteTools.cqsStage(snap,
                List.of(noIndividuals), 1000, 30_000);
        QcStageResult secondCq = QcSuiteTools.cqsStage(snap,
                List.of(noProperties), 1000, 30_000);
        assertEquals(firstCq.summary.get("failed"), secondCq.summary.get("failed"));
        assertNotEquals(firstCq.summary.get("identity_digest"), secondCq.summary.get("identity_digest"));

        String shapePrefix = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <" + NS + "> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n";
        QcStageResult firstShacl = QcSuiteTools.shaclStage(snap.assertedTurtle(),
                shapePrefix + "ex:S a sh:NodeShape ; sh:targetNode ex:Foo ; "
                        + "sh:property [ sh:path rdfs:comment ; sh:minCount 1 ] .\n",
                null, 1000, 30_000);
        QcStageResult secondShacl = QcSuiteTools.shaclStage(snap.assertedTurtle(),
                shapePrefix + "ex:S a sh:NodeShape ; sh:targetNode ex:Animal ; "
                        + "sh:property [ sh:path rdfs:comment ; sh:minCount 1 ] .\n",
                null, 1000, 30_000);
        assertEquals(firstShacl.summary.get("violations"), secondShacl.summary.get("violations"));
        assertNotEquals(firstShacl.summary.get("identity_digest"),
                secondShacl.summary.get("identity_digest"));
    }

    // ------------------------------------------------------------------ required-reasoner matching

    @Test
    void reasonerMatchesResolvesExactAndVersionlessReferencesAgainstTheCapturedSpec() {
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                reasonerInfo("HermiT 1.4.3.456", "org.semanticweb.HermiT"));

        assertTrue(QcSuiteTools.reasonerMatches("HermiT", spec, null),
                "the version-less convention resolves against the versioned display name");
        assertTrue(QcSuiteTools.reasonerMatches("hermit 1.4.3.456", spec, null),
                "the full display name matches exactly, case-insensitively");
        assertTrue(QcSuiteTools.reasonerMatches("org.semanticweb.HermiT", spec, null),
                "the factory id matches exactly");
        assertFalse(QcSuiteTools.reasonerMatches("HermiT 9.9", spec, null),
                "a versioned reference must agree token-for-token");
        assertFalse(QcSuiteTools.reasonerMatches("ELK", spec, null));
    }

    @Test
    void reasonerMatchesAlsoAcceptsTheLiveSelectionName() {
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                reasonerInfo("HermiT 1.4.3.456", "org.semanticweb.HermiT"));

        assertFalse(QcSuiteTools.reasonerMatches("Protégé Null Reasoner", spec, null));
        assertTrue(QcSuiteTools.reasonerMatches("Protégé Null Reasoner", spec,
                        "Protégé Null Reasoner"),
                "the selected-reasoner display name is an accepted match target");
    }

    /** IsolatedReasonerSpecTest's ProtegeOWLReasonerInfo proxy, parameterized by name/id. */
    private static org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo reasonerInfo(
            String name, String id) {
        var factory = new org.semanticweb.HermiT.ReasonerFactory();
        var configuration = new org.semanticweb.owlapi.reasoner.SimpleConfiguration();
        return (org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo)
                java.lang.reflect.Proxy.newProxyInstance(QcSuiteToolsTest.class.getClassLoader(),
                new Class<?>[] {org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getReasonerId" -> id;
                    case "getReasonerName" -> name;
                    case "getReasonerFactory" -> factory;
                    case "getRecommendedBuffering" ->
                            org.semanticweb.owlapi.reasoner.BufferingMode.NON_BUFFERING;
                    case "getConfiguration" -> configuration;
                    case "toString" -> name + " test info";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CompetencyQuestion failingNonEmptyCq(String id, String query) {
        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = id;
        cq.query = query;
        cq.expected = Expectation.nonEmpty();
        cq.includeInferred = false;
        return cq;
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

    private static QcSuiteExecution execution(List<QcStageResult> results) {
        String hash = "sha256:" + "1".repeat(64);
        return new QcSuiteExecution(results,
                new io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint(
                        1, hash, hash, "cross_restart", true, List.of()), true, null, null);
    }
}
