package io.github.hakjuoh.protege_mcp.tools;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * The cancellation adapter shared by the live reasoner task and its admission probe.
 *
 * <p>A cancellation request never calls third-party code on the request thread. A reasoner that
 * finishes construction after cancellation is immediately claimed by the same asynchronous
 * interrupt/dispose path.</p>
 */
final class ReasonerCancellationController {
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean stopStarted = new AtomicBoolean();
    private final AtomicReference<OWLReasoner> live = new AtomicReference<>();
    private final CountDownLatch stopFinished = new CountDownLatch(1);

    /**
     * Publish a newly constructed reasoner. The return value is false when cancellation already
     * won and the caller must not begin computation.
     */
    boolean register(OWLReasoner reasoner) {
        if (reasoner == null || !live.compareAndSet(null, reasoner)) {
            throw new IllegalStateException("reasoner cancellation owner is already populated");
        }
        if (requested.get() && stopStarted.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> stopClaimed(reasoner),
                    "protege-mcp-reasoner-cancellation-late-registration");
            thread.setDaemon(true);
            thread.start();
        }
        return !requested.get();
    }

    /** Request cancellation without waiting for interrupt/dispose to return. */
    void requestCancellation() {
        requested.set(true);
        signal();
    }

    /** Stop a normally returning reasoner, unless the cancellation signal already owns it. */
    void release(OWLReasoner reasoner) {
        if (reasoner != null && live.compareAndSet(reasoner, null)) {
            if (stopStarted.compareAndSet(false, true)) stopClaimed(reasoner);
        }
    }

    boolean cancellationRequested() {
        return requested.get();
    }

    /** Wait only for a stop operation that actually claimed a constructed reasoner. */
    boolean awaitStopped(Duration timeout) {
        if (timeout == null || timeout.isNegative()) return false;
        if (!stopStarted.get()) return live.get() == null;
        try {
            return stopFinished.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Await a proven-bounded third-party stop while preserving the worker's interrupt status. */
    boolean awaitStoppedUninterruptibly(Duration timeout) {
        if (timeout == null || timeout.isNegative()) return false;
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining < 0) return stopCompleted();
                try {
                    return stopStarted.get()
                            ? stopFinished.await(remaining, TimeUnit.NANOSECONDS)
                            : live.get() == null;
                } catch (InterruptedException signal) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    boolean stopCompleted() {
        return stopStarted.get() && stopFinished.getCount() == 0;
    }

    private void signal() {
        OWLReasoner reasoner = live.getAndSet(null);
        if (reasoner == null) return;
        if (!stopStarted.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> stopClaimed(reasoner),
                "protege-mcp-reasoner-cancellation-signal");
        thread.setDaemon(true);
        thread.start();
    }

    private void stopClaimed(OWLReasoner reasoner) {
        try {
            try {
                reasoner.interrupt();
            } catch (RuntimeException | LinkageError ignored) {
                // Disposal is the second half of the reviewed cancellation adapter.
            }
            try {
                reasoner.dispose();
            } catch (RuntimeException | LinkageError ignored) {
                // The probe fails if the stop path does not return within its bound.
            }
        } finally {
            stopFinished.countDown();
        }
    }
}
