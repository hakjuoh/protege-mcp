package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code verify_ontology} (F4): run project-defined SPARQL <em>invariants</em> — patterns that must never
 * appear (ROBOT {@code verify}). Each returned row / ASK true is a violation; a violation at or above the
 * {@code fail_on} severity fails the gate. This is the inverse of a competency question and is distinct
 * from SHACL and from the generic {@code validate_ontology} smells.
 *
 * <p>Invariants run over the shared off-EDT snapshot ({@link SuiteSnapshot}); UPDATE/SERVICE are rejected
 * inside {@link SparqlTools#execute}, and violation rows are reported in the {@code nodeJson} binding
 * shape (no EDT rendering). A persisted invariant {@code profile} is reserved for 0.4.1; the MVP is the
 * inline {@code queries[]}.
 */
public final class VerifyOntologyTools {

    private VerifyOntologyTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("verify_ontology",
                "Run project-defined SPARQL INVARIANTS over the active ontology — patterns that must NEVER "
                        + "appear (like ROBOT 'verify'). Each queries[] item is a SPARQL SELECT/ASK whose "
                        + "results are VIOLATIONS: any returned row (or ASK true) flags it, at the item's "
                        + "severity (error | warn | info, default error). The overall 'gate' fails when a "
                        + "violation reaches the 'fail_on' severity (none | info | warn | error, default "
                        + "error). Queries run over a shared snapshot (asserted, or the reasoner's "
                        + "inferences for items with include_inferred=true); UPDATE/SERVICE are rejected. "
                        + "Violations are reported as raw SPARQL bindings (a persisted invariant 'profile' "
                        + "is reserved for a later release).",
                verifySchema(),
                (ex, req) -> Tools.guard(() -> verify(ctx, Tools.args(req))));
    }

    private static CallToolResult verify(ToolContext ctx, Map<String, Object> a) {
        List<Map<String, Object>> queries = Tools.objList(a, "queries");
        if (queries.isEmpty()) {
            return Tools.error("Provide at least one invariant in 'queries' (each: sparql + optional "
                    + "message/severity/id/include_inferred). A persisted 'profile' arrives in a later "
                    + "release.");
        }
        String profile = Tools.optString(a, "profile");
        String failOn = Invariants.normalizeFailOn(Tools.optString(a, "fail_on"));
        int limit = Tools.optInt(a, "limit", 1000);
        if (limit <= 0) {
            limit = 1000;
        }
        int timeout = Tools.optInt(a, "timeout_ms", 120_000);
        if (timeout <= 0) {
            timeout = 120_000;
        }
        List<Invariants.Invariant> invariants = Invariants.parse(queries);   // structural validation
        boolean needInferred = invariants.stream().anyMatch(i -> i.includeInferred);
        SuiteSnapshot snap = ctx.access().compute(mm -> SuiteSnapshot.capture(mm, needInferred));
        Map<String, Object> result = Invariants.run(snap, invariants, limit, timeout, failOn);
        if (profile != null) {
            result.put("note", "The 'profile' (persisted invariant set) argument is reserved for a later "
                    + "release; run inline queries[] for now.");
        }
        return Tools.ok(result);
    }

    /** Hand-assembled schema: {@code queries[]} of invariant objects + profile/fail_on/limit/timeout_ms. */
    private static Map<String, Object> verifySchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("sparql", Tools.stringProperty("A SPARQL SELECT or ASK whose results are VIOLATIONS "
                + "(a returned row / ASK true means the forbidden pattern is present). CONSTRUCT/DESCRIBE "
                + "are rejected — a graph-producing form is not a detector; use sparql_query for those."));
        itemProps.put("id", Tools.stringProperty("Optional stable id (default invariant-N)."));
        itemProps.put("message", Tools.stringProperty("Human message describing the violation."));
        itemProps.put("severity", Tools.stringProperty("error (default) | warn | info."));
        itemProps.put("include_inferred", Tools.boolProperty("Run over the reasoner's inferred triples "
                + "(default false)."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", java.util.Arrays.asList("sparql"));
        item.put("additionalProperties", false);

        Map<String, Object> queries = new LinkedHashMap<>();
        queries.put("type", "array");
        queries.put("items", item);
        queries.put("description", "The invariants to check; each item's results are violations.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("queries", queries);
        properties.put("profile", Tools.stringProperty("Persisted invariant set (reserved for a later "
                + "release; use inline queries[])."));
        properties.put("fail_on", Tools.stringProperty("Gate severity: none | info | warn | error "
                + "(default error)."));
        properties.put("limit", Tools.intProperty("Max violation rows per invariant (default 1000)."));
        properties.put("timeout_ms", Tools.intProperty("Per-invariant time budget in ms (default 120000)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }
}
