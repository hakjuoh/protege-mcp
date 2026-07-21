package io.github.hakjuoh.protege_mcp.contracts;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, recursively immutable redaction for values crossing a public contract boundary. */
public final class ContractRedactor {

    public static final String REDACTED = "[REDACTED]";
    private static final int MAX_DEPTH = 8;
    private static final int MAX_MAP_ENTRIES = ToolError.MAX_DETAILS;
    private static final int MAX_LIST_ENTRIES = 128;
    private static final int MAX_STRING_CHARS = 4_096;
    private static final int MAX_TOTAL_NODES = 512;
    private static final int MAX_TOTAL_CHARS = 32_768;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "proxy_authorization", "bearer", "token", "access_token",
            "refresh_token", "id_token", "oauth_token", "api_key", "apikey", "secret",
            "client_secret", "password", "cookie", "set_cookie", "prompt", "body",
            "content", "attachment", "attached_file", "attached_file_body",
            "ontology_content", "ontology_document", "axioms");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(?:Bearer|Basic)\\s+[^\\s,;]+", Pattern.UNICODE_CASE);
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&](?:access_token|refresh_token|token|api_key|apikey|secret|password)=)[^&#\\s]*");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)(\\b(?:access_token|refresh_token|id_token|oauth_token|token|api_key|apikey|"
                    + "x-api-key|client_secret|client-secret|secret|password)\\s*[:=]\\s*)"
                    + "(?:\"(?:\\\\.|[^\"\\\\])*(?:\"|$)"
                    + "|'(?:\\\\.|[^'\\\\])*(?:'|$)|[^\\s,;]+)");
    private static final Pattern URL_USERINFO = Pattern.compile(
            "(?i)(https?://)[^/\\s]+@");
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![:/A-Za-z0-9_.-])/(?:[^\\s'\";,]+/)*[^\\s'\";,]*");
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)\\b[A-Z]:\\\\(?:[^\\s'\";,]+\\\\)*[^\\s'\";,]*");
    private static final Pattern FILE_URI = Pattern.compile(
            "(?i)\\bfile:/{1,3}[^\\s'\";,]+", Pattern.UNICODE_CASE);

    private ContractRedactor() {
    }

    public static Object redact(Object value) {
        return redact(value, List.of());
    }

    /** Redact normal secret forms plus exact request-scoped canary values. */
    public static Object redact(Object value, Collection<String> secretCanaries) {
        List<String> canaries = secretCanaries == null ? List.of() : secretCanaries.stream()
                .filter(secret -> secret != null && !secret.isEmpty())
                .distinct()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .toList();
        return redact(value, canaries, 0, new Budget(), new IdentityHashMap<>());
    }

    public static String sanitize(String value) {
        return (String) redact(value == null ? "" : value);
    }

    public static String sanitize(String value, Collection<String> secretCanaries) {
        return (String) redact(value == null ? "" : value, secretCanaries);
    }

    private static Object redact(Object value, List<String> canaries, int depth,
            Budget budget, IdentityHashMap<Object, Boolean> seen) {
        if (!budget.reserveNode()) return "[TOTAL_LIMIT]";
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Number number) return jsonNumber(number, budget);
        if (depth >= MAX_DEPTH) return "[DEPTH_LIMIT]";
        if (value instanceof CharSequence text) {
            return budget.bound(sanitizeText(text.toString(), canaries));
        }
        if (value instanceof Map<?, ?> map) {
            if (seen.put(map, Boolean.TRUE) != null) return "[REPEATED_REFERENCE]";
            Map<String, Object> copy = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= MAX_MAP_ENTRIES || budget.exhausted()) {
                    if (!copy.isEmpty()) {
                        String last = copy.keySet().stream().reduce((first, second) -> second).orElseThrow();
                        copy.remove(last);
                    }
                    copy.put("_truncated", true);
                    break;
                }
                count++;
                String rawKey = String.valueOf(entry.getKey());
                String key = budget.bound(sanitizeText(rawKey, canaries));
                String unique = key;
                int duplicate = 2;
                while (copy.containsKey(unique)) unique = key + "#" + duplicate++;
                copy.put(unique, sensitive(rawKey) ? REDACTED
                        : redact(entry.getValue(), canaries, depth + 1, budget, seen));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            if (seen.put(collection, Boolean.TRUE) != null) return "[REPEATED_REFERENCE]";
            List<Object> copy = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                if (count++ >= MAX_LIST_ENTRIES || budget.exhausted()) {
                    copy.add("[TRUNCATED]");
                    break;
                }
                copy.add(redact(item, canaries, depth + 1, budget, seen));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value.getClass().isArray()) {
            if (seen.put(value, Boolean.TRUE) != null) return "[REPEATED_REFERENCE]";
            List<Object> copy = new ArrayList<>();
            int length = Math.min(Array.getLength(value), Math.min(MAX_LIST_ENTRIES,
                    budget.remainingNodes()));
            for (int i = 0; i < length; i++) {
                copy.add(redact(Array.get(value, i), canaries, depth + 1, budget, seen));
            }
            if (Array.getLength(value) > length) copy.add("[TRUNCATED]");
            return Collections.unmodifiableList(copy);
        }
        return "[UNSUPPORTED_VALUE]";
    }

    private static boolean sensitive(String key) {
        StringBuilder flattened = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                flattened.append(Character.toLowerCase(c));
            }
        }
        String normalized = flattened.toString();
        return SENSITIVE_KEYS.stream().map(field -> field.replace("_", ""))
                .anyMatch(normalized::endsWith)
                || normalized.endsWith("token") || normalized.endsWith("password")
                || normalized.endsWith("secret") || normalized.contains("prompt")
                || normalized.contains("attachment") || normalized.contains("authorization")
                || normalized.endsWith("body") || normalized.endsWith("content");
    }

    private static String sanitizeText(String value, List<String> canaries) {
        String redacted = BEARER.matcher(value).replaceAll(match -> {
            String scheme = match.group().regionMatches(true, 0, "Basic", 0, 5)
                    ? "Basic" : "Bearer";
            return scheme + " " + REDACTED;
        });
        redacted = QUERY_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = ASSIGNED_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = URL_USERINFO.matcher(redacted).replaceAll("$1" + REDACTED + "@");
        for (String canary : canaries) redacted = redacted.replace(canary, REDACTED);
        redacted = FILE_URI.matcher(redacted).replaceAll("[PATH]");
        redacted = WINDOWS_PATH.matcher(redacted).replaceAll("[PATH]");
        redacted = UNIX_PATH.matcher(redacted).replaceAll("[PATH]");
        StringBuilder safe = new StringBuilder(Math.min(redacted.length(), MAX_STRING_CHARS));
        for (int i = 0; i < redacted.length() && safe.length() < MAX_STRING_CHARS; i++) {
            char c = redacted.charAt(i);
            safe.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (redacted.length() > MAX_STRING_CHARS) safe.append("...[truncated]");
        return safe.toString();
    }

    private static Object jsonNumber(Number number, Budget budget) {
        if (number instanceof BigInteger integer
                && integer.bitLength() > MAX_STRING_CHARS * 4L) {
            return budget.bound("[NUMBER_LIMIT]");
        }
        if (number instanceof BigDecimal decimal && decimal.precision() > MAX_STRING_CHARS) {
            return budget.bound("[NUMBER_LIMIT]");
        }
        Object normalized;
        if (number instanceof Byte || number instanceof Short || number instanceof Integer
                || number instanceof java.util.concurrent.atomic.AtomicInteger) {
            normalized = number.intValue();
        } else if (number instanceof Long
                || number instanceof java.util.concurrent.atomic.AtomicLong) {
            normalized = number.longValue();
        } else if (number instanceof BigInteger || number instanceof BigDecimal) {
            normalized = number;
        } else {
            double value = number.doubleValue();
            normalized = Double.isFinite(value) ? value : "[UNSUPPORTED_NUMBER]";
        }
        int serializedChars = normalized.toString().length();
        return serializedChars > MAX_STRING_CHARS || !budget.reserveChars(serializedChars)
                ? budget.bound("[NUMBER_LIMIT]") : normalized;
    }

    private static final class Budget {
        private int nodes;
        private int chars;

        private boolean reserveNode() {
            return nodes++ < MAX_TOTAL_NODES;
        }

        private int remainingNodes() {
            return Math.max(0, MAX_TOTAL_NODES - nodes);
        }

        private boolean exhausted() {
            return nodes >= MAX_TOTAL_NODES || chars >= MAX_TOTAL_CHARS;
        }

        private boolean reserveChars(int count) {
            if (count < 0 || count > MAX_TOTAL_CHARS - chars) return false;
            chars += count;
            return true;
        }

        private String bound(String value) {
            int available = Math.max(0, MAX_TOTAL_CHARS - chars);
            if (available == 0) return "[TOTAL_LIMIT]";
            String suffix = "...[total-limit]";
            String bounded = value.length() <= available ? value
                    : available <= suffix.length() ? suffix.substring(0, available)
                    : value.substring(0, available - suffix.length()) + suffix;
            chars = Math.min(MAX_TOTAL_CHARS, chars + bounded.length());
            return bounded;
        }
    }
}
