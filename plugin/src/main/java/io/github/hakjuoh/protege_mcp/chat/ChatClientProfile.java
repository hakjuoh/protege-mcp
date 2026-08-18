package io.github.hakjuoh.protege_mcp.chat;

import java.util.Objects;

/**
 * Stable configuration identity for one Ontology Assistant client.
 *
 * <p>The {@code id} owns per-client conversation and model preferences, while {@code adapter}
 * identifies the runtime implementation that knows how to launch the client. Keeping those concepts
 * separate lets a later release add multiple user-named profiles backed by the same adapter without
 * sharing their model catalogs or remembered selections.
 */
public record ChatClientProfile(String id, ChatClientAdapter adapter, String defaultDisplayName,
        String executable) {

    public ChatClientProfile {
        id = required(id, "id");
        adapter = Objects.requireNonNull(adapter, "adapter");
        required(adapter.id(), "adapter.id");
        defaultDisplayName = required(defaultDisplayName, "defaultDisplayName");
        executable = required(executable, "executable");
    }

    /** Stable adapter type, exposed without making callers reach through the strategy object. */
    public String adapterId() {
        return adapter.id();
    }

    private static String required(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
