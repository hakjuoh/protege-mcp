package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.xmlcatalog.XMLCatalog;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Method-level tests for the pure/IO helper cores of {@link CatalogTools}. The {@code specs(...)}
 * wrapper needs the live Protégé {@code ToolContext}/{@code OWLModelManager} write-guard plumbing and
 * is exercised only indirectly here; every private static helper is reached via reflection and driven
 * over real in-memory OWLAPI ontologies (plus JUnit {@link TempDir} folders for the filesystem legs).
 */
class CatalogToolsTest {

    // ------------------------------------------------------------------ reflection plumbing

    private static Method priv(String name, Class<?>... params) throws Exception {
        Method m = CatalogTools.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static CallToolResult writeCatalog(OWLModelManager mm, String path, boolean directOnly)
            throws Throwable {
        try {
            return (CallToolResult) priv("writeCatalog", OWLModelManager.class, String.class,
                    boolean.class).invoke(null, mm, path, directOnly);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static File resolveCatalogFile(OWLOntologyManager om, OWLOntology active, String path)
            throws Throwable {
        try {
            return (File) priv("resolveCatalogFile", OWLOntologyManager.class, OWLOntology.class,
                    String.class).invoke(null, om, active, path);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static File toFile(IRI iri) throws Exception {
        return (File) priv("toFile", IRI.class).invoke(null, iri);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> skip(String importIri, String reason) throws Exception {
        return (Map<String, Object>) priv("skip", String.class, String.class)
                .invoke(null, importIri, reason);
    }

    @SuppressWarnings("unchecked")
    private static void addEntry(XMLCatalog catalog, List<Map<String, Object>> entries,
            Set<String> seenNames, String name, URI uri) throws Exception {
        priv("addEntry", XMLCatalog.class, List.class, Set.class, String.class, URI.class)
                .invoke(null, catalog, entries, seenNames, name, uri);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    // ------------------------------------------------------------------ ontology fixtures

    private static IRI file(File f) {
        return IRI.create(f.toURI());
    }

    /**
     * Build an active ontology that imports {@code importIri}, with the imported ontology loaded into
     * the same manager and its document IRI pointing at {@code docFile}. Returns a fake model manager
     * over the active ontology (its {@code getOWLOntologyManager} delegates to the real manager, so the
     * import/document lookups in writeCatalog resolve).
     */
    private static OWLModelManager managerWithImport(File activeDoc, String importIri, File docFile,
            String versionIri) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();

        OWLOntology imported;
        if (versionIri != null) {
            imported = m.createOntology(new OWLOntologyID(IRI.create(importIri),
                    IRI.create(versionIri)));
        } else {
            imported = m.createOntology(IRI.create(importIri));
        }
        if (docFile != null) {
            m.setOntologyDocumentIRI(imported, file(docFile));
        }

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        if (activeDoc != null) {
            m.setOntologyDocumentIRI(active, file(activeDoc));
        }
        OWLImportsDeclaration decl = df.getOWLImportsDeclaration(IRI.create(importIri));
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active, decl));

        return FakeModelManager.over(active);
    }

    /** A file that is guaranteed to exist (a written-out local document). */
    private static File writeStub(File dir, String name) throws Exception {
        File f = new File(dir, name);
        Files.write(f.toPath(), "<x/>".getBytes(StandardCharsets.UTF_8));
        return f;
    }

    // ============================================================= writeCatalog: happy paths

    @Test
    void writeCatalogHappyPathWritesFileAndEntry(@TempDir File dir) throws Throwable {
        File imported = writeStub(dir, "imported.owl");
        File activeDoc = new File(dir, "active.owl");
        OWLModelManager mm = managerWithImport(activeDoc, "http://ex.org/imp", imported, null);

        CallToolResult r = writeCatalog(mm, null, false);

        assertFalse(r.isError(), "a resolvable, file-backed import writes a catalog without error");
        Map<String, Object> body = structured(r);
        File catalog = new File(dir, "catalog-v001.xml");
        assertEquals(catalog.toString(), body.get("catalog"), "catalog written next to active doc");
        assertTrue(catalog.isFile(), "catalog file is actually created on disk");
        List<?> entries = (List<?>) body.get("entries");
        assertEquals(1, entries.size(), "one import declaration → one catalog entry");
        assertEquals(1, body.get("entry_count"), "entry_count matches entries list size");
        assertTrue(((List<?>) body.get("skipped")).isEmpty(), "nothing skipped");
    }

    @Test
    void writeCatalogEntryNameIsDeclarationIri(@TempDir File dir) throws Throwable {
        File imported = writeStub(dir, "imported.owl");
        OWLModelManager mm = managerWithImport(new File(dir, "active.owl"),
                "http://ex.org/decl", imported, null);

        Map<String, Object> body = structured(writeCatalog(mm, null, false));
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) ((List<?>) body.get("entries")).get(0);
        assertEquals("http://ex.org/decl", entry.get("name"),
                "the mapped name is the owl:imports declaration IRI");
        assertNotNull(entry.get("uri"), "entry carries a (relative) uri");
    }

    @Test
    void writeCatalogAddsOntologyAndVersionIriEntries(@TempDir File dir) throws Throwable {
        // Declaration IRI == ontology IRI, plus a distinct version IRI → declIri collapses with
        // ontologyIRI (dedup), version IRI adds a second entry.
        File imported = writeStub(dir, "imported.owl");
        OWLModelManager mm = managerWithImport(new File(dir, "active.owl"),
                "http://ex.org/onto", imported, "http://ex.org/onto/v2");

        Map<String, Object> body = structured(writeCatalog(mm, null, false));
        List<?> entries = (List<?>) body.get("entries");
        Set<String> names = new LinkedHashSet<>();
        for (Object e : entries) {
            names.add((String) ((Map<?, ?>) e).get("name"));
        }
        assertTrue(names.contains("http://ex.org/onto"), "ontology IRI mapped");
        assertTrue(names.contains("http://ex.org/onto/v2"), "version IRI mapped");
        assertEquals(2, entries.size(),
                "declaration IRI dedups with the equal ontology IRI; version IRI adds one more");
    }

    @Test
    void writeCatalogDeduplicatesDeclarationAndOntologyIri(@TempDir File dir) throws Throwable {
        // decl IRI equals ontology IRI, no version → exactly one entry (deduped by seenNames).
        File imported = writeStub(dir, "imported.owl");
        OWLModelManager mm = managerWithImport(new File(dir, "active.owl"),
                "http://ex.org/same", imported, null);

        Map<String, Object> body = structured(writeCatalog(mm, null, false));
        assertEquals(1, ((List<?>) body.get("entries")).size(),
                "identical declaration and ontology IRI produce a single entry");
    }

    @Test
    void writeCatalogDirectOnlyStillMapsDirectImport(@TempDir File dir) throws Throwable {
        File imported = writeStub(dir, "imported.owl");
        OWLModelManager mm = managerWithImport(new File(dir, "active.owl"),
                "http://ex.org/imp", imported, null);

        Map<String, Object> body = structured(writeCatalog(mm, null, true));
        assertEquals(1, ((List<?>) body.get("entries")).size(),
                "direct_imports_only maps the directly-imported ontology");
    }

    @Test
    void writeCatalogEmptyImportsWritesEmptyCatalog(@TempDir File dir) throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/lonely"));
        File activeDoc = new File(dir, "active.owl");
        m.setOntologyDocumentIRI(active, file(activeDoc));
        OWLModelManager mm = FakeModelManager.over(active);

        CallToolResult r = writeCatalog(mm, null, false);
        assertFalse(r.isError(), "an ontology with no imports still writes a valid (empty) catalog");
        Map<String, Object> body = structured(r);
        assertTrue(((List<?>) body.get("entries")).isEmpty(), "no imports → no entries");
        assertEquals(0, body.get("entry_count"), "entry_count is zero");
        assertTrue(new File(dir, "catalog-v001.xml").isFile(), "empty catalog is still written");
    }

    // ============================================================= writeCatalog: skip branches

    @Test
    void writeCatalogSkipsUnresolvedImport(@TempDir File dir) throws Throwable {
        // Declaration whose target ontology is not loaded → getImportedOntology returns null.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        m.setOntologyDocumentIRI(active, file(new File(dir, "active.owl")));
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(IRI.create("http://ex.org/missing"))));
        OWLModelManager mm = FakeModelManager.over(active);

        Map<String, Object> body = structured(writeCatalog(mm, null, false));
        assertTrue(((List<?>) body.get("entries")).isEmpty(), "unresolved import produces no entry");
        List<?> skipped = (List<?>) body.get("skipped");
        assertEquals(1, skipped.size(), "the unresolved import is reported as skipped");
        Map<?, ?> s = (Map<?, ?>) skipped.get(0);
        assertEquals("http://ex.org/missing", s.get("import"), "skip records the import IRI");
        assertTrue(((String) s.get("reason")).contains("unresolved"), "reason mentions unresolved");
    }

