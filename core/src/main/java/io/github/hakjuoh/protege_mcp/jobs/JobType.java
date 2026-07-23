package io.github.hakjuoh.protege_mcp.jobs;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/** Closed set of asynchronous operations supported by the 0.8 runtime. */
public enum JobType {
    CLASSIFICATION("classification", true),
    PROJECT_QC("project_qc", false),
    SEMANTIC_DIFF("semantic_diff", false),
    INFERENCE_MATERIALIZATION("inference_materialization", true);

    private final String id;
    private final boolean requiresReasoner;

    JobType(String id, boolean requiresReasoner) {
        this.id = id;
        this.requiresReasoner = requiresReasoner;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public boolean requiresReasoner() {
        return requiresReasoner;
    }

    public JobResultType resultType() {
        return JobResultType.valueOf(name());
    }

    public static JobType fromId(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (JobType type : values()) {
                if (type.id.equals(normalized)) return type;
            }
        }
        throw new IllegalArgumentException("unknown job type");
    }
}
