package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.util.SimpleIRIMapper;

/** MOVE-boundary regressions for load_ontology attachment into the shared workspace manager. */
class OntologyDocumentAttachTest {

    private static final String ROOT_IRI = "https://example.org/attach/root";
    private static final String ALIAS_IRI = "https://example.org/attach/cache/child.rdf";
    private static final String CHILD_IRI = "http://purl.obolibrary.org/obo/attach-child.owl";

    @Test
    void catalogAliasStillResolvesAfterTheParsedClosureMovesIntoTheWorkspace(@TempDir Path dir)
            throws Exception {
        Path root = writeAliasProject(dir);

        OWLOntologyManager workspace = OwlManagers.createConcurrent();
        OWLOntology bootstrap = workspace.createOntology(IRI.create("urn:test:bootstrap"));
        AtomicReference<OWLOntology> active = new AtomicReference<>(bootstrap);
        OWLModelManager modelManager = workspaceModel(workspace, active);

        loadAndAttach(modelManager, root);

        OWLOntology attachedRoot = workspace.getOntology(IRI.create(ROOT_IRI));
        assertNotNull(attachedRoot);
        OWLOntology imported = workspace.getImportedOntology(
                workspace.getOWLDataFactory().getOWLImportsDeclaration(IRI.create(ALIAS_IRI)));
        assertNotNull(imported, "alias declaration cache must be rebuilt after MOVE");
        assertEquals(CHILD_IRI, imported.getOntologyID().getOntologyIRI().get().toString());
        assertTrue(attachedRoot.getImportsClosure().contains(imported));
        assertEquals(2, attachedRoot.getImportsClosure().size());

        int mapperCount = workspace.getIRIMappers().size();
        loadAndAttach(modelManager, root);
        assertEquals(mapperCount, workspace.getIRIMappers().size(),
                "reloading the same project must not accumulate duplicate persistent mappers");
    }

    @Test
    void staleFailedImportMarkerOnTheWorkspaceManagerDoesNotRefuseTheLoad(@TempDir Path dir)
            throws Exception {
        Path root = writeAliasProject(dir);

        OWLOntologyManager workspace = OwlManagers.createConcurrent();
        OWLOntology bootstrap = workspace.createOntology(IRI.create("urn:test:bootstrap"));
        AtomicReference<OWLOntology> active = new AtomicReference<>(bootstrap);
        OWLModelManager modelManager = workspaceModel(workspace, active);

        // Poison the live manager exactly like an earlier failed load of the alias IRI (say, before
        // the catalog existed): OWLAPI's makeLoadImportRequest records a raw importedIRIs marker
        // BEFORE loading and never removes it on failure, so every later load request for that IRI
        // on this manager is a silent no-op. The mapper to a nonexistent file keeps the failure
        // local (no network fetch of the alias URL).
        OWLImportsDeclaration aliasDeclaration = workspace.getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create(ALIAS_IRI));
        SimpleIRIMapper unresolvable = new SimpleIRIMapper(IRI.create(ALIAS_IRI),
                IRI.create(dir.resolve("missing-child.rdf").toUri()));
        workspace.getIRIMappers().add(unresolvable);
        workspace.makeLoadImportRequest(aliasDeclaration, new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        workspace.getIRIMappers().remove(unresolvable);
        assertNull(workspace.getImportedOntology(aliasDeclaration),
                "the poisoning load must fail without connecting anything");

        loadAndAttach(modelManager, root);

        OWLOntology attachedRoot = workspace.getOntology(IRI.create(ROOT_IRI));
        assertNotNull(attachedRoot);
        OWLOntology imported = workspace.getImportedOntology(aliasDeclaration);
        assertNotNull(imported, "a stale failed-import marker must not refuse a loadable project");
        assertEquals(CHILD_IRI, imported.getOntologyID().getOntologyIRI().get().toString());
        assertTrue(attachedRoot.getImportsClosure().contains(imported));
        assertEquals(2, attachedRoot.getImportsClosure().size());
    }

    /** root.rdf imports the catalog alias; catalog-v001.xml maps the alias to the sibling child.rdf. */
    private static Path writeAliasProject(Path dir) throws IOException {
        Path child = dir.resolve("child.rdf");
        Files.writeString(child, """
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Ontology rdf:about="%s"/>
                  <owl:Class rdf:about="%s#C"/>
                </rdf:RDF>
                """.formatted(CHILD_IRI, CHILD_IRI));
        Path root = dir.resolve("root.rdf");
        Files.writeString(root, """
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Ontology rdf:about="%s"><owl:imports rdf:resource="%s"/></owl:Ontology>
                </rdf:RDF>
                """.formatted(ROOT_IRI, ALIAS_IRI));
        Files.writeString(dir.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <uri name="%s" uri="child.rdf"/>
                </catalog>
                """.formatted(ALIAS_IRI));
        return root;
    }

    /** The production load_ontology path: fetch into a throwaway manager, then attach on the model. */
    private static void loadAndAttach(OWLModelManager modelManager, Path root) throws Exception {
        Method fetch = OntologyDocumentTools.class.getDeclaredMethod("fetch", String.class,
                int.class, MissingImportsMode.class, List.class);
        fetch.setAccessible(true);
        Object loaded = fetch.invoke(null, root.toString(), 1_000, MissingImportsMode.ERROR, List.of());
        Method attach = OntologyDocumentTools.class.getDeclaredMethod("attach", OWLModelManager.class,
                loaded.getClass(), boolean.class);
        attach.setAccessible(true);
        attach.invoke(null, modelManager, loaded, false);
    }

    private static OWLModelManager workspaceModel(OWLOntologyManager manager,
            AtomicReference<OWLOntology> active) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getOWLOntologyManager" -> manager;
            case "getOWLDataFactory" -> manager.getOWLDataFactory();
            case "getActiveOntology" -> active.get();
            case "getActiveOntologies" -> active.get().getImportsClosure();
            case "getOntologies" -> manager.getOntologies();
            case "setActiveOntology" -> {
                active.set((OWLOntology) args[0]);
                yield null;
            }
            case "fireEvent", "addListener", "removeListener" -> null;
            case "toString" -> "AttachWorkspace";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (OWLModelManager) Proxy.newProxyInstance(
                OntologyDocumentAttachTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, handler);
    }
}
