package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Baseline attribution of the change-set-verified {@code apply_changes}: with no policy, a
 * gate=fail verdict is re-run against the UNCHANGED baseline so findings the workspace already
 * produced are not attributed to this batch ({@code regression=false}, the batch commits), while a
 * batch that genuinely breaks a class is still prevented in rollback mode and names the culprit in
 * {@code verify.newly_unsatisfiable}. Uses the same HermiT-backed manager double as
 * {@link ChangeSetPreviewReasonerGateTest}.
 */
class ChangeSetApplyVerifyBaselineAttributionTest {

    private static final String NS = "https://example.org/gate#";

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
    void preExistingUnsatisfiableClassDoesNotPreventACleanRollbackBatch() throws Exception {
        // Legacy unsat class U pre-dates the batch. The result-state gate still reports fail, but
        // the failure is re-attributed against the unchanged baseline: the clean batch APPLIES with
        // regression=false and the honest gate/baseline_gate pair — not prevented_before_apply.
        OWLOntology ontology = disjointFixture();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass u = df.getOWLClass(IRI.create(NS + "U"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(u));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                u.getIRI(), df.getOWLLiteral("U")));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(u, a));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(u, b));
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "clean batch over a legacy unsat class",
                List.of(subclassOperation(NS + "C", NS + "A")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("fail", verify.get("gate"), verify::toString);
        assertEquals("fail", verify.get("baseline_gate"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("regression"),
                "a pre-existing failure must not be attributed to this batch: " + verify);
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
        assertNull(verify.get("prevented_before_apply"), verify::toString);
        assertEquals(List.of(), verify.get("newly_unsatisfiable"), verify::toString);
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        assertTrue(ontology.containsAxiom(factory.getOWLSubClassOfAxiom(
                        factory.getOWLClass(IRI.create(NS + "C")),
                        factory.getOWLClass(IRI.create(NS + "A")))),
                "the clean rollback batch must land despite the legacy unsat class");
    }

    @Test
    void batchThatMakesAClassUnsatisfiableIsPreventedAndNamesTheClass() throws Exception {
        // The clean-ontology counterpart: a genuinely new unsatisfiability is this batch's
        // regression — rollback prevents the live mutation and verify.newly_unsatisfiable names
        // the broken class instead of the old hard-coded empty list.
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "unsatisfiability-introducing batch",
                List.of(subclassOperation(NS + "A", NS + "B")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("fail", verify.get("gate"), verify::toString);
        assertEquals("pass", verify.get("baseline_gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("regression"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        List<?> newlyUnsatisfiable = (List<?>) verify.get("newly_unsatisfiable");
        assertFalse(newlyUnsatisfiable.isEmpty(),
                "the prevented regression must name the newly unsatisfiable class: " + verify);
        assertTrue(String.valueOf(newlyUnsatisfiable).contains(NS + "A"),
                () -> "newly_unsatisfiable must name " + NS + "A: " + newlyUnsatisfiable);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        assertFalse(ontology.containsAxiom(df.getOWLSubClassOfAxiom(
                        df.getOWLClass(IRI.create(NS + "A")), df.getOWLClass(IRI.create(NS + "B")))),
                "the prevented batch must not reach the live ontology");
    }

    @Test
    void aSecondViolationInAnAlreadyFailingStageIsAttributedToTheBatch() throws Exception {
        // The baseline already fails structural on a pre-existing subclass_cycle (X⊑Y, Y⊑X). A batch
        // that adds a SECOND cycle (A⊑B, B⊑A) must be attributed to THIS batch — coarse per-stage
        // finding keys share the baseline's generic key, so attribution has to compare per-check counts.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass x = df.getOWLClass(IRI.create(NS + "X"));
        OWLClass y = df.getOWLClass(IRI.create(NS + "Y"));
        for (OWLClass cls : List.of(a, b, x, y)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(cls.getIRI().getShortForm())));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                    df.getOWLAnnotationProperty(IRI.create("http://www.w3.org/2004/02/skos/core#definition")),
                    cls.getIRI(), df.getOWLLiteral("def " + cls.getIRI().getShortForm())));
        }
        // Pre-existing cycle X ⊑ Y ⊑ X: the baseline structural stage already fails at fail_on=warn.
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(x, y));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(y, x));
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "add a second subclass cycle",
                List.of(subclassOperation(NS + "A", NS + "B"), subclassOperation(NS + "B", NS + "A")),
                false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("fail", verify.get("gate"), verify::toString);
        assertEquals("fail", verify.get("baseline_gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("regression"),
                "a fresh violation of an already-failing stage is this batch's regression: " + verify);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        assertFalse(ontology.containsAxiom(df.getOWLSubClassOfAxiom(a, b)),
                "the batch's fresh structural violation must not reach the live ontology");
    }

    @Test
    void replacingAStandingViolationWithADifferentOneAtTheSameCountIsPrevented() throws Exception {
        // Count-only attribution considered this clean: removing X↔Y and adding A↔B leaves the
        // subclass_cycle count at two, but A/B are NEW offenders caused by this batch. The complete
        // per-check identity digest must distinguish the replacement and keep rollback fail-closed.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass x = df.getOWLClass(IRI.create(NS + "X"));
        OWLClass y = df.getOWLClass(IRI.create(NS + "Y"));
        for (OWLClass cls : List.of(a, b, x, y)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(cls.getIRI().getShortForm())));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                    df.getOWLAnnotationProperty(IRI.create(
                            "http://www.w3.org/2004/02/skos/core#definition")),
                    cls.getIRI(), df.getOWLLiteral("def " + cls.getIRI().getShortForm())));
        }
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(x, y));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(y, x));
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "replace one subclass cycle with another",
                List.of(removeSubclassOperation(NS + "X", NS + "Y"),
                        removeSubclassOperation(NS + "Y", NS + "X"),
                        subclassOperation(NS + "A", NS + "B"),
                        subclassOperation(NS + "B", NS + "A")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("fail", verify.get("gate"), verify::toString);
        assertEquals("fail", verify.get("baseline_gate"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("regression"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertFalse(ontology.containsAxiom(df.getOWLSubClassOfAxiom(a, b)),
                "the replacement cycle must not land");
        assertTrue(ontology.containsAxiom(df.getOWLSubClassOfAxiom(x, y)),
                "preventing the batch must also preserve the standing cycle it tried to remove");
    }

    // ------------------------------------------------------------------ fixtures

    /** Declared, labelled A ⊥ B plus a bystander C; adding A ⊑ B makes A unsatisfiable. */
    @Test
    void manyPreExistingUnsatisfiableClassesDoNotBrickACleanRollbackBatch() throws Exception {
        // Regression guard for the 25-item display-window attribution bug: an ontology with MORE
        // pre-existing unsatisfiable classes than the QC display cap (25) must still let a clean
        // batch apply. The baseline comparison diffs the COMPLETE uncapped IRI sets, so a batch that
        // adds nothing unsatisfiable is regression=false and applies — not conservatively prevented
        // forever merely because the display list was truncated.
        int preExisting = 30;
        OWLOntology ontology = manyUnsatFixture(preExisting);
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 120_000,
                "clean batch over " + preExisting + " legacy unsat classes",
                List.of(subclassOperation(NS + "ZZFresh", NS + "A")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("fail", verify.get("gate"), verify::toString);
        assertEquals("fail", verify.get("baseline_gate"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("regression"),
                "a clean batch must not be attributed a regression just because >25 classes were "
                        + "already unsatisfiable: " + verify);
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
        assertNull(verify.get("prevented_before_apply"), verify::toString);
        assertEquals(List.of(), verify.get("newly_unsatisfiable"), verify::toString);
        assertTrue(ontology.containsAxiom(ontology.getOWLOntologyManager().getOWLDataFactory()
                        .getOWLSubClassOfAxiom(
                                ontology.getOWLOntologyManager().getOWLDataFactory()
                                        .getOWLClass(IRI.create(NS + "ZZFresh")),
                                ontology.getOWLOntologyManager().getOWLDataFactory()
                                        .getOWLClass(IRI.create(NS + "A")))),
                "the clean rollback batch must land despite many legacy unsat classes");
    }

    @Test
    void newUnsatisfiableClassIsStillNamedBeyondTheDisplayWindow() throws Exception {
        // The counterpart: with many pre-existing unsat classes, a batch that genuinely breaks a
        // fresh class is still prevented AND names the culprit — the full-set diff does not lose the
        // real regression among the pre-existing offenders.
        int preExisting = 30;
        OWLOntology ontology = manyUnsatFixture(preExisting);
        ToolContext ctx = context(reasonerBackedManager(ontology));

        // A and B are disjoint; making Fresh a subclass of both breaks Fresh, a class absent from
        // the pre-existing unsat set.
        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 120_000,
                "unsatisfiability-introducing batch over many legacy unsat classes",
                List.of(subclassOperation(NS + "ZZFresh", NS + "A"),
                        subclassOperation(NS + "ZZFresh", NS + "B")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.TRUE, verify.get("regression"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        List<?> newlyUnsatisfiable = (List<?>) verify.get("newly_unsatisfiable");
        assertTrue(String.valueOf(newlyUnsatisfiable).contains(NS + "ZZFresh"),
                () -> "newly_unsatisfiable must name the genuinely broken class: " + newlyUnsatisfiable);
    }

    @Test
    void repairingAnInconsistentBaselineIsNotPreventedByALegacyUnsatClass() throws Exception {
        // The baseline is INCONSISTENT (an individual typed to an unsatisfiable class) and also carries
        // a legacy unsatisfiable class U. A rollback batch that removes the inconsistency leaves the
        // ontology consistent with U still unsatisfiable. Under an inconsistent baseline every class is
        // unsatisfiable, so U is NOT a fresh regression — the consistency repair must apply, not be
        // prevented and misattribute U to this batch.
        OWLOntology ontology = inconsistentWithLegacyUnsatFixture();
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "remove the inconsistency, leaving a legacy unsat class",
                List.of(removeSubclassOperation(NS + "C", NS + "B")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.TRUE, verify.get("was_inconsistent"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("regression"),
                "repairing inconsistency must not be attributed a regression: " + verify);
        assertEquals(List.of(), verify.get("newly_unsatisfiable"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("applied"),
                "the consistency repair must land: " + verify);
        assertNull(verify.get("prevented_before_apply"), verify::toString);
    }

    @Test
    void gateErrorInReportModeIsNotAttributedAsABatchRegression() throws Exception {
        // A gate that ERRORED (here: the isolated reasoner factory fails) could not produce a verdict,
        // so report mode must keep regression=false and gate=error — not claim a batch-caused
        // regression that would drive automation to undo a perfectly good batch on every reasoner
        // timeout/setup error. Rollback still fails closed on the same unverifiable gate.
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(throwingReasonerManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "report", 60_000,
                "clean batch while the reasoner is broken",
                List.of(subclassOperation(NS + "C", NS + "A")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("error", verify.get("gate"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("regression"),
                "a gate that could not be computed is not a batch regression: " + verify);
        assertEquals(Boolean.TRUE, verify.get("applied"),
                "report mode still commits the batch when the gate merely errored: " + verify);
        assertTrue(String.valueOf(verify.get("note")).contains("could not produce a verdict"),
                verify::toString);
    }

    @Test
    void gateErrorInRollbackModeStillFailsClosed() throws Exception {
        // The rollback counterpart: an unverifiable gate must prevent the batch (fail closed), even
        // though regression stays false (nothing was attributed to the batch).
        OWLOntology ontology = disjointFixture();
        ToolContext ctx = context(throwingReasonerManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 60_000,
                "clean batch while the reasoner is broken",
                List.of(subclassOperation(NS + "C", NS + "A")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals("error", verify.get("gate"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("regression"), verify::toString);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
        assertFalse(ontology.containsAxiom(ontology.getOWLOntologyManager().getOWLDataFactory()
                        .getOWLSubClassOfAxiom(
                                ontology.getOWLOntologyManager().getOWLDataFactory()
                                        .getOWLClass(IRI.create(NS + "C")),
                                ontology.getOWLOntologyManager().getOWLDataFactory()
                                        .getOWLClass(IRI.create(NS + "A")))),
                "an unverifiable gate must not let the rollback batch land");
    }

    @Test
    void rollbackPreventsAFreshStructuralViolationEvenWhenTheTotalCountDecreases() throws Exception {
        // Non-reasoner soundness: the batch breaks two standing subclass cycles and introduces one
        // FRESH cycle, so the gating structural count DROPS (6 classes-in-cycles -> 4) yet a new
        // violation this batch caused lands. Attribution set-diffs the complete gating identities, so
        // the fresh cycle is caught even though the count decreased — rollback must prevent it.
        OWLOntology ontology = labeledCyclesFixture(3, true);
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 120_000,
                "break two cycles, introduce one fresh cycle (net count down)",
                List.of(removeSubclassOperation(NS + "C1a", NS + "C1b"),
                        removeSubclassOperation(NS + "C1b", NS + "C1a"),
                        removeSubclassOperation(NS + "C2a", NS + "C2b"),
                        removeSubclassOperation(NS + "C2b", NS + "C2a"),
                        subclassOperation(NS + "Ga", NS + "Gb"),
                        subclassOperation(NS + "Gb", NS + "Ga")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.TRUE, verify.get("regression"),
                "a fresh violation that lands while the total count drops is still this batch's "
                        + "regression: " + verify);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
    }

    @Test
    void rollbackAllowsAPureStructuralImprovementThatOnlyRemovesViolations() throws Exception {
        // The counterpart: the batch only REMOVES standing cycles (adds nothing), so the changed
        // gating set is a subset of the baseline's — a pure improvement that must NOT be misattributed
        // as a regression just because the identity set changed and the count dropped.
        OWLOntology ontology = labeledCyclesFixture(3, false);
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 120_000,
                "break two cycles, add nothing (pure improvement)",
                List.of(removeSubclassOperation(NS + "C1a", NS + "C1b"),
                        removeSubclassOperation(NS + "C1b", NS + "C1a"),
                        removeSubclassOperation(NS + "C2a", NS + "C2b"),
                        removeSubclassOperation(NS + "C2b", NS + "C2a")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.FALSE, verify.get("regression"),
                "removing standing violations without adding any is an improvement: " + verify);
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
        assertNull(verify.get("prevented_before_apply"), verify::toString);
    }

    @Test
    void rollbackPreventsAFreshGovernanceViolationEvenWhenTheTotalCountDecreases() throws Exception {
        // Governance counterpart of the structural soundness test: the batch removes two standing
        // import_layering violations (axioms about imported terms) and adds one FRESH one, so the
        // governance gating count DROPS (3 -> 2) yet a new violation this batch caused lands. The
        // governance stage now records its stable gating identities, so the set-diff catches the fresh
        // axiom regardless of the count — rollback must prevent it.
        String importedIri = "https://example.org/imported";
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology imported = manager.createOntology(IRI.create(importedIri));
        for (int i = 1; i <= 4; i++) {
            manager.addAxiom(imported, df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(importedIri + "#T" + i))));
        }
        OWLOntology active = manager.createOntology(IRI.create("https://example.org/gate"));
        manager.applyChange(new AddImport(active, df.getOWLImportsDeclaration(IRI.create(importedIri))));
        OWLClass local = df.getOWLClass(IRI.create(NS + "Local"));
        OWLClass otherLocal = df.getOWLClass(IRI.create(NS + "OtherLocal"));
        manager.addAxiom(active, df.getOWLDeclarationAxiom(local));
        manager.addAxiom(active, df.getOWLDeclarationAxiom(otherLocal));
        for (int i = 1; i <= 3; i++) {   // three standing import_layering violations about T1..T3
            manager.addAxiom(active, df.getOWLSubClassOfAxiom(
                    df.getOWLClass(IRI.create(importedIri + "#T" + i)), local));
        }
        ToolContext ctx = context(reasonerBackedManager(active));

        // Remove two standing violations (T2⊑Local, T3⊑Local) and swap in a FRESH one on an already
        // imported term (T3⊑OtherLocal) — net import_layering count 3 -> 2, but a new offending axiom.
        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 120_000,
                "swap governance violations (remove two, add one; net count down)",
                List.of(removeSubclassOperation(importedIri + "#T2", NS + "Local"),
                        removeSubclassOperation(importedIri + "#T3", NS + "Local"),
                        subclassOperation(importedIri + "#T3", NS + "OtherLocal")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.TRUE, verify.get("regression"),
                "a fresh governance violation that lands while the total count drops is this batch's "
                        + "regression: " + verify);
        assertEquals(Boolean.TRUE, verify.get("prevented_before_apply"), verify::toString);
        assertEquals(Boolean.FALSE, verify.get("applied"), verify::toString);
    }

    @Test
    void rollbackAllowsShrinkingAStandingStructuralViolationGroup() throws Exception {
        // The batch shrinks a standing 3-node subclass cycle to a 2-node cycle (drops one member) — a
        // pure improvement. The subclass_cycle DETAIL string mutates ("cycle of 3: ..." -> "cycle of
        // 2: ..."), but attribution keys on the STABLE per-entity identities only, so the surviving
        // members are recognised as a subset: regression=false and the batch applies. Keying on the
        // mutable detail string would falsely flag this improvement as a fresh violation.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = labeledClass(manager, ontology, df, "A");
        OWLClass b = labeledClass(manager, ontology, df, "B");
        OWLClass c = labeledClass(manager, ontology, df, "C");
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(b, c));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(c, a));
        ToolContext ctx = context(reasonerBackedManager(ontology));

        Map<String, Object> result = structured(ChangeSetApplyVerify.apply(ctx, "rollback", 120_000,
                "shrink a 3-cycle to a 2-cycle (pure improvement)",
                List.of(subclassOperation(NS + "B", NS + "A"),
                        removeSubclassOperation(NS + "B", NS + "C"),
                        removeSubclassOperation(NS + "C", NS + "A")), false));

        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) result.get("verify");
        assertEquals(Boolean.FALSE, verify.get("regression"),
                "shrinking a standing violation group is an improvement, not a fresh violation: " + verify);
        assertEquals(Boolean.TRUE, verify.get("applied"), verify::toString);
    }

    /**
     * {@code cycles} labelled subclass cycles (each two mutually-subclassing classes), optionally with
     * a labelled but unrelated Ga/Gb pair a batch can turn into a fresh cycle. Labels keep the gating
     * structural set to subclass_cycle only (no missing_label), and isolated_class is info, not gating.
     */
    private static OWLOntology labeledCyclesFixture(int cycles, boolean declareSpare) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        for (int i = 0; i < cycles; i++) {
            OWLClass a = labeledClass(manager, ontology, df, "C" + i + "a");
            OWLClass b = labeledClass(manager, ontology, df, "C" + i + "b");
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(a, b));
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(b, a));
        }
        if (declareSpare) {
            labeledClass(manager, ontology, df, "Ga");
            labeledClass(manager, ontology, df, "Gb");
        }
        return ontology;
    }

    private static OWLClass labeledClass(OWLOntologyManager manager, OWLOntology ontology,
            OWLDataFactory df, String local) {
        OWLClass c = df.getOWLClass(IRI.create(NS + local));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(c));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral(local)));
        return c;
    }

    /**
     * An INCONSISTENT ontology (individual i typed to C, where C is subclass of disjoint A and B) plus a
     * legacy unsatisfiable class U (subclass of disjoint E and F). Removing {@code C ⊑ B} repairs the
     * inconsistency, leaving the ontology consistent with U still unsatisfiable.
     */
    private static OWLOntology inconsistentWithLegacyUnsatFixture() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        OWLClass e = df.getOWLClass(IRI.create(NS + "E"));
        OWLClass f = df.getOWLClass(IRI.create(NS + "F"));
        OWLClass u = df.getOWLClass(IRI.create(NS + "U"));
        for (OWLClass cls : List.of(a, b, c, e, f, u)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
        }
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(a, b));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(c, a));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(c, b));
        // i : C makes the ontology inconsistent (C is unsatisfiable, so it can have no instance).
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(c,
                df.getOWLNamedIndividual(IRI.create(NS + "i"))));
        // A legacy unsatisfiable class independent of the inconsistency.
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(e, f));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(u, e));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(u, f));
        return ontology;
    }

    /** Disjoint A/B plus {@code count} classes each subclass of both A and B (all unsatisfiable). */
    private static OWLOntology manyUnsatFixture(int count) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        // The fresh class name sorts AFTER every U* so it lands OUTSIDE the 25-item display window,
        // exercising the beyond-window naming path.
        OWLClass fresh = df.getOWLClass(IRI.create(NS + "ZZFresh"));
        for (OWLClass cls : List.of(a, b, fresh)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
        }
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(a, b));
        for (int i = 0; i < count; i++) {
            OWLClass u = df.getOWLClass(IRI.create(NS + "U" + i));
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(u));
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(u, a));
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(u, b));
        }
        return ontology;
    }

    private static OWLOntology disjointFixture() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/gate"));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        for (OWLClass cls : List.of(a, b, c)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(cls.getIRI().getShortForm())));
        }
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(a, b));
        return ontology;
    }

    private static Map<String, Object> subclassOperation(String sub, String sup) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("axiom_type", "subclass_of");
        op.put("sub", sub);
        op.put("super", sup);
        return op;
    }

    private static Map<String, Object> removeSubclassOperation(String sub, String sup) {
        Map<String, Object> op = subclassOperation(sub, sup);
        op.put("op", "remove");
        return op;
    }

    private static ToolContext context(OWLModelManager mm) {
        return new ToolContext(HeadlessAccess.over(mm),
                new McpServerController(new OntologyAccess(null)));
    }

    /** As {@link #reasonerBackedManager} but the reasoner factory throws, forcing gate=error. */
    private static OWLModelManager throwingReasonerManager(OWLOntology ontology) {
        OWLReasonerFactory throwingFactory = (OWLReasonerFactory) Proxy.newProxyInstance(
                ChangeSetApplyVerifyBaselineAttributionTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerFactory.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getReasonerName" -> "Throwing";
                            case "createReasoner", "createNonBufferingReasoner" -> {
                                throw new RuntimeException("reasoner factory is broken (test)");
                            }
                            case "toString" -> "ThrowingReasonerFactory";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        return reasonerManager(ontology, throwingFactory, "org.semanticweb.HermiT", "HermiT");
    }

    /** {@link FakeModelManager} plus a HermiT-backed reasoner manager, as the gate tests do. */
    private static OWLModelManager reasonerBackedManager(OWLOntology ontology) {
        return reasonerManager(ontology, new org.semanticweb.HermiT.ReasonerFactory(),
                "org.semanticweb.HermiT", "HermiT");
    }

    private static OWLModelManager reasonerManager(OWLOntology ontology, OWLReasonerFactory factory,
            String reasonerId, String reasonerName) {
        OWLReasonerConfiguration configuration = new SimpleConfiguration();
        ProtegeOWLReasonerInfo info = (ProtegeOWLReasonerInfo) Proxy.newProxyInstance(
                ChangeSetApplyVerifyBaselineAttributionTest.class.getClassLoader(),
                new Class<?>[] {ProtegeOWLReasonerInfo.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getReasonerId" -> reasonerId;
                            case "getReasonerName" -> reasonerName;
                            case "getReasonerFactory" -> factory;
                            case "getRecommendedBuffering" -> BufferingMode.NON_BUFFERING;
                            case "getConfiguration" -> configuration;
                            case "toString" -> reasonerName + " attribution-test info";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                ChangeSetApplyVerifyBaselineAttributionTest.class.getClassLoader(),
                new Class<?>[] {OWLReasonerManager.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                            case "getCurrentReasonerFactory" -> info;
                            case "getCurrentReasonerFactoryId" -> reasonerId;
                            case "getCurrentReasonerName" -> reasonerName;
                            case "getInstalledReasonerFactories" -> Set.of();
                            case "getCurrentReasoner", "classifyAsynchronously" ->
                                    throw new AssertionError(
                                            "the verified apply must never consult the live reasoner");
                            case "toString" -> "AttributionReasonerManager";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        OWLModelManager base = FakeModelManager.over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                ChangeSetApplyVerifyBaselineAttributionTest.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return reasoners;
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
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
        return (Map<String, Object>) result.structuredContent();
    }
}
