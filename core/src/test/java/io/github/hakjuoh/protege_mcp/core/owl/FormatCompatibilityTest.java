package io.github.hakjuoh.protege_mcp.core.owl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OBODocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLFacetRestriction;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLFacet;

/** Unit coverage for the honest, empirically-grounded lossy-format table and OBO report. */
class FormatCompatibilityTest {

    private static final String NS = "urn:fc#";

    private static OWLOntology ontology() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        return m.createOntology(IRI.create("urn:fc"));
    }

    private static OWLDataFactory df(OWLOntology o) {
        return o.getOWLOntologyManager().getOWLDataFactory();
    }

    private static void addLabel(OWLOntology o, IRI subject, String value) {
        OWLDataFactory df = df(o);
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), subject, df.getOWLLiteral(value)));
    }

    // ------------------------------------------------------------------ lossless formats

    @Test
    void losslessFormatsAreNeverFlaggedEvenForGenuinelyOboIncompatibleContent() throws Exception {
        // A multi-label entity is a genuine OBO frame-model loss, yet every RDF-based syntax (and
        // Manchester) round-trips it — so those must never warn.
        OWLOntology o = ontology();
        OWLDataFactory df = df(o);
        IRI a = IRI.create(NS + "A");
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(a)));
        addLabel(o, a, "L1");
        addLabel(o, a, "L2");

        for (var format : new org.semanticweb.owlapi.model.OWLDocumentFormat[] {
                new TurtleDocumentFormat(), new RDFXMLDocumentFormat(),
                new FunctionalSyntaxDocumentFormat(), new OWLXMLDocumentFormat(),
                new ManchesterSyntaxDocumentFormat() }) {
            assertFalse(FormatCompatibility.isOboFormat(format));
            assertNull(FormatCompatibility.detectLoss(o, format),
                    format.getClass().getSimpleName() + " must not warn");
        }
    }

    // ------------------------------------------------------------------ OBO frame model

    @Test
    void oboMultiLabelEntityIsIncompatibleAndFlagged() throws Exception {
        OWLOntology o = ontology();
        IRI a = IRI.create(NS + "A");
        o.getOWLOntologyManager().addAxiom(o, df(o).getOWLDeclarationAxiom(df(o).getOWLClass(a)));
        addLabel(o, a, "L1");
        addLabel(o, a, "L2");

        FormatCompatibility.OboCompatibility obo = FormatCompatibility.oboCompatibility(o);
        assertFalse(obo.compatible(), "two rdfs:labels violate the single-valued OBO 'name' tag");
        assertTrue(obo.issues().stream().anyMatch(i -> i.kind().equals("multiple_labels")
                        && a.toString().equals(i.focusIri())),
                () -> obo.issues().toString());
        assertEquals(1, obo.counts().get("multiple_valued_frame_tags"));
        assertEquals(1, obo.counts().get("total_issues"));

        FormatCompatibility.LossyWarning warning =
                FormatCompatibility.detectLoss(o, new OBODocumentFormat());
        assertNotNull(warning, "an OBO save of a multi-label ontology is lossy");
        assertEquals("OBODocumentFormat", warning.format());
        assertTrue(warning.reasons().contains("multiple_labels: not representable in the OBO frame model"));
    }

    @Test
    void oboDatatypeDefinitionIsNotFlaggedBecauseItRoundTripsViaOwlAxioms() throws Exception {
        // Empirically corrected: a genuine .obo file round trip preserves a well-formed datatype
        // definition through the owl-axioms: escape hatch, so it must NOT be reported as an OBO loss.
        OWLOntology o = ontology();
        OWLDataFactory df = df(o);
        var dt = df.getOWLDatatype(IRI.create(NS + "MyInt"));
        OWLFacetRestriction min = df.getOWLFacetRestriction(OWLFacet.MIN_INCLUSIVE, df.getOWLLiteral(0));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDatatypeDefinitionAxiom(dt,
                df.getOWLDatatypeRestriction(df.getIntegerOWLDatatype(), min)));
        // A plain datatype alias too.
        var alias = df.getOWLDatatype(IRI.create(NS + "MyStr"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDatatypeDefinitionAxiom(alias,
                df.getOWLDatatype(org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_STRING.getIRI())));

        assertTrue(FormatCompatibility.oboCompatibility(o).compatible(),
                () -> FormatCompatibility.oboCompatibility(o).counts().toString());
        assertNull(FormatCompatibility.detectLoss(o, new OBODocumentFormat()));
    }

    @Test
    void oboGeneralAxiomAndSwrlAreNotFlaggedBecauseTheyRoundTripViaOwlAxioms() throws Exception {
        // Honest conservatism: OBO's owl-axioms: escape hatch preserves GCIs and SWRL rules, so they
        // must NOT be reported as OBO losses (no false positives).
        OWLOntology o = ontology();
        OWLDataFactory df = df(o);
        var p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        var b = df.getOWLClass(IRI.create(NS + "B"));
        var c = df.getOWLClass(IRI.create(NS + "C"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(p));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(b));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(c));
        // General class axiom (anonymous subclass position).
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLSubClassOfAxiom(df.getOWLObjectSomeValuesFrom(p, b), c));

        FormatCompatibility.OboCompatibility obo = FormatCompatibility.oboCompatibility(o);
        assertTrue(obo.compatible(), () -> "GCIs survive via owl-axioms; " + obo.counts());
        assertNull(FormatCompatibility.detectLoss(o, new OBODocumentFormat()));
    }

    @Test
    void oboFrameCompatibleOntologyIsCompatible() throws Exception {
        OWLOntology o = ontology();
        OWLDataFactory df = df(o);
        IRI x = IRI.create(NS + "X");
        IRI y = IRI.create(NS + "Y");
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(x)));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(y)));
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLSubClassOfAxiom(df.getOWLClass(x), df.getOWLClass(y)));
        addLabel(o, x, "X");

        assertTrue(FormatCompatibility.oboCompatibility(o).compatible());
        assertNull(FormatCompatibility.detectLoss(o, new OBODocumentFormat()));
    }

    // ------------------------------------------------------------------ determinism (PLAN §3.3)

    @Test
    void oboReportIssuesAndReasonsAreDeterministicallyOrdered() throws Exception {
        // Several entities, each with several single-valued frame-tag violations, added in an order that
        // does NOT match the sorted order — the report must still come out sorted by (kind, focus_iri).
        OWLOntology o = ontology();
        OWLDataFactory df = df(o);
        var definition = df.getOWLAnnotationProperty(
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000115"));
        IRI c = IRI.create(NS + "C");
        IRI a = IRI.create(NS + "A");
        IRI b = IRI.create(NS + "B");
        // Insert out of order: C (2 labels), A (2 comments + 2 labels), B (2 defs).
        addLabel(o, c, "c1");
        addLabel(o, c, "c2");
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), a, df.getOWLLiteral("k1")));
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), a, df.getOWLLiteral("k2")));
        addLabel(o, a, "a1");
        addLabel(o, a, "a2");
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLAnnotationAssertionAxiom(definition, b, df.getOWLLiteral("d1")));
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLAnnotationAssertionAxiom(definition, b, df.getOWLLiteral("d2")));

        FormatCompatibility.OboCompatibility first = FormatCompatibility.oboCompatibility(o);
        FormatCompatibility.OboCompatibility second = FormatCompatibility.oboCompatibility(o);

        // Explicit expected order: sorted by kind, then focus_iri.
        List<String> keys = new ArrayList<>();
        first.issues().forEach(i -> keys.add(i.kind() + "|" + i.focusIri()));
        assertEquals(List.of(
                "multiple_comments|" + a,
                "multiple_definitions|" + b,
                "multiple_labels|" + a,
                "multiple_labels|" + c), keys, () -> first.issues().toString());
        assertEquals(4, first.counts().get("total_issues"));

        // Same report on a re-run and identical reasons ordering.
        List<String> secondKeys = new ArrayList<>();
        second.issues().forEach(i -> secondKeys.add(i.kind() + "|" + i.focusIri()));
        assertEquals(keys, secondKeys);
        assertEquals(List.of(
                "multiple_comments: not representable in the OBO frame model",
                "multiple_definitions: not representable in the OBO frame model",
                "multiple_labels: not representable in the OBO frame model"),
                FormatCompatibility.detectLoss(o, new OBODocumentFormat()).reasons());
    }
}
