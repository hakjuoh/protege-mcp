package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ProjectWorkspaceServiceTest {

    @Test
    void showsAllFilesAndSortsHiddenDirectoriesThenDirectoriesThenFiles(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve(".hidden"));
        Files.createDirectories(root.resolve("Zeta"));
        Files.createDirectories(root.resolve("alpha"));
        Files.writeString(root.resolve("ontology.ttl"), "");
        Files.writeString(root.resolve("catalog-v001.xml"), "<catalog/>");
        Files.writeString(root.resolve("Alpha.rdf"), "");
        Files.writeString(root.resolve("z.txt"), "");
        Files.writeString(root.resolve(".DS_Store"), "ignored");

        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("workspace", "https://example.org/root")
                        .replace("version: 1", "version: 3")
                        + "workspace:\n  files: [ontology.ttl, catalog-v001.xml]\n"
                        + "reasoning:\n  reasoner: HermiT\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, root.resolve("ontology.ttl"));
        assertTrue(policy.valid(), policy.issues().toString());

        ProjectWorkspaceSnapshot snapshot = new ProjectWorkspaceService().build(
                policy, root.resolve("ontology.ttl"), List.of());
        assertEquals("workspace", snapshot.projectName());
        assertEquals(List.of(".hidden", ".protege-mcp", "alpha", "Zeta", "Alpha.rdf",
                "catalog-v001.xml", "ontology.ttl", "ro-crate-metadata.json", "z.txt"),
                snapshot.files().stream().map(ProjectWorkspaceSnapshot.ProjectFile::name).toList());
        assertFalse(snapshot.files().stream().anyMatch(file -> ".DS_Store".equals(file.name())));
        assertTrue(snapshot.files().stream().filter(file -> "ontology.ttl".equals(file.name()))
                .findFirst().orElseThrow().workspaceMember());
        assertEquals(ProjectWorkspaceSnapshot.FileKind.XML_CATALOG,
                snapshot.files().stream().filter(file -> "catalog-v001.xml".equals(file.name()))
                        .findFirst().orElseThrow().kind());
    }

    @Test
    void keepsOntologyNamespacesSeparateAndGroupsMultipleDocuments(@TempDir Path root)
            throws Exception {
        Path first = Files.writeString(root.resolve("first.ttl"), "");
        Path second = Files.writeString(root.resolve("second.rdf"), "");
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/shared"));
        List<ProjectWorkspaceService.LoadedOntology> loaded = List.of(
                new ProjectWorkspaceService.LoadedOntology(ontology, "https://example.org/shared",
                        first.toUri().toString(), first, true, 1),
                new ProjectWorkspaceService.LoadedOntology(ontology, "https://example.org/shared",
                        second.toUri().toString(), second, true, 1),
                new ProjectWorkspaceService.LoadedOntology(ontology,
                        "http://purl.obolibrary.org/obo/bfo/2020/bfo.owl",
                        root.resolve("missing-bfo.owl").toUri().toString(),
                        root.resolve("missing-bfo.owl"), false, 0));

        ProjectWorkspaceSnapshot snapshot = new ProjectWorkspaceService().build(
                ProjectPolicy.notFound(), first, loaded, ontology);
        assertEquals(ontology, snapshot.activeOntology());
        assertEquals(1, snapshot.projectOntologies().size());
        assertEquals(2, snapshot.projectOntologies().get(0).documents().size());
        assertEquals(1, snapshot.externalOntologies().size());
        assertEquals(ProjectWorkspaceSnapshot.OntologyLocation.UNRESOLVED,
                snapshot.externalOntologies().get(0).location());
    }

    @Test
    void listsOneNamespaceWhenItsDocumentsSpanProjectAndExternalLocations(@TempDir Path root,
            @TempDir Path externalRoot) throws Exception {
        Path local = Files.writeString(root.resolve("local.ttl"), "");
        Path external = Files.writeString(externalRoot.resolve("external.rdf"), "");
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/shared"));
        List<ProjectWorkspaceService.LoadedOntology> loaded = List.of(
                new ProjectWorkspaceService.LoadedOntology(ontology, "https://example.org/shared",
                        local.toUri().toString(), local, true, 1),
                new ProjectWorkspaceService.LoadedOntology(ontology, "https://example.org/shared",
                        external.toUri().toString(), external, true, 1));

        ProjectWorkspaceSnapshot snapshot = new ProjectWorkspaceService().build(
                ProjectPolicy.notFound(), local, loaded);
        assertEquals(1, snapshot.projectOntologies().size());
        assertTrue(snapshot.externalOntologies().isEmpty());
        assertEquals(2, snapshot.projectOntologies().get(0).documents().size());
    }

    @Test
    void symlinkedOutsideOntologyIsNonActionableAndClassifiedExternal(@TempDir Path root,
            @TempDir Path outside) throws Exception {
        Path external = Files.writeString(outside.resolve("external.owl"), "");
        Path link = root.resolve("linked.owl");
        Files.createSymbolicLink(link, external);
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/external"));

        ProjectWorkspaceSnapshot snapshot = new ProjectWorkspaceService().build(
                ProjectPolicy.notFound(), root.resolve("anchor.ttl"), List.of(
                        new ProjectWorkspaceService.LoadedOntology(ontology,
                                "https://example.org/external", link.toUri().toString(), link,
                                true, 1)));

        ProjectWorkspaceSnapshot.ProjectFile linked = snapshot.files().stream()
                .filter(file -> file.name().equals("linked.owl")).findFirst().orElseThrow();
        assertEquals(ProjectWorkspaceSnapshot.FileKind.SYMLINK, linked.kind());
        assertEquals(null, linked.loadedOntology());
        assertTrue(snapshot.projectOntologies().isEmpty());
        assertEquals(ProjectWorkspaceSnapshot.OntologyLocation.EXTERNAL,
                snapshot.externalOntologies().get(0).location());
        assertFalse(ChatView.canOpenProjectFile(linked));
    }

    @Test
    void reportsRatherThanSilentlyContinuingPastScanLimit(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "");
        Files.writeString(root.resolve("b.txt"), "");
        Files.writeString(root.resolve("c.txt"), "");

        ProjectWorkspaceSnapshot snapshot = new ProjectWorkspaceService(2).build(
                ProjectPolicy.notFound(), root.resolve("anchor.ttl"), List.of());

        assertEquals(2, snapshot.files().size());
        assertTrue(snapshot.filesTruncated());
    }
}
