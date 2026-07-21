package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

class EntitySearchPolicyCacheTest {

    private static final String ONTOLOGY = "https://example.org/ontology";

    @Test
    void resolvesCurieSettingsAndReloadsOnlyWhenPolicyContentChanges(@TempDir Path project)
            throws Exception {
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        Path document = project.resolve("ontology.ttl");
        ProjectPolicyTools.PolicyContext live = new ProjectPolicyTools.PolicyContext(
                document, ONTOLOGY, List.of("HermiT"), null);
        EntitySearchPolicyCache cache = new EntitySearchPolicyCache();

        write(policyPath, "alias", "en", "de");
        EntitySearch.Settings first = cache.resolve(live);
        assertTrue(first.synonymProperties().contains(
                IRI.create("https://example.org/annotations/alias")));
        assertEquals(List.of("en"), first.preferredLanguages());
        assertEquals(List.of("de"), first.fallbackLanguages());
        assertEquals(1, cache.loads());

        assertEquals(first, cache.resolve(live));
        assertEquals(1, cache.loads(), "unchanged policy bytes reuse the parsed settings");

        write(policyPath, "otherAlias", "ko", "und");
        EntitySearch.Settings changed = cache.resolve(live);
        assertTrue(changed.synonymProperties().contains(
                IRI.create("https://example.org/annotations/otherAlias")));
        assertEquals(List.of("ko"), changed.preferredLanguages());
        assertEquals(2, cache.loads());
    }

    @Test
    void onlyInvalidSearchBlockOrMissingPolicyUsesSafeStandardDefaults(@TempDir Path project)
            throws Exception {
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        Path document = project.resolve("ontology.ttl");
        ProjectPolicyTools.PolicyContext live = new ProjectPolicyTools.PolicyContext(
                document, ONTOLOGY, List.of("HermiT"), null);
        EntitySearchPolicyCache cache = new EntitySearchPolicyCache();
        String unrelatedInvalid = ProjectPolicyFixtures.minimalPolicy("unrelated-invalid", ONTOLOGY)
                + "prefixes:\n  ex: https://example.org/annotations/\n"
                + "entity_search:\n  synonym_properties: [ex:stillUsable]\n";
        ProjectPolicyFixtures.writePolicy(policyPath, unrelatedInvalid);
        assertTrue(cache.resolve(live).synonymProperties().contains(
                IRI.create("https://example.org/annotations/stillUsable")),
                "an unrelated QC error cannot invalidate a locally valid read-only search block");

        String invalid = ProjectPolicyFixtures.minimalPolicy("invalid-search", ONTOLOGY)
                + "prefixes:\n  skos: http://www.w3.org/2004/02/skos/core#\n"
                + "entity_search:\n"
                + "  preferred_properties: [skos:prefLabel]\n"
                + "  synonym_properties: [skos:prefLabel]\n";
        ProjectPolicyFixtures.writePolicy(policyPath, invalid);

        assertEquals(EntitySearch.Settings.defaults(), cache.resolve(live),
                "an invalid entity_search block itself must fall back to safe defaults");

        java.nio.file.Files.delete(policyPath);
        assertEquals(EntitySearch.Settings.defaults(), cache.resolve(live));
    }

    @Test
    void discoveredPolicySymlinksCannotSupplySearchSettings(@TempDir Path temp) throws Exception {
        Path external = temp.resolve("external/project.yaml");
        write(external, "externalAlias", "en", "und");
        Path project = temp.resolve("project");
        Path policyLink = project.resolve(".protege-mcp/project.yaml");
        Files.createDirectories(policyLink.getParent());
        try {
            Files.createSymbolicLink(policyLink, external);
        } catch (UnsupportedOperationException | IOException unsupported) {
            Assumptions.abort("symbolic links are unavailable: " + unsupported);
        }
        ProjectPolicyTools.PolicyContext live = new ProjectPolicyTools.PolicyContext(
                project.resolve("ontology.ttl"), ONTOLOGY, List.of("HermiT"), null);
        EntitySearchPolicyCache cache = new EntitySearchPolicyCache();

        assertEquals(EntitySearch.Settings.defaults(), cache.resolve(live));

        Files.delete(policyLink);
        Files.delete(policyLink.getParent());
        Files.createSymbolicLink(project.resolve(".protege-mcp"), external.getParent());
        assertEquals(EntitySearch.Settings.defaults(), cache.resolve(live));
    }

