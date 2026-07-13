package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Per-validator outcome; unlike the overall gate, a policy-explicit stage may be skipped. */
public enum StageStatus {
    PASS("pass"),
    FAIL("fail"),
    ERROR("error"),
    SKIPPED("skipped");

    private final String json;

    StageStatus(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static StageStatus fromJson(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (StageStatus status : values()) {
                if (status.json.equals(normalized)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("stage status must be pass, fail, error, or skipped");
    }
}