    @Test
    void writeCatalogSkipsImportWithNonFileDocument(@TempDir File dir) throws Throwable {
        // Imported ontology loaded, but its document IRI is an http URL (toFile → null).
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology imported = m.createOntology(IRI.create("http://ex.org/http-doc"));
        m.setOntologyDocumentIRI(imported, IRI.create("http://ex.org/remote.owl"));
        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        m.setOntologyDocumentIRI(active, file(new File(dir, "active.owl")));
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(IRI.create("http://ex.org/http-doc"))));
        OWLModelManager mm = FakeModelManager.over(active);

        Map<String, Object> body = structured(writeCatalog(mm, null, false));
        assertTrue(((List<?>) body.get("entries")).isEmpty(), "non-file document produces no entry");
        List<?> skipped = (List<?>) body.get("skipped");
        assertEquals(1, skipped.size(), "an import with no local file is skipped");
        assertTrue(((String) ((Map<?, ?>) skipped.get(0)).get("reason")).contains("no local file"),
                "reason mentions the missing local file document");
    }

    @Test
    void writeCatalogSkipsImportWhoseFileDoesNotExist(@TempDir File dir) throws Throwable {
        // Document IRI is a file URI but the file does not exist on disk (isFile() false).
        File ghost = new File(dir, "does-not-exist.owl");
        OWLModelManager mm = managerWithImport(new File(dir, "active.owl"),
                "http://ex.org/ghost", ghost, null);

        Map<String, Object> body = structured(writeCatalog(mm, null, false));
        assertTrue(((List<?>) body.get("entries")).isEmpty(),
                "a document file that is not present on disk yields no entry");
        assertEquals(1, ((List<?>) body.get("skipped")).size(),
                "the missing-file import is reported as skipped");
    }

    // ============================================================= writeCatalog: path/folder errors

    @Test
    void writeCatalogUsesExplicitFolderPath(@TempDir File dir) throws Throwable {
        File imported = writeStub(dir, "imported.owl");
        File target = new File(dir, "out");
        assertTrue(target.mkdir(), "precondition: target folder exists");
        OWLModelManager mm = managerWithImport(null, "http://ex.org/imp", imported, null);

        Map<String, Object> body = structured(writeCatalog(mm, target.getPath() + File.separator, false));
        assertEquals(new File(target, "catalog-v001.xml").toString(), body.get("catalog"),
                "a trailing-separator path is treated as a target folder");
    }

    @Test
    void writeCatalogCreatesMissingFolder(@TempDir File dir) throws Throwable {
        File imported = writeStub(dir, "imported.owl");
        File target = new File(dir, "fresh");   // does not exist yet
        OWLModelManager mm = managerWithImport(null, "http://ex.org/imp", imported, null);

        CallToolResult r = writeCatalog(mm, new File(target, "catalog-v001.xml").getPath(), false);
        assertFalse(r.isError(), "a missing catalog folder is created via mkdirs");
        assertTrue(target.isDirectory(), "the folder was created");
    }

    @Test
    void writeCatalogErrorsWhenFolderCannotBeCreated(@TempDir File dir) throws Throwable {
        File imported = writeStub(dir, "imported.owl");
        // A regular file blocks a same-named directory being created underneath it.
        File blocker = writeStub(dir, "blocker");
        File target = new File(blocker, "sub");   // parent is a file, mkdirs must fail
        OWLModelManager mm = managerWithImport(null, "http://ex.org/imp", imported, null);

        CallToolResult r = writeCatalog(mm, new File(target, "catalog-v001.xml").getPath(), false);
        assertTrue(r.isError(), "an uncreatable catalog folder is an error result");
        assertTrue(((String) structured(r).get("error")).contains("could not be created"),
                "the error explains the folder could not be created");
    }

    @Test
    void writeCatalogNoSavedDocumentAndNullPathIsError(@TempDir File dir) throws Throwable {
        // Active ontology with an import but NO document IRI, and path=null → ToolArgException,
        // caught nowhere in writeCatalog itself (it propagates out of resolveCatalogFile).
        File imported = writeStub(dir, "imported.owl");
        OWLModelManager mm = managerWithImport(null, "http://ex.org/imp", imported, null);

        assertThrows(ToolArgException.class, () -> writeCatalog(mm, null, false),
                "no saved document and no path leaves nowhere to write the catalog");
    }

    // ============================================================= resolveCatalogFile

    @Test
    void resolveCatalogFileNullPathUsesActiveDocumentFolder(@TempDir File dir) throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/a"));
        File doc = new File(dir, "a.owl");
        m.setOntologyDocumentIRI(active, file(doc));

        File resolved = resolveCatalogFile(m, active, null);
        assertEquals(new File(dir, "catalog-v001.xml"), resolved,
                "null path resolves to catalog-v001.xml next to the active document");
    }

    @Test
    void resolveCatalogFileDirectoryPathAppendsCatalogName(@TempDir File dir) throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/a"));

        File resolved = resolveCatalogFile(m, active, dir.getPath());
        assertEquals(new File(dir, "catalog-v001.xml"), resolved,
                "an existing-directory path gets catalog-v001.xml appended");
    }

    @Test
    void resolveCatalogFileTrailingSlashTreatedAsDirectory(@TempDir File dir) throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/a"));
        File notYet = new File(dir, "later");   // does not exist yet, but ends with separator

        File resolved = resolveCatalogFile(m, active, notYet.getPath() + File.separator);
        assertEquals(new File(notYet, "catalog-v001.xml"), resolved,
                "a trailing-separator path is treated as a folder even if it does not exist yet");
    }

    @Test
    void resolveCatalogFileCatalogFileNameReturnedAsIs(@TempDir File dir) throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/a"));
        File explicit = new File(dir, "catalog-v001.xml");

        File resolved = resolveCatalogFile(m, active, explicit.getPath());
        assertEquals(explicit.getAbsoluteFile(), resolved,
                "a path naming catalog-v001.xml is returned unchanged (absolute)");
    }

    @Test
    void resolveCatalogFileCatalogNameCaseInsensitive(@TempDir File dir) throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/a"));
        File explicit = new File(dir, "CATALOG-V001.XML");

        File resolved = resolveCatalogFile(m, active, explicit.getPath());
        assertEquals(explicit.getAbsoluteFile(), resolved,
                "the catalog file name match is case-insensitive");
    }

    @Test
    void resolveCatalogFileOtherFileNameAppendsCatalogNameToIt(@TempDir File dir) throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/a"));
        File other = new File(dir, "something.txt");   // not a directory, not the catalog name

        File resolved = resolveCatalogFile(m, active, other.getPath());
        assertEquals(new File(other.getAbsoluteFile(), "catalog-v001.xml"), resolved,
                "a non-catalog file name is treated as a folder and gets catalog-v001.xml appended");
    }

    @Test
    void resolveCatalogFileNoDocumentNoPathThrows() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/nodoc"));
        // Default document IRI for a freshly created ontology is not a file: URI → toFile null.

        assertThrows(ToolArgException.class, () -> resolveCatalogFile(m, active, null),
                "no saved file document and no path is a ToolArgException");
    }

    // ============================================================= toFile

    @Test
    void toFileNullIriReturnsNull() throws Exception {
        assertNull(toFile(null), "a null IRI maps to a null file");
    }

    @Test
    void toFileFileSchemeConverts(@TempDir File dir) throws Exception {
        File f = new File(dir, "doc.owl");
        File out = toFile(IRI.create(f.toURI()));
        assertNotNull(out, "a file: IRI converts to a File");
        assertEquals(f.getAbsoluteFile(), out.getAbsoluteFile(), "the converted file matches the source");
    }

    @Test
    void toFileHttpSchemeReturnsNull() throws Exception {
        assertNull(toFile(IRI.create("http://ex.org/remote.owl")), "an http IRI is not a local file");
    }

    @Test
    void toFileUrnSchemeReturnsNull() throws Exception {
        assertNull(toFile(IRI.create("urn:absolute:thing")), "a urn IRI is not a local file");
    }

    @Test
    void toFileRelativeIriReturnsNull() throws Exception {
        // A bare fragment IRI has no scheme; toURI has no "file" scheme → null (no exception leaks).
        assertNull(toFile(IRI.create("http://ex.org/x#frag")), "a fragment http IRI is not a file");
    }

    // ============================================================= skip

    @Test
    void skipBuildsImportAndReasonMap() throws Exception {
        Map<String, Object> s = skip("http://ex.org/i", "because");
        assertEquals("http://ex.org/i", s.get("import"), "import key holds the IRI");
        assertEquals("because", s.get("reason"), "reason key holds the reason");
        assertEquals(2, s.size(), "exactly two keys");
    }

    @Test
    void skipKeyOrderIsImportThenReason() throws Exception {
        Map<String, Object> s = skip("i", "r");
        assertEquals(List.of("import", "reason"), List.copyOf(s.keySet()),
                "key order is stable: import first, then reason");
    }

    @Test
    void skipHandlesNullArguments() throws Exception {
        Map<String, Object> s = skip(null, null);
        assertNull(s.get("import"), "null import IRI is stored as null");
        assertNull(s.get("reason"), "null reason is stored as null");
        assertTrue(s.containsKey("import") && s.containsKey("reason"), "both keys still present");
    }

    // ============================================================= addEntry

    @Test
    void addEntryFirstOccurrenceAddsToSeenAndEntries() throws Exception {
        XMLCatalog catalog = new XMLCatalog(new File(".").toURI());
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        addEntry(catalog, entries, seen, "http://ex.org/n", URI.create("rel/a.owl"));

        assertTrue(seen.contains("http://ex.org/n"), "the name is recorded in seenNames");
        assertEquals(1, entries.size(), "the entry is added");
        assertEquals("http://ex.org/n", entries.get(0).get("name"), "entry name matches");
        assertEquals("rel/a.owl", entries.get(0).get("uri"), "entry uri is the stringified URI");
    }

    @Test
    void addEntryDuplicateNameIsSkipped() throws Exception {
        XMLCatalog catalog = new XMLCatalog(new File(".").toURI());
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        addEntry(catalog, entries, seen, "dup", URI.create("first.owl"));
        addEntry(catalog, entries, seen, "dup", URI.create("second.owl"));

        assertEquals(1, entries.size(), "a duplicate name is mapped only once");
        assertEquals("first.owl", entries.get(0).get("uri"),
                "the first URI wins; the duplicate call is a no-op");
    }

    @Test
    void addEntryPreservesNameUriKeyOrder() throws Exception {
        XMLCatalog catalog = new XMLCatalog(new File(".").toURI());
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        addEntry(catalog, entries, new LinkedHashSet<>(), "n", URI.create("u"));

        assertEquals(List.of("name", "uri"), List.copyOf(entries.get(0).keySet()),
                "entry map key order is name then uri");
    }

    // ============================================================= CATALOG_NAME invariant

    @Test
    void catalogFileNameIsProtegeConvention(@TempDir File dir) throws Throwable {
        // The resolved default file name must be exactly Protégé's catalog-v001.xml.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology active = m.createOntology(IRI.create("http://example.org/a"));
        File resolved = resolveCatalogFile(m, active, dir.getPath());
        assertEquals("catalog-v001.xml", resolved.getName(),
                "the catalog file name matches OntologyCatalogManager.CATALOG_NAME");
    }
}
