package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A competency question's <em>expected result</em> — the pass condition {@code run_competency_questions}
 * evaluates a query against. One of four kinds:
 *
 * <ul>
 *   <li>{@code NON_EMPTY} (the default) — ≥1 row / ASK true. The scoping-question shape.</li>
 *   <li>{@code EMPTY} — 0 rows / ASK false. Open-world-fragile (surfaced as a caveat).</li>
 *   <li>{@code COUNT} — the row count compared to a value with an operator ({@code >=,<=,==,>,<}).</li>
 *   <li>{@code EXACT_ROWS} — the returned SELECT row-set equals a declared set (order-insensitive,
 *       literal-normalised, bnodes rejected).</li>
 * </ul>
 *
 * <p>Parsed from three sources (the {@code add} tool's compact string, a manifest JSON object, or a
 * {@code .rq} header line) into one normalised form, and serialised back for round-tripping. Pure and
 * Protégé-free, so it is unit-tested directly.
 */
final class Expectation {

    enum Kind {
        NON_EMPTY, EMPTY, COUNT, EXACT_ROWS
    }

    private static final List<String> OPS = Arrays.asList(">=", "<=", "==", ">", "<");

    final Kind kind;
    final String op;                        // COUNT only
    final Integer value;                    // COUNT only
    final List<Map<String, Object>> rows;   // EXACT_ROWS only (each: var → {type,value,lang?,datatype?})

