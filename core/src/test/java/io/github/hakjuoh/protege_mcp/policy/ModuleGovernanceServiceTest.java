package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.core.qc.ModuleGovernanceService;

class ModuleGovernanceServiceTest {

    @Test
    void inspectsSnapshotPathsInsteadOfReopeningPolicyPaths(@TempDir Path temp) throws Exception {
        Path owner = temp.resolve("captured-owner.ttl");
        Path foreign = temp.resolve("captured-foreign.ttl");
        writeModule(owner, "https://example.org/owner",
                "<https://example.org/ns/Owned> a owl:Class .");
        writeModule(foreign, "https://example.org/foreign",
                "<https://example.org/ns/Wrong> a owl:Class .",
                "<https://example.org/ns/Wrong> rdfs:label \"foreign definition\" .");
        ProjectPolicy policy = policy(temp, List.of(
                module("https://example.org/owner", "owner.ttl", "https://example.org/ns/"),
                module("https://example.org/foreign", "foreign.ttl", null)));

        List<Map<String, Object>> checks = ModuleGovernanceService.moduleChecks(
                policy, List.of(owner, foreign), 25);

        assertEquals(1, checks.size());
        assertEquals("module_owned_namespace", checks.get(0).get("id"));
        assertEquals(1, checks.get(0).get("count"));
        assertTrue(String.valueOf(checks.get(0)).contains(foreign.toString()));
        assertTrue(checks.get(0).containsKey(ModuleGovernanceService.ATTRIBUTION_KEY));
    }

    @Test
    void failsClosedWhenCapturedModuleCoordinatesAreIncomplete(@TempDir Path temp) {
        ProjectPolicy policy = policy(temp, List.of(
                module("https://example.org/owner", "owner.ttl", "https://example.org/ns/")));

        List<Map<String, Object>> checks = ModuleGovernanceService.moduleChecks(
                policy, List.of(), -1);

        assertEquals("module_inspection_failed", checks.get(0).get("id"));
        assertEquals(1, checks.get(0).get("count"));
        assertEquals(List.of(), checks.get(0).get("examples"));
    }

    @Test
    void importRowsHaveStableIdentitiesAndDrainTheirPrivateChannel() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("path", List.of("a", "b", "a"));
        List<Map<String, Object>> checks = ModuleGovernanceService.importChecks(
                List.of(cycle), List.of(Map.of("ontology_iri", "https://example.org/o")), 25);

        assertEquals(List.of("import_cycle", "import_identity_conflict"),
                checks.stream().map(row -> String.valueOf(row.get("id"))).toList());
        Set<String> identities = new java.util.LinkedHashSet<>();
        Map<String, Object> mutable = new LinkedHashMap<>(checks.get(0));
        ModuleGovernanceService.drainAttributionIdentities(mutable, identities);
        assertFalse(mutable.containsKey(ModuleGovernanceService.ATTRIBUTION_KEY));
        assertEquals(1, identities.size());
    }

    @Test
    void namespaceMatchingIsBoundaryAwareAndMostSpecific() {
        assertTrue(ModuleGovernanceService.ownsEntity(
                "https://example.org/ns", "https://example.org/ns#Term"));
        assertFalse(ModuleGovernanceService.ownsEntity(
                "https://example.org/ns", "https://example.org/ns-ext/Term"));
        assertEquals("https://example.org/ns/specific/",
                ModuleGovernanceService.mostSpecificOwnedNamespace(
                        "https://example.org/ns/specific/Term",
                        Set.of("https://example.org/ns/", "https://example.org/ns/specific/")));
    }

    private static ProjectPolicy policy(Path root, List<Map<String, Object>> modules) {
        return new ProjectPolicy(true, "explicit", root.resolve("policy.yaml"), root,
                "sha256:test", Map.of("modules", modules),
                Map.of("modules", modules.stream()
                        .map(module -> root.resolve(String.valueOf(module.get("path")))).toList()),
                List.of());
    }

    private static Map<String, Object> module(String iri, String path, String namespace) {
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("ontology_iri", iri);
        module.put("path", path);
        module.put("owned_namespaces", namespace == null ? List.of() : List.of(namespace));
        return module;
    }

    private static void writeModule(Path path, String ontologyIri, String... statements)
            throws Exception {
        StringBuilder turtle = new StringBuilder(
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "<" + ontologyIri + "> a owl:Ontology .\n");
        for (String statement : statements) turtle.append(statement).append('\n');
        Files.writeString(path, turtle.toString());
    }
}
