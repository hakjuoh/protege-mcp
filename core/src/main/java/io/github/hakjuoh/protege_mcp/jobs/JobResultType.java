package io.github.hakjuoh.protege_mcp.jobs;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Closed discriminator for the four public 0.8 job result families.
 *
 * <p>Each value is bound one-to-one to a {@link JobType}; adapters cannot select an unrelated
 * free-form discriminator for a submission.</p>
 */
public enum JobResultType {
    CLASSIFICATION("classification", JobType.CLASSIFICATION),
    PROJECT_QC("project_qc", JobType.PROJECT_QC),
    SEMANTIC_DIFF("semantic_diff", JobType.SEMANTIC_DIFF),
    INFERENCE_MATERIALIZATION("inference_materialization", JobType.INFERENCE_MATERIALIZATION);

    private final String id;
    private final JobType jobType;

    JobResultType(String id, JobType jobType) {
        this.id = id;
        this.jobType = jobType;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public JobType jobType() {
        return jobType;
    }

    public static JobResultType fromId(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (JobResultType type : values()) {
                if (type.id.equals(normalized)) return type;
            }
        }
        throw new IllegalArgumentException("unknown job result type");
    }
}
