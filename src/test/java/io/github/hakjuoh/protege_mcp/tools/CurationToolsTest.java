package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Exercises the Protégé-free change builders behind the curation macros (create_term / deprecate /
 * move_class) on hand-built ontologies.
 */
class CurationToolsTest {

    private static final String NS = "http://example.org/cur#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private OWLOntology ont(OWLOntologyManager m) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/cur"));
    }

    // ---------------------------------------------------------------- create_term

    @Test
    void termAxiomsBuildParentEquivalentAndDefinition() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.singletonList(animal), Collections.singletonList(pet),
                df.getRDFSComment(), "A domestic dog.", "en", Collections.emptySet());

        assertTrue(contains(changes, df.getOWLSubClassOfAxiom(dog, animal)), "parent SubClassOf added");
        assertTrue(contains(changes, df.getOWLEquivalentClassesAxiom(dog, pet)), "equivalent-class added");
        assertTrue(contains(changes, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSComment(), dog.getIRI(), df.getOWLLiteral("A domestic dog.", "en"))),
                "definition annotation added");
    }

    // ---------------------------------------------------------------- deprecate_entity

    @Test
    void deprecationAddsDeprecatedFlagAndReplacedBy() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");
        IRI replacement = IRI.create(NS + "NewTerm");
        OWLAnnotationProperty replacedByProp = df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY);
        OWLAnnotationProperty deprecated =
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI());

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, replacement,
                replacedByProp, Collections.emptySet());
        assertTrue(contains(changes, df.getOWLAnnotationAssertionAxiom(
                deprecated, old.getIRI(), df.getOWLLiteral(true))), "owl:deprecated true added");
        assertTrue(contains(changes, df.getOWLAnnotationAssertionAxiom(
                replacedByProp, old.getIRI(), replacement)), "term-replaced-by pointer added");
    }

    @Test
    void reDeprecatingIsANoOp() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");
        OWLAnnotationProperty deprecated =
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI());
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(deprecated, old.getIRI(), df.getOWLLiteral(true)));

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, null,
                df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY), Collections.emptySet());
        assertTrue(changes.isEmpty(), "already-deprecated term with no new annotations yields no changes");
    }

    // ---------------------------------------------------------------- move_class

    @Test
    void moveReplacesNamedParentButKeepsRestrictionSupers() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        OWLObjectProperty hasPart = df.getOWLObjectProperty(IRI.create(NS + "hasPart"));
        OWLAxiom namedParent = df.getOWLSubClassOfAxiom(dog, animal);
        OWLAxiom restrictionParent = df.getOWLSubClassOfAxiom(dog,
                df.getOWLObjectSomeValuesFrom(hasPart, cls(df, "Leg")));
        m.addAxiom(o, namedParent);
        m.addAxiom(o, restrictionParent);

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, pet, false);
        assertTrue(changes.contains(new RemoveAxiom(o, namedParent)), "the named parent is removed");
        assertFalse(changes.contains(new RemoveAxiom(o, restrictionParent)),
                "the anonymous restriction superclass is preserved");
        assertTrue(contains(changes, df.getOWLSubClassOfAxiom(dog, pet)), "the new parent is added");
    }

    @Test
    void moveWithKeepOthersOnlyAdds() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, pet, true);
        assertEquals(1, changes.size(), "keep_other_parents adds without removing");
        assertTrue(changes.get(0) instanceof AddAxiom);
        assertTrue(contains(changes, df.getOWLSubClassOfAxiom(dog, pet)));
    }

    @Test
    void detachToRootRemovesNamedParentsOnly() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLAxiom namedParent = df.getOWLSubClassOfAxiom(dog, animal);
        m.addAxiom(o, namedParent);

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, null, false);
        assertEquals(Collections.singletonList(new RemoveAxiom(o, namedParent)), changes,
                "detaching to a root removes the named parent and adds nothing");
    }

    // ---------------------------------------------------------------- characteristics

    @Test
    void characteristicNamesMapToTheRightAxioms() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        for (String c : Arrays.asList("functional", "inverse_functional", "transitive", "symmetric",
                "asymmetric", "reflexive", "irreflexive")) {
            assertTrue(CurationTools.objectCharacteristicAxiom(df, c, p) instanceof OWLAxiom,
                    c + " maps to an axiom");
        }
        assertThrows(ToolArgException.class,
                () -> CurationTools.objectCharacteristicAxiom(df, "bogus", p));
        assertThrows(ToolArgException.class, () -> CurationTools.dataCharacteristicAxiom(df, "transitive",
                df.getOWLDataProperty(IRI.create(NS + "d"))), "a data property cannot be transitive");
    }

    private boolean contains(List<OWLOntologyChange> changes, OWLAxiom ax) {
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom && ((AddAxiom) ch).getAxiom().equals(ax)) {
                return true;
            }
        }
        return false;
    }
}
