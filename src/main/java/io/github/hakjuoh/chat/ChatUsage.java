package io.github.hakjuoh.chat;

/**
 * Per-turn token accounting reported by a provider when a turn finishes. Unknown integer fields are
 * {@code -1}; an unknown cost is {@code null} (e.g. Codex does not report a dollar cost).
 */
public record ChatUsage(int inputTokens, int outputTokens, int cachedInputTokens, Double costUsd) {

    public static ChatUsage unknown() {
        return new ChatUsage(-1, -1, -1, null);
    }
}
