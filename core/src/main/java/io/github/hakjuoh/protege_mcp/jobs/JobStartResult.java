package io.github.hakjuoh.protege_mcp.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Accepted job or an idempotently recovered existing job. */
public record JobStartResult(@JsonProperty("job") JobDescriptor job,
        @JsonProperty("reused") boolean reused) {
    public JobStartResult {
        if (job == null) throw new IllegalArgumentException("job is required");
    }
}
