package io.github.hakjuoh.protege_mcp.contracts;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared validation and defensive-copy helpers for the versioned contract records. */
final class ContractValues {

    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-fA-F]{64}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-fA-F]{64}");

    private ContractValues() {
    }

    static String nonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String absoluteIri(String value, String field) {
        nonBlank(value, field);
        try {
            URI iri = new URI(value);
            if (!iri.isAbsolute()) {
                throw new IllegalArgumentException(field + " must be an absolute IRI");
            }
            return value;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(field + " must be an absolute IRI", e);
        }
    }

    static String workspaceId(String value) {
        nonBlank(value, "workspace_id");
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("workspace_id must be a UUID", e);
        }
    }

    static String fingerprint(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be sha256 followed by 64 hexadecimal digits");
        }
        return value.toLowerCase();
    }

    static String digest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must contain 64 hexadecimal digits");
        }
        return value.toLowerCase();
    }

    static List<String> strings(List<String> values, String field, boolean allowEmpty) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (!allowEmpty && values.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        List<String> copy = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            String checked = nonBlank(value, field + " entry");
            if (!seen.add(checked)) {
                throw new IllegalArgumentException(field + " must not contain duplicates: " + checked);
            }
            copy.add(checked);
        }
        return Collections.unmodifiableList(copy);
    }

    static <T> List<T> list(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (values.stream().anyMatch(v -> v == null)) {
            throw new IllegalArgumentException(field + " must not contain null entries");
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    static Map<String, Object> map(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
