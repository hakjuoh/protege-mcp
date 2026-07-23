package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.history.HistoryManagerImpl;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

class MaterializationToolsTest {
    private static final String ONTOLOGY_IRI = "https://example.org/materialization-source";
    private Preferences preferences;
    private String savedToken;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void enableWrites() {
        preferences = McpConfig.prefs();
        savedToken = preferences.getString(McpConfig.KEY_TOKEN, "");
        savedReadOnly = preferences.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = preferences.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restorePreferences() {
        preferences.putString(McpConfig.KEY_TOKEN, savedToken);
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    @Test
    void livePreviewChangesNothingAndCommitUsesVerifiedModelManagerPaths(@TempDir Path temp)
            throws Exception {
        Path policy = writePolicy(temp);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        var loadedPolicy = io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                policy, temp.resolve("ontology.ttl"), ONTOLOGY_IRI, List.of("HermiT"));
        assertTrue(loadedPolicy.valid(), () -> loadedPolicy.issues().toString());
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        AtomicInteger creations = new AtomicInteger();
        ToolContext context = context(wire(source, history, creations));
        int sourceAxioms = source.getAxiomCount();

        Map<String, Object> preview = success(call(context, "materialize_inferences", Map.of(
                "categories", List.of("subclass_axioms", "equivalent_class_axioms",
                        "class_assertions", "property_hierarchy_axioms",
                        "object_property_assertions", "data_property_assertions"),
                "destination", Map.of("kind", "new_ontology",
                        "identifier", "https://example.org/materialized"),
                "provenance", Map.of("generator", "protege-mcp-test",
                        "purpose", "single undo contract"),
                "limits", Map.of("max_axioms_per_category", 100,
                        "max_axioms_total", 100, "max_bytes", 1_048_576,
                        "timeout_ms", 30_000),
                "policy_path", policy.toString())));
        assertEquals(true, preview.get("preview_only"));
        assertEquals(false, preview.get("live_state_changed"));
        assertEquals(6, ((List<?>) preview.get("categories")).size());
        assertEquals(6, ((List<?>) preview.get("produced_categories")).size(),
                preview::toString);
        assertEquals(sourceAxioms, source.getAxiomCount());
        assertEquals(null, source.getOWLOntologyManager().getOntology(
                IRI.create("https://example.org/materialized")));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) preview.get("artifact");

        for (String category : List.of("subclass_axioms", "equivalent_class_axioms",
                "class_assertions", "property_hierarchy_axioms",
                "object_property_assertions", "data_property_assertions")) {
            CallToolResult overflow = call(context, "materialize_inferences", Map.of(
                    "categories", List.of(category),
                    "destination", Map.of("kind", "new_ontology",
                            "identifier", "https://example.org/overflow/" + category),
                    "provenance", Map.of("generator", "protege-mcp-test",
                            "purpose", "whole category overflow contract"),
                    "limits", Map.of("max_axioms_per_category", 1,
                            "max_axioms_total", 100, "max_bytes", 1_048_576,
                            "timeout_ms", 30_000),
                    "policy_path", policy.toString()));
            assertEquals(true, overflow.isError(), category);
            assertEquals("materialization_bound_exceeded",
                    ((Map<?, ?>) overflow.structuredContent()).get("code"), category);
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>)
                    ((Map<?, ?>) overflow.structuredContent()).get("details");
            assertEquals(true, details.get("category_discarded"), category);
        }
        CallToolResult sourceAlias = call(context, "materialize_inferences", Map.of(
                "categories", List.of("subclass_axioms"),
                "destination", Map.of("kind", "new_ontology",
                        "identifier", ONTOLOGY_IRI),
                "provenance", Map.of("generator", "protege-mcp-test",
                        "purpose", "source alias refusal contract"),
                "limits", Map.of("max_axioms_per_category", 100,
                        "max_axioms_total", 100, "max_bytes", 1_048_576,
                        "timeout_ms", 30_000),
                "policy_path", policy.toString()));
        assertEquals(true, sourceAlias.isError());
        assertEquals("materialization_destination_conflict",
                ((Map<?, ?>) sourceAlias.structuredContent()).get("code"));
        assertEquals(sourceAxioms, source.getAxiomCount());

