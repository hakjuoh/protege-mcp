package io.github.hakjuoh.protege_mcp.chat.antigravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

class AntigravityEventParserTest {

    @Test
    void parsesDocumentedStreamingSequence() {
        RecordingChatListener listener = new RecordingChatListener();
        AntigravityEventParser parser = new AntigravityEventParser(listener);
        parser.accept("{\"event\":\"init\",\"conversation_id\":\"conv-1\",\"init\":{}}");
        parser.accept("{\"event\":\"step_update\",\"step_update\":{"
                + "\"state\":\"RUNNING\",\"step_type\":\"agent_response\","
                + "\"text_delta\":\"Hello \"}}");
        parser.accept("{\"event\":\"result\",\"result\":{"
                + "\"conversation_id\":\"conv-1\",\"status\":\"SUCCESS\","
                + "\"response\":\"Hello world\",\"usage\":{"
                + "\"input_tokens\":4,\"output_tokens\":2,\"cache_read_tokens\":1}}}");

        assertEquals("conv-1", listener.sessionId);
        assertEquals("Hello world", listener.text.toString());
        assertEquals(4, listener.usage.inputTokens());
        assertEquals(2, listener.usage.outputTokens());
        assertEquals(1, listener.usage.cachedInputTokens());
        assertTrue(parser.answered());
        assertFalse(parser.errorReported());
        assertEquals(java.util.List.of(0), listener.assistantMessageStarts,
                "result reconciliation extends the streamed message instead of starting another");
    }

    @Test
    void stepIndexesSeparateResponsesButNotTheirDeltas() {
        RecordingChatListener listener = new RecordingChatListener();
        AntigravityEventParser parser = new AntigravityEventParser(listener);
        parser.accept("{\"event\":\"step_update\",\"step_update\":{"
                + "\"step_index\":3,\"state\":\"ACTIVE\",\"step_type\":\"agent_response\","
                + "\"text_delta\":\"one\"}}"
                + "{\"event\":\"step_update\",\"step_update\":{"
                + "\"step_index\":3,\"state\":\"DONE\",\"step_type\":\"agent_response\","
                + "\"text_delta\":\".\"}}");
        parser.accept("{\"event\":\"step_update\",\"step_update\":{"
                + "\"step_index\":7,\"state\":\"DONE\",\"step_type\":\"agent_response\","
                + "\"text_delta\":\"Two.\"}}");

        assertEquals("one.Two.", listener.text.toString());
        assertEquals(java.util.List.of(0, 4), listener.assistantMessageStarts);
    }

    @Test
    void missingStepIndexFallsBackToDoneStateBoundaries() {
        RecordingChatListener listener = new RecordingChatListener();
        AntigravityEventParser parser = new AntigravityEventParser(listener);
        parser.accept("{\"event\":\"step_update\",\"step_update\":{"
                + "\"state\":\"ACTIVE\",\"step_type\":\"agent_response\",\"text_delta\":\"a\"}}"
                + "{\"event\":\"step_update\",\"step_update\":{"
                + "\"state\":\"DONE\",\"step_type\":\"agent_response\",\"text_delta\":\"b\"}}"
                + "{\"event\":\"step_update\",\"step_update\":{"
                + "\"state\":\"DONE\",\"step_type\":\"agent_response\",\"text_delta\":\"c\"}}");

        assertEquals("abc", listener.text.toString());
        assertEquals(java.util.List.of(0, 2), listener.assistantMessageStarts);
    }

    @Test
    void emitsThinkingToolAndFailure() {
        RecordingChatListener listener = new RecordingChatListener();
        AntigravityEventParser parser = new AntigravityEventParser(listener);
        parser.accept("{\"event\":\"step_update\",\"step_update\":{"
                + "\"step_type\":\"thinking\",\"text_delta\":\"checking\"}}"
                + "{\"event\":\"step_update\",\"step_update\":{"
                + "\"step_type\":\"tool_call\",\"state\":\"DONE\","
                + "\"tool_name\":\"protege_search\"}}");
        parser.accept("{\"event\":\"result\",\"result\":{\"status\":\"FAILED\","
                + "\"error\":\"permission denied\",\"usage\":{}}}");

        assertEquals("checking", listener.thinking.toString());
        assertEquals(java.util.List.of("protege_search"), listener.tools);
        assertEquals(java.util.List.of("permission denied"), listener.errors);
        assertTrue(parser.errorReported());
    }

    @Test
    void failedResultWithoutDetailStillExplainsFailure() {
        RecordingChatListener listener = new RecordingChatListener();
        AntigravityEventParser parser = new AntigravityEventParser(listener);
        parser.accept("{\"event\":\"result\",\"result\":{\"status\":\"CANCELLED\"}}");
        assertTrue(listener.errors.get(0).contains("CANCELLED"));
    }
}
