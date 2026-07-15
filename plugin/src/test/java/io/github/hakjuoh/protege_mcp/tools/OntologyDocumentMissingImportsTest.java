package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class OntologyDocumentMissingImportsTest {

    @Test
    void warningAndSilentModesKeepTheCompatibilityLoadBehavior(@TempDir Path dir) throws Exception {
        Path document = documentWithMissingImport(dir);
        Method mergeLoader = loader("load", String.class, int.class, MissingImportsMode.class,
                List.class);
        Method workspaceLoader = loader("fetch", String.class, int.class, MissingImportsMode.class,
                List.class);

        assertDoesNotThrow(() -> invoke(mergeLoader, document.toString(), 1_000,
                MissingImportsMode.WARN, List.of()));
        assertDoesNotThrow(() -> invoke(mergeLoader, document.toString(), 1_000,
                MissingImportsMode.SILENT, List.of()));
        assertDoesNotThrow(() -> invoke(workspaceLoader, document.toString(), 1_000,
                MissingImportsMode.WARN, List.of()));
        assertDoesNotThrow(() -> invoke(workspaceLoader, document.toString(), 1_000,
                MissingImportsMode.SILENT, List.of()));
    }

    @Test
    void warningAndSilentModesTreatUnsupportedSchemesAsUnresolvedImports(@TempDir Path dir)
            throws Throwable {
        int fixture = 0;
        for (String missing : List.of(
                "urn:example:missing-import", "tag:example.org,2026:missing-import")) {
            Path document = documentWithImport(dir.resolve("opaque-module-" + fixture++ + ".rdf"), missing);
            for (Method method : new Method[] {
                    loader("load", String.class, int.class, MissingImportsMode.class, List.class),
                    loader("fetch", String.class, int.class, MissingImportsMode.class, List.class) }) {
                Object warned = invoke(method, document.toString(), 1_000,
                        MissingImportsMode.WARN, List.of());
                assertTrue(unresolved(warned).contains(missing),
                        "warn must retain the unsupported import IRI for the tool result");

                Object silent = invoke(method, document.toString(), 1_000,
                        MissingImportsMode.SILENT, List.of());
                assertTrue(unresolved(silent).contains(missing),
                        "silent keeps the internal fact and suppresses it only when rendering the result");
            }
        }
    }

    @Test
    void strictModeRejectsOpaqueUrnAsAnActionableMissingImport(@TempDir Path dir) throws Exception {
        String missing = "urn:example:strict-missing-import";
        Path document = documentWithImport(dir.resolve("strict-urn-module.rdf"), missing);
        for (Method method : new Method[] {
                loader("load", String.class, int.class, MissingImportsMode.class, List.class),
                loader("fetch", String.class, int.class, MissingImportsMode.class, List.class) }) {
            Throwable failure = assertThrows(Throwable.class,
                    () -> invoke(method, document.toString(), 1_000,
                            MissingImportsMode.ERROR, List.of()));
            assertInstanceOf(ToolArgException.class, failure);
            assertTrue(failure.getMessage().contains("required import"), failure.getMessage());
            assertTrue(failure.getMessage().contains(missing), failure.getMessage());
        }
    }

    @Test
    void siblingCatalogStillResolvesAnOpaqueUrnBeforeTheFallback(@TempDir Path dir) throws Throwable {
        String importedIri = "urn:example:catalog-imported";
        Path importedDocument = dir.resolve("urn-catalog-imported.rdf");
        Files.writeString(importedDocument,
                "<?xml version=\"1.0\"?>\n"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                        + "xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                        + "  <owl:Ontology rdf:about=\"" + importedIri + "\"/>\n"
                        + "</rdf:RDF>\n", StandardCharsets.UTF_8);
        Path module = documentWithImport(dir.resolve("urn-catalog-module.rdf"), importedIri);
        Files.writeString(dir.resolve("catalog-v001.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                        + "  <uri name=\"" + importedIri + "\" uri=\"urn-catalog-imported.rdf\"/>\n"
                        + "</catalog>\n", StandardCharsets.UTF_8);

        for (Method method : new Method[] {
                loader("load", String.class, int.class, MissingImportsMode.class, List.class),
                loader("fetch", String.class, int.class, MissingImportsMode.class, List.class) }) {
            Object loaded = invoke(method, module.toString(), 1_000,
                    MissingImportsMode.ERROR, List.of());
            assertTrue(unresolved(loaded).isEmpty(),
                    "catalog resolution must win before the unsupported-URN fallback");
        }
    }

    @Test
    void opaqueUrnSelfImportIsNotReportedAsMissing(@TempDir Path dir) throws Throwable {
        String self = "urn:example:self-import";
        Path document = dir.resolve("self-import.rdf");
        Files.writeString(document,
                "<?xml version=\"1.0\"?>\n"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                        + "xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                        + "  <owl:Ontology rdf:about=\"" + self + "\">\n"
                        + "    <owl:imports rdf:resource=\"" + self + "\"/>\n"
                        + "  </owl:Ontology>\n"
                        + "</rdf:RDF>\n", StandardCharsets.UTF_8);

        for (Method method : new Method[] {
                loader("load", String.class, int.class, MissingImportsMode.class, List.class),
                loader("fetch", String.class, int.class, MissingImportsMode.class, List.class) }) {
            Object loaded = invoke(method, document.toString(), 1_000,
                    MissingImportsMode.ERROR, List.of());
            assertTrue(unresolved(loaded).isEmpty(),
                    "the completed root resolves its own import even if the streaming parser used fallback");
        }
    }

    @Test
    void strictModeFailsBothLoadPathsBeforeTheyCanReachLiveState(@TempDir Path dir) throws Exception {
        Path document = documentWithMissingImport(dir);
        for (Method method : new Method[] {
                loader("load", String.class, int.class, MissingImportsMode.class, List.class),
                loader("fetch", String.class, int.class, MissingImportsMode.class, List.class) }) {
            Throwable failure = assertThrows(Throwable.class,
                    () -> invoke(method, document.toString(), 1_000, MissingImportsMode.ERROR,
                            List.of()));
            assertInstanceOf(ToolArgException.class, failure);
            assertTrue(failure.getMessage().contains("required import"));
            assertTrue(failure.getMessage().contains("missing.owl"));
        }
    }

    @Test
    void strictModeReusesAWorkspaceLoadedImportDocument(@TempDir Path dir) throws Throwable {
        String importedIri = "https://example.org/imported";
        Path importedDocument = dir.resolve("imported.rdf");
        Files.writeString(importedDocument,
                "<?xml version=\"1.0\"?>\n"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                        + "xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                        + "  <owl:Ontology rdf:about=\"" + importedIri + "\"/>\n"
                        + "</rdf:RDF>\n", StandardCharsets.UTF_8);
        Path module = documentWithImport(dir.resolve("module-with-workspace-import.rdf"), importedIri);

        OWLOntologyManager workspace = OWLManager.createOWLOntologyManager();
        OWLOntology imported = workspace.loadOntologyFromOntologyDocument(importedDocument.toFile());
        List<OntologyDocumentTools.ImportMapping> mappings = OntologyDocumentTools
                .workspaceImportMappings(FakeModelManager.over(imported));
        assertTrue(mappings.stream().anyMatch(mapping -> importedIri.equals(mapping.logical.toString())
                && importedDocument.toUri().equals(mapping.document.toURI())));

        Method mergeLoader = loader("load", String.class, int.class, MissingImportsMode.class,
                List.class);
        Method workspaceLoader = loader("fetch", String.class, int.class, MissingImportsMode.class,
                List.class);
        assertDoesNotThrow(() -> invoke(mergeLoader, module.toString(), 1_000,
                MissingImportsMode.ERROR, mappings));
        assertDoesNotThrow(() -> invoke(workspaceLoader, module.toString(), 1_000,
                MissingImportsMode.ERROR, mappings));
    }

    @Test
    void siblingCatalogTakesPriorityOverAStaleWorkspaceHint(@TempDir Path dir) throws Throwable {
        String importedIri = "https://example.org/catalog-imported";
        Path importedDocument = dir.resolve("catalog-imported.rdf");
        Files.writeString(importedDocument,
                "<?xml version=\"1.0\"?>\n"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                        + "xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                        + "  <owl:Ontology rdf:about=\"" + importedIri + "\"/>\n"
                        + "</rdf:RDF>\n", StandardCharsets.UTF_8);
        Path module = documentWithImport(dir.resolve("catalog-module.rdf"), importedIri);
        Files.writeString(dir.resolve("catalog-v001.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<catalog prefer=\"public\" "
                        + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                        + "  <uri name=\"" + importedIri + "\" uri=\"catalog-imported.rdf\"/>\n"
                        + "</catalog>\n", StandardCharsets.UTF_8);
        List<OntologyDocumentTools.ImportMapping> stale = List.of(
                new OntologyDocumentTools.ImportMapping(IRI.create(importedIri),
                        IRI.create(dir.resolve("missing-stale.rdf").toUri())));

        Method mergeLoader = loader("load", String.class, int.class, MissingImportsMode.class,
                List.class);
        Method workspaceLoader = loader("fetch", String.class, int.class, MissingImportsMode.class,
                List.class);
        assertDoesNotThrow(() -> invoke(mergeLoader, module.toString(), 1_000,
                MissingImportsMode.ERROR, stale));
        assertDoesNotThrow(() -> invoke(workspaceLoader, module.toString(), 1_000,
                MissingImportsMode.ERROR, stale));
    }

    private static Method loader(String name, Class<?>... parameters) throws Exception {
        Method method = OntologyDocumentTools.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object invoke(Method method, Object... args) throws Throwable {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> unresolved(Object loaded) throws Exception {
        Field field = loaded.getClass().getDeclaredField("unresolvedImports");
        field.setAccessible(true);
        return (List<String>) field.get(loaded);
    }

    private static Path documentWithMissingImport(Path dir) throws Exception {
        String missing = dir.resolve("missing.owl").toUri().toString();
        return documentWithImport(dir.resolve("module.rdf"), missing);
    }

    private static Path documentWithImport(Path document, String importedIri) throws Exception {
        Files.writeString(document,
                "<?xml version=\"1.0\"?>\n"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                        + "xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                        + "  <owl:Ontology rdf:about=\"https://example.org/module\">\n"
                        + "    <owl:imports rdf:resource=\"" + importedIri + "\"/>\n"
                        + "  </owl:Ontology>\n"
                        + "</rdf:RDF>\n",
                StandardCharsets.UTF_8);
        return document;
    }
}
