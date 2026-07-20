package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class ImportGraphAnalysisServiceTest {

    @Test
    void reportsMissingImportsCyclesAndLoadedIdentityConflicts() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology a = manager.createOntology(IRI.create("https://example.org/a"));
        OWLOntology b = manager.createOntology(IRI.create("https://example.org/b"));
        imports(manager, a, "https://example.org/b");
        imports(manager, b, "https://example.org/a");
        imports(manager, a, "https://example.org/missing");

        String logical = "https://example.org/upstream";
        OWLOntology v1 = manager.createOntology(new OWLOntologyID(IRI.create(logical),
                IRI.create(logical + "/1")));
        OWLOntology v2 = manager.createOntology(new OWLOntologyID(IRI.create(logical),
                IRI.create(logical + "/2")));
        imports(manager, a, logical + "/1");
        imports(manager, a, logical + "/2");

        ImportGraphAnalysisService.Report report = ImportGraphAnalysisService.analyze(a);

        assertEquals(1, report.missingImports().size());
        assertEquals(1, report.cycles().size());
        assertEquals(List.of("https://example.org/a", "https://example.org/b"),
                report.cycles().get(0).get("ontologies"));
        assertTrue(report.conflicts().stream().anyMatch(row ->
                "multiple_versions".equals(row.get("type"))
                        && logical.equals(row.get("value"))));
        assertTrue(report.ontologies().stream().anyMatch(row ->
                v1.getOntologyID().getVersionIRI().get().toString()
                        .equals(row.get("ref"))));
        assertTrue(report.ontologies().stream().anyMatch(row ->
                v2.getOntologyID().getVersionIRI().get().toString()
                        .equals(row.get("ref"))));
    }

    @Test
    void resolvesLoadedDocumentWhenTheDeclarationPredatesTheTarget() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        String document = "file:/project/shared.owl";
        imports(manager, root, document);
        OWLOntology first = manager.createOntology(IRI.create("https://example.org/first"));
        manager.setOntologyDocumentIRI(first, IRI.create(document));

        ImportGraphAnalysisService.Report report = ImportGraphAnalysisService.analyze(root);

        assertEquals(0, report.missingImports().size());
        assertEquals(1, report.resolvedImports().size());
        assertEquals("https://example.org/first",
                report.resolvedImports().get(0).get("target_ontology_iri"));
    }

    @Test
    void rejectsNullAndClassifiesMalformedLocations() {
        assertThrows(IllegalArgumentException.class,
                () -> ImportGraphAnalysisService.analyze(null));
        assertEquals("unknown", ImportGraphAnalysisService.sourceType("http://[invalid"));
        assertEquals("remote", ImportGraphAnalysisService.sourceType("HTTPS://example.org/a.owl"));
    }

    private static void imports(OWLOntologyManager manager, OWLOntology source, String target) {
        manager.applyChange(new AddImport(source, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create(target))));
    }
}