        CallToolResult unconfirmed = call(context, "commit_materialization", Map.of(
                "artifact_id", artifact.get("artifact_id"),
                "artifact_fingerprint", artifact.get("artifact_fingerprint")));
        assertEquals(true, unconfirmed.isError());
        assertEquals("confirmation_required",
                ((Map<?, ?>) unconfirmed.structuredContent()).get("code"));

        Map<String, Object> committed = success(call(context, "commit_materialization", Map.of(
                "artifact_id", artifact.get("artifact_id"),
                "artifact_fingerprint", artifact.get("artifact_fingerprint"),
                "confirm", true)));
        assertEquals(true, committed.get("committed"), committed::toString);
        assertEquals(false, committed.get("single_undo"));
        assertEquals(1, creations.get());
        assertEquals(1, history.getLoggedChanges().size(), history::toString);
        OWLOntology destination = source.getOWLOntologyManager().getOntology(
                IRI.create("https://example.org/materialized"));
        assertTrue(destination != null && destination.getAxiomCount() > 0);
        assertTrue(destination.getAxioms().stream().allMatch(axiom -> axiom.isAnnotated()));
        assertEquals(sourceAxioms, source.getAxiomCount());

        Map<String, Object> repeated = success(call(context, "commit_materialization", Map.of(
                "artifact_id", artifact.get("artifact_id"),
                "artifact_fingerprint", artifact.get("artifact_fingerprint"),
                "confirm", true)));
        assertEquals(false, repeated.get("committed"), repeated::toString);
        assertEquals("noop", repeated.get("status"));
        assertEquals(1, history.getLoggedChanges().size());

        var late = source.getOWLOntologyManager().getOWLDataFactory().getOWLClass(
                IRI.create(ONTOLOGY_IRI + "#LateEdit"));
        source.getOWLOntologyManager().addAxiom(source,
                source.getOWLOntologyManager().getOWLDataFactory()
                        .getOWLDeclarationAxiom(late));
        CallToolResult staleNoop = call(context, "commit_materialization", Map.of(
                "artifact_id", artifact.get("artifact_id"),
                "artifact_fingerprint", artifact.get("artifact_fingerprint"),
                "confirm", true));
        assertEquals(true, staleNoop.isError());
        assertEquals("materialization_revision_conflict",
                ((Map<?, ?>) staleNoop.structuredContent()).get("code"));

