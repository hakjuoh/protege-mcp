package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Strict version-2 policy gate shared by live and headless materialization adapters. */
public final class MaterializationPolicy {
    private MaterializationPolicy() {
    }

    public static void requireAllowed(ProjectPolicy policy, MaterializationRequest request,
            ReasonerIdentity reasoner) {
        if (policy == null || !policy.loaded() || !policy.valid() || policy.version() != 2) {
            throw failure("materialization_policy_required",
                    "Inference materialization requires a valid version 2 project policy.");
        }
        Map<String, Object> section = object(policy.effective().get("materialization"));
        Set<String> allowedCategories = Set.copyOf(strings(section.get("allowed_categories")));
        List<String> denied = request.categories().stream()
                .map(MaterializationCategory::value)
                .filter(category -> !allowedCategories.contains(category)).toList();
        if (!denied.isEmpty()) {
            throw new MaterializationException("materialization_policy_denied",
                    "Project policy denies one or more requested inference categories.",
                    Map.of("denied_categories", denied, "effects_prevented", true), false);
        }
        Set<String> destinations = Set.copyOf(strings(section.get("allowed_destinations")));
        if (!destinations.contains(request.destination().kind())) {
            throw new MaterializationException("materialization_policy_denied",
                    "Project policy denies the requested materialization destination.",
                    Map.of("destination", request.destination().kind(),
                            "effects_prevented", true), false);
        }
        if ("active_source".equals(request.destination().kind())
                && !Boolean.TRUE.equals(section.get("allow_source_write"))) {
            throw failure("materialization_source_write_denied",
                    "Project policy does not allow writes to the active source ontology.");
        }
        List<String> allowedReasoners = strings(section.get("allowed_reasoners"));
        if (!allowedReasoners.isEmpty() && allowedReasoners.stream().noneMatch(value ->
                value.equals(reasoner.factoryId()) || value.equals(reasoner.factoryClass())
                        || value.equals(reasoner.reasonerName()))) {
            throw new MaterializationException("materialization_policy_denied",
                    "Project policy does not allow the selected exact reasoner.",
                    Map.of("factory_id", reasoner.factoryId(), "effects_prevented", true), false);
        }
        MaterializationRequest.Limits limits = request.limits();
        requireAtMost("max_axioms_per_category", limits.maxAxiomsPerCategory(), section);
        requireAtMost("max_axioms_total", limits.maxAxiomsTotal(), section);
        requireAtMost("max_bytes", limits.maxBytes(), section);
        requireAtMost("timeout_ms", limits.timeoutMillis(), section);
    }

    private static void requireAtMost(String key, long requested, Map<String, Object> section) {
        Object configured = section.get(key);
        if (!(configured instanceof Number number) || requested > number.longValue()) {
            throw new MaterializationException("materialization_policy_denied",
                    "Requested materialization limit exceeds project policy.",
                    Map.of("limit", key, "requested", requested,
                            "policy_maximum", configured == null ? "missing" : configured,
                            "effects_prevented", true), false);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String text) out.add(text);
        }
        return List.copyOf(out);
    }

    private static MaterializationException failure(String code, String message) {
        return new MaterializationException(code, message,
                Map.of("effects_prevented", true), false);
    }
}
