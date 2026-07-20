package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Tests for the F3 CURIE-expansion fix in {@link EntityResolver}: a registered-prefix CURIE like
 * {@code bfo:BFO_0000031} must resolve to the imported term (via the active ontology's prefix map) instead
 * of being mis-read as an absolute IRI whose scheme happens to be {@code bfo} — which silently minted a junk
 * class whose IRI was the literal string "bfo:BFO_0000031".
 */
class EntityResolverCurieTest {

    private static final String OBO = "http://purl.obolibrary.org/obo/";
    private static final String GDC = OBO + "BFO_0000031";

    private OWLModelManager fixture() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/mod"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(GDC))));
        TurtleDocumentFormat fmt = new TurtleDocumentFormat();
        fmt.setPrefix("bfo:", OBO);
        fmt.setDefaultPrefix(OBO);
        m.setOntologyFormat(o, fmt);
        return FakeModelManager.over(o);
    }

    @Test
    void expandsARegisteredPrefixCurie() throws Exception {
        OWLModelManager mm = fixture();
        assertEquals(IRI.create(GDC), EntityResolver.expandCurie(mm, "bfo:BFO_0000031"));
        assertEquals(IRI.create(GDC), EntityResolver.iriFor(mm, "bfo:BFO_0000031"));
        assertEquals(IRI.create(GDC), EntityResolver.iriFor(mm, ":BFO_0000031"));
        assertEquals(IRI.create(OBO + "path/with~part"),
                EntityResolver.iriFor(mm, "bfo:path/with~part"));
        assertEquals(IRI.create(OBO + "표현"), EntityResolver.iriFor(mm, "bfo:표현"));
    }

    @Test
    void leavesRealIrisAndPlainNamesAlone() throws Exception {
        OWLModelManager mm = fixture();
        assertNull(EntityResolver.expandCurie(mm, "http://foo/Bar"), "scheme://… is an IRI, not a CURIE");
        assertNull(EntityResolver.expandCurie(mm, "PlainName"), "no ':' → not a CURIE");
        assertNull(EntityResolver.expandCurie(mm, "unknownpfx:Thing"), "unregistered prefix does not expand");
        // A real absolute IRI still parses through iriFor unchanged (falls through to asIri).
        assertEquals(IRI.create("http://foo/Bar"), EntityResolver.iriFor(mm, "http://foo/Bar"));
    }

    @Test
    void findEntityResolvesTheCurieToTheImportedTerm() throws Exception {
        OWLModelManager mm = fixture();
        assertEquals(GDC, EntityResolver.findEntity(mm, "bfo:BFO_0000031").getIRI().toString());
    }

    @Test
    void manchesterCompoundResolvesCuriesAndFragments() throws Exception {
        // GAP #5: a COMPOUND Manchester expression must resolve registered-prefix CURIEs and bare IRI
        // fragments, not only quoted labels / <full IRI>. Target the parser fallback directly (the
        // primary path needs a live Protégé checker, which FakeModelManager doesn't provide).
        String b = OBO + "BFO_0000040";
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/mod2"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(GDC))));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(b))));
        TurtleDocumentFormat fmt = new TurtleDocumentFormat();
        fmt.setPrefix("bfo:", OBO);
        m.setOntologyFormat(o, fmt);
        OWLModelManager mm = FakeModelManager.over(o);

        OWLClassExpression expected = df.getOWLObjectUnionOf(
                df.getOWLClass(IRI.create(GDC)), df.getOWLClass(IRI.create(b)));

        // Baseline: a compound of full <IRI>s must parse (the form CURIEs are pre-expanded to).
        OWLClassExpression viaIri = EntityResolver.tryManchesterClassExpression(mm,
                "<" + GDC + "> or <" + b + ">");
        assertNotNull(viaIri, "a compound of full <IRI>s parses");
        assertEquals(expected, viaIri);

        // And the CURIE pre-expansion itself yields exactly that <IRI> string.
        assertEquals("<" + GDC + "> or <" + b + ">",
                EntityResolver.expandCuriesForManchester(mm, "bfo:BFO_0000031 or bfo:BFO_0000040"),
                "registered CURIEs pre-expand to full <IRI>s");

        OWLClassExpression viaCurie =
                EntityResolver.tryManchesterClassExpression(mm, "bfo:BFO_0000031 or bfo:BFO_0000040");
        assertNotNull(viaCurie, "a compound of registered CURIEs now parses");
        assertEquals(expected, viaCurie);

        OWLClassExpression viaFragment =
                EntityResolver.tryManchesterClassExpression(mm, "BFO_0000031 or BFO_0000040");
        assertEquals(expected, viaFragment, "bare IRI fragments resolve to the same expression");

        assertNull(EntityResolver.tryManchesterClassExpression(mm, "nope:Thing or bfo:BFO_0000031"),
                "an unregistered prefix keeps the parse unresolved (no silent mint)");
    }

    @Test
    void annotationSubjectExpandsAnUndeclaredCurie() throws Exception {
        OWLModelManager mm = fixture();
        // A registered-prefix CURIE subject that is NOT yet declared must still expand via the prefix map
        // (P2b), not be taken as the literal IRI "bfo:BFO_9999999".
        assertEquals(IRI.create(OBO + "BFO_9999999"),
                AnnotationBuilder.annotationSubject(mm, "bfo:BFO_9999999"));
    }

    @Test
    void resolveClassReturnsTheExistingClassNotAMintedJunkEntity() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass resolved = EntityResolver.resolveClass(mm, "bfo:BFO_0000031");
        assertEquals(GDC, resolved.getIRI().toString(),
                "the CURIE must resolve to the real BFO IRI, not the literal string 'bfo:BFO_0000031'");
        // It resolves to the class already declared in the ontology, not a freshly minted junk entity.
        assertEquals(mm.getOWLDataFactory().getOWLClass(IRI.create(GDC)), resolved);
    }
}
