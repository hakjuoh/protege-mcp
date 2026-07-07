package io.github.hakjuoh.protege_mcp.server;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Drives a classification and waits for it to finish.
 *
 * <p>{@code classifyAsynchronously} returns immediately (a background "Classification Thread"), so
 * we register an {@link OWLModelManagerListener}, start classification, and block off-EDT on a
 * {@link CountDownLatch} that the {@code ONTOLOGY_CLASSIFIED} event (delivered on the EDT) counts
 * down — never blocking the EDT itself. A bounded wait keeps a hung reasoner from hanging the tool.
 */
public final class EmbeddedClassificationWaiter {

    private EmbeddedClassificationWaiter() {
    }

    /**
     * Classify and wait, returning a structured result object. On a started run:
     * {@code {started:true, completed, classification_failed, reasoner, status, inconsistent,
     * unsatisfiable_count?, message}}. {@code classification_failed} is true when a run that signalled
     * completion left the reasoner UNINITIALIZED — Protégé caught a reasoner-factory exception and reset
     * to the Null reasoner (the exception is only in the Protégé log). If classification could not be
     * started (no reasoner, or one already running) the result is the shorter {@code {started:false, message}}.
     */
    public static Map<String, Object> runAndWait(OntologyAccess access, long timeoutMillis) {
        CountDownLatch latch = new CountDownLatch(1);
        OWLModelManagerListener listener = event -> {
            if (event.isType(EventType.ONTOLOGY_CLASSIFIED)) {
                latch.countDown();
            }
        };

        Boolean started = access.compute(mm -> {
            OWLReasonerManager rm = mm.getOWLReasonerManager();
            mm.addListener(listener);
            boolean ok = rm.classifyAsynchronously(EnumSet.allOf(InferenceType.class));
            if (!ok) {
                mm.removeListener(listener);
            }
            return ok;
        });

        if (started == null || !started) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("started", false);
            result.put("message", "Could not start classification — no reasoner is selected, "
                    + "or one is already running.");
            return result;
        }

        boolean completed;
        try {
            completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        final boolean done = completed;

        return access.compute(mm -> {
            mm.removeListener(listener);
            OWLReasonerManager rm = mm.getOWLReasonerManager();
            ReasonerStatus status = rm.getReasonerStatus();
            // A classification we started (started==true ⇒ a factory was selected) that signalled
            // completion but left the reasoner UNINITIALIZED means Protégé's ClassificationRunner caught
            // an exception from the reasoner factory and silently reset the ontology's reasoner to the
            // Null reasoner (the real exception — e.g. "HermiT does not support SWRL built-in atoms" — is
            // logged to ~/.Protege/logs/protege.log only). Flag it so callers report an error instead of a
            // benign-looking Null-reasoner no-op.
            boolean classificationFailed = done && status == ReasonerStatus.REASONER_NOT_INITIALIZED;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("started", true);
            result.put("completed", done);
            result.put("classification_failed", classificationFailed);
            result.put("reasoner", rm.getCurrentReasonerName());
            result.put("status", String.valueOf(status));
            result.put("inconsistent", status == ReasonerStatus.INCONSISTENT);
            StringBuilder message = new StringBuilder();
            if (!done) {
                message.append("Classification did not signal completion within ").append(timeoutMillis)
                        .append(" ms. ");
            }
            if (classificationFailed) {
                message.append("Classification FAILED: the reasoner rejected the ontology and was reset "
                        + "to the Null reasoner (a common cause is a SWRL rule using a built-in atom, which "
                        + "HermiT does not support). See ~/.Protege/logs/protege.log for the exact reasoner "
                        + "exception. ");
            }
            message.append("Reasoner: ").append(rm.getCurrentReasonerName())
                    .append(". Status: ").append(status).append('.');
            if (status == ReasonerStatus.INCONSISTENT) {
                message.append(" The ontology is INCONSISTENT.");
            } else if (status.isEnableStop()) {
                try {
                    OWLReasoner reasoner = mm.getReasoner();
                    int unsat = reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom().size();
                    result.put("unsatisfiable_count", unsat);
                    message.append(" Unsatisfiable classes: ").append(unsat).append('.');
                } catch (RuntimeException ignored) {
                    // reasoner may be unable to answer (e.g. mid-state); status line is enough
                }
            }
            result.put("message", message.toString());
            return result;
        });
    }
}
