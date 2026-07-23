package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict shared parser for the advertised materialization request schema. */
public final class MaterializationRequests {
    private MaterializationRequests() {
    }

    public static MaterializationRequest parse(Map<String, Object> arguments) {
        if (arguments == null) throw new IllegalArgumentException("arguments are required");
        requireKeys(arguments, Set.of(
                "categories", "destination", "provenance", "limits", "policy_path"),
                "materialization request");
        Object rawCategories = arguments.get("categories");
        if (!(rawCategories instanceof List<?> values)) {
            throw new IllegalArgumentException("categories must be an array");
        }
        List<MaterializationCategory> categories = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("category values must be strings");
            }
            categories.add(MaterializationCategory.fromValue(text));
        }
        Map<String, Object> destination = object(arguments.get("destination"), "destination");
        Map<String, Object> provenance = object(arguments.get("provenance"), "provenance");
        Map<String, Object> limits = object(arguments.get("limits"), "limits");
        requireKeys(destination, Set.of("kind", "identifier"), "destination");
        requireKeys(provenance, Set.of("generator", "purpose"), "provenance");
        requireKeys(limits, Set.of("max_axioms_per_category", "max_axioms_total",
                "max_bytes", "timeout_ms"), "limits");
        return new MaterializationRequest(categories,
                new MaterializationRequest.Destination(
                        string(destination, "kind"), string(destination, "identifier")),
                new MaterializationRequest.Provenance(
                        string(provenance, "generator"), string(provenance, "purpose")),
                new MaterializationRequest.Limits(
                        integer(limits, "max_axioms_per_category"),
                        integer(limits, "max_axioms_total"),
                        integer(limits, "max_bytes"), integer(limits, "timeout_ms")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.longValue()
                || number.longValue() < Integer.MIN_VALUE
                || number.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return number.intValue();
    }

    private static void requireKeys(Map<String, Object> values, Set<String> allowed,
            String name) {
        Set<String> unknown = new java.util.TreeSet<>(values.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(name + " contains unknown keys: "
                    + String.join(", ", unknown));
        }
    }
}
