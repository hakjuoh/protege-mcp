package io.github.hakjuoh.protege_mcp.ro_crate;

import java.util.List;

/** Immutable result of an offline RO-Crate project-profile validation. */
public record RoCrateValidationResult(List<RoCrateValidationIssue> issues) {
    public RoCrateValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean valid() {
        return issues.isEmpty();
    }
}
