package io.github.hakjuoh.protege_mcp.chat;

import java.util.List;

import io.github.hakjuoh.protege_mcp.chat.claude.ClaudeClient;
import io.github.hakjuoh.protege_mcp.chat.codex.CodexClient;
import io.github.hakjuoh.protege_mcp.chat.antigravity.AntigravityClient;
import io.github.hakjuoh.protege_mcp.chat.opencode.OpenCodeClient;

/** Registry of the predefined Assistant client profiles, in Preferences display order. */
public final class ChatClients {

    private static final List<ChatClientProfile> PREDEFINED = List.of(
            ClaudeClient.PROFILE, CodexClient.PROFILE,
            AntigravityClient.PROFILE, OpenCodeClient.PROFILE);

    private ChatClients() {
    }

    /** The built-in profiles. The immutable list is safe to use directly in Swing models. */
    public static List<ChatClientProfile> predefined() {
        return PREDEFINED;
    }

    /** Returns the predefined profile with this stable id, or {@code null}. */
    public static ChatClientProfile byId(String id) {
        if (id == null) {
            return null;
        }
        for (ChatClientProfile client : PREDEFINED) {
            if (client.id().equals(id)) {
                return client;
            }
        }
        return null;
    }
}
