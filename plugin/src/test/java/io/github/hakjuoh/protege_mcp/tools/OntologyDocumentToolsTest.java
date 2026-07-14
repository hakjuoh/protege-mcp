package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The shared local-source + sibling-catalog resolution used by load_ontology / merge_ontology_document
 * / diff_ontologies. A {@code file:} URI must be treated as a local file (so the sibling catalog is
 * honoured), exactly like a plain path.
 */
class OntologyDocumentToolsTest {

    private static final String IMPORTED_IRI = "http://example.org/odt/Imported";
    private static final String MODULE_IRI = "http://example.org/odt/Module";

    @Test
    void localFileResolvesPlainPathAndFileUri(@TempDir Path dir) throws Exception {
        File f = dir.resolve("doc.rdf").toFile();
        Files.write(f.toPath(), "<x/>".getBytes());

        assertEquals(f.getCanonicalFile(),
                OntologyDocumentTools.localFile(f.getPath()).getCanonicalFile(), "plain path");
        assertEquals(f.getCanonicalFile(),
                OntologyDocumentTools.localFile(f.toURI().toString()).getCanonicalFile(), "file: URI");
        assertNull(OntologyDocumentTools.localFile("http://example.org/not-a-file"), "http is not a local file");
        assertNull(OntologyDocumentTools.localFile(dir.resolve("missing.rdf").toString()), "missing file");
    }

    @Test
    void siblingCatalogResolvesImportsForBothPathAndFileUriSources(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("imported.rdf"),
                ("<?xml version=\"1.0\"?>\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                        + " xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                        + "  <owl:Ontology rdf:about=\"" + IMPORTED_IRI + "\"/>\n"
                        + "  <owl:Class rdf:about=\"http://example.org/odt/ImportedClass\"/>\n"
                        + "</rdf:RDF>\n").getBytes());
        File module = dir.resolve("module.rdf").toFile();
        Files.write(module.toPath(),
                ("<?xml version=\"1.0\"?>\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\""
                        + " xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                        + "  <owl:Ontology rdf:about=\"" + MODULE_IRI + "\">\n"
                        + "    <owl:imports rdf:resource=\"" + IMPORTED_IRI + "\"/>\n"
                        + "  </owl:Ontology>\n</rdf:RDF>\n").getBytes());
        Files.write(dir.resolve("catalog-v001.xml"),
                ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                        + "<catalog prefer=\"public\" xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                        + "  <uri id=\"User Entered Import Resolution\" name=\"" + IMPORTED_IRI
                        + "\" uri=\"imported.rdf\"/>\n</catalog>\n").getBytes());

        assertImportResolved(module.getPath());                 // plain path
        assertImportResolved(module.toURI().toString());        // file: URI (the gap this closes)
    }

    private static void assertImportResolved(String source) throws Exception {
        String normalized = OntologyDocumentTools.normalizeSource(source);
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        OntologyDocumentTools.addFolderCatalogMapper(m, normalized);
        OWLOntology module = m.loadOntologyFromOntologyDocument(
                OntologyDocumentTools.documentSource(normalized), config);
        boolean importedInClosure = module.getImportsClosure().stream().anyMatch(o ->
                o.getOntologyID().getOntologyIRI().isPresent()
                        && o.getOntologyID().getOntologyIRI().get().toString().equals(IMPORTED_IRI));
        assertTrue(importedInClosure,
                "the sibling catalog resolved the import to the local file for source: " + source);
    }
}
