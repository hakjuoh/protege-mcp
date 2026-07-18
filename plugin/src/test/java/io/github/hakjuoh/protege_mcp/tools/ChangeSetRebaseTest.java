package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * rebase_change_set contract: deterministic re-resolution over the headless adapter. Success mints a
 * fresh preview at the current revision and keeps the original; ANY resolution difference — a display
 * name resolving to another IRI, an operand that stops resolving — fails closed with a structured
 * {@code rebase_conflict}, never an auto-merge. The comparison runs inside the same captured hop as
 * the plan that would be cached, so a rename cannot slip between verification and caching.
 */
class ChangeSetRebaseTest {

    private static final String ONTOLOGY_IRI = "https://example.org/rebase";

    private Preferences prefs;
    private String savedToken;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void pinWritePrefs() {
        prefs = McpConfig.prefs();
        savedToken = prefs.getString(McpConfig.KEY_TOKEN, "");
        savedReadOnly = String.valueOf(prefs.getBoolean(McpConfig.KEY_READ_ONLY, false));
        savedConfirm = String.valueOf(prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restoreWritePrefs() {
        prefs.putString(McpConfig.KEY_TOKEN, savedToken);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, Boolean.parseBoolean(savedReadOnly));
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, Boolean.parseBoolean(savedConfirm));
    }

    @Test
    void unrelatedConcurrentEditRebasesToAFreshCommittablePreview(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        String originalId = (String) preview.get("change_set_id");

        // An unrelated concurrent edit invalidates the original base revision but not resolution.
        addAxiom(ontology, df(ontology).getOWLAnnotationAssertionAxiom(df(ontology).getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Animal"), df(ontology).getOWLLiteral("kingdom")));

        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals(originalId, rebased.get("rebased_from"), rebased::toString);
        assertEquals(Boolean.TRUE, rebased.get("resolution_verified"));
        assertNotNull(rebased.get("change_set_id"));
        assertNotEquals(originalId, rebased.get("change_set_id"),
                "a rebase mints a NEW preview entry");
        assertNotEquals(preview.get("base_revision"), rebased.get("base_revision"),
                "the new preview must pin the moved revision");
        assertEquals(Boolean.TRUE, rebased.get("committable"), rebased::toString);

        Map<String, Object> commitArgs = new LinkedHashMap<>();
        commitArgs.put("change_set_id", rebased.get("change_set_id"));
        commitArgs.put("expected_revision", rebased.get("base_revision"));
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs));
        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        assertTrue(ontology.containsAxiom(plannedAxiom(ontology)));

        // The original preview survives a successful rebase until explicitly discarded.
        assertTrue(ctx.changeSets().discard(originalId),
                "the original entry must remain discardable after a rebase");
    }

