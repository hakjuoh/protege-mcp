package io.github.hakjuoh.protege_mcp.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Private artifact ownership and byte accounting for one job.
 *
 * <p>The owning job record serializes access to an instance. The shared budget is atomic because
 * different jobs may stage and release artifacts concurrently.</p>
 */
final class JobArtifactStore {
    private final JobArtifactLimits limits;
    private final JobArtifactBudget budget;
    private final List<JobArtifact> artifacts = new ArrayList<>();

    JobArtifactStore(JobArtifactLimits limits, JobArtifactBudget budget) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    JobArtifact.Reference stage(String artifactId, String jobId, String mediaType,
            Instant createdAt, Instant expiresAt, byte[] bytes) {
        if (bytes.length > limits.artifactBytes()) {
            throw failure("job_artifact_too_large",
                    "A job artifact exceeded its byte bound.", false);
        }
        long retained = artifacts.stream().mapToLong(JobArtifact::bytes).sum();
        if (artifacts.size() >= limits.artifactsPerJob()
                || bytes.length > limits.jobArtifactBytes() - retained) {
            throw failure("job_artifact_quota_exceeded",
                    "The per-job artifact quota is exhausted.", false);
        }
        budget.reserve(bytes.length, limits.backendArtifactBytes());
        try {
            JobArtifact artifact = new JobArtifact(
                    artifactId, jobId, mediaType, createdAt, expiresAt, bytes);
            artifacts.add(artifact);
            return artifact.reference();
        } catch (RuntimeException | Error failure) {
            budget.release(bytes.length);
            throw failure;
        }
    }

    JobArtifact require(String jobId, String artifactId, Instant now) {
        for (JobArtifact artifact : artifacts) {
            if (artifact.artifactId().equals(artifactId)
                    && artifact.jobId().equals(jobId)
                    && now.isBefore(artifact.expiresAt())
                    && JobHashes.digest(artifact.copyBytes()).equals(artifact.sha256())) {
                return artifact.detachedCopy();
            }
        }
        return null;
    }

    List<JobArtifact.Reference> references() {
        return artifacts.stream().map(JobArtifact::reference).toList();
    }

    void renewExpiry(Instant expiresAt) {
        for (int index = 0; index < artifacts.size(); index++) {
            JobArtifact previous = artifacts.get(index);
            JobArtifact renewed = previous.withExpiry(expiresAt);
            previous.erase();
            artifacts.set(index, renewed);
        }
    }

    void cleanupExpired(Instant now) {
        Iterator<JobArtifact> iterator = artifacts.iterator();
        long released = 0;
        while (iterator.hasNext()) {
            JobArtifact artifact = iterator.next();
            if (!now.isBefore(artifact.expiresAt())) {
                released += artifact.bytes();
                artifact.erase();
                iterator.remove();
            }
        }
        budget.release(released);
    }

    void releaseAll() {
        long released = artifacts.stream().mapToLong(JobArtifact::bytes).sum();
        artifacts.forEach(JobArtifact::erase);
        artifacts.clear();
        budget.release(released);
    }

    private static JobException failure(String code, String message, boolean retryable) {
        return JobFailures.effectsPrevented(code, message, retryable);
    }
}

/** Shared bounded byte reservation across every artifact store in one runtime. */
final class JobArtifactBudget {
    private final AtomicLong retainedBytes = new AtomicLong();

    void reserve(long bytes, long maximum) {
        while (true) {
            long current = retainedBytes.get();
            if (bytes > maximum - current) {
                throw JobFailures.effectsPrevented("job_artifact_backend_quota_exceeded",
                        "The backend artifact byte quota is exhausted.",
                        true);
            }
            if (retainedBytes.compareAndSet(current, current + bytes)) return;
        }
    }

    void release(long bytes) {
        if (bytes > 0) retainedBytes.addAndGet(-bytes);
    }

    long retainedBytes() {
        return retainedBytes.get();
    }
}
