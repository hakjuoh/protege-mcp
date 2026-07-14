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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class ImportLockToolsTest {

    @TempDir
    Path temp;

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
