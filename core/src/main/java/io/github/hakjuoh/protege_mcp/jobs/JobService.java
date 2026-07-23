package io.github.hakjuoh.protege_mcp.jobs;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;

/**
 * Bounded in-memory owner-scoped job state machine. Execution threads are supplied by the live
 * adapter; this class coordinates lifecycle transitions and cancellation/publication fences.
 * Dedicated collaborators own private artifact retention and ordered audit delivery.
 */
public final class JobService implements AutoCloseable {
    public static final int MAX_ARTIFACTS_PER_JOB = 4;
    public static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;
    public static final long MAX_JOB_ARTIFACT_BYTES = 128L * 1024 * 1024;
    public static final long MAX_BACKEND_ARTIFACT_BYTES = 512L * 1024 * 1024;
    public static final int MAX_RESULT_BYTES = 1 * 1024 * 1024;
    public static final int MAX_PROGRESS_BYTES = 1_024;
    public static final int MAX_PROGRESS_AUDIT_EVENTS = 64;
    public static final Duration IDEMPOTENCY_WINDOW = Duration.ofMinutes(15);
    public static final Duration CANCELLATION_GRACE = Duration.ofSeconds(5);

    private static final ObjectMapper JSON = ContractJson.mapper();
    private final Clock clock;
    private volatile JobRuntimeConfig config;
    private final JobScheduler scheduler;
    private final JobEventSink eventSink;
    private final Supplier<UUID> ids;
    private final JobArtifactLimits limits;
    private final JobRegistry registry = new JobRegistry();
    private final JobCursorCodec cursors = new JobCursorCodec();
    private final JobArtifactBudget artifactBudget = new JobArtifactBudget();
    private final Map<Long, JobAdmission> admissions = new LinkedHashMap<>();
    private long nextAdmissionToken;
    private volatile boolean closed;

    public JobService(Clock clock, JobRuntimeConfig config, JobScheduler scheduler) {
        this(clock, config, scheduler, JobEventSink.noOp());
    }

    public JobService(Clock clock, JobRuntimeConfig config, JobScheduler scheduler, JobEventSink eventSink) {
        this(clock, config, scheduler, eventSink, UUID::randomUUID, JobArtifactLimits.defaults());
    }

    JobService(Clock clock, JobRuntimeConfig config, JobScheduler scheduler, Supplier<UUID> ids) {
        this(clock, config, scheduler, JobEventSink.noOp(), ids, JobArtifactLimits.defaults());
    }

    JobService(Clock clock, JobRuntimeConfig config, JobScheduler scheduler, Supplier<UUID> ids,
            JobArtifactLimits limits) {
        this(clock, config, scheduler, JobEventSink.noOp(), ids, limits);
    }

    JobService(Clock clock, JobRuntimeConfig config, JobScheduler scheduler, JobEventSink eventSink,
            Supplier<UUID> ids, JobArtifactLimits limits) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Apply a newly resolved policy for subsequent admission and retention decisions. */
    public synchronized void updateConfig(JobRuntimeConfig updated) {
        ensureOpen();
        config = Objects.requireNonNull(updated, "updated");
        cleanupExpired();
    }

