package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * End-to-end tests for {@code save_ontology}'s explicit-path invalid-policy bootstrap
 * ({@code policy_bootstrap=true}): the discovered-but-invalid policy still refuses a normal
 * explicit-path save (now hinting the flag), while the flag authorizes exactly one contained
 * write so the policy-referenced root artifact can be created at all.
 */
class SaveOntologyPolicyBootstrapTest {

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
    void withoutTheFlagAnInvalidPolicyStillRefusesButHintsTheBootstrap(@TempDir Path temp)
            throws Exception {
        writeInvalidPolicy(temp);
        ToolContext ctx = ctx(temp);
        Path target = temp.resolve("ontology.ttl");

        CallToolResult result = call(ctx, Map.of("path", target.toString()));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        String error = String.valueOf(structured(result).get("error"));
        assertTrue(error.contains("policy is invalid"), error);
        assertTrue(error.contains("policy_bootstrap=true"), error);
        assertFalse(Files.exists(target), "a refused save must write nothing");
    }

    @Test
    void bootstrapCreatesThePolicyReferencedArtifactInsideTheProjectRoot(@TempDir Path temp)
            throws Exception {
        writeInvalidPolicy(temp);
        ToolContext ctx = ctx(temp);
        Path target = temp.resolve("ontology.ttl");

        CallToolResult result = call(ctx,
                Map.of("path", target.toString(), "policy_bootstrap", true));

        Map<String, Object> structured = structured(result);
        assertEquals(true, structured.get("saved"), () -> structured.toString());
        assertTrue(Files.exists(target), "the bootstrap save creates the root artifact");
        Map<?, ?> bootstrap = (Map<?, ?>) structured.get("policy_bootstrap");
        assertNotNull(bootstrap, () -> structured.toString());
        assertEquals(true, bootstrap.get("used"), bootstrap::toString);
        assertTrue(bootstrap.get("policy_errors") instanceof List<?> errors && !errors.isEmpty(),
                () -> "the invalid policy's error codes must be disclosed: " + bootstrap);
        assertNotNull(bootstrap.get("contained_root"), bootstrap::toString);
    }

