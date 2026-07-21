package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Relative, bounded read request accepted by the centralized provider transport. */
public record ProviderRequest(String relativePath, Map<String, String> query) {

    public static final int MAX_PATH_LENGTH = 16_384;

    public ProviderRequest {
        if (!safeRelativePath(relativePath)) {
            throw new IllegalArgumentException("provider request path must be a bounded relative path");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (query != null) {
            if (query.size() > 32) throw new IllegalArgumentException("provider query has too many fields");
            query.forEach((key, value) -> {
                if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                        || value == null || value.length() > 4_096) {
                    throw new IllegalArgumentException("provider query field is invalid");
                }
                copy.put(key, value);
            });
        }
        query = Collections.unmodifiableMap(copy);
    }

    private static boolean safeRelativePath(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_PATH_LENGTH
                || !value.startsWith("/") || value.startsWith("//")
                || value.indexOf('\\') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0 || hasControl(value)) {
            return false;
        }
        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (parsed.isAbsolute() || parsed.getRawAuthority() != null
                || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c")) return false;
        for (String segment : value.split("/", -1)) {
            String dots = segment.toLowerCase(java.util.Locale.ROOT).replace("%2e", ".");
            if (dots.equals(".") || dots.equals("..")) return false;
        }
        return true;
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character <= 0x20 || character == 0x7f);
    }
}
