package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;

/**
 * Validates the {@link FakeModelManager} test harness itself against the real {@link Tools} and
 * {@link Axioms} code paths — the harness is depended on by other tests, so it is tested here.
 */
class FakeModelManagerTest {

    private static final String NS = "http://example.org/fake#";

    private OWLClass declareClass(OWLModelManager mm, String name) {
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLOntology o = mm.getActiveOntology();
        OWLClass c = df.getOWLClass(IRI.create(NS + name));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(c));
        return c;
    }

    @Test
    void dataFactoryAndActiveOntologyAreLive() {
        OWLModelManager mm = FakeModelManager.empty();
        assertNotNull(mm.getOWLDataFactory());
        assertNotNull(mm.getActiveOntology());
        assertSame(mm.getActiveOntology(), mm.getActiveOntology());
    }

    @Test
    void shortFormStripsNamespace() {
        assertEquals("Dog", FakeModelManager.shortForm(IRI.create(NS + "Dog")));
        assertEquals("Dog", FakeModelManager.shortForm(IRI.create("http://example.org/Dog")));
    }

    @Test
    void resolveClassFindsDeclaredByName() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLClass dog = declareClass(mm, "Dog");
        assertEquals(dog, Tools.resolveClass(mm, "Dog"));
    }

    @Test
    void resolveClassMintsFromFullIri() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLClass c = Tools.resolveClass(mm, NS + "New");
        assertEquals(IRI.create(NS + "New"), c.getIRI());
    }

    @Test
    void resolveClassThrowsForUnknownName() {
        OWLModelManager mm = FakeModelManager.empty();
        assertThrows(ToolArgException.class, () -> Tools.resolveClass(mm, "Nope"));
    }

    @Test
    void renderingIsShortForm() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLClass dog = declareClass(mm, "Dog");
        assertEquals("Dog", mm.getRendering(dog));
    }

    @Test
    void axiomsBuildsCharacteristicFromFullIri() {
        OWLModelManager mm = FakeModelManager.empty();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("axiom_type", "transitive_object_property");
        a.put("property", NS + "partOf");
        assertTrue(Axioms.build(mm, a) instanceof OWLTransitiveObjectPropertyAxiom);
    }

    @Test
    void axiomsBuildsSubclassFromDeclaredNames() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLClass dog = declareClass(mm, "Dog");
        OWLClass animal = declareClass(mm, "Animal");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("axiom_type", "subclass_of");
        a.put("sub", "Dog");
        a.put("super", "Animal");
        OWLDataFactory df = mm.getOWLDataFactory();
        assertEquals(df.getOWLSubClassOfAxiom(dog, animal), Axioms.build(mm, a));
    }

    @Test
    void axiomsRejectsUnsupportedType() {
        OWLModelManager mm = FakeModelManager.empty();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("axiom_type", "bogus_axiom");
        assertThrows(ToolArgException.class, () -> Axioms.build(mm, a));
    }

    @Test
    void unsupportedManagerMethodFailsLoudly() {
        OWLModelManager mm = FakeModelManager.empty();
        assertThrows(UnsupportedOperationException.class, mm::getOWLReasonerManager);
    }
}
