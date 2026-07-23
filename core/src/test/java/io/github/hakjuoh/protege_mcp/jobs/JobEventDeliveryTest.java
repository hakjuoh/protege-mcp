package io.github.hakjuoh.protege_mcp.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class JobEventDeliveryTest {
    private static final String WORKSPACE = "00000000-0000-4000-8000-000000000001";

    @Test
    void enqueueAfterEmptyPollRequestsRedrainWithoutBlockingCancellation() throws Exception {
        CountDownLatch emptyObserved = new CountDownLatch(1);
        CountDownLatch releaseDrainer = new CountDownLatch(1);
        AtomicBoolean firstEmpty = new AtomicBoolean(true);
        JobEventDelivery delivery = new JobEventDelivery(() -> {
            if (!firstEmpty.compareAndSet(true, false)) return;
            emptyObserved.countDown();
            try {
                assertTrue(releaseDrainer.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        List<Long> delivered = java.util.Collections.synchronizedList(new ArrayList<>());
        delivery.enqueue(event(1, JobEventKind.ACCEPTED), false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> activeDrain = executor.submit(
                    () -> delivery.drain(event -> delivered.add(event.sequence())));
            assertTrue(emptyObserved.await(5, TimeUnit.SECONDS));

            delivery.enqueue(event(2, JobEventKind.CANCEL_REQUESTED), false);
            long started = System.nanoTime();
            delivery.drainIfAvailable(event -> delivered.add(event.sequence()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
            assertTrue(elapsedMillis < 500, "non-blocking handoff waited for active audit");

            releaseDrainer.countDown();
            activeDrain.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1L, 2L), delivered);
        } finally {
            releaseDrainer.countDown();
            executor.shutdownNow();
        }
    }

    private static JobEvent event(long sequence, JobEventKind kind) {
        JobOwner owner = new JobOwner(WORKSPACE, digest("principal"),
                digest("client"), digest("grant"));
        return new JobEvent(sequence, kind, WORKSPACE, Instant.EPOCH.toString(), WORKSPACE,
                owner.ownerFingerprint(), owner.principalFingerprint(),
                owner.clientFingerprint(), owner.grantFingerprint(),
                JobType.CLASSIFICATION, JobState.QUEUED, digest("input"),
                Set.of("ontology:read"), "queued", 0,
                0, 0, 0, 0, 0, 0,
                false, false, false, null);
    }

    private static String digest(String value) {
        return JobHashes.digest(value);
    }
}
