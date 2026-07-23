package io.github.hakjuoh.protege_mcp.jobs;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;

/** Typed bounded terminal error safe for the public job contract. */
public record JobError(@JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("retryable") boolean retryable,
        @JsonProperty("details") Map<String, Object> details) {
    public JobError {
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("job error code is invalid");
        }
        if (message == null || message.isBlank()
                || message.getBytes(StandardCharsets.UTF_8).length > 2_048) {
            throw new IllegalArgumentException("job error message is invalid");
        }
        Map<String, Object> source = details == null ? Map.of() : details;
        if (source.size() > 32) {
            throw new IllegalArgumentException("job error details are too large");
        }
        source.values().forEach(value -> validateDetail(value, 4));
        details = ImmutableJson.resultMap(source);
        try {
            if (ContractJson.mapper().writeValueAsBytes(details).length > 65_536) {
                throw new IllegalArgumentException("job error details are too large");
            }
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("job error details are invalid", invalid);
        }
    }

    private static void validateDetail(Object value, int remainingDepth) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            if (value instanceof String text
                    && text.getBytes(StandardCharsets.UTF_8).length > 4_096) {
                throw new IllegalArgumentException("job error detail string is too large");
            }
            return;
        }
        if (remainingDepth <= 0) {
            throw new IllegalArgumentException("job error details are too deeply nested");
        }
        if (value instanceof java.util.List<?> list) {
            if (list.size() > 128) {
                throw new IllegalArgumentException("job error detail array is too large");
            }
            list.forEach(nested -> validateDetail(nested, remainingDepth - 1));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 32 || map.keySet().stream()
                    .anyMatch(key -> !(key instanceof String))) {
                throw new IllegalArgumentException("job error detail object is invalid");
            }
            map.values().forEach(
                    nested -> validateDetail(nested, remainingDepth - 1));
            return;
        }
        throw new IllegalArgumentException("job error detail value is not JSON-compatible");
    }
}
