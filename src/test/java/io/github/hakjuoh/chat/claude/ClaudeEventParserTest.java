package io.github.hakjuoh.chat.claude;

import io.github.hakjuoh.chat.RecordingChatListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Parses a real-shaped Claude {@code stream-json} transcript and asserts the extracted signals. */
class ClaudeEventParserTest {

    @Test
    void parsesTextThinkingToolsUsageAndSession() throws IOException {
        RecordingChatListener listener = new RecordingChatListener();
        feed(new ClaudeEventParser(listener), "/chat/claude-stream.jsonl");

        assertEquals("sess-claude-1", listener.sessionId);
        assertEquals("There are 3 classes.", listener.text.toString());
        assertEquals("Let me check.", listener.thinking.toString());
        assertTrue(listener.tools.contains("list_classes"), "tool name should be stripped of the mcp prefix");

        assertNotNull(listener.usage);
        assertEquals(10, listener.usage.inputTokens());
        assertEquals(42, listener.usage.outputTokens());
        assertEquals(15000, listener.usage.cachedInputTokens());
        assertNull(listener.usage.costUsd(), "cost is intentionally not surfaced");
        assertTrue(listener.errors.isEmpty());

        // A live token count is emitted during streaming (from message_delta) before the final result.
        assertFalse(listener.liveUsages.isEmpty(), "expected an in-flight onUsage emission");
        assertEquals(42, listener.liveUsages.get(listener.liveUsages.size() - 1).outputTokens());
    }

    @Test
    void stripsMcpToolPrefix() {
        assertEquals("create_class", ClaudeEventParser.stripToolPrefix("mcp__protege__create_class"));
        assertEquals("plain", ClaudeEventParser.stripToolPrefix("plain"));
    }

    @Test
    void ignoresUnknownAndMalformedLines() {
        RecordingChatListener listener = new RecordingChatListener();
        ClaudeEventParser parser = new ClaudeEventParser(listener);
        parser.accept("not json at all");
        parser.accept("{\"type\":\"some_future_event\"}");
        parser.accept("");
        assertEquals(0, listener.text.length());
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    void surfacesErrorResult() {
        RecordingChatListener listener = new RecordingChatListener();
        new ClaudeEventParser(listener).accept(
                "{\"type\":\"result\",\"is_error\":true,\"result\":\"boom\",\"session_id\":\"s\"}");
        assertTrue(listener.errors.contains("boom"));
    }

    private static void feed(ClaudeEventParser parser, String resource) throws IOException {
        try (InputStream in = ClaudeEventParserTest.class.getResourceAsStream(resource);
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                parser.accept(line);
            }
        }
    }
}
