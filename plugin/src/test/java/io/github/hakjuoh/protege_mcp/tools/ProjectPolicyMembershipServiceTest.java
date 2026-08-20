package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class ProjectPolicyMembershipServiceTest {

    @Test
    void addsAndRemovesPhysicalFilesAndOntologyDocumentBindings(@TempDir Path root)
            throws Exception {
        Path ontology = Files.writeString(root.resolve("ontology.ttl"), "");
        Path alternate = Files.writeString(root.resolve("ontology.rdf"), "");
        Path catalog = Files.writeString(root.resolve("catalog-v001.xml"), "<catalog/>");
        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("workspace", "https://example.org/root")
                        .replace("version: 1", "version: 3")
                        + "reasoning:\n  reasoner: HermiT\n");
        ProjectPolicy initial = ProjectPolicyLoader.load(policyPath, ontology);
        assertTrue(initial.valid(), initial.issues().toString());

        ProjectPolicyMembershipService.setMembership(initial, ontology,
                "https://example.org/root", true);
        ProjectPolicy withOntology = ProjectPolicyLoader.load(policyPath, ontology);
        assertTrue(withOntology.valid(), withOntology.issues().toString());
        Map<String, Object> workspace = object(withOntology.effective().get("workspace"));
        assertEquals(List.of("ontology.ttl"), workspace.get("files"));
        assertTrue(String.valueOf(workspace.get("ontologies"))
                .contains("https://example.org/root"));

        ProjectPolicyMembershipService.setMembership(withOntology, alternate,
                "https://example.org/root", true);
        ProjectPolicy withAlternate = ProjectPolicyLoader.load(policyPath, ontology);
        assertTrue(String.valueOf(object(withAlternate.effective().get("workspace"))
                .get("ontologies")).contains("ontology.rdf"));

        ProjectPolicyMembershipService.setMembership(withAlternate, catalog, null, true);
        ProjectPolicy withCatalog = ProjectPolicyLoader.load(policyPath, ontology);
        assertEquals(List.of("catalog-v001.xml", "ontology.rdf", "ontology.ttl"),
                object(withCatalog.effective().get("workspace")).get("files"));

        ProjectPolicyMembershipService.setMembership(withCatalog, ontology,
                "https://example.org/root", false);
        ProjectPolicy removed = ProjectPolicyLoader.load(policyPath, ontology);
        Map<String, Object> removedWorkspace = object(removed.effective().get("workspace"));
        assertEquals(List.of("catalog-v001.xml", "ontology.rdf"), removedWorkspace.get("files"));
        assertTrue(String.valueOf(removedWorkspace.get("ontologies"))
                .contains("https://example.org/root"));
        assertFalse(String.valueOf(removedWorkspace.get("ontologies"))
                .contains("ontology.ttl"));
    }

    @Test
    void refusesToOverwriteAChangedPolicySnapshot(@TempDir Path root) throws Exception {
        Path ontology = Files.writeString(root.resolve("ontology.ttl"), "");
        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("workspace", "https://example.org/root")
                        .replace("version: 1", "version: 3")
                        + "reasoning:\n  reasoner: HermiT\n");
        ProjectPolicy stale = ProjectPolicyLoader.load(policyPath, ontology);
        Path concurrent = Files.writeString(root.resolve("concurrent.ttl"), "");
        ProjectPolicyMembershipService.setMembership(stale, concurrent, null, true);
        String changed = Files.readString(policyPath);

        assertThrows(java.io.IOException.class, () ->
                ProjectPolicyMembershipService.setMembership(stale, ontology, null, true));
        assertEquals(changed, Files.readString(policyPath));
    }

    @Test
    void concurrentMembershipEditsNeverOverwriteEachOther(@TempDir Path root) throws Exception {
        Path first = Files.writeString(root.resolve("first.ttl"), "");
        Path second = Files.writeString(root.resolve("second.ttl"), "");
        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("workspace", "https://example.org/root")
                        .replace("version: 1", "version: 3")
                        + "reasoning:\n  reasoner: HermiT\n");
        ProjectPolicy snapshot = ProjectPolicyLoader.load(policyPath, first);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> edit(snapshot, first, start, applied, rejected));
            var two = executor.submit(() -> edit(snapshot, second, start, applied, rejected));
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, applied.get());
        assertEquals(1, rejected.get());
        ProjectPolicy result = ProjectPolicyLoader.load(policyPath, first);
        assertTrue(result.valid(), result.issues().toString());
        assertEquals(1, strings(object(result.effective().get("workspace")).get("files")).size());
    }

    private static void edit(ProjectPolicy snapshot, Path file, CountDownLatch start,
            AtomicInteger applied, AtomicInteger rejected) {
        try {
            start.await();
            ProjectPolicyMembershipService.setMembership(snapshot, file, null, true);
            applied.incrementAndGet();
        } catch (java.io.IOException expected) {
            rejected.incrementAndGet();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) { return (List<String>) value; }
}
