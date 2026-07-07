package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Tests for the F7 fix in {@link QcSuiteTools#aggregate}: when zero stages actually ran (every requested
 * stage was skipped) the 'pass' gate attests to nothing, so a {@code note} makes the vacuous pass explicit.
 */
class QcSuiteAggregateNoteTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    @Test
    void zeroRanStagesAnnotatesTheVacuousPass() {
        List<QcSuiteTools.StageResult> onlySkipped =
                List.of(QcSuiteTools.StageResult.skipped("shacl", "no SHACL shapes supplied"));
        Map<String, Object> m = structured(QcSuiteTools.aggregate(onlySkipped, "error"));
        assertEquals(0, ((Number) m.get("stages_ran")).intValue());
        assertEquals("pass", m.get("gate"));
        assertNotNull(m.get("note"), "a vacuous pass must be annotated");
    }

    @Test
    void aStageThatRanCarriesNoVacuousNote() {
        List<QcSuiteTools.StageResult> ran =
                List.of(new QcSuiteTools.StageResult("reasoner", true, QcSuiteTools.PASS, null, null));
        Map<String, Object> m = structured(QcSuiteTools.aggregate(ran, "error"));
        assertEquals(1, ((Number) m.get("stages_ran")).intValue());
        assertNull(m.get("note"));
    }
}
