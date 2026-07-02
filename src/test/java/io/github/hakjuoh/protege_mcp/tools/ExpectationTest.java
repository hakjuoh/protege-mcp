package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Method-level tests for {@link Expectation} parsing, serialisation and header round-tripping. */
class ExpectationTest {

    @Test
    void nullAndBlankDefaultToNonEmpty() {
        assertEquals(Expectation.Kind.NON_EMPTY, Expectation.fromObject(null).kind);
        assertEquals(Expectation.Kind.NON_EMPTY, Expectation.fromString("   ").kind);
    }

    @Test
    void stringGrammarParsesEachKind() {
        assertEquals(Expectation.Kind.NON_EMPTY, Expectation.fromString("nonEmpty").kind);
        assertEquals(Expectation.Kind.NON_EMPTY, Expectation.fromString("non_empty").kind);
        assertEquals(Expectation.Kind.EMPTY, Expectation.fromString("empty").kind);
        Expectation c = Expectation.fromString("count >= 3");
        assertEquals(Expectation.Kind.COUNT, c.kind);
        assertEquals(">=", c.op);
        assertEquals(3, c.value);
    }

    @Test
    void countEqualsSignIsNormalized() {
        assertEquals("==", Expectation.fromString("count = 5").op);
    }

    @Test
    void malformedCountAndUnknownSpecThrow() {
        assertThrows(ToolArgException.class, () -> Expectation.fromString("count >= xyz"));
        assertThrows(ToolArgException.class, () -> Expectation.fromString("count ~ 2"));
        assertThrows(ToolArgException.class, () -> Expectation.fromString("bogus"));
    }

    @Test
    void fromMapBuildsStructuredForms() {
        Map<String, Object> count = new LinkedHashMap<>();
        count.put("kind", "count");
        count.put("op", "<=");
        count.put("value", 7);
        Expectation e = Expectation.fromObject(count);
        assertEquals(Expectation.Kind.COUNT, e.kind);
        assertEquals("<=", e.op);
        assertEquals(7, e.value);
    }

    @Test
    void fromMapExactRowsCanonicalizesCells() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("kind", "exactRows");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("s", "http://ex/A");                 // scalar IRI → uri cell
        row.put("n", "hello");                       // scalar literal
        spec.put("rows", Arrays.asList(row));
        Expectation e = Expectation.fromObject(spec);
        assertEquals(Expectation.Kind.EXACT_ROWS, e.kind);
        assertEquals(1, e.rows.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) e.rows.get(0).get("s");
        assertEquals("uri", s.get("type"));
        assertEquals("http://ex/A", s.get("value"));
        @SuppressWarnings("unchecked")
        Map<String, Object> n = (Map<String, Object>) e.rows.get(0).get("n");
        assertEquals("literal", n.get("type"));
    }

    @Test
    void headerRoundTripPreservesKind() {
        assertEquals("nonEmpty", Expectation.nonEmpty().toHeaderString());
        assertEquals("empty", Expectation.empty().toHeaderString());
        assertEquals("count >= 3", Expectation.count(">=", 3).toHeaderString());

        assertEquals(Expectation.Kind.EMPTY, Expectation.fromHeaderString("empty").kind);
        Expectation c = Expectation.fromHeaderString("count <= 9");
        assertEquals(Expectation.Kind.COUNT, c.kind);
        assertEquals("<=", c.op);
        assertEquals(9, c.value);
    }

    @Test
    void exactRowsHeaderRoundTrips() {
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("type", "uri");
        cell.put("value", "http://ex/A");
        row.put("s", cell);
        Expectation original = Expectation.exactRows(Arrays.asList(row));
        String header = original.toHeaderString();               // "exactRows [...]"
        Expectation parsed = Expectation.fromHeaderString(header);
        assertEquals(Expectation.Kind.EXACT_ROWS, parsed.kind);
        assertEquals(1, parsed.rows.size());
    }

    @Test
    void toJsonMatchesStructuredForm() {
        Map<String, Object> json = Expectation.count(">", 2).toJson();
        assertEquals("count", json.get("kind"));
        assertEquals(">", json.get("op"));
        assertEquals(2, json.get("value"));
    }

    @Test
    void describeIsHumanReadable() {
        assertEquals("non-empty (≥1 row)", Expectation.nonEmpty().describe());
        List<String> descriptions = Arrays.asList(
                Expectation.empty().describe(), Expectation.count(">=", 1).describe());
        assertEquals(2, descriptions.size());
    }
}