    @Test
    void displayNameResolvingToAnotherIriFailsTheRebaseClosed(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        // The operand references Foo by its short display name, resolved through the entity finder.
        Map<String, Object> preview = committablePreview(ctx, subclassByName());
        String originalId = (String) preview.get("change_set_id");

        // Concurrent "rename": the short name Foo now resolves to a DIFFERENT IRI.
        OWLDataFactory df = df(ontology);
        OWLClass oldFoo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        OWLClass newFoo = df.getOWLClass(IRI.create("https://example.org/elsewhere#Foo"));
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.removeAxioms(ontology, ontology.getReferencingAxioms(oldFoo));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(newFoo));

        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals("rebase_conflict", rebased.get("error_code"), rebased::toString);
        assertEquals(Boolean.FALSE, rebased.get("rebased"));
        List<?> conflicts = (List<?>) rebased.get("conflicts");
        assertEquals(1, conflicts.size(), rebased::toString);
        Map<?, ?> row = (Map<?, ?>) conflicts.get(0);
        assertEquals(0, row.get("position"));
        assertTrue(String.valueOf(row.get("was")).contains(ONTOLOGY_IRI + "#Foo"), row::toString);
        assertTrue(String.valueOf(row.get("now")).contains("elsewhere#Foo"), row::toString);
        assertNotNull(rebased.get("base_revision"), "conflicts keep the complete revision envelopes");
        assertNotNull(rebased.get("current_revision"));
        assertTrue(ctx.changeSets().discard(originalId),
                "a conflict must leave the original entry intact");
        assertFalse(ontology.containsAxiom(plannedAxiom(ontology)),
                "a rebase never mutates the live ontology");
    }

    @Test
    void operandThatStopsResolvingFailsTheRebaseClosed(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByName());
        String originalId = (String) preview.get("change_set_id");

        OWLDataFactory df = df(ontology);
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        ontology.getOWLOntologyManager().removeAxioms(ontology, ontology.getReferencingAxioms(foo));

        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals("rebase_conflict", rebased.get("error_code"), rebased::toString);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) rebased.get("conflicts")).get(0);
        assertEquals(ChangePlanner.RESOLVED_ERROR, row.get("now"), row::toString);
    }

    @Test
    void concurrentCommitOfTheSameAxiomRebasesToANoOpPreview(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        String originalId = (String) preview.get("change_set_id");

        // The same axiom lands concurrently: resolution is UNCHANGED, only the delta shrinks.
        addAxiom(ontology, plannedAxiom(ontology));

        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals(originalId, rebased.get("rebased_from"), rebased::toString);
        assertEquals(0, rebased.get("normalized_changes"),
                "final-set semantics absorb the already-applied add as a no-op");
        assertEquals(Boolean.TRUE, rebased.get("committable"), rebased::toString);
    }

    @Test
    void curationPreviewRebasesThroughItsOwnPlanner(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        List<Map<String, Object>> terms = List.of(new LinkedHashMap<>(Map.of(
                "name", "Habitat", "namespace", ONTOLOGY_IRI + "#")));
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("terms", terms);
        replay.put("strict", false);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", terms);
        Map<String, Object> preview = structured(ChangeSetTools.previewPrepared(ctx, args,
                mm -> CurationTools.planTerms(mm, terms, false, null, null),
                ChangeSetTools.KIND_CREATE_TERMS, replay));
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        String originalId = (String) preview.get("change_set_id");

        addAxiom(ontology, df(ontology).getOWLAnnotationAssertionAxiom(df(ontology).getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Animal"), df(ontology).getOWLLiteral("kingdom")));

        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals(originalId, rebased.get("rebased_from"), rebased::toString);
        assertEquals(Boolean.TRUE, rebased.get("committable"), rebased::toString);
        assertNotEquals(preview.get("base_revision"), rebased.get("base_revision"));
    }

    @Test
    void explicitGatesAreReplayedSoARebaseCannotWeakenThePreflight(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        // gates:["reasoner"] with no reasoner selected makes the stage REQUIRED and the preview
        // non-committable; a rebase that dropped the argument would fall back to the default gate
        // set where the skip is benign, silently weakening the contract.
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(subclassByIri()));
        args.put("gates", List.of("reasoner"));
        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx, args));
        assertEquals(Boolean.FALSE, preview.get("committable"), preview::toString);
        String originalId = (String) preview.get("change_set_id");

        addAxiom(ontology, df(ontology).getOWLAnnotationAssertionAxiom(df(ontology).getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Animal"), df(ontology).getOWLLiteral("kingdom")));
        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals(originalId, rebased.get("rebased_from"), rebased::toString);
        assertEquals(Boolean.FALSE, rebased.get("committable"),
                "the replayed gates must keep the rebased preview exactly as strict: " + rebased);
    }

    @Test
    void curationRebaseAbsorbsAConcurrentlyAssertedAxiomAsANoOp(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        List<Map<String, Object>> terms = List.of(new LinkedHashMap<>(Map.of(
                "name", "Habitat", "namespace", ONTOLOGY_IRI + "#", "parents", List.of("Animal"))));
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("terms", terms);
        replay.put("strict", false);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", terms);
        Map<String, Object> preview = structured(ChangeSetTools.previewPrepared(ctx, args,
                mm -> CurationTools.planTerms(mm, terms, false, null, null),
                ChangeSetTools.KIND_CREATE_TERMS, replay));
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        String originalId = (String) preview.get("change_set_id");

        // Exactly the parent axiom the spec resolves lands concurrently: resolution is UNCHANGED,
        // so the rebase must succeed with the add absorbed — not conflict on the filtered list.
        OWLDataFactory df = df(ontology);
        addAxiom(ontology, df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Habitat")),
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"))));

        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals(originalId, rebased.get("rebased_from"), rebased::toString);
        assertEquals(Boolean.TRUE, rebased.get("committable"), rebased::toString);
    }

    @Test
    void createPropertiesPreviewRebasesThroughItsOwnPlanner(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        List<Map<String, Object>> properties = List.of(new LinkedHashMap<>(Map.of(
                "name", "inhabits", "namespace", ONTOLOGY_IRI + "#")));
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("properties", properties);
        replay.put("strict", false);
        replay.put("property_type", "object");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("properties", properties);
        Map<String, Object> preview = structured(ChangeSetTools.previewPrepared(ctx, args,
                mm -> CurationTools.planProperties(mm, properties, false, null, null, "object"),
                ChangeSetTools.KIND_CREATE_PROPERTIES, replay));
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        String originalId = (String) preview.get("change_set_id");

        addAxiom(ontology, df(ontology).getOWLAnnotationAssertionAxiom(df(ontology).getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Animal"), df(ontology).getOWLLiteral("kingdom")));
        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals(originalId, rebased.get("rebased_from"), rebased::toString);
        assertEquals(Boolean.TRUE, rebased.get("committable"), rebased::toString);
    }

    @Test
    void aRebasedPreviewCanItselfBeRebased(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        String firstId = (String) preview.get("change_set_id");

        addAxiom(ontology, df(ontology).getOWLAnnotationAssertionAxiom(df(ontology).getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Animal"), df(ontology).getOWLLiteral("first")));
        Map<String, Object> first = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", firstId)));
        assertEquals(Boolean.TRUE, first.get("committable"), first::toString);

        addAxiom(ontology, df(ontology).getOWLAnnotationAssertionAxiom(df(ontology).getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Animal"), df(ontology).getOWLLiteral("second")));
        Map<String, Object> second = structured(ChangeSetTools.rebase(ctx, null,
                Map.of("change_set_id", first.get("change_set_id"))));

        assertEquals(first.get("change_set_id"), second.get("rebased_from"), second::toString);
        assertEquals(Boolean.TRUE, second.get("committable"), second::toString);
    }

    @Test
    void policyLoadedRebaseReplaysTheDiscoveredPolicy(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeDiscoveredPolicy(temp);
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        assertEquals(Boolean.TRUE, preview.get("policy_loaded"), preview::toString);
        String originalId = (String) preview.get("change_set_id");

        addAxiom(ontology, df(ontology).getOWLAnnotationAssertionAxiom(df(ontology).getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Animal"), df(ontology).getOWLLiteral("kingdom")));
        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals(originalId, rebased.get("rebased_from"), rebased::toString);
        assertEquals(Boolean.TRUE, rebased.get("policy_loaded"), rebased::toString);
        assertEquals(preview.get("policy_digest"), rebased.get("policy_digest"),
                "the rebased preview must pin the same discovered policy");
        assertEquals(Boolean.TRUE, rebased.get("committable"), rebased::toString);
    }

    @Test
    void curationReplayThatCannotRunFailsStructured(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = df(ontology);
        var obsNote = df.getOWLAnnotationProperty(IRI.create(ONTOLOGY_IRI + "#obsNote"));
        addAxiom(ontology, df.getOWLDeclarationAxiom(obsNote));
        addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                obsNote.getIRI(), df.getOWLLiteral("obsNote")));
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        // The definition property is referenced by SHORT NAME, so resolution goes through the
        // entity finder — the operand that will stop resolving after the concurrent delete.
        List<Map<String, Object>> terms = List.of(new LinkedHashMap<>(Map.of(
                "name", "Habitat", "namespace", ONTOLOGY_IRI + "#",
                "definition", "A place organisms live in.",
                "definition_property", "obsNote")));
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("terms", terms);
        replay.put("strict", false);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", terms);
        Map<String, Object> preview = structured(ChangeSetTools.previewPrepared(ctx, args,
                mm -> CurationTools.planTerms(mm, terms, false, null, null),
                ChangeSetTools.KIND_CREATE_TERMS, replay));
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        String originalId = (String) preview.get("change_set_id");

        // Deleting the property makes the batch builder itself refuse — the re-plan cannot run.
        ontology.getOWLOntologyManager().removeAxioms(ontology,
                ontology.getReferencingAxioms(obsNote));
        ontology.getOWLOntologyManager().removeAxiom(ontology,
                df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), obsNote.getIRI(),
                        df.getOWLLiteral("obsNote")));

        Map<String, Object> rebased = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", originalId)));

        assertEquals("rebase_failed", rebased.get("error_code"), rebased::toString);
        assertEquals(Boolean.FALSE, rebased.get("rebased"));
        assertNotNull(rebased.get("reason"));
        assertTrue(ctx.changeSets().discard(originalId),
                "a failed rebase must leave the original entry intact");
    }

    @Test
    void unknownAndInProgressEntriesAreRefusedStructured(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));

        Map<String, Object> unknown = structured(
                ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", "no-such-id")));
        assertEquals(Boolean.FALSE, unknown.get("rebased"));
        assertEquals("unknown_change_set", unknown.get("error_code"));

        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        String id = (String) preview.get("change_set_id");
        ChangeSetStore.Lookup held = ctx.changeSets().claim(id);
        assertNotNull(held.entry());
        try {
            Map<String, Object> busy = structured(
                    ChangeSetTools.rebase(ctx, null, Map.of("change_set_id", id)));
            assertEquals("change_set_in_progress", busy.get("error_code"), busy::toString);
        } finally {
            ctx.changeSets().release(held.entry());
        }
    }

    @Test
    void invalidTtlIsRejectedWithoutTouchingTheOriginal(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> preview = committablePreview(ctx, subclassByIri());
        String id = (String) preview.get("change_set_id");

        CallToolResult result = ChangeSetTools.rebase(ctx, null,
                Map.of("change_set_id", id, "ttl_seconds", 0));
        assertTrue(Boolean.TRUE.equals(result.isError()), () -> String.valueOf(result.content()));
        assertTrue(ctx.changeSets().discard(id), "the original entry must be untouched");
    }

    // ------------------------------------------------------------------ fixtures

    private static ToolContext context(OWLModelManager mm) {
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    private static OWLOntology ontology(Path documentDir) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        Files.createDirectories(documentDir);
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(documentDir.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        OWLClass foo = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(animal));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(foo));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                animal.getIRI(), df.getOWLLiteral("Animal")));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                foo.getIRI(), df.getOWLLiteral("Foo")));
        return ontology;
    }

    private static OWLDataFactory df(OWLOntology ontology) {
        return ontology.getOWLOntologyManager().getOWLDataFactory();
    }

    private static void addAxiom(OWLOntology ontology, OWLAxiom axiom) {
        ontology.getOWLOntologyManager().addAxiom(ontology, axiom);
    }

    private static OWLAxiom plannedAxiom(OWLOntology ontology) {
        OWLDataFactory df = df(ontology);
        return df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo")),
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal")));
    }

    private static void writeDiscoveredPolicy(Path directory) throws Exception {
        Path dir = Files.createDirectories(directory.resolve(".protege-mcp"));
        io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures.writePolicy(
                dir.resolve("project.yaml"),
                io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures
                        .minimalPolicy("rebase-policy", ONTOLOGY_IRI)
                + "validation:\n"
                + "  required_stages: [structural]\n"
                + "  fail_on: error\n");
    }

    private static Map<String, Object> subclassByIri() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", ONTOLOGY_IRI + "#Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        return op;
    }

    private static Map<String, Object> subclassByName() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", "Foo");
        op.put("super", ONTOLOGY_IRI + "#Animal");
        return op;
    }

    private static Map<String, Object> committablePreview(ToolContext ctx, Map<String, Object> op) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(op));
        Map<String, Object> preview = structured(ChangeSetTools.preview(ctx, args));
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        return preview;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
