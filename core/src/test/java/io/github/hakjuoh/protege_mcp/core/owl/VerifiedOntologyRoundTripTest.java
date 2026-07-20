package io.github.hakjuoh.protege_mcp.core.owl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

class VerifiedOntologyRoundTripTest {

    @Test
    void serializesAndVerifiesANamedOntology() throws Exception {
        OWLOntology ontology = ontology();

        VerifiedOntologyRoundTrip.Result result = VerifiedOntologyRoundTrip.serialize(
                ontology, new TurtleDocumentFormat());

        assertTrue(result.verification().identical());
        assertTrue(result.roundTripClass().equals(VerifiedOntologyRoundTrip.BYTE_FOR_BYTE)
                || result.roundTripClass().equals(VerifiedOntologyRoundTrip.AXIOM_IDENTICAL));
        assertEquals(result.content().length, result.bytes());
        assertEquals(ArtifactStore.sha256(result.content()), result.sha256());
    }

    @Test
    void reloadsMultipleDeclaredImportsThroughOneLocalPlaceholder() throws Exception {
        OWLOntology ontology = ontology();
        for (int index = 0; index < 3; index++) {
            OWLImportsDeclaration declaration = ontology.getOWLOntologyManager().getOWLDataFactory()
                    .getOWLImportsDeclaration(IRI.create(
                            "https://invalid.example.test/never-fetch-" + index));
            ontology.getOWLOntologyManager().applyChange(new AddImport(ontology, declaration));
        }

        VerifiedOntologyRoundTrip.Result result = VerifiedOntologyRoundTrip.serialize(
                ontology, new TurtleDocumentFormat());

        assertTrue(result.verification().imports());
        assertTrue(result.verification().identical());
    }

    @Test
    void rejectsSessionLocalAnonymousIndividuals() throws Exception {
        OWLOntology ontology = ontology();
        OWLDataFactory data = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLAnonymousIndividual anonymous = data.getOWLAnonymousIndividual();
        ontology.getOWLOntologyManager().addAxiom(ontology, data.getOWLClassAssertionAxiom(
                data.getOWLClass(IRI.create("https://example.org/Class")), anonymous));

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> VerifiedOntologyRoundTrip.serialize(ontology, new TurtleDocumentFormat()));

        assertTrue(refusal.getMessage().contains("anonymous individuals"), refusal::getMessage);
    }

    @Test
    void returnedContentIsDefensive() throws Exception {
        VerifiedOntologyRoundTrip.Result result = VerifiedOntologyRoundTrip.serialize(
                ontology(), new TurtleDocumentFormat());
        byte[] expected = result.content();
        byte[] mutation = result.content();
        mutation[0] ^= 0x7f;

        assertFalse(java.util.Arrays.equals(mutation, result.content()));
        assertArrayEquals(expected, result.content());
    }

    private static OWLOntology ontology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/round-trip"));
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(IRI.create("https://example.org/Term"))));
        return ontology;
    }
}
