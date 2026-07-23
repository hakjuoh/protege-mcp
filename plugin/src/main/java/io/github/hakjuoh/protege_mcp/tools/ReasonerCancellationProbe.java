package io.github.hakjuoh.protege_mcp.tools;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.reasoner.CapabilityStatus;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;

/**
 * Per-window live cancellation evidence for exact reasoner configurations.
 *
 * <p>Unknown/untested profiles are rejected without construction. A reviewed bounded profile must
 * additionally create, accept interruption/disposal, and terminate a private probe within the public
 * five-second grace. Results are cached by exact profile key for the context lifetime.</p>
 */
final class ReasonerCancellationProbe {
    private static final Duration GRACE = Duration.ofSeconds(5);
    private final ReasonerCapabilityRegistry profiles = new ReasonerCapabilityRegistry();
    private final Map<String, Boolean> evidence = new ConcurrentHashMap<>();

    boolean proven(IsolatedReasonerSpec spec) {
        if (spec == null) return false;
        ReasonerCapabilityReport report = profiles.report(spec.capabilityIdentity());
        if (!"reviewed".equals(report.profileStatus())
                || report.owlStatus("bounded_cancellation") != CapabilityStatus.SUPPORTED) {
            return false;
        }
        return evidence.computeIfAbsent(
                report.identity().profileKey(), ignored -> probe(spec, GRACE));
    }

    /** Package-private bounded seam for cancellation-adapter tests. */
    static boolean probe(IsolatedReasonerSpec spec, Duration grace) {
        if (spec == null || grace == null || grace.isZero() || grace.isNegative()) {
            return false;
        }
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                OWLManager.createOWLOntologyManager();
        final OWLOntology ontology;
        try {
            ontology = manager.createOntology();
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException impossible) {
            return false;
        }
        ReasonerCancellationController cancellation =
                new ReasonerCancellationController();
        CountDownLatch constructed = new CountDownLatch(1);
        AtomicBoolean constructionSucceeded = new AtomicBoolean();
        FutureTask<Boolean> work = new FutureTask<>(() -> {
            OWLReasoner reasoner = null;
            try {
                reasoner = spec.create(ontology);
                constructionSucceeded.set(true);
                boolean mayCompute = cancellation.register(reasoner);
                constructed.countDown();
                if (!mayCompute) return false;
                reasoner.isConsistent();
                return true;
            } finally {
                cancellation.release(reasoner);
                cleanupAfterStop(cancellation, manager, grace);
            }
        });
        Thread computeThread = daemon(
                work, "protege-mcp-reasoner-cancellation-probe");
        long deadline = System.nanoTime() + grace.toNanos();
        computeThread.start();
        try {
            if (!awaitConstruction(constructed, work, deadline)) {
                cancellation.requestCancellation();
                work.cancel(true);
                return false;
            }
            if (!constructionSucceeded.get()) return false;
            cancellation.requestCancellation();
            work.cancel(true);
            joinUntil(computeThread, deadline);
            return !computeThread.isAlive()
                    && cancellation.stopCompleted()
                    && cancellation.awaitStopped(Duration.ZERO);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancellation.requestCancellation();
            work.cancel(true);
            return false;
        } finally {
            cancellation.requestCancellation();
            work.cancel(true);
        }
    }

    private static boolean awaitConstruction(
            CountDownLatch constructed, FutureTask<?> work, long deadline)
            throws InterruptedException {
        while (constructed.getCount() > 0 && !work.isDone()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            constructed.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)),
                    TimeUnit.NANOSECONDS);
        }
        return constructed.getCount() == 0;
    }

    private static void cleanupAfterStop(ReasonerCancellationController cancellation,
            org.semanticweb.owlapi.model.OWLOntologyManager manager, Duration grace) {
        Runnable cleanup = () -> {
            if (!cancellation.awaitStopped(grace)) return;
            for (OWLOntology loaded : new ArrayList<>(manager.getOntologies())) {
                manager.removeOntology(loaded);
            }
        };
        if (cleanupReady(cancellation)) {
            cleanup.run();
        } else {
            daemon(cleanup, "protege-mcp-reasoner-probe-cleanup").start();
        }
    }

    private static boolean cleanupReady(
            ReasonerCancellationController cancellation) {
        return cancellation.stopCompleted();
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void joinUntil(Thread thread, long deadline)
            throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
        thread.join(millis, nanos);
    }

}
