package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Overall strict-gate outcome: policy violation ({@code fail}) differs from execution {@code error}. */
public enum GateStatus {
    PASS("pass"),
    FAIL("fail"),
    ERROR("error");

    private final String json;

    GateStatus(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static GateStatus fromJson(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (GateStatus status : values()) {
                if (status.json.equals(normalized)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("gate must be pass, fail, or error");
    }
}
