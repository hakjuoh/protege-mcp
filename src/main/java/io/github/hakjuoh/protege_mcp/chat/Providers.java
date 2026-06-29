package io.github.hakjuoh.protege_mcp.chat;

import io.github.hakjuoh.protege_mcp.chat.claude.ClaudeCliProvider;
import io.github.hakjuoh.protege_mcp.chat.codex.CodexCliProvider;

import java.util.ArrayList;
import java.util.List;

/** The chat providers the plugin knows about, and which are installed on this machine. */
public final class Providers {

    private Providers() {
    }

    /** Every known provider, in display order (regardless of whether it is installed). */
    public static List<ChatProvider> all() {
        return List.of(new ClaudeCliProvider(), new CodexCliProvider());
    }

    /** Only the providers whose CLI is installed and resolvable right now. */
    public static List<ChatProvider> available() {
        List<ChatProvider> out = new ArrayList<>();
        for (ChatProvider p : all()) {
            if (p.isAvailable()) {
                out.add(p);
            }
        }
        return out;
    }

    /** The provider with the given id, or {@code null}. */
    public static ChatProvider byId(String id) {
        if (id == null) {
            return null;
        }
        for (ChatProvider p : all()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }
}
