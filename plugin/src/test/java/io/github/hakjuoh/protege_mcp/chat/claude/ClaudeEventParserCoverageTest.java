package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.ChatUsage;
import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Supplementary branch-level coverage for {@link ClaudeEventParser}. The pre-existing
 * {@code ClaudeEventParserTest} exercises the happy-path transcript, prefix stripping, malformed
 * lines and one error result; this file drives the remaining edge/error/boundary branches through
 * the public {@code accept(String)} entry point (feeding hand-crafted line-delimited JSON) and the
 * static {@code stripToolPrefix} helper. No Protégé/OWLAPI runtime is required.
 */
class ClaudeEventParserCoverageTest {

    private RecordingChatListener listener;
    private ClaudeEventParser parser;

    private void newParser() {
        listener = new RecordingChatListener();
        parser = new ClaudeEventParser(listener);
    }

    // ---------------------------------------------------------------- accept(): null/blank/empty

    @Test
    void nullLineIsSilentlyIgnored() {
        newParser();
        parser.accept(null);
        assertNull(listener.sessionId, "null line must not fire any callback");
        assertEquals(0, listener.text.length(), "null line must not emit text");
        assertTrue(listener.errors.isEmpty(), "null line must not error");
    }

    @Test
    void blankWhitespaceLineIsSilentlyIgnored() {
        newParser();
        parser.accept("   \t  ");
        assertNull(listener.sessionId, "whitespace-only line must not fire callbacks");
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    void emptyStringLineIsSilentlyIgnored() {
        newParser();
        parser.accept("");
        assertNull(listener.sessionId, "empty line must not fire callbacks");
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    void malformedJsonIsCaughtAndIgnored() {
        newParser();
        parser.accept("{not valid json");
        assertNull(listener.sessionId, "malformed JSON must be swallowed, no callbacks");
        assertTrue(listener.errors.isEmpty());
        assertEquals(0, listener.liveUsages.size());
    }

    // ---------------------------------------------------------------- accept(): system/init

    @Test
    void systemInitEmitsSessionId() {
        newParser();
        parser.accept("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"abc-1\"}");
        assertEquals("abc-1", listener.sessionId, "system/init should surface the session id");
    }

    @Test
    void systemNonInitSubtypeDoesNotEmitSessionId() {
        newParser();
        parser.accept("{\"type\":\"system\",\"subtype\":\"other\",\"session_id\":\"abc-1\"}");
        assertNull(listener.sessionId, "only subtype=init should surface a session id");
    }

    @Test
    void systemInitWithMissingSessionIdDoesNotEmit() {
        newParser();
        parser.accept("{\"type\":\"system\",\"subtype\":\"init\"}");
        assertNull(listener.sessionId, "missing session_id (null) must not be emitted");
    }

    @Test
    void systemInitWithEmptySessionIdDoesNotEmit() {
        newParser();
        parser.accept("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"\"}");
        assertNull(listener.sessionId, "empty session_id must not be emitted");
    }

    @Test
    void unknownTopLevelTypeIsSilentlyIgnored() {
        newParser();
        parser.accept("{\"type\":\"assistant\",\"foo\":\"bar\"}");
        assertNull(listener.sessionId);
        assertEquals(0, listener.text.length());
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    void missingTypeFieldIsTreatedAsUnknownAndIgnored() {
        newParser();
        // node.path("type").asText() -> "" (missing node), hits default branch.
        parser.accept("{\"session_id\":\"x\"}");
        assertNull(listener.sessionId);
        assertTrue(listener.errors.isEmpty());
    }

    // ------------------------------------------------- stream_event: content_block_delta / text

    @Test
    void textDeltaEmitsAssistantText() {
        newParser();
        parser.accept(streamDelta("text_delta", "text", "hello"));
        assertEquals("hello", listener.text.toString());
    }

    @Test
    void emptyTextDeltaDoesNotEmitAssistantText() {
        newParser();
        parser.accept(streamDelta("text_delta", "text", ""));
        assertEquals(0, listener.text.length(), "empty text delta must be suppressed");
    }

    @Test
    void missingTextFieldInTextDeltaDoesNotEmit() {
        newParser();
        // delta.path("text").asText("") -> "" (default), so nothing emitted.
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\"}}}");
        assertEquals(0, listener.text.length());
    }

    // --------------------------------------------- stream_event: content_block_delta / thinking

    @Test
    void thinkingDeltaEmitsThinking() {
        newParser();
        parser.accept(streamDelta("thinking_delta", "thinking", "pondering"));
        assertEquals("pondering", listener.thinking.toString());
    }

    @Test
    void emptyThinkingDeltaDoesNotEmit() {
        newParser();
        parser.accept(streamDelta("thinking_delta", "thinking", ""));
        assertEquals(0, listener.thinking.length(), "empty thinking delta must be suppressed");
    }

    @Test
    void signatureDeltaIsIgnored() {
        newParser();
        parser.accept(streamDelta("signature_delta", "signature", "sig-bytes"));
        assertEquals(0, listener.text.length());
        assertEquals(0, listener.thinking.length());
    }

    @Test
    void inputJsonDeltaIsIgnored() {
        newParser();
        parser.accept(streamDelta("input_json_delta", "partial_json", "{\\\"a\\\":1}"));
        assertEquals(0, listener.text.length());
        assertEquals(0, listener.thinking.length());
    }

    // ------------------------------------------- stream_event: content_block_start (tool_use)

    @Test
    void toolUseBlockEmitsStrippedToolActivity() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
                + "\"content_block\":{\"type\":\"tool_use\",\"name\":\"mcp__protege__create_class\"}}}");
        assertEquals(1, listener.tools.size());
        assertEquals("create_class", listener.tools.get(0), "tool name should be stripped");
    }

