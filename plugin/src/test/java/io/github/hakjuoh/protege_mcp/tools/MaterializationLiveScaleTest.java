package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.history.HistoryManagerImpl;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNode;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceFingerprints;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationArtifact;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationCategory;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationInputIdentity;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationRequest;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationService;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerIdentity;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Opt-in gate over the exact live Protege model-thread commit implementation. */
@EnabledIfSystemProperty(named = "protege.performance", matches = "true")
class MaterializationLiveScaleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ONTOLOGY_IRI =
            "https://example.org/materialization-live-scale";

    @Test
    void maximumLiveBatchStaysInsideTheActualModelThreadBudget(@TempDir Path temp)
            throws Exception {
        JsonNode budget = JSON.readTree(Files.readAllBytes(
                Path.of("performance", "materialization-v1.json")));
        int count = budget.path("maximum_live_axioms").asInt();
        assertEquals(MaterializationTools.MAX_LIVE_MATERIALIZED_AXIOMS, count);
        Path document = temp.resolve("ontology.ttl");
        OWLOntology source = scaleSource(document, count);
        Path policyPath = writePolicy(temp, count);
        ProjectPolicy policy = ProjectPolicyLoader.load(
                policyPath, document, ONTOLOGY_IRI, List.of("HermiT"));
        assertTrue(policy.valid(), () -> policy.issues().toString());

        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        var manager = MaterializationToolsTest.wire(
                source, history, new AtomicInteger());
        OntologyAccess access = HeadlessAccess.overSwingEdt(manager);
        ToolContext context = new ToolContext(access, new McpServerController(access));
        try {
            IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                    manager.getOWLReasonerManager());
            ReasonerIdentity identity = spec.capabilityIdentity();
            ModelRevision revision = context.revisions().current(
                    manager, RevisionTools.digestImportLock(policy), policy.digest()).revision();
            MaterializationInputIdentity input = new MaterializationInputIdentity(
                    revision, WorkspaceFingerprints.closure(source.getImportsClosure()),
                    RevisionTools.digestImportLock(policy), null, policy.digest(),
                    RevisionTools.preflightDigest(policy), policy.path().toString(), identity);
            MaterializationRequest request = new MaterializationRequest(
                    List.of(MaterializationCategory.SUBCLASS_AXIOMS),
                    new MaterializationRequest.Destination("active_source", ONTOLOGY_IRI),
                    new MaterializationRequest.Provenance(
                            "protege-mcp-performance", "actual live commit path"),
                    new MaterializationRequest.Limits(count, count,
                            MaterializationRequest.MAX_BYTES,
                            budget.path("maximum_elapsed_ms").asLong()));
            OWLOntology isolated = isolatedCopy(source);
            OWLClass parent = isolated.getOWLOntologyManager().getOWLDataFactory().getOWLClass(
                    IRI.create(ONTOLOGY_IRI + "#Parent"));
            MaterializationArtifact artifact = new MaterializationService(
                    Clock.systemUTC()).preview(isolated,
                            ontology -> reasoner(ontology, parent),
                            new ReasonerCapabilityRegistry().report(identity), request, input);
            assertEquals(count, artifact.axioms().size());
            int beforeAxioms = source.getAxiomCount();
            AtomicLong modelThreadNanos = new AtomicLong();
            java.util.concurrent.atomic.AtomicBoolean ranOnEdt =
                    new java.util.concurrent.atomic.AtomicBoolean();

            CallToolResult result = context.access().computeMutation(modelManager -> {
                ranOnEdt.set(SwingUtilities.isEventDispatchThread());
                long started = System.nanoTime();
                try {
                    return MaterializationTools.commitOnModelThread(
                            context, modelManager, artifact, "reject",
                            spec.selectionKey(), identity);
                } finally {
                    modelThreadNanos.set(System.nanoTime() - started);
                }
            }, 30_000L);
            long elapsedMillis = modelThreadNanos.get() / 1_000_000L;
            long regressionLimit = regressionLimit(budget,
                    "baseline_model_thread_stall_ms");

            assertFalse(Boolean.TRUE.equals(result.isError()),
                    () -> String.valueOf(result.structuredContent()));
            assertEquals(beforeAxioms + count, source.getAxiomCount());
            assertEquals(1, history.getLoggedChanges().size(), history::toString);
            assertTrue(ranOnEdt.get(), "production gateway did not execute on the Swing EDT");
            assertEquals(Boolean.TRUE,
                    ((Map<?, ?>) result.structuredContent()).get("single_undo"));
            assertTrue(elapsedMillis <= regressionLimit,
                    () -> "actual live model-thread commit stalled for "
                            + elapsedMillis + " ms; limit=" + regressionLimit);

            Path output = Path.of("plugin", "target",
                    "materialization-live-performance-results.json");
            Files.createDirectories(output.getParent());
            Files.write(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                    "schema_version", 1,
                    "axioms", count,
                    "model_thread_commit_ms", elapsedMillis,
                    "history_entries", history.getLoggedChanges().size(),
                    "swing_edt", ranOnEdt.get(),
                    "single_undo", true,
                    "regression_limit_ms", regressionLimit,
                    "maximum_model_thread_stall_ms",
                    budget.path("maximum_model_thread_stall_ms").asLong())));
        } finally {
            context.dispose();
        }
    }

    private static long regressionLimit(JsonNode budget, String baselineField) {
        long absolute = budget.path("maximum_model_thread_stall_ms").asLong();
        double baseline = budget.path(baselineField).asDouble();
        double factor = budget.path("maximum_regression_factor").asDouble();
        double noise = budget.path("minimum_noise_floor_ms").asDouble();
        return Math.min(absolute,
                (long) Math.ceil(Math.max(baseline * factor, noise)));
    }

    private static Path writePolicy(Path root, int count) throws Exception {
        Path policy = root.resolve(".protege-mcp/project.yaml");
        String yaml = ProjectPolicyFixtures.minimalPolicy(
                "materialization-live-scale", ONTOLOGY_IRI)
                .replace("version: 1", "version: 2")
                + "validation:\n  required_stages: [structural]\n"
                + "materialization:\n"
                + "  allowed_reasoners: []\n"
                + "  allowed_categories: [subclass_axioms]\n"
                + "  allowed_destinations: [active_source]\n"
                + "  allow_source_write: true\n"
                + "  max_axioms_per_category: " + count + "\n"
                + "  max_axioms_total: " + count + "\n"
                + "  max_bytes: " + MaterializationRequest.MAX_BYTES + "\n"
                + "  timeout_ms: 120000\n";
        ProjectPolicyFixtures.writePolicy(policy, yaml);
        return policy;
    }

    private static OWLOntology scaleSource(Path document, int count) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(document.toUri()));
        var data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Parent"))));
        for (int index = 0; index < count; index++) {
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                    data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#C" + index))));
        }
        manager.saveOntology(ontology);
        return ontology;
    }

    private static OWLOntology isolatedCopy(OWLOntology source) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology isolated = manager.createOntology(source.getOntologyID());
        manager.addAxioms(isolated, source.getAxioms());
        return isolated;
    }

    private static OWLReasoner reasoner(OWLOntology ontology, OWLClass parent) {
        return (OWLReasoner) Proxy.newProxyInstance(
                MaterializationLiveScaleTest.class.getClassLoader(),
                new Class<?>[] {OWLReasoner.class}, (proxy, method, arguments) -> switch (
                        method.getName()) {
                    case "getRootOntology" -> ontology;
                    case "isConsistent" -> true;
                    case "getSuperClasses" -> parent.equals(arguments[0])
                            ? new OWLClassNodeSet()
                            : new OWLClassNodeSet(new OWLClassNode(parent));
                    case "interrupt", "dispose" -> null;
                    case "toString" -> "MaterializationLiveScaleReasoner";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
