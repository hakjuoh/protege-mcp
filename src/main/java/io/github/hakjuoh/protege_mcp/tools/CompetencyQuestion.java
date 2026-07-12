package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A competency question in the storage-neutral normalised form every {@link CqStore} loads into and
 * {@code run_competency_questions} judges — so {@code run} is convention-agnostic (it sees only this
 * model, never a file format). A CQ pairs an executable SPARQL query with an {@link Expectation} (the
 * pass condition) and light metadata (id, natural-language text, type, tags, whether to run over inferred
 * triples). {@code queryLang} is reserved for a future DL path; only {@code sparql} is accepted.
 *
 * <p>Pure and Protégé-free (round-trippable to/from a standalone {@code .rq}, a JSON manifest entry, or an
 * ontology annotation), so it is unit-tested directly.
 */
final class CompetencyQuestion {

    /** {@code id} is stable WITHIN a store, not globally unique (the same id can exist in two stores). */
    String id;
    String text;              // natural-language question (nullable for a bare .rq)
    String type;              // Scoping | Validating | ... (optional)
    String queryLang = "sparql";
    String query;             // executable SPARQL
    boolean includeInferred = true;
    Expectation expected = Expectation.nonEmpty();
    List<String> tags = new ArrayList<>();

    /** Filled in by {@code load}: which convention this instance came from (for reporting/dedup). */
    String convention;

    CompetencyQuestion() {
    }

    /** The JSON shape for a manifest entry / annotation literal (round-trippable). */
    Map<String, Object> toStorageJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (text != null) {
            m.put("text", text);
        }
        if (type != null) {
            m.put("type", type);
        }
        m.put("query_lang", queryLang == null ? "sparql" : queryLang);
        m.put("query", query);
        m.put("include_inferred", includeInferred);
        m.put("expected", expected.toJson());
        if (tags != null && !tags.isEmpty()) {
            m.put("tags", tags);
        }
        return m;
    }

    /** The JSON shape surfaced by {@code list} / {@code run} (adds the resolving convention). */
    Map<String, Object> toReportJson() {
        Map<String, Object> m = toStorageJson();
        m.put("convention", convention);
        m.put("expected", expected.describe());
        return m;
    }

    /** Rebuild a CQ from a manifest/annotation JSON object. Throws {@link ToolArgException} if unusable. */
    @SuppressWarnings("unchecked")
    static CompetencyQuestion fromStorageJson(Map<String, Object> m) {
        CompetencyQuestion cq = new CompetencyQuestion();
        cq.id = str(m.get("id"));
        cq.text = str(m.get("text"));
        cq.type = str(m.get("type"));
        cq.queryLang = m.get("query_lang") == null ? "sparql" : str(m.get("query_lang"));
        cq.query = str(m.get("query"));
        cq.includeInferred = m.get("include_inferred") == null
                || Boolean.parseBoolean(String.valueOf(m.get("include_inferred")));
        cq.expected = Expectation.fromObject(m.get("expected"));
        Object tags = m.get("tags");
        if (tags instanceof List) {
            for (Object t : (List<Object>) tags) {
                if (t != null) {
                    cq.tags.add(String.valueOf(t));
                }
            }
        }
        validate(cq);
        return cq;
    }

    /**
     * Validate a fully-built CQ (id present + filesystem/annotation-safe, a query, a supported query
     * language). Called at load and at write so a bad CQ is skipped-on-load but rejected-on-add.
     */
    static void validate(CompetencyQuestion cq) {
        if (cq.id == null || cq.id.trim().isEmpty()) {
            throw new ToolArgException("A competency question needs a non-empty 'id'.");
        }
        if (cq.query == null || cq.query.trim().isEmpty()) {
            throw new ToolArgException("Competency question '" + cq.id + "' has no 'query'.");
        }
        String lang = cq.queryLang == null ? "sparql" : cq.queryLang.toLowerCase(java.util.Locale.ROOT);
        if (!lang.equals("sparql")) {
            throw new ToolArgException("query_lang '" + cq.queryLang + "' is not supported "
                    + "(only 'sparql'; a DL path is reserved for a later release).");
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }
}
