package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

/**
 * Method-level tests for the reasoner-injected cores of {@link ReasonerTools} — the package-private
 * {@code unsatisfiableClasses / inferredRelation / explainEntailment / dlQuery /
 * structuralExplanation / relatedAssertedAxioms} helpers. They take an {@link OWLReasoner} as a
 * parameter, so a real OWL API {@link StructuralReasonerFactory} reasoner over a hand-built ontology
 * exercises them fully headless (via {@link FakeModelManager} for the rendering/finder seam).
 *
 * <p>The base ontology is: A; B ⊑ A; C ⊑ B; object property p; individual i : C. Assertions match
 * what the StructuralReasoner actually returns (verified against the live reasoner), NOT the
 * transitive closure a full DL reasoner would produce — e.g. StructuralReasoner does NOT entail the
 * transitive SubClassOf(C, A) and does NOT flag owl:Nothing-equivalent classes as unsatisfiable.
 */
class ReasonerToolsSeamTest {

    private static final String NS = "http://ex.org/#";

    /** A fixture bundling the ontology, its {@link FakeModelManager}, a structural reasoner, and A/B/C/p/i. */
    private static final class Fixture {
        final OWLOntology o;
        final OWLDataFactory df;
        final OWLModelManager mm;
        final OWLReasoner r;
        final OWLClass A;
        final OWLClass B;
        final OWLClass C;
        final OWLObjectProperty p;
        final OWLNamedIndividual i;

        Fixture() {
            try {
                OWLOntologyManager m = OWLManager.createOWLOntologyManager();
                df = m.getOWLDataFactory();
                o = m.createOntology(IRI.create("http://ex.org/"));
                A = df.getOWLClass(IRI.create(NS + "A"));
                B = df.getOWLClass(IRI.create(NS + "B"));
                C = df.getOWLClass(IRI.create(NS + "C"));
                p = df.getOWLObjectProperty(IRI.create(NS + "p"));
                i = df.getOWLNamedIndividual(IRI.create(NS + "i"));
                m.addAxiom(o, df.getOWLDeclarationAxiom(A));
                m.addAxiom(o, df.getOWLSubClassOfAxiom(B, A));
                m.addAxiom(o, df.getOWLSubClassOfAxiom(C, B));
                m.addAxiom(o, df.getOWLDeclarationAxiom(p));
                m.addAxiom(o, df.getOWLClassAssertionAxiom(C, i));
                mm = FakeModelManager.over(o);
                r = new StructuralReasonerFactory().createReasoner(o);
                r.precomputeInferences();
            } catch (OWLOntologyCreationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    /** The set of "display" short forms across an entity-list result's items. */
    private static Set<String> displays(Map<String, Object> result) {
        Set<String> out = new java.util.HashSet<>();
        for (Map<String, Object> item : items(result)) {
            out.add(String.valueOf(item.get("display")));
        }
        return out;
    }

    // ------------------------------------------------------------------ unsatisfiableClasses

    @Test
    void unsatisfiableClassesOnCoherentOntologyIsEmptyAndCoherent() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.unsatisfiableClasses(f.mm, f.r);
        assertEquals(0, result.get("count"), "coherent ontology has no unsatisfiable classes");
        assertEquals(Boolean.TRUE, result.get("coherent"), "coherent flag is true");
        assertTrue(items(result).isEmpty(), "items list is empty");
    }

    // ------------------------------------------------------------------ inferredRelation

    @Test
    void inferredSubclassesNonDirectForAIncludesBandC() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "A", "subclasses", false);
        Set<String> ds = displays(result);
        // StructuralReasoner returns B, C AND owl:Nothing as (non-direct) subclasses of A.
        assertTrue(ds.contains("B"), "B is a subclass of A");
        assertTrue(ds.contains("C"), "C is a (transitive) subclass of A");
        assertTrue(ds.contains("Nothing"), "StructuralReasoner reports owl:Nothing among subclasses");
    }

