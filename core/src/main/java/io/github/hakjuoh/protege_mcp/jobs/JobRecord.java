package io.github.hakjuoh.protege_mcp.jobs;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Mutable state for exactly one job; the runtime synchronizes on the record for snapshots. */
final class JobRecord {
    @FunctionalInterface
    interface EventRecorder {
        void record(JobRecord job, JobEventKind kind);
    }

    final String jobId;
    final JobOwner owner;
    final JobType type;
    final String idempotencyKey;
    final JobInputIdentity identity;
    final Set<String> requiredCapabilities;
    final JobPreCommitGuard publicationGuard;
    final JobRuntimeConfig runtimeConfig;
    JobTask task;
    final Instant createdAt;
    final JobArtifactStore artifacts;
    final JobEventDelivery eventDelivery = new JobEventDelivery();
    final AtomicReference<JobCommitFence> fence =
            new AtomicReference<>(JobCommitFence.OPEN);
    final ReentrantLock commitPermit = new ReentrantLock(true);
    private final EventRecorder events;

    volatile JobState state = JobState.QUEUED;
    Instant startedAt;
    volatile Instant completedAt;
    String phase = "queued";
    String progress = "Waiting for a worker.";
    long progressSequence;
    long progressUpdates;
    int progressEventsEmitted;
    long progressEventsSuppressed;
    long eventSequence;
    boolean cancellationEffective;
    boolean commitCompleted;
    JobTaskOutput committedOutput;
    JobResult result;
    JobError error;
    JobTaskHandle handle;

    JobRecord(String jobId, JobSubmission submission, JobRuntimeConfig runtimeConfig, Instant createdAt,
            JobArtifactLimits limits, JobArtifactBudget artifactBudget, EventRecorder events) {
        this.jobId = jobId;
        this.owner = submission.owner();
        this.type = submission.type();
        this.idempotencyKey = submission.idempotencyKey();
        this.identity = submission.inputIdentity();
        this.requiredCapabilities = submission.requiredCapabilities();
        this.publicationGuard = submission.publicationGuard();
        this.runtimeConfig = java.util.Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.task = submission.task();
        this.createdAt = createdAt;
        this.artifacts = new JobArtifactStore(limits, artifactBudget);
        this.events = events;
    }

    void transition(JobState next, Instant terminalAt) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("illegal job transition " + state + " -> " + next);
        }
        if (next.terminal()) {
            if (terminalAt == null) throw new IllegalArgumentException("terminal time is required");
            completedAt = terminalAt;
        }
        state = next;
    }

    void fail(JobError failure, Instant now) {
        if (state.terminal()) return;
        error = java.util.Objects.requireNonNull(failure, "failure");
        transition(JobState.FAILED, now);
        phase = "failed";
        progress = "Job failed.";
        progressSequence++;
        events.record(this, JobEventKind.TERMINAL);
    }

    void cancelled(Instant now, String message) {
        if (state.terminal()) return;
        fence.compareAndSet(JobCommitFence.OPEN, JobCommitFence.CANCELLED);
        if (fence.get() != JobCommitFence.CANCELLED) {
            throw new IllegalStateException(
                    "job cancellation cannot overtake publication or commit");
        }
        cancellationEffective = true;
        transition(JobState.CANCELLED, now);
        phase = "cancelled";
        progress = message;
        progressSequence++;
        events.record(this, JobEventKind.CANCELLATION_EFFECTIVE);
        events.record(this, JobEventKind.TERMINAL);
    }

    /** Transfer a task out of the record before invoking any adapter code. */
    JobTask takeTask() {
        JobTask discarded = task;
        task = null;
        return discarded;
    }

    JobDescriptor snapshot() {
        return new JobDescriptor(jobId, owner.workspaceId(), owner.ownerFingerprint(),
                owner.principalFingerprint(), owner.clientFingerprint(), owner.grantFingerprint(),
                type, state, createdAt.toString(),
                startedAt == null ? null : startedAt.toString(),
                completedAt == null ? null : completedAt.toString(), identity.modelRevision(),
                identity.policyDigest(), phase, progress, progressSequence,
                fence.get() == JobCommitFence.CANCELLED, cancellationEffective,
                fence.get() == JobCommitFence.COMMIT_STARTED, idempotencyKey,
                requiredCapabilities, identity, type.resultType(), result, error);
    }
}
