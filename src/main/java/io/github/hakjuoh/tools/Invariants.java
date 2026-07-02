package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The forbidden-pattern invariant engine behind {@code verify_ontology} (F4) — the ROBOT {@code verify}
 * model: each invariant is a SPARQL SELECT/ASK describing a pattern that <em>must never appear</em>, so a
 * returned row / ASK true is a <em>violation</em> (the inverse of a competency question). Invariants run
 * over the {@linkplain SuiteSnapshot shared off-EDT snapshot}; violation rows are reported in the
 * {@code nodeJson} binding shape {@link SparqlTools#execute} already produces — never rendered through the
 * EDT-only {@code mm.getRendering}.
 *
 * <p>Parsing/judging/gating are pure functions over the execute-result shape, so they are unit-tested
 * directly. A violation at or above the {@code fail_on} severity fails the gate.
 */
final class Invariants {

    private Invariants() {
    }

    /** One project invariant. */
    static final class Invariant {
        final String id;
        final String message;
        final String severity;      // error | warn | info
        final String sparql;
        final boolean includeInferred;

        Invariant(String id, String message, String severity, String sparql, boolean includeInferred) {
            this.id = id;
            this.message = message;
            this.severity = severity;
            this.sparql = sparql;
            this.includeInferred = includeInferred;
        }
    }

    // ================================================================== parsing

    /** Parse + structurally validate the {@code queries[]} array (each needs a {@code sparql}). */
    static List<Invariant> parse(List<Map<String, Object>> queries) {
        List<Invariant> out = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            Map<String, Object> q = queries.get(i);
            String sparql = Tools.optString(q, "sparql");
            if (sparql == null) {
                throw new ToolArgException("Invariant #" + i + " has no 'sparql'. Each queries[] item "
                        + "needs a SPARQL SELECT/ASK whose results are violations.");
            }
            String id = Tools.optString(q, "id");
            if (id == null) {
                id = "invariant-" + (i + 1);
            }
            String message = Tools.optString(q, "message");
            if (message == null) {
                message = "Invariant '" + id + "' violated.";
            }
            String severity = normalizeSeverity(Tools.optString(q, "severity"));
            boolean includeInferred = Tools.optBool(q, "include_inferred", false);
            out.add(new Invariant(id, message, severity, sparql, includeInferred));
        }
        return out;
    }

    // ================================================================== running

    /** Run every invariant against {@code snap} and aggregate {@code {invariants, checked, violations, gate}}. */
    static Map<String, Object> run(SuiteSnapshot snap, List<Invariant> invariants, int limit,
            long timeoutMs, String failOn) {
        List<Map<String, Object>> results = new ArrayList<>();
        int violations = 0;
        int errors = 0;
        int worst = 0;
        for (Invariant inv : invariants) {
            // An invariant that only manifests over inferred triples cannot be checked when no inferred
            // materialisation is available (no classified reasoner): it is a check that CANNOT RUN, so it
            // fails CLOSED at its own severity rather than degrading to the asserted triples and possibly
            // reporting a pass — an inference-only violation would otherwise be silently missed. This is
            // stricter than the CQ runner's caveat-and-continue, because verify_ontology is a gate whose
            // contract is "a check that cannot run never reports a false pass".
            if (inv.includeInferred && !snap.inferredAvailable()) {
                errors++;
                worst = Math.max(worst, level(inv.severity));
                results.add(errored(inv, "invariant did not run: include_inferred requested but no "
                        + "inferred snapshot is available ("
                        + (snap.inferredError() == null ? "run run_reasoner first" : snap.inferredError())
                        + "); an inference-only violation cannot be checked, so this invariant fails "
                        + "closed"));
                continue;
            }
            Map<String, Object> exec;
            try {
                exec = snap.execute(inv.sparql, inv.includeInferred, limit, timeoutMs);
            } catch (RuntimeException e) {
                // A check that could not run is NOT a pass: gate fail-closed at the invariant's severity.
                errors++;
                worst = Math.max(worst, level(inv.severity));
                results.add(errored(inv, "invariant did not run: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
                continue;
            }
            // A forbidden-pattern detector must be a SELECT (rows = violations) or an ASK (true = present);
            // a graph-producing CONSTRUCT/DESCRIBE is a transformation/exploration form, not a detector,
            // and its turtle output is not the "raw bindings" violation evidence this tool contracts. It
            // slips in only because the shared SparqlTools.execute serves the general sparql_query tool,
            // so reject it fail-closed (CONSTRUCT/DESCRIBE remain available there).
            String type = String.valueOf(exec.get("query_type"));
            if (!"SELECT".equals(type) && !"ASK".equals(type)) {
                errors++;
                worst = Math.max(worst, level(inv.severity));
                results.add(errored(inv, "invariant rejected: verify_ontology invariants must be a SELECT "
                        + "(whose rows are the violations) or an ASK (true = the forbidden pattern is "
                        + "present); this one is a " + type + ". Express the check as a SELECT/ASK. "
                        + "(CONSTRUCT/DESCRIBE remain available in sparql_query.)"));
                continue;
            }
            Map<String, Object> r = judgeExec(inv, exec, limit);
            if (inv.includeInferred && snap.inferredNote() != null) {
                r.put("caveats", new ArrayList<>(List.of(
                        "ran_with_caveat: inferences truncated — " + snap.inferredNote())));
            }
            if (Boolean.TRUE.equals(r.get("violated"))) {
                violations++;
                worst = Math.max(worst, level(inv.severity));
            }
            results.add(r);
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

    /** The per-invariant report for an execute result (pure; violation rows are the nodeJson bindings). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> judgeExec(Invariant inv, Map<String, Object> exec, int limit) {
        boolean violated = hasResults(exec);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", inv.id);
        r.put("severity", inv.severity);
        r.put("message", inv.message);
        r.put("violated", violated);
        if (!violated) {
            return r;
        }
        String type = String.valueOf(exec.get("query_type"));
        if ("SELECT".equals(type)) {
            List<Map<String, Object>> bindings = (List<Map<String, Object>>) exec.getOrDefault("bindings",
                    new ArrayList<>());
            r.put("violation_count", bindings.size());
            r.put("violations", bindings);   // already the {type,value,lang?,datatype?} binding shape
            if (Boolean.TRUE.equals(exec.get("truncated"))) {
                r.put("truncated", true);
            }
        } else if ("ASK".equals(type)) {
            r.put("violation_count", 1);
        } else {
            // CONSTRUCT / DESCRIBE — report the triple count and the turtle evidence.
            r.put("violation_count", exec.get("count"));
            r.put("turtle", exec.get("turtle"));
        }
        return r;
    }

    /** Report a check that did not produce a usable verdict (could not run, or was rejected). */
    private static Map<String, Object> errored(Invariant inv, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", inv.id);
        r.put("severity", inv.severity);
        r.put("message", inv.message);
        r.put("violated", false);
        r.put("error", error);
        return r;
    }

    private static boolean hasResults(Map<String, Object> exec) {
        String type = String.valueOf(exec.get("query_type"));
        if ("ASK".equals(type)) {
            return Boolean.TRUE.equals(exec.get("boolean"));
        }
        Object count = exec.get("count");
        return count instanceof Number && ((Number) count).longValue() > 0;
    }

    // ================================================================== severity / gate

    static String normalizeSeverity(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "error";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "error":
            case "warn":
            case "warning":
                return s.equals("warning") ? "warn" : s;
            case "info":
                return "info";
            default:
                throw new ToolArgException("Unknown severity '" + raw + "'. Use error, warn, or info.");
        }
    }

    static String normalizeFailOn(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "error";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "none":
            case "info":
            case "warn":
            case "error":
                return s;
            default:
                throw new ToolArgException("fail_on must be none, info, warn, or error (not '" + raw
                        + "').");
        }
    }

    private static int level(String severity) {
        switch (severity) {
            case "error":
                return 3;
            case "warn":
                return 2;
            case "info":
                return 1;
            default:
                return 0;
        }
    }

    /** The minimum violated-severity level that fails the gate ({@code none} → never). */
    private static int threshold(String failOn) {
        switch (failOn) {
            case "info":
                return 1;
            case "warn":
                return 2;
            case "error":
                return 3;
            case "none":
            default:
                return Integer.MAX_VALUE;
        }
    }
}
