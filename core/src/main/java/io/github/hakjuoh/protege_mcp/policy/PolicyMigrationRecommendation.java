package io.github.hakjuoh.protege_mcp.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Non-mutating, non-digest guidance for explicitly adopting a newer policy version. */
public record PolicyMigrationRecommendation(int fromVersion, int toVersion, boolean available,
        boolean recommended, boolean required, boolean automaticWrite,
        boolean diagnosticAffectsDigest, List<String> enables, String message) {

    public PolicyMigrationRecommendation {
        if (fromVersion < 1 || toVersion <= fromVersion || enables == null || message == null
                || message.isBlank()) {
            throw new IllegalArgumentException("policy migration recommendation is incomplete");
        }
        enables = List.copyOf(enables);
    }

    public static PolicyMigrationRecommendation v1ToV2() {
        return new PolicyMigrationRecommendation(1, 2, true, true, false, false, false,
                List.of("external_terms", "mappings", "jobs", "materialization"),
                "Policy v1 remains supported with its existing digest. Adopt version 2 explicitly "
                        + "only when the project needs external providers, governed SSSOM mappings, "
                        + "job bounds, or materialization policy; no automatic rewrite is performed.");
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("from_version", fromVersion);
        json.put("to_version", toVersion);
        json.put("available", available);
        json.put("recommended", recommended);
        json.put("required", required);
        json.put("automatic_write", automaticWrite);
        json.put("diagnostic_affects_digest", diagnosticAffectsDigest);
        json.put("enables", enables);
        json.put("message", message);
        return Collections.unmodifiableMap(json);
    }
}
