package io.github.hakjuoh.protege_mcp.jobs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

class JobServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        for (ExecutorService executor : executors) executor.shutdownNow();
    }

    @Test
    void succeedsWithMonotonicProgressAndPrivateVerifiedArtifact() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(clock, scheduler, JobRuntimeConfig.defaults());
        JobOwner owner = owner("one");
        JobStartResult accepted = jobs.start(submission(owner, "key-one", identity(owner),
                execution -> {
                    execution.progress("classifying", "Classification is running.");
                    JobArtifact.Reference artifact = execution.stageArtifact(
                            "application/json", "evidence".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    return new JobTaskOutput(JobResultType.CLASSIFICATION,
                            Map.of("consistent", true, "artifact_id", artifact.artifactId()), false);
                }));

        assertEquals(JobState.QUEUED, accepted.job().state());
        assertFalse(accepted.reused());
        scheduler.runNext();
        JobDescriptor completed = jobs.get(owner, accepted.job().jobId());

        assertEquals(JobState.SUCCEEDED, completed.state());
        assertEquals("succeeded", completed.phase());
        assertTrue(completed.progressSequence() >= 3);
        assertEquals(JobResultType.CLASSIFICATION, completed.result().discriminator());
        assertEquals(1, completed.artifacts().size());
        JobArtifact stored = jobs.requireArtifact(owner, completed.jobId(),
                completed.artifacts().get(0).artifactId());
        jobs.close();
        assertArrayEquals("evidence".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                stored.copyBytes());
        assertEquals(stored.sha256(), JobHashes.digest(stored.copyBytes()));
    }

    @Test
    void idempotencyReturnsExistingJobAndConflictsOnDifferentIdentityUntilWindowExpires() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(clock, scheduler, JobRuntimeConfig.defaults());
        JobOwner owner = owner("idem");
        JobSubmission first = submission(owner, "stable-key", identity(owner), success());
        JobStartResult accepted = jobs.start(first);
        JobStartResult reused = jobs.start(first);
        assertTrue(reused.reused());
        assertEquals(accepted.job().jobId(), reused.job().jobId());

        JobException conflict = assertThrows(JobException.class,
                () -> jobs.start(submission(owner, "stable-key",
                        identity(owner, "different"), success())));
        assertEquals("idempotency_conflict", conflict.error().code());

        clock.advance(Duration.ofMinutes(15));
        JobStartResult replacement = jobs.start(submission(owner, "stable-key",
                identity(owner, "different"), success()));
        assertFalse(replacement.reused());
        assertFalse(replacement.job().jobId().equals(accepted.job().jobId()));
    }

    @Test
    void policyConfigurationCanTightenSubsequentAdmission() {
        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(new MutableClock(NOW), scheduler,
                JobRuntimeConfig.defaults());
        jobs.updateConfig(new JobRuntimeConfig(
                Set.of(JobType.SEMANTIC_DIFF), 4, 2, 4, 8,
                Duration.ofMinutes(10)));

        JobOwner owner = owner("policy");
        JobException denied = assertThrows(JobException.class,
                () -> jobs.start(submission(owner, "classification",
                        identity(owner), success())));
        assertEquals("job_type_disabled", denied.error().code());
        jobs.close();
        assertThrows(JobException.class,
                () -> jobs.updateConfig(JobRuntimeConfig.defaults()));
    }

    @Test
    void preCaptureReservationBindsPolicyAndCannotBeStolenByDirectAdmission() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobRuntimeConfig reservedConfig = new JobRuntimeConfig(
                Set.of(JobType.CLASSIFICATION), 1, 1, 1, 1,
                Duration.ofSeconds(60));
        JobService jobs = service(clock, scheduler, JobRuntimeConfig.defaults());
        JobOwner owner = owner("reserved-policy");
        JobAdmission admission = jobs.reserve(
                owner, JobType.CLASSIFICATION, "reserved-policy", reservedConfig);

        JobException stolen = assertThrows(JobException.class,
                () -> jobs.start(submission(owner, "direct",
                        identity(owner, "direct"), success())));
        assertEquals("job_capture_capacity_exceeded", stolen.error().code());
        jobs.updateConfig(new JobRuntimeConfig(Set.of(JobType.SEMANTIC_DIFF),
                32, 8, 32, 128, Duration.ofHours(1)));

        JobDescriptor accepted = jobs.start(
                submission(owner, "reserved-policy", identity(owner), execution -> {
                    execution.stageArtifact("application/json", new byte[] {1});
                    return output();
                }),
                admission).job();
        assertEquals(JobState.QUEUED, accepted.state());
        scheduler.runNext();
        JobDescriptor completed = jobs.get(owner, accepted.jobId());
        String artifactId = completed.artifacts().get(0).artifactId();
        clock.advance(Duration.ofSeconds(61));
        JobException expired = assertThrows(JobException.class,
                () -> jobs.requireArtifact(owner, accepted.jobId(), artifactId));
        assertEquals("unknown_job_artifact", expired.error().code());
    }

    @Test
    void closingReservationReleasesQuotaBeforeAnyTaskExists() {
        JobRuntimeConfig one = new JobRuntimeConfig(Set.of(JobType.CLASSIFICATION),
                1, 1, 1, 1, Duration.ofMinutes(1));
        JobService jobs = service(new MutableClock(NOW), new ManualScheduler(), one);
        JobOwner owner = owner("reservation-release");
        JobAdmission first = jobs.reserve(
                owner, JobType.CLASSIFICATION, "first", one);

        JobException full = assertThrows(JobException.class,
                () -> jobs.reserve(owner, JobType.CLASSIFICATION, "second", one));
        assertEquals("job_capture_capacity_exceeded", full.error().code());

        first.close();
        try (JobAdmission second = jobs.reserve(
                owner, JobType.CLASSIFICATION, "second", one)) {
            assertNotNull(second);
        }
    }

    @Test
    void rejectedCaptureCleanupRunsAfterServiceMonitorIsReleased() {
        JobRuntimeConfig semanticOnly = new JobRuntimeConfig(
                Set.of(JobType.SEMANTIC_DIFF), 32, 8, 32, 128,
                Duration.ofHours(1));
        JobService jobs = service(
                new MutableClock(NOW), new ManualScheduler(), semanticOnly);
        AtomicBoolean heldServiceMonitor = new AtomicBoolean();
        JobTask capture = new JobTask() {
            @Override
            public JobTaskOutput execute(JobExecution execution) {
                return output();
            }

            @Override
            public void discard() {
                heldServiceMonitor.set(Thread.holdsLock(jobs));
            }
        };

        assertThrows(JobException.class, () -> jobs.start(submission(
                owner("cleanup-monitor"), "disabled", identity(
                        owner("cleanup-monitor")), capture)));

        assertFalse(heldServiceMonitor.get());
    }

    @Test
    void tasksThatNeverExecuteReleaseTheirCapturedInputsExactlyOnce() {
        JobOwner owner = owner("discard");
        AtomicInteger policyDiscarded = new AtomicInteger();
        JobService policyJobs = service(new MutableClock(NOW), new ManualScheduler(),
                new JobRuntimeConfig(Set.of(JobType.SEMANTIC_DIFF), 32, 8, 32, 128,
                        Duration.ofHours(1)));
        assertThrows(JobException.class, () -> policyJobs.start(submission(
                owner, "disabled", identity(owner), retained(policyDiscarded))));
        assertEquals(1, policyDiscarded.get());

        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(new MutableClock(NOW), scheduler,
                JobRuntimeConfig.defaults());
        AtomicInteger originalDiscarded = new AtomicInteger();
        JobStartResult accepted = jobs.start(submission(owner, "stable",
                identity(owner), retained(originalDiscarded)));
        AtomicInteger duplicateDiscarded = new AtomicInteger();
        JobStartResult reused = jobs.start(submission(owner, "stable",
                identity(owner), retained(duplicateDiscarded)));
        assertTrue(reused.reused());
        assertEquals(1, duplicateDiscarded.get());
        assertEquals(0, originalDiscarded.get());

        jobs.cancel(owner, accepted.job().jobId());
        assertEquals(1, originalDiscarded.get());
        jobs.cancel(owner, accepted.job().jobId());
        jobs.close();
        assertEquals(1, originalDiscarded.get());
    }

    @Test
    void schedulerRejectionDiscardsCapturedInputs() {
        AtomicInteger discarded = new AtomicInteger();
        JobOwner owner = owner("discard-rejected");
        JobService jobs = service(new MutableClock(NOW), task -> {
            throw new RejectedExecutionException("full");
        }, JobRuntimeConfig.defaults());

        JobDescriptor failed = jobs.start(submission(owner, "discard-rejected",
                identity(owner), retained(discarded))).job();

        assertEquals(JobState.FAILED, failed.state());
        assertEquals(1, discarded.get());
    }

    @Test
    void queueRejectionIsTerminalAndNeverLeftQueued() {
        MutableClock clock = new MutableClock(NOW);
        JobService jobs = service(clock, task -> {
            throw new RejectedExecutionException("full");
        }, JobRuntimeConfig.defaults());
        JobDescriptor failed = jobs.start(submission(owner("queue"), "queue-key",
                identity(owner("queue")), success())).job();

        assertEquals(JobState.FAILED, failed.state());
        assertEquals("job_queue_full", failed.error().code());
        assertNotNull(failed.completedAt());
    }

    @Test
    void invalidAndFailingSchedulersProduceTypedTerminalFailures() {
        JobOwner owner = owner("scheduler-failures");
        JobService missingHandle = service(new MutableClock(NOW), task -> null,
                JobRuntimeConfig.defaults());
        JobDescriptor rejected = missingHandle.start(submission(owner, "missing-handle",
                identity(owner), success())).job();
        assertEquals(JobState.FAILED, rejected.state());
        assertEquals("job_queue_full", rejected.error().code());

        JobService broken = service(new MutableClock(NOW), task -> {
            throw new IllegalStateException("scheduler unavailable");
        }, JobRuntimeConfig.defaults());
        JobDescriptor failed = broken.start(submission(owner, "scheduler-failed",
                identity(owner, "scheduler-failed"), success())).job();
        assertEquals(JobState.FAILED, failed.state());
        assertEquals("job_scheduler_failed", failed.error().code());
    }

    @Test
    void reasonerJobsRequireExactDigestAndProvenBoundedCancellation() {
        JobOwner owner = owner("reasoner-gate");
        JobService jobs = service(new MutableClock(NOW), new ManualScheduler(),
                JobRuntimeConfig.defaults());
        JobSubmission unproven = new JobSubmission(owner,
                JobType.CLASSIFICATION, "unproven", identity(owner), Set.of("ontology:read"),
                false, JobPreCommitGuard.noOp(), success());

        JobException error = assertThrows(JobException.class, () -> jobs.start(unproven));
        assertEquals("job_reasoner_not_cancellable", error.error().code());
        JobInputIdentity missingReasoner = new JobInputIdentity(
                identity(owner).modelRevision(), digest("closure"), null, null, digest("policy"),
                digest("assets"), null, digest("request-no-reasoner"), List.of());
        JobException missing = assertThrows(JobException.class,
                () -> jobs.start(new JobSubmission(owner, JobType.CLASSIFICATION,
                        "missing-reasoner", missingReasoner, Set.of("ontology:read"),
                        true, JobPreCommitGuard.noOp(), success())));
        assertEquals("job_reasoner_not_cancellable", missing.error().code());
    }

    @Test
    void nonReasoningJobsDoNotRequireAReasonerProfile() {
        JobOwner owner = owner("non-reasoning");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());
        JobInputIdentity withoutReasoner = new JobInputIdentity(
                identity(owner).modelRevision(), digest("closure"), null, null, digest("policy"),
                digest("assets"), null, digest("request-no-reasoner"), List.of());
        JobSubmission submission = new JobSubmission(owner,
                JobType.SEMANTIC_DIFF, "non-reasoning-key", withoutReasoner,
                Set.of("ontology:read"), false, JobPreCommitGuard.noOp(),
                execution -> new JobTaskOutput(
                        JobResultType.SEMANTIC_DIFF, Map.of("changed", false), false));

        JobDescriptor completed = jobs.start(submission).job();

        assertEquals(JobState.SUCCEEDED, completed.state());
        assertEquals(JobResultType.SEMANTIC_DIFF, completed.result().discriminator());
    }

    @Test
    void everyClosedJobTypePublishesOnlyItsBoundResultType() {
        JobOwner owner = owner("closed-job-types");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());

        for (JobType type : JobType.values()) {
            JobDescriptor completed = jobs.start(new JobSubmission(owner, type,
                    "type-" + type.id(), identity(owner), Set.of("ontology:read"), true,
                    JobPreCommitGuard.noOp(),
                    execution -> new JobTaskOutput(type.resultType(),
                            Map.of("type", type.id()), false))).job();

            assertEquals(JobState.SUCCEEDED, completed.state());
            assertEquals(type.resultType(), completed.resultDiscriminator());
            assertEquals(type.resultType(), completed.result().discriminator());
        }
    }

    @Test
    void jobTypeAndWorkerResultCannotContradictEachOther() {
        JobOwner owner = owner("result-type-mismatch");
        JobInputIdentity withoutReasoner = new JobInputIdentity(
                identity(owner).modelRevision(), digest("closure"), null, null, digest("policy"),
                digest("assets"), null, digest("request-result-mismatch"), List.of());
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());

        JobDescriptor failed = jobs.start(new JobSubmission(owner, JobType.SEMANTIC_DIFF,
                "result-type-mismatch", withoutReasoner, Set.of("ontology:read"), false,
                JobPreCommitGuard.noOp(),
                execution -> new JobTaskOutput(
                        JobResultType.CLASSIFICATION, Map.of(), false))).job();

        assertEquals(JobState.FAILED, failed.state());
        assertEquals("job_result_invalid", failed.error().code());
        assertNull(failed.result());
    }

    @Test
    void activeQuotaSpansGrantsAndWorkspacesForOnePrincipal() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobRuntimeConfig config = new JobRuntimeConfig(Set.of(JobType.values()),
                4, 1, 8, 16, Duration.ofHours(1));
        JobService jobs = service(clock, scheduler, config);
        JobOwner first = owner("00000000-0000-4000-8000-000000000001", "grant-a");
        JobOwner second = owner("00000000-0000-4000-8000-000000000002", "grant-b");
        jobs.start(submission(first, "first", identity(first), success()));

        JobException quota = assertThrows(JobException.class,
                () -> jobs.start(submission(second, "second", identity(second), success())));
        assertEquals("job_active_quota_exceeded", quota.error().code());
    }

    @Test
    void configuredQueueCapacityCreatesTypedFailedRecord() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobRuntimeConfig config = new JobRuntimeConfig(Set.of(JobType.values()),
                1, 8, 32, 128, Duration.ofHours(1));
        JobService jobs = service(clock, scheduler, config);
        JobOwner owner = owner("capacity");
        JobDescriptor first = jobs.start(submission(owner, "first", identity(owner), success())).job();
        JobDescriptor second = jobs.start(submission(owner, "second",
                identity(owner, "second"), success())).job();

        assertEquals(JobState.QUEUED, first.state());
        assertEquals(JobState.FAILED, second.state());
        assertEquals("job_queue_full", second.error().code());
    }

    @Test
    void queuedCancellationIsImmediateIdempotentAndWorkerNeverRuns() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger calls = new AtomicInteger();
        JobService jobs = service(new MutableClock(NOW), scheduler, JobRuntimeConfig.defaults());
        JobOwner owner = owner("queued-cancel");
        JobDescriptor queued = jobs.start(submission(owner, "cancel-key", identity(owner),
                execution -> {
                    calls.incrementAndGet();
                    return output();
                })).job();

        JobCancelResult cancelled = jobs.cancel(owner, queued.jobId());
        JobCancelResult repeated = jobs.cancel(owner, queued.jobId());
        scheduler.runNext();

        assertEquals(JobCancelOutcome.CANCELLED, cancelled.outcome());
        assertEquals(JobCancelOutcome.ALREADY_TERMINAL, repeated.outcome());
        assertEquals(JobState.CANCELLED, jobs.get(owner, queued.jobId()).state());
        assertEquals(0, calls.get());
    }

    @Test
    void schedulerInterruptFailureCannotUndoCancellationOrBreakShutdown() {
        JobScheduler scheduler = task -> interrupt -> {
            throw new IllegalStateException("scheduler is already stopping");
        };
        MutableClock clock = new MutableClock(NOW);
        JobOwner owner = owner("interrupt-failure");
        JobService jobs = service(clock, scheduler, JobRuntimeConfig.defaults());
        JobDescriptor queued = jobs.start(submission(
                owner, "interrupt-key", identity(owner), success())).job();

        JobCancelResult cancelled = jobs.cancel(owner, queued.jobId());

        assertEquals(JobCancelOutcome.CANCELLED, cancelled.outcome());
        assertEquals(JobState.CANCELLED, jobs.get(owner, queued.jobId()).state());

        JobService closing = service(clock, scheduler, JobRuntimeConfig.defaults());
        closing.start(submission(owner, "close-interrupt-key",
                identity(owner, "close-interrupt"), success()));
        closing.close();
        assertTrue(closing.isClosed());
    }

    @Test
    void runningCancellationRemainsPendingUntilLateOutputIsDiscarded() throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("running-cancel");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobDescriptor job = jobs.start(submission(owner, "running-key", identity(owner),
                execution -> {
                    started.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // Simulate a third-party reasoner that does not stop immediately.
                        }
                    }
                    execution.stageArtifact("application/json", new byte[] {1});
                    return output();
                })).job();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        JobCancelResult requested = jobs.cancel(owner, job.jobId());
        assertEquals(JobCancelOutcome.CANCEL_REQUESTED, requested.outcome());
        assertEquals(JobState.CANCEL_PENDING, jobs.get(owner, job.jobId()).state());
        release.countDown();
        awaitState(jobs, owner, job.jobId(), JobState.CANCELLED);

        JobDescriptor cancelled = jobs.get(owner, job.jobId());
        assertTrue(cancelled.cancellationEffective());
        assertTrue(cancelled.artifacts().isEmpty());
    }

    @Test
    void cancellationAfterCommitPermitReportsCommitInProgressAndCannotRewriteSuccess()
            throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("commit");
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch permitReturned = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicInteger mutations = new AtomicInteger();
        JobDescriptor job = jobs.start(submission(owner, "commit-key", identity(owner),
                execution -> {
                    JobTaskOutput prepared = output();
                    execution.withCommitPermit(prepared, JobPreCommitGuard.noOp(), () -> {
                        mutations.incrementAndGet();
                        commitEntered.countDown();
                        releaseCommit.await();
                        return prepared;
                    });
                    permitReturned.countDown();
                    releaseTask.await();
                    return prepared;
                })).job();
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS));

        long beforeCancel = System.nanoTime();
        JobCancelResult cancel = jobs.cancel(owner, job.jobId());
        long cancelMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeCancel);
        assertEquals(JobCancelOutcome.COMMIT_IN_PROGRESS, cancel.outcome());
        assertTrue(cancelMillis < 500, "cancel must not wait for commit work");
        assertFalse(cancel.job().cancellationRequested());
        releaseCommit.countDown();
        assertTrue(permitReturned.await(5, TimeUnit.SECONDS));
        awaitState(jobs, owner, job.jobId(), JobState.SUCCEEDED);
        assertEquals(JobCancelOutcome.ALREADY_TERMINAL,
                jobs.cancel(owner, job.jobId()).outcome());
        releaseTask.countDown();
        assertEquals(1, mutations.get());
        assertEquals(JobState.SUCCEEDED, jobs.cancel(owner, job.jobId()).job().state());
    }

    @Test
    void exceptionAfterCompletedCommitCannotRewriteThePreparedSuccess() {
        AtomicInteger mutations = new AtomicInteger();
        JobOwner owner = owner("post-commit");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());

        JobDescriptor completed = jobs.start(submission(owner, "post-commit-key", identity(owner),
                execution -> {
                    JobTaskOutput prepared = output();
                    execution.withCommitPermit(prepared, JobPreCommitGuard.noOp(), () -> {
                        mutations.incrementAndGet();
                        return prepared;
                    });
                    throw new java.io.IOException("late adapter failure");
                })).job();

        assertEquals(1, mutations.get());
        assertEquals(JobState.SUCCEEDED, completed.state());
        assertEquals(Boolean.TRUE, completed.result().structured().get("consistent"));
        assertNull(completed.error());
    }

    @Test
    void commitActionCanPublishTerminalAuditFailureAsSuccessfulIncompleteAudit() {
        JobOwner owner = owner("audit-incomplete");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());
        JobDescriptor completed = jobs.start(submission(owner, "audit-key", identity(owner),
                execution -> {
                    JobTaskOutput prepared = output();
                    return execution.withCommitPermit(prepared, JobPreCommitGuard.noOp(),
                            () -> prepared.withAuditIncomplete());
                })).job();

        assertEquals(JobState.SUCCEEDED, completed.state());
        assertTrue(completed.result().auditIncomplete());
    }

    @Test
    void failedPreCommitAuditIntentPreventsMutation() {
        JobOwner owner = owner("audit-intent-failure");
        AtomicInteger mutations = new AtomicInteger();
        JobEventSink sink = event -> {
            if (event.kind() == JobEventKind.PUBLICATION_STARTED) {
                throw new IllegalStateException("audit unavailable");
            }
        };
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), sink);

        JobDescriptor failed = jobs.start(submission(owner, "audit-intent-key", identity(owner),
                execution -> execution.withCommitPermit(
                        output(), JobPreCommitGuard.noOp(), () -> {
                    mutations.incrementAndGet();
                    return output();
                }))).job();

        assertEquals(JobState.FAILED, failed.state());
        assertEquals("job_audit_intent_failed", failed.error().code());
        assertFalse(failed.commitStarted());
        assertEquals(0, mutations.get());
    }

    @Test
    void grantRevocationDuringAuditIntentWinsBeforeCommitCas() throws Exception {
        JobOwner owner = owner("grant-revocation-audit");
        ExecutorService executor = executor();
        CountDownLatch auditIntentEntered = new CountDownLatch(1);
        CountDownLatch releaseAuditIntent = new CountDownLatch(1);
        AtomicInteger mutations = new AtomicInteger();
        JobEventSink sink = event -> {
            if (event.kind() != JobEventKind.PUBLICATION_STARTED) return;
            auditIntentEntered.countDown();
            while (releaseAuditIntent.getCount() > 0) {
                try {
                    releaseAuditIntent.await();
                } catch (InterruptedException ignored) {
                    // Revocation interruption is only acceleration; the tombstone is authoritative.
                }
            }
        };
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), sink);
        JobDescriptor accepted = jobs.start(submission(owner, "grant-revocation-audit-key",
                identity(owner), execution -> execution.withCommitPermit(
                        output(), JobPreCommitGuard.noOp(), () -> {
                            mutations.incrementAndGet();
                            return output();
                        }))).job();
        assertTrue(auditIntentEntered.await(5, TimeUnit.SECONDS));

        assertEquals(1, jobs.revokeGrant(
                owner.clientFingerprint(), owner.grantFingerprint()));
        releaseAuditIntent.countDown();
        awaitState(jobs, owner, accepted.jobId(), JobState.CANCELLED);

        JobDescriptor cancelled = jobs.get(owner, accepted.jobId());
        assertFalse(cancelled.commitStarted());
        assertTrue(cancelled.cancellationEffective());
        assertEquals(0, mutations.get());
    }

    @Test
    void failedTerminalAuditAfterCommitKeepsSuccessfulResult() {
        JobOwner owner = owner("terminal-audit-failure");
        AtomicInteger mutations = new AtomicInteger();
        JobEventSink sink = event -> {
            if (event.kind() == JobEventKind.TERMINAL) {
                throw new IllegalStateException("audit unavailable");
            }
        };
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), sink);

        JobDescriptor completed = jobs.start(submission(owner, "terminal-audit-key",
                identity(owner), execution -> execution.withCommitPermit(
                        output(), JobPreCommitGuard.noOp(), () -> {
                            mutations.incrementAndGet();
                            return output();
                        }))).job();

        assertEquals(JobState.SUCCEEDED, completed.state());
        assertTrue(completed.result().auditIncomplete());
        assertEquals(1, mutations.get());
    }

    @Test
    void terminalAuditDispositionIsFinalBeforeSuccessBecomesObservable() throws Exception {
        JobOwner owner = owner("terminal-audit-barrier");
        ExecutorService executor = executor();
        CountDownLatch terminalDelivery = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        JobEventSink sink = event -> {
            if (event.kind() != JobEventKind.TERMINAL) return;
            terminalDelivery.countDown();
            try {
                if (!releaseDelivery.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("terminal audit barrier timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("audit unavailable");
        };
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), sink);

        JobDescriptor accepted = jobs.start(submission(owner, "terminal-audit-barrier-key",
                identity(owner), execution -> execution.withCommitPermit(
                        output(), JobPreCommitGuard.noOp(), JobServiceTest::output))).job();
        assertTrue(terminalDelivery.await(5, TimeUnit.SECONDS));

        JobDescriptor duringAudit = jobs.get(owner, accepted.jobId());
        assertEquals(JobState.RUNNING, duringAudit.state());
        assertNull(duringAudit.result());
        assertTrue(duringAudit.commitStarted());

        releaseDelivery.countDown();
        awaitState(jobs, owner, accepted.jobId(), JobState.SUCCEEDED);
        JobDescriptor completed = jobs.get(owner, accepted.jobId());
        assertTrue(completed.result().auditIncomplete());
        assertEquals(completed, jobs.get(owner, accepted.jobId()));
    }

    @Test
    void commitProtocolFreezesNearLimitArtifactEnvelopeBeforeMutation() throws Exception {
        String createdAt = NOW.toString();
        String expiresAt = Instant.MAX.toString();
        JobArtifact.Reference first = new JobArtifact.Reference(
                "00000000-0000-4000-8000-000000000002", "application/octet-stream",
                digest("artifact-one"), 1, createdAt, expiresAt);
        JobArtifact.Reference second = new JobArtifact.Reference(
                "00000000-0000-4000-8000-000000000003", "application/octet-stream",
                digest("artifact-two"), 1, createdAt, expiresAt);
        int oneArtifactBytes = io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                .writeValueAsBytes(new JobResult(JobResultType.CLASSIFICATION,
                        Map.of("consistent", true), List.of(first), false)).length;
        int twoArtifactBytes = io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                .writeValueAsBytes(new JobResult(JobResultType.CLASSIFICATION,
                        Map.of("consistent", true), List.of(first, second), false)).length;
        assertTrue(twoArtifactBytes > oneArtifactBytes);
        JobArtifactLimits limits = new JobArtifactLimits(
                4, 8, 16, 32, oneArtifactBytes, 1_024);
        AtomicReference<JobException> rejectedStage = new AtomicReference<>();
        AtomicInteger mutations = new AtomicInteger();
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), limits);
        JobOwner owner = owner("frozen-artifact-envelope");

        JobDescriptor completed = jobs.start(submission(owner, "frozen-artifact-key",
                identity(owner), execution -> {
                    execution.stageArtifact("application/octet-stream", new byte[] {1});
                    JobTaskOutput prepared = output();
                    return execution.withCommitPermit(
                            prepared, JobPreCommitGuard.noOp(), () -> {
                        rejectedStage.set(assertThrows(JobException.class,
                                () -> execution.stageArtifact(
                                        "application/octet-stream", new byte[] {2})));
                        mutations.incrementAndGet();
                        return prepared;
                    });
                })).job();

        assertEquals("job_publication_started", rejectedStage.get().error().code());
        assertEquals(1, mutations.get());
        assertEquals(JobState.SUCCEEDED, completed.state());
        assertEquals(1, completed.artifacts().size());
        assertTrue(io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                .writeValueAsBytes(completed.result()).length <= oneArtifactBytes);
    }

    @Test
    void concurrentStagingBeforeTheCommitFenceIsRevalidatedBeforeMutation() throws Exception {
        int noArtifactBytes = io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                .writeValueAsBytes(new JobResult(JobResultType.CLASSIFICATION,
                        Map.of("consistent", true), List.of(), false)).length;
        JobArtifactLimits limits = new JobArtifactLimits(
                4, 8, 16, 32, noArtifactBytes, 1_024);
        ExecutorService executor = executor();
        MutableClock clock = new MutableClock(NOW);
        JobService jobs = service(clock, scheduler(executor),
                JobRuntimeConfig.defaults(), limits);
        JobOwner owner = owner("concurrent-stage");
        AtomicReference<JobExecution> captured = new AtomicReference<>();
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        AtomicInteger mutations = new AtomicInteger();

        JobDescriptor accepted = jobs.start(submission(owner, "concurrent-stage-key",
                identity(owner), execution -> {
                    captured.set(execution);
                    return execution.withCommitPermit(output(), () -> {
                        guardEntered.countDown();
                        if (!releaseGuard.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("concurrent stage timed out");
                        }
                        return JobCommitLease.noOp();
                    }, () -> {
                        mutations.incrementAndGet();
                        return output();
                    });
                })).job();
        assertTrue(guardEntered.await(5, TimeUnit.SECONDS));

        captured.get().stageArtifact("application/octet-stream", new byte[] {1});
        releaseGuard.countDown();
        awaitState(jobs, owner, accepted.jobId(), JobState.FAILED);

        JobDescriptor failed = jobs.get(owner, accepted.jobId());
        assertEquals("job_result_too_large", failed.error().code());
        assertEquals(0, mutations.get());
        assertEquals(0, jobs.retainedArtifactBytes());
    }

    @Test
    void renewedFractionalExpiryIsSizedBeforeTerminalSuccessAudit() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        String createdAt = NOW.toString();
        JobArtifact.Reference maximumExpiry = new JobArtifact.Reference(
                "00000000-0000-4000-8000-000000000002", "application/octet-stream",
                digest("artifact"), 1, createdAt, Instant.MAX.toString());
        int boundedBytes = io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                .writeValueAsBytes(new JobResult(JobResultType.CLASSIFICATION,
                        Map.of("consistent", true), List.of(maximumExpiry), false)).length;
        JobArtifactLimits limits = new JobArtifactLimits(
                4, 8, 16, 32, boundedBytes, 1_024);
        List<JobEvent> events = new ArrayList<>();
        JobOwner owner = owner("fractional-expiry");
        JobService jobs = service(clock, inlineScheduler(), JobRuntimeConfig.defaults(),
                limits, events::add);

        JobDescriptor completed = jobs.start(submission(owner, "fractional-expiry-key",
                identity(owner), () -> {
                    clock.advance(Duration.ofNanos(1));
                    return JobCommitLease.noOp();
                }, execution -> {
                    execution.stageArtifact("application/octet-stream", new byte[] {1});
                    return output();
                })).job();

        assertEquals(JobState.SUCCEEDED, completed.state());
        assertEquals(1, completed.artifacts().size());
        assertTrue(completed.artifacts().get(0).expiresAt().contains(".000000001Z"));
        assertTrue(io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                .writeValueAsBytes(completed.result()).length <= boundedBytes);
        assertEquals(JobState.SUCCEEDED, events.get(events.size() - 1).state());
    }

    @Test
    void auditStreamsAreOrderedMonotonicAndSecretFreeForEveryTerminalOutcome()
            throws Exception {
        String secret = "patient-private-query-and-token";

        List<JobEvent> successEvents = new ArrayList<>();
        JobOwner successOwner = owner("audit-success-owner");
        JobDescriptor succeeded = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(),
                successEvents::add)
                .start(submission(successOwner, "audit-success-key", identity(successOwner),
                        execution -> {
                            execution.progress("private_work", secret);
                            return new JobTaskOutput(JobResultType.CLASSIFICATION,
                                    Map.of("evidence", secret), false);
                        })).job();
        assertEquals(List.of(JobEventKind.ACCEPTED, JobEventKind.STARTED,
                JobEventKind.PROGRESS, JobEventKind.PUBLICATION_STARTED, JobEventKind.TERMINAL),
                successEvents.stream().map(JobEvent::kind).toList());
        assertEquals(JobState.SUCCEEDED, successEvents.get(successEvents.size() - 1).state());

        List<JobEvent> cancellationEvents = new ArrayList<>();
        ManualScheduler cancellationScheduler = new ManualScheduler();
        JobOwner cancellationOwner = owner("audit-cancel-owner");
        JobService cancellationJobs = service(new MutableClock(NOW), cancellationScheduler,
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(),
                cancellationEvents::add);
        JobDescriptor queued = cancellationJobs.start(submission(cancellationOwner,
                "audit-cancel-key", identity(cancellationOwner), success())).job();
        cancellationJobs.cancel(cancellationOwner, queued.jobId());
        assertEquals(List.of(JobEventKind.ACCEPTED, JobEventKind.CANCEL_REQUESTED,
                JobEventKind.CANCELLATION_EFFECTIVE, JobEventKind.TERMINAL),
                cancellationEvents.stream().map(JobEvent::kind).toList());
        assertEquals(JobState.CANCELLED,
                cancellationEvents.get(cancellationEvents.size() - 1).state());

        List<JobEvent> failureEvents = new ArrayList<>();
        JobOwner failureOwner = owner("audit-failure-owner");
        JobDescriptor failed = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(),
                failureEvents::add)
                .start(submission(failureOwner, "audit-failure-key", identity(failureOwner),
                        execution -> {
                            throw new IllegalStateException(secret);
                        })).job();
        assertEquals(JobState.FAILED, failed.state());
        assertEquals(List.of(JobEventKind.ACCEPTED, JobEventKind.STARTED,
                JobEventKind.TERMINAL),
                failureEvents.stream().map(JobEvent::kind).toList());
        assertEquals(JobState.FAILED, failureEvents.get(failureEvents.size() - 1).state());

        for (List<JobEvent> stream : List.of(
                successEvents, cancellationEvents, failureEvents)) {
            String jobId = stream.get(0).jobId();
            for (int index = 0; index < stream.size(); index++) {
                assertEquals(index + 1, stream.get(index).sequence());
                assertEquals(jobId, stream.get(index).jobId());
            }
            String json = io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                    .writeValueAsString(stream);
            assertFalse(json.contains(secret));
            assertFalse(json.contains("audit-success-owner"));
            assertFalse(json.contains("audit-cancel-owner"));
            assertFalse(json.contains("audit-failure-owner"));
        }
        assertEquals(JobState.SUCCEEDED, succeeded.state());
    }

    @Test
    void commitExceptionIsNonRetryableUnknownOutcomeAndIdempotent() {
        JobOwner owner = owner("commit-failure");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());
        JobSubmission submission = submission(owner, "commit-failure-key", identity(owner),
                execution -> {
                    execution.withCommitPermit(
                            output(), JobPreCommitGuard.noOp(), () -> {
                        throw new java.io.IOException("ambiguous commit");
                    });
                    return output();
                });

        JobStartResult failed = jobs.start(submission);
        assertEquals(JobState.FAILED, failed.job().state());
        assertEquals("job_commit_outcome_unknown", failed.job().error().code());
        assertFalse(failed.job().error().retryable());
        assertEquals(Boolean.TRUE,
                failed.job().error().details().get("retry_requires_state_check"));
        JobStartResult replay = jobs.start(submission);
        assertTrue(replay.reused());
        assertEquals(failed.job().jobId(), replay.job().jobId());
    }

    @Test
    void caughtCommitExceptionCannotBeReclassifiedAsCancellation() {
        JobOwner owner = owner("caught-commit-failure");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());

        JobDescriptor failed = jobs.start(submission(owner, "caught-commit-failure-key",
                identity(owner), execution -> {
                    try {
                        execution.withCommitPermit(
                                output(), JobPreCommitGuard.noOp(), () -> {
                            throw new java.io.IOException("ambiguous commit");
                        });
                    } catch (JobException expected) {
                        assertEquals("job_commit_outcome_unknown", expected.error().code());
                    }
                    return output();
                })).job();

        assertEquals(JobState.FAILED, failed.state());
        assertEquals("job_commit_outcome_unknown", failed.error().code());
        assertFalse(failed.cancellationRequested());
        assertFalse(failed.cancellationEffective());
    }

    @Test
    void fatalCommitErrorAlsoRequiresAStateCheck() throws Exception {
        JobOwner owner = owner("commit-error");
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobDescriptor accepted = jobs.start(submission(owner, "commit-error-key", identity(owner),
                execution -> {
                    execution.withCommitPermit(
                            output(), JobPreCommitGuard.noOp(), () -> {
                        throw new AssertionError("fatal after permit");
                    });
                    return output();
                })).job();

        awaitState(jobs, owner, accepted.jobId(), JobState.FAILED);
        JobDescriptor failed = jobs.get(owner, accepted.jobId());
        assertEquals(JobState.FAILED, failed.state());
        assertEquals("job_commit_outcome_unknown", failed.error().code());
        assertEquals(Boolean.TRUE, failed.error().details().get("retry_requires_state_check"));
    }

    @Test
    void cancellationTombstoneIsVisibleWithoutThreadInterrupt() throws Exception {
        ExecutorService executor = executor();
        JobScheduler nonInterrupting = task -> {
            executor.submit(task);
            return interrupt -> false;
        };
        JobService jobs = service(new MutableClock(NOW), nonInterrupting,
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("visible-cancel");
        CountDownLatch started = new CountDownLatch(1);
        JobDescriptor job = jobs.start(submission(owner, "visible-key", identity(owner),
                execution -> {
                    started.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!execution.cancellationRequested() && System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                    execution.checkCancelled();
                    return output();
                })).job();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        jobs.cancel(owner, job.jobId());
        awaitState(jobs, owner, job.jobId(), JobState.CANCELLED);
        assertTrue(jobs.get(owner, job.jobId()).cancellationEffective());
    }

    @Test
    void unrequestedInterruptionFailsWithoutClaimingCancellation() throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("spurious-interrupt");
        JobDescriptor accepted = jobs.start(submission(owner, "spurious-interrupt-key",
                identity(owner), execution -> {
                    throw new InterruptedException("unexpected interrupt");
                })).job();

        awaitState(jobs, owner, accepted.jobId(), JobState.FAILED);
        JobDescriptor failed = jobs.get(owner, accepted.jobId());
        assertEquals("job_execution_failed", failed.error().code());
        assertFalse(failed.cancellationRequested());
        assertFalse(failed.cancellationEffective());
    }

    @Test
    void cancellationWinsWhilePreCommitGuardIsStillRunning() throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("guard-race");
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        AtomicInteger mutations = new AtomicInteger();
        JobDescriptor job = jobs.start(submission(owner, "guard-race-key", identity(owner),
                execution -> execution.withCommitPermit(output(), () -> {
                    guardEntered.countDown();
                    while (releaseGuard.getCount() > 0) {
                        try { releaseGuard.await(); } catch (InterruptedException ignored) { }
                    }
                    return JobCommitLease.noOp();
                }, () -> {
                    mutations.incrementAndGet();
                    return output();
                }))).job();
        assertTrue(guardEntered.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        JobCancelResult cancelled = jobs.cancel(owner, job.jobId());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(JobCancelOutcome.CANCEL_REQUESTED, cancelled.outcome());
        assertTrue(elapsed < 500, "cancel must not wait for the pre-commit guard");
        releaseGuard.countDown();
        awaitState(jobs, owner, job.jobId(), JobState.CANCELLED);
        assertEquals(0, mutations.get());
        assertFalse(jobs.get(owner, job.jobId()).commitStarted());
    }

    @Test
    void preCommitGuardDenialFailsBeforeCommitStarted() throws Exception {
        JobOwner owner = owner("guard-denied");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());
        AtomicInteger mutations = new AtomicInteger();
        JobDescriptor failed = jobs.start(submission(owner, "guard-denied-key", identity(owner),
                execution -> execution.withCommitPermit(output(), () -> {
                    throw new JobException("authorization_revoked",
                            "Authorization was revoked before commit.", false);
                }, () -> {
                    mutations.incrementAndGet();
                    return output();
                }))).job();

        assertEquals(JobState.FAILED, failed.state());
        assertEquals("authorization_revoked", failed.error().code());
        assertFalse(failed.commitStarted());
        assertEquals(0, mutations.get());
    }

    @Test
    void publicationGuardRunsBeforeReadOnlySuccess() {
        JobOwner owner = owner("publication-guard");
        AtomicInteger validations = new AtomicInteger();
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());

        JobDescriptor completed = jobs.start(submission(owner, "publication-guard-key",
                identity(owner), () -> {
                    validations.incrementAndGet();
                    return JobCommitLease.noOp();
                }, success())).job();

        assertEquals(JobState.SUCCEEDED, completed.state());
        assertEquals(1, validations.get());
    }

    @Test
    void adapterLeasesRemainHeldThroughAuditAndIrreversibleCommit() {
        JobOwner owner = owner("commit-leases");
        AtomicBoolean publicationLease = new AtomicBoolean();
        AtomicBoolean destinationLease = new AtomicBoolean();
        AtomicInteger leaseClosures = new AtomicInteger();
        List<JobEvent> events = new ArrayList<>();
        JobPreCommitGuard publicationGuard = () -> {
            assertTrue(publicationLease.compareAndSet(false, true));
            return () -> {
                assertTrue(publicationLease.compareAndSet(true, false));
                leaseClosures.incrementAndGet();
            };
        };
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), event -> {
                    if (event.kind() == JobEventKind.PUBLICATION_STARTED
                            || event.kind() == JobEventKind.TERMINAL) {
                        assertTrue(publicationLease.get());
                        assertTrue(destinationLease.get());
                    }
                    events.add(event);
                });

        JobDescriptor completed = jobs.start(submission(owner, "commit-leases-key",
                identity(owner), publicationGuard,
                execution -> execution.withCommitPermit(output(), () -> {
                    assertTrue(destinationLease.compareAndSet(false, true));
                    return () -> {
                        assertTrue(destinationLease.compareAndSet(true, false));
                        leaseClosures.incrementAndGet();
                    };
                }, () -> {
                    assertTrue(publicationLease.get());
                    assertTrue(destinationLease.get());
                    return output();
                }))).job();

        assertEquals(JobState.SUCCEEDED, completed.state());
        assertFalse(publicationLease.get());
        assertFalse(destinationLease.get());
        assertEquals(2, leaseClosures.get());
        assertTrue(events.stream().anyMatch(
                event -> event.kind() == JobEventKind.PUBLICATION_STARTED));
    }

    @Test
    void nullPublicationAndDestinationLeasesFailClosedBeforePublication() {
        JobOwner owner = owner("null-leases");
        List<JobEvent> publicationEvents = new ArrayList<>();
        JobService missingPublication = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), publicationEvents::add);

        JobDescriptor readOnlyFailure = missingPublication.start(submission(owner,
                "null-publication-lease", identity(owner), () -> null, execution -> {
                    execution.stageArtifact("application/json", new byte[] {1});
                    return output();
                })).job();

        assertEquals(JobState.FAILED, readOnlyFailure.state());
        assertEquals("job_guard_lease_missing", readOnlyFailure.error().code());
        assertTrue(readOnlyFailure.artifacts().isEmpty());
        assertEquals(0, missingPublication.retainedArtifactBytes());
        assertFalse(publicationEvents.stream().anyMatch(
                event -> event.kind() == JobEventKind.PUBLICATION_STARTED));

        AtomicInteger publicationLeaseClosures = new AtomicInteger();
        AtomicInteger mutations = new AtomicInteger();
        List<JobEvent> commitEvents = new ArrayList<>();
        JobPreCommitGuard publicationGuard =
                () -> publicationLeaseClosures::incrementAndGet;
        JobService missingDestination = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), commitEvents::add);

        JobDescriptor commitFailure = missingDestination.start(submission(owner,
                "null-destination-lease", identity(owner, "null-destination"),
                publicationGuard, execution -> execution.withCommitPermit(
                        output(), () -> null, () -> {
                            mutations.incrementAndGet();
                            return output();
                        }))).job();

        assertEquals(JobState.FAILED, commitFailure.state());
        assertEquals("job_guard_lease_missing", commitFailure.error().code());
        assertEquals(1, publicationLeaseClosures.get());
        assertEquals(0, mutations.get());
        assertFalse(commitEvents.stream().anyMatch(
                event -> event.kind() == JobEventKind.PUBLICATION_STARTED));
    }

    @Test
    void leaseReleaseFailureCannotRewriteSuccessOrEraseArtifacts() {
        JobOwner owner = owner("lease-release");
        JobPreCommitGuard throwingRelease = () -> () -> {
            throw new IllegalStateException("adapter violated non-throwing release contract");
        };
        JobService readOnlyJobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());
        JobDescriptor readOnly = readOnlyJobs.start(submission(owner, "read-release",
                identity(owner), throwingRelease, execution -> {
                    execution.stageArtifact("application/json", new byte[] {1, 2, 3});
                    return output();
                })).job();

        assertEquals(JobState.SUCCEEDED, readOnly.state());
        assertEquals(1, readOnly.artifacts().size());
        assertArrayEquals(new byte[] {1, 2, 3}, readOnlyJobs.requireArtifact(
                owner, readOnly.jobId(), readOnly.artifacts().get(0).artifactId()).copyBytes());

        AtomicInteger mutations = new AtomicInteger();
        JobService commitJobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());
        JobDescriptor committed = commitJobs.start(submission(owner, "commit-release",
                identity(owner, "commit-release"), JobPreCommitGuard.noOp(),
                execution -> {
                    execution.stageArtifact("application/json", new byte[] {4, 5});
                    return execution.withCommitPermit(output(), throwingRelease, () -> {
                        mutations.incrementAndGet();
                        return output();
                    });
                })).job();

        assertEquals(JobState.SUCCEEDED, committed.state());
        assertEquals(1, mutations.get());
        assertArrayEquals(new byte[] {4, 5}, commitJobs.requireArtifact(
                owner, committed.jobId(), committed.artifacts().get(0).artifactId()).copyBytes());
    }

    @Test
    void progressAuditIsBoundedAndTerminalEventSummarizesSuppression() {
        JobOwner owner = owner("bounded-progress-audit");
        List<JobEvent> events = new ArrayList<>();
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), events::add);
        int updates = JobService.MAX_PROGRESS_AUDIT_EVENTS + 37;

        JobDescriptor completed = jobs.start(submission(owner, "bounded-progress-key",
                identity(owner), execution -> {
                    for (int index = 0; index < updates; index++) {
                        execution.progress("compute", "Progress " + index);
                    }
                    return output();
                })).job();

        long progressEvents = events.stream()
                .filter(event -> event.kind() == JobEventKind.PROGRESS).count();
        JobEvent terminal = events.stream()
                .filter(event -> event.kind() == JobEventKind.TERMINAL)
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(JobService.MAX_PROGRESS_AUDIT_EVENTS, progressEvents);
        assertEquals(updates, terminal.progressUpdates());
        assertEquals(JobService.MAX_PROGRESS_AUDIT_EVENTS,
                terminal.progressEventsEmitted());
        assertEquals(37, terminal.progressEventsSuppressed());
        assertEquals(0, terminal.artifactCount());
        assertEquals(0, terminal.artifactBytes());
        assertEquals(JobState.SUCCEEDED, completed.state());
    }

    @Test
    void publicationGuardDenialDoesNotExposeResultOrArtifacts() {
        JobOwner owner = owner("publication-denied");
        JobService jobs = service(new MutableClock(NOW), inlineScheduler(),
                JobRuntimeConfig.defaults());

        JobDescriptor failed = jobs.start(submission(owner, "publication-denied-key",
                identity(owner), () -> {
                    throw new JobException("authorization_revoked",
                            "Authorization was revoked before result publication.", false);
                }, execution -> {
                    execution.stageArtifact("application/json", new byte[] {1});
                    return output();
                })).job();

        assertEquals(JobState.FAILED, failed.state());
        assertEquals("authorization_revoked", failed.error().code());
        assertNull(failed.result());
        assertTrue(failed.artifacts().isEmpty());
    }

    @Test
    void cancellationWinsWhilePublicationGuardIsStillRunning() throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("publication-race");
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        JobDescriptor job = jobs.start(submission(owner, "publication-race-key",
                identity(owner), () -> {
                    guardEntered.countDown();
                    while (releaseGuard.getCount() > 0) {
                        try { releaseGuard.await(); } catch (InterruptedException ignored) { }
                    }
                    return JobCommitLease.noOp();
                }, success())).job();
        assertTrue(guardEntered.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        JobCancelResult cancelled = jobs.cancel(owner, job.jobId());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(JobCancelOutcome.CANCEL_REQUESTED, cancelled.outcome());
        assertTrue(elapsed < 500, "cancel must not wait for the publication guard");
        releaseGuard.countDown();
        awaitState(jobs, owner, job.jobId(), JobState.CANCELLED);

        JobDescriptor completed = jobs.get(owner, job.jobId());
        assertNull(completed.result());
        assertFalse(completed.commitStarted());
    }

    @Test
    void failureObservedAfterCancellationTombstoneEndsCancelled() throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("failure-cancel-race");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobDescriptor job = jobs.start(submission(owner, "failure-race-key", identity(owner),
                execution -> {
                    started.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    throw new java.io.IOException("late failure");
                })).job();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        jobs.cancel(owner, job.jobId());
        release.countDown();
        awaitState(jobs, owner, job.jobId(), JobState.CANCELLED);
        assertNull(jobs.get(owner, job.jobId()).error());
    }

    @Test
    void ownerMismatchIsIndistinguishableFromAbsentForGetCancelAndArtifact() {
        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(new MutableClock(NOW), scheduler, JobRuntimeConfig.defaults());
        JobOwner owner = owner("owner-a");
        JobOwner stranger = owner("owner-b");
        JobDescriptor job = jobs.start(submission(owner, "private", identity(owner),
                execution -> {
                    execution.stageArtifact("application/json", new byte[] {1});
                    return output();
                })).job();
        scheduler.runNext();

        assertUnknown(() -> jobs.get(stranger, job.jobId()));
        assertUnknown(() -> jobs.cancel(stranger, job.jobId()));
        assertUnknown(() -> jobs.requireArtifact(stranger, job.jobId(),
                jobs.get(owner, job.jobId()).artifacts().get(0).artifactId()));
        assertUnknown(() -> jobs.get(owner, "00000000-0000-4000-8000-999999999999"));
    }

    @Test
    void clientAndGrantRevocationCancelOnlyMatchingJobs() {
        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(new MutableClock(NOW), scheduler, JobRuntimeConfig.defaults());
        JobOwner first = owner("grant-a");
        JobOwner second = owner("grant-b");
        JobDescriptor one = jobs.start(submission(first, "one", identity(first), success())).job();
        JobDescriptor two = jobs.start(submission(second, "two", identity(second), success())).job();

        assertEquals(1, jobs.revokeGrant(first.clientFingerprint(), first.grantFingerprint()));
        assertEquals(JobState.CANCELLED, jobs.get(first, one.jobId()).state());
        assertEquals(JobState.QUEUED, jobs.get(second, two.jobId()).state());
        assertEquals(1, jobs.revokeClient(second.clientFingerprint()));
        assertEquals(JobState.CANCELLED, jobs.get(second, two.jobId()).state());
    }

    @Test
    void listIsNewestFirstPagedOwnerScopedAndRejectsForeignCursor() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(clock, scheduler, JobRuntimeConfig.defaults());
        JobOwner owner = owner("list");
        JobOwner other = owner("other");
        JobDescriptor first = jobs.start(submission(owner, "first", identity(owner), success())).job();
        clock.advance(Duration.ofMillis(1));
        JobDescriptor second = jobs.start(submission(owner, "second",
                identity(owner, "second"), success())).job();
        jobs.start(submission(other, "other", identity(other), success()));

        JobPage page = jobs.list(owner, 1, null);
        assertEquals(List.of(second.jobId()), page.jobs().stream().map(JobDescriptor::jobId).toList());
        assertNotNull(page.nextCursor());
        JobPage tail = jobs.list(owner, 1, page.nextCursor());
        assertEquals(List.of(first.jobId()), tail.jobs().stream().map(JobDescriptor::jobId).toList());
        assertNull(tail.nextCursor());
        JobException foreign = assertThrows(JobException.class,
                () -> jobs.list(other, 1, page.nextCursor()));
        assertEquals("invalid_job_cursor", foreign.error().code());
    }

    @Test
    void retentionCannotShortenTheFifteenMinuteIdempotencyWindow() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobRuntimeConfig config = new JobRuntimeConfig(Set.of(JobType.values()),
                2, 1, 1, 1, Duration.ofSeconds(60));
        JobService jobs = service(clock, scheduler, config);
        JobOwner owner = owner("retention");
        JobDescriptor first = jobs.start(submission(owner, "first", identity(owner), success())).job();
        scheduler.runNext();
        JobException quota = assertThrows(JobException.class,
                () -> jobs.start(submission(owner, "second",
                        identity(owner, "second"), success())));
        assertEquals("job_retention_quota_exceeded", quota.error().code());

        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, jobs.revokeClient(owner.clientFingerprint()));
        assertEquals(first.jobId(), jobs.get(owner, first.jobId()).jobId());
        JobStartResult replay = jobs.start(submission(owner, "first",
                identity(owner), success()));
        assertTrue(replay.reused());
        assertEquals(first.jobId(), replay.job().jobId());
        JobException stillRetained = assertThrows(JobException.class,
                () -> jobs.start(submission(owner, "second",
                        identity(owner, "second"), success())));
        assertEquals("job_retention_quota_exceeded", stillRetained.error().code());

        clock.advance(Duration.ofMinutes(14));
        assertUnknown(() -> jobs.get(owner, first.jobId()));
        assertEquals(JobState.QUEUED, jobs.start(submission(owner, "second",
                identity(owner, "second"), success())).job().state());
    }

    @Test
    void backendRetentionQuotaSpansDifferentPrincipals() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobRuntimeConfig config = new JobRuntimeConfig(Set.of(JobType.values()),
                2, 1, 1, 1, Duration.ofHours(1));
        JobService jobs = service(clock, scheduler, config);
        JobOwner first = ownerForPrincipal("principal-one", "grant-one");
        JobOwner second = ownerForPrincipal("principal-two", "grant-two");
        jobs.start(submission(first, "first", identity(first), success()));
        scheduler.runNext();

        JobException quota = assertThrows(JobException.class,
                () -> jobs.start(submission(second, "second", identity(second), success())));

        assertEquals("job_backend_quota_exceeded", quota.error().code());
    }

    @Test
    void resultProgressAndArtifactBoundsFailWithoutPublishingPartialEvidence() {
        ManualScheduler scheduler = new ManualScheduler();
        JobService jobs = service(new MutableClock(NOW), scheduler, JobRuntimeConfig.defaults());
        JobOwner owner = owner("bounds");
        JobDescriptor progress = jobs.start(submission(owner, "progress", identity(owner),
                execution -> {
                    execution.progress("phase", "x".repeat(JobService.MAX_PROGRESS_BYTES + 1));
                    return output();
                })).job();
        scheduler.runNext();
        assertEquals(JobState.FAILED, jobs.get(owner, progress.jobId()).state());

        JobDescriptor artifacts = jobs.start(submission(owner, "artifacts",
                identity(owner, "artifacts"), execution -> {
                    for (int index = 0; index <= JobService.MAX_ARTIFACTS_PER_JOB; index++) {
                        execution.stageArtifact("application/json", new byte[] {(byte) index});
                    }
                    return output();
                })).job();
        scheduler.runNext();
        JobDescriptor artifactFailure = jobs.get(owner, artifacts.jobId());
        assertEquals(JobState.FAILED, artifactFailure.state());
        assertEquals("job_artifact_quota_exceeded", artifactFailure.error().code());
        assertTrue(artifactFailure.artifacts().isEmpty());

        JobDescriptor result = jobs.start(submission(owner, "result", identity(owner, "result"),
                execution -> new JobTaskOutput(JobResultType.CLASSIFICATION,
                        Map.of("oversized", "x".repeat(JobService.MAX_RESULT_BYTES)), false))).job();
        scheduler.runNext();
        assertEquals("job_result_too_large", jobs.get(owner, result.jobId()).error().code());
    }

    @Test
    void resultBoundIncludesThePublicResultEnvelope() {
        ManualScheduler scheduler = new ManualScheduler();
        JobArtifactLimits limits = new JobArtifactLimits(1, 1, 1, 1, 1, 32);
        JobService jobs = service(new MutableClock(NOW), scheduler,
                JobRuntimeConfig.defaults(), limits);
        JobOwner owner = owner("result-envelope");
        JobDescriptor accepted = jobs.start(submission(owner, "result-envelope-key",
                identity(owner), success())).job();

        scheduler.runNext();

        JobDescriptor failed = jobs.get(owner, accepted.jobId());
        assertEquals(JobState.FAILED, failed.state());
        assertEquals("job_result_too_large", failed.error().code());
    }

    @Test
    void backendArtifactQuotaReleasesBytesAtAdvertisedExpiry() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobArtifactLimits limits = new JobArtifactLimits(2, 4, 6, 6, 1_024, 32);
        JobRuntimeConfig config = new JobRuntimeConfig(Set.of(JobType.values()),
                8, 8, 32, 128, Duration.ofSeconds(60));
        JobService jobs = service(clock, scheduler, config, limits);
        JobOwner owner = owner("artifact-expiry");
        JobDescriptor first = jobs.start(submission(owner, "artifact-one", identity(owner),
                execution -> {
                    execution.stageArtifact("application/json", new byte[] {1, 2, 3, 4});
                    execution.stageArtifact("application/json", new byte[] {5, 6});
                    return output();
                })).job();
        scheduler.runNext();
        JobDescriptor completed = jobs.get(owner, first.jobId());
        String artifactId = completed.artifacts().get(0).artifactId();

        JobDescriptor blocked = jobs.start(submission(owner, "artifact-two",
                identity(owner, "artifact-two"), execution -> {
                    execution.stageArtifact("application/json", new byte[] {7});
                    return output();
                })).job();
        scheduler.runNext();
        assertEquals("job_artifact_backend_quota_exceeded",
                jobs.get(owner, blocked.jobId()).error().code());

        clock.advance(Duration.ofSeconds(61));
        JobException expired = assertThrows(JobException.class,
                () -> jobs.requireArtifact(owner, first.jobId(), artifactId));
        assertEquals("unknown_job_artifact", expired.error().code());
        JobDescriptor afterExpiry = jobs.start(submission(owner, "artifact-three",
                identity(owner, "artifact-three"), execution -> {
                    execution.stageArtifact("application/json", new byte[] {8});
                    return output();
                })).job();
        scheduler.runNext();
        assertEquals(JobState.SUCCEEDED, jobs.get(owner, afterExpiry.jobId()).state());
    }

    @Test
    void activeStagedArtifactsAreNotExpiredBeforeTerminalPublication() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ExecutorService executor = executor();
        JobRuntimeConfig config = new JobRuntimeConfig(Set.of(JobType.values()),
                4, 4, 8, 16, Duration.ofSeconds(60));
        JobService jobs = service(clock, scheduler(executor), config);
        JobOwner owner = owner("active-artifact");
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobDescriptor job = jobs.start(submission(owner, "active-artifact-key", identity(owner),
                execution -> {
                    execution.stageArtifact("application/json", new byte[] {1, 2, 3});
                    staged.countDown();
                    release.await();
                    return output();
                })).job();
        assertTrue(staged.await(5, TimeUnit.SECONDS));

        clock.advance(Duration.ofSeconds(61));
        assertEquals(JobState.RUNNING, jobs.get(owner, job.jobId()).state());
        jobs.list(owner, 10, null);
        release.countDown();
        awaitState(jobs, owner, job.jobId(), JobState.SUCCEEDED);
        JobDescriptor completed = jobs.get(owner, job.jobId());
        assertEquals(1, completed.artifacts().size());
        assertArrayEquals(new byte[] {1, 2, 3}, jobs.requireArtifact(owner, job.jobId(),
                completed.artifacts().get(0).artifactId()).copyBytes());
    }

    @Test
    void publicJsonUsesLowercaseEnumsAndSnakeCaseFields() throws Exception {
        JobOwner owner = owner("json");
        JobService jobs = service(new MutableClock(NOW), new ManualScheduler(),
                JobRuntimeConfig.defaults());
        JobDescriptor queued = jobs.start(submission(owner, "json-key", identity(owner),
                success())).job();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules();
        com.fasterxml.jackson.databind.JsonNode json = mapper.valueToTree(queued);

        assertEquals("queued", json.path("state").asText());
        assertEquals("classification", json.path("type").asText());
        assertEquals("classification", json.path("result_discriminator").asText());
        assertEquals(queued.jobId(), json.path("job_id").asText());
        assertEquals(owner.principalFingerprint(), json.path("principal_fingerprint").asText());
        assertEquals(owner.clientFingerprint(), json.path("client_fingerprint").asText());
        assertEquals(owner.grantFingerprint(), json.path("grant_fingerprint").asText());
        assertTrue(json.has("created_at"));
        assertTrue(json.has("cancellation_requested"));
        assertEquals("commit_in_progress", mapper.writeValueAsString(
                JobCancelOutcome.COMMIT_IN_PROGRESS).replace("\"", ""));
        assertEquals(queued, io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                .readValue(io.github.hakjuoh.protege_mcp.contracts.ContractJson.mapper()
                        .writeValueAsBytes(queued), JobDescriptor.class));
    }

    @Test
    void corruptAndStaleCursorsFailClosed() {
        MutableClock clock = new MutableClock(NOW);
        ManualScheduler scheduler = new ManualScheduler();
        JobRuntimeConfig config = new JobRuntimeConfig(Set.of(JobType.values()),
                4, 4, 8, 16, Duration.ofSeconds(60));
        JobService jobs = service(clock, scheduler, config);
        JobOwner owner = owner("cursor");
        jobs.start(submission(owner, "cursor-one", identity(owner), success()));
        clock.advance(Duration.ofMillis(1));
        jobs.start(submission(owner, "cursor-two", identity(owner, "two"), success()));
        JobPage page = jobs.list(owner, 1, null);

        JobException corrupt = assertThrows(JobException.class,
                () -> jobs.list(owner, 1, page.nextCursor() + "!"));
        assertEquals("invalid_job_cursor", corrupt.error().code());
        scheduler.runNext();
        scheduler.runNext();
        clock.advance(Duration.ofMinutes(15));
        JobException stale = assertThrows(JobException.class,
                () -> jobs.list(owner, 1, page.nextCursor()));
        assertEquals("invalid_job_cursor", stale.error().code());
    }

    @Test
    void closeMakesEveryIdUnknownAndFencesLateWork() throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("close");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobDescriptor job = jobs.start(submission(owner, "close-key", identity(owner),
                execution -> {
                    started.countDown();
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    execution.checkCancelled();
                    return output();
                })).job();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        jobs.close();
        release.countDown();
        assertTrue(jobs.isClosed());
        assertUnknown(() -> jobs.get(owner, job.jobId()));
        assertUnknown(() -> jobs.list(owner, 10, null));
    }

    @Test
    void closeWinsBeforePublicationClaimAndDiscardsLateOutput() throws Exception {
        ExecutorService executor = executor();
        List<JobEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        CountDownLatch leaseClosed = new CountDownLatch(1);
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), events::add);
        JobOwner owner = owner("close-before-publication");

        jobs.start(submission(owner, "close-before-publication-key", identity(owner), () -> {
            guardEntered.countDown();
            while (releaseGuard.getCount() > 0) {
                try {
                    releaseGuard.await();
                } catch (InterruptedException ignored) {
                    // Shutdown interruption is acceleration; the fence decides the winner.
                }
            }
            return leaseClosed::countDown;
        }, success()));
        assertTrue(guardEntered.await(5, TimeUnit.SECONDS));

        jobs.close();
        releaseGuard.countDown();
        assertTrue(leaseClosed.await(5, TimeUnit.SECONDS));

        assertTrue(events.stream().anyMatch(
                event -> event.kind() == JobEventKind.CANCELLATION_EFFECTIVE));
        assertFalse(events.stream().anyMatch(
                event -> event.kind() == JobEventKind.PUBLICATION_STARTED));
        assertEquals(0, jobs.retainedArtifactBytes());
    }

    @Test
    void publicationClaimWinsShutdownWithoutBlockingOrLeakingArtifacts() throws Exception {
        ExecutorService executor = executor();
        List<JobEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch terminalDelivered = new CountDownLatch(1);
        JobEventSink sink = event -> {
            events.add(event);
            if (event.kind() == JobEventKind.PUBLICATION_STARTED) {
                publicationEntered.countDown();
                while (releasePublication.getCount() > 0) {
                    try {
                        releasePublication.await();
                    } catch (InterruptedException ignored) {
                        // A publication winner is not cancelled by shutdown.
                    }
                }
            }
            if (event.kind() == JobEventKind.TERMINAL) terminalDelivered.countDown();
        };
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults(), JobArtifactLimits.defaults(), sink);
        JobOwner owner = owner("publication-before-close");

        jobs.start(submission(owner, "publication-before-close-key", identity(owner),
                execution -> {
                    execution.stageArtifact("application/json", new byte[] {1});
                    return output();
                }));
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        jobs.close();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed < 500, "close must not wait for an active audit callback");

        releasePublication.countDown();
        assertTrue(terminalDelivered.await(5, TimeUnit.SECONDS));
        assertTrue(awaitRetainedBytes(jobs, 0, Duration.ofSeconds(5)));
        JobEvent terminal = events.stream()
                .filter(event -> event.kind() == JobEventKind.TERMINAL)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(JobState.SUCCEEDED, terminal.state());
        assertFalse(terminal.cancellationRequested());
        assertFalse(terminal.cancellationEffective());
    }

    @Test
    void closePreventsArtifactStagingAfterCommitStarted() throws Exception {
        ExecutorService executor = executor();
        JobService jobs = service(new MutableClock(NOW), scheduler(executor),
                JobRuntimeConfig.defaults());
        JobOwner owner = owner("close-commit-stage");
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicInteger stagingAttempts = new AtomicInteger();
        jobs.start(submission(owner, "close-commit-stage-key", identity(owner), execution -> {
            try {
                return execution.withCommitPermit(
                        output(), JobPreCommitGuard.noOp(), () -> {
                    commitEntered.countDown();
                    releaseCommit.await();
                    stagingAttempts.incrementAndGet();
                    execution.stageArtifact("application/json", new byte[] {1});
                    return output();
                });
            } finally {
                taskFinished.countDown();
            }
        }));
        assertTrue(commitEntered.await(5, TimeUnit.SECONDS));

        jobs.close();
        releaseCommit.countDown();
        assertTrue(taskFinished.await(5, TimeUnit.SECONDS));

        assertEquals(1, stagingAttempts.get());
        assertEquals(0, jobs.retainedArtifactBytes());
    }

    private static boolean awaitRetainedBytes(
            JobService jobs, long expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (jobs.retainedArtifactBytes() == expected) return true;
            Thread.sleep(10);
        }
        return jobs.retainedArtifactBytes() == expected;
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private static JobScheduler scheduler(ExecutorService executor) {
        return task -> {
            Future<?> future = executor.submit(task);
            return future::cancel;
        };
    }

    private static JobScheduler inlineScheduler() {
        return task -> {
            task.run();
            return interrupt -> false;
        };
    }

    private static JobService service(MutableClock clock, JobScheduler scheduler,
            JobRuntimeConfig config) {
        return service(clock, scheduler, config, JobArtifactLimits.defaults());
    }

    private static JobService service(MutableClock clock, JobScheduler scheduler,
            JobRuntimeConfig config, JobArtifactLimits limits) {
        return service(clock, scheduler, config, limits, JobEventSink.noOp());
    }

    private static JobService service(MutableClock clock, JobScheduler scheduler,
            JobRuntimeConfig config, JobArtifactLimits limits, JobEventSink eventSink) {
        AtomicInteger ids = new AtomicInteger();
        Supplier<UUID> generator = () -> UUID.fromString(String.format(
                "00000000-0000-4000-8000-%012x", ids.incrementAndGet()));
        return new JobService(clock, config, scheduler, eventSink, generator, limits);
    }

    private static JobSubmission submission(JobOwner owner, String key,
            JobInputIdentity identity, JobTask task) {
        return submission(owner, key, identity, JobPreCommitGuard.noOp(), task);
    }

    private static JobSubmission submission(JobOwner owner, String key,
            JobInputIdentity identity, JobPreCommitGuard publicationGuard,
            JobTask task) {
        return new JobSubmission(owner, JobType.CLASSIFICATION, key, identity,
                Set.of("ontology:read"), true, publicationGuard, task);
    }

    private static JobTask success() {
        return execution -> output();
    }

    private static JobTask retained(AtomicInteger discarded) {
        return new JobTask() {
            @Override
            public JobTaskOutput execute(JobExecution execution) {
                return output();
            }

            @Override
            public void discard() {
                discarded.incrementAndGet();
            }
        };
    }

    private static JobTaskOutput output() {
        return new JobTaskOutput(JobResultType.CLASSIFICATION, Map.of("consistent", true), false);
    }

    private static JobOwner owner(String grant) {
        return owner("00000000-0000-4000-8000-000000000001", grant);
    }

    private static JobOwner owner(String workspace, String grant) {
        return new JobOwner(workspace, digest("shared-principal"),
                digest("shared-client"), digest(grant));
    }

    private static JobOwner ownerForPrincipal(String principal, String grant) {
        return new JobOwner("00000000-0000-4000-8000-000000000001", digest(principal),
                digest("shared-client"), digest(grant));
    }

    private static JobInputIdentity identity(JobOwner owner) {
        return identity(owner, "request");
    }

    private static JobInputIdentity identity(JobOwner owner, String request) {
        return new JobInputIdentity(new ModelRevision(owner.workspaceId(), 1,
                digest("semantic"), digest("document")), digest("closure"), null, null,
                digest("policy"), digest("assets"), digest("reasoner"), digest(request), List.of());
    }

    private static String digest(String value) {
        return JobHashes.digest(value);
    }

    private static void assertUnknown(org.junit.jupiter.api.function.Executable action) {
        JobException error = assertThrows(JobException.class, action);
        assertEquals("unknown_job", error.error().code());
    }

    private static void awaitState(JobService jobs, JobOwner owner, String id, JobState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jobs.get(owner, id).state() == expected) return;
            Thread.sleep(5);
        }
        assertEquals(expected, jobs.get(owner, id).state());
    }

    private static final class ManualScheduler implements JobScheduler {
        private final ArrayDeque<Entry> tasks = new ArrayDeque<>();

        @Override
        public JobTaskHandle submit(Runnable task) {
            Entry entry = new Entry(task);
            tasks.add(entry);
            return interrupt -> {
                entry.cancelled = true;
                return true;
            };
        }

        void runNext() {
            Entry entry = tasks.remove();
            if (!entry.cancelled) entry.task.run();
        }

        private static final class Entry {
            private final Runnable task;
            private boolean cancelled;
            private Entry(Runnable task) { this.task = task; }
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
