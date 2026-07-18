package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService;
import io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService.Candidates;
import io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService.Evaluation;
import io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService.Sigma;

/**
 * inferred-diff-v1 grammar over real HermiT classification (ADR 0004 verification bullets). Each
 * side is evaluated into plain data and its reasoner disposed before comparison, matching the
 * one-live-instance discipline the plugin orchestration must keep.
 */
class InferredDiffServiceTest {

    private static final String NS = "http://example.org/inferred#";
    private static final InferredDiffService.BudgetGuard NO_BUDGET = () -> { };

    @Test
    void inconsistentSideSuppressesMemberCategoriesWithACaveat() throws Exception {
        Fixture left = new Fixture();
        left.subClass("A", "B");
        Fixture right = new Fixture();
        right.subClass("A", "B");
        right.disjoint("A", "B");
        right.type("a", "A");
        right.type("a", "B");
        Sigma sigma = sigma("A", "B", "a");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> consistency = (Map<?, ?>) result.get("consistency");
        assertEquals(Boolean.TRUE, consistency.get("left"));
        assertEquals(Boolean.FALSE, consistency.get("right"));
        assertEquals(Boolean.TRUE, consistency.get("changed"));
        assertNotNull(consistency.get("caveat"));
        assertEquals(Boolean.TRUE, result.get("categories_suppressed"));
        assertNull(result.get("subsumption"), "member categories must be suppressed");
        assertNull(result.get("satisfiability"));
    }

    @Test
    void satisfiabilityDeltaCoversBornUnsatisfiableClassesOutsideSigma() throws Exception {
        Fixture left = new Fixture();
        left.subClass("A", "B");
        Fixture right = new Fixture();
        right.subClass("A", "B");
        right.disjoint("P", "Q");
        right.subClass("NewC", "P");
        right.subClass("NewC", "Q");
        // Sigma deliberately excludes NewC: satisfiability ignores sigma (full signature).
        Sigma sigma = sigma("A", "B", "P", "Q");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> satisfiability = (Map<?, ?>) result.get("satisfiability");
        Map<?, ?> newly = (Map<?, ?>) satisfiability.get("newly_unsatisfiable");
        assertEquals(1, newly.get("count"), satisfiability::toString);
        assertTrue(((List<?>) newly.get("items")).contains(NS + "NewC"));
        Map<?, ?> recovered = (Map<?, ?>) satisfiability.get("no_longer_unsatisfiable");
        assertEquals(0, recovered.get("count"));
    }

    @Test
    void lostIndirectEntailmentIsDetectedWhileDirectEdgesSurvive() throws Exception {
        Fixture left = new Fixture();
        left.subClass("A", "B");
        left.subClass("B", "C");
        Fixture right = new Fixture();
        right.subClass("A", "B");
        right.subClass("B", "D");
        right.declare("C");
        Sigma sigma = sigma("A", "B", "C", "D");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> subsumption = (Map<?, ?>) result.get("subsumption");
        Map<?, ?> lost = (Map<?, ?>) subsumption.get("lost");
        List<?> lostEdges = (List<?>) lost.get("edges");
        // Transitive reduction keeps B⊑C (A⊑C follows through it inside the same delta).
        assertEquals(1, lost.get("count"), subsumption::toString);
        Map<?, ?> edge = (Map<?, ?>) lostEdges.get(0);
        assertEquals(NS + "B", edge.get("sub"));
        assertEquals(NS + "C", edge.get("super"));
        assertEquals("direct", edge.get("label"));
        Map<?, ?> gained = (Map<?, ?>) subsumption.get("gained");
        assertTrue(gained.get("count").equals(1), "B⊑D gained: " + subsumption);
    }

    @Test
    void insertedIntermediateClassChangesNoClosureMembershipOverSigma() throws Exception {
        Fixture left = new Fixture();
        left.subClass("A", "C");
        Fixture right = new Fixture();
        right.subClass("A", "Mid");
        right.subClass("Mid", "C");
        // Sigma excludes the intermediate: the entailed A⊑C relation over sigma is unchanged.
        Sigma sigma = sigma("A", "C");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> subsumption = (Map<?, ?>) result.get("subsumption");
        assertEquals(0, ((Map<?, ?>) subsumption.get("gained")).get("count"),
                subsumption::toString);
        assertEquals(0, ((Map<?, ?>) subsumption.get("lost")).get("count"));
    }