    @Test
    void policyDirectorySwapBetweenDiscoveryAndCaptureFallsBackToDefaults(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path policyDirectory = project.resolve(".protege-mcp");
        Path policyPath = policyDirectory.resolve("project.yaml");
        write(policyPath, "trustedAlias", "en", "und");
        Path savedDirectory = project.resolve(".protege-mcp-before-swap");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        write(outside.resolve("project.yaml"), "outsideAlias", "ko", "und");
        ProjectPolicyTools.PolicyContext live = new ProjectPolicyTools.PolicyContext(
                project.resolve("ontology.ttl"), ONTOLOGY, List.of("HermiT"), null);
        EntitySearchPolicyCache cache = new EntitySearchPolicyCache(() -> {
            Files.move(policyDirectory, savedDirectory);
            Files.createSymbolicLink(policyDirectory, outside);
        });

        try {
            assertEquals(EntitySearch.Settings.defaults(), cache.resolve(live));
            assertEquals(0, cache.loads(), "an escaped source is never parsed or cached");
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.abort("symbolic links are unavailable: " + unsupported);
        } finally {
            if (Files.isSymbolicLink(policyDirectory)) Files.deleteIfExists(policyDirectory);
            if (Files.exists(savedDirectory)) Files.move(savedDirectory, policyDirectory);
        }
    }

    @Test
    void samePathOrdinaryProjectReplacementCannotSupplyCachedSettings(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        write(policyPath, "trustedAlias", "en", "und");
        Path saved = temp.resolve("project-before-replacement");
        ProjectPolicyTools.PolicyContext live = new ProjectPolicyTools.PolicyContext(
                project.resolve("ontology.ttl"), ONTOLOGY, List.of("HermiT"), null);
        EntitySearchPolicyCache cache = new EntitySearchPolicyCache(() -> {
            Files.move(project, saved);
            Files.createDirectories(policyPath.getParent());
            Files.createLink(policyPath, saved.resolve(".protege-mcp/project.yaml"));
        });

        try {
            assertEquals(EntitySearch.Settings.defaults(), cache.resolve(live));
            assertEquals(0, cache.loads());
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.abort("hard links are unavailable: " + unsupported);
        }
    }

    @Test
    void samePathPolicyFileReplacementCannotSupplyCachedSettings(@TempDir Path project)
            throws Exception {
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        write(policyPath, "trustedAlias", "en", "und");
        Path savedPolicy = policyPath.resolveSibling("project-before-replacement.yaml");
        var originalTime = Files.getLastModifiedTime(policyPath);
        ProjectPolicyTools.PolicyContext live = new ProjectPolicyTools.PolicyContext(
                project.resolve("ontology.ttl"), ONTOLOGY, List.of("HermiT"), null);
        EntitySearchPolicyCache cache = new EntitySearchPolicyCache(() -> {
            Files.move(policyPath, savedPolicy);
            Files.copy(savedPolicy, policyPath);
            Files.setLastModifiedTime(policyPath, originalTime);
        });

        assertEquals(EntitySearch.Settings.defaults(), cache.resolve(live));
        assertEquals(0, cache.loads(), "a replacement source inode is never parsed or cached");
    }

    private static void write(Path policyPath, String local, String preferred, String fallback)
            throws Exception {
        String yaml = ProjectPolicyFixtures.minimalPolicy("search-cache", ONTOLOGY)
                + "prefixes:\n  ex: https://example.org/annotations/\n"
                + "entity_search:\n"
                + "  synonym_properties: [ex:" + local + "]\n"
                + "  preferred_languages: [" + preferred + "]\n"
                + "  fallback_languages: [" + fallback + "]\n"
                + "reasoning:\n  reasoner: HermiT\n";
        ProjectPolicyFixtures.writePolicy(policyPath, yaml);
    }
}
