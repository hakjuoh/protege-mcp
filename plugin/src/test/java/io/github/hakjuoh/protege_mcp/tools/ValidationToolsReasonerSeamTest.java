package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

/**
 * Exercises the injected, headless-friendly overload
 * {@link ValidationTools#reasonerVerdict(OWLModelManager, OWLReasoner, int)} against a real OWL API
 * {@link org.semanticweb.owlapi.reasoner.structural.StructuralReasoner StructuralReasoner}.
 *
 * <p>Important limitation captured by these tests: the OWL API {@code StructuralReasoner} performs
 * NO logical inference — it treats told class relationships structurally. It therefore reports every
 * (structurally well-formed) ontology as {@code consistent == true} and NEVER populates the
 * unsatisfiable-class set, even for a class equivalent to {@code owl:Nothing} or one subsumed by an
 * explicit contradiction {@code (D and not D)}. The full inference behaviour (a non-empty
 * {@code unsatisfiable_classes.items}, the truncation cap firing, an INCONSISTENT verdict with the
 * diagnostic {@code note}) requires a real reasoner (e.g. HermiT/Pellet) supplied by the Protégé
 * reasoner manager and cannot be reproduced with StructuralReasoner headless. Those branches are
 * documented here but asserted only to the extent StructuralReasoner exercises them.
 */
class ValidationToolsReasonerSeamTest {

    private static final String NS = "http://example.org/rv#";

