package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

class ProjectPolicyVersioningTest {

    @Test
    void v1NormalizationAndDigestRemainStableAndMigrationIsOutOfBand(@TempDir Path temp)
            throws Exception {
        Path policyPath = temp.resolve("v1.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath, v1("compat"));

        ProjectPolicy first = ProjectPolicyLoader.load(policyPath, null);
        ProjectPolicy second = ProjectPolicyLoader.load(policyPath, null);

        assertTrue(first.valid(), () -> first.issues().toString());
        assertEquals(1, first.version());
        assertEquals("sha256:e3c362372e5252e8d28c950c6ca038fda85760f896301c4e143fe8648e8d359f",
                first.digest(), "v1 default normalization is a published compatibility boundary");
        assertEquals(first.digest(), second.digest());
        assertFalse(first.effective().containsKey("migration"));
        assertFalse(first.effective().containsKey("external_terms"));
        assertNotNull(first.migration());
        assertEquals(1, first.migration().fromVersion());
        assertEquals(2, first.migration().toVersion());
        assertFalse(first.migration().required());
        assertFalse(first.migration().automaticWrite());
        assertFalse(first.migration().diagnosticAffectsDigest());
        assertThrows(UnsupportedOperationException.class,
                () -> first.effective().put("migration", Map.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> imports = (Map<String, Object>) first.effective().get("imports");
        assertThrows(UnsupportedOperationException.class, () -> imports.put("mode", "locked"));
    }

    @Test
    void v2AddsOnlyVersionedDefaultsAndProducesAStableDigest(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("v2.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath, v1("v2").replace("version: 1", "version: 2"));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        ProjectPolicy repeat = ProjectPolicyLoader.load(policyPath, null);

        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(2, policy.version());
        assertNull(policy.migration());
        assertEquals("sha256:f92996204b1e1868dd48bb3a3a9a0f24edb5f8b57131f663ea34a655efdb29ea",
                policy.digest(),
                "v2 default normalization is an immutable 0.8 contract baseline");
        assertEquals(policy.digest(), repeat.digest());
        assertNotEquals(ProjectPolicyLoader.load(writeV1(temp.resolve("other.yaml")), null).digest(),
                policy.digest());
        assertEquals(".protege-mcp/mappings.sssom.tsv",
                object(policy.effective(), "mappings").get("path"));
        assertEquals(2, object(policy.effective(), "jobs").get("workers"));
        assertEquals(false, object(policy.effective(), "materialization").get("allow_source_write"));
        assertEquals(90, object(policy.effective(), "audit").get("retention_days"));
        assertFalse(policy.assets().containsKey("mapping_store"),
                "an absent optional mapping output must not become a captured input asset");
        Path mappingStore = policy.projectRoot()
                        .resolve(".protege-mcp/mappings.sssom.tsv")
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(mappingStore.getParent());
        Files.writeString(mappingStore, "subject_id\tpredicate_id\tobject_id\n");
        ProjectPolicy withStore = ProjectPolicyLoader.load(policyPath, null);
        assertEquals(List.of(mappingStore.toRealPath()), withStore.assets().get("mapping_store"));

        Path explicitPath = temp.resolve("explicit-v2.yaml");
        ProjectPolicyFixtures.writePolicy(explicitPath,
                v1("v2").replace("version: 1", "version: 2") + explicitV2Defaults());
        ProjectPolicy explicit = ProjectPolicyLoader.load(explicitPath, null);
        assertTrue(explicit.valid(), () -> explicit.issues().toString());
        assertEquals(policy.digest(), explicit.digest(),
                "omitted and explicitly authored v2 defaults must normalize identically");
    }

    @Test
    void versionDispatchFailsClosedAndV1CannotSmuggleV2Fields(@TempDir Path temp) throws Exception {
        Path future = temp.resolve("future.yaml");
        Files.writeString(future, v1("future").replace("version: 1", "version: 3"));
        ProjectPolicy unsupported = ProjectPolicyLoader.load(future, null);
        assertFalse(unsupported.valid());
        assertTrue(unsupported.issues().stream().anyMatch(
                issue -> "unsupported_policy_version".equals(issue.code())));

        Path fractional = temp.resolve("fractional.yaml");
        Files.writeString(fractional, v1("fractional").replace("version: 1", "version: 1.5"));
        ProjectPolicy nonIntegral = ProjectPolicyLoader.load(fractional, null);
        assertFalse(nonIntegral.valid());
        assertEquals(0, nonIntegral.version(), "a fractional version must not be truncated");

        Path smuggled = temp.resolve("smuggled.yaml");
        ProjectPolicyFixtures.writePolicy(smuggled, v1("smuggled")
                + "jobs:\n  workers: 1\n");
        ProjectPolicy v1 = ProjectPolicyLoader.load(smuggled, null);
        assertFalse(v1.valid());
        assertNull(v1.migration(), "invalid policies must not receive migration guidance");
        assertTrue(v1.issues().stream().anyMatch(issue -> "schema_invalid".equals(issue.code())));
    }

    @Test
    void v2NestedProviderAndEvidenceDefaultsHaveAFixedGolden(@TempDir Path temp) throws Exception {
        Path partialPath = temp.resolve("provider-partial.yaml");
        ProjectPolicyFixtures.writePolicy(partialPath, providerPolicy(false));
        ProjectPolicy partial = ProjectPolicyLoader.load(partialPath, null);

        assertTrue(partial.valid(), () -> partial.issues().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = (Map<String, Object>) ((List<?>) object(
                partial.effective(), "external_terms").get("providers")).get(0);
        assertEquals(900, provider.get("ttl_seconds"));
        assertEquals("cache_ok", provider.get("freshness"));
        assertEquals(List.of(), provider.get("required_evidence_for"));
        assertEquals(25, provider.get("max_results"));
        assertEquals("fresh_required", object(object(partial.effective(), "validation"),
                "provider_evidence").get("freshness"));
        assertEquals("sha256:f9323ec1f1927e86d6cf9f82d4b438ddc39c79334a23b5d37e39d93a759e752f",
                partial.digest());

        Path explicitPath = temp.resolve("provider-explicit.yaml");
        ProjectPolicyFixtures.writePolicy(explicitPath, providerPolicy(true));
        ProjectPolicy explicit = ProjectPolicyLoader.load(explicitPath, null);
        assertTrue(explicit.valid(), () -> explicit.issues().toString());
        assertEquals(partial.digest(), explicit.digest(),
                "nested omitted and explicit provider defaults must normalize identically");
    }

    @Test
    void v2SemanticCrossFieldAndDuplicateProviderChecksAreStable(@TempDir Path temp) throws Exception {
        Path duplicate = temp.resolve("duplicate.yaml");
        ProjectPolicyFixtures.writePolicy(duplicate,
                v1("duplicate").replace("version: 1", "version: 2")
                + "external_terms:\n"
                + "  providers:\n"
                + "    - {id: ols, profile: ols4, enabled: true, origin_alias: ebi}\n"
                + "    - {id: ols, profile: ols4, enabled: false, origin_alias: mirror}\n");
        ProjectPolicy providers = ProjectPolicyLoader.load(duplicate, null);
        assertFalse(providers.valid());
        assertTrue(providers.issues().stream().anyMatch(
                issue -> "provider_id_duplicate".equals(issue.code())));

        Path quotas = temp.resolve("quotas.yaml");
        ProjectPolicyFixtures.writePolicy(quotas,
                v1("quotas").replace("version: 1", "version: 2")
                + "jobs:\n"
                + "  active_per_principal: 8\n"
                + "  retained_per_principal: 4\n"
                + "  retained_per_backend: 2\n");
        ProjectPolicy jobs = ProjectPolicyLoader.load(quotas, null);
        assertTrue(jobs.valid(), () -> "active and terminal-retention quotas tighten independently: "
                + jobs.issues());

        Path unknownProvider = temp.resolve("unknown-provider.yaml");
        ProjectPolicyFixtures.writePolicy(unknownProvider,
                v1("unknown-provider").replace("version: 1", "version: 2")
                        .replace("required_stages: [governance, structural]",
                                "required_stages: [governance, structural, provider_evidence]")
                + "  provider_evidence: {providers: [missing]}\n"
                + "external_terms:\n"
                + "  providers:\n"
                + "    - {id: ols, profile: ols4, enabled: true, origin_alias: ebi}\n"
                + "mappings:\n"
                + "  many_to_one_rules:\n"
                + "    - {predicate: 'https://example.org/match', subject_providers: [typo], "
                + "subject_ontologies: ['missing:Scope']}\n");
        ProjectPolicy references = ProjectPolicyLoader.load(unknownProvider, null);
        assertFalse(references.valid());
        assertEquals(2, references.issues().stream()
                .filter(issue -> "provider_id_unknown".equals(issue.code())).count());
        assertTrue(references.issues().stream().anyMatch(
                issue -> "prefix_unknown".equals(issue.code())
                        && issue.path().contains("subject_ontologies")));

        Path disabledProvider = temp.resolve("disabled-provider.yaml");
        ProjectPolicyFixtures.writePolicy(disabledProvider,
                v1("disabled-provider").replace("version: 1", "version: 2")
                        .replace("required_stages: [governance, structural]",
                                "required_stages: [governance, structural, provider_evidence]")
                + "  provider_evidence: {providers: [ols]}\n"
                + "external_terms:\n"
                + "  providers:\n"
                + "    - {id: ols, profile: ols4, enabled: false, origin_alias: ebi}\n");
        ProjectPolicy disabled = ProjectPolicyLoader.load(disabledProvider, null);
        assertFalse(disabled.valid());
        assertTrue(disabled.issues().stream().anyMatch(
                issue -> "provider_disabled_for_evidence".equals(issue.code())));

        Path collision = temp.resolve("collision.yaml");
        ProjectPolicyFixtures.writePolicy(collision,
                v1("collision").replace("version: 1", "version: 2")
                        + "mappings:\n  path: ontology.ttl\n");
        ProjectPolicy colliding = ProjectPolicyLoader.load(collision, null);
        assertFalse(colliding.valid());
        assertTrue(colliding.issues().stream().anyMatch(
                issue -> "mapping_path_collision".equals(issue.code())));

        Path childOfFile = temp.resolve("child-of-file.yaml");
        ProjectPolicyFixtures.writePolicy(childOfFile,
                v1("child-of-file").replace("version: 1", "version: 2")
                        + "mappings:\n  path: ontology.ttl/new.tsv\n");
        ProjectPolicy impossibleParent = ProjectPolicyLoader.load(childOfFile, null);
        assertFalse(impossibleParent.valid());
        assertTrue(impossibleParent.issues().stream().anyMatch(
                issue -> "path_parent_not_directory".equals(issue.code())));

        Path releaseOverlap = temp.resolve("release-overlap.yaml");
        ProjectPolicyFixtures.writePolicy(releaseOverlap,
                v1("release-overlap").replace("version: 1", "version: 2")
                        + "release:\n  output_dir: release\n"
                        + "mappings:\n  path: release/mappings.tsv\n");
        ProjectPolicy overlapping = ProjectPolicyLoader.load(releaseOverlap, null);
        assertFalse(overlapping.valid());
        assertTrue(overlapping.issues().stream().anyMatch(
                issue -> "mapping_path_collision".equals(issue.code())));

    }

    @Test
    void mappingOutputRejectsFinalAndAncestorSymlinks(@TempDir Path temp) throws Exception {
        try {
            Files.createSymbolicLink(temp.resolve("mapping-link.tsv"),
                    temp.resolve("outside/missing.tsv"));
            Files.createSymbolicLink(temp.resolve("mapping-dir"),
                    temp.resolve("outside/missing-dir"));
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("symbolic links are unavailable: " + unsupported);
        }

        Path dangling = temp.resolve("dangling.yaml");
        ProjectPolicyFixtures.writePolicy(dangling,
                v1("dangling").replace("version: 1", "version: 2")
                        + "mappings:\n  path: mapping-link.tsv\n");
        ProjectPolicy danglingOutput = ProjectPolicyLoader.load(dangling, null);
        assertFalse(danglingOutput.valid());
        assertTrue(danglingOutput.issues().stream().anyMatch(
                issue -> "symlink_escape".equals(issue.code())));

        Path danglingParent = temp.resolve("dangling-parent.yaml");
        ProjectPolicyFixtures.writePolicy(danglingParent,
                v1("dangling-parent").replace("version: 1", "version: 2")
                        + "mappings:\n  path: mapping-dir/store.tsv\n");
        ProjectPolicy escapedParent = ProjectPolicyLoader.load(danglingParent, null);
        assertFalse(escapedParent.valid());
        assertTrue(escapedParent.issues().stream().anyMatch(
                issue -> "symlink_escape".equals(issue.code())));
    }

    @Test
    void mappingOutputRejectsAHardlinkToAReservedAsset(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("hardlink.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                v1("hardlink").replace("version: 1", "version: 2")
                        + "mappings:\n  path: mapping-hardlink.tsv\n");
        try {
            Files.createLink(temp.resolve("mapping-hardlink.tsv"), temp.resolve("ontology.ttl"));
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            Assumptions.abort("hard links are unavailable: " + unsupported);
        }

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertFalse(policy.valid());
        assertTrue(policy.issues().stream().anyMatch(
                issue -> "mapping_path_collision".equals(issue.code())));
    }

    @Test
    void invalidPolicyContentAndDiagnosticsAreBoundedAndSecretSafe(@TempDir Path temp)
            throws Exception {
        String canary = "policy-secret-canary-7X9";
        Path secret = temp.resolve("secret.yaml");
        ProjectPolicyFixtures.writePolicy(secret,
                v1("secret").replace("version: 1", "version: 2")
                        + "external_terms:\n"
                        + "  providers:\n"
                        + "    - {id: ols, profile: ols4, enabled: true, origin_alias: ebi, "
                        + "endpoint: 'https://user:" + canary + "@example.org', api_key: '"
                        + canary + "'}\n");
        ProjectPolicy rejected = ProjectPolicyLoader.load(secret, null);
        assertFalse(rejected.valid());
        assertTrue(rejected.effective().isEmpty(), "schema-invalid authored content is not public output");
        assertFalse(rejected.issues().toString().contains(canary));
        assertTrue(rejected.issues().stream().allMatch(issue -> issue.message().length() <= 2_048));

        String semanticCanary = "semantic-secret-canary-4Q2";
        Path semantic = temp.resolve("semantic-secret.yaml");
        ProjectPolicyFixtures.writePolicy(semantic, v1("semantic-secret").replace(
                "https://example.org/ontology",
                "https://user:" + semanticCanary + "@example.org/ontology"));
        ProjectPolicy semanticRejected = ProjectPolicyLoader.load(semantic, null,
                "https://example.org/active", null);
        assertFalse(semanticRejected.valid());
        assertTrue(semanticRejected.issues().stream().anyMatch(
                issue -> "root_ontology_mismatch".equals(issue.code())));
        assertFalse(semanticRejected.issues().toString().contains(semanticCanary),
                "semantic diagnostics must redact URL userinfo too");
        assertTrue(semanticRejected.issues().stream()
                .allMatch(issue -> issue.message().length() <= 2_048));

        StringBuilder oversized = new StringBuilder("version: 2\n");
        for (int i = 0; i < 6_000; i++) oversized.append("unknown_").append(i).append(": x\n");
        Path structure = temp.resolve("structure.yaml");
        Files.writeString(structure, oversized);
        ProjectPolicy boundedStructure = ProjectPolicyLoader.load(structure, null);
        assertTrue(boundedStructure.issues().stream().anyMatch(
                issue -> "policy_structure_too_large".equals(issue.code())));

        StringBuilder nullItems = new StringBuilder(ProjectPolicyFixtures.minimalPolicy(
                "null-items", "https://example.org/ontology").replace("version: 1", "version: 2"));
        nullItems.append("validation:\n  structural:\n    disabled:\n");
        for (int i = 0; i < 10_001; i++) nullItems.append("    - null\n");
        Path nullItemsPath = temp.resolve("null-items.yaml");
        Files.writeString(nullItemsPath, nullItems);
        ProjectPolicy boundedNullItems = ProjectPolicyLoader.load(nullItemsPath, null);
        assertTrue(boundedNullItems.issues().stream().anyMatch(
                issue -> "policy_structure_too_large".equals(issue.code())),
                "null collection entries must count toward the v2 node budget");

        String schemaCanary = "schema-secret-canary-8M5";
        Path unknownKey = temp.resolve("unknown-key.yaml");
        ProjectPolicyFixtures.writePolicy(unknownKey,
                v1("unknown-key").replace("version: 1", "version: 2")
                        + schemaCanary + ": forbidden\n");
        ProjectPolicy schemaRejected = ProjectPolicyLoader.load(unknownKey, null);
        assertFalse(schemaRejected.valid());
        assertFalse(schemaRejected.issues().toString().contains(schemaCanary));
        assertTrue(schemaRejected.issues().stream().anyMatch(issue ->
                "schema_invalid".equals(issue.code())
                        && issue.message().equals(
                                "Policy does not conform to project-policy v2 schema.")));

        StringBuilder invalidV1Array = new StringBuilder(v1("invalid-v1-array"));
        invalidV1Array.append("annotations:\n  required: [");
        for (int i = 0; i < 30_000; i++) invalidV1Array.append("null,");
        invalidV1Array.append("null]\n");
        Path invalidV1 = temp.resolve("invalid-v1-array.yaml");
        Files.writeString(invalidV1, invalidV1Array);
        ProjectPolicy failFastV1 = ProjectPolicyLoader.load(invalidV1, null);
        assertFalse(failFastV1.valid());
        assertEquals(1, failFastV1.issues().size(),
                "v1 schema validation must fail fast without allocating one issue per item");

        Path aliases = temp.resolve("alias.yaml");
        Files.writeString(aliases, "version: 2\nproject_id: &id aliased\n"
                + "root_ontology: https://example.org/ontology\n"
                + "copied_id: *id\n");
        ProjectPolicy aliasRejected = ProjectPolicyLoader.load(aliases, null);
        assertFalse(aliasRejected.valid());
        assertTrue(aliasRejected.issues().stream().anyMatch(
                issue -> "yaml_invalid".equals(issue.code())));

        String yamlCanary = "yaml-secret-canary-2H7";
        Path duplicateKey = temp.resolve("duplicate-key.yaml");
        Files.writeString(duplicateKey, "version: 2\n" + yamlCanary + ": first\n"
                + yamlCanary + ": second\n");
        ProjectPolicy duplicateRejected = ProjectPolicyLoader.load(duplicateKey, null);
        assertFalse(duplicateRejected.valid());
        assertFalse(duplicateRejected.issues().toString().contains(yamlCanary));
        assertTrue(duplicateRejected.issues().stream().anyMatch(issue ->
                "yaml_invalid".equals(issue.code())
                        && "Policy YAML could not be parsed safely.".equals(issue.message())));

        StringBuilder manyModules = new StringBuilder(v1("many-issues"));
        manyModules.append("modules:\n");
        for (int i = 0; i < 200; i++) {
            manyModules.append("  - {ontology_iri: 'https://example.org/module/")
                    .append(i).append("', path: 'missing-").append(i).append(".ttl'}\n");
        }
        Path issuesPath = temp.resolve("many-issues.yaml");
        ProjectPolicyFixtures.writePolicy(issuesPath, manyModules.toString());
        ProjectPolicy bounded = ProjectPolicyLoader.load(issuesPath, null);
        assertEquals(ProjectPolicyLoader.MAX_POLICY_ISSUES, bounded.issues().size());
        assertEquals("policy_issues_truncated",
                bounded.issues().get(bounded.issues().size() - 1).code());
    }

    @Test
    void v1AcceptanceIsNotRetrofittedWithTheVersionTwoNodeBudget(@TempDir Path temp)
            throws Exception {
        StringBuilder legacy = new StringBuilder(v1("large-v1"));
        legacy.append("prefixes:\n");
        for (int i = 0; i < 5_100; i++) {
            legacy.append("  p").append(i).append(": https://example.org/prefix/")
                    .append(i).append("/\n");
        }
        Path path = temp.resolve("large-v1.yaml");
        ProjectPolicyFixtures.writePolicy(path, legacy.toString());

        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);

        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(1, policy.version());
    }

    private static Path writeV1(Path path) throws Exception {
        ProjectPolicyFixtures.writePolicy(path, v1("v1-other"));
        return path;
    }

    private static String v1(String id) {
        return ProjectPolicyFixtures.minimalPolicy(id, "https://example.org/ontology")
                + "validation:\n  required_stages: [governance, structural]\n";
    }

    private static String explicitV2Defaults() {
        return "audit:\n"
                + "  retention_days: 90\n"
                + "  max_file_bytes: 10485760\n"
                + "  max_files: 10\n"
                + "external_terms:\n"
                + "  providers: []\n"
                + "mappings:\n"
                + "  path: .protege-mcp/mappings.sssom.tsv\n"
                + "  allowed_predicates: []\n"
                + "  allowed_sources: []\n"
                + "  allowed_licenses: []\n"
                + "  require_license: false\n"
                + "  required_findings: []\n"
                + "  directional_cycle_policy:\n"
                + "    skos:broadMatch: error\n"
                + "    skos:narrowMatch: error\n"
                + "  many_to_one_rules: []\n"
                + "jobs:\n"
                + "  allowed_types: [classification, project_qc, semantic_diff, inference_materialization]\n"
                + "  workers: 2\n"
                + "  queue_capacity: 32\n"
                + "  active_per_principal: 8\n"
                + "  retained_per_principal: 32\n"
                + "  retained_per_backend: 128\n"
                + "  retention_seconds: 3600\n"
                + "materialization:\n"
                + "  allowed_reasoners: []\n"
                + "  allowed_categories: [subclass_axioms, equivalent_class_axioms, class_assertions, property_hierarchy_axioms, object_property_assertions, data_property_assertions]\n"
                + "  allowed_destinations: [new_ontology, project_file]\n"
                + "  allow_source_write: false\n"
                + "  max_axioms_per_category: 50000\n"
                + "  max_axioms_total: 50000\n"
                + "  max_bytes: 67108864\n"
                + "  timeout_ms: 120000\n";
    }

    private static String providerPolicy(boolean explicitDefaults) {
        String policy = v1("v2-provider").replace("version: 1", "version: 2")
                .replace("required_stages: [governance, structural]",
                        "required_stages: [governance, structural, provider_evidence]")
                + "  provider_evidence:\n"
                + "    providers: [ols]\n"
                + (explicitDefaults ? "    freshness: fresh_required\n" : "")
                + "external_terms:\n"
                + "  providers:\n"
                + "    - id: ols\n"
                + "      profile: ols4\n"
                + "      enabled: true\n"
                + "      origin_alias: ebi\n";
        return explicitDefaults ? policy
                + "      ontologies: []\n"
                + "      languages: []\n"
                + "      ttl_seconds: 900\n"
                + "      freshness: cache_ok\n"
                + "      required_evidence_for: []\n"
                + "      max_results: 25\n" : policy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }
}
