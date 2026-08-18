package io.github.hakjuoh.protege_mcp.chat.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

class OpenCodeEventParserTest {

    @Test
    void parsesTextReasoningAndStepUsage() {
        RecordingChatListener listener = new RecordingChatListener();
        OpenCodeEventParser parser = new OpenCodeEventParser(listener);
        parser.accept("{\"type\":\"reasoning\",\"sessionID\":\"ses-1\","
                + "\"part\":{\"id\":\"part-r\",\"text\":\"checking\"}}"
                + "{\"type\":\"text\",\"sessionID\":\"ses-1\","
                + "\"part\":{\"id\":\"part-t\",\"text\":\"answer\"}}");
        parser.accept("{\"type\":\"step_finish\",\"sessionID\":\"ses-1\","
                + "\"part\":{\"tokens\":{\"input\":11,\"output\":7,"
                + "\"reasoning\":3,\"cache\":{\"read\":5,\"write\":2}},\"cost\":0.04}}");

        assertEquals("ses-1", listener.sessionId);
        assertEquals("checking\n", listener.thinking.toString());
        assertEquals("answer", listener.text.toString());
        assertEquals(11, listener.usage.inputTokens());
        assertEquals(7, listener.usage.outputTokens());
        assertEquals(5, listener.usage.cachedInputTokens());
        assertEquals(0.04, listener.usage.costUsd());
        assertTrue(parser.answered());
        assertFalse(parser.errorReported());
    }

    @Test
    void accumulatesUsageAcrossToolLoopSteps() {
        RecordingChatListener listener = new RecordingChatListener();
        OpenCodeEventParser parser = new OpenCodeEventParser(listener);
        parser.accept("{\"type\":\"step_finish\",\"part\":{\"tokens\":{"
                + "\"input\":10,\"output\":2,\"cache\":{\"read\":3}},\"cost\":0.01}}");
        parser.accept("{\"type\":\"step_finish\",\"part\":{\"tokens\":{"
                + "\"input\":20,\"output\":4,\"cache\":{\"read\":5}},\"cost\":0.02}}");

        assertEquals(30, listener.usage.inputTokens());
        assertEquals(6, listener.usage.outputTokens());
        assertEquals(8, listener.usage.cachedInputTokens());
        assertEquals(0.03, listener.usage.costUsd(), 0.000001);
    }

    @Test
    void parsesCompletedAndFailedToolEvents() {
        RecordingChatListener listener = new RecordingChatListener();
        OpenCodeEventParser parser = new OpenCodeEventParser(listener);
        parser.accept("{\"type\":\"tool_use\",\"part\":{\"id\":\"tool-1\","
                + "\"tool\":\"protege_search\",\"state\":{\"status\":\"completed\"}}}");
        parser.accept("{\"type\":\"tool_use\",\"part\":{\"id\":\"tool-2\","
                + "\"tool\":\"protege_write\",\"state\":{\"status\":\"error\","
                + "\"error\":\"write rejected\"}}}");

        assertEquals(List.of("protege_search", "protege_write"), listener.tools);
        assertEquals(List.of("write rejected"), listener.errors);
        assertTrue(parser.errorReported());
    }

    @Test
    void duplicatePartEventIsNotRenderedTwice() {
        RecordingChatListener listener = new RecordingChatListener();
        OpenCodeEventParser parser = new OpenCodeEventParser(listener);
        String event = "{\"type\":\"text\",\"part\":{\"id\":\"part-1\",\"text\":\"once\"}}";
        parser.accept(event);
        parser.accept(event);
        assertEquals("once", listener.text.toString());
    }

    @Test
    void extractsStructuredErrorMessage() {
        RecordingChatListener listener = new RecordingChatListener();
        OpenCodeEventParser parser = new OpenCodeEventParser(listener);
        parser.accept("{\"type\":\"error\",\"error\":{\"name\":\"ProviderError\","
                + "\"data\":{\"message\":\"model unavailable\"}}}");
        assertEquals(List.of("model unavailable"), listener.errors);
    }
}
