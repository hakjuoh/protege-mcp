package io.github.hakjuoh.protege_mcp.tools;

import java.util.Map;
import java.util.Collection;
import java.util.List;

import io.github.hakjuoh.protege_mcp.contracts.ToolError;

/**
 * Thrown when a tool argument is missing or invalid. Handlers turn it into a (non-fatal) MCP error
 * result with the message shown to the client.
 */
public class ToolArgException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;
    private final List<String> secretCanaries;

    public ToolArgException(String message) {
        this("invalid_request", message, Map.of(), false);
    }

    public ToolArgException(String code, String message, boolean retryable) {
        this(code, message, Map.of(), retryable);
    }

    public ToolArgException(String code, String message, Map<String, Object> details,
            boolean retryable) {
        this(code, message, details, retryable, List.of());
    }

    public ToolArgException(String code, String message, Map<String, Object> details,
            boolean retryable, Collection<String> secretCanaries) {
        super(message);
        if (!ToolError.validCode(code)) {
            throw new IllegalArgumentException("tool error code must be a stable lowercase identifier");
        }
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.secretCanaries = secretCanaries == null ? List.of() : secretCanaries.stream()
                .filter(value -> value != null && !value.isEmpty()).distinct().toList();
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

    public List<String> secretCanaries() {
        return secretCanaries;
    }
}
