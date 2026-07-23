package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

class MaterializationCollisionsTest {
    @Test
    void exactAndAlternateProvenanceFormsRemainACollision() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology();
        var data = manager.getOWLDataFactory();
        var child = data.getOWLClass(IRI.create("https://example.org/Child"));
        var parent = data.getOWLClass(IRI.create("https://example.org/Parent"));
        var property = data.getOWLAnnotationProperty(IRI.create(
                MaterializationService.PROVENANCE_PROPERTY));
        var exact = data.getOWLSubClassOfAxiom(child, parent).getAnnotatedAxiom(Set.of(
                data.getOWLAnnotation(property, IRI.create("https://example.org/provenance/a"))));
        var alternate = data.getOWLSubClassOfAxiom(child, parent).getAnnotatedAxiom(Set.of(
                data.getOWLAnnotation(property, IRI.create("https://example.org/provenance/b"))));
        manager.addAxiom(ontology, exact);
        manager.addAxiom(ontology, alternate);

        MaterializationCollisions.State state =
                MaterializationCollisions.analyze(ontology, Set.of(exact));

        assertEquals(1, state.existing());
        assertEquals(1, state.logical());
        assertEquals(Set.of(alternate), state.differentForms());
        assertFalse(state.exactOnly(1));
        assertFalse(state.commitComplete(1, "replace"));
        assertTrue(state.commitComplete(1, "merge"));
    }
}
