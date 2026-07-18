package io.github.hakjuoh.protege_mcp.tools;

import java.util.Locale;

/**
 * Request-level import-lock verification control (ADR 0005 decision 4). {@code ignore} — the
 * absent-argument default — expresses no request-level opinion and can never disable a
 * policy-mandated check: with {@code imports.mode: locked} the gate verification always runs,
 * whatever the request says. {@code verify} opts an unlocked/absent-policy request into the same
 * coordinate/SHA-256 comparison the locked gate uses (skipping cleanly, with a reported note, when
 * the resolved default lockfile does not exist); {@code required} turns exactly that file-absent
 * state into an error.
 */
enum LockMode {
    IGNORE("ignore"),
    VERIFY("verify"),
    REQUIRED("required");

    private final String value;

    LockMode(String value) {
        this.value = value;
    }

    static LockMode parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return IGNORE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (LockMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new ToolArgException("Invalid lock_mode '" + value
                + "'; expected ignore, verify, or required.");
    }

    String value() {
        return value;
    }

    /** Whether this request asks for lock verification at all. */
    boolean requested() {
        return this != IGNORE;
    }
}
