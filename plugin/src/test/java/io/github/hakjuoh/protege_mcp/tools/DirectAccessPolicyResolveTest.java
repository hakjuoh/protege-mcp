package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The PRODUCTION authorization entry of {@link DirectAccessPolicy}: {@code resolve} /
 * {@code requireCapability} driven through a REAL {@link McpSyncServerExchange} whose transport
 * context carries (or lacks) the propagated {@link AuthenticatedPrincipal} — exactly what
 * {@code McpServerManager}'s context extractor produces — instead of the null-exchange test seam
 * that inherits the historic local-admin profile.
 */
class DirectAccessPolicyResolveTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";

    @Test
    void aRealExchangeWithoutAPropagatedPrincipalFailsClosed(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(temp);
        McpSyncServerExchange noPrincipal = exchange(null);

        ToolArgException resolveDenied = assertThrows(ToolArgException.class,
                () -> DirectAccessPolicy.resolve(ctx, noPrincipal),
                "an empty production transport context must NOT inherit the null-seam admin profile");
        assertTrue(resolveDenied.getMessage().contains(
                "requires capability " + DirectAccessPolicy.PROJECT_READ), resolveDenied.getMessage());

        ToolArgException capabilityDenied = assertThrows(ToolArgException.class,
                () -> DirectAccessPolicy.requireCapability(noPrincipal, DirectAccessPolicy.NETWORK));
        assertTrue(capabilityDenied.getMessage().contains("Operation requires capability"),
                capabilityDenied.getMessage());

        // Through a registered handler the refusal surfaces as a guarded MCP error result.
        CallToolResult result = call(ProjectPolicyTools::register, ctx, noPrincipal,
                "validate_project_policy", Map.of());
        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.content()));
        assertTrue(String.valueOf(result.content()).contains(DirectAccessPolicy.PROJECT_READ),
                () -> String.valueOf(result.content()));
    }

    @Test
    void aPrincipalWithoutProjectReadIsRefusedByResolve(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(temp);
        McpSyncServerExchange exchange = exchange(principal(Set.of("ontology:read")));

        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> DirectAccessPolicy.resolve(ctx, exchange));

        assertEquals("Resolving project filesystem/network policy requires capability "
                + DirectAccessPolicy.PROJECT_READ + ".", denied.getMessage());
    }

    @Test
    void aLocalAdminPrincipalPassesResolveAndCapabilityChecks(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(temp);
        McpSyncServerExchange admin = exchange(principal(Set.of(DirectAccessPolicy.LOCAL_ADMIN)));

        DirectAccessPolicy.Rules rules = assertDoesNotThrow(
                () -> DirectAccessPolicy.resolve(ctx, admin));

        assertNotNull(rules);
        assertFalse(rules.policy().loaded(), "no policy is discoverable under a temp directory");
        assertDoesNotThrow(
                () -> DirectAccessPolicy.requireCapability(admin, DirectAccessPolicy.NETWORK),
                "local:admin implies every capability");
    }

    /**
     * The Group-A regression: a DISCOVERED loaded-but-invalid policy must not make {@code resolve()}
     * throw — the diagnostic tools' structured invalid-policy reports (per-issue paths/messages, the
     * revision envelope) must stay reachable through the registered handlers, with every
     * filesystem/network authorization failing closed at use time instead.
     */
    @Test
    void aDiscoveredInvalidPolicyKeepsStructuredDiagnosticsReachable(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        ProjectPolicyFixtures.writePolicy(project.resolve(".protege-mcp/project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("invalid-discovered", ONTOLOGY_IRI)
                        + "modules:\n  - ontology_iri: https://example.org/missing\n"
                        + "    path: missing.rdf\n");
        ToolContext ctx = context(project);
        McpSyncServerExchange exchange = exchange(
                principal(Set.of(Capability.ONTOLOGY_READ.value(),
                        DirectAccessPolicy.PROJECT_READ)));

        DirectAccessPolicy.Rules rules = assertDoesNotThrow(
                () -> DirectAccessPolicy.resolve(ctx, exchange, null),
                "a loaded-but-invalid discovered policy must not fail resolve()");
        assertTrue(rules.policy().loaded());
        assertFalse(rules.policy().valid(), "a module naming a missing file invalidates the policy");

        CallToolResult validate = call(ProjectPolicyTools::register, ctx, exchange,
                "validate_project_policy", Map.of());
        assertFalse(Boolean.TRUE.equals(validate.isError()),
                () -> "validate_project_policy must report, not refuse: "
                        + String.valueOf(validate.content()));
        Map<String, Object> report = structured(validate);
        assertEquals(true, report.get("policy_loaded"));
        assertEquals(false, report.get("valid"));
        List<?> errors = (List<?>) report.get("errors");
        assertFalse(errors.isEmpty(), () -> report.toString());
        assertTrue(errors.stream().map(issue -> (Map<?, ?>) issue).anyMatch(
                issue -> "asset_missing".equals(issue.get("code"))
                        && "modules[0].path".equals(issue.get("path"))
                        && String.valueOf(issue.get("message")).contains("missing.rdf")),
                () -> "the per-issue path/message diagnostics must survive: " + errors);

        CallToolResult revision = call(RevisionTools::register, ctx, exchange,
                "get_model_revision", Map.of());
        assertFalse(Boolean.TRUE.equals(revision.isError()),
                () -> "get_model_revision must stay readable while the policy is being fixed: "
                        + String.valueOf(revision.content()));
        Map<String, Object> envelope = structured(revision);
        assertNotNull(envelope.get("revision"), () -> envelope.toString());
        assertEquals(true, envelope.get("policy_loaded"));
        assertEquals(false, envelope.get("policy_valid"));
        assertTrue(String.valueOf(envelope.get("semantic_fingerprint")).startsWith("sha256:"));
    }

    /**
     * The same recovery for the EXPLICIT policy_path shape: while the discovered policy is invalid, a
     * candidate-fix file inside the project root is authorized by canonical containment (not by
     * trusting the invalid policy), so validate_project_policy on it still returns a structured
     * report; a path outside the root is refused.
     */
    @Test
    void anExplicitPolicyPathInsideTheProjectValidatesUnderAnInvalidDiscoveredPolicy(
            @TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        ProjectPolicyFixtures.writePolicy(project.resolve(".protege-mcp/project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("invalid-discovered", ONTOLOGY_IRI)
                        + "modules:\n  - ontology_iri: https://example.org/missing\n"
                        + "    path: missing.rdf\n");
        // A valid candidate fix drafted inside the project root.
        Path candidate = project.resolve("candidate.yaml");
        ProjectPolicyFixtures.writePolicy(candidate,
                ProjectPolicyFixtures.minimalPolicy("candidate", ONTOLOGY_IRI)
                        + "validation:\n  required_stages: [structural]\n");
        ToolContext ctx = context(project);
        McpSyncServerExchange exchange = exchange(principal(Set.of(
                Capability.ONTOLOGY_READ.value(), DirectAccessPolicy.PROJECT_READ)));

        CallToolResult validate = call(ProjectPolicyTools::register, ctx, exchange,
                "validate_project_policy", Map.of("policy_path", candidate.toString()));
        assertFalse(Boolean.TRUE.equals(validate.isError()),
                () -> "an in-project candidate policy must validate, not be refused: "
                        + String.valueOf(validate.content()));
        Map<String, Object> report = structured(validate);
        assertEquals(true, report.get("valid"),
                () -> "the candidate fix should validate: " + report);

        // A policy_path outside the project root is still refused.
        Path outside = temp.resolve("outside.yaml");
        ProjectPolicyFixtures.writePolicy(outside,
                ProjectPolicyFixtures.minimalPolicy("outside", ONTOLOGY_IRI));
        CallToolResult refused = call(ProjectPolicyTools::register, ctx, exchange,
                "validate_project_policy", Map.of("policy_path", outside.toString()));
        assertEquals(Boolean.TRUE, refused.isError(), () -> String.valueOf(refused.content()));
        assertTrue(String.valueOf(refused.content()).contains("outside project_root"),
                () -> String.valueOf(refused.content()));
    }

    /**
     * When the discovered policy is invalid because its {@code project_root} is unresolvable
     * (projectRoot()==null), the CONVENTIONAL relative policy_path ({@code .protege-mcp/project.yaml},
     * rooted at project_root) must still reach that file's structured diagnostics — not double into a
     * {@code .protege-mcp/.protege-mcp/...} path reported as policy_not_found.
     */
    @Test
    void aRelativePolicyPathReachesDiagnosticsWhenTheDiscoveredProjectRootIsUnresolvable(
            @TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        // A SCHEMA-VALID policy (with the interoperability block) whose project_root escapes the base
        // -> validation reaches project_root_escape and projectRoot() is null (the documented
        // diagnostic-recovery condition). Write the file directly (no RO-Crate materialization) so
        // nothing creates the escape target; the interoperability assets are never validated because
        // the null project_root short-circuits semantic validation.
        Files.createDirectories(project.resolve(".protege-mcp"));
        Files.writeString(project.resolve(".protege-mcp/project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("unresolved-root", ONTOLOGY_IRI)
                        + "project_root: ../escape\n");
        ToolContext ctx = context(project);
        McpSyncServerExchange exchange = exchange(principal(Set.of(
                Capability.ONTOLOGY_READ.value(), DirectAccessPolicy.PROJECT_READ)));

        CallToolResult validate = call(ProjectPolicyTools::register, ctx, exchange,
                "validate_project_policy", Map.of("policy_path", ".protege-mcp/project.yaml"));
        assertFalse(Boolean.TRUE.equals(validate.isError()),
                () -> "the conventional relative policy_path must reach diagnostics, not error: "
                        + String.valueOf(validate.content()));
        Map<String, Object> report = structured(validate);
        assertEquals(false, report.get("valid"), report::toString);
        assertTrue(String.valueOf(report).contains("project_root"),
                () -> "expected the real project_root diagnostics: " + report);
        assertFalse(String.valueOf(report).contains("policy_not_found"),
                () -> "the relative path must not double into a not-found path: " + report);
    }

    // ------------------------------------------------------------------ fixtures

    /** A real sync exchange whose transport context optionally carries a propagated principal. */
    private static McpSyncServerExchange exchange(AuthenticatedPrincipal principal) {
        McpTransportContext context = principal == null ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(AuthenticatedPrincipal.CONTEXT_KEY, principal));
        return new McpSyncServerExchange(new McpAsyncServerExchange(
                "direct-access-test-session", null, null, null, context));
    }

    private static AuthenticatedPrincipal principal(Set<String> capabilities) {
        return new AuthenticatedPrincipal(1, "test", "test-client", "Test", capabilities, null);
    }

    /** Invoke a tool exactly as the server would: through its registered call handler. */
    private static CallToolResult call(BiConsumer<ToolRegistry, ToolContext> register,
            ToolContext ctx, McpSyncServerExchange exchange, String tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        register.accept(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals(tool)) {
                return spec.callHandler().apply(exchange, new CallToolRequest(tool, args));
            }
        }
        throw new AssertionError("no tool named " + tool);
    }

    private static ToolContext context(Path documentDir) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(documentDir.resolve("ontology.ttl").toUri()));
        return new ToolContext(HeadlessAccess.over(dirtyAware(FakeModelManager.over(ontology))), null);
    }

    /**
     * {@code get_model_revision} reads {@code getDirtyOntologies}, which the shared fake fails
     * loudly on; report a clean workspace without touching the shared fixture.
     */
    private static OWLModelManager dirtyAware(OWLModelManager base) {
        return (OWLModelManager) Proxy.newProxyInstance(
                DirectAccessPolicyResolveTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getDirtyOntologies".equals(method.getName())) {
                        return Set.of();
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }
}
