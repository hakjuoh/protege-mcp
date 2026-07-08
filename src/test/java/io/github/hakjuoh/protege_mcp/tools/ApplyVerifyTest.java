package io.github.hakjuoh.protege_mcp.tools;

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

    // -------------------------------------------------- preApply: rollback fails closed (0.4.3)

    /**
     * A model-manager double for the {@code preApply} gate: answers only
     * {@code getOWLReasonerManager().getReasonerStatus()} (scripted) — enough to reach the
     * fail-closed decision, which must fire BEFORE anything else (history, applier) is touched.
     */
    private static org.protege.editor.owl.model.OWLModelManager statusOnlyManager(
            org.protege.editor.owl.model.inference.ReasonerStatus status) {
        Object rm = java.lang.reflect.Proxy.newProxyInstance(
                ApplyVerifyTest.class.getClassLoader(),
                new Class<?>[] { org.protege.editor.owl.model.inference.OWLReasonerManager.class },
                (proxy, method, args) -> {
                    if ("getReasonerStatus".equals(method.getName())) {
                        return status;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (org.protege.editor.owl.model.OWLModelManager) java.lang.reflect.Proxy.newProxyInstance(
                ApplyVerifyTest.class.getClassLoader(),
                new Class<?>[] { org.protege.editor.owl.model.OWLModelManager.class },
                (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return rm;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static Object callPreApply(Object mm, String verify,
            java.util.function.Function<?, ?> applier) throws Throwable {
        java.lang.reflect.Method m = ApplyVerify.class.getDeclaredMethod("preApply",
                org.protege.editor.owl.model.OWLModelManager.class, String.class,
                java.util.function.Function.class);
        m.setAccessible(true);
        try {
            return m.invoke(null, mm, verify, applier);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object declinedOf(Object preApply) throws Throwable {
        java.lang.reflect.Field f = preApply.getClass().getDeclaredField("declined");
        f.setAccessible(true);
        return f.get(preApply);
    }

    @Test
    void rollbackWithoutABaselineDeclinesBeforeApplying() throws Throwable {
        // The reasoner never produced usable pre-apply results (e.g. the cold-start classification
        // failed): verify=rollback must refuse up front — the applier must never run.
        java.util.function.Function<Object, Object> neverRuns = mm -> {
            throw new AssertionError("the applier must not run when rollback fails closed");
        };
        Object pre = callPreApply(
                statusOnlyManager(org.protege.editor.owl.model.inference.ReasonerStatus.REASONER_NOT_INITIALIZED),
                ApplyVerify.MODE_ROLLBACK, neverRuns);
        Object declined = declinedOf(pre);
        assertTrue(declined instanceof io.modelcontextprotocol.spec.McpSchema.CallToolResult,
                "rollback without a baseline declines with an error result");
        io.modelcontextprotocol.spec.McpSchema.CallToolResult r =
                (io.modelcontextprotocol.spec.McpSchema.CallToolResult) declined;
        assertEquals(Boolean.TRUE, r.isError());
        String msg = String.valueOf(((java.util.Map<String, Object>) r.structuredContent()).get("error"));
        assertTrue(msg.contains("NOTHING was applied"), "says nothing was applied: " + msg);
        assertTrue(msg.contains("REASONER_NOT_INITIALIZED"), "names the status: " + msg);
        assertTrue(msg.contains("verify=report"), "offers the report/none fallback: " + msg);
    }

    @Test
    void reportWithoutABaselineStillApplies() throws Throwable {
        // verify=report keeps 0.4.0 semantics: apply, then report the batch as unverifiable. The
        // status-only manager throws on getHistoryManager, proving the gate is rollback-specific
        // by failing PAST the decline point.
        java.util.function.Function<Object, Object> applier = mm -> java.util.Collections.emptyMap();
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> callPreApply(
                        statusOnlyManager(org.protege.editor.owl.model.inference.ReasonerStatus.REASONER_NOT_INITIALIZED),
                        ApplyVerify.MODE_REPORT, applier));
        assertEquals("getHistoryManager", e.getMessage(),
                "report mode proceeds past the baseline gate into the apply leg");
    }
}