    @Test
    void tautologiesAndBottomMembersAreExcluded() throws Exception {
        Fixture left = new Fixture();
        left.declare("A");
        Fixture right = new Fixture();
        right.subClass("A", "B");
        right.disjoint("U", "V");
        right.subClass("Broken", "U");
        right.subClass("Broken", "V");
        Sigma sigma = sigma("A", "B", "Broken", "U", "V");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> subsumption = (Map<?, ?>) result.get("subsumption");
        List<?> gained = (List<?>) ((Map<?, ?>) subsumption.get("gained")).get("edges");
        for (Object row : gained) {
            Map<?, ?> edge = (Map<?, ?>) row;
            assertFalse(String.valueOf(edge.get("super")).endsWith("owl#Thing"), edge::toString);
            assertFalse(String.valueOf(edge.get("sub")).contains("Broken"),
                    "bottom-node members stay out of the hierarchy categories: " + edge);
            assertFalse(String.valueOf(edge.get("sub")).equals(edge.get("super")));
        }
    }

    @Test
    void equivalenceMergesAndSplitsAreReportedAsPairDeltas() throws Exception {
        Fixture left = new Fixture();
        left.equivalent("A", "B");
        left.declare("C");
        Fixture right = new Fixture();
        right.declare("A");
        right.declare("B");
        right.equivalent("A", "C");
        Sigma sigma = sigma("A", "B", "C");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> equivalence = (Map<?, ?>) result.get("equivalence");
        Map<?, ?> gainedPairs = (Map<?, ?>) equivalence.get("gained_pairs");
        Map<?, ?> lostPairs = (Map<?, ?>) equivalence.get("lost_pairs");
        assertEquals(List.of(List.of(NS + "A", NS + "C")), gainedPairs.get("items"),
                equivalence::toString);
        assertEquals(List.of(List.of(NS + "A", NS + "B")), lostPairs.get("items"));
    }

