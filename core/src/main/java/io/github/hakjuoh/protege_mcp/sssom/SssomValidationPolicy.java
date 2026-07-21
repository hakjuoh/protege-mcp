package io.github.hakjuoh.protege_mcp.sssom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Project-governed SSSOM validation controls; empty sets mean structural validation only. */
public record SssomValidationPolicy(Set<String> allowedPredicates, Set<String> allowedSources,
        Set<String> allowedLicenses, boolean requireLicense, Set<String> requiredFindings,
        Map<String, String> directionalCyclePolicy, List<ManyToOneRule> manyToOneRules,
        Map<String, String> approvedPrefixes, boolean restrictPredicates) {

    public SssomValidationPolicy(Set<String> allowedPredicates, Set<String> allowedSources,
            Set<String> allowedLicenses, boolean requireLicense, Set<String> requiredFindings,
            Map<String, String> directionalCyclePolicy, List<ManyToOneRule> manyToOneRules,
            Map<String, String> approvedPrefixes) {
        this(allowedPredicates, allowedSources, allowedLicenses, requireLicense, requiredFindings,
                directionalCyclePolicy, manyToOneRules, approvedPrefixes, true);
    }

    public SssomValidationPolicy {
        allowedPredicates = immutableSet(allowedPredicates);
        allowedSources = immutableSet(allowedSources);
        allowedLicenses = immutableSet(allowedLicenses);
        requiredFindings = immutableSet(requiredFindings);
        directionalCyclePolicy = immutableMap(directionalCyclePolicy);
        directionalCyclePolicy.forEach((predicate, severity) -> {
            if (predicate.isBlank() || !Set.of("allow", "warning", "error").contains(severity)) {
                throw new IllegalArgumentException("directional cycle policy must be allow, warning, or error");
            }
        });
        manyToOneRules = manyToOneRules == null ? List.of() : List.copyOf(manyToOneRules);
        if (manyToOneRules.size() > 64) {
            throw new IllegalArgumentException("at most 64 many-to-one rules are supported");
        }
        approvedPrefixes = immutableMap(approvedPrefixes);
    }

    public static SssomValidationPolicy structural() {
        return new SssomValidationPolicy(Set.of(), Set.of(), Set.of(), false, Set.of(),
                Map.of("skos:broadMatch", "error", "skos:narrowMatch", "error"),
                List.of(), Map.of(), false);
    }

    public record ManyToOneRule(String predicate, Set<String> subjectOntologies,
            Set<String> subjectProviders, Set<String> targetOntologies) {
        public ManyToOneRule {
            if (predicate == null || predicate.isBlank()) {
                throw new IllegalArgumentException("many-to-one predicate is required");
            }
            subjectOntologies = immutableSet(subjectOntologies);
            subjectProviders = immutableSet(subjectProviders);
            targetOntologies = immutableSet(targetOntologies);
            if (subjectOntologies.size() > 128 || subjectProviders.size() > 128
                    || targetOntologies.size() > 128) {
                throw new IllegalArgumentException("many-to-one scopes are limited to 128 values");
            }
            if (subjectOntologies.isEmpty() && subjectProviders.isEmpty()
                    && targetOntologies.isEmpty()) {
                throw new IllegalArgumentException("many-to-one rule requires a scope");
            }
        }
    }

    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) return Set.of();
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : source) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("SSSOM policy lists reject blank values");
            }
            copy.add(value);
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<String, String> immutableMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("SSSOM policy maps reject blank entries");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
