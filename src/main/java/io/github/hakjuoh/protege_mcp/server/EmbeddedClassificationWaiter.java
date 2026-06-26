package io.github.hakjuoh.protege_mcp.server;

import java.util.EnumSet;
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

    public static String runAndWait(OntologyAccess access, long timeoutMillis) {
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
            return "Could not start classification — no reasoner is selected, or one is already running.";
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
            StringBuilder sb = new StringBuilder();
            if (!done) {
                sb.append("Classification did not signal completion within ").append(timeoutMillis)
                        .append(" ms. ");
            }
            sb.append("Reasoner: ").append(rm.getCurrentReasonerName())
                    .append(". Status: ").append(status).append('.');
            if (status == ReasonerStatus.INCONSISTENT) {
                sb.append(" The ontology is INCONSISTENT.");
            } else if (status.isEnableStop()) {
                try {
                    OWLReasoner reasoner = mm.getReasoner();
                    int unsat = reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom().size();
                    sb.append(" Unsatisfiable classes: ").append(unsat).append('.');
                } catch (RuntimeException ignored) {
                    // reasoner may be unable to answer (e.g. mid-state); status line is enough
                }
            }
            return sb.toString();
        });
    }
}
