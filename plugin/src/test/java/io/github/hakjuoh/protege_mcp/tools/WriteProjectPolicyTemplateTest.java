package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String POLICY_RELATIVE = ".protege-mcp/project.yaml";

    private Preferences prefs;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void savePreferences() {
        prefs = McpConfig.prefs();
        savedReadOnly = prefs.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restorePreferences() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    @Test
    void generalTemplateLoadsValidAfterItsNamedAssetsExist(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of("profile", "general")));

        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals("general", result.get("profile"));
        Path policyPath = Path.of((String) result.get("path"));
        assertTrue(policyPath.endsWith(POLICY_RELATIVE), () -> "path: " + policyPath);
        String yaml = Files.readString(policyPath);
        assertTrue(yaml.contains("root_artifact: ontology.ttl"), yaml);
        assertTrue(yaml.contains("reasoner: HermiT"), yaml);

        // The bare template names files that do not exist yet, so it is NOT valid on its own.
        assertFalse(ProjectPolicyLoader.load(policyPath, null).valid(),
                "a scaffold naming non-existent assets must not validate before they are created");

        // Materialize the two files the validation_hint tells the user to create, then it loads valid.
        ProjectPolicyFixtures.materialize(policyPath, yaml);
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> "general: " + policy.issues());
    }

    @Test
    void oboTemplateLoadsValidAfterItsNamedAssetsExist(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of("profile", "obo")));

        assertEquals(true, result.get("written"), () -> result.toString());
        assertEquals("obo", result.get("profile"));
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        String yaml = Files.readString(policyPath);
        assertTrue(yaml.contains("root_artifact: ontology-edit.owl"), yaml);
        assertTrue(yaml.contains("reasoner: ELK"), yaml);

        ProjectPolicyFixtures.materialize(policyPath, yaml);
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> "obo: " + policy.issues());
    }

    @Test
    void projectIdAndRootOntologyDeriveFromTheActiveOntology(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, ONTOLOGY_IRI);

        Map<String, Object> result = structured(call(ctx, Map.of()));

        assertEquals("project", result.get("project_id"), () -> result.toString());
        String yaml = Files.readString(temp.resolve(POLICY_RELATIVE));
        assertTrue(yaml.contains("project_id: project"), yaml);
        assertTrue(yaml.contains("root_ontology: " + ONTOLOGY_IRI), yaml);
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
                        line -> String.valueOf(line).contains("policy_bootstrap=true")),
                () -> "the hint must name the explicit-path bootstrap that can create the root "
                        + "artifact while the policy is still invalid: " + hint);
        assertFalse(result.containsKey("valid"), "a scaffold must not claim valid=true");
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
        assertEquals(true, structured(call(ctx, Map.of("project_id", "first"))).get("written"));
        Path policyPath = temp.resolve(POLICY_RELATIVE);

        Map<String, Object> result = structured(call(ctx,
                Map.of("project_id", "second", "overwrite", true)));

        assertEquals(true, result.get("written"), () -> result.toString());
        String after = Files.readString(policyPath);
        assertTrue(after.contains("project_id: second"), after);
        assertFalse(after.contains("project_id: first"), after);
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
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create(ontologyIri + "#Thing"))));
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(ontology)),
                new McpServerController(new OntologyAccess(null)));
    }

    private static CallToolResult call(ToolContext ctx, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        ProjectPolicyTools.register(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals("write_project_policy_template")) {
                return spec.callHandler().apply(null,
                        new CallToolRequest("write_project_policy_template", args));
            }
        }
        throw new AssertionError("no tool named write_project_policy_template");
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
