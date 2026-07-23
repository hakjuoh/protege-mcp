package io.github.hakjuoh.protege_mcp.jobs;

import java.util.EnumSet;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/** Normative monotonic state machine for public jobs. */
public enum JobState {
    QUEUED("queued"),
    RUNNING("running"),
    CANCEL_PENDING("cancel_pending"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String id;

    JobState(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(JobState next) {
        if (next == null || next == this || terminal()) return false;
        return switch (this) {
            case QUEUED -> Set.of(RUNNING, CANCELLED, FAILED).contains(next);
            case RUNNING -> Set.of(SUCCEEDED, FAILED, CANCEL_PENDING, CANCELLED).contains(next);
            case CANCEL_PENDING -> next == CANCELLED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    public static Set<JobState> terminalStates() {
        return EnumSet.of(SUCCEEDED, FAILED, CANCELLED);
    }
}
