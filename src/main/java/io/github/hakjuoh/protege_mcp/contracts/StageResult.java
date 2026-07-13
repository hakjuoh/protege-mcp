package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Result of one validation stage before strict-gate aggregation. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StageResult(
        @JsonProperty("stage") String stage,
        @JsonProperty("status") StageStatus status,
        @JsonProperty("message") String message,
        @JsonProperty("findings") List<Finding> findings,
        @JsonProperty("details") Map<String, Object> details) {

    public StageResult {
        stage = ContractValues.nonBlank(stage, "stage");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if ((status == StageStatus.ERROR || status == StageStatus.SKIPPED)
                && (message == null || message.trim().isEmpty())) {
            throw new IllegalArgumentException(status.json() + " stage requires a message");
        }
        findings = ContractValues.list(findings, "findings");
        details = ContractValues.map(details);
    }

    public static StageResult pass(String stage, List<Finding> findings) {
        return new StageResult(stage, StageStatus.PASS, null, findings, Collections.emptyMap());
    }

    public static StageResult fail(String stage, List<Finding> findings) {
        return new StageResult(stage, StageStatus.FAIL, null, findings, Collections.emptyMap());
    }

    public static StageResult error(String stage, String message) {
        return new StageResult(stage, StageStatus.ERROR, message,
                Collections.emptyList(), Collections.emptyMap());
    }

    public static StageResult skipped(String stage, String message) {
        return new StageResult(stage, StageStatus.SKIPPED, message,
                Collections.emptyList(), Collections.emptyMap());
    }
}