    @Test
    void typeDeltasCarryDirectAndIndirectLabelsAndExcludeThing() throws Exception {
        Fixture left = new Fixture();
        left.type("rex", "Dog");
        left.declare("Animal");
        Fixture right = new Fixture();
        right.type("rex", "Dog");
        right.subClass("Dog", "Animal");
        Sigma sigma = sigma("Dog", "Animal", "rex");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> types = (Map<?, ?>) result.get("types");
        Map<?, ?> gained = (Map<?, ?>) types.get("gained");
        assertEquals(1, gained.get("count"), types::toString);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) gained.get("rows")).get(0);
        assertEquals(NS + "rex", row.get("individual"));
        assertEquals(NS + "Animal", row.get("type"));
        assertEquals("indirect", row.get("label"));
        for (Object anyRow : (List<?>) gained.get("rows")) {
            assertFalse(String.valueOf(((Map<?, ?>) anyRow).get("type")).endsWith("owl#Thing"));
        }
    }

    @Test
    void disjointnessIsCandidateBoundedWithScopeLabelAndTruncationDisclosure() throws Exception {
        Fixture left = new Fixture();
        left.declare("A");
        left.declare("B");
        left.subClass("A2", "A");
        left.subClass("B2", "B");
        Fixture right = new Fixture();
        // Candidate sourced from the RIGHT side only; entailed on the right, not the left.
        right.disjoint("A", "B");
        right.subClass("A2", "A");
        right.subClass("B2", "B");
        // Out-of-sigma pair never becomes a candidate.
        right.disjoint("X", "Y");
        Sigma sigma = sigma("A", "B", "A2", "B2");

        Evaluation leftEval = evaluate(left, sigma);
        Evaluation rightEval = evaluate(right, sigma);
        Candidates candidates = InferredDiffService.disjointnessCandidates(leftEval, rightEval, 10);
        assertEquals(1, candidates.total(), candidates::toString);
        assertFalse(candidates.truncated());

        Map<String, Boolean> leftVerdicts = verdicts(left, candidates);
        Map<String, Boolean> rightVerdicts = verdicts(right, candidates);
        Map<String, Object> result = InferredDiffService.compare(leftEval, rightEval,
                leftVerdicts, rightVerdicts, candidates, 25);

        Map<?, ?> disjointness = (Map<?, ?>) result.get("disjointness");
        assertEquals("asserted_candidates", disjointness.get("disjointness_scope"));
        Map<?, ?> gained = (Map<?, ?>) disjointness.get("gained");
        assertEquals(1, gained.get("count"), disjointness::toString);

        // Cap disclosure: cap of 0 truncates deterministically and says so.
        Candidates capped = InferredDiffService.disjointnessCandidates(leftEval, rightEval, 0);
        assertTrue(capped.truncated());
        assertEquals(1, capped.total());
        assertEquals(0, capped.pairs().size());
    }

    @Test
    void unsatisfiablePairsAreExcludedFromDisjointnessCandidates() throws Exception {
        Fixture left = new Fixture();
        left.disjoint("U", "V");
        left.subClass("Broken", "U");
        left.subClass("Broken", "V");
        left.disjoint("Broken", "A");
        Fixture right = new Fixture();
        right.declare("A");
        right.declare("Broken");
        Sigma sigma = sigma("A", "Broken", "U", "V");

        Evaluation leftEval = evaluate(left, sigma);
        Evaluation rightEval = evaluate(right, sigma);
        Candidates candidates = InferredDiffService.disjointnessCandidates(leftEval, rightEval, 10);
        for (List<String> pair : candidates.pairs()) {
            assertFalse(pair.contains(NS + "Broken"),
                    "trivially disjoint unsatisfiable pairs are excluded: " + candidates);
        }
    }

    @Test
    void aThrowingReasonerErrorsOnlyItsCategory() throws Exception {
        Fixture left = new Fixture();
        left.type("rex", "Dog");
        Fixture right = new Fixture();
        right.type("rex", "Dog");
        right.subClass("Dog", "Animal");
        Sigma sigma = sigma("Dog", "Animal", "rex");

        Evaluation leftEval = evaluate(left, sigma);
        OWLReasoner throwing = throwingOnGetTypes(right.reasoner());
        Evaluation rightEval = InferredDiffService.evaluate(right.ontology, throwing, sigma,
                NO_BUDGET);
        right.dispose();

        Map<String, Object> result = InferredDiffService.compare(leftEval, rightEval,
                Map.of(), Map.of(), new Candidates(List.of(), 0, false), 25);

        Map<?, ?> types = (Map<?, ?>) result.get("types");
        assertNotNull(types.get("error"), types::toString);
        assertTrue(String.valueOf(types.get("error")).contains("getTypes"));
        Map<?, ?> subsumption = (Map<?, ?>) result.get("subsumption");
        assertNull(subsumption.get("error"), "other categories stay intact: " + subsumption);
        assertTrue(((List<?>) result.get("errored_categories")).contains("types"));
    }

    @Test
    void resultsAreDeterministicAcrossRuns() throws Exception {
        Map<String, Object> first = richComparison();
        Map<String, Object> second = richComparison();
        assertEquals(first, second, "inferred-diff-v1 must be deterministic");
        assertEquals("inferred-diff-v1", first.get("entailment_set"));
        assertEquals(List.of("property_hierarchies", "property_characteristics",
                "property_assertions"), first.get("excluded_categories"));
    }

    private Map<String, Object> richComparison() throws Exception {
        Fixture left = new Fixture();
        left.subClass("A", "B");
        left.subClass("B", "C");
        left.equivalent("D", "E");
        left.type("rex", "A");
        Fixture right = new Fixture();
        right.subClass("A", "C");
        right.declare("B");
        right.declare("D");
        right.declare("E");
        right.type("rex", "A");
        Sigma sigma = sigma("A", "B", "C", "D", "E", "rex");
        return compare(left, right, sigma, 25);
    }

    @Test
    void gainedEntailmentThroughAOneSideOnlyIntermediateIsReported() throws Exception {
        // Blocker regression: the only witness path for the gained A⊑C runs through the right-only
        // class V (outside Σ); a full-closure reduction witness would silently erase the delta.
        Fixture left = new Fixture();
        left.declare("A");
        left.declare("C");
        Fixture right = new Fixture();
        right.subClass("A", "V");
        right.subClass("V", "C");
        Sigma sigma = sigma("A", "C");

        Map<String, Object> result = compare(left, right, sigma, 25);

        Map<?, ?> subsumption = (Map<?, ?>) result.get("subsumption");
        Map<?, ?> gained = (Map<?, ?>) subsumption.get("gained");
        assertEquals(1, gained.get("count"), subsumption::toString);
        Map<?, ?> edge = (Map<?, ?>) ((List<?>) gained.get("edges")).get(0);
        assertEquals(NS + "A", edge.get("sub"));
        assertEquals(NS + "C", edge.get("super"));
        assertEquals("indirect", edge.get("label"));
    }

    @Test
    void crossKindPunsStayOutOfClassCategoriesAndDisjointnessCandidates() throws Exception {
        // Typed-Σ regression: 'Pun' is an individual on the left and a class on the right, so it
        // must enter neither the class categories nor the disjointness candidates (whose
        // fresh-entity exclusion depends on both sides knowing the CLASS).
        Fixture left = new Fixture();
        left.type("Pun", "A");
        left.declare("B");
        Fixture right = new Fixture();
        right.declare("A");
        right.declare("B");
        right.declare("Pun");
        right.disjoint("Pun", "B");
        Set<String> classSigma = new TreeSet<>(Set.of(NS + "A", NS + "B"));
        Set<String> individualSigma = new TreeSet<>();
        Sigma sigma = new Sigma(classSigma, individualSigma);

        Evaluation leftEval = evaluate(left, sigma);
        Evaluation rightEval = evaluate(right, sigma);
        Candidates candidates = InferredDiffService.disjointnessCandidates(leftEval, rightEval, 10);
        for (List<String> pair : candidates.pairs()) {
            assertFalse(pair.contains(NS + "Pun"),
                    "a cross-kind pun must not become a disjointness candidate: " + candidates);
        }
        Map<String, Object> result = InferredDiffService.compare(leftEval, rightEval,
                Map.of(), Map.of(), candidates, 25);
        Map<?, ?> subsumption = (Map<?, ?>) result.get("subsumption");
        assertEquals(0, ((Map<?, ?>) subsumption.get("gained")).get("count"),
                subsumption::toString);
    }

    // ------------------------------------------------------------------ fixtures

    /** One side: an ontology plus a HermiT reasoner over it, disposable independently. */
    private static final class Fixture {
        final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        final OWLDataFactory df = manager.getOWLDataFactory();
        final OWLOntology ontology;
        private OWLReasoner reasoner;

        Fixture() throws Exception {
            ontology = manager.createOntology(IRI.create(NS.substring(0, NS.length() - 1)));
        }

        void declare(String name) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls(name)));
        }

        void subClass(String sub, String sup) {
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls(sub), cls(sup)));
        }

        void equivalent(String a, String b) {
            manager.addAxiom(ontology, df.getOWLEquivalentClassesAxiom(cls(a), cls(b)));
        }

        void disjoint(String a, String b) {
            manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(cls(a), cls(b)));
        }

        void type(String individualName, String className) {
            manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(cls(className),
                    individual(individualName)));
        }

        OWLClass cls(String name) {
            return df.getOWLClass(IRI.create(NS + name));
        }

        OWLNamedIndividual individual(String name) {
            return df.getOWLNamedIndividual(IRI.create(NS + name));
        }

        OWLReasoner reasoner() {
            if (reasoner == null) {
                reasoner = new org.semanticweb.HermiT.ReasonerFactory().createReasoner(ontology);
            }
            return reasoner;
        }

        void dispose() {
            if (reasoner != null) {
                reasoner.dispose();
                reasoner = null;
            }
        }
    }

    /** Test convention: uppercase names are classes, lowercase names are individuals. */
    private static Sigma sigma(String... names) {
        Set<String> classes = new TreeSet<>();
        Set<String> individuals = new TreeSet<>();
        for (String name : names) {
            (Character.isUpperCase(name.charAt(0)) ? classes : individuals).add(NS + name);
        }
        return new Sigma(classes, individuals);
    }

    /** Evaluate a side and dispose its reasoner — the one-live-instance discipline. */
    private static Evaluation evaluate(Fixture fixture, Sigma sigma) {
        try {
            return InferredDiffService.evaluate(fixture.ontology, fixture.reasoner(), sigma,
                    NO_BUDGET);
        } finally {
            fixture.dispose();
        }
    }

    private static Map<String, Boolean> verdicts(Fixture fixture, Candidates candidates) {
        try {
            return InferredDiffService.disjointnessVerdicts(fixture.ontology, fixture.reasoner(),
                    candidates, NO_BUDGET);
        } finally {
            fixture.dispose();
        }
    }

    private static Map<String, Object> compare(Fixture left, Fixture right, Sigma sigma,
            int limit) {
        Evaluation leftEval = evaluate(left, sigma);
        Evaluation rightEval = evaluate(right, sigma);
        Candidates candidates = InferredDiffService.disjointnessCandidates(leftEval, rightEval, 200);
        // Mirror the orchestration: verdicts are only computable on a consistent side, and the
        // comparison suppresses member categories anyway when either side is inconsistent.
        boolean verdictsComputable = leftEval.consistent() && rightEval.consistent()
                && !candidates.pairs().isEmpty();
        Map<String, Boolean> leftVerdicts = verdictsComputable ? verdicts(left, candidates) : Map.of();
        Map<String, Boolean> rightVerdicts = verdictsComputable ? verdicts(right, candidates) : Map.of();
        return InferredDiffService.compare(leftEval, rightEval, leftVerdicts, rightVerdicts,
                candidates, limit);
    }

    /** Wrap HermiT so getTypes throws, proving per-category fail-closed isolation. */
    private static OWLReasoner throwingOnGetTypes(OWLReasoner delegate) {
        return (OWLReasoner) Proxy.newProxyInstance(InferredDiffServiceTest.class.getClassLoader(),
                new Class<?>[] { OWLReasoner.class }, (proxy, method, args) -> {
                    if ("getTypes".equals(method.getName())) {
                        throw new UnsupportedOperationException("getTypes is not supported");
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(OWLReasoner delegate, Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
