package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.IRIDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Supplementary coverage for {@link OntologyDocumentTools} pure/package-private helpers that the
 * primary {@link OntologyDocumentToolsTest} does not exercise: the full {@code normalizeSource}
 * GitHub-blob rewrite matrix, {@code documentSource} type dispatch and its two error branches,
 * {@code localFile} edge cases, and {@code addFolderCatalogMapper} no-op / robustness paths.
 *
 * <p>The runtime-only, {@code OWLModelManager}-driven entry points (specs/doLoad/createOntology/
 * attach/apply and the private fetch/load network I/O) are intentionally out of scope — they need a
 * live Protégé workspace and real remote fetches, which are non-deterministic headless.
 */
class OntologyDocumentToolsCoverageTest {

    // ------------------------------------------------------------------ normalizeSource

    @Test
    void normalizeSourceTrimsLeadingAndTrailingWhitespace() {
        assertEquals("/tmp/x.rdf", OntologyDocumentTools.normalizeSource("  /tmp/x.rdf \t\n"),
                "surrounding whitespace should be stripped");
    }

    @Test
    void normalizeSourceLeavesPlainPathUnchanged() {
        assertEquals("/tmp/module.owl", OntologyDocumentTools.normalizeSource("/tmp/module.owl"),
                "a plain path is returned as-is once trimmed");
    }

    @Test
    void normalizeSourceRewritesGithubBlobUrlToRaw() {
        String in = "https://github.com/user/repo/blob/main/dir/file.owl";
        assertEquals("https://raw.githubusercontent.com/user/repo/main/dir/file.owl",
                OntologyDocumentTools.normalizeSource(in),
                "a GitHub blob URL should become a raw.githubusercontent.com URL");
    }

    @Test
    void normalizeSourceRewritesGithubBlobUrlWithNestedPath() {
        String in = "https://github.com/org/proj/blob/feature/branch-name/a/b/c.rdf";
        // branchAndPath splits on the FIRST slash after /blob/, so the branch is only "feature"
        // and everything after is treated as the path.
        assertEquals("https://raw.githubusercontent.com/org/proj/feature/branch-name/a/b/c.rdf",
                OntologyDocumentTools.normalizeSource(in),
                "only the first path segment after /blob/ is the branch");
    }

    @Test
    void normalizeSourceTrimsThenRewritesGithubBlobUrl() {
        String in = "   https://github.com/user/repo/blob/main/file.owl  ";
        assertEquals("https://raw.githubusercontent.com/user/repo/main/file.owl",
                OntologyDocumentTools.normalizeSource(in),
                "the URL is trimmed before the blob rewrite is attempted");
    }

    @Test
    void normalizeSourceLeavesGithubUrlWithoutBlobUnchanged() {
        String in = "https://github.com/user/repo/tree/main/file.owl";
        assertEquals(in, OntologyDocumentTools.normalizeSource(in),
                "a GitHub URL without /blob/ is not rewritten");
    }

    @Test
    void normalizeSourceLeavesGithubUrlWithMissingRepoSegmentUnchanged() {
        // repoPart="user" -> split yields length 1, so no rewrite.
        String in = "https://github.com/user/blob/main/file.owl";
        assertEquals(in, OntologyDocumentTools.normalizeSource(in),
                "a GitHub blob URL lacking a repo segment is returned unchanged");
    }

    @Test
    void normalizeSourceLeavesGithubBlobUrlWithNoPathSegmentUnchanged() {
        // blobPart="main" -> split yields length 1 (no path after branch), so no rewrite.
        String in = "https://github.com/user/repo/blob/main";
        assertEquals(in, OntologyDocumentTools.normalizeSource(in),
                "a GitHub blob URL with only a branch (no file path) is returned unchanged");
    }

    @Test
    void normalizeSourceRequiresBlobAfterPrefix() {
        // "/blob/" appears but blob index must be strictly > prefix length; craft a URL where
        // the only /blob/ occurrence would sit at/before the prefix boundary is impossible, so
        // verify a non-github host with /blob/ is untouched.
        String in = "https://example.com/user/repo/blob/main/file.owl";
        assertEquals(in, OntologyDocumentTools.normalizeSource(in),
                "a non-github host is never rewritten even with a /blob/ segment");
    }

    @Test
    void normalizeSourceLeavesFileUriUnchanged() {
        String in = "file:/tmp/module.owl";
        assertEquals(in, OntologyDocumentTools.normalizeSource(in),
                "a file: URI is not a GitHub URL and passes through");
    }

