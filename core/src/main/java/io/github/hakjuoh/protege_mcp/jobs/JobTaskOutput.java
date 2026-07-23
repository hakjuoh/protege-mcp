package io.github.hakjuoh.protege_mcp.jobs;

import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;

/** Immutable worker output whose closed discriminator must match the submitted job type. */
public record JobTaskOutput(JobResultType discriminator, Map<String, Object> structured,
        boolean auditIncomplete) {
    public JobTaskOutput {
        if (discriminator == null) throw new IllegalArgumentException(
                "task result discriminator is required");
        structured = ImmutableJson.resultMap(structured == null ? Map.of() : structured);
    }

    public JobTaskOutput withAuditIncomplete() {
        return auditIncomplete ? this : new JobTaskOutput(discriminator, structured, true);
    }
}
