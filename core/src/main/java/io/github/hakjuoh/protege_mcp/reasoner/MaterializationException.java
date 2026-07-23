package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.Map;

/** Bounded, typed materialization refusal; no artifact is published for these failures. */
public final class MaterializationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final Map<String, Object> details;
    private final boolean retryable;

    public MaterializationException(String code, String message,
            Map<String, Object> details, boolean retryable) {
        super(message);
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid materialization error code");
        }
        this.code = code;
        this.details = Map.copyOf(details == null ? Map.of() : details);
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public boolean retryable() {
        return retryable;
    }
}
