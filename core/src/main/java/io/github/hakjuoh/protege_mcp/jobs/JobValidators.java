package io.github.hakjuoh.protege_mcp.jobs;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.auth.Capability;

/** Shared validation for stable public job fields. */
final class JobValidators {
    private JobValidators() {
    }

    static String requireUuid(String value, String field) {
        if (value == null || !value.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException(field + " must be a canonical UUID");
        }
        return value;
    }

    static Instant requireInstant(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(field + " is invalid", invalid);
        }
    }

    static Instant optionalInstant(String value, String field) {
        return value == null ? null : requireInstant(value, field);
    }

    static String requirePhase(String value, String field) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    static String requireProgress(String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > JobService.MAX_PROGRESS_BYTES) {
            throw new IllegalArgumentException("job progress message is invalid");
        }
        return value;
    }

    static String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("idempotency key is invalid");
        }
        return value;
    }

    static Set<String> requireCapabilities(Set<String> values) {
        if (values == null || values.isEmpty() || values.size() > 16
                || !Capability.valuesSet().containsAll(values)) {
            throw new IllegalArgumentException("required capability set is invalid");
        }
        return JobHashes.immutableSortedSet(values);
    }
}
