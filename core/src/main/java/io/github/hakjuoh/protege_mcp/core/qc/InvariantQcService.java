package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared forbidden-pattern SPARQL invariant runner and QC-stage projection. */
public final class InvariantQcService {

    private InvariantQcService() {
    }

    public record Invariant(String id, String message, String severity,
            String sparql, boolean includeInferred) {
        public Invariant {
            if (id == null || id.isBlank() || sparql == null || sparql.isBlank()) {
                throw new IllegalArgumentException("invariant id and sparql must not be blank");
            }
            severity = normalizeSeverity(severity);
            message = message == null ? "Invariant '" + id + "' violated." : message;
        }
    }

    public record Result(QcStageExecution execution, Set<String> gatingIdentities) {
        public Result {
            gatingIdentities = Set.copyOf(gatingIdentities);
        }
    }

    public static Result evaluate(QuerySnapshot snapshot, List<Invariant> invariants,
            int limit, long timeoutMs) {
        if (invariants == null || invariants.isEmpty()) {
            return new Result(QcStageExecution.skipped("invariants",
                    "no invariants supplied — pass an 'invariants' array "
                            + "(same shape as verify_ontology's queries[])."), Set.of());
        }
        Map<String, Object> run = run(snapshot, invariants, limit, timeoutMs, "error");
        int violations = number(run.get("violations"));
        int errors = number(run.get("errors"));
        Object rows = run.get("invariants");
        List<String> identities = resultIdentities(rows, "violated");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checked", run.get("checked"));
        summary.put("violations", violations);
        summary.put("errors", errors);
        summary.put("identity_digest", QcFindingIdentity.digest(identities));
        int inferenceCaveats = countInferenceCaveats(rows);
        if (inferenceCaveats > 0) summary.put("inference_caveats", inferenceCaveats);
        int issueLevel = issueLevel(rows);
        QcStageVerdict verdict = issueLevel >= 3 ? QcStageVerdict.FAIL
                : issueLevel == 2 ? QcStageVerdict.WARNING
                : issueLevel == 1 ? QcStageVerdict.INFO : QcStageVerdict.PASS;
        return new Result(new QcStageExecution("invariants", verdict, null, summary),
                new LinkedHashSet<>(identities));
    }

