package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Supplementary branch coverage for {@link CodexEventParser} that is not already exercised by
 * {@code CodexEventParserTest}. Feeds hand-built JSONL lines and asserts the resulting
 * {@link io.github.hakjuoh.protege_mcp.chat.ChatListener} callbacks. All inputs are pure strings, so these tests
 * are fully deterministic and headless.
 */
class CodexEventParserCoverageTest {

    private static CodexEventParser parser(RecordingChatListener l) {
        return new CodexEventParser(l);
    }

    // ---- accept(): guard clauses -------------------------------------------------------------

    @Test
    void nullLineIsIgnored() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept(null);
        assertEquals(0, l.text.length(), "null line must produce no callbacks");
        assertNull(l.sessionId);
        assertTrue(l.errors.isEmpty());
    }

    @Test
    void emptyLineIsIgnored() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("");
        assertEquals(0, l.text.length(), "empty line must produce no callbacks");
    }

    @Test
    void whitespaceOnlyLineIsIgnored() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("   \t  ");
        assertEquals(0, l.text.length(), "blank line must produce no callbacks");
        assertTrue(l.errors.isEmpty());
    }

    @Test
    void malformedJsonIsSwallowed() {
        RecordingChatListener l = new RecordingChatListener();
        // Not exception-throwing: readTree failure is caught and the line dropped.
        parser(l).accept("{not:valid json");
        assertEquals(0, l.text.length());
        assertTrue(l.errors.isEmpty(), "malformed JSON must not surface an error callback");
    }

    @Test
    void jsonMissingTypeFieldFallsToDefault() {
        RecordingChatListener l = new RecordingChatListener();
        // type -> "" (path().asText() default) -> no case matches, default no-op.
        parser(l).accept("{\"thread_id\":\"x\"}");
        assertNull(l.sessionId, "missing type must not trigger onSessionId");
        assertEquals(0, l.text.length());
    }

    // ---- thread.started ----------------------------------------------------------------------

    @Test
    void threadStartedWithIdCallsOnSessionId() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"thread.started\",\"thread_id\":\"abc-123\"}");
        assertEquals("abc-123", l.sessionId);
    }

    @Test
    void threadStartedWithMissingIdSkipsOnSessionId() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"thread.started\"}");
        assertNull(l.sessionId, "absent thread_id -> asText(null) -> null -> skipped");
    }

    @Test
    void threadStartedWithEmptyIdSkipsOnSessionId() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"thread.started\",\"thread_id\":\"\"}");
        assertNull(l.sessionId, "empty thread_id must be skipped");
    }

    // ---- turn.completed / usage --------------------------------------------------------------

    @Test
    void turnCompletedWithAllUsageFields() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"turn.completed\",\"usage\":{"
                + "\"input_tokens\":100,\"output_tokens\":40,\"cached_input_tokens\":7}}");
        assertEquals(100, l.usage.inputTokens());
        assertEquals(40, l.usage.outputTokens());
        assertEquals(7, l.usage.cachedInputTokens());
        assertNull(l.usage.costUsd(), "Codex never reports a dollar cost");
    }

    @Test
    void turnCompletedWithMissingUsageDefaultsToMinusOne() {
        RecordingChatListener l = new RecordingChatListener();
        // No "usage" object at all: every field falls back to -1.
        parser(l).accept("{\"type\":\"turn.completed\"}");
        assertEquals(-1, l.usage.inputTokens());
        assertEquals(-1, l.usage.outputTokens());
        assertEquals(-1, l.usage.cachedInputTokens());
        assertNull(l.usage.costUsd());
    }

    @Test
    void turnCompletedWithPartialUsageDefaultsMissingFields() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"turn.completed\",\"usage\":{\"output_tokens\":5}}");
        assertEquals(-1, l.usage.inputTokens());
        assertEquals(5, l.usage.outputTokens());
        assertEquals(-1, l.usage.cachedInputTokens());
    }

    // ---- turn.failed / top-level error -------------------------------------------------------

    @Test
    void turnFailedWithErrorMessageObject() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"boom\"}}");
        assertEquals(1, l.errors.size());
        assertEquals("boom", l.errors.get(0));
    }

    @Test
    void topLevelErrorUsesTopLevelMessagePath() {
        RecordingChatListener l = new RecordingChatListener();
        // No error.message; falls through to node.message.
        parser(l).accept("{\"type\":\"error\",\"message\":\"top-level failure\"}");
        assertEquals(1, l.errors.size());
        assertEquals("top-level failure", l.errors.get(0));
    }

    @Test
    void topLevelErrorUsesErrorAsTextWhenErrorIsAString() {
        RecordingChatListener l = new RecordingChatListener();
        // error is a plain string, message absent: firstNonEmpty picks node.error.asText().
        parser(l).accept("{\"type\":\"error\",\"error\":\"string error\"}");
        assertEquals(1, l.errors.size());
        assertEquals("string error", l.errors.get(0));
    }

    @Test
    void errorMessageObjectTakesPrecedenceOverTopLevelMessage() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"turn.failed\","
                + "\"error\":{\"message\":\"from-error\"},\"message\":\"from-top\"}");
        assertEquals("from-error", l.errors.get(0),
                "error.message is checked first in firstNonEmpty");
    }

    @Test
    void errorEventWithNoMessageSkipsOnError() {
        RecordingChatListener l = new RecordingChatListener();
        // error is an object with no message; message absent -> all empty -> no callback.
        parser(l).accept("{\"type\":\"error\",\"error\":{}}");
        assertTrue(l.errors.isEmpty(), "empty error message must not fire onError");
    }

    // ---- unknown top-level type --------------------------------------------------------------

    @Test
    void unknownTopLevelTypeIsIgnored() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"turn.started\"}");
        parser(l).accept("{\"type\":\"some.future.event\"}");
        assertEquals(0, l.text.length());
        assertTrue(l.errors.isEmpty());
        assertNull(l.usage);
    }

    // ---- item: agent_message -----------------------------------------------------------------

    @Test
    void agentMessageWithIdOnStartedEmitsDelta() {
        RecordingChatListener l = new RecordingChatListener();
        // item.started -> completed=false, but id present -> emitDelta still runs.
        parser(l).accept("{\"type\":\"item.started\","
                + "\"item\":{\"id\":\"m1\",\"type\":\"agent_message\",\"text\":\"Hi\"}}");
        assertEquals("Hi", l.text.toString(), "id-bearing agent_message streams even when not completed");
    }

    @Test
    void agentMessageWithoutIdOnUpdatedIsSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.updated\","
                + "\"item\":{\"type\":\"agent_message\",\"text\":\"partial\"}}");
        assertEquals(0, l.text.length(), "no-id in-flight update must be skipped");
    }

    @Test
    void agentMessageWithoutIdCompletedEmptyTextSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"agent_message\",\"text\":\"\"}}");
        assertEquals(0, l.text.length(), "empty completion text must not emit");
    }

    @Test
    void agentMessageWithoutIdMissingTextSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        // text absent -> asText("") -> "" -> skipped.
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"agent_message\"}}");
        assertEquals(0, l.text.length());
    }

    @Test
    void agentMessageWithIdMissingTextIsSkippedByEmitDelta() {
        RecordingChatListener l = new RecordingChatListener();
        // id present but text absent -> emitDelta("") -> returns without callback.
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"id\":\"m\",\"type\":\"agent_message\"}}");
        assertEquals(0, l.text.length());
    }

    // ---- emitDelta state semantics -----------------------------------------------------------

    @Test
    void emitDeltaSkipsWhenTextShrinksThenEmitsWhenItGrowsPastStored() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"item.updated\",\"item\":{\"id\":\"m\",\"type\":\"agent_message\",\"text\":\"Hello\"}}");
        // Shorter than stored 5: no emit.
        p.accept("{\"type\":\"item.updated\",\"item\":{\"id\":\"m\",\"type\":\"agent_message\",\"text\":\"Hi\"}}");
        // Equal length to stored 5: still no emit (strictly-greater guard).
        p.accept("{\"type\":\"item.updated\",\"item\":{\"id\":\"m\",\"type\":\"agent_message\",\"text\":\"Howdy\"}}");
        // Longer than stored 5: emits the suffix beyond index 5.
        p.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"m\",\"type\":\"agent_message\",\"text\":\"Hello more\"}}");
        assertEquals("Hello more", l.text.toString(),
                "only the initial full text and the later grown suffix should be appended");
    }

    @Test
    void emitDeltaTracksIdsIndependently() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"item.updated\",\"item\":{\"id\":\"a\",\"type\":\"agent_message\",\"text\":\"AAA\"}}");
        p.accept("{\"type\":\"item.updated\",\"item\":{\"id\":\"b\",\"type\":\"agent_message\",\"text\":\"BBB\"}}");
        // Each id starts from its own zero counter; no cross-contamination.
        assertEquals("AAABBB", l.text.toString());
    }

    // ---- item: reasoning ---------------------------------------------------------------------

    @Test
    void reasoningNotCompletedIsSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.updated\","
                + "\"item\":{\"type\":\"reasoning\",\"text\":\"thinking\"}}");
        assertEquals(0, l.thinking.length(), "reasoning emits only on completion");
    }

    @Test
    void reasoningCompletedWithTextCallsOnThinking() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"reasoning\",\"text\":\"deep thought\"}}");
        assertEquals("deep thought", l.thinking.toString());
    }

    @Test
    void reasoningCompletedUsesSummaryWhenTextAbsent() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"reasoning\",\"summary\":\"summary text\"}}");
        assertEquals("summary text", l.thinking.toString());
    }

    @Test
    void reasoningCompletedPrefersTextOverSummary() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"reasoning\",\"text\":\"T\",\"summary\":\"S\"}}");
        assertEquals("T", l.thinking.toString(), "text is first in firstNonEmpty");
    }

    @Test
    void reasoningCompletedWithNeitherTextNorSummarySkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\"}}");
        assertEquals(0, l.thinking.length());
    }

    // ---- item: mcp_tool_call -----------------------------------------------------------------

    @Test
    void mcpToolCallNotCompletedIsSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.updated\","
                + "\"item\":{\"type\":\"mcp_tool_call\",\"tool\":\"x\"}}");
        assertTrue(l.tools.isEmpty(), "tool activity emits only on completion");
    }

    @Test
    void mcpToolCallUsesToolField() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"mcp_tool_call\",\"tool\":\"create_class\"}}");
        assertEquals("create_class", l.tools.get(0));
    }

    @Test
    void mcpToolCallFallsBackToToolNameField() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"mcp_tool_call\",\"tool_name\":\"list_classes\"}}");
        assertEquals("list_classes", l.tools.get(0));
    }

    @Test
    void mcpToolCallFallsBackToNameField() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"mcp_tool_call\",\"name\":\"delete_class\"}}");
        assertEquals("delete_class", l.tools.get(0));
    }

    @Test
    void mcpToolCallWithNoNameUsesDefaultLabel() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"mcp_tool_call\"}}");
        assertEquals("tool call", l.tools.get(0), "empty tool name -> default label");
    }

    // ---- item: command_execution -------------------------------------------------------------

    @Test
    void commandExecutionNotCompletedIsSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.started\","
                + "\"item\":{\"type\":\"command_execution\",\"command\":\"ls\"}}");
        assertTrue(l.tools.isEmpty());
    }

    @Test
    void commandExecutionWithCommandPrefixesDollar() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"command_execution\",\"command\":\"ls -la\"}}");
        assertEquals("$ ls -la", l.tools.get(0));
    }

    @Test
    void commandExecutionWithEmptyCommandUsesDefaultLabel() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"command_execution\",\"command\":\"\"}}");
        assertEquals("command", l.tools.get(0));
    }

    // ---- item: file_change / web_search ------------------------------------------------------

    @Test
    void fileChangeNotCompletedIsSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.updated\",\"item\":{\"type\":\"file_change\"}}");
        assertTrue(l.tools.isEmpty());
    }

    @Test
    void fileChangeCompletedEmitsFixedLabel() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"file_change\"}}");
        assertEquals("file change", l.tools.get(0));
    }

    @Test
    void webSearchNotCompletedIsSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.updated\",\"item\":{\"type\":\"web_search\"}}");
        assertTrue(l.tools.isEmpty());
    }

    @Test
    void webSearchCompletedEmitsFixedLabel() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"web_search\"}}");
        assertEquals("web search", l.tools.get(0));
    }

    // ---- item: error -------------------------------------------------------------------------

    @Test
    void errorItemWithMessageCallsOnError() {
        RecordingChatListener l = new RecordingChatListener();
        // Error item fires regardless of completed flag (no completed guard on this branch).
        parser(l).accept("{\"type\":\"item.updated\","
                + "\"item\":{\"type\":\"error\",\"message\":\"item failed\"}}");
        assertEquals(1, l.errors.size());
        assertEquals("item failed", l.errors.get(0));
    }

    @Test
    void errorItemWithEmptyMessageSkipsOnError() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"error\",\"message\":\"\"}}");
        assertTrue(l.errors.isEmpty());
    }

    @Test
    void errorItemWithMissingMessageSkipsOnError() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\"}}");
        assertTrue(l.errors.isEmpty());
    }

    // ---- item: unknown / missing type --------------------------------------------------------

    @Test
    void unknownItemTypeIsIgnored() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"todo_list\",\"text\":\"ignored\"}}");
        assertEquals(0, l.text.length());
        assertTrue(l.tools.isEmpty());
        assertTrue(l.errors.isEmpty());
    }

    @Test
    void itemWithMissingTypeFallsToDefault() {
        RecordingChatListener l = new RecordingChatListener();
        // item.type absent -> "" -> no case matches, default no-op.
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"text\":\"x\"}}");
        assertEquals(0, l.text.length());
        assertTrue(l.tools.isEmpty());
    }

    @Test
    void missingItemNodeIsHandledGracefully() {
        RecordingChatListener l = new RecordingChatListener();
        // No "item" node: path("item") is a MissingNode; item.type -> "" -> default no-op.
        parser(l).accept("{\"type\":\"item.completed\"}");
        assertEquals(0, l.text.length());
        assertTrue(l.errors.isEmpty());
    }

    // ---- constructor + listener wiring -------------------------------------------------------

    @Test
    void constructorStoresListenerAndRoutesCallbacks() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = new CodexEventParser(l);
        p.accept("{\"type\":\"thread.started\",\"thread_id\":\"sess\"}");
        assertEquals("sess", l.sessionId, "the constructor-provided listener must receive callbacks");
        assertFalse(l.errors.contains("sess"));
    }
}
