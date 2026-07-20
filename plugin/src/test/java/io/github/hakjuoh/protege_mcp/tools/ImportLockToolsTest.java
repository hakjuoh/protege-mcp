package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class ImportLockToolsTest {

    @TempDir
    Path temp;

    @Test
    void validateCatalogReportsPolicyRefusedEntriesPerEntryInsteadOfAbortingTheScan() throws Exception {
        // A catalog entry whose target the direct-access policy refuses (here: caller-selected paths
        // disabled with no policy) makes rules.readPath throw a ToolArgException — a RuntimeException
        // the per-entry IllegalArgumentException catch does NOT catch. Before the fix that escaped to
        // the outer catch and aborted the WHOLE scan as a fake "could not parse catalog" failure,
        // dropping every other entry, the nextCatalog chain, and fabricating unmapped_imports. Now it
        // is a per-entry outside_project status and the scan completes. The catalog itself is derived
        // beside the active document (an authorized implicit path); only the entry targets are refused.
        Files.createDirectories(temp.resolve("lib"));
        Files.writeString(temp.resolve("lib/a.owl"), "<x/>\n");
        Files.writeString(temp.resolve("lib/b.owl"), "<x/>\n");
        Files.writeString(temp.resolve("catalog-v001.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                + "  <uri name=\"https://example.org/a\" uri=\"lib/a.owl\"/>\n"
                + "  <uri name=\"https://example.org/b\" uri=\"lib/b.owl\"/>\n"
                + "  <nextCatalog catalog=\"other/catalog-v001.xml\"/>\n"
                + "</catalog>\n");

        McpConfig.prefs().putBoolean(McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS, false);
        try {
            Map<String, Object> result = structured(ImportLockTools.validateCatalog(
                    controlledContext(temp.resolve("ontology.ttl"), "https://example.org/b"),
                    Map.of("compare_imports", true)));

            List<?> entries = (List<?>) result.get("entries");
            assertEquals(2, entries.size(), () -> "both entries must be scanned, not dropped: " + result);
            for (Object value : entries) {
                assertEquals("policy_refused", ((Map<?, ?>) value).get("status"), result::toString);
            }
            List<?> errors = (List<?>) result.get("errors");
            assertFalse(errors.stream().anyMatch(e -> String.valueOf(e).contains("could not parse")),
                    () -> "a policy-refused entry must not be misreported as a parse failure: " + errors);
            assertEquals(1, ((List<?>) result.get("next_catalogs")).size(),
                    () -> "the nextCatalog after the refused entry must still be reported: " + result);
            // The import mapped by the second entry was scanned into names, so it is neither fabricated
            // as unmapped nor as delegated.
            assertEquals(List.of(), result.get("unmapped_imports"), result::toString);
            assertEquals(List.of(), result.get("delegated_imports"), result::toString);
        } finally {
            McpConfig.prefs().putBoolean(McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS, true);
        }
    }

    /**
     * A context with a real controller (so the no-policy compatibility switch is consulted) whose
     * active ontology reports {@code documentIri} as its document — the beside-active catalog default
     * derives from that folder.
     */
    private ToolContext controlledContext(Path documentIri, String... importIris) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("https://example.org/active"));
        manager.setOntologyDocumentIRI(active, IRI.create(documentIri.toUri()));
        for (String importIri : importIris) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                    manager.getOWLDataFactory().getOWLImportsDeclaration(IRI.create(importIri))));
        }
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)),
                new McpServerController(new OntologyAccess(null)));
    }

    @Test
    void parsesStrictRelativeLockEntry() throws Exception {
        ImportLockTools.LockEntry entry = ImportLockTools.parseEntry(entry("imports/upper.ttl"), temp);
        assertEquals("https://example.org/upper @ https://example.org/upper/1", entry.key());
        assertEquals(temp.resolve("imports/upper.ttl").normalize(), entry.absolute());
    }

    @Test
    void rejectsTraversalAbsolutePathsMalformedHashesAndUnknownFields() {
        assertThrows(IOException.class, () -> ImportLockTools.parseEntry(entry("../secret.owl"), temp));
        assertThrows(IOException.class, () -> ImportLockTools.parseEntry(entry(temp.resolve("x.owl").toString()), temp));
        Map<String, Object> badHash = entry("imports/upper.ttl");
        badHash.put("sha256", "ABC");
        assertThrows(IOException.class, () -> ImportLockTools.parseEntry(badHash, temp));
        Map<String, Object> unknown = entry("imports/upper.ttl");
        unknown.put("surprise", true);
        assertThrows(IOException.class, () -> ImportLockTools.parseEntry(unknown, temp));
    }

    @Test
    void verifyRejectsUnknownTopLevelFields() throws Exception {
        Path lock = temp.resolve("imports.lock.json");
        Files.writeString(lock, "{\"version\":1,\"imports\":[],\"surprise\":true}\n");

        Map<String, Object> result = structured(ImportLockTools.verify(
                context(), Map.of("path", lock.toString())));

        assertEquals(false, result.get("valid"));
        assertTrue(String.valueOf(result.get("errors")).contains("unknown top-level"));
        assertTrue(String.valueOf(result.get("sha256")).matches("sha256:[0-9a-f]{64}"),
                "invalid but fully captured lock bytes retain their digest for triage");
    }

    @Test
    void verifyRefusesAStalePassWhenImportsChangeDuringHashing() throws Exception {
        Path lock = temp.resolve("imports.lock.json");
        Files.writeString(lock, "{\"version\":1,\"imports\":[]}\n");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("https://example.org/active"));
        ToolContext ctx = new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);

        Map<String, Object> result = structured(ImportLockTools.verify(ctx,
                Map.of("path", lock.toString()), () -> manager.applyChange(
                        new org.semanticweb.owlapi.model.AddImport(active,
                                manager.getOWLDataFactory().getOWLImportsDeclaration(
                                        IRI.create("urn:test:concurrent-import"))))));

        assertEquals(false, result.get("valid"));
        assertTrue(String.valueOf(result.get("errors")).contains("state changed"),
                () -> result.toString());
    }

    @Test
    void bothVerifyHopsPassTheirExplicitLongBoundToTheEdtWait() throws Exception {
        // verify() performs exactly three EDT dispatches: the policy-resolution read, the initial
        // coordinate hop, and the post-hashing recheck hop. Dispatches 2 and 3 start their bodies
        // only after a stall that exceeds the access's DEFAULT wait bound, so this verify succeeds
        // only because both hops hand WRITE_HOP_TIMEOUT_MS to the bounded compute — each runs the
        // same two-render revision fingerprint as the write hops, so a hop regressing to the
        // default-bound compute(fn) would spuriously time out on a large ontology right after a
        // successful write_import_lock. Under the stall that regression expires before its queued
        // body starts, the body is then skipped, and the verify fails instead of reporting the lock.
        Path lock = temp.resolve("imports.lock.json");
        Files.writeString(lock, "{\"version\":1,\"imports\":[]}\n");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("https://example.org/active"));
        AtomicInteger dispatches = new AtomicInteger();
        ToolContext ctx = new ToolContext(
                HeadlessAccess.overStalledDispatches(FakeModelManager.over(active),
                        150L, 1_000L, 2, dispatches), null);

        Map<String, Object> result = structured(ImportLockTools.verify(ctx,
                Map.of("path", lock.toString())));

        assertEquals(true, result.get("valid"), () -> result.toString());
        assertEquals(3, dispatches.get(), "policy resolution + initial coordinates + final recheck;"
                + " update the stalled-dispatch script if verify()'s hop structure changes");
    }

    private static Map<String, Object> entry(String document) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ontology_iri", "https://example.org/upper");
        entry.put("version_iri", "https://example.org/upper/1");
        entry.put("document", document);
        entry.put("sha256", "a".repeat(64));
        entry.put("direct", true);
        return entry;
    }

    // ------------------------------------------------------------------ validate_catalog: xml:base

    @Test
    void validateCatalogHonorsGroupXmlBaseWhenResolvingUriTargets() throws Exception {
        // The document lives ONLY under lib/ — resolving the uri against the catalog folder while
        // ignoring the group xml:base would misreport a spec-valid catalog as missing its document.
        Files.createDirectories(temp.resolve("lib"));
        Files.writeString(temp.resolve("lib/upper.owl"), "<x/>\n");
        Path catalog = temp.resolve("catalog-v001.xml");
        Files.writeString(catalog, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" prefer=\"public\">\n"
                + "  <group xml:base=\"lib/\">\n"
                + "    <uri name=\"https://example.org/upper\" uri=\"upper.owl\"/>\n"
                + "  </group>\n"
                + "</catalog>\n");
        Map<String, Object> result = structured(ImportLockTools.validateCatalog(
                context(), Map.of("path", catalog.toString())));
        assertEquals(true, result.get("valid"), () -> result.toString());
        assertEquals(List.of(), result.get("errors"));
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) result.get("entries")).get(0);
        assertEquals("local_ok", entry.get("status"),
                "the uri target must resolve through the group xml:base");
    }

    @Test
    void validateCatalogChainsRelativeXmlBasesFromCatalogRootToEntry() throws Exception {
        // OASIS xml:base semantics: a relative entry base resolves against the enclosing base, which
        // itself resolves against the catalog file URI — a/ then b/ locates a/b/doc.owl.
        Files.createDirectories(temp.resolve("a/b"));
        Files.writeString(temp.resolve("a/b/doc.owl"), "<x/>\n");
        Path catalog = temp.resolve("catalog-v001.xml");
        Files.writeString(catalog, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\" xml:base=\"a/\">\n"
                + "  <uri xml:base=\"b/\" name=\"https://example.org/doc\" uri=\"doc.owl\"/>\n"
                + "</catalog>\n");
        Map<String, Object> result = structured(ImportLockTools.validateCatalog(
                context(), Map.of("path", catalog.toString())));
        assertEquals(true, result.get("valid"), () -> result.toString());
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) result.get("entries")).get(0);
        assertEquals("local_ok", entry.get("status"));
    }

    // ------------------------------------------------------------------ validate_catalog: nextCatalog

    @Test
    void validateCatalogReportsNextCatalogAndSoftensCompareImports() throws Exception {
        Path catalog = temp.resolve("catalog-v001.xml");
        Files.writeString(catalog, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                + "  <nextCatalog catalog=\"other/catalog-v001.xml\"/>\n"
                + "</catalog>\n");
        Map<String, Object> result = structured(ImportLockTools.validateCatalog(
                context("https://example.org/elsewhere"),
                Map.of("path", catalog.toString(), "compare_imports", true)));
        assertEquals(true, result.get("valid"), () -> result.toString());
        List<?> nextCatalogs = (List<?>) result.get("next_catalogs");
        assertEquals(1, nextCatalogs.size(), "the delegation must be reported");
        assertTrue(String.valueOf(nextCatalogs.get(0)).endsWith("other/catalog-v001.xml"),
                "the delegation is resolved against the catalog file URI");
        assertEquals(List.of("https://example.org/elsewhere"), result.get("delegated_imports"),
                "an import only reachable via the delegation is unverified-delegated, not unmapped");
        assertEquals(List.of(), result.get("unmapped_imports"));
    }

    @Test
    void validateCatalogWithoutDelegationStillFailsUnmappedImports() throws Exception {
        Path catalog = temp.resolve("catalog-v001.xml");
        Files.writeString(catalog, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\"/>\n");
        Map<String, Object> result = structured(ImportLockTools.validateCatalog(
                context("https://example.org/elsewhere"),
                Map.of("path", catalog.toString(), "compare_imports", true)));
        assertEquals(false, result.get("valid"),
                "with no delegation an unmapped import remains a hard failure");
        assertEquals(List.of("https://example.org/elsewhere"), result.get("unmapped_imports"));
        assertEquals(List.of(), result.get("delegated_imports"));
    }

    @Test
    void malformedCatalogHasOneParseErrorPrefix() throws Exception {
        Path catalog = temp.resolve("catalog-v001.xml");
        Files.writeString(catalog, "<catalog><broken></catalog>");

        Map<String, Object> result = structured(ImportLockTools.validateCatalog(
                context(), Map.of("path", catalog.toString())));

        String errors = String.valueOf(result.get("errors"));
        assertTrue(errors.contains("could not parse catalog: invalid catalog XML:"), errors);
        assertFalse(errors.contains("could not parse catalog: could not parse catalog:"), errors);
    }

    // ------------------------------------------------------------------ fixtures

    /** A headless context whose active ontology declares the given direct imports (unresolved is fine). */
    private static ToolContext context(String... importIris) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology active = manager.createOntology(IRI.create("https://example.org/active"));
        for (String importIri : importIris) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                    manager.getOWLDataFactory().getOWLImportsDeclaration(IRI.create(importIri))));
        }
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
