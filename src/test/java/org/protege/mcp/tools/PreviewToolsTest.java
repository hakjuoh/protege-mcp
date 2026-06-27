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
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/** {@link PreviewTools#newEntities} — which entities an add would introduce vs. those already known. */
class PreviewToolsTest {

    private static final String NS = "http://example.org/test#";

    @Test
    void introducesOnlyTrulyNewEntities() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/test"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass fresh = df.getOWLClass(IRI.create(NS + "Fresh"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        m.addAxiom(o, df.getOWLDeclarationAxiom(b));
        Set<OWLOntology> closure = o.getImportsClosure();

        // A ⊑ Fresh: Fresh is new, A is already known.
        OWLSubClassOfAxiom add = df.getOWLSubClassOfAxiom(a, fresh);
        Set<OWLEntity> introduced = PreviewTools.newEntities(closure, add);
        assertTrue(introduced.contains(fresh), "Fresh is new");
        assertFalse(introduced.contains(a), "A already exists");

        // A ⊑ B: nothing new.
        assertTrue(PreviewTools.newEntities(closure, df.getOWLSubClassOfAxiom(a, b)).isEmpty(),
                "both operands already exist");
    }
}
