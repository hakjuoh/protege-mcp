package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.Locale;

import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;

/** Adapter-neutral outcome produced by one project-QC stage before strict aggregation. */
public enum QcStageVerdict {
    PASS("pass", null),
    INFO("info", FindingSeverity.INFO),
    WARNING("warn", FindingSeverity.WARNING),
    FAIL("fail", FindingSeverity.ERROR),
    SKIPPED(null, null),
    ERROR("fail", FindingSeverity.ERROR);

    private final String legacyVerdict;
    private final FindingSeverity severity;

    QcStageVerdict(String legacyVerdict, FindingSeverity severity) {
        this.legacyVerdict = legacyVerdict;
        this.severity = severity;
    }

    public boolean ran() {
        return this != SKIPPED;
    }

    public boolean executionError() {
        return this == ERROR;
    }

    public String legacyVerdict() {
        return legacyVerdict;
    }

    public FindingSeverity severity() {
        return severity;
    }

    /** Parse the established stage vocabulary used by both plugin and headless adapters. */
    public static QcStageVerdict fromLegacy(String value) {
        if (value != null) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "pass" -> PASS;
                case "info" -> INFO;
                case "warn", "warning" -> WARNING;
                case "fail" -> FAIL;
                default -> throw new IllegalArgumentException(
                        "stage verdict must be pass, info, warn, or fail");
            };
        }
        throw new IllegalArgumentException("stage verdict must not be null");
    }
}
