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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.history.HistoryManagerImpl;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Couples {@code commit_change_set} to {@code undo_change} over the headless Protégé adapter: the
 * commit applies a normalized multi-operation delta through ONE {@link OWLModelManager#applyChanges}
 * broadcast, and the undo-logging contract it reports must match how many undo entries a single Undo
 * would actually revert.
 *
 * <p>{@link FakeModelManager} deliberately carries no {@code HistoryManager}, so each test wires a
 * REAL {@link HistoryManagerImpl} onto the ontology manager via a per-test {@link Proxy} (no
 * production file is touched). The ontology-change listener is registered AFTER the fixture axioms
 * are added so the undo stack starts empty. Two seams are exercised:
 * <ul>
 *   <li>the faithful one-{@code logChanges}-per-broadcast seam Protégé uses, where the whole delta
 *       is a single undo entry that a single Undo fully reverts; and</li>
 *   <li>an anomalous listener that logs each change as its own undo entry, so the commit reports the
 *       {@code undo_log_warning} (without stranding the client, since the delta already landed) and a
 *       full revert takes as many Undos as logged entries.</li>
 * </ul>
 *
 * <p>The workspace revision counter advances on every broadcast — including the Undo itself — so a
 * post-undo revision is compared to the preview's {@code base_revision} on {@code workspace_id} plus
 * both content fingerprints, with {@code session_revision} only required to be strictly greater
 * (never a four-coordinate equality).
 */
class ChangeSetCommitUndoCouplingTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String COMMENT_TEXT = "A curated comment on Foo.";

    private Preferences prefs;
    private String savedToken;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void pinWritePrefs() {
        // Snapshot as strings (like the controller tests) so headless prefs are restored faithfully;
        // the controller constructor may mint and persist a token, and commit reads the write gates.
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
    void committedMultiOpChangeSetFullyRevertsWithASingleUndo(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        // Faithful seam: one logChanges per broadcast → the whole delta is a single undo entry.
        ToolContext ctx = context(wire(ontology, false));

        int axiomsBefore = ontology.getAxiomCount();
        OWLAxiom subclass = subclassAxiom(ontology);
        OWLAxiom comment = commentAxiom(ontology);

        Map<String, Object> preview = twoOpPreview(ctx);
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(2, ((Number) preview.get("normalized_changes")).intValue(), preview::toString);
        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "preview is memory-only and must not mutate the live ontology");
        assertFalse(ontology.containsAxiom(subclass), "the delta is only staged, not applied");
        assertFalse(ontology.containsAxiom(comment), "the delta is only staged, not applied");
        @SuppressWarnings("unchecked")
        Map<String, Object> base = (Map<String, Object>) preview.get("base_revision");

        Map<String, Object> commit = structured(callRegistered(ctx, "commit_change_set",
                commitArgs(preview), ChangeSetTools::register));

        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        assertEquals(Boolean.TRUE, commit.get("single_broadcast"), commit::toString);
        assertEquals(2, ((Number) commit.get("effective_changes")).intValue(), commit::toString);
        assertEquals(Boolean.TRUE, commit.get("undo_logged"), commit::toString);
        assertEquals(0, ((Number) commit.get("undo_depth_before")).intValue(), commit::toString);
        assertEquals(1, ((Number) commit.get("undo_depth_after")).intValue(), commit::toString);
        assertFalse(commit.containsKey("undo_log_warning"),
                "one broadcast → one undo entry carries no anomaly warning: " + commit);
        assertEquals(axiomsBefore + 2, ontology.getAxiomCount(), commit::toString);
        assertTrue(ontology.containsAxiom(subclass), "the committed subclass axiom landed");
        assertTrue(ontology.containsAxiom(comment), "the committed annotation axiom landed");

        // ONE undo fully reverts the whole change set.
        Map<String, Object> undo = structured(callRegistered(ctx, "undo_change",
                new LinkedHashMap<>(), WriteTools::register));

        assertEquals(Boolean.TRUE, undo.get("undone"), undo::toString);
        assertEquals(-2L, ((Number) undo.get("net_axiom_change")).longValue(), undo::toString);
        assertEquals(0, ((Number) undo.get("undo_depth")).intValue(), undo::toString);
        assertEquals(Boolean.FALSE, undo.get("can_undo"), undo::toString);
        assertEquals(Boolean.TRUE, undo.get("can_redo"), undo::toString);

        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "a single undo reverted every axiom in the set");
        assertFalse(ontology.containsAxiom(subclass), "the subclass axiom is gone after one undo");
        assertFalse(ontology.containsAxiom(comment), "the annotation axiom is gone after one undo");
        assertRevertedToBase(ctx, base);
    }

    @Test
    void listenerLoggedExtraUndoEntriesSurfacesTheWarning(@TempDir Path temp) throws Exception {
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        // Anomalous seam: a listener logs each change as its OWN undo entry, so one broadcast yields
        // more than one undo entry.
        ToolContext ctx = context(wire(ontology, true));

        int axiomsBefore = ontology.getAxiomCount();

        Map<String, Object> preview = twoOpPreview(ctx);
        assertEquals(Boolean.TRUE, preview.get("committable"), preview::toString);
        assertEquals(2, ((Number) preview.get("normalized_changes")).intValue(), preview::toString);
        @SuppressWarnings("unchecked")
        Map<String, Object> base = (Map<String, Object>) preview.get("base_revision");

        Map<String, Object> commit = structured(callRegistered(ctx, "commit_change_set",
                commitArgs(preview), ChangeSetTools::register));

        // The delta already landed through ONE applyChanges broadcast, so the commit must still
        // report committed=true — reporting a failure would strand the client against a model that
        // already contains the delta.
        assertEquals(Boolean.TRUE, commit.get("committed"), commit::toString);
        assertEquals(Boolean.TRUE, commit.get("single_broadcast"), commit::toString);
        assertEquals(Boolean.FALSE, commit.get("undo_logged"), commit::toString);
        assertEquals(2, ((Number) commit.get("undo_depth_after")).intValue(), commit::toString);
        assertTrue(String.valueOf(commit.get("undo_log_warning"))
                        .contains("a single Undo may not fully revert"),
                "the split-logged broadcast must surface the multi-entry warning: " + commit);
        assertEquals(axiomsBefore + 2, ontology.getAxiomCount(), commit::toString);

        // One undo reverts only the LAST logged entry — a partial revert.
        Map<String, Object> firstUndo = structured(callRegistered(ctx, "undo_change",
                new LinkedHashMap<>(), WriteTools::register));
        assertEquals(Boolean.TRUE, firstUndo.get("undone"), firstUndo::toString);
        assertEquals(axiomsBefore + 1, ontology.getAxiomCount(),
                "one undo of a two-entry log is only a partial revert: " + firstUndo);

        // A second undo drains the remaining entry — the full revert takes as many undos as entries.
        Map<String, Object> secondUndo = structured(callRegistered(ctx, "undo_change",
                new LinkedHashMap<>(), WriteTools::register));
        assertEquals(Boolean.TRUE, secondUndo.get("undone"), secondUndo::toString);
        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "a full revert takes as many undos as logged entries: " + secondUndo);
        assertRevertedToBase(ctx, base);
    }

    // ------------------------------------------------------------------ fixtures

    private static ToolContext context(OWLModelManager mm) {
        // The controller only dereferences the access inside start(), which these tests never call.
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    /**
     * Wrap {@link FakeModelManager#withNoReasonerSelected} in a proxy that answers
     * {@code getHistoryManager} with a REAL {@link HistoryManagerImpl} and reports a clean-but-present
     * dirty set (the shared fake fails loudly on {@code getDirtyOntologies}), delegating everything
     * else. When {@code splitPerChange} is set the change listener logs each change as its own undo
     * entry (the anomaly); otherwise it mirrors Protégé's one-{@code logChanges}-per-broadcast seam.
     */
    private static OWLModelManager wire(OWLOntology ontology, boolean splitPerChange) {
        OWLOntologyManager owlManager = ontology.getOWLOntologyManager();
        HistoryManager history = new HistoryManagerImpl(owlManager);
        // Registered AFTER the fixture axioms, so the undo stack starts empty (undo_depth_before == 0).
        if (splitPerChange) {
            owlManager.addOntologyChangeListener(changes -> {
                for (OWLOntologyChange change : changes) {
                    history.logChanges(List.of(change));
                }
            });
        } else {
            owlManager.addOntologyChangeListener(history::logChanges);
        }
        OWLModelManager base = FakeModelManager.withNoReasonerSelected(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetCommitUndoCouplingTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHistoryManager":
                            return history;
                        case "getDirtyOntologies":
                            return Set.of(ontology);
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            try {
                                return method.invoke(base, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                    }
                });
    }

    /** Labelled Animal + Foo with no asserted hierarchy and NO project policy (digests stay null). */
    private static OWLOntology ontology(Path documentDir) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(ONTOLOGY_IRI));
        manager.setOntologyFormat(ontology, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(ontology, IRI.create(documentDir.resolve("ontology.ttl").toUri()));
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

    /**
     * Preview a two-operation change set: add {@code Foo ⊑ Animal} and add an {@code rdfs:comment}
     * annotation on Foo. Both operands are already declared and rdfs:comment is built-in, so the
     * planner injects no declarations — the delta normalizes to exactly two adds.
     */
    private static Map<String, Object> twoOpPreview(ToolContext ctx) {
        Map<String, Object> subclassOp = new LinkedHashMap<>();
        subclassOp.put("op", "add");
        subclassOp.put("axiom_type", "subclass_of");
        subclassOp.put("sub", ONTOLOGY_IRI + "#Foo");
        subclassOp.put("super", ONTOLOGY_IRI + "#Animal");
        Map<String, Object> commentOp = new LinkedHashMap<>();
        commentOp.put("op", "add");
        commentOp.put("axiom_type", "annotation_assertion");
        commentOp.put("subject", ONTOLOGY_IRI + "#Foo");
        commentOp.put("property", "rdfs:comment");
        commentOp.put("value", COMMENT_TEXT);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("operations", List.of(subclassOp, commentOp));
        return structured(ChangeSetTools.preview(ctx, args));
    }

    private static Map<String, Object> commitArgs(Map<String, Object> preview) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("change_set_id", preview.get("change_set_id"));
        args.put("expected_revision", preview.get("base_revision"));
        return args;
    }

    private static OWLAxiom subclassAxiom(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        return df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo")),
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal")));
    }

    private static OWLAxiom commentAxiom(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        return df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(),
                IRI.create(ONTOLOGY_IRI + "#Foo"), df.getOWLLiteral(COMMENT_TEXT));
    }

    /**
     * A full revert restores the exact content the preview captured: the workspace id is stable and
     * both content fingerprints match the base, while the session counter only advances (each
     * broadcast, including the Undo itself, bumps it) — so it is required strictly greater, never
     * equal.
     */
    private static void assertRevertedToBase(ToolContext ctx, Map<String, Object> base) {
        Map<String, Object> current = structured(RevisionTools.get(ctx, new LinkedHashMap<>()));
        @SuppressWarnings("unchecked")
        Map<String, Object> revision = (Map<String, Object>) current.get("revision");
        assertEquals(base.get("workspace_id"), revision.get("workspace_id"),
                "the workspace id is stable across the commit/undo coupling");
        assertEquals(base.get("semantic_fingerprint"), revision.get("semantic_fingerprint"),
                "a full revert restores the semantic fingerprint");
        assertEquals(base.get("document_fingerprint"), revision.get("document_fingerprint"),
                "a full revert restores the document fingerprint");
        assertTrue(((Number) revision.get("session_revision")).longValue()
                        > ((Number) base.get("session_revision")).longValue(),
                "the session counter only advances (the undo is itself a broadcast): " + revision);
    }

    private static CallToolResult callRegistered(ToolContext ctx, String name,
            Map<String, Object> args,
            java.util.function.BiConsumer<ToolRegistry, ToolContext> register) {
        ToolRegistry registry = new ToolRegistry();
        register.accept(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals(name)) {
                return spec.callHandler().apply(ToolTestExchange.localAdmin(),
                        new CallToolRequest(name, args));
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
