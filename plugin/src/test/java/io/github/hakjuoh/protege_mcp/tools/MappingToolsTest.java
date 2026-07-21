package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** End-to-end coverage for the live Protégé mapping adapter and its write gates. */
class MappingToolsTest {

    private static final String ONTOLOGY = "https://example.org/ontology";

    private Preferences preferences;
    private boolean savedReadOnly;
    private boolean savedConfirm;
    private boolean savedCompatibility;

    @BeforeEach
    void pinWritablePreferences() {
        preferences = McpConfig.prefs();
        savedReadOnly = preferences.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = preferences.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        savedCompatibility = preferences.getBoolean(
                McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS, true);
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        preferences.putBoolean(McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS, true);
    }

    @AfterEach
    void restorePreferences() {
        preferences.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
        preferences.putBoolean(McpConfig.KEY_ALLOW_UNRESTRICTED_NO_POLICY_PATHS,
                savedCompatibility);
    }

    @Test
    void noPolicyRelativePathIsRootedBesideTheActiveDocument(@TempDir Path temporary)
            throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("ontology.ttl"), "# active document fixture\n");
        ToolContext context = context(project);

        CallToolResult result = call(context, "list_mappings", Map.of("path", "mappings.tsv"));

        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        assertEquals(project.toRealPath().resolve("mappings.tsv").toString(),
                Path.of(String.valueOf(structured(result).get("path"))).toAbsolutePath()
                        .normalize().toString());
    }

    @Test
    void liveAdapterListsAddsAndFailsClosedOnMissingConfirmationOrPathOverride(
            @TempDir Path temporary) throws Exception {
        Path project = temporary.resolve("project");
        writePolicy(project);
        ToolContext context = context(project);

        Map<String, Object> empty = structured(call(context, "list_mappings", Map.of()));
        assertEquals(false, empty.get("exists"));
        String emptyRevision = (String) empty.get("mapping_revision");

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("subject_id", ONTOLOGY + "#A");
        mapping.put("predicate_id", "skos:exactMatch");
        mapping.put("object_id", ONTOLOGY + "#B");
        mapping.put("mapping_justification", "semapv:ManualMappingCuration");
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("expected_mapping_revision", emptyRevision);
        add.put("mapping", mapping);
        add.put("mapping_set_id", "https://example.org/mappings");
        add.put("license", "https://creativecommons.org/licenses/by/4.0/");

        CallToolResult unconfirmed = call(context, "add_mapping", add);
        assertEquals(Boolean.TRUE, unconfirmed.isError());
        assertEquals("confirmation_required", structured(unconfirmed).get("code"));
        assertEquals(true, details(unconfirmed).get("effects_prevented"));
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));

        add.put("confirm", true);
        CallToolResult addedResult = call(context, "add_mapping", add);
        assertFalse(Boolean.TRUE.equals(addedResult.isError()),
                () -> String.valueOf(addedResult.structuredContent()));
        Map<String, Object> added = structured(addedResult);
        assertEquals(true, added.get("committed"));
        assertEquals(true, added.get("valid"), added::toString);
        String addedRevision = (String) added.get("mapping_revision");

        Map<String, Object> override = new LinkedHashMap<>(add);
        override.put("path", "other.tsv");
        override.put("expected_mapping_revision", addedRevision);
        CallToolResult refused = call(context, "add_mapping", override);
        assertEquals(Boolean.TRUE, refused.isError());
        assertEquals("invalid_request", structured(refused).get("code"));
        assertEquals(true, details(refused).get("effects_prevented"));
        assertTrue(String.valueOf(structured(refused).get("message"))
                .contains("cannot be overridden"));

        Map<String, Object> listed = structured(call(context, "list_mappings", Map.of()));
        assertEquals(addedRevision, listed.get("mapping_revision"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) listed.get("items");
        assertEquals(1, items.size());
        assertTrue(String.valueOf(items.get(0).get("mapping_id")).startsWith("sha256:"));

        Map<String, Object> validated = structured(call(context, "validate_mappings", Map.of()));
        assertEquals(true, validated.get("valid"));
        assertEquals(0, ((Number) validated.get("error_count")).intValue());

        Files.createDirectories(project.resolve("exports"));
        Map<String, Object> exported = structured(call(context, "export_sssom", Map.of(
                "expected_mapping_revision", addedRevision,
                "destination", "exports/mappings.tsv", "confirm", true)));
        assertEquals(addedRevision, exported.get("mapping_revision"));
        assertEquals(true, exported.get("lossless"));
        assertTrue(Files.isRegularFile(project.resolve("exports/mappings.tsv")));

        String mappingId = String.valueOf(items.get(0).get("mapping_id"));
        Map<String, Object> removed = structured(call(context, "remove_mapping", Map.of(
                "expected_mapping_revision", addedRevision, "mapping_id", mappingId,
                "confirm", true)));
        assertEquals(0, ((Number) removed.get("record_count")).intValue());
        String removedRevision = String.valueOf(removed.get("mapping_revision"));

        Map<String, Object> imported = structured(call(context, "import_sssom", Map.of(
                "expected_mapping_revision", removedRevision,
                "source", "exports/mappings.tsv", "mode", "replace", "confirm", true)));
        assertEquals(1, ((Number) imported.get("record_count")).intValue());
        assertEquals("replace", imported.get("mode"));
        assertEquals(1, ((Number) imported.get("source_records")).intValue());
    }

    @Test
    void readOnlyAndHumanDeclineAreTypedAsPrevented(@TempDir Path temporary) throws Exception {
        Path project = temporary.resolve("project");
        writePolicy(project);
        ToolContext writable = context(project);
        String revision = String.valueOf(structured(
                call(writable, "list_mappings", Map.of())).get("mapping_revision"));
        Map<String, Object> add = addArguments(revision);

        preferences.putBoolean(McpConfig.KEY_READ_ONLY, true);
        CallToolResult readOnly = call(writable, "add_mapping", add);
        assertEquals(Boolean.TRUE, readOnly.isError());
        assertEquals("read_only", structured(readOnly).get("code"));
        assertEquals(true, details(readOnly).get("effects_prevented"));

        preferences.putBoolean(McpConfig.KEY_READ_ONLY, false);
        preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, true);
        ToolContext declinedContext = context(project, summary -> false);
        CallToolResult declined = call(declinedContext, "add_mapping", add);
        assertEquals(Boolean.TRUE, declined.isError());
        assertEquals("write_declined", structured(declined).get("code"));
        assertEquals(true, details(declined).get("effects_prevented"));

        ToolContext preferenceDrift = context(project, summary -> {
            preferences.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
            return true;
        });
        CallToolResult drifted = call(preferenceDrift, "add_mapping", add);
        assertEquals(Boolean.TRUE, drifted.isError());
        assertEquals("confirmation_state_changed", structured(drifted).get("code"));
        assertEquals(true, details(drifted).get("effects_prevented"));
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void ontologyClosureDriftBeforeStoreCommitIsPrevented(@TempDir Path temporary)
            throws Exception {
        Path project = temporary.resolve("project");
        writePolicy(project);
        String revision = String.valueOf(structured(call(context(project),
                "list_mappings", Map.of())).get("mapping_revision"));
        AtomicInteger dispatches = new AtomicInteger();
        ToolContext drifting = contextWithClosureDrift(project, dispatches);

        CallToolResult result = call(drifting, "add_mapping", addArguments(revision));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("mutation_guard_failed", structured(result).get("code"));
        assertEquals(true, details(result).get("effects_prevented"));
        assertTrue(dispatches.get() >= 5, "the guard must recapture the live closure");
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));
    }

    @Test
    void invalidMutationInputIsReportedAsPrevented(@TempDir Path temporary) throws Exception {
        Path project = temporary.resolve("project");
        writePolicy(project);
        ToolContext context = context(project);
        Map<String, Object> add = addArguments("not-a-revision");

        CallToolResult result = call(context, "add_mapping", add);

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("invalid_request", structured(result).get("code"));
        assertEquals(true, details(result).get("effects_prevented"));
        assertFalse(Files.exists(project.resolve(".protege-mcp/mappings.sssom.tsv")));
    }

    private static ToolContext context(Path project) throws Exception {
        return context(project, null);
    }

    private static ToolContext context(Path project, WriteConfirmer confirmer) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(ONTOLOGY + "#A"))));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(ONTOLOGY + "#B"))));
        manager.setOntologyDocumentIRI(ontology, IRI.create(project.resolve("ontology.ttl").toUri()));
        OntologyAccess access = HeadlessAccess.over(FakeModelManager.over(ontology));
        McpServerController controller = new McpServerController(access);
        return new ToolContext(access, controller, confirmer);
    }

    private static ToolContext contextWithClosureDrift(Path project, AtomicInteger dispatches)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY));
        OWLDataFactory data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(ONTOLOGY + "#A"))));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create(ONTOLOGY + "#B"))));
        manager.setOntologyDocumentIRI(ontology, IRI.create(project.resolve("ontology.ttl").toUri()));
        AtomicBoolean changed = new AtomicBoolean();
        OntologyAccess access = HeadlessAccess.overHookedDispatches(FakeModelManager.over(ontology),
                () -> {
                    if (changed.compareAndSet(false, true)) {
                        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                                data.getOWLClass(IRI.create(ONTOLOGY + "#late"))));
                    }
                }, 4, dispatches);
        return new ToolContext(access, new McpServerController(access));
    }

    private static Map<String, Object> addArguments(String revision) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("subject_id", ONTOLOGY + "#A");
        mapping.put("predicate_id", "skos:exactMatch");
        mapping.put("object_id", ONTOLOGY + "#B");
        mapping.put("mapping_justification", "semapv:ManualMappingCuration");
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("expected_mapping_revision", revision);
        add.put("mapping", mapping);
        add.put("mapping_set_id", "https://example.org/mappings");
        add.put("license", "https://creativecommons.org/licenses/by/4.0/");
        add.put("confirm", true);
        return add;
    }

    private static void writePolicy(Path project) throws Exception {
        String policy = ProjectPolicyFixtures.minimalPolicy("mapping-live", ONTOLOGY)
                .replace("version: 1", "version: 2")
                + "prefixes:\n  skos: http://www.w3.org/2004/02/skos/core#\n"
                + "mappings:\n"
                + "  path: .protege-mcp/mappings.sssom.tsv\n"
                + "  allowed_predicates: [skos:exactMatch]\n"
                + "  allowed_sources: []\n"
                + "  allowed_licenses: [https://creativecommons.org/licenses/by/4.0/]\n"
                + "  require_license: true\n"
                + "  required_findings: []\n"
                + "  directional_cycle_policy:\n"
                + "    skos:broadMatch: error\n"
                + "    skos:narrowMatch: error\n"
                + "  many_to_one_rules: []\n"
                + "validation:\n  required_stages: [structural]\n";
        ProjectPolicyFixtures.writePolicy(project.resolve(".protege-mcp/project.yaml"), policy);
    }

    private static CallToolResult call(ToolContext context, String name,
            Map<String, Object> arguments) {
        ToolRegistry registry = new ToolRegistry();
        MappingTools.register(registry, context);
        for (SyncToolSpecification specification : registry.build()) {
            if (name.equals(specification.tool().name())) {
                return specification.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest(name, arguments));
            }
        }
        throw new AssertionError("No mapping tool named " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(CallToolResult result) {
        return (Map<String, Object>) structured(result).get("details");
    }
}
