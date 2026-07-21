package io.github.hakjuoh.protege_mcp.sssom;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Convert policy-v2 mapping controls, retaining a fail-closed check at the trust boundary. */
public final class SssomPolicies {

    private SssomPolicies() {
    }

    public static SssomValidationPolicy from(ProjectPolicy policy) {
        if (policy == null || !policy.loaded()) return SssomValidationPolicy.structural();
        if (!policy.valid()) {
            throw new IllegalArgumentException(
                    "Invalid project policy cannot authorize SSSOM validation");
        }
        if (policy.version() != 2) return SssomValidationPolicy.structural();
        try {
            Map<String, Object> mappings = object(policy.effective().get("mappings"));
            Map<String, String> cycles = stringsMap(mappings.get("directional_cycle_policy"));
            List<SssomValidationPolicy.ManyToOneRule> many = objects(mappings.get("many_to_one_rules"))
                    .stream().map(rule -> new SssomValidationPolicy.ManyToOneRule(
                            string(rule.get("predicate")), strings(rule.get("subject_ontologies")),
                            strings(rule.get("subject_providers")), strings(rule.get("target_ontologies"))))
                    .toList();
            return new SssomValidationPolicy(strings(mappings.get("allowed_predicates")),
                    strings(mappings.get("allowed_sources")),
                    strings(mappings.get("allowed_licenses")),
                    bool(mappings.get("require_license")),
                    strings(mappings.get("required_findings")), cycles, many,
                    stringsMap(policy.effective().get("prefixes")));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Project policy v2 mapping controls are malformed", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("mapping policy block must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) {
            throw new IllegalArgumentException("mapping policy rules must be objects");
        }
        return (List<Map<String, Object>>) value;
    }

    private static Set<String> strings(Object value) {
        if (value == null) return Set.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("mapping policy list must be an array");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("mapping policy lists require non-blank strings");
            }
            result.add(text);
        }
        return result;
    }

    private static Map<String, String> stringsMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("mapping policy map must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String name) || name.isBlank()
                    || !(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("mapping policy maps require non-blank strings");
            }
            result.put(name, text);
        });
        return result;
    }

    private static String string(Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException("mapping policy scalar requires a non-blank string");
    }

    private static boolean bool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("mapping policy boolean has the wrong type");
    }
}
