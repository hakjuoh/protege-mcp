package io.github.hakjuoh.protege_mcp.jobs;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Newest-first owner-scoped page with an opaque stable-anchor cursor. */
public record JobPage(@JsonProperty("jobs") List<JobDescriptor> jobs,
        @JsonProperty("next_cursor") String nextCursor) {
    public JobPage {
        jobs = List.copyOf(jobs);
        if (jobs.size() > 100 || nextCursor != null && nextCursor.length() > 512) {
            throw new IllegalArgumentException("job page is invalid");
        }
    }
}