    @Test
    void inferredSubclassesDirectForAIsOnlyB() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "A", "subclasses", true);
        Set<String> ds = displays(result);
        assertTrue(ds.contains("B"), "B is the direct subclass of A");
        assertFalse(ds.contains("C"), "C is not a DIRECT subclass of A");
    }

    @Test
    void inferredSuperclassesDirectForCIsOnlyB() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "C", "superclasses", true);
        Set<String> ds = displays(result);
        assertTrue(ds.contains("B"), "B is the direct superclass of C");
        assertFalse(ds.contains("A"), "A is not a DIRECT superclass of C");
    }

    @Test
    void inferredSuperclassesNonDirectForCIncludesAandThing() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "C", "superclasses", false);
        Set<String> ds = displays(result);
        assertTrue(ds.contains("A"), "A is a (transitive) superclass of C");
        assertTrue(ds.contains("B"), "B is a superclass of C");
        assertTrue(ds.contains("Thing"), "StructuralReasoner reports owl:Thing among superclasses");
    }

    @Test
    void inferredInstancesNonDirectForAReturnsIndividualI() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "A", "instances", false);
        assertTrue(displays(result).contains("i"), "i is an instance of A (via C ⊑ B ⊑ A)");
    }

    @Test
    void inferredTypesDirectForIndividualIReturnsC() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "i", "types", true);
        assertTrue(displays(result).contains("C"), "C is the (direct) asserted type of i");
    }

    @Test
    void inferredRelationEchoesRelationEntityAndDirectKeys() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "A", "subclasses", true);
        assertEquals("subclasses", result.get("relation"), "relation echoed");
        assertEquals("A", result.get("entity"), "entity echoed");
        assertEquals(Boolean.TRUE, result.get("direct"), "direct echoed");
    }

    @Test
    void inferredRelationItemsCarryIriDisplayType() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.inferredRelation(f.mm, f.r, "A", "subclasses", true);
        List<Map<String, Object>> items = items(result);
        assertFalse(items.isEmpty(), "there is at least the direct subclass B");
        Map<String, Object> b = items.get(0);
        assertEquals(NS + "B", b.get("iri"), "item carries the full IRI");
        assertEquals("B", b.get("display"), "item carries the rendered short form");
        assertEquals("Class", b.get("type"), "item carries the entity type name");
    }

    // ------------------------------------------------------------------ explainEntailment

    @Test
    void explainEntailmentTrueForAssertedSubClassAxiom() {
        Fixture f = new Fixture();
        // StructuralReasoner entails the asserted direct axiom SubClassOf(B, A).
        OWLAxiom ax = f.df.getOWLSubClassOfAxiom(f.B, f.A);
        Map<String, Object> result = ReasonerTools.explainEntailment(f.mm, f.r, ax);
        assertEquals(Boolean.TRUE, result.get("entailed"), "asserted SubClassOf(B,A) is entailed");
        assertNotNull(result.get("axiom"), "axiom map is present");
    }

    @Test
    void explainEntailmentFalseForUnassertedReverseAxiom() {
        Fixture f = new Fixture();
        // A ⊑ C holds in neither the asserted set nor the structural closure.
        OWLAxiom ax = f.df.getOWLSubClassOfAxiom(f.A, f.C);
        Map<String, Object> result = ReasonerTools.explainEntailment(f.mm, f.r, ax);
        assertEquals(Boolean.FALSE, result.get("entailed"), "SubClassOf(A,C) is not entailed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explainEntailmentAxiomMapCarriesTypeAndRendering() {
        Fixture f = new Fixture();
        OWLAxiom ax = f.df.getOWLSubClassOfAxiom(f.B, f.A);
        Map<String, Object> result = ReasonerTools.explainEntailment(f.mm, f.r, ax);
        Map<String, Object> axiom = (Map<String, Object>) result.get("axiom");
        assertEquals("SubClassOf", axiom.get("axiom_type"), "axiom_type is the OWL axiom-type name");
        assertNotNull(axiom.get("rendering"), "axiom rendering is present");
    }

    // ------------------------------------------------------------------ dlQuery

    @Test
    void dlQueryAllIncludesAllFourRelationKeys() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.dlQuery(f.mm, f.r, "A", f.A, "all", true);
        assertTrue(result.containsKey("equivalent"), "all includes equivalent");
        assertTrue(result.containsKey("superclasses"), "all includes superclasses");
        assertTrue(result.containsKey("subclasses"), "all includes subclasses");
        assertTrue(result.containsKey("instances"), "all includes instances");
    }

    @Test
    void dlQueryEchoesQueryAndDirectKeys() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.dlQuery(f.mm, f.r, "A", f.A, "all", false);
        assertEquals("A", result.get("query"), "query echoed verbatim");
        assertEquals(Boolean.FALSE, result.get("direct"), "direct echoed");
    }

    @Test
    void dlQuerySingleRelationIncludesOnlyThatKey() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.dlQuery(f.mm, f.r, "A", f.A, "subclasses", true);
        assertTrue(result.containsKey("subclasses"), "requested relation present");
        assertFalse(result.containsKey("equivalent"), "other relations omitted");
        assertFalse(result.containsKey("superclasses"), "other relations omitted");
        assertFalse(result.containsKey("instances"), "other relations omitted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dlQuerySubclassesDirectForAReturnsBAsEntityList() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.dlQuery(f.mm, f.r, "A", f.A, "subclasses", true);
        Map<String, Object> subs = (Map<String, Object>) result.get("subclasses");
        assertTrue(displays(subs).contains("B"), "B is the direct subclass of A");
        assertFalse(displays(subs).contains("C"), "C is not a direct subclass of A");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dlQueryInstancesForAIncludesIndividualI() {
        Fixture f = new Fixture();
        Map<String, Object> result = ReasonerTools.dlQuery(f.mm, f.r, "A", f.A, "instances", false);
        Map<String, Object> instances = (Map<String, Object>) result.get("instances");
        assertTrue(displays(instances).contains("i"), "i is an instance of A");
    }

    // ------------------------------------------------------------------ structuralExplanation

    @Test
    @SuppressWarnings("unchecked")
    void structuralExplanationEntailedIncludesRelatedAxiomsAndNeighbourhoodNote() {
        Fixture f = new Fixture();
        OWLAxiom ax = f.df.getOWLSubPropertyChainOfAxiom(
                new ArrayList<>(java.util.Arrays.asList(f.p, f.p)), f.p);
        Map<String, Object> result =
                ReasonerTools.structuralExplanation(f.mm, ax, "sub_property_chain_of", true);
        assertEquals(Boolean.TRUE, result.get("entailed"), "entailed echoed true");
        assertEquals(Boolean.FALSE, result.get("justification_available"),
                "the structural fallback never has a minimal justification");
        assertTrue(result.containsKey("related_axioms"), "related_axioms present when entailed");
        assertNotNull(result.get("axiom"), "axiom map present");
        String note = String.valueOf(result.get("note"));
        assertTrue(note.contains("structural neighbourhood"),
                "the note describes the related_axioms as a structural neighbourhood");
    }

    @Test
    void structuralExplanationNotEntailedOmitsRelatedAxiomsAndSaysNothingToJustify() {
        Fixture f = new Fixture();
        OWLAxiom ax = f.df.getOWLSubClassOfAxiom(f.A, f.C);
        Map<String, Object> result =
                ReasonerTools.structuralExplanation(f.mm, ax, "sub_property_chain_of", false);
        assertEquals(Boolean.FALSE, result.get("entailed"), "entailed echoed false");
        assertEquals(Boolean.FALSE, result.get("justification_available"), "no justification");
        assertFalse(result.containsKey("related_axioms"),
                "no related_axioms when the axiom is not entailed");
        String note = String.valueOf(result.get("note"));
        assertTrue(note.contains("nothing to justify"),
                "the note states there is nothing to justify");
    }

    @Test
    void structuralExplanationNoteNamesTheUnsupportedAxiomType() {
        Fixture f = new Fixture();
        OWLAxiom ax = f.df.getOWLSubClassOfAxiom(f.B, f.A);
        Map<String, Object> result =
                ReasonerTools.structuralExplanation(f.mm, ax, "my_weird_type", false);
        assertTrue(String.valueOf(result.get("note")).contains("my_weird_type"),
                "the note echoes the axiom_type that could not be justified");
    }

    // ------------------------------------------------------------------ relatedAssertedAxioms

    @Test
    void relatedAssertedAxiomsForAxiomMentioningAReturnsReferencingLogicalAxioms() {
        Fixture f = new Fixture();
        // An axiom whose signature contains A; SubClassOf(B, A) references A and is a logical axiom.
        OWLAxiom probe = f.df.getOWLSubClassOfAxiom(f.A, f.df.getOWLThing());
        Set<OWLAxiom> related = ReasonerTools.relatedAssertedAxioms(f.o, probe);
        assertTrue(related.contains(f.df.getOWLSubClassOfAxiom(f.B, f.A)),
                "the SubClassOf(B,A) axiom referencing A is collected");
    }

    @Test
    void relatedAssertedAxiomsExcludesDeclarationAxioms() {
        Fixture f = new Fixture();
        OWLAxiom probe = f.df.getOWLSubClassOfAxiom(f.A, f.df.getOWLThing());
        Set<OWLAxiom> related = ReasonerTools.relatedAssertedAxioms(f.o, probe);
        // A's declaration axiom references A but is non-logical and must be excluded.
        assertFalse(related.contains(f.df.getOWLDeclarationAxiom(f.A)),
                "declaration (non-logical) axioms are excluded");
        for (OWLAxiom ax : related) {
            assertTrue(ax.isLogicalAxiom(), "every collected axiom is a logical axiom");
        }
    }

    @Test
    void relatedAssertedAxiomsSkipsBuiltInsWhenSignatureIsAllBuiltIn() {
        Fixture f = new Fixture();
        // owl:Thing ⊑ owl:Thing — its signature is only the built-in owl:Thing, which is skipped,
        // so no referencing axioms are collected.
        OWLAxiom probe = f.df.getOWLSubClassOfAxiom(f.df.getOWLThing(), f.df.getOWLThing());
        Set<OWLAxiom> related = ReasonerTools.relatedAssertedAxioms(f.o, probe);
        assertTrue(related.isEmpty(),
                "built-in-only signatures contribute no related axioms");
    }

    @Test
    void relatedAssertedAxiomsForCollectsBothItsReferencingSubclassAxioms() {
        Fixture f = new Fixture();
        // C's signature: SubClassOf(C,B) and ClassAssertion(C,i) both reference C and are logical.
        OWLAxiom probe = f.df.getOWLSubClassOfAxiom(f.C, f.df.getOWLThing());
        Set<OWLAxiom> related = ReasonerTools.relatedAssertedAxioms(f.o, probe);
        assertTrue(related.contains(f.df.getOWLSubClassOfAxiom(f.C, f.B)),
                "SubClassOf(C,B) is collected");
        assertTrue(related.contains(f.df.getOWLClassAssertionAxiom(f.C, f.i)),
                "ClassAssertion(C,i) is collected");
    }

    // ------------------------------------------------------------------ sanity: fixture wiring

    @Test
    void fixtureFinderResolvesDeclaredEntitiesByShortForm() {
        Fixture f = new Fixture();
        // Sanity that inferredRelation's Tools.resolveClass / resolveIndividual seam works headless:
        // a name that resolves (A) yields items, and rendering short forms are correct.
        assertNotNull(f.mm.getOWLEntityFinder().getOWLEntity("A"));
        assertNull(f.mm.getOWLEntityFinder().getOWLEntity("Nonexistent"),
                "finder returns null for an unknown short form");
    }
}
