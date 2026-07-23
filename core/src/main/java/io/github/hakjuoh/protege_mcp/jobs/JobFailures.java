package io.github.hakjuoh.protege_mcp.jobs;

import java.util.Map;

/** Shared stable failures for job orchestration and bounded storage components. */
final class JobFailures {
    private JobFailures() {
    }

    static JobException effectsPrevented(String code, String message, boolean retryable) {
        return new JobException(code, message, Map.of("effects_prevented", true), retryable);
    }

    static JobError commitOutcomeUnknown() {
        return new JobError("job_commit_outcome_unknown",
                "The job failed after commit started; check destination state before retry.",
                false, Map.of("commit_started", true,
                        "retry_requires_state_check", true));
    }
}
