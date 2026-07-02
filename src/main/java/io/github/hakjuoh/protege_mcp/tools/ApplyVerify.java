package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.server.EmbeddedClassificationWaiter;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * The {@code apply_changes verify=report|rollback} orchestration (F1): apply a batch, classify the
 * reasoner, and decide whether the batch caused a <em>regression</em> — a class that became unsatisfiable
 * or an ontology that became inconsistent <em>because of this batch</em>. {@code report} keeps the batch
 * and returns the verdict; {@code rollback} additionally reverts the whole batch (one {@code undo}) when a
 * regression is found and it is safe to attribute it.
 *
 * <p>The work spans the EDT/async boundary and several hops, so it runs under the server-level
 * {@linkplain ToolContext#writeLock() write mutex} (two concurrent verify-applies serialise) and, because
 * an interactive GUI edit cannot take that mutex, also detects an intervening change between apply and the
 * post-classification read — degrading to report semantics rather than blind-undoing. The classification
 * runs via {@link EmbeddedClassificationWaiter#runAndWait} (off the EDT, never nested in a {@code compute}
 * body — that would deadlock the EDT), and every unsatisfiable read uses the manager's current reasoner
 * <em>after</em> a completed classification (the status-gated verdict stubs out while {@code OUT_OF_SYNC}).
 *
 * <p>The regression <em>decision</em> ({@link #decide}) is a pure function over primitive flags and IRI
 * sets, so it is unit-tested headless; only the EDT/reasoner plumbing needs a live Protégé (the live leg
 * is the checklist in {@code docs/smoke-test.md}).
 */
final class ApplyVerify {

    private ApplyVerify() {
    }

    static final String MODE_NONE = "none";
    static final String MODE_REPORT = "report";
    static final String MODE_ROLLBACK = "rollback";

    /** Normalise the {@code verify} argument to none/report/rollback (default none). */
    static String normalizeMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return MODE_NONE;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case MODE_NONE:
            case MODE_REPORT:
            case MODE_ROLLBACK:
                return s;
            default:
                throw new ToolArgException("verify must be one of none, report, rollback (not '"
                        + raw + "').");
        }
    }

    // ================================================================== orchestration (live Protégé)

    /**
     * Apply {@code operations} and verify. Precondition: {@code verify} is report or rollback. Holds the
     * write mutex across the whole pre-read → apply → classify → post-read → (conditional) undo sequence.
     */
    static CallToolResult verifiedApply(ToolContext ctx, List<Map<String, Object>> operations,
            boolean strict, String verify, int timeoutMs, String summary) {
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, summary);
        if (denied != null) {
            return denied;
        }
        OntologyAccess access = ctx.access();
        ReentrantLock lock = ctx.writeLock();
        lock.lock();
        try {
            // Phase A: reasoner readiness. A NoOp/absent factory would make classifyAsynchronously return
            // true with no event and burn the whole timeout, so we refuse up front (mirrors run_reasoner).
            Probe probe = access.compute(ApplyVerify::probe);
            if (probe.noFactory) {
                return Tools.error("apply_changes verify=" + verify + " needs a reasoner, but none is "
                        + "selected in Protégé (Reasoner menu). Select one, or call apply_changes with "
                        + "verify=none.");
            }
            // Cold reasoner: classify once to establish a pre-apply baseline (the 2-classification path).
            if (!probe.warm) {
                EmbeddedClassificationWaiter.runAndWait(access, timeoutMs);
            }
            // Phase B: read the pre-apply baseline AND apply the batch in one EDT hop.
            PreApply pre = access.compute(mm -> preApply(mm, operations, strict));
            // Phase C: classify the edited model, off the EDT.
            Map<String, Object> classify = EmbeddedClassificationWaiter.runAndWait(access, timeoutMs);
            // Phase D: post-read, decide, and conditionally undo — one EDT hop.
            return access.compute(mm -> finish(mm, verify, pre, classify));
        } finally {
            lock.unlock();
        }
    }

    /** Phase A reasoner readiness probe. */
    private static Probe probe(OWLModelManager mm) {
        ReasonerStatus status = mm.getOWLReasonerManager().getReasonerStatus();
        return new Probe(status == ReasonerStatus.NO_REASONER_FACTORY_CHOSEN, resultsCurrent(status));
    }

    /** Phase B: capture pre-apply unsat baseline + inconsistency, then apply the batch. */
    private static PreApply preApply(OWLModelManager mm, List<Map<String, Object>> operations,
            boolean strict) {
        ReasonerStatus status = mm.getOWLReasonerManager().getReasonerStatus();
        boolean preInconsistent = status == ReasonerStatus.INCONSISTENT;
        boolean baselineAvailable = resultsCurrent(status);
        Set<String> preUnsat = Collections.emptySet();
        if (baselineAvailable && !preInconsistent) {
            try {
                preUnsat = unsatIris(mm);
            } catch (RuntimeException e) {
                baselineAvailable = false;   // could not read a baseline → cannot attribute later
            }
        }
        HistoryManager hm = mm.getHistoryManager();
        int loggedBefore = hm.getLoggedChanges().size();
        Map<String, Object> batch = WriteTools.applyBatchData(mm, operations, strict);
        // Use the ACTUAL post-apply log size as the intervening-change baseline, not loggedBefore+1: the
        // model manager's ChangeListMinimizer can collapse the batch to zero and log NO undo entry even
        // when applyBatch planned changes, so a +1 assumption would over/under-count. "committed to the
        // undo stack" (didLog) — not the planned-something flag — is what tells us there is an entry to
        // undo (rollback) and gates the intervening check.
        int loggedAfter = hm.getLoggedChanges().size();
        boolean didLog = loggedAfter > loggedBefore;
        return new PreApply(baselineAvailable, preInconsistent, preUnsat, batch, didLog, loggedAfter);
    }

    /** Phase D: read the post-apply verdict, decide, roll back if warranted, and render the result. */
    private static CallToolResult finish(OWLModelManager mm, String verify, PreApply pre,
            Map<String, Object> classify) {
        ReasonerStatus status = mm.getOWLReasonerManager().getReasonerStatus();
        boolean started = Boolean.TRUE.equals(classify.get("started"));
        boolean completed = Boolean.TRUE.equals(classify.get("completed"));
        boolean postInconsistent = status == ReasonerStatus.INCONSISTENT;
        boolean postAvailable = resultsCurrent(status);
        Set<String> postUnsat = Collections.emptySet();
        if (postAvailable && !postInconsistent) {
            try {
                postUnsat = unsatIris(mm);
            } catch (RuntimeException e) {
                postAvailable = false;
            }
        }
        HistoryManager hm = mm.getHistoryManager();
        // Intervening-change detection is by log SIZE (there is no top-marker API). This catches the
        // realistic case — a concurrent GUI/other edit pushes an entry during our off-EDT classification.
        // A size-NEUTRAL compensating sequence (an add + a remove that nets zero) in that same window would
        // slip past; it is vanishingly unlikely and, being a residual, only ever risks a missed-degrade,
        // so we accept it (the write mutex already serialises concurrent verify-applies).
        boolean intervening = hm.getLoggedChanges().size() != pre.expectedLogged;
        boolean verdictComputable = pre.baselineAvailable && started && completed && postAvailable;

        Outcome outcome = decide(verify, verdictComputable, pre.preInconsistent, postInconsistent,
                pre.preUnsat, postUnsat, intervening, pre.committed);

        if (outcome.rolledBack && hm.canUndo()) {
            hm.undo();   // the batch is one logged entry — this reverts the whole batch
        } else if (outcome.rolledBack) {
            // Guard: decide() said roll back, but there is unexpectedly nothing to undo. Do not undo an
            // unrelated entry; report as a kept regression instead.
            outcome.rolledBack = false;
            outcome.batchApplied = pre.committed;
            outcome.degradedToReport = true;
        }
        return render(mm, verify, pre, classify, outcome);
    }

    // ================================================================== decision core (pure, tested)

    /** The regression verdict + what was done about it. */
    static final class Outcome {
        boolean regression;
        boolean rolledBack;
        boolean batchApplied;      // do the batch's changes remain in the model?
        boolean concurrentChange;  // an intervening edit between apply and re-classification
        boolean degradedToReport;  // a regression that could not be safely rolled back
        boolean verdictComputable;
        boolean postInconsistent;
        Set<String> newlyUnsatisfiable = Collections.emptySet();
    }

    /**
     * Decide the regression verdict and the action, from pure inputs. A regression is a class that became
     * unsatisfiable ({@code postUnsat \ preUnsat} non-empty) or an ontology that became inconsistent
     * ({@code postInconsistent && !preInconsistent}) — attributed to the batch only when the verdict is
     * computable (a real baseline and a completed post-classification) and, for a rollback, only when no
     * intervening edit occurred and the batch actually committed something to undo.
     */
    static Outcome decide(String verify, boolean verdictComputable, boolean preInconsistent,
            boolean postInconsistent, Set<String> preUnsat, Set<String> postUnsat, boolean intervening,
            boolean appliedSomething) {
        Outcome o = new Outcome();
        o.verdictComputable = verdictComputable;
        o.postInconsistent = postInconsistent;
        o.concurrentChange = intervening;
        boolean newlyInconsistent = postInconsistent && !preInconsistent;
        Set<String> newly = new LinkedHashSet<>();
        if (verdictComputable && !postInconsistent && !preInconsistent) {
            newly.addAll(postUnsat);
            newly.removeAll(preUnsat);
        }
        o.newlyUnsatisfiable = newly;
        o.regression = verdictComputable && (newlyInconsistent || !newly.isEmpty());
        boolean wantRollback = MODE_ROLLBACK.equals(verify);
        o.rolledBack = wantRollback && o.regression && !intervening && appliedSomething;
        o.batchApplied = appliedSomething && !o.rolledBack;
        o.degradedToReport = wantRollback && o.regression && !o.rolledBack;
        return o;
    }

    // ================================================================== result rendering

    private static CallToolResult render(OWLModelManager mm, String verify, PreApply pre,
            Map<String, Object> classify, Outcome o) {
        Tools.Json v = Tools.json()
                .put("mode", verify)
                .put("regression", o.regression)
                .put("inconsistent", o.postInconsistent);
        if (pre.preInconsistent) {
            v.put("was_inconsistent", true);
        }
        v.put("newly_unsatisfiable", entityListFromIris(mm, o.newlyUnsatisfiable));
        v.put("rolled_back", o.rolledBack);
        v.put("applied", o.batchApplied);
        v.put("classification_started", Boolean.TRUE.equals(classify.get("started")));
        v.put("classification_completed", Boolean.TRUE.equals(classify.get("completed")));
        v.putIfNotNull("reasoner", classify.get("reasoner"));
        if (o.concurrentChange) {
            v.put("concurrent_change", true);
        }
        v.put("note", note(pre, o));
        return Tools.json()
                .put("operations", pre.batch.get("operations"))
                .put("summary", pre.batch.get("summary"))
                .put("verify", v.map())
                .result();
    }

    private static String note(PreApply pre, Outcome o) {
        StringBuilder n = new StringBuilder();
        if (!pre.committed) {
            n.append("No changes were applied (the batch minimised to zero changes), so there was "
                    + "nothing to verify or roll back.");
        } else if (!o.verdictComputable) {
            n.append("The batch was applied but could not be verified — no completed reasoner "
                    + "classification (select a reasoner and ensure it finishes within timeout_ms). "
                    + "Run run_reasoner to check the current state.");
        } else if (o.rolledBack) {
            n.append("Regression detected (see newly_unsatisfiable / inconsistent); the whole batch was "
                    + "rolled back in one undo.");
        } else if (o.degradedToReport && o.concurrentChange) {
            n.append("Regression detected, but the ontology changed concurrently (a GUI edit or another "
                    + "call) between apply and re-classification, so the batch was NOT rolled back "
                    + "automatically — review and undo_change manually if it was this batch.");
        } else if (o.regression) {
            n.append("Regression detected (see newly_unsatisfiable / inconsistent); the batch was kept "
                    + "(verify=report). Call undo_change to revert it if unintended.");
        } else {
            n.append("No regression: the batch introduced no newly unsatisfiable class and left the "
                    + "ontology consistent.");
        }
        if (pre.preInconsistent) {
            n.append(" Note: the ontology was already inconsistent before this batch, so new problems "
                    + "could not be attributed to it (no rollback).");
        }
        return n.toString();
    }

    private static Map<String, Object> entityListFromIris(OWLModelManager mm, Set<String> iris) {
        OWLDataFactory df = mm.getOWLDataFactory();
        List<OWLClass> classes = new ArrayList<>();
        for (String iri : iris) {
            classes.add(df.getOWLClass(IRI.create(iri)));
        }
        return Tools.entityList(mm, classes, Integer.MAX_VALUE);
    }

    // ================================================================== small helpers

    /** Current, usable results (no pending changes): INITIALIZED or INCONSISTENT. */
    private static boolean resultsCurrent(ReasonerStatus status) {
        return status == ReasonerStatus.INITIALIZED || status == ReasonerStatus.INCONSISTENT;
    }

    /** Unsatisfiable named classes (minus owl:Nothing), as IRI strings, from the current reasoner. */
    private static Set<String> unsatIris(OWLModelManager mm) {
        return unsatIris(mm.getReasoner());
    }

    /**
     * Unsatisfiable named classes (minus owl:Nothing) reported by {@code reasoner}, as IRI strings. Split
     * out to take the {@link OWLReasoner} directly so this exact iteration — the leg that turns a real DL
     * reasoner's verdict into the {@code postUnsat} set that {@link #decide} attributes — is unit-testable
     * headless against a real reasoner (the enclosing {@code unsatIris(OWLModelManager)} only supplies
     * {@code mm.getReasoner()}).
     */
    static Set<String> unsatIris(OWLReasoner reasoner) {
        Set<String> out = new LinkedHashSet<>();
        for (OWLClass c : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
            out.add(c.getIRI().toString());
        }
        return out;
    }

    // ================================================================== carriers

    private static final class Probe {
        final boolean noFactory;
        final boolean warm;

        Probe(boolean noFactory, boolean warm) {
            this.noFactory = noFactory;
            this.warm = warm;
        }
    }

    private static final class PreApply {
        final boolean baselineAvailable;
        final boolean preInconsistent;
        final Set<String> preUnsat;
        final Map<String, Object> batch;
        /** True iff the apply committed a real undo entry (survived the manager's change minimizer). */
        final boolean committed;
        /** The log size right after our apply — the intervening-change baseline. */
        final int expectedLogged;

        PreApply(boolean baselineAvailable, boolean preInconsistent, Set<String> preUnsat,
                Map<String, Object> batch, boolean committed, int expectedLogged) {
            this.baselineAvailable = baselineAvailable;
            this.preInconsistent = preInconsistent;
            this.preUnsat = preUnsat;
            this.batch = batch;
            this.committed = committed;
            this.expectedLogged = expectedLogged;
        }
    }
}
