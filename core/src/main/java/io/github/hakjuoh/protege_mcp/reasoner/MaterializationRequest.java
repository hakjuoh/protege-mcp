package io.github.hakjuoh.protege_mcp.reasoner;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fully explicit, policy-bounded materialization preview request. */
public record MaterializationRequest(
        List<MaterializationCategory> categories,
        Destination destination,
        Provenance provenance,
        Limits limits) {

    public static final int MAX_AXIOMS = 50_000;
    public static final long MAX_BYTES = 67_108_864L;
    public static final long MAX_TIMEOUT_MILLIS = 3_600_000L;

    public MaterializationRequest {
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
        if (categories.isEmpty() || categories.size() > MaterializationCategory.values().length
                || new LinkedHashSet<>(categories).size() != categories.size()) {
            throw new IllegalArgumentException("categories must be non-empty and unique");
        }
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(limits, "limits");
    }

    public record Destination(String kind, String identifier) {
        private static final Set<String> KINDS = Set.of(
                "new_ontology", "project_file", "active_source");

        public Destination {
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException("invalid materialization destination");
            }
            if (identifier == null || identifier.isBlank() || identifier.length() > 4096) {
                throw new IllegalArgumentException("destination identifier must be bounded");
            }
        }

        public Map<String, Object> toMap() {
            return Map.of("kind", kind, "identifier", identifier);
        }
    }

    public record Provenance(String generator, String purpose) {
        public Provenance {
            generator = bounded(generator, "generator", 512);
            purpose = bounded(purpose, "purpose", 1024);
        }

        public Map<String, Object> toMap() {
            return Map.of("generator", generator, "purpose", purpose);
        }
    }

    public record Limits(int maxAxiomsPerCategory, int maxAxiomsTotal,
            long maxBytes, long timeoutMillis) {
        public Limits {
            if (maxAxiomsPerCategory < 1 || maxAxiomsPerCategory > MAX_AXIOMS
                    || maxAxiomsTotal < 1 || maxAxiomsTotal > MAX_AXIOMS
                    || maxBytes < 1024 || maxBytes > MAX_BYTES
                    || timeoutMillis < 1 || timeoutMillis > MAX_TIMEOUT_MILLIS) {
                throw new IllegalArgumentException("materialization limits are outside hard bounds");
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("max_axioms_per_category", maxAxiomsPerCategory);
            out.put("max_axioms_total", maxAxiomsTotal);
            out.put("max_bytes", maxBytes);
            out.put("timeout_ms", timeoutMillis);
            return out;
        }
    }

    private static String bounded(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return value;
    }
}
