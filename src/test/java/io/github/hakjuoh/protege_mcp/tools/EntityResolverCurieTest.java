package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
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
        m.setOntologyFormat(o, fmt);
        return FakeModelManager.over(o);
    }

    @Test
    void expandsARegisteredPrefixCurie() throws Exception {
        OWLModelManager mm = fixture();
        assertEquals(IRI.create(GDC), EntityResolver.expandCurie(mm, "bfo:BFO_0000031"));
        assertEquals(IRI.create(GDC), EntityResolver.iriFor(mm, "bfo:BFO_0000031"));
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
