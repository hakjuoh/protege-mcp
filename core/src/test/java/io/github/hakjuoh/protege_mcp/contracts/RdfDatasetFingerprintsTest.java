package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class RdfDatasetFingerprintsTest {

    @ParameterizedTest
    @ValueSource(strings = {"test003", "test020", "test028", "test044", "test060", "test072"})
    void matchesTheOfficialW3cCanonicalizationVectors(String name) throws Exception {
        String input = vector(name + "-in.nq");
        String expected = vector(name + "-rdfc10.nq");

        assertEquals(expected, RdfDatasetFingerprints.canonicalizeNQuads(input, 120_000));
    }

    @Test
    void canonicalQuadsAreSortedInCodePointOrderNotUtf16Order() {
        String supplementary = new String(Character.toChars(0x10000));
        String input = "<urn:a> <urn:p> \"a" + supplementary + "\" .\n"
                + "<urn:a> <urn:p> \"a\" .\n";

        String canonical = RdfDatasetFingerprints.canonicalizeNQuads(input, 5_000);

        // RDFC-1.0 code point order: U+E000 sorts below U+10000. Java's UTF-16 code unit
        // order would put the surrogate-encoded supplementary quad first.
        assertEquals("<urn:a> <urn:p> \"a\" .\n"
                + "<urn:a> <urn:p> \"a" + supplementary + "\" .\n", canonical);
        assertTrue(canonical.endsWith(".\n"));
    }

    @Test
    void canonicalNQuadsAreInvariantToBlankNodeLabelsAndStatementOrder() {
        String first = "_:alice <https://example.org/knows> _:bob .\n"
                + "_:bob <https://example.org/name> \"Bob\" .\n";
        String second = "_:z <https://example.org/name> \"Bob\" .\n"
                + "_:y <https://example.org/knows> _:z .\n";

        assertEquals(RdfDatasetFingerprints.canonicalizeNQuads(first, 5_000),
                RdfDatasetFingerprints.canonicalizeNQuads(second, 5_000));
    }

    @Test
    void ontologyFingerprintIsStableAcrossAnonymousIndividualIdentifiers() throws Exception {
        OWLOntology first = ontologyWithAnonymousIndividual("one");
        OWLOntology second = ontologyWithAnonymousIndividual("two");

        RdfDatasetFingerprint a = RdfDatasetFingerprints.compute(first);
        RdfDatasetFingerprint b = RdfDatasetFingerprints.compute(second);

        assertEquals(a.rdfDatasetFingerprint(), b.rdfDatasetFingerprint());
        assertEquals("RDFC-1.0", a.canonicalizationAlgorithm());
        assertEquals("SHA-256", a.hashAlgorithm());
        assertEquals("root-ontology", a.scope());
    }

    @Test
    void directImportCoordinatesArePartOfTheRootDatasetIdentity() throws Exception {
        OWLOntology ontology = ontologyWithAnonymousIndividual("one");
        RdfDatasetFingerprint withoutImport = RdfDatasetFingerprints.compute(ontology, null, 5_000);
        RdfDatasetFingerprint withImport = RdfDatasetFingerprints.compute(ontology,
                java.util.List.of("https://example.org/imported"), 5_000);

        assertNotEquals(withoutImport.rdfDatasetFingerprint(),
                withImport.rdfDatasetFingerprint());
    }

    @Test
    void nonPositiveTimeoutIsRejectedBeforeCanonicalization() throws Exception {
        OWLOntology ontology = ontologyWithAnonymousIndividual("one");
        assertThrows(IllegalArgumentException.class,
                () -> RdfDatasetFingerprints.compute(ontology, null, 0));
    }

    @Test
    void pinnedDigestDetectsSilentCanonicalizationChanges() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/pinned"));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(
                data.getOWLClass(IRI.create("https://example.org/Person")),
                data.getOWLNamedIndividual(IRI.create("https://example.org/alice"))));
        manager.addAxiom(ontology, data.getOWLAnnotationAssertionAxiom(
                data.getRDFSLabel(), IRI.create("https://example.org/alice"),
                data.getOWLLiteral("Alice", "en")));

        RdfDatasetFingerprint fingerprint = RdfDatasetFingerprints.compute(ontology,
                java.util.List.of("https://example.org/imported"), 30_000);

        // Absolute pin: a titanium-rdfc or OWLAPI N-Quads rendering change that alters the
        // canonical bytes must fail loudly, because external systems record this digest.
        assertEquals("sha256:c7bf96dc9bfddfac8511b986eba955cf287a1688dcea86c4e48e2ce82696771f",
                fingerprint.rdfDatasetFingerprint());
    }

    @Test
    void rootlessAnonymousCyclesFailClosedInsteadOfCollidingSilently() throws Exception {
        // OWLAPI's RDF renderer drops rootless anonymous-individual cycles, so a cycle of 4 and a
        // cycle of 400 would otherwise digest identically; the digest must refuse instead.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RdfDatasetFingerprints.compute(ontologyWithAnonymousCycle(4, false)));
        assertTrue(error.getMessage().contains("dropped"), error::getMessage);
        assertThrows(IllegalStateException.class,
                () -> RdfDatasetFingerprints.compute(ontologyWithAnonymousCycle(2, false)));
        // A self-loop is NOT dropped — OWLAPI renders `_:b p _:b` faithfully — so it must
        // keep fingerprinting; only actual rendering loss fails the digest.
        assertTrue(RdfDatasetFingerprints.compute(ontologyWithAnonymousCycle(1, false))
                .rdfDatasetFingerprint().startsWith("sha256:"));
    }

    @Test
    void inverseAssertionsBetweenAnonymousIndividualsFailClosedWhenDropped() throws Exception {
        // Both arrangements of an anonymous inverse-property pair are silently dropped by
        // OWLAPI's renderer (verified empirically), previously colliding with the empty digest.
        for (boolean sameDirection : new boolean[] {true, false}) {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.createOntology(
                    IRI.create("https://example.org/inverse-" + sameDirection));
            OWLDataFactory data = manager.getOWLDataFactory();
            var p = data.getOWLObjectProperty(IRI.create("https://example.org/p"));
            var q = data.getOWLObjectProperty(IRI.create("https://example.org/q"));
            OWLAnonymousIndividual x = data.getOWLAnonymousIndividual("x");
            OWLAnonymousIndividual y = data.getOWLAnonymousIndividual("y");
            manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(p, x, y));
            manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(
                    data.getOWLObjectInverseOf(q), sameDirection ? x : y, sameDirection ? y : x));

            assertThrows(IllegalStateException.class,
                    () -> RdfDatasetFingerprints.compute(ontology), "sameDirection=" + sameDirection);
        }
    }

    @Test
    void anchoredAnonymousCyclesRemainFingerprintable() throws Exception {
        // A cycle reachable from a named individual IS rendered faithfully by OWLAPI (verified
        // empirically), so it must fingerprint — and the back-edge must be part of the digest.
        RdfDatasetFingerprint two = RdfDatasetFingerprints.compute(
                ontologyWithAnonymousCycle(2, true));
        RdfDatasetFingerprint three = RdfDatasetFingerprints.compute(
                ontologyWithAnonymousCycle(3, true));

        assertTrue(two.rdfDatasetFingerprint().startsWith("sha256:"));
        assertNotEquals(two.rdfDatasetFingerprint(), three.rdfDatasetFingerprint());
    }

    @Test
    void anonymousAnnotationCyclesFailClosedButChainsFingerprint() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology cycle = manager.createOntology(IRI.create("https://example.org/ann-cycle"));
        OWLDataFactory data = manager.getOWLDataFactory();
        var property = data.getOWLAnnotationProperty(IRI.create("https://example.org/ap"));
        OWLAnonymousIndividual x = data.getOWLAnonymousIndividual("x");
        OWLAnonymousIndividual y = data.getOWLAnonymousIndividual("y");
        manager.addAxiom(cycle, data.getOWLAnnotationAssertionAxiom(property, x, y));
        manager.addAxiom(cycle, data.getOWLAnnotationAssertionAxiom(property, y, x));
        assertThrows(IllegalStateException.class, () -> RdfDatasetFingerprints.compute(cycle));

        OWLOntology chain = manager.createOntology(IRI.create("https://example.org/ann-chain"));
        manager.addAxiom(chain, data.getOWLAnnotationAssertionAxiom(property,
                data.getOWLAnonymousIndividual("c1"), data.getOWLAnonymousIndividual("c2")));
        assertTrue(RdfDatasetFingerprints.compute(chain)
                .rdfDatasetFingerprint().startsWith("sha256:"));
    }

    @Test
    void reservedVocabularyPropertiesFailClosedInsteadOfSpoofingTheCount() throws Exception {
        // rdf:type as a user object property (OWL Full misuse) would let a dropped anonymous
        // cycle hide behind declaration-emitted rdf:type triples; the verification must refuse.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/reserved"));
        OWLDataFactory data = manager.getOWLDataFactory();
        var rdfType = data.getOWLObjectProperty(
                IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"));
        OWLAnonymousIndividual x = data.getOWLAnonymousIndividual("x");
        OWLAnonymousIndividual y = data.getOWLAnonymousIndividual("y");
        manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(rdfType, x, y));
        manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(rdfType, y, x));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RdfDatasetFingerprints.compute(ontology));
        assertTrue(error.getMessage().contains("reserved"), error::getMessage);
    }

    @Test
    void negativeAssertionsAmongAnonymousIndividualsFailClosedWhenDropped() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory data = manager.getOWLDataFactory();
        var p = data.getOWLObjectProperty(IRI.create("https://example.org/p"));

        // Anonymous pair: OWLAPI drops the whole reified structure — must fail closed.
        OWLOntology anonymous = manager.createOntology(IRI.create("https://example.org/neg-anon"));
        OWLAnonymousIndividual x = data.getOWLAnonymousIndividual("x");
        OWLAnonymousIndividual y = data.getOWLAnonymousIndividual("y");
        manager.addAxiom(anonymous, data.getOWLNegativeObjectPropertyAssertionAxiom(p, x, y));
        manager.addAxiom(anonymous, data.getOWLNegativeObjectPropertyAssertionAxiom(p, y, x));
        assertThrows(IllegalStateException.class,
                () -> RdfDatasetFingerprints.compute(anonymous));

        // Named pair: the reification renders, so it must keep fingerprinting and be covered.
        OWLOntology named = manager.createOntology(IRI.create("https://example.org/neg-named"));
        var n1 = data.getOWLNamedIndividual(IRI.create("https://example.org/n1"));
        var n2 = data.getOWLNamedIndividual(IRI.create("https://example.org/n2"));
        manager.addAxiom(named, data.getOWLNegativeObjectPropertyAssertionAxiom(p, n1, n2));
        RdfDatasetFingerprint one = RdfDatasetFingerprints.compute(named);
        manager.addAxiom(named, data.getOWLNegativeObjectPropertyAssertionAxiom(p, n2, n1));
        RdfDatasetFingerprint two = RdfDatasetFingerprints.compute(named);
        assertNotEquals(one.rdfDatasetFingerprint(), two.rdfDatasetFingerprint());
    }

    @Test
    void selfReferentialAnonymousTypeExpressionsFailClosedWhenDropped() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory data = manager.getOWLDataFactory();
        var p = data.getOWLObjectProperty(IRI.create("https://example.org/p"));
        OWLAnonymousIndividual x = data.getOWLAnonymousIndividual("x");
        OWLAnonymousIndividual y = data.getOWLAnonymousIndividual("y");

        // ClassAssertion(ObjectHasValue(p, x), x) is valid OWL 2 DL, yet OWLAPI's rendering
        // drops it entirely — the digest must refuse instead of matching the empty ontology.
        OWLOntology selfTyped = manager.createOntology(IRI.create("https://example.org/self"));
        manager.addAxiom(selfTyped, data.getOWLClassAssertionAxiom(
                data.getOWLObjectHasValue(p, x), x));
        assertThrows(IllegalStateException.class,
                () -> RdfDatasetFingerprints.compute(selfTyped));

        // Indirect pure-type cycle across two class assertions is dropped the same way.
        OWLOntology indirect = manager.createOntology(IRI.create("https://example.org/indirect"));
        manager.addAxiom(indirect, data.getOWLClassAssertionAxiom(
                data.getOWLObjectHasValue(p, y), x));
        manager.addAxiom(indirect, data.getOWLClassAssertionAxiom(
                data.getOWLObjectHasValue(p, x), y));
        assertThrows(IllegalStateException.class,
                () -> RdfDatasetFingerprints.compute(indirect));

        // Acyclic expression references render faithfully and must keep fingerprinting,
        // with the referenced individual part of the digest.
        OWLOntology acyclic = manager.createOntology(IRI.create("https://example.org/acyclic"));
        manager.addAxiom(acyclic, data.getOWLClassAssertionAxiom(
                data.getOWLObjectHasValue(p, y), x));
        RdfDatasetFingerprint with = RdfDatasetFingerprints.compute(acyclic);
        assertTrue(with.rdfDatasetFingerprint().startsWith("sha256:"));
        OWLOntology without = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/acyclic"));
        assertNotEquals(RdfDatasetFingerprints.compute(without).rdfDatasetFingerprint(),
                with.rdfDatasetFingerprint());
    }

    @Test
    void sameAsLinkedAnonymousStructuresFailClosedWhenDropped() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory data = manager.getOWLDataFactory();
        OWLAnonymousIndividual x = data.getOWLAnonymousIndividual("x");
        OWLAnonymousIndividual y = data.getOWLAnonymousIndividual("y");
        OWLAnonymousIndividual z = data.getOWLAnonymousIndividual("z");

        // Consistent OWL 2 DL, yet the sameAs edge closes a renderer cycle the class-reference
        // graph alone cannot see; OWLAPI drops everything and the digest matched the empty
        // ontology before owl:sameAs was counted.
        OWLOntology mixed = manager.createOntology(IRI.create("https://example.org/same-mixed"));
        manager.addAxiom(mixed, data.getOWLSameIndividualAxiom(x, y));
        manager.addAxiom(mixed, data.getOWLClassAssertionAxiom(
                data.getOWLObjectComplementOf(data.getOWLObjectOneOf(z)), y));
        manager.addAxiom(mixed, data.getOWLClassAssertionAxiom(
                data.getOWLObjectComplementOf(data.getOWLObjectOneOf(x)), z));
        assertThrows(IllegalStateException.class, () -> RdfDatasetFingerprints.compute(mixed));

        // A bare anonymous sameAs pair renders (one chained triple) and keeps fingerprinting.
        OWLOntology pair = manager.createOntology(IRI.create("https://example.org/same-pair"));
        manager.addAxiom(pair, data.getOWLSameIndividualAxiom(x, y));
        assertTrue(RdfDatasetFingerprints.compute(pair)
                .rdfDatasetFingerprint().startsWith("sha256:"));

        // An n-ary DifferentIndividuals reifies from a fresh root and keeps fingerprinting.
        OWLOntology nary = manager.createOntology(IRI.create("https://example.org/diff-nary"));
        manager.addAxiom(nary, data.getOWLDifferentIndividualsAxiom(x, y, z));
        assertTrue(RdfDatasetFingerprints.compute(nary)
                .rdfDatasetFingerprint().startsWith("sha256:"));
    }

    @Test
    void treeShapedAnonymousReferencesStillFingerprint() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/tree"));
        OWLDataFactory data = manager.getOWLDataFactory();
        OWLAnonymousIndividual first = data.getOWLAnonymousIndividual("t1");
        OWLAnonymousIndividual second = data.getOWLAnonymousIndividual("t2");
        var property = data.getOWLObjectProperty(IRI.create("https://example.org/p"));
        manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(property,
                data.getOWLNamedIndividual(IRI.create("https://example.org/root")), first));
        manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(property,
                first, second));

        RdfDatasetFingerprint fingerprint = RdfDatasetFingerprints.compute(ontology);

        assertTrue(fingerprint.rdfDatasetFingerprint().startsWith("sha256:"));
    }

    private static OWLOntology ontologyWithAnonymousCycle(int size, boolean anchored)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(
                IRI.create("https://example.org/cycle-" + anchored));
        OWLDataFactory data = manager.getOWLDataFactory();
        var property = data.getOWLObjectProperty(IRI.create("https://example.org/p"));
        for (int i = 0; i < size; i++) {
            manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(property,
                    data.getOWLAnonymousIndividual("c" + i),
                    data.getOWLAnonymousIndividual("c" + ((i + 1) % size))));
        }
        if (anchored) {
            manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(property,
                    data.getOWLNamedIndividual(IRI.create("https://example.org/anchor")),
                    data.getOWLAnonymousIndividual("c0")));
        }
        return ontology;
    }

    private static String vector(String resource) throws Exception {
        try (InputStream in = RdfDatasetFingerprintsTest.class.getResourceAsStream(
                "/rdfc10/" + resource)) {
            assertNotNull(in, "missing vector " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static OWLOntology ontologyWithAnonymousIndividual(String id) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/ontology"));
        OWLDataFactory data = manager.getOWLDataFactory();
        OWLAnonymousIndividual individual = data.getOWLAnonymousIndividual(id);
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(
                data.getOWLClass(IRI.create("https://example.org/Person")), individual));
        return ontology;
    }
}
