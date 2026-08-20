package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** End-to-end tests for write_project_policy_template over the headless Protégé adapter. */
class WriteProjectPolicyTemplateTest {

    @Test
    void anonymousV3StarterDoesNotCreateAFalseLogicalOntologyBinding() {
        ProjectPolicyTemplate.Template template = ProjectPolicyTemplate.render(
                ProjectPolicyTemplate.GENERAL, "anonymous-project", null, 3);

        assertTrue(template.yaml().contains("workspace:"));
        assertTrue(template.yaml().contains("files: [ontology.ttl]"));
        assertFalse(template.yaml().contains("  ontologies:"));
        assertTrue(ProjectPolicyTemplate.validationHint(template).stream().anyMatch(hint ->
                hint.contains("release/interoperability entry-point IRI")));
        assertTrue(ProjectPolicyTemplate.validationHint(template).stream().anyMatch(hint ->
                hint.contains("workspace.ontologies binding")));
    }

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String POLICY_RELATIVE = ".protege-mcp/project.yaml";

    private Preferences prefs;
    private boolean savedReadOnly;
    private boolean savedConfirm;
    private boolean savedNoPolicyCompatibility;

    @BeforeEach
    void savePreferences() {
        prefs = McpConfig.prefs();
        savedReadOnly = prefs.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        savedNoPolicyCompatibility = prefs.getBoolean(
                McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS, true);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restorePreferences() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
        prefs.putBoolean(McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS,
                savedNoPolicyCompatibility);
    }

