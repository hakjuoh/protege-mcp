package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;

/** Stable, content-free failure raised by a provider adapter or its restricted transport. */
public final class ProviderFailure extends IOException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;

    public ProviderFailure(String code, String message, boolean retryable) {
        this(code, message, retryable, Map.of(), null);
    }

    public ProviderFailure(String code, String message, boolean retryable,
            Map<String, Object> details, Throwable cause) {
        super(requireText(message, "message", 1_024), cause);
        this.code = requireCode(code);
        this.retryable = retryable;
        this.details = ImmutableJson.map(details == null ? Map.of() : details);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }

    private static String requireCode(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("provider failure code is invalid");
        }
        return value;
    }

    static String requireText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is missing or exceeds " + maximum);
        }
        return value;
    }
}