    @Test
    void normalizeSourceLeavesWindowsPathUnchanged() {
        String in = "C:/ontologies/module.owl";
        assertEquals(in, OntologyDocumentTools.normalizeSource(in),
                "a Windows drive path passes through untouched");
    }

    // ------------------------------------------------------------------ documentSource

    @Test
    void documentSourceReturnsFileSourceForExistingPlainPath(@TempDir Path dir) throws Exception {
        File f = dir.resolve("doc.rdf").toFile();
        Files.write(f.toPath(), "<x/>".getBytes());
        OWLOntologyDocumentSource src = OntologyDocumentTools.documentSource(f.getPath());
        assertInstanceOf(FileDocumentSource.class, src,
                "an existing local path yields a FileDocumentSource");
    }

    @Test
    void documentSourceReturnsFileSourceForExistingFileUri(@TempDir Path dir) throws Exception {
        File f = dir.resolve("doc.rdf").toFile();
        Files.write(f.toPath(), "<x/>".getBytes());
        OWLOntologyDocumentSource src = OntologyDocumentTools.documentSource(f.toURI().toString());
        assertInstanceOf(FileDocumentSource.class, src,
                "an existing file: URI yields a FileDocumentSource");
    }

    @Test
    void documentSourceReturnsIriSourceForHttpIri() {
        OWLOntologyDocumentSource src =
                OntologyDocumentTools.documentSource("http://example.org/onto.owl");
        assertInstanceOf(IRIDocumentSource.class, src,
                "an absolute http IRI that is not a local file yields an IRIDocumentSource");
    }

