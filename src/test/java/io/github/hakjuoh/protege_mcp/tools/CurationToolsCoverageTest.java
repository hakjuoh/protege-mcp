package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAsymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLFunctionalDataPropertyAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLInverseFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLIrreflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLReflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLSymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Supplementary coverage for {@link CurationTools}'s Protégé-free change builders, filling the gaps
 * left by {@link CurationToolsTest}: empty/dedup/multi-operand paths of {@code termAxioms},
 * {@code deprecationChanges} and {@code moveClassChanges}, the exact axiom-type mapping and
 * normalisation/error branches of the characteristic resolvers, and the private {@code add()} dedup
 * and {@code addAnnotationAxioms()} language-tag/extra-annotation logic reached through those builders.
 */
class CurationToolsCoverageTest {

    private static final String NS = "http://example.org/cur#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private OWLOntology ont(OWLOntologyManager m) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/cur"));
    }

    private OWLAnnotationProperty deprecatedProp(OWLDataFactory df) {
        return df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI());
    }

    /** True if {@code changes} contains an AddAxiom for {@code ax}. */
    private boolean addsAxiom(List<OWLOntologyChange> changes, OWLAxiom ax) {
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom && ((AddAxiom) ch).getAxiom().equals(ax)) {
                return true;
            }
        }
        return false;
    }

    private int addCount(List<OWLOntologyChange> changes) {
        int n = 0;
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom) {
                n++;
            }
        }
        return n;
    }

    // ================================================================== termAxioms

    @Test
    void termAxiomsWithNoOperandsYieldsEmptyList() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.emptyList(), Collections.emptyList(),
                df.getRDFSComment(), null, null, Collections.emptySet());

        assertTrue(changes.isEmpty(), "no parents/equivalents/definition/extra means no changes");
    }

    @Test
    void termAxiomsAddsAllParentsAndAllEquivalents() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass mammal = cls(df, "Mammal");
        OWLClass pet = cls(df, "Pet");
        OWLClass companion = cls(df, "Companion");

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Arrays.<OWLClassExpression>asList(animal, mammal),
                Arrays.<OWLClassExpression>asList(pet, companion),
                df.getRDFSComment(), null, null, Collections.emptySet());

        assertTrue(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, animal)), "first parent added");
        assertTrue(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, mammal)), "second parent added");
        assertTrue(addsAxiom(changes, df.getOWLEquivalentClassesAxiom(dog, pet)), "first equivalent added");
        assertTrue(addsAxiom(changes, df.getOWLEquivalentClassesAxiom(dog, companion)),
                "second equivalent added");
        assertEquals(4, changes.size(), "exactly the four axioms");
    }

    @Test
    void termAxiomsDefinitionWithoutLangUsesPlainLiteral() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.emptyList(), Collections.emptyList(),
                df.getRDFSComment(), "A domestic dog.", null, Collections.emptySet());

        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSComment(), dog.getIRI(), df.getOWLLiteral("A domestic dog."))),
                "definition added as a plain (no-lang) literal");
        assertFalse(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSComment(), dog.getIRI(), df.getOWLLiteral("A domestic dog.", "en"))),
                "no language-tagged literal when defLang is null");
    }

    @Test
    void termAxiomsNullDefinitionSkipsDefinitionAnnotation() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.<OWLClassExpression>singletonList(animal), Collections.emptyList(),
                df.getRDFSComment(), null, "en", Collections.emptySet());

        assertEquals(1, changes.size(), "only the parent axiom, no definition when defText is null");
        assertTrue(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, animal)));
    }

    @Test
    void termAxiomsAddsEachExtraAnnotation() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLAnnotationProperty seeAlso = df.getRDFSSeeAlso();
        OWLAnnotationProperty label = df.getRDFSLabel();
        Set<OWLAnnotation> extra = new LinkedHashSet<>();
        extra.add(df.getOWLAnnotation(seeAlso, IRI.create(NS + "Reference")));
        extra.add(df.getOWLAnnotation(label, df.getOWLLiteral("Dog", "en")));

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.emptyList(), Collections.emptyList(),
                df.getRDFSComment(), null, null, extra);

        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                seeAlso, dog.getIRI(), IRI.create(NS + "Reference"))), "IRI-valued extra annotation added");
        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                label, dog.getIRI(), df.getOWLLiteral("Dog", "en"))), "literal-valued extra annotation added");
        assertEquals(2, changes.size(), "exactly the two extra annotations");
    }

    @Test
    void termAxiomsDeduplicatesAxiomsAlreadyInOntology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass mammal = cls(df, "Mammal");
        // Pre-assert one of the two parents.
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Arrays.<OWLClassExpression>asList(animal, mammal), Collections.emptyList(),
                df.getRDFSComment(), null, null, Collections.emptySet());

        assertFalse(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, animal)),
                "already-present parent is not re-added");
        assertTrue(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, mammal)),
                "the new parent is still added");
        assertEquals(1, changes.size(), "only the missing axiom appears");
    }

    // ================================================================== deprecationChanges

    @Test
    void deprecationWithNoReplacementAddsOnlyDeprecatedFlag() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, null,
                df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY), Collections.emptySet());

        assertEquals(1, changes.size(), "only owl:deprecated true when no replacement/extra");
        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                deprecatedProp(df), old.getIRI(), df.getOWLLiteral(true))), "owl:deprecated true added");
    }

    @Test
    void deprecationAddsExtraAnnotations() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");
        OWLAnnotationProperty changeNote = df.getOWLAnnotationProperty(
                IRI.create("http://www.w3.org/2004/02/skos/core#changeNote"));
        OWLAnnotationProperty seeAlso = df.getRDFSSeeAlso();
        Set<OWLAnnotation> extra = new LinkedHashSet<>();
        extra.add(df.getOWLAnnotation(changeNote, df.getOWLLiteral("obsoleted 2026")));
        extra.add(df.getOWLAnnotation(seeAlso, IRI.create(NS + "Successor")));

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, null,
                df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY), extra);

        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                changeNote, old.getIRI(), df.getOWLLiteral("obsoleted 2026"))), "change note added");
        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                seeAlso, old.getIRI(), IRI.create(NS + "Successor"))), "seeAlso pointer added");
        assertEquals(3, changes.size(), "deprecated flag + two extra annotations");
    }

    @Test
    void reDeprecatingWithNewReplacementAddsOnlyThePointer() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");
        // Already deprecated, but no replacement pointer yet.
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(deprecatedProp(df), old.getIRI(),
                df.getOWLLiteral(true)));
        IRI replacement = IRI.create(NS + "NewTerm");
        OWLAnnotationProperty replacedByProp = df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY);

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, replacement,
                replacedByProp, Collections.emptySet());

        assertFalse(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                deprecatedProp(df), old.getIRI(), df.getOWLLiteral(true))),
                "the already-present deprecated flag is not re-added");
        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                replacedByProp, old.getIRI(), replacement)), "the new replacement pointer is added");
        assertEquals(1, changes.size(), "only the missing pointer");
    }

    @Test
    void deprecationUsesCustomReplacedByProperty() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");
        IRI replacement = IRI.create(NS + "NewTerm");
        OWLAnnotationProperty custom = df.getOWLAnnotationProperty(IRI.create(NS + "supersededBy"));

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, replacement,
                custom, Collections.emptySet());

        assertTrue(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                custom, old.getIRI(), replacement)), "the pointer uses the caller-supplied property");
        assertFalse(addsAxiom(changes, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY), old.getIRI(), replacement)),
                "not the default IAO property when a custom one is supplied");
    }

    // ================================================================== moveClassChanges

    @Test
    void moveWithNoParentsAndNoNewParentIsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, null, false);

        assertTrue(changes.isEmpty(), "no existing parents and no new parent means nothing to do");
    }

    @Test
    void moveClassWithNoParentsOnlyAddsNewParent() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass pet = cls(df, "Pet");

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, pet, false);

        assertEquals(1, changes.size(), "no parents to remove, only the new parent to add");
        assertTrue(changes.get(0) instanceof AddAxiom, "the only change is an addition");
        assertTrue(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, pet)));
    }

    @Test
    void moveRemovesAllNamedParentsAndAddsNewOnce() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass mammal = cls(df, "Mammal");
        OWLClass pet = cls(df, "Pet");
        OWLAxiom p1 = df.getOWLSubClassOfAxiom(dog, animal);
        OWLAxiom p2 = df.getOWLSubClassOfAxiom(dog, mammal);
        m.addAxiom(o, p1);
        m.addAxiom(o, p2);

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, pet, false);

        assertTrue(changes.contains(new RemoveAxiom(o, p1)), "first named parent removed");
        assertTrue(changes.contains(new RemoveAxiom(o, p2)), "second named parent removed");
        assertTrue(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, pet)), "new parent added");
        assertEquals(1, addCount(changes), "the new parent is added exactly once");
        assertEquals(3, changes.size(), "two removals plus one addition");
    }

    @Test
    void moveKeepsExistingParentEqualToNewParent() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        OWLAxiom keepMe = df.getOWLSubClassOfAxiom(dog, pet);
        OWLAxiom removeMe = df.getOWLSubClassOfAxiom(dog, animal);
        m.addAxiom(o, keepMe);
        m.addAxiom(o, removeMe);

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, pet, false);

        assertFalse(changes.contains(new RemoveAxiom(o, keepMe)),
                "a named parent equal to the new parent is not removed");
        assertFalse(addsAxiom(changes, df.getOWLSubClassOfAxiom(dog, pet)),
                "and it is not re-added (already present)");
        assertEquals(Collections.singletonList(new RemoveAxiom(o, removeMe)), changes,
                "only the other named parent is removed");
    }

    @Test
    void moveKeepOthersWithNullParentIsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, null, true);

        assertTrue(changes.isEmpty(), "keepOthers with no new parent adds and removes nothing");
    }

    // ================================================================== objectCharacteristicAxiom

    @Test
    void objectCharacteristicMapsToExactAxiomTypes() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));

        assertInstanceOf(OWLFunctionalObjectPropertyAxiom.class,
                CurationTools.objectCharacteristicAxiom(df, "functional", p));
        assertInstanceOf(OWLInverseFunctionalObjectPropertyAxiom.class,
                CurationTools.objectCharacteristicAxiom(df, "inverse_functional", p));
        assertInstanceOf(OWLTransitiveObjectPropertyAxiom.class,
                CurationTools.objectCharacteristicAxiom(df, "transitive", p));
        assertInstanceOf(OWLSymmetricObjectPropertyAxiom.class,
                CurationTools.objectCharacteristicAxiom(df, "symmetric", p));
        assertInstanceOf(OWLAsymmetricObjectPropertyAxiom.class,
                CurationTools.objectCharacteristicAxiom(df, "asymmetric", p));
        assertInstanceOf(OWLReflexiveObjectPropertyAxiom.class,
                CurationTools.objectCharacteristicAxiom(df, "reflexive", p));
        assertInstanceOf(OWLIrreflexiveObjectPropertyAxiom.class,
                CurationTools.objectCharacteristicAxiom(df, "irreflexive", p));
    }

    @Test
    void objectCharacteristicNormalisesCaseAndWhitespace() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));

        assertEquals(df.getOWLFunctionalObjectPropertyAxiom(p),
                CurationTools.objectCharacteristicAxiom(df, "FUNCTIONAL", p),
                "uppercase is normalised");
        assertEquals(df.getOWLTransitiveObjectPropertyAxiom(p),
                CurationTools.objectCharacteristicAxiom(df, "  Transitive  ", p),
                "surrounding whitespace is trimmed");
    }

    @Test
    void objectCharacteristicUnknownThrowsWithHelpfulMessage() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));

        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> CurationTools.objectCharacteristicAxiom(df, "bogus", p));
        assertTrue(ex.getMessage().contains("bogus"), "message echoes the bad characteristic name");
        assertTrue(ex.getMessage().contains("inverse_functional"),
                "message lists the valid characteristics");
    }

    // ================================================================== dataCharacteristicAxiom

    @Test
    void dataCharacteristicFunctionalReturnsFunctionalAxiom() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLDataProperty p = df.getOWLDataProperty(IRI.create(NS + "d"));

        assertInstanceOf(OWLFunctionalDataPropertyAxiom.class,
                CurationTools.dataCharacteristicAxiom(df, "functional", p));
    }

    @Test
    void dataCharacteristicNormalisesCaseAndWhitespace() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLDataProperty p = df.getOWLDataProperty(IRI.create(NS + "d"));

        assertEquals(df.getOWLFunctionalDataPropertyAxiom(p),
                CurationTools.dataCharacteristicAxiom(df, "FUNCTIONAL", p),
                "uppercase is normalised");
        assertEquals(df.getOWLFunctionalDataPropertyAxiom(p),
                CurationTools.dataCharacteristicAxiom(df, "  functional ", p),
                "surrounding whitespace is trimmed");
    }

    @Test
    void dataCharacteristicSymmetricThrows() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLDataProperty p = df.getOWLDataProperty(IRI.create(NS + "d"));

        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> CurationTools.dataCharacteristicAxiom(df, "symmetric", p));
        assertTrue(ex.getMessage().contains("only be 'functional'"),
                "message explains a data property can only be functional");
    }

    @Test
    void dataCharacteristicUnknownThrows() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLDataProperty p = df.getOWLDataProperty(IRI.create(NS + "d"));

        assertThrows(ToolArgException.class,
                () -> CurationTools.dataCharacteristicAxiom(df, "reflexive", p));
    }

    // ================================================================== add() dedup via builders

    @Test
    void addDoesNotDuplicateAcrossOperandCategories() throws OWLOntologyCreationException {
        // The same annotation supplied both as the definition and as an extra annotation should be
        // produced only once by termAxioms (add() dedups against the changes it already computed via
        // the ontology-contains check only, so pre-assert it to force the dedup branch).
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLAxiom def = df.getOWLAnnotationAssertionAxiom(
                df.getRDFSComment(), dog.getIRI(), df.getOWLLiteral("A dog."));
        m.addAxiom(o, def);

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.emptyList(), Collections.emptyList(),
                df.getRDFSComment(), "A dog.", null, Collections.emptySet());

        assertTrue(changes.isEmpty(), "a definition already asserted in the ontology is not re-added");
    }

    @Test
    void changeListsAreFreshMutableInstances() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");

        List<OWLOntologyChange> first = CurationTools.moveClassChanges(df, o, dog, animal, false);
        List<OWLOntologyChange> second = CurationTools.moveClassChanges(df, o, dog, animal, false);
        assertNotSame(first, second, "each call returns a distinct list instance, not a shared one");

        List<OWLOntologyChange> changes = first;
        int before = changes.size();
        changes.add(new AddAxiom(o, df.getOWLSubClassOfAxiom(dog, cls(df, "Extra"))));
        assertEquals(before + 1, changes.size(), "the returned list is mutable");

        for (OWLOntologyChange ch : second) {
            if (ch instanceof AddAxiom) {
                OWLAxiom ax = ((AddAxiom) ch).getAxiom();
                assertFalse(ax instanceof OWLAnnotationAssertionAxiom,
                        "move_class emits structural axioms, not annotations");
            }
        }
    }
}
