package io.github.hakjuoh.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Judges competency questions against a {@linkplain SuiteSnapshot shared point-in-time snapshot} and
 * aggregates the run into one verdict. Identical for every SPARQL convention — {@code run} sees only the
 * normalised {@link CompetencyQuestion} model, never a file format.
 *
 * <p>The judging table ({@link #judgeExec}) and EXACT_ROWS canonicalisation ({@link #canonicalizeRows})
 * are pure functions over the {@link SparqlTools#execute} result shape, so they are unit-tested directly;
 * the mandatory caveats (open-world EMPTY, truncated results, truncated inferences) are surfaced, never
 * silent.
 */
final class CqRunner {

    private CqRunner() {
    }

    static final String FAIL_ON_NONE = "none";
    static final String FAIL_ON_ANY = "any";

    /** Run every CQ against {@code snap} and aggregate {@code {questions, passed, failed, total, gate}}. */
    static Map<String, Object> run(SuiteSnapshot snap, List<CompetencyQuestion> cqs, int limit,
            long timeoutMs, String failOn) {
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (CompetencyQuestion cq : cqs) {
            Map<String, Object> r = judge(snap, cq, limit, timeoutMs);
            if (Boolean.TRUE.equals(r.get("pass"))) {
                passed++;
            } else {
                failed++;
            }
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", results);
        out.put("total", cqs.size());
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("gate", gate(failOn, failed));
        return out;
    }

    /** Execute + judge a single CQ (degrading include_inferred to a caveat when no inferred snapshot). */
    static Map<String, Object> judge(SuiteSnapshot snap, CompetencyQuestion cq, int limit, long timeoutMs) {
        List<String> caveats = new ArrayList<>();
        boolean includeInferred = cq.includeInferred;
        if (includeInferred && !snap.inferredAvailable()) {
            caveats.add("include_inferred requested but no inferred snapshot was available ("
                    + (snap.inferredError() == null ? "run run_reasoner first" : snap.inferredError())
                    + "); ran over the asserted triples only");
            includeInferred = false;
        }
        long start = System.nanoTime();
        Map<String, Object> exec;
        try {
            exec = snap.execute(cq.query, includeInferred, limit, timeoutMs);
        } catch (RuntimeException e) {
            Map<String, Object> r = base(cq, caveats);
            r.put("pass", false);
            r.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            r.put("ms", millis(start));
            return r;
        }
        if (includeInferred && snap.inferredNote() != null) {
            caveats.add("ran_with_caveat: inferences truncated — " + snap.inferredNote());
        }
        Judged judged = judgeExec(cq.expected, exec, limit);
        caveats.addAll(judged.caveats);
        Map<String, Object> r = base(cq, caveats);
        r.put("pass", judged.pass);
        r.put("actual_summary", judged.summary);
        r.put("ms", millis(start));
        return r;
    }

    private static Map<String, Object> base(CompetencyQuestion cq, List<String> caveats) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", cq.id);
        if (cq.text != null) {
            r.put("text", cq.text);
        }
        r.put("expected", cq.expected.describe());
        r.put("convention", cq.convention);
        if (!caveats.isEmpty()) {
            r.put("caveats", caveats);
        }
        return r;
    }

    // ================================================================== pure judging

    /** The pass/summary/caveats for an {@link Expectation} against a {@link SparqlTools#execute} result. */
    static Judged judgeExec(Expectation expected, Map<String, Object> exec, int limit) {
        List<String> caveats = new ArrayList<>();
        boolean truncated = Boolean.TRUE.equals(exec.get("truncated"));
        switch (expected.kind) {
            case NON_EMPTY:
                return new Judged(notEmpty(exec), summary(exec), caveats);
            case EMPTY:
                caveats.add("open-world caveat: an EMPTY result can hold for the wrong reason (missing "
                        + "data reads the same as a genuine absence) — prefer a non-empty check");
                return new Judged(!notEmpty(exec), summary(exec), caveats);
            case COUNT: {
                long n = count(exec);
                if (truncated) {
                    caveats.add("results truncated at limit " + limit + "; the true count may be higher — "
                            + "raise 'limit' for a reliable COUNT");
                }
                return new Judged(compare(n, expected.op, expected.value), n + " rows ("
                        + expected.op + " " + expected.value + ")", caveats);
            }
            case EXACT_ROWS:
                return judgeExactRows(expected, exec, limit, truncated, caveats);
            default:
                return new Judged(false, "unknown expectation", caveats);
        }
    }

    private static Judged judgeExactRows(Expectation expected, Map<String, Object> exec, int limit,
            boolean truncated, List<String> caveats) {
        if (!"SELECT".equals(exec.get("query_type"))) {
            caveats.add("EXACT_ROWS requires a SELECT query; got " + exec.get("query_type"));
            return new Judged(false, String.valueOf(exec.get("query_type")), caveats);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) exec.getOrDefault("bindings",
                new ArrayList<>());
        if (hasBnode(bindings)) {
            caveats.add("EXACT_ROWS cannot compare blank nodes (their labels are non-deterministic); "
                    + "rewrite the query to project stable IRIs/literals");
            return new Judged(false, bindings.size() + " rows (contain blank nodes)", caveats);
        }
        if (truncated || bindings.size() >= limit) {
            caveats.add("results reached the limit " + limit + "; raise 'limit' above the declared row "
                    + "count for a reliable EXACT_ROWS check");
        }
        Set<String> got = canonicalizeRows(bindings);
        Set<String> want = canonicalizeRows(expected.rows);
        return new Judged(got.equals(want), got.size() + " of " + want.size() + " declared rows matched",
                caveats);
    }

    /** Order-insensitive canonical form of a SELECT result set: each row → a sorted var=cell string. */
    static Set<String> canonicalizeRows(List<Map<String, Object>> rows) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            out.add(canonicalRow(row));
        }
        return out;
    }

    private static String canonicalRow(Map<String, Object> row) {
        Set<String> cells = new TreeSet<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            cells.add(e.getKey() + "=" + canonicalCell(e.getValue()));
        }
        return String.join("", cells);
    }

    @SuppressWarnings("unchecked")
    private static String canonicalCell(Object cell) {
        if (!(cell instanceof Map)) {
            return "literal|" + String.valueOf(cell) + "||";
        }
        Map<String, Object> m = (Map<String, Object>) cell;
        return str(m.get("type"), "literal") + "|" + str(m.get("value"), "") + "|"
                + str(m.get("lang"), "") + "|" + str(m.get("datatype"), "");
    }

    // ================================================================== result predicates

    private static boolean notEmpty(Map<String, Object> exec) {
        String type = String.valueOf(exec.get("query_type"));
        if ("ASK".equals(type)) {
            return Boolean.TRUE.equals(exec.get("boolean"));
        }
        return count(exec) > 0;
    }

    private static long count(Map<String, Object> exec) {
        String type = String.valueOf(exec.get("query_type"));
        if ("ASK".equals(type)) {
            return Boolean.TRUE.equals(exec.get("boolean")) ? 1 : 0;
        }
        Object c = exec.get("count");
        return c instanceof Number ? ((Number) c).longValue() : 0;
    }

    private static String summary(Map<String, Object> exec) {
        String type = String.valueOf(exec.get("query_type"));
        if ("ASK".equals(type)) {
            return "ASK " + exec.get("boolean");
        }
        return count(exec) + " " + ("SELECT".equals(type) ? "rows" : "triples");
    }

    private static boolean compare(long n, String op, int value) {
        switch (op) {
            case ">=":
                return n >= value;
            case "<=":
                return n <= value;
            case "==":
                return n == value;
            case ">":
                return n > value;
            case "<":
                return n < value;
            default:
                return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasBnode(List<Map<String, Object>> bindings) {
        for (Map<String, Object> row : bindings) {
            for (Object cell : row.values()) {
                if (cell instanceof Map && "bnode".equals(((Map<String, Object>) cell).get("type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================== gate

    static String normalizeFailOn(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return FAIL_ON_NONE;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals(FAIL_ON_NONE) || s.equals(FAIL_ON_ANY)) {
            return s;
        }
        throw new ToolArgException("fail_on must be 'none' or 'any' (not '" + raw + "').");
    }

    static String gate(String failOn, int failed) {
        return FAIL_ON_ANY.equals(failOn) && failed > 0 ? "fail" : "pass";
    }

    private static long millis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    /** A single CQ's verdict + human summary + caveats. */
    static final class Judged {
        final boolean pass;
        final String summary;
        final List<String> caveats;

        Judged(boolean pass, String summary, List<String> caveats) {
            this.pass = pass;
            this.summary = summary;
            this.caveats = caveats;
        }
    }
}
