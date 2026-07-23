package io.github.hakjuoh.protege_mcp.jobs;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes one job's audit callbacks and records bounded delivery state. */
final class JobEventDelivery {
    private final ArrayDeque<PendingEvent> pending = new ArrayDeque<>();
    private final ReentrantLock deliveryLock = new ReentrantLock(true);
    private final AtomicBoolean redrainRequested = new AtomicBoolean();
    private final Runnable queueDrainedObserver;
    private volatile boolean incomplete;
    private volatile long failedRequiredSequence;

    JobEventDelivery() {
        this(() -> { });
    }

    /** Deterministic seam for the enqueue-after-empty-poll race test. */
    JobEventDelivery(Runnable queueDrainedObserver) {
        this.queueDrainedObserver = Objects.requireNonNull(
                queueDrainedObserver, "queueDrainedObserver");
    }

    synchronized void enqueue(JobEvent event, boolean requiredBeforeCommit) {
        pending.add(new PendingEvent(
                Objects.requireNonNull(event, "event"), requiredBeforeCommit));
    }

    void drain(JobEventSink sink) {
        redrainRequested.set(true);
        drainLoop(sink, false);
    }

    /** Drain only when no callback is already in flight; cancellation must never wait on audit. */
    void drainIfAvailable(JobEventSink sink) {
        redrainRequested.set(true);
        drainLoop(sink, true);
    }

    boolean incomplete() {
        return incomplete;
    }

    boolean requiredDeliveryFailed(long sequence) {
        return failedRequiredSequence == sequence;
    }

    private synchronized PendingEvent poll() {
        return pending.poll();
    }

    private void drainLocked(JobEventSink sink) {
        while (true) {
            PendingEvent next = poll();
            if (next == null) {
                queueDrainedObserver.run();
                return;
            }
            try {
                sink.onEvent(next.event());
            } catch (RuntimeException deliveryFailure) {
                incomplete = true;
                if (next.requiredBeforeCommit()) {
                    failedRequiredSequence = next.event().sequence();
                }
            }
        }
    }

    private void drainLoop(JobEventSink sink, boolean nonBlocking) {
        while (true) {
            if (nonBlocking) {
                if (!deliveryLock.tryLock()) return;
            } else {
                deliveryLock.lock();
            }
            try {
                do {
                    redrainRequested.set(false);
                    drainLocked(sink);
                } while (redrainRequested.get() || hasPending());
            } finally {
                deliveryLock.unlock();
            }
            // A producer can request a drain after the final in-lock check but before unlock.
            // Reacquire in that case; if it arrives later it observes the free lock itself.
            if (!redrainRequested.get() && !hasPending()) return;
        }
    }

    private synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    private record PendingEvent(JobEvent event, boolean requiredBeforeCommit) { }
}
