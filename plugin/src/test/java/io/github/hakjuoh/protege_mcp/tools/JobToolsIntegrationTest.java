package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
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

class JobToolsIntegrationTest {
    private static final String ONTOLOGY_IRI = "https://example.org/jobs";

    private final List<ToolContext> contexts = new ArrayList<>();
    private Preferences preferences;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void enableWrites() {
        preferences = McpConfig.prefs();
        savedReadOnly = preferences.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = preferences.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void cleanup() {
        contexts.forEach(ToolContext::dispose);
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    @Test
    void semanticDiffLifecycleIsOwnerScopedIdempotentAndAtomicallyExportable(
            @TempDir Path temp) throws Exception {
        ToolContext context = context(temp, null);
        Path policy = policy(temp, null,
                "[classification, project_qc, semantic_diff, inference_materialization]");
        Path firstRight = rightOntology(temp.resolve("right-one.ttl"), "RightOne");
        Path secondRight = rightOntology(temp.resolve("right-two.ttl"), "RightTwo");
        McpSyncServerExchange owner = exchange(
                AuthenticatedPrincipal.oauthAdmin("client-a", "A", "grant-a"));
        Map<String, SyncToolSpecification> tools = jobTools(context);
        Map<String, Object> request = Map.of(
                "right_document", firstRight.toString(),
                "include_imports", false,
                "network", "deny",
                "limit", 20,
                "policy_path", policy.toString());

        Map<String, Object> accepted = success(call(tools, owner, "start_job", Map.of(
                "type", "semantic_diff",
                "idempotency_key", "diff-stable",
                "request", request)));
        Map<String, Object> reused = success(call(tools, owner, "start_job", Map.of(
                "type", "semantic_diff",
                "idempotency_key", "diff-stable",
                "request", request)));
        assertEquals(true, reused.get("reused"));
        String jobId = string(map(accepted.get("job")), "job_id");
        assertEquals(jobId, string(map(reused.get("job")), "job_id"));

        Map<String, Object> completed = await(tools, owner, jobId);
        assertEquals("succeeded", completed.get("state"), completed::toString);
        Map<String, Object> result = map(completed.get("result"));
        Map<String, Object> summary = map(result.get("structured"));
        assertEquals("semantic_diff", summary.get("kind"));
        assertTrue(((Number) summary.get("added_axioms")).longValue() >= 0);
        List<?> artifacts = list(result.get("artifacts"));
        assertEquals(1, artifacts.size());
        String artifactId = string(map(artifacts.get(0)), "artifact_id");

        Map<String, Object> listed = success(call(
                tools, owner, "list_jobs", Map.of("limit", 10)));
        assertTrue(list(listed.get("jobs")).stream().map(JobToolsIntegrationTest::map)
                .anyMatch(job -> jobId.equals(job.get("job_id"))));

        McpSyncServerExchange other = exchange(
                AuthenticatedPrincipal.oauthAdmin("client-b", "B", "grant-b"));
        CallToolResult hidden = call(
                tools, other, "get_job", Map.of("job_id", jobId));
        assertError(hidden, "unknown_job");
        assertTrue(list(success(call(tools, other,
                "list_jobs", Map.of())).get("jobs")).isEmpty());

        Path target = temp.resolve("reports/diff-job.json");
        Files.createDirectories(target.getParent());
        Map<String, Object> exported = success(call(
                tools, owner, "export_job_artifact", Map.of(
                        "job_id", jobId,
                        "artifact_id", artifactId,
                        "destination", target.toString(),
                        "confirm", true,
                        "policy_path", policy.toString())));
        assertEquals(true, exported.get("exported"));
        assertTrue(Files.isRegularFile(target));
        String firstDigest = string(exported, "sha256");
        assertError(call(tools, owner, "export_job_artifact", Map.of(
                "job_id", jobId,
                "artifact_id", artifactId,
                "destination", target.toString(),
                "confirm", true,
                "policy_path", policy.toString())), "job_artifact_target_exists");
        assertError(call(tools, owner, "export_job_artifact", Map.of(
                "job_id", jobId,
                "artifact_id", artifactId,
                "destination", target.toString(),
                "confirm", true,
                "overwrite", true,
                "policy_path", policy.toString())),
                "expected_target_digest_required");
        Map<String, Object> replaced = success(call(
                tools, owner, "export_job_artifact", Map.of(
                        "job_id", jobId,
                        "artifact_id", artifactId,
                        "destination", target.toString(),
                        "confirm", true,
                        "overwrite", true,
                        "expected_target_digest", firstDigest,
                        "policy_path", policy.toString())));
        assertEquals(true, replaced.get("overwritten"));
        assertEquals(firstDigest, replaced.get("sha256"));

        Map<String, Object> changedRequest = new LinkedHashMap<>(request);
        changedRequest.put("right_document", secondRight.toString());
        assertError(call(tools, owner, "start_job", Map.of(
                "type", "semantic_diff",
                "idempotency_key", "diff-stable",
                "request", changedRequest)), "idempotency_conflict");
    }

    @Test
    void projectQcCompletesWithoutAReasonerAndPolicyCanDisableItsAdmission(
            @TempDir Path temp) throws Exception {
        ToolContext context = context(temp, null);
        Path policy = policy(temp, null, "[project_qc]");
        Map<String, SyncToolSpecification> tools = jobTools(context);
        McpSyncServerExchange owner = ToolTestExchange.localAdmin();

        Map<String, Object> accepted = success(call(tools, owner, "start_job", Map.of(
                "type", "project_qc",
                "idempotency_key", "qc-one",
                "request", Map.of(
                        "lock_mode", "ignore",
                        "policy_path", policy.toString()))));
        Map<String, Object> completed = await(
                tools, owner, string(map(accepted.get("job")), "job_id"));

        assertEquals("succeeded", completed.get("state"), completed::toString);
        Map<String, Object> summary =
                map(map(completed.get("result")).get("structured"));
        assertEquals("project_qc", summary.get("kind"));
        assertTrue(Set.of("pass", "fail", "error").contains(summary.get("gate")));
        assertTrue(((Number) summary.get("stages_ran")).intValue() > 0);

        assertError(call(tools, owner, "start_job", Map.of(
                "type", "semantic_diff",
                "idempotency_key", "disabled",
                "request", Map.of(
                        "right_document", rightOntology(
                                temp.resolve("disabled.ttl"), "Disabled").toString(),
                        "policy_path", policy.toString()))), "job_type_disabled");
    }

    @Test
    void reasonerJobsRejectUnprovenCancellationAndReportCapabilityLimitsHonestly(
            @TempDir Path temp) throws Exception {
        ToolContext hermit = context(temp.resolve("hermit"),
                new org.semanticweb.HermiT.ReasonerFactory());
        Path hermitPolicy = policy(temp.resolve("hermit"), null, "[classification]");
        Map<String, SyncToolSpecification> hermitTools = jobTools(hermit);
        assertError(call(hermitTools, ToolTestExchange.localAdmin(), "start_job", Map.of(
                "type", "classification",
                "idempotency_key", "hermit-classification",
                "request", Map.of("policy_path", hermitPolicy.toString()))),
                "job_reasoner_not_cancellable");

        ToolContext structural = context(temp.resolve("structural"),
                new StructuralReasonerFactory());
        Path structuralPolicy = policy(temp.resolve("structural"),
                "Structural Reasoner", "[classification, inference_materialization]");
        Map<String, SyncToolSpecification> tools = jobTools(structural);
        Map<String, Object> classification = success(call(
                tools, ToolTestExchange.localAdmin(), "start_job", Map.of(
                        "type", "classification",
                        "idempotency_key", "structural-classification",
                        "request", Map.of(
                                "policy_path", structuralPolicy.toString()))));
        Map<String, Object> classified = await(
                tools, ToolTestExchange.localAdmin(),
                string(map(classification.get("job")), "job_id"));
        assertEquals("succeeded", classified.get("state"), classified::toString);
        Map<String, Object> classificationSummary =
                map(map(classified.get("result")).get("structured"));
        assertEquals(true, classificationSummary.get("classification_completed"));
        assertEquals("unsupported", classificationSummary.get("consistency_status"));
        assertEquals("unsupported", classificationSummary.get("unsatisfiable_status"));
        assertEquals(null, classificationSummary.get("unsatisfiable_count"));
        assertEquals(true, classificationSummary.get("capability_limited"));

        Map<String, Object> accepted = success(call(
                tools, ToolTestExchange.localAdmin(), "start_job", Map.of(
                        "type", "inference_materialization",
                        "idempotency_key", "structural-materialization",
                        "request", Map.of(
                                "categories", List.of("subclass_axioms"),
                                "destination", Map.of(
                                        "kind", "new_ontology",
                                        "identifier", "https://example.org/jobs/materialized"),
                                "provenance", Map.of(
                                        "generator", "protege-mcp-test",
                                        "purpose", "async contract"),
                                "limits", Map.of(
                                        "max_axioms_per_category", 100,
                                        "max_axioms_total", 100,
                                        "max_bytes", 1_048_576,
                                        "timeout_ms", 30_000),
                                "policy_path", structuralPolicy.toString()))));
        Map<String, Object> completed = await(
                tools, ToolTestExchange.localAdmin(),
                string(map(accepted.get("job")), "job_id"));

        assertEquals("failed", completed.get("state"), completed::toString);
        assertEquals("materialization_consistency_not_supported",
                map(completed.get("error")).get("code"));
        assertEquals(null, completed.get("result"));
    }

    private ToolContext context(Path temp, OWLReasonerFactory reasoner)
            throws Exception {
        Files.createDirectories(temp);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(
                ontology, IRI.create(temp.resolve("ontology.ttl").toUri()));
        var data = manager.getOWLDataFactory();
        var first = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#A"));
        var second = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#B"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(first));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(second));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(first, second));
        manager.saveOntology(ontology);

        OWLModelManager base = FakeModelManager.over(ontology);
        OWLReasonerManager reasoners = reasonerManager(reasoner);
        OWLModelManager model = (OWLModelManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) return reasoners;
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        OntologyAccess access = HeadlessAccess.over(model);
        ToolContext context =
                new ToolContext(access, new McpServerController(access));
        contexts.add(context);
        return context;
    }

    private static OWLReasonerManager reasonerManager(
            OWLReasonerFactory factory) {
        ProtegeOWLReasonerInfo info = factory == null ? null : reasonerInfo(factory);
        return (OWLReasonerManager) Proxy.newProxyInstance(
                JobToolsIntegrationTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                    case "getCurrentReasonerFactory" -> info;
                    case "getCurrentReasonerFactoryId" ->
                            info == null ? null : info.getReasonerId();
                    case "getCurrentReasonerName" ->
                            info == null ? null : info.getReasonerName();
                    case "getInstalledReasonerFactories" ->
                            info == null ? Set.of() : Set.of(info);
                    case "toString" -> "JobReasonerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ProtegeOWLReasonerInfo reasonerInfo(
            OWLReasonerFactory factory) {
        String structural = StructuralReasonerFactory.class.getName();
        String id = factory.getClass().getName();
        String name = structural.equals(id) ? "Structural Reasoner" : factory.getReasonerName();
        return (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                JobToolsIntegrationTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getReasonerId" -> id;
                    case "getReasonerName" -> name;
                    case "getReasonerFactory" -> factory;
                    case "getRecommendedBuffering" -> BufferingMode.BUFFERING;
                    case "getConfiguration" -> new SimpleConfiguration();
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Path policy(Path temp, String allowedReasoner, String allowedTypes)
            throws Exception {
        Path path = temp.resolve(".protege-mcp/project.yaml");
        String reasoners = allowedReasoner == null
                ? "[]" : "[" + allowedReasoner + "]";
        String yaml = ProjectPolicyFixtures.minimalPolicy("job-tools", ONTOLOGY_IRI)
                .replace("version: 1", "version: 2")
                + "validation:\n  required_stages: [structural]\n"
                + "jobs:\n"
                + "  allowed_types: " + allowedTypes + "\n"
                + "  workers: 2\n"
                + "  queue_capacity: 32\n"
                + "  active_per_principal: 8\n"
                + "  retained_per_principal: 32\n"
                + "  retained_per_backend: 128\n"
                + "  retention_seconds: 3600\n"
                + "materialization:\n"
                + "  allowed_reasoners: " + reasoners + "\n"
                + "  allowed_categories: [subclass_axioms]\n"
                + "  allowed_destinations: [new_ontology]\n"
                + "  allow_source_write: false\n"
                + "  max_axioms_per_category: 500\n"
                + "  max_axioms_total: 500\n"
                + "  max_bytes: 1048576\n"
                + "  timeout_ms: 30000\n";
        ProjectPolicyFixtures.writePolicy(path, yaml);
        return path.toAbsolutePath().normalize();
    }

    private static Path rightOntology(Path path, String localName)
            throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                <https://example.org/right> a owl:Ontology .
                <https://example.org/right#%s> a owl:Class ;
                    rdfs:label "%s" .
                """.formatted(localName, localName));
        return path.toAbsolutePath().normalize();
    }

    private static Map<String, SyncToolSpecification> jobTools(
            ToolContext context) {
        ToolRegistry registry =
                new ToolRegistry(context.audit(), context.executions());
        JobTools.register(registry, context);
        return index(registry.build());
    }

    private static Map<String, SyncToolSpecification> index(
            List<SyncToolSpecification> specifications) {
        Map<String, SyncToolSpecification> out = new LinkedHashMap<>();
        specifications.forEach(spec -> out.put(spec.tool().name(), spec));
        return out;
    }

    private static CallToolResult call(Map<String, SyncToolSpecification> tools,
            McpSyncServerExchange exchange, String name,
            Map<String, Object> arguments) {
        return tools.get(name).callHandler().apply(
                exchange, new CallToolRequest(name, arguments));
    }

    private static Map<String, Object> await(
            Map<String, SyncToolSpecification> tools,
            McpSyncServerExchange exchange, String jobId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Map<String, Object> job;
        do {
            job = map(success(call(tools, exchange,
                    "get_job", Map.of("job_id", jobId))).get("job"));
            if (Set.of("succeeded", "failed", "cancelled")
                    .contains(job.get("state"))) return job;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("job did not complete: " + job);
    }

    private static McpSyncServerExchange exchange(
            AuthenticatedPrincipal principal) {
        McpTransportContext context = McpTransportContext.create(Map.of(
                AuthenticatedPrincipal.CONTEXT_KEY, principal));
        return new McpSyncServerExchange(new McpAsyncServerExchange(
                "job-tools-test", null, null, null, context));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> success(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }

    private static void assertError(CallToolResult result, String code) {
        assertEquals(true, result.isError(), () ->
                String.valueOf(result.structuredContent()));
        assertEquals(code, map(result.structuredContent()).get("code"),
                () -> String.valueOf(result.structuredContent()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        return (List<?>) value;
    }

    private static String string(Map<String, Object> value, String key) {
        return String.valueOf(value.get(key));
    }
}
