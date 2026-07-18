package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
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
 * shape (no EDT rendering). A persisted invariant {@code profile} is reserved for a future release;
 * the MVP is the inline {@code queries[]}.
 */
public final class VerifyOntologyTools {

    private VerifyOntologyTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("verify_ontology",
                (ex, req) -> verify(ctx, Tools.args(req)));
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

}
