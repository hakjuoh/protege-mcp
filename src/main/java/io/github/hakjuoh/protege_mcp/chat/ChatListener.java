package io.github.hakjuoh.protege_mcp.chat;

/**
 * Streaming callbacks a {@link ChatProvider} fires while a turn runs. Implementations are invoked on
 * the provider's daemon worker thread (never the EDT), so a Swing listener must marshal to the EDT
 * itself (see {@link io.github.hakjuoh.protege_mcp.ui.ChatView}). Every method has a no-op default so a listener
 * only overrides what it needs.
 */
public interface ChatListener {

    /** The provider's session/thread id, for resuming subsequent turns. */
    default void onSessionId(String sessionId) {
    }

    /** A chunk of assistant-visible text (a streamed delta, or a whole message for Codex). */
    default void onAssistantText(String text) {
    }

    /** A chunk of model reasoning ("thinking"); shown only when the panel opts in. */
    default void onThinking(String text) {
    }

    /** A short note that the assistant invoked a tool / ran an action (e.g. {@code create_class}). */
    default void onToolActivity(String summary) {
    }

    /** A live (running) token count emitted while the turn streams; not necessarily final or exact. */
    default void onUsage(ChatUsage usage) {
    }

    /** Final token/cost accounting for the turn. */
    default void onResult(ChatUsage usage) {
    }

    /** A non-fatal or fatal error message surfaced to the transcript. */
    default void onError(String message) {
    }

    /** The provider process exited; {@code exitCode} is 0 on success ({@code -1} if unknown). */
    default void onComplete(int exitCode) {
    }
}
