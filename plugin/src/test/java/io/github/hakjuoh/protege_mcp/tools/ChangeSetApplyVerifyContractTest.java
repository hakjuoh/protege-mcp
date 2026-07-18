package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.history.UndoManagerListener;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
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
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Pins the change-set-verified {@code apply_changes} contract on {@link ChangeSetApplyVerify}
 * branches the end-to-end tests in {@link ChangeSetToolsTest} do not reach: the live commit lands
 * EXACTLY the planner delta the isolated gate evaluated (partial-error batches included), a
 * prevented rollback leaves the shared undo history untouched while a passing rollback logs exactly
 * one undo entry, and the timeout/revision-conflict failure paths stay honest about what may or may
 * not have been applied.
 */
class ChangeSetApplyVerifyContractTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";

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
    void partialErrorBatchLandsExactlyTheValidSubsetDeltaTheGateEvaluated(@TempDir Path temp)
            throws Exception {
        // Released partial-error semantics through the migrated verify path: the malformed middle
        // operation carries its indexed error, and the LIVE ontology receives exactly the valid
        // subset's normalized delta (adds plus planner-injected declarations) — nothing else.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        // The valid adds mint brand-new individuals, so the planner injects their Declarations —
        // the delta below must contain those side effects and nothing beyond them.
        Map<String, Object> addA = classAssertionOperation(ONTOLOGY_IRI + "#IndA");
        Map<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("op", "add");
        malformed.put("axiom_type", "unsupported_kind");
        Map<String, Object> addB = classAssertionOperation(ONTOLOGY_IRI + "#IndB");

        // The gate verdict the valid subset alone earns through the shared preview pipeline — the
        // verify gate must reflect the preflight of exactly that subset.
        Map<String, Object> subsetArgs = new LinkedHashMap<>();
        subsetArgs.put("operations", List.of(addA, addB));
        subsetArgs.put("include_impact", "none");
        Map<String, Object> subsetPreview = structured(ChangeSetTools.preview(ctx, subsetArgs));
        assertNotNull(subsetPreview.get("preflight"), subsetPreview::toString);
        Object expectedGate = ((Map<?, ?>) subsetPreview.get("preflight")).get("gate");
        Set<OWLAxiom> before = new LinkedHashSet<>(ontology.getAxioms());

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "report", 60_000,
                "partial-error batch", List.of(addA, malformed, addB), false));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("operations");
        assertEquals(1, rows.get(1).get("index"), rows::toString);
        assertNotNull(rows.get(1).get("error"), "the malformed operation must carry its own "
                + "indexed error row: " + rows);
        assertEquals(Boolean.TRUE, rows.get(0).get("applied"), rows::toString);
        assertEquals(Boolean.TRUE, rows.get(2).get("applied"), rows::toString);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, ((Number) summary.get("errors")).intValue(), summary::toString);
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
        assertEquals(expectedGate, verify.get("gate"),
                "the verify gate must be the preflight verdict of the valid subset: " + verify);

        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal"));
        var indA = df.getOWLNamedIndividual(IRI.create(ONTOLOGY_IRI + "#IndA"));
        var indB = df.getOWLNamedIndividual(IRI.create(ONTOLOGY_IRI + "#IndB"));
        Set<OWLAxiom> landed = new LinkedHashSet<>(ontology.getAxioms());
        assertTrue(landed.containsAll(before), "a partial-error apply must never remove axioms");
        landed.removeAll(before);
        assertEquals(Set.of(df.getOWLClassAssertionAxiom(animal, indA),
                        df.getOWLClassAssertionAxiom(animal, indB),
                        df.getOWLDeclarationAxiom(indA), df.getOWLDeclarationAxiom(indB)),
                landed, "the live ontology must receive exactly the gated valid-subset delta");
    }

    @Test
    void netZeroCancellationWithATrailingErrorStillLandsNothing(@TempDir Path temp)
            throws Exception {
        // The add/remove pair cancels to an EMPTY normalized delta and the third operation errors:
        // the exact-delta apply must land nothing — in particular not the Declaration side effect
        // the old operation-re-deriving path injected for the cancelled annotation add.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        ToolContext ctx = context(FakeModelManager.withNoReasonerSelected(ontology));
        Map<String, Object> add = new LinkedHashMap<>();
        add.put("op", "add");
        add.put("axiom_type", "annotation_assertion");
        add.put("subject", ONTOLOGY_IRI + "#Foo");
        add.put("property", ONTOLOGY_IRI + "#undeclaredNote");
        add.put("value", "temporary");
        Map<String, Object> remove = new LinkedHashMap<>(add);
        remove.put("op", "remove");
        Map<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("op", "frobnicate");
        int before = ontology.getAxiomCount();

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "report", 60_000,
                "net-zero cancellation with an error", List.of(add, remove, malformed), false));

        assertEquals(before, ontology.getAxiomCount(), "a cancelled batch must not land the "
                + "declaration side effects the isolated gate never evaluated");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, ((Number) summary.get("errors")).intValue(), summary::toString);
        assertEquals(Boolean.FALSE, summary.get("single_undo"), summary::toString);
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("pass", verify.get("gate"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
    }

    @Test
    void preventedRollbackLeavesTheUndoHistoryDepthUnchanged(@TempDir Path temp) throws Exception {
        // Direct undo observability, not the ontology-state proxy: every live broadcast is recorded
        // as one undo entry and HistoryManager.undo() fails loudly, so a prevented rollback proves
        // "Undo untouched" by depth, and a regression to mutate-then-undo cannot hide.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeGovernedPolicy(temp);
        OWLOntology ontology = ontology(docDir);
        List<List<OWLOntologyChange>> logged = new ArrayList<>();
        ToolContext ctx = context(historyTracking(
                FakeModelManager.withNoReasonerSelected(ontology), logged));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "prevented rollback", List.of(removeFooLabelOperation()), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(0, logged.size(),
                "a prevented rollback must leave the shared undo history depth unchanged");
        assertTrue(ontology.containsAxiom(fooLabelAxiom(ontology)),
                "the prevented batch must not touch the live ontology");
    }

    @Test
    void passingRollbackBatchAppliesWithExactlyOneUndoEntry(@TempDir Path temp) throws Exception {
        // The other half of the branch pair: verify=rollback with a PASSING gate must commit — as
        // one live broadcast, i.e. exactly one new undo entry containing the gated delta.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeDiscoveredPolicy(temp, "root-policy");
        OWLOntology ontology = ontology(docDir);
        List<List<OWLOntologyChange>> logged = new ArrayList<>();
        ToolContext ctx = context(historyTracking(
                FakeModelManager.withNoReasonerSelected(ontology), logged));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "passing rollback", List.of(subclassOperation(ONTOLOGY_IRI + "#Foo",
                        ONTOLOGY_IRI + "#Animal")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("pass", verify.get("gate"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("regression"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
        assertNull(verify.get("prevented_before_apply"), verify::toString);
        assertTrue(ontology.containsAxiom(plannedAxiom(ontology)),
                "the passing rollback batch must land in the live ontology");
        assertEquals(1, logged.size(),
                "a passing rollback batch must commit as exactly one undoable broadcast: " + logged);
        assertTrue(logged.get(0).stream().anyMatch(change -> change.isAddAxiom()
                        && plannedAxiom(ontology).equals(change.getAxiom())),
                () -> "the single undo entry must carry the gated delta: " + logged);
    }

    @Test
    void applyHopTimeoutIsHonestAboutThePossiblyAppliedBatch(@TempDir Path temp) throws Exception {
        // Mirror of the commit-hop honesty pin: the bounded model-thread wait can expire after the
        // verified-apply body started, and a started body cannot be cancelled — the error must say
        // the batch may still land instead of implying nothing happened.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        writeDiscoveredPolicy(temp, "root-policy");
        OWLOntology ontology = ontology(docDir);
        OWLModelManager base = FakeModelManager.withNoReasonerSelected(ontology);
        OWLModelManager timingOut = (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetApplyVerifyContractTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    if ("applyChanges".equals(method.getName())) {
                        throw new McpAccessException("Timed out after "
                                + ChangeSetTools.COMMIT_TIMEOUT_MS
                                + " ms waiting for the Protégé UI thread (the application may be busy).");
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        ToolContext ctx = context(timingOut);

        McpAccessException failure = assertThrows(McpAccessException.class,
                () -> ChangeSetApplyVerify.apply(ctx, "report", 60_000, "timing out apply",
                        List.of(subclassOperation(ONTOLOGY_IRI + "#Foo",
                                ONTOLOGY_IRI + "#Animal")), false));

        assertTrue(failure.getMessage().contains("may still complete"), failure.getMessage());
        assertTrue(failure.getMessage().contains("get_model_revision"), failure.getMessage());
    }

    @Test
    void concurrentEditBetweenCaptureAndCommitIsAnHonestRevisionConflict(@TempDir Path temp)
            throws Exception {
        // A GUI-style edit lands after the revision was captured (fired from the capture hop's own
        // reasoner-selection probe, which runs after the fingerprint) — the commit hop must refuse
        // with revision_conflict, flag concurrent_change, and neutralize the simulated payload so
        // the released row/summary fields never claim the batch landed.
        Path docDir = Files.createDirectories(temp.resolve("sub"));
        OWLOntology ontology = ontology(docDir);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        AtomicBoolean fired = new AtomicBoolean(false);
        Runnable guiEdit = () -> ontology.getOWLOntologyManager().addAxiom(ontology,
                df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Bystander"))));
        ToolContext ctx = context(mutatingOnFirstSelectionProbe(
                FakeModelManager.withNoReasonerSelected(ontology), guiEdit, fired));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "report", 60_000,
                "conflicting apply", List.of(removeFooLabelOperation()), false));

        assertTrue(fired.get(), "the concurrent edit must have fired inside the captured hop");
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("revision_conflict", verify.get("error_code"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("concurrent_change"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        // Neutralized payload: nothing was applied, so the released summary/row claims must say so.
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(Boolean.FALSE, summary.get("single_undo"), summary::toString);
        assertEquals(0, ((Number) summary.get("removed")).intValue(), summary::toString);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("operations");
        assertEquals(Boolean.FALSE, rows.get(0).get("removed"), rows::toString);
        assertTrue(ontology.containsAxiom(fooLabelAxiom(ontology)),
                "a conflicted batch must apply nothing");
    }

    // ------------------------------------------------------------------ fixtures

    private static ToolContext context(OWLModelManager mm) {
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    /** Labelled Animal + Foo with no asserted hierarchy, as in {@link ChangeSetToolsTest}. */
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

    private static OWLAxiom plannedAxiom(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        return df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Foo")),
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Animal")));
    }

    private static OWLAxiom fooLabelAxiom(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        return df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                IRI.create(ONTOLOGY_IRI + "#Foo"), df.getOWLLiteral("Foo"));
    }

    private static Map<String, Object> removeFooLabelOperation() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "remove");
        operation.put("axiom_type", "annotation_assertion");
        operation.put("subject", ONTOLOGY_IRI + "#Foo");
        operation.put("property", "http://www.w3.org/2000/01/rdf-schema#label");
        operation.put("value", "Foo");
        return operation;
    }

    private static Map<String, Object> subclassOperation(String sub, String sup) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", sub);
        op.put("super", sup);
        return op;
    }

    /** Asserts a brand-new individual into Animal — the headless way to mint through a batch. */
    private static Map<String, Object> classAssertionOperation(String individualIri) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "class_assertion");
        op.put("individual", individualIri);
        op.put("class", ONTOLOGY_IRI + "#Animal");
        return op;
    }

    private static void writeDiscoveredPolicy(Path directory, String projectId) throws Exception {
        Path dir = Files.createDirectories(directory.resolve(".protege-mcp"));
        ProjectPolicyFixtures.writePolicy(dir.resolve("project.yaml"),
                ProjectPolicyFixtures.minimalPolicy(projectId, ONTOLOGY_IRI)
                + "validation:\n"
                + "  required_stages: [structural]\n"
                + "  fail_on: error\n");
    }

    private static void writeGovernedPolicy(Path directory) throws Exception {
        Path dir = Files.createDirectories(directory.resolve(".protege-mcp"));
        ProjectPolicyFixtures.writePolicy(dir.resolve("project.yaml"),
                ProjectPolicyFixtures.minimalPolicy("governed", ONTOLOGY_IRI)
                + "annotations:\n"
                + "  required: [http://www.w3.org/2000/01/rdf-schema#label]\n"
                + "validation:\n"
                + "  required_stages: [governance]\n"
                + "  fail_on: warning\n");
    }

    /**
     * Delegating manager that records every live broadcast as one undo entry (Protégé's logging
     * behaviour) and whose {@link HistoryManager} mutators fail loudly — the shared-undo
     * observability {@link FakeModelManager} deliberately omits.
     */
    private static OWLModelManager historyTracking(OWLModelManager base,
            List<List<OWLOntologyChange>> logged) {
        HistoryManager history = new RecordingHistory(logged);
        return (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetApplyVerifyContractTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHistoryManager":
                            return history;
                        case "applyChanges":
                            logged.add(List.copyOf(
                                    (List<? extends OWLOntologyChange>) args[0]));
                            break;
                        case "applyChange":
                            logged.add(List.of((OWLOntologyChange) args[0]));
                            break;
                        default:
                            break;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** An undo log backed by the recorded broadcasts; any rollback-by-undo attempt fails the test. */
    private static final class RecordingHistory implements HistoryManager {
        private final List<List<OWLOntologyChange>> logged;

        RecordingHistory(List<List<OWLOntologyChange>> logged) {
            this.logged = logged;
        }

        @Override
        public void logChanges(List<? extends OWLOntologyChange> changes) {
            logged.add(List.copyOf(changes));
        }

        @Override
        public boolean canUndo() {
            return !logged.isEmpty();
        }

        @Override
        public void undo() {
            throw new AssertionError("the verified apply must never touch shared undo history");
        }

        @Override
        public boolean canRedo() {
            return false;
        }

        @Override
        public void redo() {
            throw new AssertionError("the verified apply must never touch shared undo history");
        }

        @Override
        public void clear() {
            throw new AssertionError("the verified apply must never touch shared undo history");
        }

        @Override
        public List<List<OWLOntologyChange>> getLoggedChanges() {
            return logged;
        }

        @Override
        public void addUndoManagerListener(UndoManagerListener listener) {
        }

        @Override
        public void removeUndoManagerListener(UndoManagerListener listener) {
        }
    }

    /**
     * Fires {@code mutation} on the FIRST reasoner-selection capture ({@code
     * getCurrentReasonerFactory}), which the verified apply performs inside its capture hop AFTER
     * the base revision fingerprint — i.e. a concurrent edit landing between capture and commit.
     */
    private static OWLModelManager mutatingOnFirstSelectionProbe(OWLModelManager base,
            Runnable mutation, AtomicBoolean fired) {
        return (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetApplyVerifyContractTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class},
                (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if ("getOWLReasonerManager".equals(method.getName())
                            && result instanceof OWLReasonerManager real) {
                        return Proxy.newProxyInstance(
                                ChangeSetApplyVerifyContractTest.class.getClassLoader(),
                                new Class<?>[] {OWLReasonerManager.class},
                                (p2, m2, a2) -> {
                                    if ("getCurrentReasonerFactory".equals(m2.getName())
                                            && fired.compareAndSet(false, true)) {
                                        mutation.run();
                                    }
                                    try {
                                        return m2.invoke(real, a2);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    }
                    return result;
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