        Map<String, Object> sourcePreview = success(call(context,
                "materialize_inferences", Map.of(
                        "categories", List.of("subclass_axioms"),
                        "destination", Map.of("kind", "active_source",
                                "identifier", ONTOLOGY_IRI),
                        "provenance", Map.of("generator", "protege-mcp-test",
                                "purpose", "active source contract"),
                        "limits", Map.of("max_axioms_per_category", 100,
                                "max_axioms_total", 100, "max_bytes", 1_048_576,
                                "timeout_ms", 30_000),
                        "policy_path", policy.toString())));
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceArtifact =
                (Map<String, Object>) sourcePreview.get("artifact");
        CallToolResult missingSourceConfirmation = call(context,
                "commit_materialization", Map.of(
                        "artifact_id", sourceArtifact.get("artifact_id"),
                        "artifact_fingerprint", sourceArtifact.get("artifact_fingerprint"),
                        "confirm", true));
        assertEquals(true, missingSourceConfirmation.isError());
        assertEquals("materialization_source_write_confirmation_required",
                ((Map<?, ?>) missingSourceConfirmation.structuredContent()).get("code"));
        int beforeSourceCommit = source.getAxiomCount();
        Map<String, Object> sourceCommitted = success(call(context,
                "commit_materialization", Map.of(
                        "artifact_id", sourceArtifact.get("artifact_id"),
                        "artifact_fingerprint", sourceArtifact.get("artifact_fingerprint"),
                        "confirm", true,
                        "allow_source", true)));
        assertEquals(true, sourceCommitted.get("committed"), sourceCommitted::toString);
        assertEquals(true, sourceCommitted.get("single_undo"));
        assertTrue(source.getAxiomCount() > beforeSourceCommit);
        context.dispose();
    }

    @Test
    void livePreviewCannotUseAnExternalPolicyPath(@TempDir Path temp) throws Exception {
        writePolicy(temp);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        ToolContext context = context(wire(source, history, new AtomicInteger()));
        var liveDiscovered = RevisionTools.resolvePolicy(context, null).policy();
        assertTrue(liveDiscovered.valid(), () -> liveDiscovered.issues().toString());
        Path outside = Files.createTempFile(temp.getParent(),
                "materialization-external-policy-", ".yaml");
        try {
            Files.writeString(outside, "version: 2\n");
            CallToolResult refused = call(context, "materialize_inferences", Map.of(
                    "categories", List.of("subclass_axioms"),
                    "destination", Map.of("kind", "new_ontology",
                            "identifier", "https://example.org/external-policy-result"),
                    "provenance", Map.of("generator", "protege-mcp-test",
                            "purpose", "external policy refusal"),
                    "limits", Map.of("max_axioms_per_category", 100,
                            "max_axioms_total", 100, "max_bytes", 1_048_576,
                            "timeout_ms", 30_000),
                    "policy_path", outside.toString()));

            assertEquals(true, refused.isError());
            assertTrue(String.valueOf(refused.structuredContent())
                    .contains("outside project_root"),
                    () -> String.valueOf(refused.structuredContent()));
            assertEquals(null, source.getOWLOntologyManager().getOntology(
                    IRI.create("https://example.org/external-policy-result")));
        } finally {
            context.dispose();
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void livePreviewRejectsLimitsAboveTheModelThreadEnvelope(@TempDir Path temp)
            throws Exception {
        writePolicy(temp);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        ToolContext context = context(wire(source, history, new AtomicInteger()));
        try {
            CallToolResult refused = call(context, "materialize_inferences", Map.of(
                    "categories", List.of("subclass_axioms"),
                    "destination", Map.of("kind", "new_ontology",
                            "identifier", "https://example.org/oversized-live"),
                    "provenance", Map.of("generator", "protege-mcp-test",
                            "purpose", "live stall envelope"),
                    "limits", Map.of("max_axioms_per_category", 501,
                            "max_axioms_total", 501, "max_bytes", 1_048_576,
                            "timeout_ms", 30_000)));

            assertEquals(true, refused.isError());
            assertEquals("materialization_live_limit_exceeded",
                    ((Map<?, ?>) refused.structuredContent()).get("code"));
        } finally {
            context.dispose();
        }
    }

    @Test
    void destinationIdentifierAmbiguityAcrossOntologyAndVersionIrisIsRejected(
            @TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        String identifier = "https://example.org/ambiguous-destination";
        source.getOWLOntologyManager().createOntology(new OWLOntologyID(
                IRI.create(identifier), IRI.create(identifier + "/v1")));
        source.getOWLOntologyManager().createOntology(new OWLOntologyID(
                IRI.create("https://example.org/other-destination"), IRI.create(identifier)));
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        ToolContext context = context(wire(source, history, new AtomicInteger()));
        try {
            CallToolResult refused = call(context, "materialize_inferences", Map.of(
                    "categories", List.of("subclass_axioms"),
                    "destination", Map.of("kind", "new_ontology",
                            "identifier", identifier),
                    "provenance", Map.of("generator", "protege-mcp-test",
                            "purpose", "ambiguous identifier contract"),
                    "limits", Map.of("max_axioms_per_category", 100,
                            "max_axioms_total", 100, "max_bytes", 1_048_576,
                            "timeout_ms", 30_000),
                    "policy_path", policy.toString()));

            assertEquals(true, refused.isError());
            assertEquals("materialization_destination_ambiguous",
                    ((Map<?, ?>) refused.structuredContent()).get("code"));
        } finally {
            context.dispose();
        }
    }

    @Test
    void commitReauthorizesThePinnedPolicyWhenTheArgumentIsOmitted(@TempDir Path temp)
            throws Exception {
        Path discovered = writePolicy(temp, true);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        var loadedDiscovered = io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                discovered, temp.resolve("ontology.ttl"), ONTOLOGY_IRI, List.of("HermiT"));
        assertTrue(loadedDiscovered.valid(), () -> loadedDiscovered.issues().toString());
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        ToolContext context = context(wire(source, history, new AtomicInteger()));
        Path outside = Files.createTempFile(temp.getParent(),
                "materialization-authorized-policy-", ".yaml");
        try {
            String externalYaml = policyYaml("materialization-external")
                    .replace("path: ro-crate-metadata.json",
                            "path: ro-crate-metadata.jsonld")
                    .replace("format: ro-crate-1.1", "format: ro-crate-1.0")
                    + "project_root: " + temp.getFileName() + "\n";
            ProjectPolicyFixtures.writePolicy(outside, externalYaml);
            var loadedExternal = io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                    outside, temp.resolve("ontology.ttl"), ONTOLOGY_IRI, List.of("HermiT"));
            assertTrue(loadedExternal.valid(), () -> loadedExternal.issues().toString());
            Map<String, Object> preview = success(call(context,
                    "materialize_inferences", Map.of(
                            "categories", List.of("subclass_axioms"),
                            "destination", Map.of("kind", "new_ontology",
                                    "identifier", "https://example.org/pinned-policy-result"),
                            "provenance", Map.of("generator", "protege-mcp-test",
                                    "purpose", "pinned policy authorization"),
                            "limits", Map.of("max_axioms_per_category", 100,
                                    "max_axioms_total", 100, "max_bytes", 1_048_576,
                                    "timeout_ms", 30_000),
                            "policy_path", outside.toString())));
            @SuppressWarnings("unchecked")
            Map<String, Object> artifact = (Map<String, Object>) preview.get("artifact");

            ProjectPolicyFixtures.writePolicy(discovered,
                    policyYaml("materialization-test")
                            + "filesystem:\n  allow_external_paths: false\n");
            CallToolResult refused = call(context, "commit_materialization", Map.of(
                    "artifact_id", artifact.get("artifact_id"),
                    "artifact_fingerprint", artifact.get("artifact_fingerprint"),
                    "confirm", true));

            assertEquals(true, refused.isError());
            assertTrue(String.valueOf(refused.structuredContent())
                    .contains("outside project_root"),
                    () -> String.valueOf(refused.structuredContent()));
            assertEquals(null, source.getOWLOntologyManager().getOntology(
                    IRI.create("https://example.org/pinned-policy-result")));
        } finally {
            context.dispose();
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void replaceCommitReportsUnknownWhenAnAlternateFormSurvivesApply(@TempDir Path temp)
            throws Exception {
        Path policy = writePolicy(temp);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        ToolContext context = context(wireWithApplyFailure(
                source, history, new AtomicInteger()));
        try {
            Map<String, Object> preview = success(call(context,
                    "materialize_inferences", Map.of(
                            "categories", List.of("subclass_axioms"),
                            "destination", Map.of("kind", "active_source",
                                    "identifier", ONTOLOGY_IRI),
                            "provenance", Map.of("generator", "protege-mcp-test",
                                    "purpose", "partial replace verification"),
                            "limits", Map.of("max_axioms_per_category", 100,
                                    "max_axioms_total", 100, "max_bytes", 1_048_576,
                                    "timeout_ms", 30_000),
                            "policy_path", policy.toString())));
            @SuppressWarnings("unchecked")
            Map<String, Object> artifact = (Map<String, Object>) preview.get("artifact");

            CallToolResult failed = call(context, "commit_materialization", Map.of(
                    "artifact_id", artifact.get("artifact_id"),
                    "artifact_fingerprint", artifact.get("artifact_fingerprint"),
                    "confirm", true,
                    "allow_source", true,
                    "collision_mode", "replace"));

            assertEquals(true, failed.isError());
            assertEquals("materialization_commit_outcome_unknown",
                    ((Map<?, ?>) failed.structuredContent()).get("code"));
            assertTrue(source.getAxioms().stream().anyMatch(axiom -> axiom.isAnnotated()
                    && axiom.getAnnotations().stream().anyMatch(annotation -> {
                        var literal = annotation.getValue().asLiteral().orNull();
                        return literal != null && "alternate".equals(literal.getLiteral());
                    })));
        } finally {
            context.dispose();
        }
    }

    @Test
    void commitReportsUnknownWhenApplyCompletesBeforeTheAdapterThrows(@TempDir Path temp)
            throws Exception {
        Path policy = writePolicy(temp);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        ToolContext context = context(wireWithCompleteApplyFailure(
                source, history, new AtomicInteger()));
        try {
            Map<String, Object> preview = success(call(context,
                    "materialize_inferences", Map.of(
                            "categories", List.of("subclass_axioms"),
                            "destination", Map.of("kind", "active_source",
                                    "identifier", ONTOLOGY_IRI),
                            "provenance", Map.of("generator", "protege-mcp-test",
                                    "purpose", "complete apply failure verification"),
                            "limits", Map.of("max_axioms_per_category", 100,
                                    "max_axioms_total", 100, "max_bytes", 1_048_576,
                                    "timeout_ms", 30_000),
                            "policy_path", policy.toString())));
            @SuppressWarnings("unchecked")
            Map<String, Object> artifact = (Map<String, Object>) preview.get("artifact");

            CallToolResult failed = call(context, "commit_materialization", Map.of(
                    "artifact_id", artifact.get("artifact_id"),
                    "artifact_fingerprint", artifact.get("artifact_fingerprint"),
                    "confirm", true,
                    "allow_source", true));

            assertEquals(true, failed.isError());
            Map<?, ?> details = (Map<?, ?>) failed.structuredContent();
            assertEquals("materialization_commit_outcome_unknown", details.get("code"));
            assertTrue(String.valueOf(details).contains("axiom_state_complete=true"),
                    details::toString);
        } finally {
            context.dispose();
        }
    }

    @Test
    void hiddenReasonerConfigurationDriftIsRejectedOnTheFinalCommitHop(
            @TempDir Path temp) throws Exception {
        Path policy = writePolicy(temp);
        OWLOntology source = sourceOntology(temp.resolve("ontology.ttl"));
        HistoryManager history = new HistoryManagerImpl(source.getOWLOntologyManager());
        source.getOWLOntologyManager().addOntologyChangeListener(history::logChanges);
        ToolContext context = context(wireWithConfigurationDrift(
                source, history, new AtomicInteger()));
        try {
            Map<String, Object> preview = success(call(context,
                    "materialize_inferences", Map.of(
                            "categories", List.of("subclass_axioms"),
                            "destination", Map.of("kind", "active_source",
                                    "identifier", ONTOLOGY_IRI),
                            "provenance", Map.of("generator", "protege-mcp-test",
                                    "purpose", "hidden configuration drift"),
                            "limits", Map.of("max_axioms_per_category", 100,
                                    "max_axioms_total", 100, "max_bytes", 1_048_576,
                                    "timeout_ms", 30_000),
                            "policy_path", policy.toString())));
            @SuppressWarnings("unchecked")
            Map<String, Object> artifact = (Map<String, Object>) preview.get("artifact");
            int axiomCount = source.getAxiomCount();

            CallToolResult failed = call(context, "commit_materialization", Map.of(
                    "artifact_id", artifact.get("artifact_id"),
                    "artifact_fingerprint", artifact.get("artifact_fingerprint"),
                    "confirm", true,
                    "allow_source", true));

            assertEquals(true, failed.isError());
            assertEquals("materialization_input_changed",
                    ((Map<?, ?>) failed.structuredContent()).get("code"));
            assertEquals(axiomCount, source.getAxiomCount());
        } finally {
            context.dispose();
        }
    }

    private static Path writePolicy(Path root) throws Exception {
        return writePolicy(root, false);
    }

    private static Path writePolicy(Path root, boolean allowExternal) throws Exception {
        Path policy = root.resolve(".protege-mcp/project.yaml");
        String yaml = policyYaml("materialization-test")
                + "filesystem:\n  allow_external_paths: " + allowExternal + "\n";
        ProjectPolicyFixtures.writePolicy(policy, yaml);
        return policy;
    }

    private static String policyYaml(String projectId) {
        return ProjectPolicyFixtures.minimalPolicy(projectId, ONTOLOGY_IRI)
                .replace("version: 1", "version: 2")
                + "validation:\n"
                        + "  required_stages: [structural]\n"
                + "materialization:\n"
                        + "  allowed_reasoners: []\n"
                        + "  allowed_categories: [subclass_axioms, equivalent_class_axioms, class_assertions, property_hierarchy_axioms, object_property_assertions, data_property_assertions]\n"
                        + "  allowed_destinations: [new_ontology, active_source]\n"
                        + "  allow_source_write: true\n"
                        + "  max_axioms_per_category: 100\n"
                        + "  max_axioms_total: 100\n"
                        + "  max_bytes: 1048576\n"
                        + "  timeout_ms: 30000\n";
    }

    private static OWLOntology sourceOntology(Path document) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(document.toUri()));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#A"));
        var b = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#B"));
        var c = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#C"));
        var e = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#E"));
        var f = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#F"));
        var g = data.getOWLClass(IRI.create(ONTOLOGY_IRI + "#G"));
        var i = data.getOWLNamedIndividual(IRI.create(ONTOLOGY_IRI + "#i"));
        var j = data.getOWLNamedIndividual(IRI.create(ONTOLOGY_IRI + "#j"));
        var p = data.getOWLObjectProperty(IRI.create(ONTOLOGY_IRI + "#p"));
        var q = data.getOWLObjectProperty(IRI.create(ONTOLOGY_IRI + "#q"));
        var r = data.getOWLObjectProperty(IRI.create(ONTOLOGY_IRI + "#r"));
        var dp = data.getOWLDataProperty(IRI.create(ONTOLOGY_IRI + "#dp"));
        var dq = data.getOWLDataProperty(IRI.create(ONTOLOGY_IRI + "#dq"));
        var dr = data.getOWLDataProperty(IRI.create(ONTOLOGY_IRI + "#dr"));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(b, c));
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(e, f));
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(f, g));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, i));
        manager.addAxiom(ontology, data.getOWLSubObjectPropertyOfAxiom(p, q));
        manager.addAxiom(ontology, data.getOWLSubObjectPropertyOfAxiom(q, r));
        manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(p, i, j));
        manager.addAxiom(ontology, data.getOWLSubDataPropertyOfAxiom(dp, dq));
        manager.addAxiom(ontology, data.getOWLSubDataPropertyOfAxiom(dq, dr));
        manager.addAxiom(ontology, data.getOWLDataPropertyAssertionAxiom(
                dp, i, data.getOWLLiteral("value")));
        manager.saveOntology(ontology);
        return ontology;
    }

    static ToolContext context(OWLModelManager manager) {
        OntologyAccess access = HeadlessAccess.over(manager);
        return new ToolContext(access, new McpServerController(access));
    }

    static OWLModelManager wire(OWLOntology ontology, HistoryManager history,
            AtomicInteger creations) {
        return wire(ontology, history, creations, ApplyFailure.NONE);
    }

    private static OWLModelManager wireWithApplyFailure(OWLOntology ontology,
            HistoryManager history, AtomicInteger creations) {
        return wire(ontology, history, creations, ApplyFailure.ALTERNATE_FORM);
    }

    private static OWLModelManager wireWithCompleteApplyFailure(OWLOntology ontology,
            HistoryManager history, AtomicInteger creations) {
        return wire(ontology, history, creations, ApplyFailure.AFTER_COMPLETE);
    }

    private static OWLModelManager wireWithConfigurationDrift(OWLOntology ontology,
            HistoryManager history, AtomicInteger creations) {
        var delegate = new org.semanticweb.HermiT.ProtegeReasonerFactory();
        delegate.setup(ontology.getOWLOntologyManager(),
                "HermiT.reasoner.factory", "HermiT");
        AtomicInteger captures = new AtomicInteger();
        ProtegeOWLReasonerInfo drifting = (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                MaterializationToolsTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) -> switch (
                        method.getName()) {
                    case "getConfiguration" -> {
                        org.semanticweb.HermiT.Configuration configuration =
                                (org.semanticweb.HermiT.Configuration)
                                        delegate.getConfiguration(
                                                (org.semanticweb.owlapi.reasoner.ReasonerProgressMonitor)
                                                        args[0]);
                        if (captures.incrementAndGet() >= 3) {
                            configuration = configuration.clone();
                            configuration.ignoreUnsupportedDatatypes =
                                    !configuration.ignoreUnsupportedDatatypes;
                        }
                        yield configuration;
                    }
                    case "getReasonerFactory" -> delegate.getReasonerFactory();
                    case "getRecommendedBuffering" -> delegate.getRecommendedBuffering();
                    case "getReasonerId" -> delegate.getReasonerId();
                    case "getReasonerName" -> delegate.getReasonerName();
                    case "getOWLModelManager" -> delegate.getOWLModelManager();
                    case "setOWLModelManager" -> {
                        delegate.setOWLModelManager((OWLModelManager) args[0]);
                        yield null;
                    }
                    case "setup" -> {
                        delegate.setup((OWLOntologyManager) args[0],
                                (String) args[1], (String) args[2]);
                        yield null;
                    }
                    case "dispose" -> null;
                    case "toString" -> "DriftingReasonerInfo";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return wire(ontology, history, creations, ApplyFailure.NONE, drifting);
    }

    private static OWLModelManager wire(OWLOntology ontology, HistoryManager history,
            AtomicInteger creations, ApplyFailure applyFailure) {
        var info = new org.semanticweb.HermiT.ProtegeReasonerFactory();
        info.setup(ontology.getOWLOntologyManager(), "HermiT.reasoner.factory", "HermiT");
        return wire(ontology, history, creations, applyFailure, info);
    }

    private static OWLModelManager wire(OWLOntology ontology, HistoryManager history,
            AtomicInteger creations, ApplyFailure applyFailure,
            ProtegeOWLReasonerInfo info) {
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                MaterializationToolsTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) -> switch (
                        method.getName()) {
                    case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                    case "getCurrentReasonerFactory" -> info;
                    case "getCurrentReasonerFactoryId" -> info.getReasonerId();
                    case "getCurrentReasonerName" -> info.getReasonerName();
                    case "getInstalledReasonerFactories" -> Set.of(info);
                    case "toString" -> "MaterializationReasonerManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                MaterializationToolsTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOWLReasonerManager":
                            return reasoners;
                        case "getHistoryManager":
                            return history;
                        case "getDirtyOntologies":
                            return Set.of(ontology);
                        case "createNewOntology":
                            creations.incrementAndGet();
                            return ontology.getOWLOntologyManager().createOntology(
                                    (org.semanticweb.owlapi.model.OWLOntologyID) args[0]);
                        case "setActiveOntology":
                            return null;
                        case "removeOntology":
                            ontology.getOWLOntologyManager().removeOntology(
                                    (OWLOntology) args[0]);
                            return null;
                        case "applyChanges":
                            if (applyFailure != ApplyFailure.NONE) {
                                @SuppressWarnings("unchecked")
                                List<org.semanticweb.owlapi.model.OWLOntologyChange> changes =
                                        (List<org.semanticweb.owlapi.model.OWLOntologyChange>) args[0];
                                if (applyFailure == ApplyFailure.ALTERNATE_FORM) {
                                    org.semanticweb.owlapi.model.AddAxiom first = changes.stream()
                                            .filter(org.semanticweb.owlapi.model.AddAxiom.class::isInstance)
                                            .map(org.semanticweb.owlapi.model.AddAxiom.class::cast)
                                            .findFirst().orElseThrow();
                                    var data = ontology.getOWLOntologyManager().getOWLDataFactory();
                                    var alternate = first.getAxiom().getAxiomWithoutAnnotations()
                                            .getAnnotatedAxiom(Set.of(data.getOWLAnnotation(
                                                    data.getRDFSComment(),
                                                    data.getOWLLiteral("alternate"))));
                                    ontology.getOWLOntologyManager().addAxiom(
                                            first.getOntology(), alternate);
                                }
                                ontology.getOWLOntologyManager().applyChanges(changes);
                                throw new IllegalStateException("injected adapter failure");
                            }
                            try {
                                return method.invoke(base, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        default:
                            try {
                                return method.invoke(base, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                    }
                });
    }

    private enum ApplyFailure {
        NONE,
        ALTERNATE_FORM,
        AFTER_COMPLETE
    }

    private static CallToolResult call(ToolContext context, String name,
            Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        MaterializationTools.register(registry, context);
        return registry.build().stream().filter(spec -> name.equals(spec.tool().name()))
                .findFirst().orElseThrow().callHandler().apply(
                        ToolTestExchange.localAdmin(), new CallToolRequest(name, arguments));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> success(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
