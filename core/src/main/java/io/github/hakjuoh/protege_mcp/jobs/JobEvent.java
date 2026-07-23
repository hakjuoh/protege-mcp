package io.github.hakjuoh.protege_mcp.jobs;

import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Secret-free, monotonically sequenced lifecycle event for asynchronous audit adapters. */
public record JobEvent(
        @JsonProperty("sequence") long sequence,
        @JsonProperty("kind") JobEventKind kind,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("owner_fingerprint") String ownerFingerprint,
        @JsonProperty("principal_fingerprint") String principalFingerprint,
        @JsonProperty("client_fingerprint") String clientFingerprint,
        @JsonProperty("grant_fingerprint") String grantFingerprint,
        @JsonProperty("type") JobType type,
        @JsonProperty("state") JobState state,
        @JsonProperty("input_identity_digest") String inputIdentityDigest,
        @JsonProperty("required_capabilities") Set<String> requiredCapabilities,
        @JsonProperty("phase") String phase,
        @JsonProperty("progress_sequence") long progressSequence,
        @JsonProperty("progress_updates") long progressUpdates,
        @JsonProperty("progress_events_emitted") int progressEventsEmitted,
        @JsonProperty("progress_events_suppressed") long progressEventsSuppressed,
        @JsonProperty("elapsed_millis") long elapsedMillis,
        @JsonProperty("artifact_count") int artifactCount,
        @JsonProperty("artifact_bytes") long artifactBytes,
        @JsonProperty("cancellation_requested") boolean cancellationRequested,
        @JsonProperty("cancellation_effective") boolean cancellationEffective,
        @JsonProperty("commit_started") boolean commitStarted,
        @JsonProperty("error_code") String errorCode) {

    public JobEvent {
        if (sequence < 1) throw new IllegalArgumentException("job event sequence is invalid");
        Objects.requireNonNull(kind, "kind");
        jobId = JobValidators.requireUuid(jobId, "job id");
        JobValidators.requireInstant(occurredAt, "job event time");
        workspaceId = JobValidators.requireUuid(workspaceId, "workspace id");
        JobHashes.requireDigest(ownerFingerprint, "owner fingerprint");
        JobHashes.requireDigest(principalFingerprint, "principal fingerprint");
        JobHashes.requireDigest(clientFingerprint, "client fingerprint");
        JobHashes.requireDigest(grantFingerprint, "grant fingerprint");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        JobHashes.requireDigest(inputIdentityDigest, "input identity digest");
        requiredCapabilities = JobValidators.requireCapabilities(requiredCapabilities);
        phase = JobValidators.requirePhase(phase, "job event phase");
        if (progressSequence < 0) {
            throw new IllegalArgumentException("job event progress sequence is invalid");
        }
        if (progressUpdates < 0 || progressEventsEmitted < 0
                || progressEventsEmitted > JobService.MAX_PROGRESS_AUDIT_EVENTS
                || progressEventsSuppressed < 0 || elapsedMillis < 0
                || artifactCount < 0 || artifactCount > JobService.MAX_ARTIFACTS_PER_JOB
                || artifactBytes < 0 || artifactBytes > JobService.MAX_JOB_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("job event summary is invalid");
        }
        if (errorCode != null && !errorCode.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("job event error code is invalid");
        }
        if (cancellationEffective && !cancellationRequested
                || commitStarted && cancellationRequested) {
            throw new IllegalArgumentException("job event cancellation flags are inconsistent");
        }
    }
}
