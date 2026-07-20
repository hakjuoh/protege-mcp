package io.github.hakjuoh.protege_mcp.core.audit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/** Secret-free input to the append-only audit store. */
public record AuditEvent(String operation, Outcome outcome, Actor actor,
        String targetOntology, String targetModule, String gate, Boolean committed,
        Map<String, Object> summary, List<String> confirmationReferences,
        String releaseManifest) {

    public enum Outcome {
        STARTED("started"), SUCCEEDED("succeeded"), FAILED("failed"), DENIED("denied");

        private final String json;

        Outcome(String json) {
            this.json = json;
        }

        public String json() {
            return json;
        }
    }

    /** Authenticated caller identity; no bearer token or raw credential is accepted here. */
    public record Actor(String clientId, String displayName, String provider,
            Set<String> capabilities) {
        public Actor {
            clientId = boundedRequired(clientId, "client id", 512);
            displayName = boundedOptional(displayName, 1000);
            provider = boundedRequired(provider, "provider", 100);
            capabilities = Set.copyOf(capabilities == null
                    ? Set.of() : new LinkedHashSet<>(capabilities));
        }
    }

    public AuditEvent {
        operation = boundedRequired(operation, "operation", 200);
        if (outcome == null || actor == null) {
            throw new IllegalArgumentException("audit outcome and actor are required");
        }
        targetOntology = boundedOptional(targetOntology, 4096);
        targetModule = boundedOptional(targetModule, 4096);
        gate = boundedOptional(gate, 50);
        if (gate != null && !Set.of("pass", "fail", "error", "not_applicable").contains(gate)) {
            throw new IllegalArgumentException("audit gate must be pass, fail, error, or not_applicable");
        }
        summary = summary == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
        confirmationReferences = List.copyOf(confirmationReferences == null
                ? List.of() : confirmationReferences);
        if (confirmationReferences.size() > 100) {
            throw new IllegalArgumentException("too many audit confirmation references");
        }
        confirmationReferences = confirmationReferences.stream()
                .map(value -> boundedRequired(value, "confirmation reference", 1000)).toList();
        releaseManifest = boundedOptional(releaseManifest, 4096);
    }

    private static String boundedRequired(String value, String label, int limit) {
        String bounded = boundedOptional(value, limit);
        if (bounded == null || bounded.isBlank()) {
            throw new IllegalArgumentException("audit " + label + " is required");
        }
        return bounded;
    }

    private static String boundedOptional(String value, int limit) {
        if (value == null) return null;
        if (value.length() > limit) throw new IllegalArgumentException("audit text exceeds its bound");
        return value;
    }
}
