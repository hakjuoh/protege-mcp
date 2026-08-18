package io.github.hakjuoh.protege_mcp.chat;

import java.util.List;

/** One model offered by a client and the explicit reasoning-effort values it supports. */
public record ChatModelDefinition(String id, List<String> reasoningEfforts) {

    public ChatModelDefinition {
        id = id == null ? "" : id.trim();
        reasoningEfforts = ChatReasoningEfforts.normalize(reasoningEfforts);
    }
}