    public static Map<String, Object> run(QuerySnapshot snapshot, List<Invariant> invariants,
            int limit, long timeoutMs, String failOn) {
        if (snapshot == null || invariants == null) {
            throw new IllegalArgumentException("snapshot and invariants must not be null");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int violations = 0;
        int errors = 0;
        int worst = 0;
        for (Invariant invariant : invariants) {
            if (invariant.includeInferred && !snapshot.inferredAvailable()) {
                errors++;
                worst = Math.max(worst, level(invariant.severity));
                results.add(errored(invariant,
                        "invariant did not run: include_inferred requested but no "
                                + "inferred snapshot is available ("
                                + (snapshot.inferredError() == null
                                        ? "run run_reasoner first" : snapshot.inferredError())
                                + "); an inference-only violation cannot be checked, so this invariant "
                                + "fails closed"));
                continue;
            }
            final Map<String, Object> execution;
            try {
                execution = snapshot.execute(invariant.sparql, invariant.includeInferred,
                        limit, timeoutMs);
            } catch (RuntimeException error) {
                errors++;
                worst = Math.max(worst, level(invariant.severity));
                results.add(errored(invariant, "invariant did not run: " + message(error)));
                continue;
            }
            String type = String.valueOf(execution.get("query_type"));
            if (!"SELECT".equals(type) && !"ASK".equals(type)) {
                errors++;
                worst = Math.max(worst, level(invariant.severity));
                results.add(errored(invariant,
                        "invariant rejected: verify_ontology invariants must be a SELECT "
                                + "(whose rows are the violations) or an ASK (true = the forbidden "
                                + "pattern is present); this one is a " + type + ". Express the check "
                                + "as a SELECT/ASK. (CONSTRUCT/DESCRIBE remain available in "
                                + "sparql_query.)"));
                continue;
            }
            Map<String, Object> result = judgeExecution(invariant, execution);
            if (invariant.includeInferred && snapshot.inferredNote() != null) {
                result.put("caveats", new ArrayList<>(List.of(
                        "ran_with_caveat: inferences truncated — " + snapshot.inferredNote())));
            }
            if (Boolean.TRUE.equals(result.get("violated"))) {
                violations++;
                worst = Math.max(worst, level(invariant.severity));
            }
            results.add(result);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", invariants.size());
        out.put("violations", violations);
        out.put("errors", errors);
        out.put("fail_on", failOn);
        out.put("gate", worst >= threshold(failOn) ? "fail" : "pass");
        out.put("invariants", results);
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> judgeExecution(Invariant invariant,
            Map<String, Object> execution) {
        boolean violated = hasResults(execution);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", invariant.id);
        result.put("severity", invariant.severity);
        result.put("message", invariant.message);
        result.put("violated", violated);
        if (!violated) return result;
        String type = String.valueOf(execution.get("query_type"));
        if ("SELECT".equals(type)) {
            List<Map<String, Object>> bindings = (List<Map<String, Object>>)
                    execution.getOrDefault("bindings", new ArrayList<>());
            result.put("violation_count", bindings.size());
            result.put("violations", bindings);
            if (Boolean.TRUE.equals(execution.get("truncated"))) result.put("truncated", true);
        } else if ("ASK".equals(type)) {
            result.put("violation_count", 1);
        } else {
            result.put("violation_count", execution.get("count"));
            result.put("turtle", execution.get("turtle"));
        }
        return result;
    }

    private static Map<String, Object> errored(Invariant invariant, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", invariant.id);
        result.put("severity", invariant.severity);
        result.put("message", invariant.message);
        result.put("violated", false);
        result.put("error", error);
        return result;
    }

    private static boolean hasResults(Map<String, Object> execution) {
        if ("ASK".equals(String.valueOf(execution.get("query_type")))) {
            return Boolean.TRUE.equals(execution.get("boolean"));
        }
        Object count = execution.get("count");
        return count instanceof Number && ((Number) count).longValue() > 0;
    }

    private static int issueLevel(Object value) {
        int worst = 0;
        if (!(value instanceof List<?> rows)) return worst;
        for (Object valueRow : rows) {
            if (!(valueRow instanceof Map<?, ?> row)) continue;
            if (!Boolean.TRUE.equals(row.get("violated")) && row.get("error") == null) continue;
            String severity = String.valueOf(row.get("severity"));
            worst = Math.max(worst, "error".equals(severity) ? 3
                    : "warn".equals(severity) ? 2 : 1);
        }
        return worst;
    }

    public static List<String> resultIdentities(Object value, String verdictKey) {
        List<String> identities = new ArrayList<>();
        if (!(value instanceof List<?> rows)) return identities;
        for (Object valueRow : rows) {
            if (!(valueRow instanceof Map<?, ?> row)) continue;
            boolean failed = "pass".equals(verdictKey)
                    ? !Boolean.TRUE.equals(row.get(verdictKey))
                    : Boolean.TRUE.equals(row.get(verdictKey)) || row.get("error") != null;
            if (!failed) continue;
            Map<String, Object> stable = new LinkedHashMap<>();
            row.forEach((key, entryValue) -> {
                if (!"ms".equals(String.valueOf(key))) {
                    stable.put(String.valueOf(key), entryValue);
                }
            });
            identities.add(QcFindingIdentity.object(stable));
        }
        return identities;
    }

    public static int countInferenceCaveats(Object value) {
        if (!(value instanceof List<?> rows)) return 0;
        int count = 0;
        for (Object valueRow : rows) {
            if (!(valueRow instanceof Map<?, ?> row)) continue;
            Object caveats = row.get("caveats");
            if (!(caveats instanceof List<?> items)) continue;
            boolean affected = items.stream().map(String::valueOf)
                    .anyMatch(caveat -> caveat.startsWith("include_inferred requested")
                            || caveat.startsWith("ran_with_caveat: inferences truncated"));
            if (affected) count++;
        }
        return count;
    }

    private static String normalizeSeverity(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "error";
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "error" -> "error";
            case "warn", "warning" -> "warn";
            case "info" -> "info";
            default -> throw new IllegalArgumentException(
                    "Unknown severity '" + raw + "'. Use error, warn, or info.");
        };
    }

    private static int level(String severity) {
        return switch (severity) {
            case "error" -> 3;
            case "warn" -> 2;
            case "info" -> 1;
            default -> 0;
        };
    }

    private static int threshold(String failOn) {
        return switch (failOn) {
            case "info" -> 1;
            case "warn" -> 2;
            case "error" -> 3;
            default -> Integer.MAX_VALUE;
        };
    }

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
