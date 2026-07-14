package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** Ordering, round-trip, document-coordinate, and degraded-guarantee tests for fingerprint v1. */
class OntologyFingerprintsTest {

    private static final String NS = "https://example.org/fingerprint/";

    @Test
    void semanticFingerprintIgnoresInsertionOrderAndLivePrefixes() throws Exception {
        OWLOntology left = fixture(false);
        OWLOntology right = fixture(true);

        OntologyFingerprint l1 = OntologyFingerprints.compute(left);
        OntologyFingerprint r1 = OntologyFingerprints.compute(right);
        assertEquals(l1.semanticFingerprint(), r1.semanticFingerprint());

        TurtleDocumentFormat format = new TurtleDocumentFormat();
        format.setPrefix("z:", NS);
        format.setPrefix("a:", "https://example.org/another/");
        left.getOWLOntologyManager().setOntologyFormat(left, format);
        OntologyFingerprint l2 = OntologyFingerprints.compute(left);
        assertEquals(l1.semanticFingerprint(), l2.semanticFingerprint(), "prefixes are document state");
        assertNotEquals(l1.documentFingerprint(), l2.documentFingerprint());
    }

    @Test
    void documentFingerprintCoversDocumentIriFormatSortedPrefixesAndLockDigest() throws Exception {
        OWLOntology ontology = fixture(false);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        TurtleDocumentFormat format = new TurtleDocumentFormat();
        format.setPrefix("b:", "https://example.org/b/");
        format.setPrefix("a:", "https://example.org/a/");
        manager.setOntologyFormat(ontology, format);
        manager.setOntologyDocumentIRI(ontology, IRI.create("file:///tmp/one.ttl"));

        OntologyFingerprint base = OntologyFingerprints.compute(ontology,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        TurtleDocumentFormat reordered = new TurtleDocumentFormat();
        reordered.setPrefix("a:", "https://example.org/a/");
        reordered.setPrefix("b:", "https://example.org/b/");
        manager.setOntologyFormat(ontology, reordered);
        assertEquals(base.documentFingerprint(), OntologyFingerprints.compute(ontology,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .documentFingerprint(), "prefix insertion order must not matter");

        manager.setOntologyDocumentIRI(ontology, IRI.create("file:///tmp/two.ttl"));
        assertNotEquals(base.documentFingerprint(), OntologyFingerprints.compute(ontology,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .documentFingerprint());
        manager.setOntologyDocumentIRI(ontology, IRI.create("file:///tmp/one.ttl"));
        assertNotEquals(base.documentFingerprint(), OntologyFingerprints.compute(ontology,
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .documentFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> OntologyFingerprints.compute(ontology, "not-a-digest"));
    }

    @Test
    void semanticFingerprintCoversIdentityImportsOntologyAndAxiomAnnotations() throws Exception {
        OWLOntology base = fixture(false);
        String start = OntologyFingerprints.compute(base).semanticFingerprint();
        OWLDataFactory df = base.getOWLOntologyManager().getOWLDataFactory();

        OWLImportsDeclaration declaration = df.getOWLImportsDeclaration(IRI.create(NS + "imported"));
        base.getOWLOntologyManager().applyChange(new AddImport(base, declaration));
        String imported = OntologyFingerprints.compute(base).semanticFingerprint();
        assertNotEquals(start, imported);

        OWLAnnotation annotation = df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("ontology note"));
        base.getOWLOntologyManager().applyChange(
                new org.semanticweb.owlapi.model.AddOntologyAnnotation(base, annotation));
        assertNotEquals(imported, OntologyFingerprints.compute(base).semanticFingerprint());

        OWLOntology annotated = fixture(false);
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLAxiom plain = df.getOWLSubClassOfAxiom(a, b);
        annotated.getOWLOntologyManager().removeAxiom(annotated, plain);
        annotated.getOWLOntologyManager().addAxiom(annotated, plain.getAnnotatedAxiom(Set.of(annotation)));
        assertNotEquals(start, OntologyFingerprints.compute(annotated).semanticFingerprint(),
                "axiom annotations are semantic-fingerprint input");
    }

    @Test
    void namedOntologyIsStableAcrossTwoSaveReloadCycles(@TempDir Path temp) throws Exception {
        OWLOntology initial = fixture(false);
        String expected = OntologyFingerprints.compute(initial).semanticFingerprint();
        Path one = temp.resolve("one.ofn");
        initial.getOWLOntologyManager().saveOntology(initial, new FunctionalSyntaxDocumentFormat(),
                IRI.create(one.toUri()));

        OWLOntologyManager secondManager = OWLManager.createOWLOntologyManager();
        OWLOntology second = secondManager.loadOntologyFromOntologyDocument(one.toFile());
        assertEquals(expected, OntologyFingerprints.compute(second).semanticFingerprint());
        Path two = temp.resolve("two.ofn");
        secondManager.saveOntology(second, new FunctionalSyntaxDocumentFormat(), IRI.create(two.toUri()));

        OWLOntologyManager thirdManager = OWLManager.createOWLOntologyManager();
        OWLOntology third = thirdManager.loadOntologyFromOntologyDocument(two.toFile());
        OntologyFingerprint result = OntologyFingerprints.compute(third);
        assertEquals(expected, result.semanticFingerprint());
        assertTrue(result.releaseStable());
        assertEquals("cross_restart", result.stability());
    }

    @Test
    void anonymousIndividualsAreExplicitlySessionOnlyWithoutLeakingNodeIds() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "anonymous"));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(
                df.getOWLClass(IRI.create(NS + "A")), df.getOWLAnonymousIndividual("private-node-7")));

