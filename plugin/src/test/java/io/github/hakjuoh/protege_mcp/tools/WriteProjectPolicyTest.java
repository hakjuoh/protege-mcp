package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

/** End-to-end tests for write_project_policy over the headless Protégé adapter. */
class WriteProjectPolicyTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String POLICY_RELATIVE = ".protege-mcp/project.yaml";
    private static final String HEADLESS_VALIDATION = "validation:\n"
            + "  required_stages: [interoperability, profile, governance, structural]\n";

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
    void writePolicyWritesYamlAndValidatesImmediately(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        String yaml = "version: 2\n"
                + "project_id: my-project\n"
                + "root_ontology: https://example.org/project\n"
                + "external_terms:\n"
                + "  providers:\n"
                + "    - id: embl-ebi\n"
                + "      profile: ols4\n"
                + "      enabled: true\n"
                + "      origin_alias: embl-ebi\n"
                + "    - id: bioportal\n"
                + "      profile: ontoportal\n"
                + "      enabled: true\n"
                + "      origin_alias: bioportal\n"
                + "      credential_id: bioportal-key\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + HEADLESS_VALIDATION;
        ProjectPolicyFixtures.materialize(temp.resolve(POLICY_RELATIVE), yaml);

        Map<String, Object> result = structured(call(ctx, Map.of("yaml", yaml)));

        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals(true, result.get("policy_loaded"), () -> result.toString());
        assertEquals(2, result.get("schema_version"), () -> result.toString());
        Path policyPath = Path.of((String) result.get("path"));
        assertTrue(policyPath.endsWith(POLICY_RELATIVE), () -> "path: " + policyPath);
        assertEquals(yaml, Files.readString(policyPath));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> "issues: " + policy.issues());
        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) policy.effective().get("external_terms");
        assertNotNull(ext);
        List<?> providers = (List<?>) ext.get("providers");
        assertEquals(2, providers.size());
    }

    @Test
    void writePolicyOverwritesExistingByDefault(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        String yaml1 = "version: 2\n"
                + "project_id: my-project\n"
                + "root_ontology: https://example.org/project\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + HEADLESS_VALIDATION;
        ProjectPolicyFixtures.materialize(temp.resolve(POLICY_RELATIVE), yaml1);
        call(ctx, Map.of("yaml", yaml1));

        String yaml2 = yaml1 + """
                external_terms:
                  providers:
                    - id: embl-ebi
                      profile: ols4
                      enabled: true
                      origin_alias: embl-ebi
                """;
        Map<String, Object> result = structured(call(ctx, Map.of("yaml", yaml2)));

        assertEquals(true, result.get("written"), result::toString);
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        assertEquals(yaml2, Files.readString(policyPath));
    }

    @Test
    void patchOnFreshProjectScaffoldsV3AndAddsProvidersInOneOperation(@TempDir Path temp)
            throws Exception {
        prefs.putBoolean(McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS, false);
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        Map<String, Object> providers = Map.of("providers", List.of(
                Map.of("id", "bioportal", "profile", "ontoportal", "enabled", true,
                        "origin_alias", "bioportal", "credential_id", "bioportal"),
                Map.of("id", "argoportal", "profile", "ontoportal", "enabled", true,
                        "origin_alias", "argoportal", "credential_id", "argoportal"),
                Map.of("id", "embl-ebi", "profile", "ols4", "enabled", true,
                        "origin_alias", "embl-ebi")));

        Map<String, Object> result = structured(call(ctx,
                Map.of("patch", Map.of("external_terms", providers))));

        assertEquals(true, result.get("written"), result::toString);
        assertEquals(true, result.get("valid"), result::toString);
        assertEquals(3, result.get("schema_version"));
        assertEquals("patch", result.get("update_mode"));
        assertEquals(true, result.get("created_from_template"));
        assertEquals(false, result.get("preserved_existing_content"));
        assertEquals(true, result.get("metadata_created"));
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        String yaml = Files.readString(policyPath);
        assertTrue(yaml.startsWith("# Protégé MCP project policy (v3)"), yaml);
        assertTrue(yaml.contains("id: \"bioportal\""), yaml);
        assertTrue(yaml.contains("id: \"argoportal\""), yaml);
        assertTrue(yaml.contains("id: \"embl-ebi\""), yaml);
        assertTrue(Files.isRegularFile(temp.resolve("ro-crate-metadata.json")));

        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, temp.resolve("ontology.ttl"),
                ONTOLOGY_IRI, List.of());
        assertTrue(policy.valid(), () -> policy.issues().toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> effectiveProviders = (List<Map<String, Object>>)
                ((Map<String, Object>) policy.effective().get("external_terms")).get("providers");
        assertEquals(List.of("bioportal", "argoportal", "embl-ebi"),
                effectiveProviders.stream().map(row -> row.get("id")).toList());
    }

    @Test
    void invalidFreshPatchLeavesNeitherPolicyNorGeneratedMetadata(@TempDir Path temp)
            throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx,
                Map.of("patch", Map.of("project_id", ""))));

        assertEquals(false, result.get("written"), result::toString);
        assertEquals("policy_invalid", result.get("error_code"));
        assertEquals(true, result.get("created_from_template"));
        assertFalse(Files.exists(temp.resolve(POLICY_RELATIVE)));
        assertFalse(Files.exists(temp.resolve("ro-crate-metadata.json")));
    }

    @Test
    void generalPatchUpdatesMultipleSectionsAndPreservesUnrelatedContent(@TempDir Path temp)
            throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        Map<String, Object> template = structured(call(ctx, "write_project_policy_template",
                Map.of("version", 2)));
        assertEquals(true, template.get("valid"), template::toString);
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        String before = Files.readString(policyPath);
        String mappingsSection = before.substring(before.indexOf("# Canonical SSSOM"));

        Map<String, Object> externalTerms = Map.of("providers", List.of(
                Map.of("id", "bioportal", "profile", "ontoportal", "enabled", true,
                        "origin_alias", "bioportal", "credential_id", "bioportal-key"),
                Map.of("id", "agroportal", "profile", "ontoportal", "enabled", true,
                        "origin_alias", "agroportal", "credential_id", "agroportal-key"),
                Map.of("id", "embl-ebi", "profile", "ols4", "enabled", true,
                        "origin_alias", "embl-ebi")));

        Map<String, Object> auditPatch = new LinkedHashMap<>();
        auditPatch.put("retention_days", 30);
        auditPatch.put("max_files", null);
        Map<String, Object> patch = Map.of(
                "external_terms", externalTerms,
                "network", Map.of("allowed_hosts", List.of("data.bioontology.org")),
                "audit", auditPatch);
        Map<String, Object> result = structured(call(ctx, Map.of("patch", patch)));

        assertEquals(true, result.get("written"), result::toString);
        assertEquals(true, result.get("valid"), result::toString);
        assertEquals("patch", result.get("update_mode"));
        assertEquals(true, result.get("preserved_existing_content"));
        String after = Files.readString(policyPath);
        assertTrue(after.startsWith("# Protégé MCP project policy"), after);
        assertTrue(after.contains("# Version 2 feature policy"), after);
        assertTrue(after.endsWith(mappingsSection), "everything after external_terms is byte-preserved");
        assertTrue(after.contains("credential_id: \"bioportal-key\""), after);
        assertTrue(after.contains("id: \"agroportal\""), after);
        assertTrue(after.contains("profile: \"ols4\""), after);
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, temp.resolve("ontology.ttl"),
                ONTOLOGY_IRI, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> network = (Map<String, Object>) policy.effective().get("network");
        assertEquals("deny", network.get("default"), "nested merge must retain omitted fields");
        assertEquals(List.of("data.bioontology.org"), network.get("allowed_hosts"));
        @SuppressWarnings("unchecked")
        Map<String, Object> audit = (Map<String, Object>) policy.effective().get("audit");
        assertEquals(30, audit.get("retention_days"));
        assertEquals(10, audit.get("max_files"), "removed authored field receives schema default");
        assertFalse(after.contains("  max_files: 10"), "null removes the authored nested field");
    }

    @Test
    void invalidGeneralPatchLeavesExistingPolicyUnchanged(@TempDir Path temp)
            throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        structured(call(ctx, "write_project_policy_template", Map.of("version", 2)));
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        String before = Files.readString(policyPath);

        Map<String, Object> result = structured(call(ctx,
                Map.of("patch", Map.of("project_id", ""))));

        assertEquals(false, result.get("written"), result::toString);
        assertEquals(false, result.get("valid"), result::toString);
        assertEquals("policy_invalid", result.get("error_code"));
        assertEquals(before, Files.readString(policyPath));
    }

    @Test
    void patchRejectsOversizedExistingPolicyWithoutReadingItUnbounded(@TempDir Path temp)
            throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        Files.createDirectories(policyPath.getParent());
        byte[] oversized = new byte[(int) ProjectPolicyLoader.MAX_POLICY_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');
        Files.write(policyPath, oversized);

        CallToolResult result = call(ctx, Map.of("patch", Map.of("project_id", "changed")));

        assertTrue(result.isError(), () -> result.toString());
        assertTrue(String.valueOf(result.content()).contains("too large"),
                () -> String.valueOf(result.content()));
        assertEquals(oversized.length, Files.size(policyPath));
    }

    @Test
    void writePolicyRefusesOverwriteWhenFalse(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        String yaml = "version: 2\n"
                + "project_id: my-project\n"
                + "root_ontology: https://example.org/project\n"
                + ProjectPolicyFixtures.interoperabilityYaml("ontology.ttl", "ro-crate-1.1")
                + HEADLESS_VALIDATION;
        ProjectPolicyFixtures.materialize(temp.resolve(POLICY_RELATIVE), yaml);
        call(ctx, Map.of("yaml", yaml));

        Map<String, Object> result = structured(call(ctx, Map.of("yaml", yaml, "overwrite", false)));

        assertEquals(false, result.get("written"));
        assertEquals("policy_exists", result.get("error_code"));
    }

    @Test
    void readOnlyModeRefusesTheWrite(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);

        CallToolResult result = call(ctx, Map.of("yaml", "version: 2\n"));

        assertTrue(result.isError());
        assertFalse(Files.exists(temp.resolve(POLICY_RELATIVE)));
    }

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
        return call(ctx, "write_project_policy", args);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(result.isError(), () -> "Tool call failed: " + result);
        assertNotNull(result.structuredContent());
        return (Map<String, Object>) result.structuredContent();
    }
}
