package io.github.hakjuoh.protege_mcp.core.audit;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, recursive defense-in-depth redaction for audit summaries and exports. */
public final class AuditRedactor {

    public static final String REDACTED = "[REDACTED]";
    private static final int MAX_DEPTH = 8;
    private static final int MAX_MAP_ENTRIES = 128;
    private static final int MAX_LIST_ENTRIES = 256;
    private static final int MAX_STRING_CHARS = 4096;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "proxy_authorization", "bearer", "token", "access_token",
            "refresh_token", "id_token", "oauth_token", "api_key", "apikey", "secret",
            "client_secret", "password", "cookie", "set_cookie", "prompt", "body",
            "content", "attachment", "attached_file", "attached_file_body",
            "ontology_content", "ontology_document", "axioms");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+", Pattern.UNICODE_CASE);
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:access_token|refresh_token|token|api_key|apikey|secret|password)=)[^&#\\s]*");

    private AuditRedactor() {
    }

    public static Object redact(Object value) {
        return redact(value, 0);
    }

    private static Object redact(Object value, int depth) {
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (depth >= MAX_DEPTH) return "[DEPTH_LIMIT]";
        if (value instanceof CharSequence text) return sanitize(text.toString());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_MAP_ENTRIES) {
                    copy.put("_truncated", true);
                    break;
                }
                String key = sanitize(String.valueOf(entry.getKey()));
                String unique = key;
                int duplicate = 2;
                while (copy.containsKey(unique)) unique = key + "#" + duplicate++;
                copy.put(unique, sensitive(key) ? REDACTED : redact(entry.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                if (count++ >= MAX_LIST_ENTRIES) {
                    copy.add("[TRUNCATED]");
                    break;
                }
                copy.add(redact(item, depth + 1));
            }
            return copy;
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            int length = Math.min(Array.getLength(value), MAX_LIST_ENTRIES);
            for (int i = 0; i < length; i++) copy.add(redact(Array.get(value, i), depth + 1));
            if (Array.getLength(value) > length) copy.add("[TRUNCATED]");
            return copy;
        }
        return "[UNSUPPORTED_VALUE]";
    }

    private static boolean sensitive(String key) {
        StringBuilder normalizedKey = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c) && normalizedKey.length() > 0
                    && normalizedKey.charAt(normalizedKey.length() - 1) != '_') {
                normalizedKey.append('_');
            }
            if (Character.isLetterOrDigit(c)) {
                normalizedKey.append(Character.toLowerCase(c));
            } else if (normalizedKey.length() == 0
                    || normalizedKey.charAt(normalizedKey.length() - 1) != '_') {
                normalizedKey.append('_');
            }
        }
        String normalized = normalizedKey.toString().toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized) || normalized.endsWith("_token")
                || normalized.endsWith("_password") || normalized.endsWith("_secret")
                || normalized.contains("prompt") || normalized.contains("attachment")
                || normalized.contains("authorization") || normalized.endsWith("_body")
                || normalized.endsWith("_content");
    }

    private static String sanitize(String value) {
        String redacted = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        redacted = QUERY_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
        StringBuilder safe = new StringBuilder(Math.min(redacted.length(), MAX_STRING_CHARS));
        for (int i = 0; i < redacted.length() && safe.length() < MAX_STRING_CHARS; i++) {
            char c = redacted.charAt(i);
            safe.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (redacted.length() > MAX_STRING_CHARS) safe.append("...[truncated]");
        return safe.toString();
    }
}
