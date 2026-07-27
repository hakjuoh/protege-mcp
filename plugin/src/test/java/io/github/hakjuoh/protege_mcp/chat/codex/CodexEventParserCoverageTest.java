package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

import java.util.List;

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

    @Test
    void errorReportedFlagTracksStreamSurfacedErrors() {
        // The provider's completion handler reads this flag to keep the generic
        // "codex exited with code N" line out of a transcript that already shows the stream's
        // own error — while still reporting it when the CLI died without emitting one.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        assertFalse(p.errorReported(), "fresh parser has surfaced no error");
        p.accept("{\"type\":\"error\",\"error\":{}}");
        assertFalse(p.errorReported(), "a skipped (empty-message) error must not set the flag");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"boom\"}}");
        assertTrue(p.errorReported(), "a turn.failed error marks the error as already shown");
    }

    @Test
    void errorReportedFlagAlsoSetByAnErrorItem() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"message\":\"item boom\"}}");
        assertEquals(1, l.errors.size());
        assertTrue(p.errorReported(), "an error item marks the error as already shown");
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
        assertEquals("deep thought\n", l.thinking.toString());
    }

    @Test
    void reasoningCompletedUsesSummaryWhenTextAbsent() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"reasoning\",\"summary\":\"summary text\"}}");
        assertEquals("summary text\n", l.thinking.toString());
    }

    @Test
    void reasoningCompletedPrefersTextOverSummary() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"reasoning\",\"text\":\"T\",\"summary\":\"S\"}}");
        assertEquals("T\n", l.thinking.toString(), "text is first in firstNonEmpty");
    }

    @Test
    void reasoningCompletedWithNeitherTextNorSummarySkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\"}}");
        assertEquals(0, l.thinking.length());
    }

    @Test
    void reasoningSummaryAsSummaryTextArrayIsJoined() {
        // The Responses API shapes summaries as an array of summary_text parts; asText("") on an
        // array is "" and used to drop the whole summary silently.
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"part one\"},"
                + "{\"type\":\"summary_text\",\"text\":\"part two\"}]}}");
        assertEquals("part one\n\npart two\n", l.thinking.toString());
    }

    @Test
    void reasoningSummaryAsStringArrayIsJoined() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\","
                + "\"summary\":[\"alpha\",\"beta\"]}}");
        assertEquals("alpha\n\nbeta\n", l.thinking.toString());
    }

    @Test
    void reasoningSummaryAsObjectReadsItsTextField() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\","
                + "\"summary\":{\"type\":\"summary_text\",\"text\":\"whole\"}}}");
        assertEquals("whole\n", l.thinking.toString());
    }

    @Test
    void reasoningSummaryArrayWithNoTextPartsIsSkipped() {
        RecordingChatListener l = new RecordingChatListener();
        parser(l).accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\","
                + "\"summary\":[{\"type\":\"summary_text\"},{},42]}}");
        assertEquals(0, l.thinking.length(), "no readable part -> no onThinking call");
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

    // ---- what the stream said, kept for the exit path ----------------------------------------

    @Test
    void streamErrorsAreKeptSoTheExitPathCanClassifyThem() {
        // finishTurn has to decide whether the failure was the reasoning effort being refused, and
        // the only evidence is the error the stream carried - stderr is empty on this path.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);

        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"message\":\"first\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"Invalid value: 'max'.\"}}");

        assertTrue(p.errorReported());
        assertEquals("first\nInvalid value: 'max'.", p.errorText(),
                "every error the stream reported, in order");
        assertEquals(2, l.errors.size(), "the user still sees each one as it arrives");
    }

    @Test
    void anErrorLoopCannotGrowTheKeptTextWithoutBound() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        for (int i = 0; i < 40; i++) {
            // Distinct each time, so this measures the length bound and not the repeat suppression.
            p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + i + "e".repeat(500)
                    + "\"}}");
        }
        assertTrue(p.errorText().length() <= 4_001,
                "the kept text must stop AT the cap, not at the first error to cross it: "
                        + p.errorText().length());
        assertEquals(CodexEventParser.MAX_DISTINCT_ERRORS + 1, l.errors.size(),
                "every failure up to the reporting bound, then the line that says the rest are not shown");
        assertEquals(CodexEventParser.FURTHER_ERRORS_NOTE, l.errors.get(l.errors.size() - 1));
    }

    @Test
    void aRunawayErrorLoopIsElidedOnceRatherThanFloodingTheTranscript() {
        // A CLI stuck in a retry loop can emit thousands of distinct failures. Every one of them
        // reaching the transcript pushes the reply out of reach and pins the UI redrawing them, so past
        // the bound they are dropped - but visibly, because a silent drop reads as "that was all".
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        for (int i = 0; i < 500; i++) {
            p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"failure " + i + "\"}}");
        }
        assertEquals(CodexEventParser.MAX_DISTINCT_ERRORS + 1, l.errors.size());
        assertEquals(1, l.errors.stream()
                        .filter(CodexEventParser.FURTHER_ERRORS_NOTE::equals).count(),
                "the elision is announced once, not once per dropped failure");
        assertEquals("failure 0", l.errors.get(0), "the earliest failures are the ones kept");
        assertTrue(p.errorReported());
    }

    @Test
    void theNewestFailureStaysClassifiablePastBothBounds() {
        // The refusal that names what went wrong is the last failure, not the first. Once the kept text
        // is full - or reporting has saturated - the exit path would otherwise classify a turn on
        // nothing but the noise that came before it.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + "e".repeat(10_000) + "\"}}");
        for (int i = 0; i < CodexEventParser.MAX_DISTINCT_ERRORS + 5; i++) {
            p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"noise " + i + "\"}}");
        }
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"Invalid value: 'max'.\"}}");

        assertFalse(p.errorText().contains("Invalid value: 'max'."),
                "the kept text is full, so the decisive message is not in it");
        assertTrue(p.classifiableErrorText().contains("Invalid value: 'max'."),
                "what the exit path classifies must still carry the newest failure");
        assertTrue(CodexCliProvider.effortRejection("max", p.classifiableErrorText()),
                "and that is what lets the refused effort be named");
    }

    @Test
    void aReprintedPreambleDoesNotHideTheTailThatDecidesTheTurn() {
        // A retry loop reprints one preamble, so the newest failure opens with text the kept budget
        // already spent - and what says the turn was refused is only in the tail after it. Sharing a
        // prefix with something kept must not count as being kept.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        String preamble = "stream disconnected while retrying: " + "x".repeat(200);
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + preamble + "\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"filler " + "y".repeat(3_800)
                + "\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + preamble
                + " Invalid value: 'max'.\"}}");

        assertTrue(p.errorText().contains(preamble), "the preamble itself was kept, early on");
        assertFalse(p.errorText().contains("Invalid value: 'max'."),
                "the budget was gone by the time the decisive failure arrived");
        assertTrue(CodexCliProvider.effortRejection("max", p.classifiableErrorText()),
                "so the refused effort can still be named from what the exit path classifies");
    }

    @Test
    void aPreambleAsLongAsTheRememberedBoundStillDoesNotHideTheDecisiveTail() {
        // The same retry loop, with a preamble at the bound on what is remembered of the newest failure.
        // Cutting that failure to its first MAX_LAST_ERROR_CHARS leaves exactly the preamble the earlier
        // attempt already spent the kept budget on, so neither the kept text nor the remembered copy may
        // be the whole story on its own.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        String preamble = "stream disconnected while retrying: " + "x".repeat(1_200);
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + preamble + "\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"filler " + "y".repeat(3_800)
                + "\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + preamble
                + " Invalid value: 'max'. Supported values are: 'low', 'medium', 'high'.\"}}");

        assertFalse(p.errorText().contains("Invalid value: 'max'."),
                "the budget was gone by the time the decisive failure arrived");
        assertTrue(CodexCliProvider.effortRejection("max", p.classifiableErrorText()),
                "so the refused effort can still be named from what the exit path classifies");
    }

    @Test
    void aShortFailureKeptWholeIsNotClassifiedTwice() {
        // The kept text holds it already; appending it again would be noise in what the exit path reads.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"Invalid value: 'max'.\"}}");

        assertEquals("Invalid value: 'max'.", p.classifiableErrorText());
    }

    @Test
    void aTurnWithNoErrorClassifiesNothing() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"turn.completed\",\"usage\":{}}");

        assertEquals("", p.classifiableErrorText(), "no error must not read as a blank line of error");
    }

    @Test
    void twoFailuresSharingAMessageAreBothShownWhenTheyAreDifferentItems() {
        // Codex's error messages are generic enough to collide ("stream error"), and two tool calls
        // failing the same way is two failures. Suppressing by text alone would hide the second one, so
        // suppression keys off the item's identity; only an event with no item id falls back to text.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        String message = "stream error: retrying";

        p.accept("{\"type\":\"item.started\",\"item\":{\"type\":\"error\",\"id\":\"err_1\","
                + "\"message\":\"" + message + "\"}}");
        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"id\":\"err_1\","
                + "\"message\":\"" + message + "\"}}");
        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"id\":\"err_2\","
                + "\"message\":\"" + message + "\"}}");

        assertEquals(List.of(message, message), l.errors,
                "one report per failing item, not per distinct string");

        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + message + "\"}}");
        assertEquals(2, l.errors.size(),
                "a turn-level event has only the text to identify it, so it is the echo it looks like");
    }

    @Test
    void oneHugeErrorIsTruncatedRatherThanKeptWhole() {
        // The budget was checked before appending, so any single message got in whole however big it
        // was - a 10,000-char diagnostic became 10,000 chars of kept text.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + "e".repeat(10_000) + "\"}}");
        assertTrue(p.errorText().length() <= 4_001, "kept text length: " + p.errorText().length());
        assertTrue(p.errorText().endsWith("…"), "a truncated diagnostic says so");
        assertEquals(1, l.errors.size());
        assertEquals(10_000, l.errors.get(0).length(), "the user is still shown all of it");
    }

    @Test
    void theSameFailureArrivingTwiceIsReportedOnce() {
        // An error item is re-delivered on item.completed, and turn.failed then repeats the same
        // message: three deliveries of one failure, which read as three failures in the transcript.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        String message = "Invalid value: 'max'.";

        p.accept("{\"type\":\"item.started\",\"item\":{\"type\":\"error\",\"message\":\""
                + message + "\"}}");
        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"message\":\""
                + message + "\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + message + "\"}}");

        assertEquals(List.of(message), l.errors, "one failure, said once");
        assertEquals(message, p.errorText(), "and kept once, so classification sees it plainly");
        assertTrue(p.errorReported(), "a repeat still counts as the stream having spoken");
    }

    @Test
    void twoDifferentFailuresAreBothStillReported() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"message\":\"first\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"second\"}}");
        assertEquals(List.of("first", "second"), l.errors,
                "suppressing repeats must not suppress news");
    }

    @Test
    void anEmptyErrorMessageIsNotTreatedAsAReportedError() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"error\"}}");
        assertFalse(p.errorReported(), "nothing was said, so the exit line is still needed");
        assertEquals("", p.errorText());
        assertTrue(l.errors.isEmpty());
    }

    // ---- answered(): what the completion handler calls the turn -------------------------------

    @Test
    void aStreamedReplyMakesTheTurnAnswered() {
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);
        assertFalse(p.answered(), "nothing has been said yet");

        p.accept("{\"type\":\"item.updated\",\"item\":{\"type\":\"agent_message\","
                + "\"id\":\"m1\",\"text\":\"hello\"}}");

        assertTrue(p.answered(), "text the user can read is an answer, whatever else the turn reported");
        assertEquals("hello", l.text.toString());
    }

    @Test
    void anIdLessReplyDeliveredOnCompletionAlsoCounts() {
        // The other emit path: a message with no item id is printed once, on completion.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);

        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\","
                + "\"text\":\"late reply\"}}");

        assertTrue(p.answered(), "both ways a reply reaches the listener must count as one");
        assertEquals("late reply", l.text.toString());
    }

    @Test
    void reasoningAndErrorsAloneAreNotAnAnswer() {
        // Neither hidden reasoning nor a diagnostic is a reply: a turn that produced only these was not
        // answered, so the completion handler must still describe it as the failure it was.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);

        p.accept("{\"type\":\"item.updated\",\"item\":{\"type\":\"reasoning\","
                + "\"id\":\"r1\",\"text\":\"thinking\"}}");
        p.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"Invalid value: 'max'.\"}}");

        assertFalse(p.answered(), "reasoning and an error are not a reply");
        assertTrue(p.errorReported());
    }

    @Test
    void aReplyOfNothingButWhitespaceIsNotAnAnswer() {
        // A blank agent_message puts nothing on the screen. A turn that also failed was refused, and
        // calling that whitespace an answer would describe the refusal as a success it never was.
        RecordingChatListener l = new RecordingChatListener();
        CodexEventParser p = parser(l);

        p.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\","
                + "\"text\":\"  \\n \"}}");

        assertFalse(p.answered(), "blank text is not something the user can read");
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
