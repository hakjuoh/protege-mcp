package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.core.audit.AuditSettings;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

/** Discovery, strict YAML/schema validation, semantic checks, and path-confinement tests. */
class ProjectPolicyLoaderTest {

    @Test
    void noImplicitPolicyIsACompatibleUnloadedResult(@TempDir Path temp) {
        ProjectPolicy policy = ProjectPolicyLoader.load(null, temp.resolve("ontology.ttl"));
        assertFalse(policy.loaded());
        assertFalse(policy.valid());
        assertEquals("none", policy.discovery());
        assertTrue(policy.issues().isEmpty());
    }

    @Test
    void callerConstraintRejectsExternalPathsBeforeResolvingThem(@TempDir Path temp) throws Exception {
        Path outside = Files.createTempFile("policy-external-", ".rq");
        try {
            Path policyPath = temp.resolve("project.yaml");
            write(policyPath, minimal("external-constraint")
                    + "filesystem:\n  allow_external_paths: true\n"
                    + "validation:\n"
                    + "  required_stages: [invariants]\n"
                    + "  invariants:\n    paths: ['"
                    + outside.toString().replace("'", "''") + "']\n");

            ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null, null, null, true);

            assertFalse(policy.valid());
            assertCode(policy, "external_paths_forbidden");
            assertTrue(policy.assets().getOrDefault("invariants", List.of()).isEmpty(),
                    "caller-denied external assets must not enter the resolved asset set");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void discoversNearestPolicyAndAppliesDeterministicDefaults(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("project");
        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        Files.createDirectories(policyPath.getParent());
        Files.createDirectories(root.resolve("nested"));
        write(policyPath, minimal("example") + "reasoning:\n  reasoner: HermiT\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(null, root.resolve("nested/ontology.ttl"));
        assertTrue(policy.loaded());
        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals("discovered", policy.discovery());
        assertEquals(policyPath.toRealPath(), policy.path());
        // The .protege-mcp directory holds the policy; the project it governs is the parent.
        assertEquals(root.toRealPath(), policy.projectRoot());
        assertEquals("sha256:", policy.digest().substring(0, 7));
        assertEquals("unlocked", object(policy.effective(), "imports").get("mode"));
        assertEquals("ro-crate-1.1",
                object(object(policy.effective(), "interoperability"), "metadata").get("format"));
        assertEquals(List.of("interoperability", "reasoner", "profile", "governance", "structural"),
                object(policy.effective(), "validation").get("required_stages"));
        assertFalse(policy.effective().containsKey("entity_search"),
                "new runtime search defaults must not rewrite an older policy's effective digest");
    }

    @Test
    void omittedRoCrateVersionDefaultsOperationallyToOnePointOne(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, "version: 1\n"
                + "project_id: default-ro-crate\n"
                + "root_ontology: https://example.org/ontology\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  root_artifact: ontology.ttl\n"
                + "  metadata: {}\n"
                + "  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}\n"
                + "reasoning:\n  reasoner: HermiT\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertTrue(policy.valid(), () -> policy.issues().toString());
        Map<String, Object> metadata = object(
                object(policy.effective(), "interoperability"), "metadata");
        assertEquals("ro-crate-1.1", metadata.get("format"));
        assertEquals("ro-crate-metadata.json", metadata.get("path"));
    }

    @Test
    void omittedVersionFollowsARecognizedExistingCrateContext(@TempDir Path temp) throws Exception {
        for (String format : List.of("ro-crate-1.2", "ro-crate-1.3")) {
            Path project = temp.resolve(format);
            Path policyPath = project.resolve("policy.yaml");
            String explicit = "version: 1\n"
                    + "project_id: existing-crate\n"
                    + "root_ontology: https://example.org/ontology\n"
                    + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", format)
                    + "reasoning:\n  reasoner: HermiT\n";
            ProjectPolicyFixtures.writePolicy(policyPath, explicit);
            Files.writeString(policyPath,
                    explicit.replace("    format: " + format + "\n", ""));

            ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

            assertTrue(policy.valid(), () -> format + ": " + policy.issues());
            assertEquals(format, object(
                    object(policy.effective(), "interoperability"), "metadata").get("format"));
        }
    }

    @Test
    void legacyCrateContextsAreNeverSilentlyInferred(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("legacy");
        Path policyPath = project.resolve("policy.yaml");
        String explicit = "version: 1\n"
                + "project_id: legacy-crate\n"
                + "root_ontology: https://example.org/ontology\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + "reasoning:\n  reasoner: HermiT\n";
        ProjectPolicyFixtures.writePolicy(policyPath, explicit);
        // A 1.0-context crate stored under the 1.1 filename with no authored format: the loader
        // must keep the documented 1.1 default and fail loudly, not adopt ro-crate-1.0 (whose
        // format/path pairing the schema rejects when authored).
        Path manifest = project.resolve("ro-crate-metadata.json");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("https://w3id.org/ro/crate/1.1", "https://w3id.org/ro/crate/1.0"));
        Files.writeString(policyPath, explicit.replace("    format: ro-crate-1.1\n", ""));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertFalse(policy.valid());
        assertEquals("ro-crate-1.1", object(
                object(policy.effective(), "interoperability"), "metadata").get("format"));
        assertCode(policy, "interop_context_missing");
    }

    @Test
    void crateProfileViolationsSurfaceAsInteropIssues(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("violated");
        Path policyPath = project.resolve("policy.yaml");
        String yaml = "version: 1\n"
                + "project_id: violated\n"
                + "root_ontology: https://example.org/ontology\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + "reasoning:\n  reasoner: HermiT\n";
        ProjectPolicyFixtures.writePolicy(policyPath, yaml);
        Path manifest = project.resolve("ro-crate-metadata.json");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("\"datePublished\"", "\"datePublishedRemoved\""));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertFalse(policy.valid());
        assertCode(policy, "interop_root_date_published");
    }

