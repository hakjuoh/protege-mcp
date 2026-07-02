package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** Method-level tests for {@link SidecarPaths} — the shared sidecar-file path resolver. */
class SidecarPathsTest {

    private static final String CQS = "onto-cqs.json";

    private OWLOntology onto(OWLOntologyManager m) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/a"));
    }

    // ------------------------------------------------------------------ toFile

    @Test
    void toFileNullIsNull() {
        assertNull(SidecarPaths.toFile(null));
    }

    @Test
    void toFileHttpIriIsNull() {
        assertNull(SidecarPaths.toFile(IRI.create("http://example.org/a.owl")),
                "a non-file document IRI has no local File");
    }

    @Test
    void toFileFileIriRoundTrips(@TempDir File dir) {
        File f = new File(dir, "a.owl");
        assertEquals(f, SidecarPaths.toFile(IRI.create(f.toURI())));
    }

    // ------------------------------------------------------------------ documentFolder

    @Test
    void documentFolderNullWhenNeverSaved() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = onto(m);
        assertNull(SidecarPaths.documentFolder(m, o),
                "a freshly created ontology has no file: document, so no folder");
    }

    @Test
    void documentFolderIsTheDocumentsParent(@TempDir File dir) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = onto(m);
        m.setOntologyDocumentIRI(o, IRI.create(new File(dir, "a.owl").toURI()));
        assertEquals(dir, SidecarPaths.documentFolder(m, o));
    }

    // ------------------------------------------------------------------ resolveSidecarFile

    @Test
    void resolveSidecarFileNullPathUsesDocumentFolder(@TempDir File dir) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = onto(m);
        m.setOntologyDocumentIRI(o, IRI.create(new File(dir, "a.owl").toURI()));
        assertEquals(new File(dir, CQS), SidecarPaths.resolveSidecarFile(m, o, null, CQS));
    }

    @Test
    void resolveSidecarFileDirectoryPathAppendsName(@TempDir File dir) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = onto(m);
        assertEquals(new File(dir, CQS), SidecarPaths.resolveSidecarFile(m, o, dir.getPath(), CQS));
    }

    @Test
    void resolveSidecarFileExactNameReturnedAsIs(@TempDir File dir) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = onto(m);
        File explicit = new File(dir, CQS);
        assertEquals(explicit.getAbsoluteFile(),
                SidecarPaths.resolveSidecarFile(m, o, explicit.getPath(), CQS));
    }

    @Test
    void resolveSidecarFileNoDocumentNoPathThrows() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = onto(m);
        assertThrows(ToolArgException.class, () -> SidecarPaths.resolveSidecarFile(m, o, null, CQS));
    }

    // ------------------------------------------------------------------ sanitizeId

    @Test
    void sanitizeIdKeepsSafeCharacters() {
        assertEquals("CQ-3_a", SidecarPaths.sanitizeId("CQ-3_a"));
    }

    @Test
    void sanitizeIdReplacesPathAndDotCharacters() {
        assertEquals("a_b_c", SidecarPaths.sanitizeId("a/b.c"));
        assertEquals("etc_passwd", SidecarPaths.sanitizeId("../etc/passwd"));
    }

    @Test
    void sanitizeIdCollapsesRunsAndTrimsUnderscores() {
        assertEquals("x", SidecarPaths.sanitizeId("  x  "));
    }

    @Test
    void sanitizeIdBlankFallsBackToCq() {
        assertEquals("cq", SidecarPaths.sanitizeId("///"));
        assertEquals("cq", SidecarPaths.sanitizeId(""));
        assertEquals("cq", SidecarPaths.sanitizeId(null));
    }

    // ------------------------------------------------------------------ isWithin

    @Test
    void isWithinTrueForChild(@TempDir File dir) {
        assertTrue(SidecarPaths.isWithin(dir, new File(dir, "cqs/CQ-1.rq")));
    }

    @Test
    void isWithinFalseForTraversal(@TempDir File dir) {
        assertFalse(SidecarPaths.isWithin(new File(dir, "cqs"), new File(dir, "../secrets")));
    }
}
