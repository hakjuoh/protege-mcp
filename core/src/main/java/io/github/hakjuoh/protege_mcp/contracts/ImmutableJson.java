package io.github.hakjuoh.protege_mcp.contracts;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict recursively immutable copy for JSON-compatible contract declarations. */
public final class ImmutableJson {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 16_384;
    private static final int MAX_RESULT_DEPTH = 64;
    private static final int MAX_RESULT_NODES = 1_000_000;

    private ImmutableJson() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> value) {
        if (value == null) throw new IllegalArgumentException("JSON object is required");
        return (Map<String, Object>) copy(value, 0, new int[] {0}, new IdentityHashMap<>(),
                MAX_DEPTH, MAX_NODES);
    }

    /** Deep immutable snapshot for an already JSON-normalized runtime result object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resultMap(Map<String, Object> value) {
        if (value == null) throw new IllegalArgumentException("JSON result object is required");
        return (Map<String, Object>) copy(value, 0, new int[] {0}, new IdentityHashMap<>(),
                MAX_RESULT_DEPTH, MAX_RESULT_NODES);
    }

    private static Object copy(Object value, int depth, int[] nodes,
            IdentityHashMap<Object, Boolean> seen, int maxDepth, int maxNodes) {
        if (++nodes[0] > maxNodes) throw new IllegalArgumentException("JSON object is too large");
        if (depth > maxDepth) throw new IllegalArgumentException("JSON object is too deep");
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            rejectCycle(value, seen);
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                result.put(key, copy(entry.getValue(), depth + 1, nodes, seen,
                        maxDepth, maxNodes));
            }
            seen.remove(value);
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            rejectCycle(value, seen);
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(copy(item, depth + 1, nodes, seen, maxDepth, maxNodes));
            }
            seen.remove(value);
            return Collections.unmodifiableList(result);
        }
        if (value.getClass().isArray()) {
            rejectCycle(value, seen);
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(copy(Array.get(value, index), depth + 1, nodes, seen,
                        maxDepth, maxNodes));
            }
            seen.remove(value);
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("Unsupported JSON contract value: "
                + value.getClass().getName());
    }

    private static void rejectCycle(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("JSON contract must not contain cycles");
        }
    }
}
