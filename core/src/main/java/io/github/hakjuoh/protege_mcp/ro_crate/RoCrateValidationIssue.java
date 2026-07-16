package io.github.hakjuoh.protege_mcp.ro_crate;

/** Stable machine-readable validation diagnostic returned by the standalone module. */
public record RoCrateValidationIssue(String code, String path, String message) {
}
