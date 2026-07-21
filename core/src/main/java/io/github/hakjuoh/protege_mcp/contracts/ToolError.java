package io.github.hakjuoh.protege_mcp.contracts;

import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/** Stable structured MCP error envelope shared by live and headless adapters. */
public record ToolError(String error, String code, String message,
        Map<String, Object> details, boolean retryable) {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    public static final int MAX_MESSAGE_LENGTH = 2_048;
    public static final int MAX_DETAILS = 32;

    public ToolError {
        if (!validCode(code)) {
            throw new IllegalArgumentException("code must be a stable lowercase identifier");
        }
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must contain 1-" + MAX_MESSAGE_LENGTH
                    + " characters");
        }
        if (error == null) error = message;
        if (!error.equals(message)) {
            throw new IllegalArgumentException("legacy error must equal message");
        }
        message = ContractRedactor.sanitize(message);
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        error = message;
        @SuppressWarnings("unchecked")
        Map<String, Object> safeDetails = (Map<String, Object>) ContractRedactor.redact(
                details == null ? Map.of() : details);
        details = safeDetails;
        if (details.size() > MAX_DETAILS) {
            throw new IllegalArgumentException("details must contain at most " + MAX_DETAILS
                    + " entries");
        }
    }

    public static boolean validCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    public static ToolError of(String code, String message, boolean retryable) {
        return of(code, message, Map.of(), retryable);
    }

    public static ToolError of(String code, String message, Map<String, Object> details,
            boolean retryable) {
        return of(code, message, details, retryable, java.util.List.of());
    }

    public static ToolError of(String code, String message, Map<String, Object> details,
            boolean retryable, Collection<String> secretCanaries) {
        String safe = ContractRedactor.sanitize(
                message == null || message.isBlank() ? "Operation failed." : message,
                secretCanaries);
        @SuppressWarnings("unchecked")
        Map<String, Object> safeDetails = (Map<String, Object>) ContractRedactor.redact(
                details == null ? Map.of() : details, secretCanaries);
        if (safe.length() > MAX_MESSAGE_LENGTH) {
            safe = safe.substring(0, MAX_MESSAGE_LENGTH);
        }
        return new ToolError(safe, code, safe, safeDetails, retryable);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("error", error);
        json.put("code", code);
        json.put("message", message);
        if (!details.isEmpty()) json.put("details", details);
        json.put("retryable", retryable);
        return json;
    }
}
