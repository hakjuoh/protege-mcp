package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

class IsolatedPreflightDeltaTest {

    @Test
    void proposedDeltaChangesOnlyThePrivateValidationSnapshot() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/preflight"));
        var df = manager.getOWLDataFactory();
        var axiom = df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create("http://example.org/preflight#A")),
                df.getOWLClass(IRI.create("http://example.org/preflight#B")));
        IsolatedValidationSnapshot before = IsolatedValidationSnapshot.capture(
                FakeModelManager.over(ontology));

        IsolatedValidationSnapshot proposed = before.withChanges(List.of(
                new NormalizedChange(NormalizedChange.Operation.ADD, axiom)));

        assertEquals(0, ontology.getAxiomCount(), "live ontology stays unchanged");
        assertTrue(proposed.active().containsAxiom(axiom));
        assertNotEquals(before.fingerprint().semanticFingerprint(),
                proposed.fingerprint().semanticFingerprint());
        assertNotEquals(before.closureFingerprint(), proposed.closureFingerprint());
    }
}
