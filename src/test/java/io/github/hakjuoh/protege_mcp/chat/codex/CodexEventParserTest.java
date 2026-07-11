package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Parses a real-shaped Codex {@code --json} transcript and asserts the extracted signals. */
class CodexEventParserTest {

    @Test
    void parsesTextThinkingToolsUsageAndThread() throws IOException {
        RecordingChatListener listener = new RecordingChatListener();
        feed(new CodexEventParser(listener), "/chat/codex-events.jsonl");

        assertEquals("thread-codex-1", listener.sessionId);
        assertEquals("There are 3 classes.", listener.text.toString());
        assertEquals("Looking at the ontology.\n", listener.thinking.toString());
        assertTrue(listener.tools.contains("list_classes"));
        assertTrue(listener.errors.contains("Skill descriptions were shortened."));

        assertNotNull(listener.usage);
        assertEquals(18554, listener.usage.inputTokens());
        assertEquals(25, listener.usage.outputTokens());
        assertEquals(3456, listener.usage.cachedInputTokens());
        assertNull(listener.usage.costUsd(), "Codex reports no dollar cost");
    }

    @Test
    void agentMessageStreamsOnlyTheNewSuffix() {
        RecordingChatListener listener = new RecordingChatListener();
        CodexEventParser parser = new CodexEventParser(listener);
        parser.accept("{\"type\":\"item.updated\",\"item\":{\"id\":\"m\",\"type\":\"agent_message\",\"text\":\"Hello\"}}");
        parser.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"m\",\"type\":\"agent_message\",\"text\":\"Hello, world\"}}");
        assertEquals("Hello, world", listener.text.toString());
    }

    @Test
    void agentMessageWithoutIdEmitsOnceOnCompletion() {
        RecordingChatListener listener = new RecordingChatListener();
        CodexEventParser parser = new CodexEventParser(listener);
        // No item id: the in-flight update is skipped and the whole message is emitted once on completion
        // (a shared empty-id dedup key would otherwise corrupt incremental streaming).
        parser.accept("{\"type\":\"item.updated\",\"item\":{\"type\":\"agent_message\",\"text\":\"partial\"}}");
        parser.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"final answer\"}}");
        assertEquals("final answer", listener.text.toString());
    }

    @Test
    void ignoresUnknownAndMalformedLines() {
        RecordingChatListener listener = new RecordingChatListener();
        CodexEventParser parser = new CodexEventParser(listener);
        parser.accept("}{ not json");
        parser.accept("{\"type\":\"turn.started\"}");
        parser.accept("{\"type\":\"item.completed\",\"item\":{\"type\":\"unknown_kind\"}}");
        assertEquals(0, listener.text.length());
    }

    private static void feed(CodexEventParser parser, String resource) throws IOException {
        try (InputStream in = CodexEventParserTest.class.getResourceAsStream(resource);
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                parser.accept(line);
            }
        }
    }
}
