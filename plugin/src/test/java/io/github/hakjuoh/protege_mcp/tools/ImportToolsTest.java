package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class ImportToolsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String field) {
        return (List<Map<String, Object>>) body.get(field);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> body, String field) {
        return (Map<String, Object>) body.get(field);
    }

    private static void imports(OWLOntologyManager manager, OWLOntology source, String target) {
        manager.applyChange(new AddImport(source, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create(target))));
    }

    @Test
    void reportsResolvedMissingDirectAndTransitiveImports(@TempDir Path dir) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        OWLOntology direct = manager.createOntology(IRI.create("https://example.org/direct"));
        OWLOntology transitive = manager.createOntology(IRI.create("https://example.org/transitive"));
        manager.setOntologyDocumentIRI(root, IRI.create(dir.resolve("root.owl").toUri()));
        manager.setOntologyDocumentIRI(direct, IRI.create(dir.resolve("direct.owl").toUri()));
        manager.setOntologyDocumentIRI(transitive, IRI.create("https://cdn.example.org/transitive.owl"));

        imports(manager, root, "https://example.org/direct");
        imports(manager, root, "https://example.org/missing");
        imports(manager, direct, "https://example.org/transitive");

        Map<String, Object> result = body(ImportTools.inspect(FakeModelManager.over(root)));
        Map<String, Object> summary = object(result, "summary");
        assertEquals(3, summary.get("ontology_count"));
        assertEquals(3, summary.get("declaration_count"));
        assertEquals(2, summary.get("direct_import_count"));
        assertEquals(1, summary.get("transitive_import_count"));
        assertEquals(2, summary.get("resolved_import_count"));
        assertEquals(1, summary.get("missing_import_count"));
        assertEquals(2, summary.get("local_document_count"));
        assertEquals(1, summary.get("remote_document_count"));
        assertFalse((Boolean) result.get("missing_imports_clear"));
        assertTrue((Boolean) result.get("conflicts_clear"));
        assertFalse((Boolean) result.get("import_integrity_ok"));

        List<Map<String, Object>> missing = rows(result, "missing_imports");
        assertEquals("https://example.org/missing", missing.get(0).get("import_iri"));
        assertEquals(Boolean.FALSE, missing.get(0).get("resolved"));
        assertEquals(2, rows(result, "resolved_imports").size());
        assertTrue(rows(result, "imports").stream().anyMatch(row ->
                "https://example.org/transitive".equals(row.get("import_iri"))
                        && Boolean.FALSE.equals(row.get("direct"))
                        && Integer.valueOf(2).equals(row.get("depth"))));
    }

    @Test
    void reportsCyclesAsStronglyConnectedComponents() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology a = manager.createOntology(IRI.create("https://example.org/a"));
        OWLOntology b = manager.createOntology(IRI.create("https://example.org/b"));
        imports(manager, a, "https://example.org/b");
        imports(manager, b, "https://example.org/a");

        Map<String, Object> result = body(ImportTools.inspect(FakeModelManager.over(a)));
        assertEquals(1, object(result, "summary").get("cycle_count"));
        Map<String, Object> cycle = rows(result, "cycles").get(0);
        assertEquals(2, cycle.get("size"));
        assertEquals(List.of("https://example.org/a", "https://example.org/b"),
                cycle.get("ontologies"));
        assertEquals(Boolean.FALSE, cycle.get("self_loop"));
    }

    @Test
    void reportsLoadedVersionConflicts() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        String logical = "https://example.org/upstream";
        String v1 = "https://example.org/upstream/1";
        String v2 = "https://example.org/upstream/2";
        manager.createOntology(new OWLOntologyID(IRI.create(logical), IRI.create(v1)));
        manager.createOntology(new OWLOntologyID(IRI.create(logical), IRI.create(v2)));
        imports(manager, root, v1);
        imports(manager, root, v2);

        Map<String, Object> result = body(ImportTools.inspect(FakeModelManager.over(root)));
        List<Map<String, Object>> conflicts = rows(result, "conflicts");
        assertTrue(conflicts.stream().anyMatch(row -> "multiple_versions".equals(row.get("type"))
                && logical.equals(row.get("value"))));
        assertFalse((Boolean) result.get("conflicts_clear"));
        assertFalse((Boolean) result.get("import_integrity_ok"));
    }

    @Test
    void resolvesAnUnambiguousLoadedDocumentIriWhenOwlapiHasNoCachedEdge() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        String document = "file:/project/imported.owl";
        imports(manager, root, document);
        OWLOntology target = manager.createOntology(IRI.create("https://example.org/imported"));
        manager.setOntologyDocumentIRI(target, IRI.create(document));

        Map<String, Object> result = body(ImportTools.inspect(FakeModelManager.over(root)));
        assertEquals(1, rows(result, "resolved_imports").size());
        assertEquals("https://example.org/imported",
                rows(result, "resolved_imports").get(0).get("target_ontology_iri"));
        assertTrue((Boolean) result.get("import_integrity_ok"));
    }

    @Test
    void outputOrderingIsIndependentOfDeclarationInsertionOrder() throws Exception {
        Map<String, Object> forward = deterministicFixture(false);
        Map<String, Object> reverse = deterministicFixture(true);
        assertEquals(Tools.serialize(forward), Tools.serialize(reverse));
    }

    private static Map<String, Object> deterministicFixture(boolean reverse) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        OWLOntology a = manager.createOntology(IRI.create("https://example.org/a"));
        OWLOntology b = manager.createOntology(IRI.create("https://example.org/b"));
        manager.setOntologyDocumentIRI(root, IRI.create("file:/project/root.owl"));
        manager.setOntologyDocumentIRI(a, IRI.create("file:/project/a.owl"));
        manager.setOntologyDocumentIRI(b, IRI.create("file:/project/b.owl"));
        if (reverse) {
            imports(manager, root, "https://example.org/b");
            imports(manager, root, "https://example.org/a");
        } else {
            imports(manager, root, "https://example.org/a");
            imports(manager, root, "https://example.org/b");
        }
        return body(ImportTools.inspect(FakeModelManager.over(root)));
    }

    @Test
    void classifiesDocumentSourceSchemes() {
        assertEquals("local", ImportTools.sourceType("file:/tmp/a.owl"));
        assertEquals("remote", ImportTools.sourceType("HTTPS://example.org/a.owl"));
        assertEquals("memory", ImportTools.sourceType("owlapi:ontology42"));
        assertEquals("other", ImportTools.sourceType("urn:example:a"));
        assertEquals("unknown", ImportTools.sourceType("relative/path.owl"));
        assertEquals("unknown", ImportTools.sourceType(null));
    }

    @Test
    void registersOneReadOnlyNoArgumentTool() {
        ToolRegistry registry = new ToolRegistry();
        ImportTools.register(registry, null);
        List<SyncToolSpecification> specs = registry.build();
        assertEquals(1, specs.size());
        assertEquals("inspect_imports", specs.get(0).tool().name());
        assertEquals(Tools.emptySchema(), specs.get(0).tool().inputSchema());
    }
}
