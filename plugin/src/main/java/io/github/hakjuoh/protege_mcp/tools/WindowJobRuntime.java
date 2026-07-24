package io.github.hakjuoh.protege_mcp.tools;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.hakjuoh.protege_mcp.jobs.JobRuntimeConfig;
import io.github.hakjuoh.protege_mcp.jobs.JobAdmission;
import io.github.hakjuoh.protege_mcp.jobs.JobOwner;
import io.github.hakjuoh.protege_mcp.jobs.JobService;
import io.github.hakjuoh.protege_mcp.jobs.JobStartResult;
import io.github.hakjuoh.protege_mcp.jobs.JobSubmission;
import io.github.hakjuoh.protege_mcp.jobs.JobType;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** One bounded two-worker asynchronous runtime owned by exactly one live Protégé window. */
final class WindowJobRuntime implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final JobService jobs;

    WindowJobRuntime(WorkspaceAudit audit) {
        AtomicInteger ids = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task,
                    "protege-mcp-job-" + ids.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), factory,
                new ThreadPoolExecutor.AbortPolicy());
        jobs = new JobService(Clock.systemUTC(), JobRuntimeConfig.defaults(), task -> {
            Future<?> future = workers.submit(task);
            return future::cancel;
        }, audit::jobEvent);
    }

    JobService service() {
        return jobs;
    }

    synchronized JobAdmission reserve(ProjectPolicy policy, JobOwner owner,
            JobType type, String idempotencyKey) {
        Settings settings = Settings.from(policy);
        int current = workers.getCorePoolSize();
        if (settings.workers() > current) {
            workers.setMaximumPoolSize(settings.workers());
            workers.setCorePoolSize(settings.workers());
        } else if (settings.workers() < current) {
            workers.setCorePoolSize(settings.workers());
            workers.setMaximumPoolSize(settings.workers());
        }
        return jobs.reserve(owner, type, idempotencyKey, settings.config());
    }

    JobStartResult start(JobSubmission submission, JobAdmission admission) {
        return jobs.start(submission, admission);
    }

    @Override
    public void close() {
        jobs.close();
        // JobService selectively interrupts only OPEN jobs. A worker that already won the
        // commit/publication fence must be allowed to finish its irreversible section.
        workers.shutdown();
        try {
            if (!workers.awaitTermination(JobService.CANCELLATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    record Settings(int workers, JobRuntimeConfig config) {
        static Settings from(ProjectPolicy policy) {
            if (policy == null || !policy.valid()) {
                throw invalidPolicy("A valid project policy is required for jobs.");
            }
            Object raw = policy.effective().get("jobs");
            if (raw == null) return new Settings(2, JobRuntimeConfig.defaults());
            if (!(raw instanceof Map<?, ?> jobs)) {
                throw invalidPolicy("The effective jobs policy is not an object.");
            }
            try {
                Set<JobType> allowed = new LinkedHashSet<>();
                Object rawAllowed = jobs.get("allowed_types");
                if (!(rawAllowed instanceof List<?> values)) {
                    throw new IllegalArgumentException("allowed_types");
                }
                for (Object value : values) {
                    if (!(value instanceof String id)) {
                        throw new IllegalArgumentException("allowed_types");
                    }
                    allowed.add(JobType.fromId(id));
                }
                int workerCount = integer(jobs, "workers");
                if (workerCount < 1 || workerCount > 2) {
                    throw new IllegalArgumentException("workers");
                }
                JobRuntimeConfig config = new JobRuntimeConfig(
                        allowed, integer(jobs, "queue_capacity"),
                        integer(jobs, "active_per_principal"),
                        integer(jobs, "retained_per_principal"),
                        integer(jobs, "retained_per_backend"),
                        Duration.ofSeconds(integer(jobs, "retention_seconds")));
                return new Settings(workerCount, config);
            } catch (IllegalArgumentException invalid) {
                throw invalidPolicy(
                        "The effective jobs policy is outside product bounds.");
            }
        }

        private static int integer(Map<?, ?> values, String key) {
            Object value = values.get(key);
            if (!(value instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException(key);
            }
            return number.intValue();
        }

        private static ToolArgException invalidPolicy(String message) {
            return new ToolArgException("job_policy_invalid", message,
                    Map.of("effects_prevented", true), false);
        }
    }
}
