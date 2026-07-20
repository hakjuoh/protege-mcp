package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

/** Assessed stage result with both the common contract and legacy-compatible envelope fields. */
public record ProjectQcStageReport(
        QcStageExecution execution,
        boolean scheduled,
        boolean required,
        StageStatus status,
        String message,
        List<Finding> findings) {

    public ProjectQcStageReport {
        if (execution == null || status == null || findings == null) {
            throw new IllegalArgumentException("stage report fields must not be null");
        }
        findings = Collections.unmodifiableList(new ArrayList<>(findings));
    }

    /** Stable JSON-shaped map used by existing plugin and future headless surfaces. */
    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, Object> projectedDetails = execution.details() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(execution.details());
        List<Map<String, Object>> projectedFindings = new ArrayList<>(
                findings.stream().map(ProjectQcReport::findingMap).toList());
        if (!scheduled) {
            // Preserve the released synthetic-row key order because deterministic change-set
            // contract digests include the JSON-shaped map iteration order.
            row.put("stage", execution.stage());
            row.put("required", required);
            row.put("ran", false);
            row.put("status", status.json());
            row.put("reason", execution.message());
            row.put("message", message);
            row.put("findings", projectedFindings);
            row.put("details", projectedDetails);
            return row;
        }
        row.put("stage", execution.stage());
        row.put("ran", execution.verdict().ran());
        if (execution.verdict().ran()) {
            row.put("verdict", execution.verdict().legacyVerdict());
            if (execution.verdict().executionError()) {
                row.put("error", true);
            }
            if (execution.details() != null) {
                row.put("findings_summary", projectedDetails);
            }
        } else {
            row.put("reason", execution.message());
        }
        row.put("required", required);
        row.put("status", status.json());
        row.put("message", message);
        row.put("findings", projectedFindings);
        row.put("details", projectedDetails);
        return row;
    }
}