        OntologyFingerprint result = OntologyFingerprints.compute(ontology);
        assertFalse(result.releaseStable());
        assertEquals("session_only", result.stability());
        assertEquals(1, result.warnings().size());
        assertFalse(result.toString().contains("private-node-7"));
        assertTrue(result.semanticFingerprint().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void importOverrideFingerprintsAsIfTheImportsWereDeclared() throws Exception {
        // The isolated QC copies deliberately carry no import declarations; the override lets them
        // fingerprint identically to an ontology that really declares those imports (so a delta preflight
        // matches the live, import-bearing revision instead of a phantom import-free one).
        OWLOntology declared = fixture(false);
        OWLDataFactory df = declared.getOWLOntologyManager().getOWLDataFactory();
        declared.getOWLOntologyManager().applyChange(new AddImport(declared,
                df.getOWLImportsDeclaration(IRI.create(NS + "imported"))));

        OWLOntology stripped = fixture(false); // same axioms, NO import declaration
        String withOverride = OntologyFingerprints.compute(stripped, null, List.of(NS + "imported"))
                .semanticFingerprint();
        assertEquals(OntologyFingerprints.compute(declared).semanticFingerprint(), withOverride,
                "an import-stripped copy fingerprinted with the captured imports matches the declared one");
        assertNotEquals(OntologyFingerprints.compute(stripped).semanticFingerprint(), withOverride,
                "without the override the stripped copy fingerprints differently (imports are semantic)");
    }

    @Test
    void anonymousIndividualInOntologyAnnotationIsSessionOnly() throws Exception {
        // getAnonymousIndividuals() only reports axiom-referenced blank nodes; a blank node used as an
        // ontology-header annotation value is still parser-local, so the digest must fall to session_only.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "hdr"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLAnnotation blankCreator = df.getOWLAnnotation(df.getRDFSComment(),
                df.getOWLAnonymousIndividual("header-node-1"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology, blankCreator));

        OntologyFingerprint result = OntologyFingerprints.compute(ontology);
        assertFalse(result.releaseStable(),
                "a blank node reachable only from an ontology annotation must not be release-stable");
        assertEquals("session_only", result.stability());
        assertFalse(result.toString().contains("header-node-1"), "the NodeID must not leak");
    }

    @Test
    void recordRejectsContradictoryStabilityAndMutableWarnings() {
        String digest = "sha256:" + "1".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> new OntologyFingerprint(1, digest, digest, "session_only", true, List.of("x")));
        assertThrows(IllegalArgumentException.class,
                () -> new OntologyFingerprint(1, digest, digest, "session_only", false, List.of()));
    }

    private static OWLOntology fixture(boolean reverse) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "ontology"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        OWLAnnotation note = df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("note", "en"));

        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        OWLAxiom sub = df.getOWLSubClassOfAxiom(a, b);
        OWLAxiom equivalent = df.getOWLEquivalentClassesAxiom(new LinkedHashSet<>(
                reverse ? List.of(c, b, a) : List.of(a, b, c)));
        OWLAxiom annotatedDeclaration = df.getOWLDeclarationAxiom(c, Set.of(note));
        if (reverse) {
            axioms.add(annotatedDeclaration);
            axioms.add(equivalent);
            axioms.add(sub);
        } else {
            axioms.add(sub);
            axioms.add(equivalent);
            axioms.add(annotatedDeclaration);
        }
        manager.addAxioms(ontology, axioms);
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology, note));
        return ontology;
    }
}