    @Test
    void missingCrateManifestFailsClosedAtItsPolicyPath(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("missing-crate");
        Path policyPath = project.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath, "version: 1\n"
                + "project_id: missing-crate\n"
                + "root_ontology: https://example.org/ontology\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + "reasoning:\n  reasoner: HermiT\n");
        Files.delete(project.resolve("ro-crate-metadata.json"));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertFalse(policy.valid());
        assertTrue(policy.issues().stream().anyMatch(issue ->
                        "asset_missing".equals(issue.code())
                                && "interoperability.metadata.path".equals(issue.path())),
                () -> policy.issues().toString());
    }

    @Test
    void commentsAndKeyOrderDoNotChangeEffectivePolicyDigest(@TempDir Path temp) throws Exception {
        Path one = temp.resolve("one.yaml");
        Path two = temp.resolve("two.yaml");
        write(one, "# comment\n" + minimal("example") + "reasoning:\n  reasoner: HermiT\n");
        write(two, "reasoning:\n  reasoner: HermiT\nroot_ontology: https://example.org/ontology\n"
                + "project_id: example\nversion: 1\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1"));
        ProjectPolicy a = ProjectPolicyLoader.load(one, null);
        ProjectPolicy b = ProjectPolicyLoader.load(two, null);
        assertTrue(a.valid(), () -> a.issues().toString());
        assertTrue(b.valid(), () -> b.issues().toString());
        assertEquals(a.digest(), b.digest());
    }

    @Test
    void releaseFieldsAreNotDefaultedSoTheDigestOfAReleaseFreePolicyIsUnchanged(@TempDir Path temp)
            throws Exception {
        // The release-bundle workflow (run_release_gate/prepare_release) consumes release.* with its
        // OWN defaults, adding no policy defaults — so a policy that omits the release block gets no
        // injected `release` key and its digest is unaffected. A policy that DOES set release.* is
        // digest-stable across loads (the fields were already schema-accepted before the workflow).
        String stages = "validation:\n  required_stages: [structural]\n";
        Path releaseFree = temp.resolve("release-free.yaml");
        write(releaseFree, minimal("example") + stages);
        ProjectPolicy noRelease = ProjectPolicyLoader.load(releaseFree, null);
        assertTrue(noRelease.valid(), () -> noRelease.issues().toString());
        assertFalse(noRelease.effective().containsKey("release"),
                "no release defaults may be injected into the effective policy");

        Path withRelease = temp.resolve("with-release.yaml");
        write(withRelease, minimal("example") + stages
                + "release:\n  output_dir: out\n  format: turtle\n");
        ProjectPolicy a = ProjectPolicyLoader.load(withRelease, null);
        ProjectPolicy b = ProjectPolicyLoader.load(withRelease, null);
        assertTrue(a.valid(), () -> a.issues().toString());
        assertEquals(a.digest(), b.digest());
    }

    @Test
    void auditRuntimeDefaultsDoNotRewriteLegacyPolicyDigests(@TempDir Path temp) throws Exception {
        String stages = "validation:\n  required_stages: [structural]\n";
        Path legacy = temp.resolve("legacy.yaml");
        write(legacy, minimal("example") + stages);
        ProjectPolicy withoutAudit = ProjectPolicyLoader.load(legacy, null);
        assertTrue(withoutAudit.valid(), () -> withoutAudit.issues().toString());
        assertFalse(withoutAudit.effective().containsKey("audit"),
                "runtime retention defaults must not silently change an existing policy digest");
        AuditSettings implicitDefaults = AuditSettings.from(withoutAudit);
        assertEquals(new AuditSettings(AuditSettings.DEFAULT_RETENTION_DAYS,
                AuditSettings.DEFAULT_MAX_FILE_BYTES, AuditSettings.DEFAULT_MAX_FILES),
                implicitDefaults);
        assertTrue(implicitDefaults.policyDerived(),
                "a valid policy that omits the audit block deliberately accepts the defaults, so "
                        + "its retention may sweep sibling streams");
        assertFalse(AuditSettings.defaults().policyDerived(),
                "fallback defaults (no valid policy) must never sweep sibling streams");

        Path configured = temp.resolve("configured.yaml");
        write(configured, minimal("example") + stages
                + "audit:\n  retention_days: 30\n  max_file_bytes: 2097152\n  max_files: 4\n");
        ProjectPolicy withAudit = ProjectPolicyLoader.load(configured, null);
        assertTrue(withAudit.valid(), () -> withAudit.issues().toString());
        assertEquals(new AuditSettings(30, 2_097_152, 4), AuditSettings.from(withAudit));
        assertNotEquals(withoutAudit.digest(), withAudit.digest());
    }

    @Test
    void explicitMissingPathAndMalformedOrDuplicateYamlFailClosed(@TempDir Path temp) throws Exception {
        ProjectPolicy missing = ProjectPolicyLoader.load(temp.resolve("missing.yaml"), null);
        assertTrue(missing.loaded());
        assertFalse(missing.valid());
        assertCode(missing, "policy_not_found");

        Path duplicate = temp.resolve("duplicate.yaml");
        write(duplicate, minimal("first") + "project_id: second\n");
        ProjectPolicy duplicateResult = ProjectPolicyLoader.load(duplicate, null);
        assertFalse(duplicateResult.valid());
        assertCode(duplicateResult, "yaml_invalid");

        Path trailing = temp.resolve("trailing.yaml");
        write(trailing, minimal("first") + "---\n" + minimal("second"));
        ProjectPolicy trailingResult = ProjectPolicyLoader.load(trailing, null);
        assertFalse(trailingResult.valid());
        assertCode(trailingResult, "yaml_invalid");
    }

    @Test
    void oversizedPolicyIsRejectedBeforeParsing(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("large.yaml");
        byte[] content = new byte[(int) ProjectPolicyLoader.MAX_POLICY_BYTES + 1];
        java.util.Arrays.fill(content, (byte) 'x');
        Files.write(path, content);
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        assertFalse(policy.valid());
        assertCode(policy, "policy_too_large");
    }

    @Test
    void schemaAndSemanticErrorsRemainStructured(@TempDir Path temp) throws Exception {
        Path schema = temp.resolve("schema.yaml");
        write(schema, minimal("example") + "future_option: true\n");
        ProjectPolicy schemaResult = ProjectPolicyLoader.load(schema, null);
        assertCode(schemaResult, "schema_invalid");

        Path curie = temp.resolve("curie.yaml");
        write(curie, minimal("example") + "annotations:\n  required: [missing:source]\n");
        ProjectPolicy curieResult = ProjectPolicyLoader.load(curie, null);
        assertCode(curieResult, "prefix_unknown");

        Path mismatch = temp.resolve("mismatch.yaml");
        write(mismatch, minimal("example"));
        ProjectPolicy mismatchResult = ProjectPolicyLoader.load(mismatch, null,
                "https://example.org/another", List.of());
        assertCode(mismatchResult, "root_ontology_mismatch");
    }

    @Test
    void unavailableRequiredReasonerIsAnErrorBeforeQc(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "reasoning:\n  reasoner: HermiT\n  required: true\n"
                + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy unavailable = ProjectPolicyLoader.load(policyPath, null,
                "https://example.org/ontology", List.of("ELK"));
        assertFalse(unavailable.valid());
        assertCode(unavailable, "reasoner_unavailable");
        ProjectPolicy available = ProjectPolicyLoader.load(policyPath, null,
                "https://example.org/ontology", List.of("ELK", "HermiT"));
        assertTrue(available.valid(), () -> available.issues().toString());
        assertEquals(List.of("reasoner", "interoperability", "structural"),
                object(available.effective(), "validation").get("required_stages"),
                "reasoning.required must force the reasoner stage into the effective contract");
    }

    @Test
    void versionlessReasonerReferenceResolvesAgainstVersionedDisplayNames(@TempDir Path temp)
            throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "reasoning:\n  reasoner: HermiT\n  required: true\n"
                + "validation:\n  required_stages: [structural]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null,
                "https://example.org/ontology", List.of("HermiT 1.4.3.456", "ELK 0.6.0"));

        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertTrue(policy.issues().stream().noneMatch(i -> "reasoner_unavailable".equals(i.code())
                        || "reasoner_ambiguous".equals(i.code())),
                () -> policy.issues().toString());
    }

    @Test
    void aReasonerReferenceMatchingSeveralInstalledVersionsIsAmbiguousNotPicked(@TempDir Path temp)
            throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "reasoning:\n  reasoner: HermiT\n  required: true\n"
                + "validation:\n  required_stages: [structural]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null,
                "https://example.org/ontology", List.of("HermiT 1.4", "HermiT 2.0"));

        assertFalse(policy.valid());
        assertCode(policy, "reasoner_ambiguous");
        assertTrue(policy.issues().stream().noneMatch(i -> "reasoner_unavailable".equals(i.code())),
                () -> policy.issues().toString());
    }

    @Test
    void aVersionedReferenceWithoutAnExactInstalledMatchIsUnavailable(@TempDir Path temp)
            throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "reasoning:\n  reasoner: HermiT 9.9.9\n  required: true\n"
                + "validation:\n  required_stages: [structural]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null,
                "https://example.org/ontology", List.of("HermiT 1.4.3.456"));

        assertFalse(policy.valid());
        assertCode(policy, "reasoner_unavailable");
    }

    @Test
    void pathsAreRelativeToTheProjectRootAndGlobsAreSortedAndDeduplicated(@TempDir Path temp) throws Exception {
        Path quality = temp.resolve("quality");
        Files.createDirectories(quality);
        write(quality.resolve("z.rq"), "SELECT * WHERE {}\n");
        write(quality.resolve("a.rq"), "ASK {}\n");
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "validation:\n"
                + "  required_stages: [invariants]\n"
                + "  invariants:\n"
                + "    paths: [quality/*.rq, quality/a.rq]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(List.of(quality.resolve("a.rq").toRealPath(), quality.resolve("z.rq").toRealPath()),
                policy.assets().get("invariants"));
    }

    @Test
    void missingTraversalUrlAndSymlinkEscapeAssetsFailClosed(@TempDir Path temp) throws Exception {
        Path outside = temp.resolve("outside.rq");
        write(outside, "ASK {}\n");
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("quality"));

        Path missing = project.resolve("missing.yaml");
        write(missing, invariantPolicy("quality/missing.rq"));
        assertCode(ProjectPolicyLoader.load(missing, null), "asset_missing");

        Path traversal = project.resolve("traversal.yaml");
        // projectPath deliberately permits absolute/external syntax so runtime capability/confinement
        // can decide it; with allow_external_paths=false this must fail here.
        write(traversal, invariantPolicy(outside.toString()));
        assertCode(ProjectPolicyLoader.load(traversal, null), "path_outside_project");

        Path url = project.resolve("url.yaml");
        write(url, invariantPolicy("https://example.org/shape.rq"));
        assertCode(ProjectPolicyLoader.load(url, null), "path_scheme_forbidden");

        Path link = project.resolve("quality/link.rq");
        try {
            Files.createSymbolicLink(link, outside);
            Path linked = project.resolve("linked.yaml");
            write(linked, invariantPolicy("quality/link.rq"));
            assertCode(ProjectPolicyLoader.load(linked, null), "symlink_escape");
        } catch (UnsupportedOperationException | IOException ignored) {
            // Platform cannot create symlinks; lexical escape cases above still run everywhere.
        }
    }

    @Test
    void duplicateModuleOwnershipAndPathsAreRejected(@TempDir Path temp) throws Exception {
        Path module = temp.resolve("module.ttl");
        write(module, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "modules:\n"
                + "  - {ontology_iri: 'https://example.org/m', path: module.ttl}\n"
                + "  - {ontology_iri: 'https://example.org/m', path: module.ttl}\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertCode(policy, "module_ontology_duplicate");
        assertCode(policy, "module_path_duplicate");
    }

    @Test
    void lockedImportPolicyRejectsDirectoryAsLockfile(@TempDir Path temp) throws Exception {
        Files.createDirectory(temp.resolve("lockdir"));
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "imports:\n"
                + "  mode: locked\n"
                + "  lockfile: lockdir\n"
                + "validation:\n"
                + "  required_stages: [profile, governance, structural]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertFalse(policy.valid());
        assertCode(policy, "asset_not_file");
        assertNull(policy.assets().get("import_lock"));
    }

    @Test
    void discoveredDotDirectoryPolicyReachesSourcesBesideIt(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("repo");
        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        write(root.resolve("ontology.ttl"), "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/ontology> a owl:Ontology .\n");
        write(policyPath, minimal("example")
                + "modules:\n"
                + "  - {ontology_iri: 'https://example.org/ontology', path: ontology.ttl}\n"
                + "validation:\n  required_stages: [structural]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(null, root.resolve("ontology.ttl"));
        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(root.toRealPath(), policy.projectRoot());
        assertEquals(List.of(root.resolve("ontology.ttl").toRealPath()), policy.assets().get("modules"));
    }

    @Test
    void configuredProjectRootResolvesAgainstTheDotDirectoryParent(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("repo");
        Path src = root.resolve("src");
        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        write(src.resolve("ontology.ttl"), "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/ontology> a owl:Ontology .\n");
        write(policyPath, minimal("example")
                + "project_root: src\n"
                + "modules:\n"
                + "  - {ontology_iri: 'https://example.org/ontology', path: ontology.ttl}\n"
                + "validation:\n  required_stages: [structural]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(null, src.resolve("ontology.ttl"));
        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(src.toRealPath(), policy.projectRoot());
        assertEquals(List.of(src.resolve("ontology.ttl").toRealPath()), policy.assets().get("modules"));
    }

    @Test
    void moduleOntologyIriMustMatchThePolicyDeclaration(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(temp.resolve("module.ttl"), "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/actual> a owl:Ontology .\n");
        write(policyPath, minimal("module-mismatch")
                + "modules:\n"
                + "  - {ontology_iri: 'https://example.org/declared', path: module.ttl}\n"
                + "validation:\n  required_stages: [governance]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertFalse(policy.valid());
        assertCode(policy, "module_ontology_iri_mismatch");
    }

    @Test
    void discoveredPolicyAssetsAndSymlinksOutsideTheAnchorStillFailClosed(@TempDir Path temp)
            throws Exception {
        Path outside = temp.resolve("outside.rq");
        write(outside, "ASK {}\n");
        Path root = temp.resolve("repo");
        Path policyPath = root.resolve(".protege-mcp/project.yaml");
        Files.createDirectories(root.resolve("quality"));

        write(policyPath, minimal("example")
                + "modules:\n"
                + "  - {ontology_iri: 'https://example.org/m', path: ../module.ttl}\n"
                + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy traversal = ProjectPolicyLoader.load(null, root.resolve("ontology.ttl"));
        assertCode(traversal, "path_outside_project");

        try {
            Files.createSymbolicLink(root.resolve("quality/link.rq"), outside);
            write(policyPath, invariantPolicy("quality/link.rq"));
            ProjectPolicy linked = ProjectPolicyLoader.load(null, root.resolve("ontology.ttl"));
            assertCode(linked, "symlink_escape");
        } catch (UnsupportedOperationException | IOException ignored) {
            // Platform cannot create symlinks; the lexical escape case above still runs everywhere.
        }
    }

    @Test
    void narrowGlobScansOnlyItsWildcardFreeBaseDirectory(@TempDir Path temp) throws Exception {
        Path quality = temp.resolve("quality");
        Files.createDirectories(quality);
        write(quality.resolve("a.rq"), "ASK {}\n");
        // A sibling tree larger than the visited cap must not starve a glob that never names it.
        Path big = temp.resolve("big");
        Files.createDirectories(big);
        for (int i = 0; i <= ProjectPolicyLoader.MAX_ASSET_FILES; i++) {
            Files.createFile(big.resolve("f" + i));
        }
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, invariantPolicy("quality/*.rq"));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(List.of(quality.resolve("a.rq").toRealPath()), policy.assets().get("invariants"));
    }

    @Test
    void globIntoAMissingDirectoryReportsEmptyPatternNotScanFailure(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, invariantPolicy("missing/*.rq"));
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertCode(policy, "asset_pattern_empty");
        assertTrue(policy.issues().stream().noneMatch(i -> "glob_read_failed".equals(i.code())),
                () -> policy.issues().toString());
    }

    @Test
    void externalGlobsResolveFromTheirBaseDirectoryWhenExternalPathsAreAllowed(@TempDir Path temp)
            throws Exception {
        Path shared = temp.resolve("shared");
        Files.createDirectories(shared);
        write(shared.resolve("x.rq"), "ASK {}\n");
        Path policyPath = temp.resolve("project/policy.yaml");
        write(policyPath, minimal("example")
                + "filesystem:\n  allow_external_paths: true\n"
                + "validation:\n"
                + "  required_stages: [invariants]\n"
                + "  invariants:\n"
                + "    paths: ['../shared/*.rq']\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(List.of(shared.resolve("x.rq").toRealPath()), policy.assets().get("invariants"));
    }

    @Test
    void globMatchingSurvivesMetacharactersInTheProjectDirectoryName(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("onto [v2]");
        Path quality = project.resolve("quality");
        Files.createDirectories(quality);
        write(quality.resolve("a.rq"), "ASK {}\n");
        Path policyPath = project.resolve("policy.yaml");
        write(policyPath, invariantPolicy("quality/*.rq"));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(List.of(quality.resolve("a.rq").toRealPath()), policy.assets().get("invariants"));
    }

    @Test
    void requiredCqStageWithAFileConventionMustNameAPath(@TempDir Path temp) throws Exception {
        Path robot = temp.resolve("robot.yaml");
        write(robot, cqPolicy("robot-sparql-dir", null));
        ProjectPolicy robotResult = ProjectPolicyLoader.load(robot, null);
        assertFalse(robotResult.valid());
        assertCode(robotResult, "cq_path_required");

        Path manifest = temp.resolve("manifest.yaml");
        write(manifest, cqPolicy("sidecar-manifest", null));
        ProjectPolicy manifestResult = ProjectPolicyLoader.load(manifest, null);
        assertFalse(manifestResult.valid());
        assertCode(manifestResult, "cq_path_required");

        Path annotations = temp.resolve("annotations.yaml");
        write(annotations, cqPolicy("ontology-annotations", null));
        ProjectPolicy annotationsResult = ProjectPolicyLoader.load(annotations, null);
        assertTrue(annotationsResult.valid(), () -> annotationsResult.issues().toString());

        Files.createDirectories(temp.resolve("cqs"));
        Path resolved = temp.resolve("resolved.yaml");
        write(resolved, cqPolicy("robot-sparql-dir", "cqs"));
        ProjectPolicy resolvedResult = ProjectPolicyLoader.load(resolved, null);
        assertTrue(resolvedResult.valid(), () -> resolvedResult.issues().toString());
        assertEquals(List.of(temp.resolve("cqs").toRealPath()), resolvedResult.assets().get("cqs"));
    }

    @Test
    void minimalLifecycleWithoutDeprecationIsNotForcedIntoContradictoryDefaults(@TempDir Path temp)
            throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "prefixes:\n  ex: https://example.org/ns#\n"
                + "lifecycle:\n"
                + "  status_property: ex:status\n"
                + "  allowed_values: [draft, released]\n"
                + "validation:\n  required_stages: [governance]\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        Map<String, Object> lifecycle = object(policy.effective(), "lifecycle");
        assertEquals(List.of(), lifecycle.get("deprecated_values"));
        assertEquals(Boolean.FALSE, lifecycle.get("require_replacement"));
    }

    @Test
    void oboStyleLifecycleDefaultsStillRequireReplacementProperties(@TempDir Path temp) throws Exception {
        Path missing = temp.resolve("missing.yaml");
        write(missing, minimal("example")
                + "prefixes:\n  ex: https://example.org/ns#\n"
                + "lifecycle:\n"
                + "  status_property: ex:status\n"
                + "  allowed_values: [active, deprecated]\n"
                + "validation:\n  required_stages: [governance]\n");
        ProjectPolicy missingResult = ProjectPolicyLoader.load(missing, null);
        assertFalse(missingResult.valid());
        assertCode(missingResult, "lifecycle_replacement_properties_required");
        assertEquals(List.of("deprecated"),
                object(missingResult.effective(), "lifecycle").get("deprecated_values"));

        Path replaced = temp.resolve("replaced.yaml");
        write(replaced, minimal("example")
                + "prefixes:\n  ex: https://example.org/ns#\n"
                + "lifecycle:\n"
                + "  status_property: ex:status\n"
                + "  allowed_values: [active, deprecated]\n"
                + "  replaced_by_properties: [ex:replacedBy]\n"
                + "validation:\n  required_stages: [governance]\n");
        ProjectPolicy replacedResult = ProjectPolicyLoader.load(replaced, null);
        assertTrue(replacedResult.valid(), () -> replacedResult.issues().toString());
        assertEquals(Boolean.TRUE,
                object(replacedResult.effective(), "lifecycle").get("require_replacement"));
    }

    @Test
    void authoredDeprecatedValuesOutsideAllowedValuesAreStillRejected(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("policy.yaml");
        write(policyPath, minimal("example")
                + "prefixes:\n  ex: https://example.org/ns#\n"
                + "lifecycle:\n"
                + "  status_property: ex:status\n"
                + "  allowed_values: [active]\n"
                + "  deprecated_values: [obsolete]\n"
                + "validation:\n  required_stages: [governance]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertFalse(policy.valid());
        assertCode(policy, "lifecycle_deprecated_not_allowed");
    }

    @Test
    void headlessNullRegistryStillRequiresANamedReasonerButSkipsAvailability(@TempDir Path temp)
            throws Exception {
        Path unnamed = temp.resolve("unnamed.yaml");
        write(unnamed, minimal("example") + "validation:\n  required_stages: [reasoner]\n");
        ProjectPolicy missing = ProjectPolicyLoader.load(unnamed, null);
        assertFalse(missing.valid());
        assertCode(missing, "reasoner_unspecified");

        Path named = temp.resolve("named.yaml");
        write(named, minimal("example")
                + "reasoning:\n  reasoner: HermiT\n"
                + "validation:\n  required_stages: [reasoner]\n");
        ProjectPolicy ok = ProjectPolicyLoader.load(named, null);
        assertTrue(ok.valid(), () -> ok.issues().toString());
        assertTrue(ok.issues().stream().noneMatch(i -> "reasoner_unavailable".equals(i.code())),
                () -> ok.issues().toString());
    }

    @Test
    void validatesOrderedEntitySearchPropertiesAndLanguages(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve("search.yaml");
        write(policyPath, minimal("search")
                + "prefixes:\n"
                + "  ex: https://example.org/annotations/\n"
                + "  rdfs: http://www.w3.org/2000/01/rdf-schema#\n"
                + "  skos: http://www.w3.org/2004/02/skos/core#\n"
                + "entity_search:\n"
                + "  preferred_properties: [rdfs:label, skos:prefLabel]\n"
                + "  synonym_properties: [skos:altLabel, ex:alias]\n"
                + "  preferred_languages: [en, ko]\n"
                + "  fallback_languages: [und, de]\n"
                + "reasoning:\n  reasoner: HermiT\n");

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);

        assertTrue(policy.valid(), () -> policy.issues().toString());
        assertEquals(List.of("en", "ko"),
                object(policy.effective(), "entity_search").get("preferred_languages"));
    }

    @Test
    void rejectsEntitySearchPropertyAndLanguageAmbiguity(@TempDir Path temp) throws Exception {
        Path overlap = temp.resolve("overlap.yaml");
        write(overlap, minimal("overlap")
                + "prefixes:\n  skos: http://www.w3.org/2004/02/skos/core#\n"
                + "entity_search:\n"
                + "  preferred_properties: [skos:prefLabel]\n"
                + "  synonym_properties: [http://www.w3.org/2004/02/skos/core#prefLabel]\n"
                + "  preferred_languages: [en]\n"
                + "  fallback_languages: [EN]\n");
        ProjectPolicy overlapResult = ProjectPolicyLoader.load(overlap, null);
        assertFalse(overlapResult.valid());
        assertCode(overlapResult, "search_property_overlap");
        assertCode(overlapResult, "search_language_overlap");

        Path duplicate = temp.resolve("duplicate.yaml");
        write(duplicate, minimal("duplicate")
                + "entity_search:\n  preferred_languages: [en, EN]\n");
        ProjectPolicy duplicateResult = ProjectPolicyLoader.load(duplicate, null);
        assertFalse(duplicateResult.valid());
        assertCode(duplicateResult, "search_language_duplicate");

        Path tooMany = temp.resolve("too-many-languages.yaml");
        write(tooMany, minimal("too-many-languages")
                + "entity_search:\n  preferred_languages: [en, de, fr, ko, ja]\n");
        ProjectPolicy tooManyResult = ProjectPolicyLoader.load(tooMany, null);
        assertFalse(tooManyResult.valid());
        assertCode(tooManyResult, "schema_invalid");

        Path defaultOverlap = temp.resolve("default-overlap.yaml");
        write(defaultOverlap, minimal("default-overlap")
                + "prefixes:\n  skos: http://www.w3.org/2004/02/skos/core#\n"
                + "entity_search:\n  preferred_properties: [skos:altLabel]\n");
        ProjectPolicy defaultOverlapResult = ProjectPolicyLoader.load(defaultOverlap, null);
        assertFalse(defaultOverlapResult.valid());
        assertCode(defaultOverlapResult, "search_property_overlap");
    }

    @Test
    void languageOnlySearchOverrideUsesDefaultsButExplicitlyEmptyPropertiesFail(@TempDir Path temp)
            throws Exception {
        Path languageOnly = temp.resolve("language-only.yaml");
        write(languageOnly, minimal("language-only")
                + "entity_search:\n  preferred_languages: [en]\n  fallback_languages: [und]\n"
                + "reasoning:\n  reasoner: HermiT\n");
        ProjectPolicy languageOnlyResult = ProjectPolicyLoader.load(languageOnly, null);
        assertTrue(languageOnlyResult.valid(), () -> languageOnlyResult.issues().toString());

        Path disabled = temp.resolve("disabled.yaml");
        write(disabled, minimal("disabled")
                + "entity_search:\n"
                + "  preferred_properties: []\n"
                + "  synonym_properties: []\n"
                + "  preferred_languages: [en]\n");
        ProjectPolicy disabledResult = ProjectPolicyLoader.load(disabled, null);
        assertFalse(disabledResult.valid());
        assertCode(disabledResult, "search_properties_required");
    }

    private static String minimal(String projectId) {
        return ProjectPolicyFixtures.minimalPolicy(projectId, "https://example.org/ontology");
    }

    private static String cqPolicy(String convention, String path) {
        return minimal("example")
                + "validation:\n"
                + "  required_stages: [cqs]\n"
                + "  competency_questions:\n"
                + "    convention: " + convention + "\n"
                + (path == null ? "" : "    path: " + path + "\n");
    }

    private static String invariantPolicy(String path) {
        return minimal("example")
                + "validation:\n"
                + "  required_stages: [invariants]\n"
                + "  invariants:\n"
                + "    paths: ['" + path.replace("'", "''") + "']\n";
    }

    private static void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
        ProjectPolicyFixtures.materialize(path, value);
    }

    private static void assertCode(ProjectPolicy policy, String code) {
        assertTrue(policy.issues().stream().anyMatch(i -> code.equals(i.code())),
                () -> "missing " + code + " in " + policy.issues());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }
}
