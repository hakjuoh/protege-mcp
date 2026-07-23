package io.github.hakjuoh.protege_mcp.jobs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Backend-local admission, idempotency, ownership, ordering, and retention index.
 *
 * <p>The owning {@link JobService} serializes registry calls; workers synchronize only on their
 * individual {@link JobRecord}.</p>
 */
final class JobRegistry {
    private final Map<String, JobRecord> jobs = new LinkedHashMap<>();

    void admit(JobSubmission submission, JobRuntimeConfig config) {
        admit(submission.owner(), config, 0, 0);
    }

    void admit(JobOwner owner, JobRuntimeConfig config,
            long reservedForPrincipal, long reservedForBackend) {
        String principal = owner.principalFingerprint();
        long retained = jobs.values().stream()
                .filter(record -> record.owner.principalFingerprint().equals(principal)).count();
        long active = jobs.values().stream()
                .filter(record -> record.owner.principalFingerprint().equals(principal))
                .filter(record -> !record.state.terminal()).count();
        if (active + reservedForPrincipal >= config.activePerPrincipal()) {
            throw failure("job_active_quota_exceeded",
                    "The active job quota for this principal is exhausted.", true);
        }
        if (retained + reservedForPrincipal >= config.retainedPerPrincipal()) {
            throw failure("job_retention_quota_exceeded",
                    "The retained job quota for this principal is exhausted.", true);
        }
        if (jobs.size() + reservedForBackend >= config.retainedPerBackend()) {
            throw failure("job_backend_quota_exceeded",
                    "The backend job retention quota is exhausted.", true);
        }
    }

    long queuedCount() {
        return jobs.values().stream().filter(record -> record.state == JobState.QUEUED).count();
    }

    JobRecord idempotent(JobSubmission submission, Instant now) {
        return idempotent(submission.owner(), submission.type(),
                submission.idempotencyKey(), now);
    }

    JobRecord idempotent(JobOwner owner, JobType type, String idempotencyKey, Instant now) {
        Instant floor = now.minus(JobService.IDEMPOTENCY_WINDOW);
        return jobs.values().stream()
                .filter(record -> record.owner.equals(owner))
                .filter(record -> record.type == type)
                .filter(record -> record.idempotencyKey.equals(idempotencyKey))
                .filter(record -> record.createdAt.isAfter(floor))
                .max(Comparator.comparing(record -> record.createdAt)).orElse(null);
    }

    void add(JobRecord record) {
        if (jobs.putIfAbsent(record.jobId, record) != null) {
            throw new IllegalStateException("duplicate job id");
        }
    }

    boolean contains(String jobId) {
        return jobs.containsKey(jobId);
    }

    JobRecord owned(JobOwner owner, String jobId) {
        JobRecord record = jobs.get(jobId);
        return record != null && record.owner.equals(owner) ? record : null;
    }

    List<JobRecord> ownedNewestFirst(JobOwner owner) {
        return jobs.values().stream()
                .filter(record -> record.owner.equals(owner))
                .sorted(Comparator.comparing((JobRecord value) -> value.createdAt).reversed()
                        .thenComparing(Comparator.comparing(
                                (JobRecord value) -> value.jobId).reversed()))
                .toList();
    }

    List<JobRecord> matchingActive(Predicate<JobOwner> match) {
        return jobs.values().stream()
                .filter(record -> match.test(record.owner))
                .filter(record -> !record.state.terminal()).toList();
    }

    void cleanupExpired(Instant now) {
        List<String> expired = new ArrayList<>();
        for (JobRecord record : jobs.values()) {
            synchronized (record) {
                if (record.state.terminal()) {
                    record.artifacts.cleanupExpired(now);
                    Instant retentionEnd = record.completedAt.plus(
                            record.runtimeConfig.retention());
                    Instant idempotencyEnd =
                            record.createdAt.plus(JobService.IDEMPOTENCY_WINDOW);
                    Instant visibleUntil = retentionEnd.isAfter(idempotencyEnd)
                            ? retentionEnd : idempotencyEnd;
                    if (!now.isBefore(visibleUntil)) expired.add(record.jobId);
                }
            }
        }
        for (String id : expired) {
            JobRecord record = jobs.remove(id);
            if (record != null) {
                synchronized (record) {
                    record.artifacts.releaseAll();
                }
            }
        }
    }

    List<JobRecord> clear() {
        List<JobRecord> current = List.copyOf(jobs.values());
        jobs.clear();
        return current;
    }

    private static JobException failure(String code, String message, boolean retryable) {
        return JobFailures.effectsPrevented(code, message, retryable);
    }
}
