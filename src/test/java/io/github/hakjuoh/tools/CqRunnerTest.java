package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        CqRunner.Judged j = CqRunner.judgeExec(Expectation.count(">=", 1), select(5, true), 5);
        assertTrue(j.caveats.stream().anyMatch(c -> c.contains("truncated")),
                "a truncated result must warn the COUNT may be underreported");
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
        assertFalse(CqRunner.judgeExec(expected, select(1, false), 1000).pass);
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
    void canonicalizeRowsIsOrderInsensitiveSet() {
        Map<String, Object> r1 = row("s", cell("uri", "http://ex/A", null, null));
        Map<String, Object> r2 = row("s", cell("uri", "http://ex/B", null, null));
        assertEquals(CqRunner.canonicalizeRows(Arrays.asList(r1, r2)),
                CqRunner.canonicalizeRows(Arrays.asList(r2, r1)));
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
