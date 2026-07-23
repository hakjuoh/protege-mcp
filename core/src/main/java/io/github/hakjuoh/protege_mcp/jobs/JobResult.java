package io.github.hakjuoh.protege_mcp.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;

/** Immutable typed result published only after the final cancellation fence. */
public record JobResult(@JsonProperty("discriminator") JobResultType discriminator,
        @JsonProperty("structured") Map<String, Object> structured,
        @JsonProperty("artifacts") List<JobArtifact.Reference> artifacts,
        @JsonProperty("audit_incomplete") boolean auditIncomplete) {
    public JobResult {
        if (discriminator == null) throw new IllegalArgumentException(
                "job result discriminator is required");
        structured = ImmutableJson.resultMap(structured == null ? Map.of() : structured);
        List<JobArtifact.Reference> copy = new ArrayList<>(
                artifacts == null ? List.of() : artifacts);
        if (copy.size() > JobService.MAX_ARTIFACTS_PER_JOB
                || copy.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("job result artifact list is invalid");
        }
        artifacts = List.copyOf(copy);
    }
}