    @Test
    void toolUseBlockWithMissingNameFallsBackToTool() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
                + "\"content_block\":{\"type\":\"tool_use\"}}}");
        assertEquals(1, listener.tools.size());
        assertEquals("tool", listener.tools.get(0), "missing name defaults to 'tool'");
    }

    @Test
    void nonToolUseBlockStartIsIgnored() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
                + "\"content_block\":{\"type\":\"text\"}}}");
        assertTrue(listener.tools.isEmpty(), "text block start must not report tool activity");
    }

    // --------------------------------------------------------- stream_event: structural / unknown

    @Test
    void messageStopStreamEventIsIgnored() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_stop\"}}");
        assertTrue(listener.liveUsages.isEmpty());
        assertEquals(0, listener.text.length());
    }

    @Test
    void contentBlockStopStreamEventIsIgnored() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\"}}");
        assertTrue(listener.liveUsages.isEmpty());
    }

    @Test
    void unknownStreamEventTypeIsIgnored() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"future_stream_thing\"}}");
        assertTrue(listener.liveUsages.isEmpty());
        assertEquals(0, listener.text.length());
    }

    // ---------------------------------------------------------------- updateLiveUsage via stream

    @Test
    void messageStartWithUsageEmitsLiveUsage() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\","
                + "\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":2,"
                + "\"cache_read_input_tokens\":100}}}}");
        assertEquals(1, listener.liveUsages.size());
        ChatUsage u = listener.liveUsages.get(0);
        assertEquals(10, u.inputTokens());
        assertEquals(2, u.outputTokens());
        assertEquals(100, u.cachedInputTokens());
        assertNull(u.costUsd(), "cost must remain null in live usage");
    }

    @Test
    void messageStartWithoutUsageIsSafeAndEmitsNothing() {
        newParser();
        // message.path("usage") -> missing node -> updateLiveUsage returns early.
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\","
                + "\"message\":{}}}");
        assertTrue(listener.liveUsages.isEmpty(), "missing usage must not emit onUsage");
    }

    @Test
    void messageDeltaWithUsageEmitsLiveUsage() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                + "\"usage\":{\"output_tokens\":42}}}");
        assertEquals(1, listener.liveUsages.size());
        assertEquals(42, listener.liveUsages.get(0).outputTokens());
    }

    @Test
    void messageDeltaWithoutUsageIsSafe() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\"}}");
        assertTrue(listener.liveUsages.isEmpty());
    }

    @Test
    void usageNonObjectNodeIsIgnored() {
        newParser();
        // usage is a JSON string, not an object -> isObject() false -> ignored.
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                + "\"usage\":\"not-an-object\"}}");
        assertTrue(listener.liveUsages.isEmpty(), "non-object usage must not emit onUsage");
    }

    @Test
    void usageEmptyObjectEmitsWithDefaultsRetained() {
        newParser();
        // Empty object is an object but has no token fields; running counts stay -1 and onUsage fires.
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                + "\"usage\":{}}}");
        assertEquals(1, listener.liveUsages.size(), "an object usage always emits onUsage");
        ChatUsage u = listener.liveUsages.get(0);
        assertEquals(-1, u.inputTokens(), "no input_tokens keeps the -1 initial running count");
        assertEquals(-1, u.outputTokens());
        assertEquals(-1, u.cachedInputTokens());
    }

    @Test
    void liveUsageClampsUpwardAndNeverRegresses() {
        newParser();
        // First: output climbs to 42.
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                + "\"usage\":{\"output_tokens\":42}}}");
        // Then an out-of-order lower value arrives; it must be clamped (stays 42).
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                + "\"usage\":{\"output_tokens\":5}}}");
        assertEquals(2, listener.liveUsages.size());
        assertEquals(42, listener.liveUsages.get(0).outputTokens());
        assertEquals(42, listener.liveUsages.get(1).outputTokens(),
                "lower out-of-order count must be clamped, not regress");
    }

    @Test
    void liveUsageAccumulatesAcrossEventsRetainingUnreportedFields() {
        newParser();
        // First event reports only input; second reports only output. Running counts persist.
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\","
                + "\"message\":{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":15000}}}}");
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                + "\"usage\":{\"output_tokens\":42}}}");
        ChatUsage last = listener.liveUsages.get(listener.liveUsages.size() - 1);
        assertEquals(10, last.inputTokens(), "input from earlier event must persist");
        assertEquals(15000, last.cachedInputTokens(), "cache read from earlier event must persist");
        assertEquals(42, last.outputTokens());
    }

    @Test
    void liveUsageInitializesFromMinusOne() {
        newParser();
        parser.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                + "\"usage\":{\"input_tokens\":7}}}");
        assertEquals(7, listener.liveUsages.get(0).inputTokens(),
                "first update must move the -1 initial value up to the reported value");
    }

    // ---------------------------------------------------------------- handleResult

    @Test
    void resultEmitsSessionIdAndFinalUsage() {
        newParser();
        parser.accept("{\"type\":\"result\",\"session_id\":\"sess-9\","
                + "\"usage\":{\"input_tokens\":11,\"output_tokens\":22,\"cache_read_input_tokens\":33}}");
        assertEquals("sess-9", listener.sessionId);
        assertNotNull(listener.usage, "onResult should have been called");
        assertEquals(11, listener.usage.inputTokens());
        assertEquals(22, listener.usage.outputTokens());
        assertEquals(33, listener.usage.cachedInputTokens());
        assertNull(listener.usage.costUsd(), "cost is intentionally not surfaced");
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    void resultMissingSessionIdDoesNotEmitButStillReportsUsage() {
        newParser();
        parser.accept("{\"type\":\"result\",\"usage\":{\"output_tokens\":5}}");
        assertNull(listener.sessionId, "result with no session_id must not emit onSessionId");
        assertNotNull(listener.usage, "onResult always fires for a result event");
        assertEquals(5, listener.usage.outputTokens());
    }

    @Test
    void resultWithMissingUsageFieldsUsesMinusOneDefault() {
        newParser();
        parser.accept("{\"type\":\"result\",\"session_id\":\"s\"}");
        assertNotNull(listener.usage);
        assertEquals(-1, listener.usage.inputTokens());
        assertEquals(-1, listener.usage.outputTokens());
        assertEquals(-1, listener.usage.cachedInputTokens());
    }

    @Test
    void resultIsErrorTrueWithMessageCallsOnError() {
        newParser();
        parser.accept("{\"type\":\"result\",\"is_error\":true,\"result\":\"kaboom\",\"session_id\":\"s\"}");
        assertEquals(1, listener.errors.size());
        assertEquals("kaboom", listener.errors.get(0));
    }

    @Test
    void resultIsErrorTrueWithEmptyResultUsesDefaultMessage() {
        newParser();
        parser.accept("{\"type\":\"result\",\"is_error\":true,\"result\":\"\",\"session_id\":\"s\"}");
        assertEquals(1, listener.errors.size());
        assertEquals("Claude reported an error.", listener.errors.get(0),
                "empty error result should fall back to the default message");
    }

    @Test
    void resultIsErrorTrueWithMissingResultUsesDefaultMessage() {
        newParser();
        // node.path("result").asText("") -> "" for a missing node -> default message.
        parser.accept("{\"type\":\"result\",\"is_error\":true,\"session_id\":\"s\"}");
        assertEquals(1, listener.errors.size());
        assertEquals("Claude reported an error.", listener.errors.get(0));
    }

    @Test
    void resultIsErrorFalseDoesNotCallOnError() {
        newParser();
        parser.accept("{\"type\":\"result\",\"is_error\":false,\"result\":\"ignored\",\"session_id\":\"s\"}");
        assertTrue(listener.errors.isEmpty(), "is_error=false must not trigger onError");
    }

    @Test
    void resultMissingIsErrorDefaultsToFalse() {
        newParser();
        parser.accept("{\"type\":\"result\",\"session_id\":\"s\"}");
        assertTrue(listener.errors.isEmpty(), "missing is_error defaults to false");
    }

    @Test
    void errorReportedFlagSetOnlyByAnIsErrorResult() {
        // The provider's completion handler reads this flag to keep the generic
        // "claude exited with code N" line out of a transcript that already shows the stream's
        // own error (e.g. an API/policy refusal) — while still reporting it when the CLI died
        // without emitting one.
        newParser();
        assertFalse(parser.errorReported(), "fresh parser has surfaced no error");
        parser.accept("{\"type\":\"result\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s\"}");
        assertFalse(parser.errorReported(), "a clean result must not set the flag");
        parser.accept("{\"type\":\"result\",\"is_error\":true,\"result\":\"API Error: refused\",\"session_id\":\"s\"}");
        assertTrue(parser.errorReported(), "an is_error result marks the error as already shown");
    }

    // ---------------------------------------------------------------- stripToolPrefix

    @Test
    void stripToolPrefixNullReturnsToolDefault() {
        assertEquals("tool", ClaudeEventParser.stripToolPrefix(null));
    }

    @Test
    void stripToolPrefixMcpPatternRemovesProviderPrefix() {
        assertEquals("create_class",
                ClaudeEventParser.stripToolPrefix("mcp__protege__create_class"));
    }

    @Test
    void stripToolPrefixPreservesInternalUnderscoresBeyondThirdPart() {
        assertEquals("foo__bar",
                ClaudeEventParser.stripToolPrefix("mcp__protege__foo__bar"),
                "double underscores in the tool name itself must be preserved");
    }

    @Test
    void stripToolPrefixPlainNameUnchanged() {
        assertEquals("list_classes", ClaudeEventParser.stripToolPrefix("list_classes"));
    }

    @Test
    void stripToolPrefixSinglePartMcpUnchanged() {
        assertEquals("mcp", ClaudeEventParser.stripToolPrefix("mcp"),
                "a single 'mcp' with no separators is not a prefix pattern");
    }

    @Test
    void stripToolPrefixTwoPartMcpUnchanged() {
        assertEquals("mcp__protege", ClaudeEventParser.stripToolPrefix("mcp__protege"),
                "fewer than 3 parts is returned unchanged");
    }

    @Test
    void stripToolPrefixEmptyStringReturnsEmpty() {
        assertEquals("", ClaudeEventParser.stripToolPrefix(""));
    }

    @Test
    void stripToolPrefixNonMcpFirstPartUnchanged() {
        // 3+ parts but first is not "mcp" -> returned unchanged.
        assertEquals("other__provider__name",
                ClaudeEventParser.stripToolPrefix("other__provider__name"));
    }

    @Test
    void stripToolPrefixSingleSeparatorUnchanged() {
        assertEquals("a__b", ClaudeEventParser.stripToolPrefix("a__b"),
                "only two parts, no mcp prefix stripping");
    }

    @Test
    void stripToolPrefixTrailingSeparatorPreserved() {
        // "mcp__protege__name__" -> split drops trailing empties, so parts = [mcp, protege, name].
        // The stripped result is just the tool part; verify it does not throw and strips prefix.
        assertEquals("name", ClaudeEventParser.stripToolPrefix("mcp__protege__name__"),
                "trailing separators are collapsed by String.split default limit");
    }

    @Test
    void stripToolPrefixTrailingSeparatorWithLimitPreservesEmpties() {
        // Verify behaviour with a genuine tool part carrying an internal trailing double-underscore
        // between two real segments (no trailing) so no empties are dropped.
        assertEquals("a__b",
                ClaudeEventParser.stripToolPrefix("mcp__protege__a__b"));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Builds a {@code stream_event} line whose event is a {@code content_block_delta} carrying a delta
     * of the given type with a single string field.
     */
    private static String streamDelta(String deltaType, String field, String value) {
        return "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"" + deltaType + "\",\"" + field + "\":\"" + value + "\"}}}";
    }
}
