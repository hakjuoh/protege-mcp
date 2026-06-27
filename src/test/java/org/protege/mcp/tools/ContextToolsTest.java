package org.protege.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/** {@link ContextTools#rootClasses} (asserted roots) and {@link ContextTools#isDeprecated}. */
class ContextToolsTest {

    private static final String NS = "http://example.org/test#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private boolean contains(Set<OWLClass> classes, String name) {
        for (OWLClass c : classes) {
            if (c.getIRI().toString().equals(NS + name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void rootClassesAreThoseWithNoNamedSuperclass() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/test"));
        OWLClass animal = cls(df, "Animal");
        OWLClass dog = cls(df, "Dog");
        OWLClass standalone = cls(df, "Standalone");
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dog));
        m.addAxiom(o, df.getOWLDeclarationAxiom(standalone));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));

        Set<OWLClass> roots = ContextTools.rootClasses(o);
        assertTrue(contains(roots, "Animal"), "Animal has no named superclass");
        assertTrue(contains(roots, "Standalone"), "Standalone has no superclass");
        assertFalse(contains(roots, "Dog"), "Dog is a subclass of Animal");
    }

    @Test
    void detectsDeprecatedEntities() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/test"));
        OWLClass old = cls(df, "Old");
        OWLClass current = cls(df, "Current");
        m.addAxiom(o, df.getOWLDeclarationAxiom(old));
        m.addAxiom(o, df.getOWLDeclarationAxiom(current));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI()),
                old.getIRI(), df.getOWLLiteral(true)));

        Set<OWLOntology> scope = Collections.singleton(o);
        assertTrue(ContextTools.isDeprecated(old, scope), "Old is owl:deprecated true");
        assertFalse(ContextTools.isDeprecated(current, scope), "Current is not deprecated");
    }
}
