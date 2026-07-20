package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
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
