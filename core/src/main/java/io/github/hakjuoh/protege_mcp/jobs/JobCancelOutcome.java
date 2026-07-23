package io.github.hakjuoh.protege_mcp.jobs;

import com.fasterxml.jackson.annotation.JsonValue;

/** Stable public outcome of an idempotent cancellation request. */
public enum JobCancelOutcome {
    CANCEL_REQUESTED("cancel_requested"),
    CANCELLED("cancelled"),
    COMMIT_IN_PROGRESS("commit_in_progress"),
    ALREADY_TERMINAL("already_terminal");

    private final String id;

    JobCancelOutcome(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }
}
