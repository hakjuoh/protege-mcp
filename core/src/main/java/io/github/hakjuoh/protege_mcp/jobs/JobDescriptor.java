package io.github.hakjuoh.protege_mcp.jobs;

import java.util.List;
import java.util.Set;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

/** Immutable public snapshot of one owner-scoped job. */
public record JobDescriptor(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("owner_fingerprint") String ownerFingerprint,
        @JsonProperty("principal_fingerprint") String principalFingerprint,
        @JsonProperty("client_fingerprint") String clientFingerprint,
        @JsonProperty("grant_fingerprint") String grantFingerprint,
        @JsonProperty("type") JobType type,
        @JsonProperty("state") JobState state,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("completed_at") String completedAt,
        @JsonProperty("base_revision") ModelRevision baseRevision,
        @JsonProperty("policy_digest") String policyDigest,
        @JsonProperty("phase") String phase,
        @JsonProperty("progress_message") String progressMessage,
        @JsonProperty("progress_sequence") long progressSequence,
        @JsonProperty("cancellation_requested") boolean cancellationRequested,
        @JsonProperty("cancellation_effective") boolean cancellationEffective,
        @JsonProperty("commit_started") boolean commitStarted,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("required_capabilities") Set<String> requiredCapabilities,
        @JsonProperty("input_identity") JobInputIdentity inputIdentity,
        @JsonProperty("result_discriminator") JobResultType resultDiscriminator,
        @JsonProperty("result") JobResult result,
        @JsonProperty("error") JobError error) {

    public JobDescriptor {
        jobId = JobValidators.requireUuid(jobId, "job id");
        workspaceId = JobValidators.requireUuid(workspaceId, "workspace id");
        ownerFingerprint = JobHashes.requireDigest(ownerFingerprint, "owner fingerprint");
        principalFingerprint = JobHashes.requireDigest(
                principalFingerprint, "principal fingerprint");
        clientFingerprint = JobHashes.requireDigest(clientFingerprint, "client fingerprint");
        grantFingerprint = JobHashes.requireDigest(grantFingerprint, "grant fingerprint");
        if (type == null || state == null || resultDiscriminator == null
                || type.resultType() != resultDiscriminator) {
            throw new IllegalArgumentException("job result discriminator does not match job type");
        }
        Instant created = JobValidators.requireInstant(createdAt, "job creation time");
        Instant started = JobValidators.optionalInstant(startedAt, "job start time");
        Instant completed = JobValidators.optionalInstant(completedAt, "job completion time");
        if (started != null && started.isBefore(created)
                || completed != null && completed.isBefore(
                        started == null ? created : started)) {
            throw new IllegalArgumentException("job timestamps are not monotonic");
        }
        if (baseRevision == null || inputIdentity == null
                || !workspaceId.equals(baseRevision.workspaceId())
                || !workspaceId.equals(inputIdentity.modelRevision().workspaceId())
                || !baseRevision.equals(inputIdentity.modelRevision())) {
            throw new IllegalArgumentException("job input identity does not match descriptor");
        }
        policyDigest = JobHashes.requireDigest(policyDigest, "policy digest");
        if (!policyDigest.equals(inputIdentity.policyDigest())) {
            throw new IllegalArgumentException("job policy digest does not match input identity");
        }
        phase = JobValidators.requirePhase(phase, "job phase");
        progressMessage = JobValidators.requireProgress(progressMessage);
        if (progressSequence < 0) {
            throw new IllegalArgumentException("job progress sequence is invalid");
        }
        idempotencyKey = JobValidators.requireIdempotencyKey(idempotencyKey);
        requiredCapabilities = JobValidators.requireCapabilities(requiredCapabilities);
        if (state.terminal() != (completedAt != null)) {
            throw new IllegalArgumentException("terminal timestamp does not match job state");
        }
        if (state == JobState.QUEUED && startedAt != null
                || (state == JobState.RUNNING || state == JobState.CANCEL_PENDING
                        || state == JobState.SUCCEEDED) && startedAt == null) {
            throw new IllegalArgumentException("start timestamp does not match job state");
        }
        if (cancellationEffective != (state == JobState.CANCELLED)
                || cancellationEffective && !cancellationRequested
                || commitStarted && cancellationRequested) {
            throw new IllegalArgumentException("job cancellation flags are inconsistent");
        }
        if (state == JobState.SUCCEEDED && result == null
                || state != JobState.SUCCEEDED && result != null) {
            throw new IllegalArgumentException("job result does not match job state");
        }
        if (result != null && result.discriminator() != resultDiscriminator) {
            throw new IllegalArgumentException("published result discriminator does not match job");
        }
        if (state == JobState.FAILED && error == null
                || state != JobState.FAILED && error != null) {
            throw new IllegalArgumentException("job error does not match job state");
        }
    }

    public List<JobArtifact.Reference> artifacts() {
        return result == null ? List.of() : result.artifacts();
    }
}
