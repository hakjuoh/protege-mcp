package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Method-level tests for the pure CQ judging core {@link CqRunner}. */
class CqRunnerTest {

    // ------------------------------------------------------------------ exec fixtures

    private static Map<String, Object> select(int count, boolean truncated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query_type", "SELECT");
        m.put("count", count);
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("s", cell("uri", "http://ex/" + i, null, null));
            bindings.add(row);
        }
        m.put("bindings", bindings);
        if (truncated) {
            m.put("truncated", true);
        }
        return m;
    }

    private static Map<String, Object> selectBindings(List<Map<String, Object>> bindings,
            boolean truncated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query_type", "SELECT");
        result.put("count", bindings.size());
        result.put("bindings", bindings);
        if (truncated) {
            result.put("truncated", true);
        }
        return result;
    }

    private static Map<String, Object> graph(String type, long count, boolean truncated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query_type", type);
        m.put("count", count);
        m.put("turtle", "");
        if (truncated) {
            m.put("truncated", true);
        }
        return m;
    }

    private static Map<String, Object> ask(boolean value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query_type", "ASK");
        m.put("boolean", value);
        return m;
    }

    private static Map<String, Object> cell(String type, String value, String lang, String datatype) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("value", value);
        if (lang != null) {
            c.put("lang", lang);
        }
        if (datatype != null) {
            c.put("datatype", datatype);
        }
        return c;
    }

    // ------------------------------------------------------------------ NON_EMPTY / EMPTY

    @Test
    void nonEmptyPassesWithRowsAndAskTrue() {
        assertTrue(CqRunner.judgeExec(Expectation.nonEmpty(), select(2, false), 1000).pass);
        assertTrue(CqRunner.judgeExec(Expectation.nonEmpty(), ask(true), 1000).pass);
        assertFalse(CqRunner.judgeExec(Expectation.nonEmpty(), select(0, false), 1000).pass);
        assertFalse(CqRunner.judgeExec(Expectation.nonEmpty(), ask(false), 1000).pass);
    }

    @Test
    void emptyPassesWithNoRowsAndCarriesOpenWorldCaveat() {
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.empty(), select(0, false), 1000);
        assertTrue(j.pass);
        assertTrue(j.caveats.stream().anyMatch(c -> c.toLowerCase().contains("open-world")),
                "EMPTY must surface the open-world caveat");
        assertFalse(CqRunner.judgeExec(Expectation.empty(), select(1, false), 1000).pass);
    }

    // ------------------------------------------------------------------ COUNT

    @Test
    void countComparesWithOperator() {
        assertTrue(CqRunner.judgeExec(Expectation.count(">=", 2), select(2, false), 1000).pass);
        assertTrue(CqRunner.judgeExec(Expectation.count("==", 3), select(3, false), 1000).pass);
        assertFalse(CqRunner.judgeExec(Expectation.count("<", 2), select(2, false), 1000).pass);
        assertTrue(CqRunner.judgeExec(Expectation.count(">", 1), select(2, false), 1000).pass);
    }

    @Test
    void truncatedCountAddsCaveat() {
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.count("==", 5), select(5, true), 5);
        assertFalse(j.pass, "a truncated equality count is indeterminate and must fail closed");
        assertTrue(j.error != null && j.error.contains("truncated"));
        assertTrue(j.caveats.stream().anyMatch(c -> c.contains("truncated")),
                "a truncated result must warn the COUNT may be underreported");
    }

    @Test
    void truncatedCountCannotFalsePassAnUpperBound() {
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.count("<=", 1000),
                select(1000, true), 1000);
        assertFalse(j.pass);
        assertTrue(j.error.contains("true count is unknown"));
    }

    @Test
    void truncatedGraphCountIsExactAndJudgedNormally() {
        // graphJson truncates only the returned Turtle sample; 'count' is the exact graph size.
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.count(">=", 2000),
                graph("CONSTRUCT", 2000, true), 1000);
        assertTrue(j.pass, "a truncated CONSTRUCT sample must not fail a COUNT the exact size satisfies");
        assertNull(j.error);
        assertTrue(j.caveats.stream().anyMatch(c -> c.contains("exact graph size")),
                "the truncated sample must still be surfaced as a caveat");
        assertTrue(CqRunner.judgeExec(Expectation.count("==", 2000),
                graph("DESCRIBE", 2000, true), 1000).pass);
        // a genuinely failing compare over the exact size fails normally, without an error.
        CqRunner.Judged failing = CqRunner.judgeExec(Expectation.count("<", 100),
                graph("CONSTRUCT", 2000, true), 1000);
        assertFalse(failing.pass);
        assertNull(failing.error);
    }

    @Test
    void truncatedSelectCountPassesWhenLowerBoundAlreadySatisfies() {
        // a capped SELECT count is a lower bound, so '>='/'>' already met by it provably hold.
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.count(">=", 3), select(5, true), 5);
        assertTrue(j.pass, "5+ rows provably satisfies >= 3");
        assertNull(j.error);
        assertTrue(j.caveats.stream().anyMatch(c -> c.contains("lower bound")),
                "the lower-bound reasoning must be surfaced as a caveat");
        assertTrue(CqRunner.judgeExec(Expectation.count(">", 4), select(5, true), 5).pass);
        // a '>=' the capped count does not yet reach stays indeterminate and fails closed.
        CqRunner.Judged unmet = CqRunner.judgeExec(Expectation.count(">=", 10), select(5, true), 5);
        assertFalse(unmet.pass);
        assertTrue(unmet.error.contains("true count is unknown"));
    }

    @Test
    void truncationMessagesDoNotClaimAUniversalMaximum() {
        // run_competency_questions passes 'limit' unclamped; only project QC / the QC suite cap it.
        CqRunner.Judged count = CqRunner.judgeExec(Expectation.count("==", 30000),
                select(3, true), 20000);
        assertTrue(count.error.contains("truncated at limit 20000"), count.error);
        assertFalse(count.error.contains("maximum 10000"), count.error);
        assertTrue(count.error.contains("project QC and the QC suite cap it at 10000"), count.error);
        CqRunner.Judged rows = CqRunner.judgeExec(Expectation.exactRows(Arrays.asList(row("s",
                cell("uri", "http://ex/0", null, null)))), select(3, true), 20000);
        assertTrue(rows.error.contains("truncated at limit 20000"), rows.error);
        assertFalse(rows.error.contains("up to 10000"), rows.error);
        assertTrue(rows.error.contains("project QC and the QC suite cap it at 10000"), rows.error);
    }

    // ------------------------------------------------------------------ EXACT_ROWS

    @Test
    void exactRowsMatchesOrderInsensitively() {
        Map<String, Object> declaredA = row("s", cell("uri", "http://ex/0", null, null));
        Map<String, Object> declaredB = row("s", cell("uri", "http://ex/1", null, null));
        Expectation expected = Expectation.exactRows(Arrays.asList(declaredB, declaredA));  // reversed
        assertTrue(CqRunner.judgeExec(expected, select(2, false), 1000).pass,
                "row-set equality is order-insensitive");
    }

    @Test
    void exactRowsFailsOnMismatch() {
        Map<String, Object> declared = row("s", cell("uri", "http://ex/999", null, null));
        Expectation expected = Expectation.exactRows(Arrays.asList(declared));
        CqRunner.Judged judged = CqRunner.judgeExec(expected, select(1, false), 1000);
        assertFalse(judged.pass);
        assertTrue(judged.summary.startsWith("0 of 1 declared rows matched"), judged.summary);
    }

    @Test
    void exactRowsUsesDocumentedSetSemanticsForDuplicateRows() {
        Map<String, Object> declared = row("s", cell("uri", "http://ex/0", null, null));
        Map<String, Object> duplicate = row("s", cell("uri", "http://ex/0", null, null));
        Expectation expected = Expectation.exactRows(Arrays.asList(declared));
        assertTrue(CqRunner.judgeExec(expected,
                selectBindings(Arrays.asList(declared, duplicate), false), 1000).pass);
    }

    @Test
    void exactRowsTreatsRdfLanguageTagsCaseInsensitively() {
        Map<String, Object> declared = row("label",
                cell("literal", "hello", "EN", null));
        Map<String, Object> actual = row("label",
                cell("literal", "hello", "en", null));
        assertTrue(CqRunner.judgeExec(Expectation.exactRows(List.of(declared)),
                selectBindings(List.of(actual), false), 1000).pass);
    }

    @Test
    void exactRowsRejectsBlankNodes() {
        Map<String, Object> execWithBnode = new LinkedHashMap<>();
        execWithBnode.put("query_type", "SELECT");
        execWithBnode.put("count", 1);
        execWithBnode.put("bindings", Arrays.asList(row("s", cell("bnode", "b0", null, null))));
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.exactRows(new ArrayList<>()),
                execWithBnode, 1000);
        assertFalse(j.pass);
        assertTrue(j.caveats.stream().anyMatch(c -> c.toLowerCase().contains("blank node")));
    }

    @Test
    void exactRowsRejectsNonSelect() {
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.exactRows(new ArrayList<>()), ask(true), 1000);
        assertFalse(j.pass);
        assertTrue(j.caveats.stream().anyMatch(c -> c.contains("SELECT")));
    }

    @Test
    void exactRowsFailsClosedWhenResultsAreTruncated() {
        CqRunner.Judged j = CqRunner.judgeExec(
                Expectation.exactRows(Arrays.asList(row("s",
                        cell("uri", "http://ex/0", null, null)))), select(1, true), 1);
        assertFalse(j.pass);
        assertTrue(j.error.contains("truncated"));
    }

    @Test
    void canonicalizeRowsIsOrderInsensitiveSet() {
        Map<String, Object> r1 = row("s", cell("uri", "http://ex/A", null, null));
        Map<String, Object> r2 = row("s", cell("uri", "http://ex/B", null, null));
        assertEquals(CqRunner.canonicalizeRows(Arrays.asList(r1, r2)),
                CqRunner.canonicalizeRows(Arrays.asList(r2, r1)));
    }

    @Test
    void canonicalizeRowsCannotCollideThroughLiteralDelimiters() {
        Map<String, Object> twoCells = new LinkedHashMap<>();
        twoCells.put("a", cell("literal", "x", null, null));
        twoCells.put("b", cell("literal", "y", null, null));
        Map<String, Object> oneCraftedCell = row("a", cell("literal",
                "x||\u0001b=literal|y", null, null));

        assertFalse(CqRunner.canonicalizeRows(List.of(twoCells)).equals(
                CqRunner.canonicalizeRows(List.of(oneCraftedCell))));
    }

    // ------------------------------------------------------------------ gate

    @Test
    void gateHonoursFailOn() {
        assertEquals("pass", CqRunner.gate(CqRunner.FAIL_ON_NONE, 3), "fail_on=none never gates");
        assertEquals("fail", CqRunner.gate(CqRunner.FAIL_ON_ANY, 1));
        assertEquals("pass", CqRunner.gate(CqRunner.FAIL_ON_ANY, 0));
    }

    @Test
    void normalizeFailOnValidates() {
        assertEquals("none", CqRunner.normalizeFailOn(null));
        assertEquals("any", CqRunner.normalizeFailOn("Any"));
        org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> CqRunner.normalizeFailOn("all"));
    }

    private static Map<String, Object> row(String var, Map<String, Object> cell) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(var, cell);
        return r;
    }
}
