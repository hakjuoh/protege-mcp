package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * Argument extraction from an MCP {@link CallToolRequest}: null-safe accessors that coerce values and
 * apply defaults. Split out of {@link Tools} so this cluster is a focused, independently-testable unit;
 * {@code Tools} keeps thin delegators for source compatibility.
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static Map<String, Object> args(CallToolRequest req) {
        Map<String, Object> a = req.arguments();
        return a == null ? Collections.emptyMap() : a;
    }

    public static String optString(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    public static String reqString(Map<String, Object> a, String key) {
        String s = optString(a, key);
        if (s == null) {
            throw new ToolArgException("Missing required argument '" + key + "'.");
        }
        return s;
    }

    public static int optInt(Map<String, Object> a, String key, int def) {
        Object v = a.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }

    public static boolean optBool(Map<String, Object> a, String key, boolean def) {
        Object v = a.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v != null) {
            return Boolean.parseBoolean(String.valueOf(v).trim());
        }
        return def;
    }

    public static List<String> stringList(Map<String, Object> a, String key) {
        Object v = a.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v != null) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    /** Read {@code key} as a list of object maps (e.g. the {@code annotations} operand). */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objList(Map<String, Object> a, String key) {
        Object v = a.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
        } else if (v instanceof Map) {
            out.add((Map<String, Object>) v);
        }
        return out;
    }
}