    private OWLOntology ont(OWLOntologyManager m) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/rv"));
    }

    private OWLReasoner structural(OWLOntology o) {
        return new StructuralReasonerFactory().createReasoner(o);
    }

    // ---------------------------------------------------------------- consistent + coherent

    @Test
    void consistentOntologyReportsConsistentTrue() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "A"))));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "B"))));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, structural(o), 100);

        assertEquals(Boolean.TRUE, v.get("consistent"));
    }

    @Test
    void consistentCoherentOntologyHasZeroUnsatisfiableCount() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "A"))));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, structural(o), 100);

        assertEquals(0, v.get("unsatisfiable_count"));
    }

    @Test
    void consistentVerdictCarriesEntityListShapedUnsatisfiableClasses()
            throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "A"))));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, structural(o), 100);

        Object uc = v.get("unsatisfiable_classes");
        assertInstanceOf(Map.class, uc);
        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) uc;
        // entityList shape: {count, items:[...]} — count 0 and an empty items list when coherent.
        assertEquals(0, list.get("count"));
        assertInstanceOf(List.class, list.get("items"));
        assertTrue(((List<?>) list.get("items")).isEmpty(), "coherent ontology: no unsat items");
    }

    @Test
    void consistentVerdictOmitsInconsistentNote() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "A"))));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, structural(o), 100);

        // The diagnostic "note" key is only added on the INCONSISTENT branch.
        assertFalse(v.containsKey("note"), "consistent branch must not emit the inconsistent note");
        assertFalse(v.containsKey("error"), "no reasoner error expected for a clean ontology");
    }

    @Test
    void emptyOntologyIsConsistentAndCoherent() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ont(m);
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, structural(o), 100);

        assertEquals(Boolean.TRUE, v.get("consistent"));
        assertEquals(0, v.get("unsatisfiable_count"));
    }

    // ---------------------------------------------------------------- unsatisfiable named class

    @Test
    void classEquivalentToNothingIsNotDetectedByStructuralReasoner()
            throws OWLOntologyCreationException {
        // X EquivalentClasses owl:Nothing — logically unsatisfiable, but the StructuralReasoner
        // does no inference and leaves the ontology "consistent" with an empty unsat set. This test
        // pins that documented StructuralReasoner limitation.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass x = df.getOWLClass(IRI.create(NS + "X"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(x));
        m.addAxiom(o, df.getOWLEquivalentClassesAxiom(x, df.getOWLNothing()));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, structural(o), 100);

        assertEquals(Boolean.TRUE, v.get("consistent"));
        assertEquals(0, v.get("unsatisfiable_count"),
                "StructuralReasoner performs no inference, so X≡Nothing is not flagged");
    }

    @Test
    void classSubsumedByContradictionIsNotDetectedByStructuralReasoner()
            throws OWLOntologyCreationException {
        // X subClassOf (D and not D) — again unsatisfiable under inference but invisible to the
        // purely structural reasoner. Verdict stays consistent with a zero unsat count.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass x = df.getOWLClass(IRI.create(NS + "X"));
        OWLClass d = df.getOWLClass(IRI.create(NS + "D"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(x));
        m.addAxiom(o, df.getOWLDeclarationAxiom(d));
        OWLClassExpression contradiction =
                df.getOWLObjectIntersectionOf(d, df.getOWLObjectComplementOf(d));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(x, contradiction));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, structural(o), 100);

        assertEquals(Boolean.TRUE, v.get("consistent"));
        assertEquals(0, v.get("unsatisfiable_count"),
                "StructuralReasoner does not derive X ⊑ (D ⊓ ¬D) unsatisfiability");
        Object uc = v.get("unsatisfiable_classes");
        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) uc;
        // X does not appear in items because the structural reasoner never flags it.
        assertTrue(((List<?>) list.get("items")).isEmpty());
    }

    // ---------------------------------------------------------------- limit parameter (cap fires)

    /**
     * A Proxy {@link OWLReasoner} that is consistent and reports {@code unsat} as its unsatisfiable
     * classes. The real {@code StructuralReasoner} never produces a non-empty unsat set, so this is
     * the only way to drive the {@code Tools.entityList} truncation/cap path headless.
     */
    private OWLReasoner unsatReasoner(java.util.Set<OWLClass> unsat) {
        org.semanticweb.owlapi.reasoner.Node<OWLClass> node =
                new org.semanticweb.owlapi.reasoner.impl.OWLClassNode(unsat);
        return (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { OWLReasoner.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isConsistent":
                            return Boolean.TRUE;
                        case "getUnsatisfiableClasses":
                            return node;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private java.util.Set<OWLClass> declareClasses(OWLOntologyManager m, OWLOntology o, String prefix,
            int n) {
        OWLDataFactory df = m.getOWLDataFactory();
        java.util.Set<OWLClass> set = new java.util.LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            OWLClass c = df.getOWLClass(IRI.create(NS + prefix + i));
            m.addAxiom(o, df.getOWLDeclarationAxiom(c));
            set.add(c);
        }
        return set;
    }

    @Test
    void limitCapsUnsatisfiableItemsAndReportsTruncation() throws OWLOntologyCreationException {
        // Inject 5 unsatisfiable classes via a Proxy reasoner so the actual cap fires: items are
        // capped at the limit, count is the FULL size, and a 'truncated' key reports the omission.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ont(m);
        java.util.Set<OWLClass> unsat = declareClasses(m, o, "U", 5);
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, unsatReasoner(unsat), 2);

        assertEquals(Boolean.TRUE, v.get("consistent"));
        assertEquals(5, v.get("unsatisfiable_count"), "full unsat count reported");
        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) v.get("unsatisfiable_classes");
        assertEquals(5, list.get("count"), "count is the full unsat set size");
        assertEquals(2, ((List<?>) list.get("items")).size(), "items capped at the limit");
        assertEquals(3, list.get("truncated"), "reports how many offenders were omitted");
    }

    @Test
    void zeroLimitYieldsEmptyItemsButFullCount() throws OWLOntologyCreationException {
        // With a genuinely non-empty unsat set, limit 0 must clamp items to empty (Math.max(0,limit))
        // while count/unsatisfiable_count still reflect the true size — proving the clamp, not an
        // incidentally-empty set, is why items is empty.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ont(m);
        java.util.Set<OWLClass> unsat = declareClasses(m, o, "Z", 3);
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, unsatReasoner(unsat), 0);

        assertEquals(3, v.get("unsatisfiable_count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) v.get("unsatisfiable_classes");
        assertTrue(((List<?>) list.get("items")).isEmpty(), "limit 0 -> Math.max(0,limit) -> no items");
        assertEquals(3, list.get("count"), "count still reflects the full unsat set");
        assertEquals(3, list.get("truncated"), "all offenders omitted");
    }

    // ---------------------------------------------------------------- error branch (defensive)

    @Test
    void reasonerThrowingIsCapturedAsErrorEntry() throws OWLOntologyCreationException {
        // If the reasoner blows up, reasonerVerdict swallows the RuntimeException into an "error"
        // entry rather than propagating. Use a Proxy reasoner whose isConsistent() throws.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ont(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLReasoner boom = (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { OWLReasoner.class },
                (proxy, method, args) -> {
                    if ("isConsistent".equals(method.getName())) {
                        throw new IllegalStateException("reasoner exploded");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, boom, 10);

        assertInstanceOf(String.class, v.get("error"));
        String err = (String) v.get("error");
        assertTrue(err.contains("IllegalStateException"), "error names the exception type: " + err);
        assertTrue(err.contains("reasoner exploded"), "error includes the message: " + err);
        // On the error path, no consistency/unsat keys are produced.
        assertFalse(v.containsKey("consistent"));
        assertFalse(v.containsKey("unsatisfiable_count"));
    }
}