    /**
     * Reserve policy and quota capacity before the live adapter allocates immutable captures.
     * Idempotent retries receive a non-counting permit so they can recapture and prove identity
     * without being rejected by quotas already consumed by the original job.
     */
    public synchronized JobAdmission reserve(JobOwner owner, JobType type,
            String idempotencyKey, JobRuntimeConfig admissionConfig) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        JobRuntimeConfig resolved = Objects.requireNonNull(
                admissionConfig, "admissionConfig");
        String key = JobValidators.requireIdempotencyKey(idempotencyKey);
        ensureOpen();
        cleanupExpired();
        if (!resolved.allowedTypes().contains(type)) {
            throw failure("job_type_disabled",
                    "The project policy does not allow this job type.", false);
        }
        long ownerCaptures = admissions.values().stream()
                .filter(admission -> admission.owner.principalFingerprint()
                        .equals(owner.principalFingerprint()))
                .count();
        if (ownerCaptures >= resolved.activePerPrincipal()
                || admissions.size() >= resolved.retainedPerBackend()) {
            throw failure("job_capture_capacity_exceeded",
                    "The bounded pre-capture job capacity is exhausted.", true);
        }
        Instant now = clock.instant();
        boolean idempotent = registry.idempotent(owner, type, key, now) != null;
        boolean quotaReserved = !idempotent;
        if (quotaReserved) {
            long principalReservations = admissions.values().stream()
                    .filter(admission -> admission.quotaReserved)
                    .filter(admission -> admission.owner.principalFingerprint()
                            .equals(owner.principalFingerprint()))
                    .count();
            long backendReservations = admissions.values().stream()
                    .filter(admission -> admission.quotaReserved).count();
            registry.admit(owner, resolved, principalReservations, backendReservations);
            if (registry.queuedCount() + backendReservations
                    >= resolved.queueCapacity()) {
                throw failure("job_queue_full",
                        "The asynchronous job queue is full.", true);
            }
        }
        long token = nextAdmissionToken();
        JobAdmission admission = new JobAdmission(
                this, token, owner, type, key, resolved, quotaReserved);
        admissions.put(token, admission);
        return admission;
    }

    /** Consume one matching pre-capture reservation and admit the captured submission atomically. */
    public JobStartResult start(
            JobSubmission submission, JobAdmission admission) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(admission, "admission");
        TaskCleanup cleanup = new TaskCleanup(submission.task());
        try {
            synchronized (this) {
                consumeAdmission(submission, admission);
                return startInternalLocked(submission, admission.config,
                        true, admission.quotaReserved, cleanup);
            }
        } finally {
            cleanup.run();
        }
    }

    /** Accept or idempotently recover one immutable submission. */
    public JobStartResult start(JobSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        TaskCleanup cleanup = new TaskCleanup(submission.task());
        try {
            synchronized (this) {
                return startInternalLocked(
                        submission, config, false, false, cleanup);
            }
        } finally {
            cleanup.run();
        }
    }

    /**
     * Called only while holding this service monitor. Adapter cleanup is deferred through
     * {@code cleanup} so no rejected capture is disposed while the monitor is held.
     */
    private JobStartResult startInternalLocked(JobSubmission submission,
            JobRuntimeConfig admissionConfig, boolean preReserved,
            boolean quotaReserved, TaskCleanup cleanup) {
        ensureOpen();
        cleanupExpired();
        if (!admissionConfig.allowedTypes().contains(submission.type())) {
            throw failure("job_type_disabled",
                    "The project policy does not allow this job type.", false);
        }
        boolean usesReasoner = submission.inputIdentity().reasonerDigest() != null;
        if (submission.type().requiresReasoner() && !usesReasoner
                || usesReasoner && !submission.reasonerCancellationProven()) {
            throw failure("job_reasoner_not_cancellable",
                    "The exact reasoner profile has not proven bounded cancellation.", false);
        }
        Instant now = clock.instant();
        JobRecord idempotent = registry.idempotent(submission, now);
        if (idempotent != null) {
            synchronized (idempotent) {
                if (idempotent.task == submission.task()) {
                    // Re-submitting the same immutable submission must not discard the task
                    // already owned by the queued record.
                    cleanup.clear();
                }
                if (!idempotent.identity.identityDigest()
                        .equals(submission.inputIdentity().identityDigest())) {
                    throw failure("idempotency_conflict",
                            "The idempotency key is already bound to different job inputs.",
                            false);
                }
                return new JobStartResult(idempotent.snapshot(), true);
            }
        }
        if (preReserved && !quotaReserved) {
            throw failure("job_admission_expired",
                    "The pre-capture job reservation no longer matches an idempotent job.",
                    true);
        }
        boolean directQueueFull = false;
        if (!preReserved) {
            long principalReservations = quotaReservations(
                    submission.owner().principalFingerprint());
            long backendReservations = quotaReservations(null);
            if (backendReservations > 0) {
                throw failure("job_capture_capacity_exceeded",
                        "A pre-capture reservation owns the remaining admission decision.",
                        true);
            }
            registry.admit(submission.owner(), admissionConfig,
                    principalReservations, backendReservations);
            directQueueFull = registry.queuedCount() + backendReservations
                    >= admissionConfig.queueCapacity();
        }
        JobRecord record = new JobRecord(uniqueJobId(), submission, admissionConfig, now,
                limits, artifactBudget, this::enqueueEventLocked);
        registry.add(record);
        cleanup.clear();
        synchronized (record) {
            enqueueEventLocked(record, JobEventKind.ACCEPTED);
        }
        drainEvents(record);
        if (directQueueFull) {
            JobStartResult result;
            synchronized (record) {
                record.fail(new JobError("job_queue_full",
                        "The asynchronous job queue is full.", true, Map.of()), now);
                cleanup.replace(record.takeTask());
                result = new JobStartResult(record.snapshot(), false);
            }
            drainEvents(record);
            return result;
        }
        try {
            JobTaskHandle handle = scheduler.submit(() -> run(record));
            if (handle == null) {
                throw new RejectedExecutionException("scheduler returned no handle");
            }
            synchronized (record) {
                record.handle = handle;
                if (record.state == JobState.CANCELLED) requestInterrupt(handle);
                return new JobStartResult(record.snapshot(), false);
            }
        } catch (RejectedExecutionException rejected) {
            JobStartResult result;
            synchronized (record) {
                record.fail(new JobError("job_queue_full",
                        "The asynchronous job queue is full.", true, Map.of()),
                        clock.instant());
                cleanup.replace(record.takeTask());
                result = new JobStartResult(record.snapshot(), false);
            }
            drainEvents(record);
            return result;
        } catch (RuntimeException schedulerFailure) {
            JobStartResult result;
            synchronized (record) {
                record.fail(new JobError("job_scheduler_failed",
                        "The asynchronous scheduler rejected the job.", true, Map.of()),
                        clock.instant());
                cleanup.replace(record.takeTask());
                result = new JobStartResult(record.snapshot(), false);
            }
            drainEvents(record);
            return result;
        }
    }

    public JobDescriptor get(JobOwner owner, String jobId) {
        JobRecord record = requireOwned(owner, jobId);
        synchronized (record) {
            return record.snapshot();
        }
    }

    /** Request monotonic cancellation without waiting for a blocking worker. */
    public JobCancelResult cancel(JobOwner owner, String jobId) {
        JobRecord record = requireOwned(owner, jobId);
        JobTaskHandle handle = null;
        JobTask cancellationTarget = null;
        JobTask discarded = null;
        JobCancelOutcome outcome;
        JobDescriptor descriptor;
        synchronized (record) {
            JobCommitFence observed = record.fence.get();
            if (record.state.terminal()) {
                outcome = JobCancelOutcome.ALREADY_TERMINAL;
            } else if (observed == JobCommitFence.COMMIT_STARTED
                    || observed == JobCommitFence.PUBLICATION_STARTED) {
                outcome = JobCancelOutcome.COMMIT_IN_PROGRESS;
            } else {
                boolean newRequest = observed == JobCommitFence.OPEN
                        && record.fence.compareAndSet(
                                JobCommitFence.OPEN, JobCommitFence.CANCELLED);
                if (newRequest) enqueueEventLocked(record, JobEventKind.CANCEL_REQUESTED);
                if (record.state == JobState.QUEUED) {
                    record.cancelled(clock.instant(),
                            "Cancellation completed before worker claim.");
                    handle = record.handle;
                    discarded = record.takeTask();
                    outcome = JobCancelOutcome.CANCELLED;
                } else {
                    if (record.state == JobState.RUNNING) {
                        record.transition(JobState.CANCEL_PENDING, null);
                        record.phase = "cancel_pending";
                        record.progress = "Cancellation requested; waiting for the worker to stop.";
                        record.progressSequence++;
                    }
                    handle = record.handle;
                    cancellationTarget = record.task;
                    outcome = JobCancelOutcome.CANCEL_REQUESTED;
                }
            }
            descriptor = record.snapshot();
        }
        drainEventsIfAvailable(record);
        requestCancellation(cancellationTarget);
        requestInterrupt(handle);
        discard(discarded);
        return new JobCancelResult(descriptor, outcome);
    }

    /** List only exact-owner jobs, newest first, with an opaque stable anchor cursor. */
    public synchronized JobPage list(JobOwner owner, int limit, String cursor) {
        Objects.requireNonNull(owner, "owner");
        if (limit < 1 || limit > 100) throw failure("invalid_job_limit",
                "Job list limit must be between 1 and 100.", false);
        ensureOpen();
        cleanupExpired();
        List<JobRecord> owned = registry.ownedNewestFirst(owner);
        int start = cursor == null ? 0 : cursors.start(owner, owned, cursor);
        List<JobDescriptor> rows = new ArrayList<>();
        for (int index = start; index < owned.size() && rows.size() < limit; index++) {
            JobRecord record = owned.get(index);
            synchronized (record) {
                rows.add(record.snapshot());
            }
        }
        String next = null;
        int consumed = start + rows.size();
        if (consumed < owned.size() && !rows.isEmpty()) {
            JobRecord anchor = owned.get(consumed - 1);
            next = cursors.encode(owner, anchor);
        }
        return new JobPage(List.copyOf(rows), next);
    }

    /** Return verified artifact bytes only to the exact owning principal/workspace. */
    public JobArtifact requireArtifact(JobOwner owner, String jobId, String artifactId) {
        JobRecord record = requireOwned(owner, jobId);
        synchronized (record) {
            if (record.state != JobState.SUCCEEDED) throw unknownArtifact();
            JobArtifact artifact = record.artifacts.require(jobId, artifactId, clock.instant());
            if (artifact != null) return artifact;
        }
        throw unknownArtifact();
    }

    public int revokeClient(String clientFingerprint) {
        JobHashes.requireDigest(clientFingerprint, "client fingerprint");
        return cancelMatching(owner -> owner.clientFingerprint().equals(clientFingerprint));
    }

    public int revokeGrant(String clientFingerprint, String grantFingerprint) {
        JobHashes.requireDigest(clientFingerprint, "client fingerprint");
        JobHashes.requireDigest(grantFingerprint, "grant fingerprint");
        return cancelMatching(owner -> owner.clientFingerprint().equals(clientFingerprint)
                && owner.grantFingerprint().equals(grantFingerprint));
    }

    @Override
    public void close() {
        List<JobRecord> current;
        List<JobAdmission> pendingAdmissions;
        synchronized (this) {
            if (closed) return;
            closed = true;
            current = registry.clear();
            pendingAdmissions = List.copyOf(admissions.values());
            admissions.clear();
            pendingAdmissions.forEach(admission -> admission.finished.set(true));
        }
        for (JobRecord record : current) {
            JobTaskHandle handle;
            JobTask cancellationTarget;
            JobTask discarded;
            boolean cancelled = record.fence.compareAndSet(
                    JobCommitFence.OPEN, JobCommitFence.CANCELLED);
            boolean releaseArtifacts;
            synchronized (record) {
                handle = cancelled ? record.handle : null;
                cancellationTarget = cancelled ? record.task : null;
                discarded = null;
                if (cancelled && !record.state.terminal()) {
                    enqueueEventLocked(record, JobEventKind.CANCEL_REQUESTED);
                    record.cancelled(clock.instant(), "Cancellation completed during shutdown.");
                }
                if (record.state == JobState.QUEUED || record.state.terminal()) {
                    discarded = record.takeTask();
                }
                // A publication or commit that won the fence CAS owns its staged artifacts until
                // it reaches a terminal state. Its completion path releases them after shutdown.
                releaseArtifacts = cancelled || record.state.terminal();
                if (releaseArtifacts) record.artifacts.releaseAll();
            }
            drainEventsIfAvailable(record);
            requestCancellation(cancellationTarget);
            requestInterrupt(handle);
            discard(discarded);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    long retainedArtifactBytes() {
        return artifactBudget.retainedBytes();
    }

    private void run(JobRecord record) {
        boolean execute;
        JobTask discarded = null;
        synchronized (record) {
            if (closed || record.state == JobState.CANCELLED) {
                discarded = record.takeTask();
                execute = false;
            } else if (record.fence.get() == JobCommitFence.CANCELLED) {
                record.cancelled(clock.instant(), "Cancellation completed before execution.");
                discarded = record.takeTask();
                execute = false;
            } else {
                record.transition(JobState.RUNNING, null);
                record.startedAt = clock.instant();
                record.phase = "running";
                record.progress = "Worker claimed the job.";
                record.progressSequence++;
                enqueueEventLocked(record, JobEventKind.STARTED);
                execute = true;
            }
        }
        drainEvents(record);
        discard(discarded);
        if (!execute) return;
        Execution execution = new Execution(record);
        try {
            JobTask task;
            synchronized (record) {
                task = record.task;
            }
            if (task == null) return;
            JobTaskOutput output = task.execute(execution);
            JobTaskOutput effective;
            synchronized (record) {
                effective = record.commitCompleted ? record.committedOutput : output;
            }
            validateTaskOutput(record, effective);
            publishReadOnly(record, effective);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finishFailure(record, new JobError("job_execution_failed",
                    "The asynchronous job was interrupted without completing.", false,
                    Map.of("failure_type", boundedType(interrupted))));
        } catch (JobCancelled cancelled) {
            finishFailure(record, null);
        } catch (JobException typed) {
            finishFailure(record, typed.error());
        } catch (Exception failure) {
            finishFailure(record, new JobError("job_execution_failed",
                    "The asynchronous job failed during execution.", false,
                    Map.of("failure_type", boundedType(failure))));
        } catch (Error fatal) {
            finishFailure(record, new JobError("job_execution_failed",
                    "The asynchronous job failed during execution.", false,
                    Map.of("failure_type", boundedType(fatal))));
            throw fatal;
        } finally {
            synchronized (record) {
                record.task = null;
            }
        }
    }

    private void finishFailure(JobRecord record, JobError error) {
        JobTaskOutput committedOutput;
        try {
            synchronized (record) {
                if (record.state == JobState.SUCCEEDED) {
                    // A misbehaving adapter lease may throw while releasing after terminal
                    // publication. Terminal evidence and its advertised artifacts are immutable.
                    committedOutput = null;
                } else if (record.commitCompleted && record.committedOutput != null) {
                    committedOutput = record.committedOutput;
                } else {
                    committedOutput = null;
                    JobCommitFence observed = claimTerminalPublication(record);
                    if (observed == JobCommitFence.COMMIT_STARTED) {
                        record.fail(JobFailures.commitOutcomeUnknown(), clock.instant());
                    } else if (observed == JobCommitFence.CANCELLED
                            || record.state == JobState.CANCEL_PENDING) {
                        record.cancelled(clock.instant(),
                                "Cancellation completed; output was discarded.");
                    } else if (!record.state.terminal()) {
                        record.fail(error == null ? new JobError("job_execution_failed",
                                "The asynchronous job failed during execution.", false, Map.of())
                                : error, clock.instant());
                    }
                    if (record.state != JobState.SUCCEEDED) record.artifacts.releaseAll();
                }
            }
            if (committedOutput != null) completeSuccess(record, committedOutput, clock.instant());
        } finally {
            drainEvents(record);
        }
    }

    private JobCommitFence claimTerminalPublication(JobRecord record) {
        while (true) {
            JobCommitFence observed = record.fence.get();
            if (observed != JobCommitFence.OPEN) return observed;
            JobCommitFence claimed = closed
                    ? JobCommitFence.CANCELLED : JobCommitFence.PUBLICATION_STARTED;
            if (record.fence.compareAndSet(JobCommitFence.OPEN, claimed)) {
                return claimed;
            }
        }
    }

    private void validateTaskOutput(JobRecord record, JobTaskOutput output) {
        if (output == null || record.type.resultType() != output.discriminator()) {
            throw failure("job_result_invalid", "The job returned an invalid result type.", false);
        }
        List<JobArtifact.Reference> references;
        synchronized (record) {
            references = record.artifacts.references();
        }
        // Expiry renewal happens at completion. Size against the longest legal Instant text so
        // a commit cannot make the final envelope unpublishable by changing timestamp precision.
        List<JobArtifact.Reference> boundedReferences = references.stream()
                .map(reference -> new JobArtifact.Reference(reference.artifactId(),
                        reference.mediaType(), reference.sha256(), reference.bytes(),
                        reference.createdAt(), Instant.MAX.toString()))
                .toList();
        validateResultSize(new JobResult(output.discriminator(), output.structured(),
                boundedReferences, output.auditIncomplete()));
    }

    private void publishReadOnly(JobRecord record, JobTaskOutput output) throws Exception {
        synchronized (record) {
            if (record.state.terminal()) return;
        }
        try (JobCommitLease publicationLease = requireLease(
                record.publicationGuard.acquire())) {
            record.commitPermit.lock();
            try {
                synchronized (record) {
                    if (record.state.terminal()) return;
                    // The adapter-held lease keeps the validated dynamic authority and input
                    // identity stable through this publication linearization point.
                    validateTaskOutput(record, output);
                    JobCommitFence observed = claimTerminalPublication(record);
                    if (observed == JobCommitFence.COMMIT_STARTED) {
                        record.fail(JobFailures.commitOutcomeUnknown(), clock.instant());
                        record.artifacts.releaseAll();
                        return;
                    }
                    if (observed == JobCommitFence.CANCELLED
                            || record.state == JobState.CANCEL_PENDING) {
                        record.cancelled(clock.instant(),
                                "Late output was discarded after cancellation.");
                        record.artifacts.releaseAll();
                        return;
                    }
                    enqueueEventLocked(record, JobEventKind.PUBLICATION_STARTED);
                }
                completeSuccess(record, output, clock.instant());
            } finally {
                record.commitPermit.unlock();
            }
        } finally {
            drainEvents(record);
        }
    }

    /**
     * Delivers the prospective terminal audit fact before exposing the immutable terminal
     * descriptor. Audit delivery failure is therefore reflected in the first observable result.
     */
    private void completeSuccess(JobRecord record, JobTaskOutput output, Instant completed) {
        PreparedSuccess prepared;
        synchronized (record) {
            if (record.state.terminal()) return;
            prepared = prepareSuccessLocked(record, output, completed);
            enqueueSuccessTerminalLocked(record, completed);
        }
        drainEvents(record);
        synchronized (record) {
            JobResult result = output.auditIncomplete() || record.eventDelivery.incomplete()
                    ? prepared.auditIncomplete() : prepared.auditComplete();
            publishSuccess(record, result, completed);
            if (closed) record.artifacts.releaseAll();
        }
    }

    private PreparedSuccess prepareSuccessLocked(
            JobRecord record, JobTaskOutput output, Instant completed) {
        record.artifacts.renewExpiry(completed.plus(
                record.runtimeConfig.retention()));
        List<JobArtifact.Reference> references = record.artifacts.references();
        JobResult auditIncomplete = new JobResult(output.discriminator(), output.structured(),
                references, true);
        validateResultSize(auditIncomplete);
        JobResult auditComplete = output.auditIncomplete() ? auditIncomplete
                : new JobResult(output.discriminator(), output.structured(), references, false);
        validateResultSize(auditComplete);
        return new PreparedSuccess(auditComplete, auditIncomplete);
    }

    private void validateResultSize(JobResult result) {
        try {
            if (JSON.writeValueAsBytes(result).length > limits.resultBytes()) {
                throw failure("job_result_too_large",
                        "The job result exceeded its byte bound.", false);
            }
        } catch (java.io.IOException invalid) {
            throw failure("job_result_invalid", "The job returned an invalid result.", false);
        }
    }

    private void publishSuccess(JobRecord record, JobResult result, Instant completed) {
        if (record.state.terminal()) return;
        record.result = result;
        record.transition(JobState.SUCCEEDED, completed);
        record.phase = "succeeded";
        record.progress = "Job completed.";
        record.progressSequence++;
    }

    private long enqueueEventLocked(JobRecord record, JobEventKind kind) {
        return enqueueEventLocked(record, kind, false);
    }

    private long enqueueRequiredAuditIntentLocked(JobRecord record) {
        return enqueueEventLocked(record, JobEventKind.PUBLICATION_STARTED, true);
    }

    private long enqueueEventLocked(JobRecord record, JobEventKind kind,
            boolean requiredAuditIntent) {
        return enqueueEventLocked(record, kind, requiredAuditIntent, record.state,
                record.phase, record.progressSequence,
                record.error == null ? null : record.error.code(), clock.instant());
    }

    private void enqueueSuccessTerminalLocked(JobRecord record, Instant completed) {
        enqueueEventLocked(record, JobEventKind.TERMINAL, false, JobState.SUCCEEDED,
                "succeeded", record.progressSequence + 1, null, completed);
    }

    private long enqueueEventLocked(JobRecord record, JobEventKind kind,
            boolean requiredAuditIntent, JobState eventState, String eventPhase,
            long eventProgressSequence, String eventErrorCode, Instant occurredAt) {
        long sequence = ++record.eventSequence;
        JobEvent event = new JobEvent(sequence, kind, record.jobId,
                occurredAt.toString(), record.owner.workspaceId(),
                record.owner.ownerFingerprint(), record.owner.principalFingerprint(),
                record.owner.clientFingerprint(), record.owner.grantFingerprint(), record.type,
                eventState, record.identity.identityDigest(), record.requiredCapabilities,
                eventPhase, eventProgressSequence,
                record.progressUpdates, record.progressEventsEmitted,
                record.progressEventsSuppressed,
                elapsedMillis(record.createdAt, occurredAt),
                record.artifacts.references().size(),
                record.artifacts.references().stream()
                        .mapToLong(JobArtifact.Reference::bytes).sum(),
                record.fence.get() == JobCommitFence.CANCELLED, record.cancellationEffective,
                record.fence.get() == JobCommitFence.COMMIT_STARTED,
                eventErrorCode);
        record.eventDelivery.enqueue(event, requiredAuditIntent);
        return sequence;
    }

    private void drainEvents(JobRecord record) {
        record.eventDelivery.drain(eventSink);
    }

    private void drainEventsIfAvailable(JobRecord record) {
        record.eventDelivery.drainIfAvailable(eventSink);
    }

    private JobRecord requireOwned(JobOwner owner, String jobId) {
        Objects.requireNonNull(owner, "owner");
        if (jobId == null || jobId.length() > 64) throw unknownJob();
        synchronized (this) {
            ensureOpen();
            cleanupExpired();
            JobRecord record = registry.owned(owner, jobId);
            if (record == null) throw unknownJob();
            return record;
        }
    }

    private int cancelMatching(Predicate<JobOwner> match) {
        List<JobRecord> selected;
        synchronized (this) {
            if (closed) return 0;
            cleanupExpired();
            selected = registry.matchingActive(match);
        }
        int requested = 0;
        for (JobRecord record : selected) {
            JobCancelResult result = cancel(record.owner, record.jobId);
            if (result.outcome() == JobCancelOutcome.CANCEL_REQUESTED
                    || result.outcome() == JobCancelOutcome.CANCELLED) requested++;
        }
        return requested;
    }

    private void cleanupExpired() {
        registry.cleanupExpired(clock.instant());
    }

    private String id() {
        UUID value = ids.get();
        if (value == null) throw new IllegalStateException("job id generator returned null");
        return value.toString();
    }

    private String uniqueJobId() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = id();
            if (!registry.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("job id generator produced repeated identifiers");
    }

    private long nextAdmissionToken() {
        for (int attempt = 0; attempt < 8; attempt++) {
            long candidate = ++nextAdmissionToken;
            if (candidate == 0) continue;
            if (!admissions.containsKey(candidate)) return candidate;
        }
        throw new IllegalStateException("job admission token space is exhausted");
    }

    private long quotaReservations(String principalFingerprint) {
        return admissions.values().stream()
                .filter(admission -> admission.quotaReserved)
                .filter(admission -> principalFingerprint == null
                        || admission.owner.principalFingerprint()
                                .equals(principalFingerprint))
                .count();
    }

    private void consumeAdmission(
            JobSubmission submission, JobAdmission admission) {
        if (admission.service != this
                || !admission.owner.equals(submission.owner())
                || admission.type != submission.type()
                || !admission.idempotencyKey.equals(submission.idempotencyKey())
                || !admission.finished.compareAndSet(false, true)
                || admissions.remove(admission.token) != admission) {
            throw failure("job_admission_invalid",
                    "The pre-capture job reservation is invalid or already consumed.", false);
        }
    }

    synchronized void releaseAdmission(JobAdmission admission) {
        if (admission == null || admission.service != this
                || !admission.finished.compareAndSet(false, true)) {
            return;
        }
        admissions.remove(admission.token, admission);
    }

    private void ensureOpen() {
        if (closed) throw unknownJob();
    }

    private static String boundedType(Throwable failure) {
        String value = failure.getClass().getSimpleName();
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private static long elapsedMillis(Instant started, Instant finished) {
        if (finished.isBefore(started)) return 0;
        try {
            return Duration.between(started, finished).toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static JobException unknownJob() {
        return failure("unknown_job", "The job is unavailable.", false);
    }

    private static JobException unknownArtifact() {
        return failure("unknown_job_artifact", "The job artifact is unavailable.", false);
    }

    private static JobException failure(String code, String message, boolean retryable) {
        return JobFailures.effectsPrevented(code, message, retryable);
    }

    private static JobCommitLease requireLease(JobCommitLease lease) {
        if (lease == null) {
            throw failure("job_guard_lease_missing",
                    "The adapter did not retain a validated publication guard.", false);
        }
        return lease;
    }

    private static void discard(JobTask task) {
        if (task == null) return;
        try {
            task.discard();
        } catch (RuntimeException | LinkageError ignored) {
            // Rejected captures are no longer observable; cleanup cannot replace the typed result.
        }
    }

    private static void requestCancellation(JobTask task) {
        if (task == null) return;
        try {
            task.requestCancellation();
        } catch (RuntimeException | LinkageError ignored) {
            // The monotonic tombstone and scheduler interrupt remain authoritative.
        }
    }

    private static void requestInterrupt(JobTaskHandle handle) {
        if (handle == null) return;
        try {
            handle.cancel(true);
        } catch (RuntimeException ignored) {
            // The monotonic tombstone is authoritative; interruption is best-effort acceleration.
        }
    }

    /** Defers adapter-owned capture disposal until the service monitor has been released. */
    private static final class TaskCleanup implements Runnable {
        private JobTask task;

        private TaskCleanup(JobTask task) {
            this.task = Objects.requireNonNull(task, "task");
        }

        private void clear() {
            task = null;
        }

        private void replace(JobTask replacement) {
            task = replacement;
        }

        @Override
        public void run() {
            JobTask captured = task;
            task = null;
            discard(captured);
        }
    }

    private final class Execution implements JobExecution {
        private final JobRecord record;

        private Execution(JobRecord record) {
            this.record = record;
        }

        @Override
        public void progress(String phase, String message) {
            validateProgress(phase, message);
            synchronized (record) {
                requireRunning();
                record.phase = phase;
                record.progress = message;
                record.progressSequence++;
                record.progressUpdates++;
                if (record.progressEventsEmitted < MAX_PROGRESS_AUDIT_EVENTS) {
                    record.progressEventsEmitted++;
                    enqueueEventLocked(record, JobEventKind.PROGRESS);
                } else {
                    record.progressEventsSuppressed++;
                }
            }
            drainEvents(record);
        }

        @Override
        public boolean cancellationRequested() {
            return record.fence.get() == JobCommitFence.CANCELLED
                    || Thread.currentThread().isInterrupted();
        }

        @Override
        public void checkCancelled() throws InterruptedException {
            if (cancellationRequested()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                throw new JobCancelled();
            }
        }

        @Override
        public JobArtifact.Reference stageArtifact(String mediaType, byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            synchronized (record) {
                requireRunning();
                if (closed) throw new JobCancelled();
                if (cancellationRequested()) throw new JobCancelled();
                Instant now = clock.instant();
                return record.artifacts.stage(id(), record.jobId, mediaType, now,
                        now.plus(record.runtimeConfig.retention()), bytes);
            }
        }

        @Override
        public JobTaskOutput withCommitPermit(JobTaskOutput preparedResult, JobPreCommitGuard guard,
                JobCommitAction action) throws Exception {
            Objects.requireNonNull(guard, "guard");
            Objects.requireNonNull(action, "action");
            validateTaskOutput(record, preparedResult);
            // Adapter guard acquisition may block; do it before taking the runtime's short
            // exclusive section. Both leases remain held across audit intent, the fence CAS, and
            // the irreversible action, so no authority/revision gap can open after validation.
            try (JobCommitLease publicationLease = requireLease(
                        record.publicationGuard.acquire());
                    JobCommitLease destinationLease = requireLease(guard.acquire())) {
                long auditIntentSequence;
                synchronized (record) {
                    requireRunning();
                    auditIntentSequence = enqueueRequiredAuditIntentLocked(record);
                }
                drainEvents(record);
                synchronized (record) {
                    if (record.eventDelivery.requiredDeliveryFailed(auditIntentSequence)) {
                        throw failure("job_audit_intent_failed",
                                "The pre-commit audit intent could not be recorded.", false);
                    }
                }
                record.commitPermit.lock();
                try {
                    synchronized (record) {
                        requireRunning();
                        // Staging uses this monitor too: validation and the fence transition are
                        // one atomic freeze while the adapter leases retain external validity.
                        validateTaskOutput(record, preparedResult);
                        if (closed || !record.fence.compareAndSet(
                                JobCommitFence.OPEN, JobCommitFence.COMMIT_STARTED)) {
                            if (record.fence.get() == JobCommitFence.CANCELLED || closed) {
                                throw new JobCancelled();
                            }
                            throw failure("job_commit_already_started",
                                    "The job commit permit was already consumed.", false);
                        }
                        record.phase = "commit";
                        record.progress = "Commit permit acquired.";
                        record.progressSequence++;
                    }
                    final JobTaskOutput committed;
                    try {
                        committed = action.commit();
                    } catch (Error fatal) {
                        synchronized (record) {
                            record.fail(JobFailures.commitOutcomeUnknown(), clock.instant());
                        }
                        throw fatal;
                    } catch (Exception failure) {
                        synchronized (record) {
                            record.fail(JobFailures.commitOutcomeUnknown(), clock.instant());
                            record.artifacts.releaseAll();
                        }
                        throw new JobException(JobFailures.commitOutcomeUnknown());
                    }
                    JobTaskOutput effective = committed == null ? preparedResult : committed;
                    try {
                        validateTaskOutput(record, effective);
                    } catch (JobException invalidPostCommitResult) {
                        effective = preparedResult.withAuditIncomplete();
                        validateTaskOutput(record, effective);
                    }
                    synchronized (record) {
                        record.commitCompleted = true;
                        record.committedOutput = effective;
                    }
                    try {
                        completeSuccess(record, effective, clock.instant());
                    } catch (JobException publicationFailure) {
                        synchronized (record) {
                            record.fail(new JobError("job_publication_failed_after_commit",
                                    "The committed job result could not be published.", false,
                                    Map.of("commit_completed", true,
                                            "failure_code", publicationFailure.error().code())),
                                    clock.instant());
                            record.artifacts.releaseAll();
                        }
                        throw publicationFailure;
                    }
                    return effective;
                } finally {
                    record.commitPermit.unlock();
                }
            } finally {
                drainEvents(record);
            }
        }

        private void requireRunning() {
            JobCommitFence observed = record.fence.get();
            if (closed || record.state != JobState.RUNNING
                    || observed == JobCommitFence.CANCELLED) {
                throw new JobCancelled();
            }
            if (observed != JobCommitFence.OPEN) {
                throw failure("job_publication_started",
                        "Job output publication has already started.", false);
            }
        }
    }

    private void validateProgress(String phase, String message) {
        if (phase == null || !phase.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("job progress phase is invalid");
        }
        if (message == null || message.isBlank()
                || message.getBytes(StandardCharsets.UTF_8).length > limits.progressBytes()) {
            throw new IllegalArgumentException("job progress message is invalid");
        }
    }

    private record PreparedSuccess(
            JobResult auditComplete, JobResult auditIncomplete) { }

    private static final class JobCancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