    @Test
    void bootstrapNeverAuthorizesAPathOutsideTheProjectRoot(@TempDir Path temp) throws Exception {
        writeInvalidPolicy(temp);
        ToolContext ctx = ctx(temp);
        Path escape = temp.getParent().resolve("outside-" + temp.getFileName() + ".ttl");

        CallToolResult result = call(ctx,
                Map.of("path", escape.toString(), "policy_bootstrap", true));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(structured(result).get("error")).contains("outside project_root"),
                () -> String.valueOf(structured(result)));
        assertFalse(Files.exists(escape), "nothing may be written outside the project");
    }

    @Test
    void bootstrapRequiresBothProjectReadAndWriteCapabilities(@TempDir Path temp) throws Exception {
        writeInvalidPolicy(temp);
        ToolContext ctx = ctx(temp);
        Path target = temp.resolve("ontology.ttl");
        Map<String, Object> args = Map.of("path", target.toString(), "policy_bootstrap", true);

        CallToolResult noWrite = call(ctx,
                exchange(Set.of(Capability.ONTOLOGY_ADMIN.value(),
                        DirectAccessPolicy.PROJECT_READ)), args);
        assertEquals(Boolean.TRUE, noWrite.isError(),
                () -> String.valueOf(noWrite.structuredContent()));
        assertTrue(String.valueOf(structured(noWrite).get("error"))
                        .contains(DirectAccessPolicy.PROJECT_WRITE),
                () -> String.valueOf(structured(noWrite)));

        CallToolResult noRead = call(ctx,
                exchange(Set.of(Capability.ONTOLOGY_ADMIN.value(),
                        DirectAccessPolicy.PROJECT_WRITE)), args);
        assertEquals(Boolean.TRUE, noRead.isError(),
                () -> String.valueOf(noRead.structuredContent()));
        assertTrue(String.valueOf(structured(noRead).get("error"))
                        .contains(DirectAccessPolicy.PROJECT_READ),
                () -> String.valueOf(structured(noRead)));
        assertFalse(Files.exists(target), "capability refusals must write nothing");
    }

    @Test
    void bootstrapWithoutAnExplicitPathIsRefused(@TempDir Path temp) throws Exception {
        writeInvalidPolicy(temp);
        ToolContext ctx = ctx(temp);

        CallToolResult result = call(ctx, Map.of("policy_bootstrap", true));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(structured(result).get("error"))
                        .contains("requires an explicit 'path'"),
                () -> String.valueOf(structured(result)));
    }

    @Test
    void underAValidPolicyTheFlagIsAReportedNoOp(@TempDir Path temp) throws Exception {
        // Materialized fixture: the named root artifact and RO-Crate exist, so the policy is valid.
        ProjectPolicyFixtures.writePolicy(temp.resolve(POLICY_RELATIVE), policyYaml());
        ToolContext ctx = ctx(temp);
        Path target = temp.resolve("ontology.ttl");

        CallToolResult result = call(ctx,
                Map.of("path", target.toString(), "policy_bootstrap", true));

        Map<String, Object> structured = structured(result);
        assertEquals(true, structured.get("saved"), () -> structured.toString());
        Map<?, ?> bootstrap = (Map<?, ?>) structured.get("policy_bootstrap");
        assertNotNull(bootstrap, () -> structured.toString());
        assertEquals(false, bootstrap.get("used"), bootstrap::toString);
        assertTrue(String.valueOf(bootstrap.get("reason")).contains("valid"), bootstrap::toString);
    }

    // ------------------------------------------------------------------ helpers

    private static String policyYaml() {
        return ProjectPolicyFixtures.minimalPolicy("bootstrap", ONTOLOGY_IRI)
                + "validation:\n  required_stages: [governance, structural]\n";
    }

    /**
     * The discovered policy file WITHOUT {@link ProjectPolicyFixtures#materialize}: its declared
     * root artifact and RO-Crate metadata do not exist, so the policy loads but is invalid.
     */
    private static void writeInvalidPolicy(Path temp) throws Exception {
        Path policyPath = temp.resolve(POLICY_RELATIVE);
        Files.createDirectories(policyPath.getParent());
        Files.writeString(policyPath, policyYaml(), StandardCharsets.UTF_8);
    }

    private static ToolContext ctx(Path temp) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Thing"))));
        return new ToolContext(HeadlessAccess.over(saveCapableManager(ontology)),
                new McpServerController(new OntologyAccess(null)));
    }

    /** FakeModelManager plus a real {@code save}: serialize to the bound document IRI/format. */
    private static OWLModelManager saveCapableManager(OWLOntology ontology) {
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                SaveOntologyPolicyBootstrapTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("save".equals(method.getName()) && args != null && args.length == 1) {
                        OWLOntology target = (OWLOntology) args[0];
                        target.getOWLOntologyManager().saveOntology(target);
                        return null;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static CallToolResult call(ToolContext ctx, Map<String, Object> args) {
        return call(ctx, ToolTestExchange.localAdmin(), args);
    }

    private static CallToolResult call(ToolContext ctx, McpSyncServerExchange exchange,
            Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        WriteTools.register(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals("save_ontology")) {
                return spec.callHandler().apply(exchange, new CallToolRequest("save_ontology", args));
            }
        }
        return fail("no tool named save_ontology");
    }

    private static McpSyncServerExchange exchange(Set<String> capabilities) {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                1, "test", "bootstrap-test", "Bootstrap test", capabilities, null);
        McpTransportContext context = McpTransportContext.create(
                Map.of(AuthenticatedPrincipal.CONTEXT_KEY, principal));
        return new McpSyncServerExchange(new McpAsyncServerExchange(
                "bootstrap-test-session", null, null, null, context));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }
}
