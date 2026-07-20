package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

class CompetencyQuestionServiceTest {

    @Test
    void runsQuestionsAndProjectsAStableQcFailure() throws Exception {
        QuerySnapshot snapshot = snapshotWithClass();
        var passing = question("passing", expectation(
                CompetencyQuestionService.ExpectationKind.NON_EMPTY),
                "ASK { ?c a <http://www.w3.org/2002/07/owl#Class> }", false);
        var failing = question("failing", expectation(
                CompetencyQuestionService.ExpectationKind.NON_EMPTY),
                "ASK { ?i a <http://www.w3.org/2002/07/owl#NamedIndividual> }", false);

        CompetencyQuestionService.Result result = CompetencyQuestionService.evaluate(
                snapshot, List.of(passing, failing), 100, 10_000);

        assertEquals(QcStageVerdict.FAIL, result.execution().verdict());
        assertEquals(2, result.execution().details().get("total"));
        assertEquals(1, result.execution().details().get("failed"));
        assertEquals(1, result.gatingIdentities().size());
        assertEquals(result.execution().details().get("identity_digest"),
                CompetencyQuestionService.evaluate(snapshot, List.of(passing, failing), 100, 10_000)
                        .execution().details().get("identity_digest"));
    }

    @Test
    void inferredQuestionsDegradeExplicitlyToAssertedData() throws Exception {
        var question = question("inferred", expectation(
                CompetencyQuestionService.ExpectationKind.NON_EMPTY),
                "ASK { ?c a <http://www.w3.org/2002/07/owl#Class> }", true);

        Map<String, Object> run = CompetencyQuestionService.run(
                snapshotWithClass(), List.of(question), 100, 10_000,
                CompetencyQuestionService.FAIL_ON_ANY);

        assertEquals(1, run.get("passed"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) run.get("questions")).get(0);
        assertTrue(((List<?>) row.get("caveats")).stream()
                .map(String::valueOf).anyMatch(value -> value.startsWith("include_inferred")));
        assertEquals(1, InvariantQcService.countInferenceCaveats(run.get("questions")));
    }

    @Test
    void malformedQueriesFailTheQuestionWithoutAbortingTheRun() throws Exception {
        var broken = question("broken", expectation(
                CompetencyQuestionService.ExpectationKind.NON_EMPTY),
                "SELECT {{{ not sparql", false);

        Map<String, Object> run = CompetencyQuestionService.run(
                snapshotWithClass(), List.of(broken), 100, 10_000,
                CompetencyQuestionService.FAIL_ON_ANY);

        assertEquals(1, run.get("failed"));
        assertEquals("fail", run.get("gate"));
        assertEquals(1, CompetencyQuestionService.countErrors(run.get("questions")));
    }

    @Test
    void truncatedSelectCountsOnlyPassWhenTheLowerBoundProvesTheExpectation() {
        Map<String, Object> execution = select(5, true);

        var proven = CompetencyQuestionService.judgeExecution(count(">=", 3), execution, 5);
        var unknown = CompetencyQuestionService.judgeExecution(count("<=", 100), execution, 5);

        assertTrue(proven.pass());
        assertNull(proven.error());
        assertTrue(proven.caveats().stream().anyMatch(value -> value.contains("lower bound")));
        assertFalse(unknown.pass());
        assertTrue(unknown.error().contains("true count is unknown"));
    }

    @Test
    void truncatedGraphCountsRemainExact() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("query_type", "CONSTRUCT");
        execution.put("count", 2000L);
        execution.put("truncated", true);

        var judged = CompetencyQuestionService.judgeExecution(count("==", 2000), execution, 1000);

        assertTrue(judged.pass());
        assertNull(judged.error());
        assertTrue(judged.caveats().stream().anyMatch(value -> value.contains("exact graph size")));
    }

    @Test
    void exactRowsAreOrderInsensitiveButRejectBlankNodesAndTruncation() {
        Map<String, Object> first = row("s", cell("uri", "https://example.org/A"));
        Map<String, Object> second = row("s", cell("uri", "https://example.org/B"));
        var expected = exactRows(List.of(second, first));

        assertTrue(CompetencyQuestionService.judgeExecution(
                expected, selectBindings(List.of(first, second), false), 100).pass());

        Map<String, Object> blank = row("s", cell("bnode", "b0"));
        var bnode = CompetencyQuestionService.judgeExecution(
                expected, selectBindings(List.of(blank), false), 100);
        assertFalse(bnode.pass());
        assertTrue(bnode.caveats().stream().anyMatch(value -> value.contains("blank nodes")));

        var truncated = CompetencyQuestionService.judgeExecution(
                expected, selectBindings(List.of(first), true), 1);
        assertFalse(truncated.pass());
        assertTrue(truncated.error().contains("truncated"));
    }

    @Test
    void rowEncodingCannotCollideThroughCraftedLiteralDelimiters() {
        Map<String, Object> twoCells = new LinkedHashMap<>();
        twoCells.put("a", cell("literal", "x"));
        twoCells.put("b", cell("literal", "y"));
        Map<String, Object> crafted = row("a", cell("literal", "x||\u0001b=literal|y"));

        assertFalse(CompetencyQuestionService.canonicalizeRows(List.of(twoCells)).equals(
                CompetencyQuestionService.canonicalizeRows(List.of(crafted))));
    }

    @Test
    void emptyQuestionSetIsSkipped() throws Exception {
        CompetencyQuestionService.Result result = CompetencyQuestionService.evaluate(
                snapshotWithClass(), List.of(), 100, 10_000);

        assertEquals(QcStageVerdict.SKIPPED, result.execution().verdict());
        assertTrue(result.gatingIdentities().isEmpty());
    }

    private static CompetencyQuestionService.Question question(String id,
            CompetencyQuestionService.Expectation expected, String query, boolean includeInferred) {
        return new CompetencyQuestionService.Question(id, null, expected, "test", query,
                includeInferred);
    }

    private static CompetencyQuestionService.Expectation expectation(
            CompetencyQuestionService.ExpectationKind kind) {
        return new CompetencyQuestionService.Expectation(kind, null, null, List.of());
    }

    private static CompetencyQuestionService.Expectation count(String operator, int value) {
        return new CompetencyQuestionService.Expectation(
                CompetencyQuestionService.ExpectationKind.COUNT, operator, value, List.of());
    }

    private static CompetencyQuestionService.Expectation exactRows(List<Map<String, Object>> rows) {
        return new CompetencyQuestionService.Expectation(
                CompetencyQuestionService.ExpectationKind.EXACT_ROWS, null, null, rows);
    }

    private static QuerySnapshot snapshotWithClass() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/cqs"));
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(IRI.create("https://example.org/cqs#Term"))));
        return QuerySnapshot.capture(ontology, Set.of(ontology), Map.of());
    }

    private static Map<String, Object> select(int count, boolean truncated) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            bindings.add(row("s", cell("uri", "https://example.org/" + index)));
        }
        return selectBindings(bindings, truncated);
    }

    private static Map<String, Object> selectBindings(List<Map<String, Object>> bindings,
            boolean truncated) {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("query_type", "SELECT");
        execution.put("count", bindings.size());
        execution.put("bindings", bindings);
        if (truncated) execution.put("truncated", true);
        return execution;
    }

    private static Map<String, Object> row(String variable, Map<String, Object> cell) {
        return new LinkedHashMap<>(Map.of(variable, cell));
    }

    private static Map<String, Object> cell(String type, String value) {
        return new LinkedHashMap<>(Map.of("type", type, "value", value));
    }
}
