package io.github.hakjuoh.protege_mcp.chat;

import java.util.List;

/**
 * A backend that can run a chat turn against the live ontology. The shipped implementations drive a
 * locally-installed coding-agent CLI ({@link ClaudeCliProvider}, {@link CodexCliProvider}) configured
 * to connect back to Protégé's own MCP server, so the agent loop and provider authentication live in
 * the CLI and the plugin holds no API key. The seam is kept deliberately small so a future
 * direct-API provider could slot in behind it.
 */
public interface ChatProvider {

    /** Stable id persisted in preferences, e.g. {@code "claude"}. */
    String id();

    /** Human-facing name for the selector, e.g. {@code "Claude"} (the button reads "Use Claude"). */
    String displayName();

    /** Whether the CLI is installed and resolvable (cheap filesystem check — safe on the EDT). */
    boolean isAvailable();

    /**
     * Model ids/aliases offered in the picker. The first entry is the empty string {@code ""} meaning
     * "use the CLI's own default model" (so the chat works out of the box for whatever the user's CLI
     * is configured with).
     */
    List<String> listModels();

    /** The model selected by default (blank = the CLI's own default). */
    String defaultModel();

    /**
     * Spawn the CLI for one turn and stream its output to {@code listener}. Returns immediately with a
     * handle to cancel the in-flight turn. Must be called off the EDT (it starts a process).
     */
    ChatProcess startTurn(ChatRequest request, ChatListener listener) throws java.io.IOException;
}
