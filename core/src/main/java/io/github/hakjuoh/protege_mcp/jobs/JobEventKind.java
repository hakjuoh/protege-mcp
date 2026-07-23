package io.github.hakjuoh.protege_mcp.jobs;

import com.fasterxml.jackson.annotation.JsonValue;

/** Ordered lifecycle facts emitted without job inputs, content, paths, or credentials. */
public enum JobEventKind {
    ACCEPTED("accepted"),
    STARTED("started"),
    PROGRESS("progress"),
    CANCEL_REQUESTED("cancel_requested"),
    CANCELLATION_EFFECTIVE("cancellation_effective"),
    PUBLICATION_STARTED("publication_started"),
    TERMINAL("terminal");

    private final String id;

    JobEventKind(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }
}