    @Test
    void generalTemplateIsImmediatelyValidForSavedOntology(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of("profile", "general")));

        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals("general", result.get("profile"));
        Path policyPath = Path.of((String) result.get("path"));
        assertTrue(policyPath.endsWith(POLICY_RELATIVE), () -> "path: " + policyPath);
        String yaml = Files.readString(policyPath);
        assertTrue(yaml.contains("root_artifact: ontology.ttl"), yaml);
        assertFalse(yaml.contains("reasoning:"), yaml);
        assertTrue(yaml.contains("required_stages: [interoperability, profile"), yaml);
        assertTrue(yaml.contains("audit:\n  retention_days: 90"), yaml);
        assertTrue(yaml.contains("workspace:"), yaml);
        assertTrue(yaml.contains("documents: [ontology.ttl]"), yaml);
        assertEquals(3, result.get("schema_version"));
        assertEquals(true, result.get("valid"), result::toString);
        assertEquals(true, result.get("metadata_created"), result::toString);
        assertTrue(Files.isRegularFile(temp.resolve("ro-crate-metadata.json")));
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> "general: " + policy.issues());
        assertEquals(3, policy.version());
    }

    @Test
    void defaultTemplateBootstrapsWhenCallerSelectedNoPolicyPathsAreDisabled(@TempDir Path temp)
            throws Exception {
        prefs.putBoolean(McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS, false);
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of("version", 3)));

        assertEquals(true, result.get("written"), result::toString);
        assertEquals(true, result.get("valid"), result::toString);
        assertTrue(Files.isRegularFile(temp.resolve(POLICY_RELATIVE)));
        assertTrue(Files.isRegularFile(temp.resolve("ro-crate-metadata.json")));
        String yaml = Files.readString(temp.resolve(POLICY_RELATIVE));
        assertTrue(yaml.contains("workspace:"), yaml);
        assertTrue(yaml.contains("documents: [ontology.ttl]"), yaml);
    }

    @Test
    void incompatibleExistingMetadataRejectsTemplateWithoutWritingPolicy(@TempDir Path temp)
            throws Exception {
        Path metadata = temp.resolve("ro-crate-metadata.json");
        Files.writeString(metadata, "{}\n");
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of("version", 2)));

        assertEquals(false, result.get("written"), result::toString);
        assertEquals(false, result.get("valid"), result::toString);
        assertEquals("policy_invalid", result.get("error_code"));
        assertEquals(false, result.get("metadata_created"));
        assertFalse(Files.exists(temp.resolve(POLICY_RELATIVE)),
                "an invalid combined scaffold must not leave a policy behind");
        assertEquals("{}\n", Files.readString(metadata),
                "pre-existing metadata must never be replaced or rewritten");
    }

    @Test
    void oboTemplateLoadsValidAfterItsNamedAssetsExist(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of("profile", "obo")));

        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals("obo", result.get("profile"));
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        String yaml = Files.readString(policyPath);
        assertTrue(yaml.contains("root_artifact: ontology.ttl"), yaml);
        assertFalse(yaml.contains("reasoning:"), yaml);

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> "obo: " + policy.issues());
    }

    @Test
    void explicitVersionTwoTemplateLoadsWithBoundedFeatureDefaults(@TempDir Path temp)
            throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx,
                Map.of("profile", "general", "version", 2)));

        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals(2, result.get("schema_version"));
        Path policyPath = Path.of((String) result.get("path"));
        String yaml = Files.readString(policyPath);
        assertTrue(yaml.contains("version: 2"), yaml);
        assertTrue(yaml.contains("mappings.sssom.tsv"), yaml);
        assertTrue(yaml.contains("queue_capacity: 32"), yaml);
        assertTrue(yaml.contains("allow_source_write: false"), yaml);
        assertFalse(yaml.contains("endpoint:"), yaml);
        assertFalse(yaml.contains("api_key:"), yaml);

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> "v2: " + policy.issues());
        assertEquals(2, policy.version());
        assertNull(policy.migration());
        assertTrue(((List<?>) result.get("validation_hint")).stream().anyMatch(
                line -> String.valueOf(line).contains("owner-locally")));
    }

    @Test
    void versionMustBeAnExactJsonIntegerAndInvalidValuesWriteNothing(@TempDir Path temp)
            throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        for (Object invalid : List.of("garbage", "2", 2.9, 4, true)) {
            CallToolResult result = call(ctx, Map.of("version", invalid));
            assertEquals(Boolean.TRUE, result.isError(), () -> invalid + ": " + result.content());
            assertTrue(String.valueOf(result.content()).contains("invalid_request"),
                    () -> invalid + ": " + result.content());
            assertFalse(Files.exists(temp.resolve(POLICY_RELATIVE)),
                    () -> "invalid version wrote a policy: " + invalid);
        }
    }

    @Test
    void validationReportsV1MigrationWithoutChangingItsDigest(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("migration-report", ONTOLOGY_IRI)
                        + "validation:\n  required_stages: [structural]\n");
        String digest = ProjectPolicyLoader.load(policyPath, null).digest();

        Map<String, Object> report = structured(call(ctx, "validate_project_policy", Map.of()));

        assertEquals(true, report.get("valid"), report::toString);
        assertEquals(1, report.get("schema_version"));
        assertEquals(digest, report.get("policy_digest"));
        @SuppressWarnings("unchecked")
        Map<String, Object> migration = (Map<String, Object>) report.get("migration");
        assertNotNull(migration, report::toString);
        assertEquals(1, migration.get("from_version"));
        assertEquals(2, migration.get("to_version"));
        assertEquals(false, migration.get("required"));
        assertEquals(false, migration.get("automatic_write"));
        assertEquals(false, migration.get("diagnostic_affects_digest"));
    }

    @Test
    void validationNeverTruncatesAFractionalPolicyVersion(@TempDir Path temp) throws Exception {
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        Files.createDirectories(policyPath.getParent());
        Files.writeString(policyPath,
                ProjectPolicyFixtures.minimalPolicy("fractional", ONTOLOGY_IRI)
                        .replace("version: 1", "version: 1.5"));

        Map<String, Object> report = structured(call(ctx(temp, ONTOLOGY_IRI),
                "validate_project_policy", Map.of()));

        assertEquals(false, report.get("valid"));
        assertFalse(report.containsKey("schema_version"), report::toString);
        assertTrue(String.valueOf(report.get("errors")).contains("schema_invalid"));
    }

    @Test
    void schemaRejectedProviderSecretsNeverEchoInPublicDiagnostics(@TempDir Path temp)
            throws Exception {
        String canary = "mcp-policy-secret-Q4W8";
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("secret-diagnostic", ONTOLOGY_IRI)
                        .replace("version: 1", "version: 2")
                        + "external_terms:\n"
                        + "  providers:\n"
                        + "    - {id: ols, profile: ols4, enabled: true, origin_alias: ebi, "
                        + "endpoint: 'https://user:" + canary + "@example.org', api_key: '"
                        + canary + "'}\n");

        Map<String, Object> report = structured(call(ctx(temp, ONTOLOGY_IRI),
                "validate_project_policy", Map.of()));

        assertEquals(false, report.get("valid"));
        assertFalse(report.containsKey("policy"), report::toString);
        assertFalse(report.toString().contains(canary), report::toString);
    }

    @Test
    void projectIdAndRootOntologyDeriveFromTheActiveOntology(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of()));

        assertEquals("project", result.get("project_id"), () -> result.toString());
        String yaml = Files.readString(temp.resolve(POLICY_RELATIVE));
        assertTrue(yaml.contains("project_id: project"), yaml);
        assertTrue(yaml.contains("root_ontology: " + ONTOLOGY_IRI), yaml);
        assertFalse(yaml.contains("MUST equal your ontology's IRI"), yaml);
        assertTrue(yaml.contains("does not restrict which project or external ontology can be active"),
                yaml);
    }

    @Test
    void emittedYamlIsCommentedAndReportsAValidationHint(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of()));

        String yaml = Files.readString(temp.resolve(POLICY_RELATIVE));
        assertTrue(yaml.contains("# Protégé MCP project policy"), yaml);
        assertTrue(yaml.contains("# OPTIONAL blocks"), yaml);
        long commentLines = yaml.lines().filter(l -> l.stripLeading().startsWith("#")).count();
        assertTrue(commentLines >= 20, "expected many explanatory comment lines, got " + commentLines);

        Object hint = result.get("validation_hint");
        assertTrue(hint instanceof List, () -> String.valueOf(hint));
        assertFalse(((List<?>) hint).isEmpty(), "validation_hint must list what to complete");
        assertTrue(((List<?>) hint).stream().anyMatch(
                        line -> String.valueOf(line).contains("reasoner")),
                () -> "the hint must explain the selected or omitted reasoner stage: " + hint);
        assertTrue(((List<?>) hint).stream().anyMatch(
                        line -> String.valueOf(line).contains("audit-export")),
                () -> "the hint must explain VCS handling for explicit audit exports: " + hint);
        assertEquals(true, result.get("valid"), result::toString);
    }

    @Test
    void sha256AndBytesMatchTheWrittenFile(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of()));

        byte[] bytes = Files.readAllBytes(temp.resolve(POLICY_RELATIVE));
        assertEquals(bytes.length, result.get("bytes"), () -> result.toString());
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)), result.get("sha256"));
    }

    @Test
    void overwriteFalseRefusesAnExistingFileWithoutWriting(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        assertEquals(true, structured(call(ctx, Map.of("project_id", "first"))).get("written"));
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        String before = Files.readString(policyPath);

        Map<String, Object> result = structured(call(ctx, Map.of("project_id", "second")));

        assertEquals(false, result.get("written"), () -> result.toString());
        assertEquals("policy_exists", result.get("error_code"));
        assertEquals(before, Files.readString(policyPath), "a refused write must not change the file");
        assertTrue(before.contains("project_id: first"), before);
    }

    @Test
    void overwriteTrueReplacesTheExistingTemplateAtomically(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        assertEquals(true, structured(call(ctx, Map.of("project_id", "same"))).get("written"));
        Path policyPath = temp.resolve(POLICY_RELATIVE);

        Map<String, Object> result = structured(call(ctx,
                Map.of("project_id", "same", "profile", "obo", "overwrite", true)));

        assertEquals(true, result.get("written"), () -> result.toString());
        String after = Files.readString(policyPath);
        assertTrue(after.contains("project_id: same"), after);
        assertTrue(after.contains("#   format: obo"), after);
    }

    @Test
    void readOnlyModeRefusesTheWrite(@TempDir Path temp) throws Exception {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        CallToolResult result = call(ctx, Map.of());

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(structured(result).get("error")).toLowerCase().contains("read-only"));
        assertFalse(Files.exists(temp.resolve(POLICY_RELATIVE)), "read-only mode must write nothing");
    }

    @Test
    void anExplicitPathOutsideProjectRootIsRefusedUnderAPolicy(@TempDir Path temp) throws Exception {
        // A valid discovered policy confines the filesystem to project_root (temp).
        Path discovered = temp.resolve(POLICY_RELATIVE);
        ProjectPolicyFixtures.writePolicy(discovered,
                ProjectPolicyFixtures.minimalPolicy("existing", ONTOLOGY_IRI)
                        + "validation:\n  required_stages: [governance, structural]\n"
                        + "  fail_on: warning\n");
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        Path escape = temp.getParent().resolve("outside-" + temp.getFileName() + ".yaml");

        CallToolResult result = call(ctx, Map.of("path", escape.toString()));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(structured(result).get("error")).contains("project_root"),
                () -> String.valueOf(structured(result)));
        assertFalse(Files.exists(escape), "nothing may be written outside the project");
    }

    // ------------------------------------------------------------------ helpers

    private static ToolContext ctx(Path temp, String ontologyIri) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ontologyIri));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        Path document = temp.resolve("ontology.ttl");
        manager.setOntologyDocumentIRI(ontology, IRI.create(document.toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create(ontologyIri + "#Thing"))));
        manager.saveOntology(ontology, new TurtleDocumentFormat(), IRI.create(document.toUri()));
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(ontology)),
                new McpServerController(new OntologyAccess(null)));
    }

    private static CallToolResult call(ToolContext ctx, Map<String, Object> args) {
        return call(ctx, "write_project_policy_template", args);
    }

    private static CallToolResult call(ToolContext ctx, String tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        ProjectPolicyTools.register(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals(tool)) {
                return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest(tool, args));
            }
        }
        throw new AssertionError("no tool named " + tool);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }
}
