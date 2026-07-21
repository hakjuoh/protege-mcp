package io.github.hakjuoh.protege_mcp.sssom;

import java.io.IOException;
import java.util.List;

/** Typed, adapter-neutral failure from a mapping-store transaction. */
public final class SssomStoreException extends IOException {

    private final String code;
    private final boolean effectsPrevented;
    private final boolean outcomeUnknown;
    private final List<SssomFinding> findings;

    public SssomStoreException(String code, String message, boolean effectsPrevented) {
        this(code, message, effectsPrevented, false, List.of(), null);
    }

    public SssomStoreException(String code, String message, boolean effectsPrevented,
            boolean outcomeUnknown, List<SssomFinding> findings, Throwable cause) {
        super(message, cause);
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid mapping-store error code");
        }
        this.code = code;
        this.effectsPrevented = effectsPrevented;
        this.outcomeUnknown = outcomeUnknown;
        this.findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public String code() {
        return code;
    }

    public boolean effectsPrevented() {
        return effectsPrevented;
    }

    public boolean outcomeUnknown() {
        return outcomeUnknown;
    }

    public List<SssomFinding> findings() {
        return findings;
    }
}
