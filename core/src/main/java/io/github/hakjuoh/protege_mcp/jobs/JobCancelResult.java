package io.github.hakjuoh.protege_mcp.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Public cancellation response with the immutable observed snapshot. */
public record JobCancelResult(@JsonProperty("job") JobDescriptor job,
        @JsonProperty("outcome") JobCancelOutcome outcome) {
    public JobCancelResult {
        if (job == null || outcome == null) {
            throw new IllegalArgumentException("job cancellation result is incomplete");
        }
    }
}
