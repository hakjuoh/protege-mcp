package io.github.hakjuoh.protege_mcp.jobs;

import java.util.Map;

/** Internal typed failure that becomes a stable public job error. */
public final class JobException extends RuntimeException {
    private final JobError error;

    public JobException(String code, String message, boolean retryable) {
        this(code, message, Map.of(), retryable);
    }

    public JobException(String code, String message, Map<String, Object> details,
            boolean retryable) {
        this(new JobError(code, message, retryable, details));
    }

    JobException(JobError error) {
        super(error.message());
        this.error = error;
    }

    public JobError error() {
        return error;
    }
}
