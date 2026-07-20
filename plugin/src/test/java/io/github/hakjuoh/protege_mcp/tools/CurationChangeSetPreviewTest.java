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
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The batch curation tools' change-set routing, exercised through their REGISTERED handlers: {@code
 * create_terms}/{@code create_properties} with {@code preview=true} must produce a cached, committable
 * change set (and touch nothing live) that {@code commit_change_set} then applies, and the
 * {@code preview}+{@code verify} combination must be refused up front — post-apply verification makes
 * no sense for a batch that is not applied. These are the public entry points the Ontology Assistant
 * steering and the rewritten prompts direct clients to, so the routing itself needs pinning, not just
 * the underlying planner cores.
 */
class CurationChangeSetPreviewTest {

    private static final String ONTOLOGY_IRI = "https://example.org/batch";
    private static final String NS = ONTOLOGY_IRI + "#";

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
    void createTermsPreviewCachesACommittableChangeSetAndCommitAppliesIt(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(ontology);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var dog = df.getOWLClass(IRI.create(NS + "Dog"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", List.of(Map.of("name", "Dog", "definition", "A domestic dog.")));
        args.put("namespace", NS);
        args.put("preview", true);
        Map<String, Object> preview = structured(call(ctx, "create_terms", args));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertNotNull(preview.get("change_set_id"), "a preview must return its cache id");
        assertNotNull(preview.get("base_revision"), "a preview must return the revision it evaluated");
        int planned = ((Number) preview.get("normalized_changes")).intValue();
        assertTrue(planned >= 3, "declaration + label + definition must all be planned: " + preview);
        assertFalse(ontology.containsAxiom(df.getOWLDeclarationAxiom(dog)),
                "preview=true must leave the live ontology untouched");

        int axiomsBefore = ontology.getAxiomCount();
        Map<String, Object> commitArgs = new LinkedHashMap<>();
        commitArgs.put("change_set_id", preview.get("change_set_id"));
        commitArgs.put("expected_revision", preview.get("base_revision"));
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs));

        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        // The commit must apply exactly the previewed plan — no dropped entries, no duplication —
        // measured against the ONTOLOGY's real axiom delta, not just two numbers derived from the
        // same cached list.
        assertEquals(planned, ((Number) commit.get("effective_changes")).intValue(),
                "commit must apply exactly the previewed normalized delta");
        assertEquals(planned, ontology.getAxiomCount() - axiomsBefore,
                "the reported effective_changes must equal the axioms that actually landed");
        assertEquals(Boolean.TRUE, commit.get("single_broadcast"), commit::toString);
        assertTrue(ontology.containsAxiom(df.getOWLDeclarationAxiom(dog)),
                "the committed batch must land in the live ontology");
        assertTrue(ontology.containsAxiom(df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                        dog.getIRI(), df.getOWLLiteral("Dog"))),
                "the batch's default label must land with it");
        assertTrue(ontology.containsAxiom(df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(),
                        dog.getIRI(), df.getOWLLiteral("A domestic dog."))),
                "the batch's definition (default property rdfs:comment) must land with it — the "
                        + "preview planner shares buildTermsBatch with the direct path, and no other "
                        + "test pushes a definition through it");
    }

    @Test
    void createPropertiesPreviewCachesACommittableChangeSetAndCommitAppliesIt(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(ontology);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var hasPart = df.getOWLObjectProperty(IRI.create(NS + "hasPart"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("properties", List.of(Map.of("name", "hasPart")));
        args.put("namespace", NS);
        args.put("preview", true);
        Map<String, Object> preview = structured(call(ctx, "create_properties", args));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertFalse(ontology.containsAxiom(df.getOWLDeclarationAxiom(hasPart)),
                "preview=true must leave the live ontology untouched");

        Map<String, Object> commitArgs = new LinkedHashMap<>();
        commitArgs.put("change_set_id", preview.get("change_set_id"));
        commitArgs.put("expected_revision", preview.get("base_revision"));
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs));

        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        assertTrue(ontology.containsAxiom(df.getOWLDeclarationAxiom(hasPart)),
                "the committed property batch must land in the live ontology");
    }

    @Test
    void duplicateLabelWithinABatchNormalizesToTheAxiomsThatActuallyLand(@TempDir Path temp)
            throws Exception {
        // The default label ("Dog") and an explicit rdfs:label annotation with the same text build
        // the SAME axiom twice. Review repro: the un-normalized plan reported normalized=3/effective=3
        // while OWL set semantics landed only 2 axioms.
        OWLOntology ontology = ontology(temp);
        ToolContext ctx = context(ontology);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", List.of(Map.of("name", "Dog", "annotations", List.of(
                Map.of("property", "http://www.w3.org/2000/01/rdf-schema#label", "value", "Dog")))));
        args.put("namespace", NS);
        args.put("preview", true);
        Map<String, Object> preview = structured(call(ctx, "create_terms", args));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(2, ((Number) preview.get("normalized_changes")).intValue(),
                "declaration + ONE label — the duplicate must normalize away: " + preview);
        assertEquals(1, ((Number) summary(preview).get("no_ops")).intValue(), preview::toString);

        int axiomsBefore = ontology.getAxiomCount();
        Map<String, Object> commitArgs = new LinkedHashMap<>();
        commitArgs.put("change_set_id", preview.get("change_set_id"));
        commitArgs.put("expected_revision", preview.get("base_revision"));
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs));

        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        assertEquals(2, ((Number) commit.get("effective_changes")).intValue(), commit::toString);
        assertEquals(2, ontology.getAxiomCount() - axiomsBefore,
                "reported effective changes must match the real ontology delta");
    }

    @Test
    void recreatingAnExistingTermIsAnAllNoOpChangeSet(@TempDir Path temp) throws Exception {
        // Review repro: re-creating an already existing Dog reported effective=2 although the
        // ontology's axiom count never moved (2→2).
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var dog = df.getOWLClass(IRI.create(NS + "Dog"));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(dog));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Dog")));
        ToolContext ctx = context(ontology);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", List.of(Map.of("name", "Dog")));
        args.put("namespace", NS);
        args.put("preview", true);
        Map<String, Object> preview = structured(call(ctx, "create_terms", args));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(0, ((Number) preview.get("normalized_changes")).intValue(),
                "declaration and label already exist — nothing to change: " + preview);
        assertEquals(2, ((Number) summary(preview).get("no_ops")).intValue(), preview::toString);

        int axiomsBefore = ontology.getAxiomCount();
        Map<String, Object> commitArgs = new LinkedHashMap<>();
        commitArgs.put("change_set_id", preview.get("change_set_id"));
        commitArgs.put("expected_revision", preview.get("base_revision"));
        Map<String, Object> commit = structured(ChangeSetTools.commit(ctx, commitArgs));

        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        assertEquals(0, ((Number) commit.get("effective_changes")).intValue(), commit::toString);
        assertEquals(Boolean.FALSE, commit.get("single_broadcast"),
                "an all-no-op commit broadcasts nothing: " + commit);
        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "the ontology must be untouched by an all-no-op commit");
    }

    @Test
    void batchPreviewAcceptsAGatesOverride(@TempDir Path temp) throws Exception {
        // Review finding: the batch schemas rejected `gates`, leaving no documented way to preview a
        // harmless batch in an ontology whose pre-existing unsatisfiable classes fail the default
        // reasoner stage.
        ToolContext ctx = context(ontology(temp));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", List.of(Map.of("name", "Dog")));
        args.put("namespace", NS);
        args.put("preview", true);
        args.put("gates", List.of("structural"));
        Map<String, Object> preview = structured(call(ctx, "create_terms", args));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(Boolean.FALSE, preview.get("satisfiability_checked"), preview::toString);
        assertTrue(String.valueOf(((Map<?, ?>) preview.get("preflight")).get("stages"))
                        .contains("structural"), preview::toString);
        assertFalse(String.valueOf(((Map<?, ?>) preview.get("preflight")).get("stages"))
                        .contains("governance"),
                "an explicit override must replace the default stage set: " + preview);
    }

    @Test
    void directBatchRecreationReportsTheRealDeltaNotTheRequestedOne(@TempDir Path temp)
            throws Exception {
        // Review repro (direct, non-preview path): re-creating an existing Dog reported applied=2 and
        // an unflagged created row while the ontology's axiom count never moved.
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var dog = df.getOWLClass(IRI.create(NS + "Dog"));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(dog));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Dog")));
        ToolContext ctx = context(ontology);
        int axiomsBefore = ontology.getAxiomCount();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", List.of(Map.of("name", "Dog")));
        args.put("namespace", NS);
        Map<String, Object> result = structured(call(ctx, "create_terms", args));

        assertEquals(0, ((Number) result.get("applied")).intValue(),
                "nothing landed, so nothing was applied: " + result);
        assertEquals(2, ((Number) result.get("no_ops")).intValue(), result::toString);
        assertEquals(1, ((Number) result.get("count")).intValue(), result::toString);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) result.get("created");
        assertEquals(Boolean.TRUE, created.get(0).get("already_existed"),
                "a re-created term must be flagged, not reported as new: " + result);
        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "the ontology must be untouched by an all-no-op batch");
    }

    @Test
    void cancelingChangesInADirectBatchNetToZero(@TempDir Path temp) throws Exception {
        // Latent today (the public builders only add), but the simulation promises FINAL-SET
        // semantics: an add cancelled by a later remove of the same axiom nets to nothing. Both
        // operands are pre-declared so the declare-minted side effect cannot re-add anything.
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var dog = df.getOWLClass(IRI.create(NS + "Dog"));
        var animal = df.getOWLClass(IRI.create(NS + "Animal"));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(dog));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(animal));
        var subclass = df.getOWLSubClassOfAxiom(dog, animal);
        OWLModelManager mm = FakeModelManager.over(ontology);
        List<org.semanticweb.owlapi.model.OWLOntologyChange> changes = List.of(
                new org.semanticweb.owlapi.model.AddAxiom(ontology, subclass),
                new org.semanticweb.owlapi.model.RemoveAxiom(ontology, subclass));

        Object outcome = CurationTools.applyBatchCurationData(mm, ontology,
                new java.util.ArrayList<>(changes), false,
                new java.util.LinkedHashSet<>(), List.of());

        assertTrue(outcome instanceof Map, () -> String.valueOf(outcome));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) outcome;
        assertEquals(0, ((Number) payload.get("applied")).intValue(), payload::toString);
        assertEquals(2, ((Number) payload.get("no_ops")).intValue(), payload::toString);
        assertFalse(ontology.containsAxiom(subclass), "the cancelled add must not land");
    }

    @Test
    void cancelingChangesInAPreparedPreviewReportBothOperandsAsNoOps(@TempDir Path temp)
            throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var dog = df.getOWLClass(IRI.create(NS + "Dog"));
        var animal = df.getOWLClass(IRI.create(NS + "Animal"));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(dog));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(animal));
        var subclass = df.getOWLSubClassOfAxiom(dog, animal);
        OWLModelManager mm = FakeModelManager.over(ontology);

        ChangePlanner.Plan plan = ChangePlanner.prepared(mm, List.of(
                new org.semanticweb.owlapi.model.AddAxiom(ontology, subclass),
                new org.semanticweb.owlapi.model.RemoveAxiom(ontology, subclass)),
                List.of(), "terms");

        assertEquals(0, plan.changes().size(), "the final ontology set is unchanged");
        assertEquals(2, ((Number) plan.summary().get("no_ops")).intValue(),
                "preview and direct batch paths must report the same final-set semantics");
    }

    @Test
    void removingAnAssertedAxiomThroughTheDirectBatchCountsAsApplied(@TempDir Path temp)
            throws Exception {
        // Pins the removes half of the direct-path no_ops formula (applied = adds + removes): a net
        // remove is 1 applied change and 0 no-ops — dropping removes from the formula would report 2.
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var dog = df.getOWLClass(IRI.create(NS + "Dog"));
        var animal = df.getOWLClass(IRI.create(NS + "Animal"));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(dog));
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLDeclarationAxiom(animal));
        var subclass = df.getOWLSubClassOfAxiom(dog, animal);
        ontology.getOWLOntologyManager().addAxiom(ontology, subclass);
        OWLModelManager mm = FakeModelManager.over(ontology);

        Object outcome = CurationTools.applyBatchCurationData(mm, ontology,
                new java.util.ArrayList<>(List.of(
                        new org.semanticweb.owlapi.model.RemoveAxiom(ontology, subclass))),
                false, new java.util.LinkedHashSet<>(), List.of());

        assertTrue(outcome instanceof Map, () -> String.valueOf(outcome));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) outcome;
        assertEquals(1, ((Number) payload.get("applied")).intValue(), payload::toString);
        assertFalse(payload.containsKey("no_ops"),
                "a fully effective batch reports no no_ops key: " + payload);
        assertFalse(ontology.containsAxiom(subclass), "the remove must land");
    }

    @Test
    void exactlyTheMaximumBatchSizePassesThePreviewCap(@TempDir Path temp) throws Exception {
        // Pins the cap's boundary direction (> not >=): exactly 2,000 items is a documented,
        // accepted batch ("Change-set previews accept at most 2,000 items").
        ToolContext ctx = context(ontology(temp));
        List<Map<String, Object>> terms = new java.util.ArrayList<>();
        for (int i = 0; i < ChangePlanner.MAX_OPERATIONS; i++) {
            terms.add(Map.of("name", "C" + i));
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", terms);
        args.put("namespace", NS);
        args.put("preview", true);
        args.put("gates", List.of("structural"));
        args.put("include_impact", "none");

        Map<String, Object> preview = structured(call(ctx, "create_terms", args));

        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(2 * ChangePlanner.MAX_OPERATIONS,
                ((Number) preview.get("normalized_changes")).intValue(),
                "declaration + label per term: " + preview.get("summary"));
    }

    @Test
    void preparedBoundsTheFinalDeltaNotTheRawBatch(@TempDir Path temp) throws Exception {
        // Repeated operands legitimately make the raw change list far larger than the normalized
        // delta (review repro: 2,000 specs sharing parents → 12,000 raw / 6,000 final was refused).
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var dog = df.getOWLClass(IRI.create(NS + "Dog"));
        var decl = new AddAxiom(ontology, df.getOWLDeclarationAxiom(dog));
        List<org.semanticweb.owlapi.model.OWLOntologyChange> raw = new java.util.ArrayList<>();
        for (int i = 0; i < 9_000; i++) {
            raw.add(decl);
        }
        OWLModelManager mm = FakeModelManager.over(ontology);

        ChangePlanner.Plan plan = ChangePlanner.prepared(mm, raw, List.of(dog), "terms");

        assertEquals(1, plan.changes().size(),
                "9,000 identical raw changes normalize to one — and must not trip the delta bound");
    }

    @Test
    void preparedRejectsAnOversizedFinalDelta(@TempDir Path temp) throws Exception {
        OWLOntology ontology = ontology(temp);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        var parent = df.getOWLClass(IRI.create(NS + "Parent"));
        List<org.semanticweb.owlapi.model.OWLOntologyChange> raw = new java.util.ArrayList<>();
        for (int i = 0; i <= ChangePlanner.MAX_OPERATIONS * 4; i++) {
            raw.add(new AddAxiom(ontology, df.getOWLSubClassOfAxiom(
                    df.getOWLClass(IRI.create(NS + "C" + i)), parent)));
        }
        OWLModelManager mm = FakeModelManager.over(ontology);

        ToolArgException tooLarge = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class,
                () -> ChangePlanner.prepared(mm, raw, List.of(), "terms"));
        assertTrue(tooLarge.getMessage().contains("normalized change set is too large"),
                tooLarge.getMessage());
    }

    @Test
    void createTermsRefusesPreviewCombinedWithVerify(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(ontology(temp));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("terms", List.of(Map.of("name", "Dog")));
        args.put("namespace", NS);
        args.put("preview", true);
        args.put("verify", "report");
        CallToolResult result = call(ctx, "create_terms", args);
        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.content()));
        assertTrue(String.valueOf(result.content())
                        .contains("preview=true cannot be combined with verify"),
                () -> String.valueOf(result.content()));
    }

    @Test
    void createPropertiesRefusesPreviewCombinedWithVerify(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(ontology(temp));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("properties", List.of(Map.of("name", "hasPart")));
        args.put("namespace", NS);
        args.put("preview", true);
        args.put("verify", "rollback");
        CallToolResult result = call(ctx, "create_properties", args);
        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.content()));
        assertTrue(String.valueOf(result.content())
                        .contains("preview=true cannot be combined with verify"),
                () -> String.valueOf(result.content()));
    }

    @Test
    void batchPreviewsRejectOversizedItemArraysBeforePlanning(@TempDir Path temp) throws Exception {
        ToolContext ctx = context(ontology(temp));
        List<Map<String, Object>> tooMany = java.util.Collections.nCopies(
                ChangePlanner.MAX_OPERATIONS + 1, Map.of("name", "duplicate"));

        Map<String, Object> termArgs = new LinkedHashMap<>();
        termArgs.put("terms", tooMany);
        termArgs.put("preview", true);
        CallToolResult terms = call(ctx, "create_terms", termArgs);
        assertEquals(Boolean.TRUE, terms.isError(), () -> String.valueOf(terms.content()));
        assertTrue(String.valueOf(terms.content()).contains("preview maximum of 2000"),
                () -> String.valueOf(terms.content()));

        Map<String, Object> propertyArgs = new LinkedHashMap<>();
        propertyArgs.put("properties", tooMany);
        propertyArgs.put("preview", true);
        CallToolResult properties = call(ctx, "create_properties", propertyArgs);
        assertEquals(Boolean.TRUE, properties.isError(), () -> String.valueOf(properties.content()));
        assertTrue(String.valueOf(properties.content()).contains("preview maximum of 2000"),
                () -> String.valueOf(properties.content()));
    }

    // ------------------------------------------------------------------ fixtures

    /** Invoke a batch tool exactly as the server would: through its registered call handler. */
    private static CallToolResult call(ToolContext ctx, String tool, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        CurationTools.register(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals(tool)) {
                return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest(tool, args));
            }
        }
        throw new AssertionError("no tool named " + tool);
    }

    private static ToolContext context(OWLOntology ontology) {
        OWLModelManager mm = FakeModelManager.withNoReasonerSelected(ontology);
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    private static OWLOntology ontology(Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("doc"));
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology,
                IRI.create(docDir.resolve("ontology.ttl").toUri()));
        return ontology;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> preview) {
        return (Map<String, Object>) preview.get("summary");
    }
}
