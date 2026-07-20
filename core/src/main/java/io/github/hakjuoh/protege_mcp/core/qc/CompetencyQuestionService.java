package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Shared SPARQL competency-question runner and QC-stage projection. */
public final class CompetencyQuestionService {

    public static final String FAIL_ON_NONE = "none";
    public static final String FAIL_ON_ANY = "any";

    private CompetencyQuestionService() {
    }

    public enum ExpectationKind {
        NON_EMPTY, EMPTY, COUNT, EXACT_ROWS
    }

    public record Expectation(ExpectationKind kind, String op, Integer value,
            List<Map<String, Object>> rows) {
        public Expectation {
            if (kind == null) throw new IllegalArgumentException("expectation kind must not be null");
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        public String describe() {
            return switch (kind) {
                case EMPTY -> "empty (0 rows)";
                case COUNT -> "count " + op + " " + value;
                case EXACT_ROWS -> "exactly " + rows.size() + " declared row(s)";
                case NON_EMPTY -> "non-empty (≥1 row)";
            };
        }
    }

    public record Question(String id, String text, Expectation expected, String convention,
            String query, boolean includeInferred) {
        public Question {
            if (expected == null) throw new IllegalArgumentException("expected must not be null");
        }
    }

    public record Judged(boolean pass, String summary, List<String> caveats, String error) {
        public Judged {
            caveats = List.copyOf(caveats);
        }

        public Judged(boolean pass, String summary, List<String> caveats) {
            this(pass, summary, caveats, null);
        }
    }

    public record Result(QcStageExecution execution, Set<String> gatingIdentities) {
        public Result {
            gatingIdentities = Set.copyOf(gatingIdentities);
        }
    }

    public static Result evaluate(QuerySnapshot snapshot, List<Question> questions,
            int limit, long timeoutMs) {
        if (questions == null || questions.isEmpty()) {
            return new Result(QcStageExecution.skipped("cqs",
                    "no competency questions found — add some with add_competency_question."),
                    Set.of());
        }
        Map<String, Object> run = run(snapshot, questions, limit, timeoutMs, FAIL_ON_ANY);
        int failed = number(run.get("failed"));
        Object rows = run.get("questions");
        List<String> identities = InvariantQcService.resultIdentities(rows, "pass");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", run.get("total"));
        summary.put("passed", run.get("passed"));
        summary.put("failed", failed);
        summary.put("identity_digest", QcFindingIdentity.digest(identities));
        int errors = countErrors(rows);
        if (errors > 0) summary.put("errors", errors);
        int caveated = countCaveated(rows);
        if (caveated > 0) summary.put("caveats", caveated);
        int inferenceCaveats = InvariantQcService.countInferenceCaveats(rows);
        if (inferenceCaveats > 0) summary.put("inference_caveats", inferenceCaveats);
        QcStageVerdict verdict = failed > 0 ? QcStageVerdict.FAIL : QcStageVerdict.PASS;
        return new Result(new QcStageExecution("cqs", verdict, null, summary),
                new LinkedHashSet<>(identities));
    }

    public static Map<String, Object> run(QuerySnapshot snapshot, List<Question> questions,
            int limit, long timeoutMs, String failOn) {
        if (snapshot == null || questions == null) {
            throw new IllegalArgumentException("snapshot and questions must not be null");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (Question question : questions) {
            Map<String, Object> result = judge(snapshot, question, limit, timeoutMs);
            if (Boolean.TRUE.equals(result.get("pass"))) passed++;
            else failed++;
            results.add(result);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", results);
        out.put("total", questions.size());
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("gate", gate(failOn, failed));
        return out;
    }

    public static Map<String, Object> judge(QuerySnapshot snapshot, Question question,
            int limit, long timeoutMs) {
        List<String> caveats = new ArrayList<>();
        boolean includeInferred = question.includeInferred;
        if (includeInferred && !snapshot.inferredAvailable()) {
            caveats.add("include_inferred requested but no inferred snapshot was available ("
                    + (snapshot.inferredError() == null
                            ? "run run_reasoner first" : snapshot.inferredError())
                    + "); ran over the asserted triples only");
            includeInferred = false;
        }
        long start = System.nanoTime();
        final Map<String, Object> execution;
        try {
            execution = snapshot.execute(question.query, includeInferred, limit, timeoutMs);
        } catch (RuntimeException error) {
            Map<String, Object> result = base(question, caveats);
            result.put("pass", false);
            result.put("error", message(error));
            result.put("ms", millis(start));
            return result;
        }
        if (includeInferred && snapshot.inferredNote() != null) {
            caveats.add("ran_with_caveat: inferences truncated — " + snapshot.inferredNote());
        }
        Judged judged = judgeExecution(question.expected, execution, limit);
        caveats.addAll(judged.caveats);
        Map<String, Object> result = base(question, caveats);
        result.put("pass", judged.pass);
        if (judged.error != null) result.put("error", judged.error);
        result.put("actual_summary", judged.summary);
        result.put("ms", millis(start));
        return result;
    }

    private static Map<String, Object> base(Question question, List<String> caveats) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.id);
        if (question.text != null) result.put("text", question.text);
        result.put("expected", question.expected.describe());
        result.put("convention", question.convention);
        if (!caveats.isEmpty()) result.put("caveats", caveats);
        return result;
    }

    public static Judged judgeExecution(Expectation expected, Map<String, Object> execution,
            int limit) {
        List<String> caveats = new ArrayList<>();
        boolean truncated = Boolean.TRUE.equals(execution.get("truncated"));
        return switch (expected.kind) {
            case NON_EMPTY -> new Judged(notEmpty(execution), summary(execution), caveats);
            case EMPTY -> {
                caveats.add("open-world caveat: an EMPTY result can hold for the wrong reason "
                        + "(missing data reads the same as a genuine absence) — prefer a non-empty check");
                yield new Judged(!notEmpty(execution), summary(execution), caveats);
            }
            case COUNT -> judgeCount(expected, execution, limit, truncated, caveats);
            case EXACT_ROWS -> judgeExactRows(expected, execution, limit, truncated, caveats);
        };
    }

    private static Judged judgeCount(Expectation expected, Map<String, Object> execution,
            int limit, boolean truncated, List<String> caveats) {
        long count = count(execution);
        if (truncated) {
            if (!"SELECT".equals(execution.get("query_type"))) {
                caveats.add("returned triples truncated at limit " + limit
                        + "; the count is the exact graph size, so the COUNT check is unaffected");
                return new Judged(compare(count, expected.op, expected.value), count + " rows ("
                        + expected.op + " " + expected.value + ")", caveats);
            }
            if ((">=".equals(expected.op) || ">".equals(expected.op))
                    && compare(count, expected.op, expected.value)) {
                caveats.add("results truncated at limit " + limit + "; the capped count is a "
                        + "lower bound on the true count, and " + count + " already satisfies "
                        + expected.op + " " + expected.value);
                return new Judged(true, count + "+ rows (" + expected.op + " "
                        + expected.value + ")", caveats);
            }
            String error = "results truncated at limit " + limit
                    + "; the true count is unknown — raise 'limit' (project QC and the QC "
                    + "suite cap it at 10000) or rewrite the query so the complete result "
                    + "fits for a reliable COUNT";
            caveats.add(error);
            return new Judged(false, count + "+ rows (indeterminate " + expected.op + " "
                    + expected.value + ")", caveats, error);
        }
        return new Judged(compare(count, expected.op, expected.value), count + " rows ("
                + expected.op + " " + expected.value + ")", caveats);
    }

    @SuppressWarnings("unchecked")
    private static Judged judgeExactRows(Expectation expected, Map<String, Object> execution,
            int limit, boolean truncated, List<String> caveats) {
        if (!"SELECT".equals(execution.get("query_type"))) {
            caveats.add("EXACT_ROWS requires a SELECT query; got " + execution.get("query_type"));
            return new Judged(false, String.valueOf(execution.get("query_type")), caveats);
        }
        List<Map<String, Object>> bindings = (List<Map<String, Object>>)
                execution.getOrDefault("bindings", new ArrayList<>());
        if (hasBnode(bindings)) {
            caveats.add("EXACT_ROWS cannot compare blank nodes (their labels are non-deterministic); "
                    + "rewrite the query to project stable IRIs/literals");
            return new Judged(false, bindings.size() + " rows (contain blank nodes)", caveats);
        }
        if (truncated) {
            String error = "results truncated at limit " + limit
                    + "; raise 'limit' (project QC and the QC suite cap it at 10000) or narrow the "
                    + "query so the complete result fits for a reliable EXACT_ROWS check";
            caveats.add(error);
            return new Judged(false, bindings.size() + "+ rows (incomplete)", caveats, error);
        }
        Set<String> got = canonicalizeRows(bindings);
        Set<String> want = canonicalizeRows(expected.rows);
        Set<String> matched = new TreeSet<>(got);
        matched.retainAll(want);
        return new Judged(got.equals(want), matched.size() + " of " + want.size()
                + " declared rows matched (" + got.size() + " distinct rows returned)", caveats);
    }

    public static Set<String> canonicalizeRows(List<Map<String, Object>> rows) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> row : rows) out.add(canonicalRow(row));
        return out;
    }

    private static String canonicalRow(Map<String, Object> row) {
        Map<String, Object> cells = new java.util.TreeMap<>(row);
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, Object> entry : cells.entrySet()) {
            encoded.append(component(entry.getKey()))
                    .append(component(canonicalCell(entry.getValue())));
        }
        return encoded.toString();
    }

    @SuppressWarnings("unchecked")
    private static String canonicalCell(Object cell) {
        if (!(cell instanceof Map)) {
            return component("literal") + component(String.valueOf(cell))
                    + component("") + component("");
        }
        Map<String, Object> map = (Map<String, Object>) cell;
        return component(str(map.get("type"), "literal"))
                + component(str(map.get("value"), ""))
                + component(str(map.get("lang"), "").toLowerCase(Locale.ROOT))
                + component(str(map.get("datatype"), ""));
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }

    private static boolean notEmpty(Map<String, Object> execution) {
        return count(execution) > 0;
    }

    private static long count(Map<String, Object> execution) {
        if ("ASK".equals(String.valueOf(execution.get("query_type")))) {
            return Boolean.TRUE.equals(execution.get("boolean")) ? 1 : 0;
        }
        Object count = execution.get("count");
        return count instanceof Number ? ((Number) count).longValue() : 0;
    }

    private static String summary(Map<String, Object> execution) {
        String type = String.valueOf(execution.get("query_type"));
        if ("ASK".equals(type)) return "ASK " + execution.get("boolean");
        return count(execution) + " " + ("SELECT".equals(type) ? "rows" : "triples");
    }

    private static boolean compare(long count, String operator, int value) {
        return switch (operator) {
            case ">=" -> count >= value;
            case "<=" -> count <= value;
            case "==" -> count == value;
            case ">" -> count > value;
            case "<" -> count < value;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private static boolean hasBnode(List<Map<String, Object>> bindings) {
        for (Map<String, Object> row : bindings) {
            for (Object cell : row.values()) {
                if (cell instanceof Map
                        && "bnode".equals(((Map<String, Object>) cell).get("type"))) return true;
            }
        }
        return false;
    }

    public static String normalizeFailOn(String raw) {
        if (raw == null || raw.trim().isEmpty()) return FAIL_ON_NONE;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (FAIL_ON_NONE.equals(normalized) || FAIL_ON_ANY.equals(normalized)) return normalized;
        throw new IllegalArgumentException("fail_on must be 'none' or 'any' (not '" + raw + "').");
    }

    public static String gate(String failOn, int failed) {
        return FAIL_ON_ANY.equals(failOn) && failed > 0 ? "fail" : "pass";
    }

    public static int countErrors(Object value) {
        if (!(value instanceof List<?> rows)) return 0;
        int count = 0;
        for (Object valueRow : rows) {
            if (valueRow instanceof Map<?, ?> row && row.get("error") != null) count++;
        }
        return count;
    }

    public static int countCaveated(Object value) {
        if (!(value instanceof List<?> rows)) return 0;
        int count = 0;
        for (Object valueRow : rows) {
            if (valueRow instanceof Map<?, ?> row && row.containsKey("caveats")) count++;
        }
        return count;
    }

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long millis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