    private Expectation(Kind kind, String op, Integer value, List<Map<String, Object>> rows) {
        this.kind = kind;
        this.op = op;
        this.value = value;
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    static Expectation nonEmpty() {
        return new Expectation(Kind.NON_EMPTY, null, null, null);
    }

    static Expectation empty() {
        return new Expectation(Kind.EMPTY, null, null, null);
    }

    static Expectation count(String op, int value) {
        String normalized = normalizeOp(op);
        return new Expectation(Kind.COUNT, normalized, value, null);
    }

    static Expectation exactRows(List<Map<String, Object>> rows) {
        return new Expectation(Kind.EXACT_ROWS, null, null, rows);
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parse an {@code expected} spec from any source: {@code null}/blank → NON_EMPTY; a String uses the
     * compact grammar ({@link #fromString}); a Map is the structured form ({@code {kind, op, value,
     * rows}}). Throws {@link ToolArgException} for a malformed spec (validate-at-write).
     */
    @SuppressWarnings("unchecked")
    static Expectation fromObject(Object spec) {
        if (spec == null) {
            return nonEmpty();
        }
        if (spec instanceof Map) {
            return fromMap((Map<String, Object>) spec);
        }
        return fromString(String.valueOf(spec));
    }

    /**
     * The compact string grammar for the {@code add} tool and {@code .rq} headers: {@code nonEmpty} |
     * {@code empty} | {@code count OP N} (e.g. {@code "count >= 3"}). EXACT_ROWS is not expressible as a
     * bare string — author it in a manifest / structured spec.
     */
    static Expectation fromString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return nonEmpty();
        }
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("nonempty") || lower.equals("non_empty") || lower.equals("non-empty")) {
            return nonEmpty();
        }
        if (lower.equals("empty")) {
            return empty();
        }
        if (lower.startsWith("count")) {
            String rest = s.substring("count".length()).trim();
            // Longest operators first so ">=" beats ">"; a bare "=" is accepted (→ "==" via count()).
            for (String op : Arrays.asList(">=", "<=", "==", "=", ">", "<")) {
                if (rest.startsWith(op)) {
                    return count(op, parseInt(rest.substring(op.length()).trim(), s));
                }
            }
            throw new ToolArgException("Malformed 'expected' count spec: '" + raw + "'. Use e.g. "
                    + "'count >= 3' (operators: >=, <=, ==, >, <).");
        }
        throw new ToolArgException("Unknown 'expected' spec: '" + raw + "'. Use nonEmpty, empty, or "
                + "'count OP N' (e.g. 'count >= 3').");
    }

    @SuppressWarnings("unchecked")
    private static Expectation fromMap(Map<String, Object> m) {
        String kindRaw = String.valueOf(m.getOrDefault("kind", "nonEmpty")).trim();
        String kind = kindRaw.toLowerCase(Locale.ROOT).replace("_", "");
        switch (kind) {
            case "nonempty":
                return nonEmpty();
            case "empty":
                return empty();
            case "count":
                Object v = m.get("value");
                if (!(v instanceof Number)) {
                    throw new ToolArgException("'expected' count needs a numeric 'value'.");
                }
                return count(String.valueOf(m.getOrDefault("op", ">=")), ((Number) v).intValue());
            case "exactrows":
                Object r = m.get("rows");
                List<Map<String, Object>> rows = new ArrayList<>();
                if (r instanceof List) {
                    for (Object row : (List<Object>) r) {
                        if (row instanceof Map) {
                            rows.add(canonicalRow((Map<String, Object>) row));
                        }
                    }
                }
                return exactRows(rows);
            default:
                throw new ToolArgException("Unknown 'expected.kind': '" + kindRaw + "'. Use nonEmpty, "
                        + "empty, count, or exactRows.");
        }
    }

    /**
     * Lift a declared EXACT_ROWS row to the {@code nodeJson} cell shape used by SELECT bindings: each
     * cell value becomes {@code {type,value,lang?,datatype?}}. A bare String cell is expanded — an
     * absolute IRI → a {@code uri} cell, else a plain {@code literal}; a Map cell is normalised in place.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> canonicalRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Object cell = e.getValue();
            if (cell instanceof Map) {
                out.put(e.getKey(), canonicalCell((Map<String, Object>) cell));
            } else {
                out.put(e.getKey(), cellFromScalar(String.valueOf(cell)));
            }
        }
        return out;
    }

    private static Map<String, Object> canonicalCell(Map<String, Object> cell) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = cell.get("type") == null ? "literal" : String.valueOf(cell.get("type"));
        out.put("type", type);
        out.put("value", cell.get("value") == null ? "" : String.valueOf(cell.get("value")));
        if (cell.get("lang") != null) {
            out.put("lang", String.valueOf(cell.get("lang")));
        }
        if (cell.get("datatype") != null) {
            out.put("datatype", String.valueOf(cell.get("datatype")));
        }
        return out;
    }

    private static Map<String, Object> cellFromScalar(String s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (EntityResolver.asIri(s) != null) {
            out.put("type", "uri");
            out.put("value", s);
        } else {
            out.put("type", "literal");
            out.put("value", s);
        }
        return out;
    }

    // ------------------------------------------------------------------ serialization

    /** The structured form for a manifest / annotation JSON: {@code {kind, op?, value?, rows?}}. */
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (kind) {
            case NON_EMPTY:
                m.put("kind", "nonEmpty");
                break;
            case EMPTY:
                m.put("kind", "empty");
                break;
            case COUNT:
                m.put("kind", "count");
                m.put("op", op);
                m.put("value", value);
                break;
            case EXACT_ROWS:
                m.put("kind", "exactRows");
                m.put("rows", rows);
                break;
            default:
                break;
        }
        return m;
    }

    /** The compact header form for a {@code .rq} file comment (EXACT_ROWS carries JSON-encoded rows). */
    String toHeaderString() {
        switch (kind) {
            case EMPTY:
                return "empty";
            case COUNT:
                return "count " + op + " " + value;
            case EXACT_ROWS:
                return "exactRows " + Cq.JSON.toJsonOrEmpty(rows);
            case NON_EMPTY:
            default:
                return "nonEmpty";
        }
    }

    /** Parse a {@code .rq} header {@code expected} value (the compact string, or {@code exactRows <json>}). */
    @SuppressWarnings("unchecked")
    static Expectation fromHeaderString(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return nonEmpty();
        }
        String s = raw.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("exactrows")) {
            String json = s.substring("exactrows".length()).trim();
            List<Map<String, Object>> rows = new ArrayList<>();
            Object parsed = Cq.JSON.readOrNull(json);
            if (parsed instanceof List) {
                for (Object row : (List<Object>) parsed) {
                    if (row instanceof Map) {
                        rows.add(canonicalRow((Map<String, Object>) row));
                    }
                }
            }
            return exactRows(rows);
        }
        return fromString(s);
    }

    /** A short human description for the run report. */
    String describe() {
        switch (kind) {
            case EMPTY:
                return "empty (0 rows)";
            case COUNT:
                return "count " + op + " " + value;
            case EXACT_ROWS:
                return "exactly " + rows.size() + " declared row(s)";
            case NON_EMPTY:
            default:
                return "non-empty (≥1 row)";
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String normalizeOp(String op) {
        String o = op == null ? ">=" : op.trim();
        if (o.equals("=")) {
            o = "==";
        }
        if (!OPS.contains(o)) {
            throw new ToolArgException("Unknown count operator '" + op + "'. Use one of "
                    + String.join(", ", OPS) + ".");
        }
        return o;
    }

    private static int parseInt(String s, String context) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new ToolArgException("Malformed 'expected' spec '" + context + "': '" + s
                    + "' is not an integer.");
        }
    }
}
