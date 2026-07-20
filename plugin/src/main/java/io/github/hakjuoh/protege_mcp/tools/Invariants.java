package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.core.qc.InvariantQcService;

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

        InvariantQcService.Invariant shared() {
            return new InvariantQcService.Invariant(id, message, severity, sparql, includeInferred);
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
        return InvariantQcService.run(snap.shared(),
                invariants.stream().map(Invariant::shared).toList(), limit, timeoutMs, failOn);
    }

    /** The per-invariant report for an execute result (pure; violation rows are the nodeJson bindings). */
    static Map<String, Object> judgeExec(Invariant inv, Map<String, Object> exec, int limit) {
        return InvariantQcService.judgeExecution(inv.shared(), exec);
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

}
