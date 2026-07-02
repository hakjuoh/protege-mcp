package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * End-to-end coverage of the {@code apply_changes verify=report|rollback} safety promise with a REAL
 * OWL 2 DL reasoner (HermiT, test-scoped). The headless {@code StructuralReasoner} does no inference, so
 * the unit tests in {@link ApplyVerifyTest} can only feed hand-built IRI sets into {@link ApplyVerify#decide};
 * here a real reasoner classifies a genuinely unsatisfiable class / inconsistent ontology, its verdict
 * flows through the production {@link ApplyVerify#unsatIris(OWLReasoner)} iteration, and the resulting set
 * drives {@code decide} — so the "reasoner detects a real regression → rollback/report" leg is CI-gated,
 * not only exercised by the manual {@code docs/smoke-test.md} checklist. (The remaining live legs — EDT
 * marshalling and Protégé's {@code HistoryManager.undo()} — still need a running Protégé and stay in that
 * checklist.)
 */
class ApplyVerifyRealReasonerTest {

    private static final String NS = "http://example.org/verify#";
    private static final OWLReasonerFactory REASONER_FACTORY = new org.semanticweb.HermiT.ReasonerFactory();

    private final OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = mgr.getOWLDataFactory();

    private OWLClass cls(String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    // ----------------------------------------------------------------- newly unsatisfiable → rollback

    @Test
    void realReasonerDetectsUnsatisfiableClassAndDecideRollsBack() throws Exception {
        OWLOntology ont = mgr.createOntology(IRI.create(NS + "o"));
        OWLClass a = cls("A");
        OWLClass b = cls("B");
        OWLClass x = cls("X");
        // A and B are disjoint; X is a subclass of both → X is unsatisfiable.
        mgr.addAxiom(ont, df.getOWLDisjointClassesAxiom(a, b));
        mgr.addAxiom(ont, df.getOWLSubClassOfAxiom(x, a));
        mgr.addAxiom(ont, df.getOWLSubClassOfAxiom(x, b));

        OWLReasoner reasoner = REASONER_FACTORY.createReasoner(ont);
        try {
            // The production iteration over a REAL reasoner's unsatisfiable-classes verdict.
            Set<String> postUnsat = ApplyVerify.unsatIris(reasoner);
            assertTrue(postUnsat.contains(NS + "X"),
                    "HermiT should flag X as unsatisfiable; got " + postUnsat);

            // rollback: a batch that (in this scenario) introduced X → regression attributed → rolled back.
            ApplyVerify.Outcome rollback = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, false,
                    Collections.emptySet(), postUnsat, false, true);
            assertTrue(rollback.regression);
            assertTrue(rollback.rolledBack, "rollback mode must undo an attributable regression");
            assertTrue(rollback.newlyUnsatisfiable.contains(NS + "X"));
            assertFalse(rollback.batchApplied, "the batch was rolled back, so it does not remain applied");

            // report: same regression, but the batch is KEPT and flagged.
            ApplyVerify.Outcome report = ApplyVerify.decide(ApplyVerify.MODE_REPORT, true, false, false,
                    Collections.emptySet(), postUnsat, false, true);
            assertTrue(report.regression);
            assertFalse(report.rolledBack, "report mode never rolls back");
            assertTrue(report.batchApplied, "report mode keeps the batch");
            assertTrue(report.newlyUnsatisfiable.contains(NS + "X"));
        } finally {
            reasoner.dispose();
        }
    }

    // ------------------------------------------------------ pre-existing unsat is NOT attributed to us

    @Test
    void preExistingUnsatisfiableIsNotAttributedToTheBatch() throws Exception {
        OWLOntology ont = mgr.createOntology(IRI.create(NS + "o2"));
        OWLClass a = cls("A");
        OWLClass b = cls("B");
        OWLClass x = cls("X");
        mgr.addAxiom(ont, df.getOWLDisjointClassesAxiom(a, b));
        mgr.addAxiom(ont, df.getOWLSubClassOfAxiom(x, a));
        mgr.addAxiom(ont, df.getOWLSubClassOfAxiom(x, b));

        OWLReasoner reasoner = REASONER_FACTORY.createReasoner(ont);
        try {
            Set<String> unsat = ApplyVerify.unsatIris(reasoner);
            assertTrue(unsat.contains(NS + "X"));
            // X was already unsatisfiable BEFORE the batch (pre == post) → no regression, no rollback.
            ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, false,
                    unsat, unsat, false, true);
            assertFalse(o.regression, "a pre-existing unsatisfiable class is not this batch's regression");
            assertFalse(o.rolledBack);
            assertTrue(o.newlyUnsatisfiable.isEmpty());
        } finally {
            reasoner.dispose();
        }
    }

    // ----------------------------------------------------------------- newly inconsistent → rollback

    @Test
    void realReasonerDetectsInconsistencyAndDecideRollsBack() throws Exception {
        OWLOntology ont = mgr.createOntology(IRI.create(NS + "o3"));
        OWLClass a = cls("A");
        OWLClass b = cls("B");
        OWLNamedIndividual i = df.getOWLNamedIndividual(IRI.create(NS + "i"));
        // A and B disjoint, yet i is asserted to be both → the ontology is inconsistent.
        mgr.addAxiom(ont, df.getOWLDisjointClassesAxiom(a, b));
        mgr.addAxiom(ont, df.getOWLClassAssertionAxiom(a, i));
        mgr.addAxiom(ont, df.getOWLClassAssertionAxiom(b, i));

        OWLReasoner reasoner = REASONER_FACTORY.createReasoner(ont);
        try {
            assertFalse(reasoner.isConsistent(), "HermiT should find the ontology inconsistent");
            // Production does not read unsat on an inconsistent ontology; it feeds postInconsistent=true.
            ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, true,
                    Collections.emptySet(), Collections.emptySet(), false, true);
            assertTrue(o.regression);
            assertTrue(o.rolledBack, "a newly inconsistent ontology is an attributable regression");
            assertTrue(o.postInconsistent);
        } finally {
            reasoner.dispose();
        }
    }

    // ----------------------------------------------------------------------- consistent → no regression

    @Test
    void consistentOntologyProducesNoRegression() throws Exception {
        OWLOntology ont = mgr.createOntology(IRI.create(NS + "o4"));
        OWLClass a = cls("A");
        OWLClass x = cls("X");
        mgr.addAxiom(ont, df.getOWLSubClassOfAxiom(x, a)); // perfectly satisfiable

        OWLReasoner reasoner = REASONER_FACTORY.createReasoner(ont);
        try {
            assertTrue(reasoner.isConsistent());
            Set<String> unsat = ApplyVerify.unsatIris(reasoner);
            assertTrue(unsat.isEmpty(), "no class should be unsatisfiable; got " + unsat);
            ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, false,
                    Collections.emptySet(), unsat, false, true);
            assertFalse(o.regression);
            assertFalse(o.rolledBack);
            assertTrue(o.batchApplied);
        } finally {
            reasoner.dispose();
        }
    }
}
