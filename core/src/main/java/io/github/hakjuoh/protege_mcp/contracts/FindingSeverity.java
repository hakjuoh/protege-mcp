package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Severity vocabulary shared by project validators and strict gates. */
public enum FindingSeverity {
    INFO(1, "info"),
    WARNING(2, "warning"),
    ERROR(3, "error");

    private final int rank;
    private final String json;

    FindingSeverity(int rank, String json) {
        this.rank = rank;
        this.json = json;
    }

    public boolean reaches(FindingSeverity threshold) {
        return rank >= threshold.rank;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static FindingSeverity fromJson(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (FindingSeverity severity : values()) {
                if (severity.json.equals(normalized)) {
                    return severity;
                }
            }
        }
        throw new IllegalArgumentException("severity must be info, warning, or error");
    }
}
