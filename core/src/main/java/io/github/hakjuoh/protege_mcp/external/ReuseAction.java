package io.github.hakjuoh.protege_mcp.external;

import java.util.Locale;

/** Supported explicit outcomes for one immutable reuse proposal. */
public enum ReuseAction {
    REUSE_IRI,
    ADD_MAPPING,
    MINT_LOCAL_WITH_MAPPING;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ReuseAction parse(String value) {
        if (value == null) throw new IllegalArgumentException("reuse action is required");
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unsupported reuse action", invalid);
        }
    }
}