    @Test
    void documentSourceThrowsForNonExistingLocalPath(@TempDir Path dir) {
        String missing = dir.resolve("nope.rdf").toString();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> OntologyDocumentTools.documentSource(missing),
                "a non-existent path that is not an absolute IRI must throw");
        assertTrue(ex.getMessage().contains("not a readable file"),
                "message should explain the file is not readable: " + ex.getMessage());
    }

    @Test
    void documentSourceThrowsForRelativeNonIriString() {
        // "not-an-iri" is relative (asIri returns null) and no such file exists in the cwd.
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> OntologyDocumentTools.documentSource("this-file-should-not-exist-xyz"),
                "a relative, non-IRI, non-file string must throw");
        assertTrue(ex.getMessage().contains("not an absolute IRI"),
                "message should mention absolute IRI: " + ex.getMessage());
    }

    @Test
    void documentSourceTreatsWindowsDrivePathAsFileNotIri() {
        // A bare Windows drive path is short-circuited from IRI parsing; since the file does not
        // exist on this (non-Windows) test host, it falls through to the error branch rather than
        // being (wrongly) parsed as an IRI with scheme "c".
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> OntologyDocumentTools.documentSource("C:/definitely/missing/module.owl"),
                "a Windows drive path that is not a real file must throw, not be treated as an IRI");
        assertNotNull(ex.getMessage(), "error message present");
    }

    // ------------------------------------------------------------------ localFile

    @Test
    void localFileReturnsNullForNull() {
        assertNull(OntologyDocumentTools.localFile(null), "null source is not a local file");
    }

    @Test
    void localFileReturnsNullForHttpIri() {
        assertNull(OntologyDocumentTools.localFile("https://example.org/x.owl"),
                "an http IRI is not a local file");
    }

    @Test
    void localFileReturnsNullForNonExistentFileUri(@TempDir Path dir) {
        String uri = dir.resolve("gone.rdf").toUri().toString();
        assertNull(OntologyDocumentTools.localFile(uri),
                "a file: URI pointing at a missing file returns null");
    }

    @Test
    void localFileReturnsNullForNonExistentPlainPath(@TempDir Path dir) {
        assertNull(OntologyDocumentTools.localFile(dir.resolve("missing.rdf").toString()),
                "a plain path to a missing file returns null");
    }

    @Test
    void localFileReturnsNullForMalformedUri() {
        // Not a valid URI and no such file — the URISyntaxException is swallowed and null returned.
        assertNull(OntologyDocumentTools.localFile("ht tp://bad uri with spaces"),
                "a malformed URI that is not a file returns null");
    }

    @Test
    void localFileResolvesPlainPathToExistingFile(@TempDir Path dir) throws Exception {
        File f = dir.resolve("present.rdf").toFile();
        Files.write(f.toPath(), "<x/>".getBytes());
        File resolved = OntologyDocumentTools.localFile(f.getPath());
        assertNotNull(resolved, "existing plain path resolves");
        assertEquals(f.getCanonicalFile(), resolved.getCanonicalFile(), "same underlying file");
    }

    // ------------------------------------------------------------------ addFolderCatalogMapper

    @Test
    void addFolderCatalogMapperIsNoOpForNullSource() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        int before = m.getIRIMappers().size();
        OntologyDocumentTools.addFolderCatalogMapper(m, null);
        assertEquals(before, m.getIRIMappers().size(),
                "a null source must not register any IRI mapper");
    }

    @Test
    void addFolderCatalogMapperIsNoOpForHttpSource() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        int before = m.getIRIMappers().size();
        OntologyDocumentTools.addFolderCatalogMapper(m, "http://example.org/onto.owl");
        assertEquals(before, m.getIRIMappers().size(),
                "a non-file (http) source must not register any IRI mapper");
    }

    @Test
    void addFolderCatalogMapperIsNoOpWhenNoSiblingCatalog(@TempDir Path dir) throws Exception {
        File f = dir.resolve("module.rdf").toFile();
        Files.write(f.toPath(), "<x/>".getBytes());
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        int before = m.getIRIMappers().size();
        OntologyDocumentTools.addFolderCatalogMapper(m, f.getPath());
        assertEquals(before, m.getIRIMappers().size(),
                "no catalog-v001.xml sibling means no mapper is added");
    }

    @Test
    void addFolderCatalogMapperTolAtesMalformedCatalogWithoutThrowing(@TempDir Path dir)
            throws Exception {
        File f = dir.resolve("module.rdf").toFile();
        Files.write(f.toPath(), "<x/>".getBytes());
        // A garbage catalog file must be caught and ignored, never fatal.
        Files.write(dir.resolve("catalog-v001.xml"), "this is not valid xml <<< &&&".getBytes());
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        // Should not throw regardless of whether a mapper ends up registered.
        OntologyDocumentTools.addFolderCatalogMapper(m, f.getPath());
        assertTrue(true, "a malformed catalog must not propagate an exception");
    }

    @Test
    void addFolderCatalogMapperRegistersMapperForValidSiblingCatalog(@TempDir Path dir)
            throws Exception {
        File module = dir.resolve("module.rdf").toFile();
        Files.write(module.toPath(), "<x/>".getBytes());
        Files.write(dir.resolve("catalog-v001.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                        + "<catalog prefer=\"public\" "
                        + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                        + "  <uri id=\"x\" name=\"http://example.org/odt/Imported\" "
                        + "uri=\"imported.rdf\"/>\n</catalog>\n").getBytes());
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        int before = m.getIRIMappers().size();
        OntologyDocumentTools.addFolderCatalogMapper(m, module.getPath());
        assertTrue(m.getIRIMappers().size() > before,
                "a valid sibling catalog should register an XMLCatalogIRIMapper");
    }

    @Test
    void addFolderCatalogMapperHonoursFileUriSource(@TempDir Path dir) throws Exception {
        File module = dir.resolve("module.rdf").toFile();
        Files.write(module.toPath(), "<x/>".getBytes());
        Files.write(dir.resolve("catalog-v001.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                        + "<catalog prefer=\"public\" "
                        + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                        + "  <uri id=\"x\" name=\"http://example.org/odt/Imported\" "
                        + "uri=\"imported.rdf\"/>\n</catalog>\n").getBytes());
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        int before = m.getIRIMappers().size();
        OntologyDocumentTools.addFolderCatalogMapper(m, module.toURI().toString());
        assertTrue(m.getIRIMappers().size() > before,
                "a file: URI source with a sibling catalog also registers a mapper");
    }

    @Test
    void documentSourceAndNormalizeComposeForGithubRawFallback() {
        // A GitHub blob URL, once normalized, is not a local file, so documentSource must yield an
        // IRIDocumentSource over the raw URL (composition guard, no network access performed).
        String raw = OntologyDocumentTools.normalizeSource(
                "https://github.com/user/repo/blob/main/file.owl");
        OWLOntologyDocumentSource src = OntologyDocumentTools.documentSource(raw);
        assertInstanceOf(IRIDocumentSource.class, src,
                "the normalized raw GitHub URL is fetched as an IRI document source");
        assertFalse(raw.contains("/blob/"), "the /blob/ segment was rewritten away");
    }
}
