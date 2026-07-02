package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for the F1 regression-decision core {@link ApplyVerify#decide} and mode parsing. The
 * live EDT/reasoner orchestration is exercised by the smoke-test checklist; here the pure verdict logic
 * is pinned across every branch (clean, newly-unsat, newly-inconsistent, pre-existing, empty-minimised,
 * intervening, non-computable, report-vs-rollback).
 */
class ApplyVerifyTest {

    private static Set<String> set(String... s) {
        return new LinkedHashSet<>(java.util.Arrays.asList(s));
    }

    private static final Set<String> NONE = Collections.emptySet();

    // ------------------------------------------------------------------ normalizeMode

    @Test
    void normalizeModeDefaultsToNone() {
        assertEquals(ApplyVerify.MODE_NONE, ApplyVerify.normalizeMode(null));
        assertEquals(ApplyVerify.MODE_NONE, ApplyVerify.normalizeMode("  "));
    }

    @Test
    void normalizeModeAcceptsAndLowercases() {
        assertEquals(ApplyVerify.MODE_REPORT, ApplyVerify.normalizeMode("Report"));
        assertEquals(ApplyVerify.MODE_ROLLBACK, ApplyVerify.normalizeMode("ROLLBACK"));
    }

    @Test
    void normalizeModeRejectsUnknown() {
        assertThrows(ToolArgException.class, () -> ApplyVerify.normalizeMode("undo"));
    }

    // ------------------------------------------------------------------ decide: clean

    @Test
    void cleanApplyIsKept() {
        // report, pre {A} == post {A}: no new unsat, no rollback, changes remain.
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_REPORT, true, false, false,
                set("A"), set("A"), false, true);
        assertFalse(o.regression);
        assertFalse(o.rolledBack);
        assertTrue(o.batchApplied);
        assertTrue(o.newlyUnsatisfiable.isEmpty());
    }

    // ------------------------------------------------------------------ decide: newly unsatisfiable

    @Test
    void newlyUnsatisfiableFlaggedInReportMode() {
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_REPORT, true, false, false,
                NONE, set("B"), false, true);
        assertTrue(o.regression);
        assertFalse(o.rolledBack, "report never rolls back");
        assertTrue(o.batchApplied);
        assertEquals(set("B"), o.newlyUnsatisfiable);
    }

    @Test
    void newlyUnsatisfiableRolledBackInRollbackMode() {
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, false,
                NONE, set("B"), false, true);
        assertTrue(o.regression);
        assertTrue(o.rolledBack);
        assertFalse(o.batchApplied, "rolled-back changes do not remain");
        assertEquals(set("B"), o.newlyUnsatisfiable);
    }

    @Test
    void preExistingUnsatIsNotAttributedToTheBatch() {
        // A was already unsatisfiable before AND after → not newly unsat → no regression.
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, false,
                set("A"), set("A"), false, true);
        assertFalse(o.regression);
        assertFalse(o.rolledBack);
        assertTrue(o.batchApplied);
    }

    // ------------------------------------------------------------------ decide: inconsistency

    @Test
    void newlyInconsistentIsARegressionAndRollsBack() {
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, true,
                NONE, NONE, false, true);
        assertTrue(o.regression);
        assertTrue(o.rolledBack);
        assertTrue(o.postInconsistent);
    }

    @Test
    void preExistingInconsistencyIsNotAttributed() {
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, true, true,
                NONE, NONE, false, true);
        assertFalse(o.regression);
        assertFalse(o.rolledBack);
    }

    // ------------------------------------------------------------------ decide: empty-minimised batch

    @Test
    void emptyMinimisedBatchIsNeverRolledBack() {
        // appliedSomething=false: even a "regression" reading has no history entry to undo.
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, false,
                NONE, set("B"), false, false);
        assertTrue(o.regression);
        assertFalse(o.rolledBack, "nothing was applied, so nothing to undo");
        assertFalse(o.batchApplied);
    }

    // ------------------------------------------------------------------ decide: intervening change

    @Test
    void interveningChangeDegradesToReportAndSkipsRollback() {
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, true, false, false,
                NONE, set("B"), true, true);
        assertTrue(o.regression);
        assertFalse(o.rolledBack, "an intervening edit blocks blind rollback");
        assertTrue(o.concurrentChange);
        assertTrue(o.degradedToReport);
        assertTrue(o.batchApplied, "the batch stays applied when we cannot safely roll back");
    }

    // ------------------------------------------------------------------ decide: non-computable verdict

    @Test
    void nonComputableVerdictReportsNoRegression() {
        // e.g. classification did not complete: no verdict, changes kept, no rollback.
        ApplyVerify.Outcome o = ApplyVerify.decide(ApplyVerify.MODE_ROLLBACK, false, false, false,
                NONE, set("B"), false, true);
        assertFalse(o.regression);
        assertFalse(o.rolledBack);
        assertTrue(o.batchApplied);
        assertTrue(o.newlyUnsatisfiable.isEmpty(), "no verdict → no attributed unsat classes");
    }
}
