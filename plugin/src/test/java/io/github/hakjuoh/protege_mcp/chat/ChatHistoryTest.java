package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatHistoryTest {

    @Test
    void providerRoundTripResumesNativeSessionAndHandsOffOnlyMissedTurns() {
        ChatHistory history = new ChatHistory();
        history.setSessionId("codex", "codex-thread");
        history.addUser("codex", "Question for Codex");
        history.addAssistant("codex", "Codex answer");
        history.markSynced("codex");

        assertEquals("codex-thread", history.sessionId("codex"));
        assertTrue(history.handoffFor("codex").isEmpty(), "Codex already saw its completed turn");

        String forClaude = history.handoffFor("claude");
        assertTrue(forClaude.contains("Question for Codex"));
        assertTrue(forClaude.contains("Codex answer"));
        history.setSessionId("claude", "claude-session");
        history.addUser("claude", "Follow-up for Claude");
        history.addAssistant("claude", "Claude answer");
        history.markSynced("claude");

        String backToCodex = history.handoffFor("codex");
        assertFalse(backToCodex.contains("Question for Codex"),
                "the original Codex turn is already in its native session");
        assertTrue(backToCodex.contains("Follow-up for Claude"));
        assertTrue(backToCodex.contains("Claude answer"));
        assertEquals("codex-thread", history.sessionId("codex"),
                "returning to Codex keeps the original native thread id");
    }

    @Test
    void providerSessionIdsAreIndependent() {
        ChatHistory history = new ChatHistory();
        history.setSessionId("codex", "c1");
        history.setSessionId("claude", "a1");
        assertEquals("c1", history.sessionId("codex"));
        assertEquals("a1", history.sessionId("claude"));
        assertNull(history.sessionId("other"));
    }

    @Test
    void successfulCatchUpAdvancesOnlyThatProvidersCursor() {
        ChatHistory history = new ChatHistory();
        history.addUser("codex", "one");
        history.addAssistant("codex", "two");
        history.markSynced("codex");

        assertFalse(history.handoffFor("claude").isEmpty());
        history.markSynced("claude");
        assertTrue(history.handoffFor("claude").isEmpty());
        assertTrue(history.handoffFor("codex").isEmpty());

        history.addUser("claude", "three");
        assertTrue(history.handoffFor("claude").contains("three"));
        assertTrue(history.handoffFor("codex").contains("three"));
    }

    @Test
    void handoffIsBoundedAndKeepsNewestContent() {
        ChatHistory history = new ChatHistory();
        history.addUser("codex", "old-marker-" + "x".repeat(ChatHistory.MAX_HANDOFF_CHARS));
        history.addAssistant("codex", "new-marker");

        String handoff = history.handoffFor("claude");
        assertTrue(handoff.contains("Earlier handoff content was compacted"));
        assertTrue(handoff.contains("new-marker"));
        assertTrue(handoff.length() < ChatHistory.MAX_HANDOFF_CHARS + 1_000);
    }

    @Test
    void clearDropsTranscriptSessionsAndCursors() {
        ChatHistory history = new ChatHistory();
        history.setSessionId("codex", "thread");
        history.addUser("codex", "question");
        history.markSynced("codex");

        history.clear();

        assertNull(history.sessionId("codex"));
        assertTrue(history.entries().isEmpty());
        assertTrue(history.handoffFor("codex").isEmpty());
    }
}
