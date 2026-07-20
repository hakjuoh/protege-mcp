package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

/** Pure strict-gate orchestration shared by Protégé and headless project-QC adapters. */
public final class ProjectQcService {

    private static final String INTEROPERABILITY = "interoperability";

    private ProjectQcService() {
    }

    /**
     * Assess completed stage executions without consulting a live editor, filesystem, clock, or network.
     * Required missing/skipped stages and every execution error fail closed; completed findings are
     * compared to the configured threshold. Snapshot/precondition failures outrank policy failures.
     */
    public static ProjectQcReport aggregate(ProjectQcRequest request,
            List<QcStageExecution> executions) {
        if (request == null || executions == null) {
            throw new IllegalArgumentException("request and executions must not be null");
        }
        Map<String, QcStageExecution> byStage = new LinkedHashMap<>();
        for (QcStageExecution execution : executions) {
            if (execution == null) {
                throw new IllegalArgumentException("executions must not contain null");
            }
            if (byStage.putIfAbsent(execution.stage(), execution) != null) {
                throw new IllegalArgumentException("duplicate stage execution: " + execution.stage());
            }
        }

        Set<String> required = new LinkedHashSet<>(request.requiredStages());
        List<ProjectQcStageReport> reports = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        boolean gateError = !request.snapshotConsistent() || request.preconditionError() != null;
        boolean gateFail = false;
        int ran = 0;
        int skipped = 0;

        if (!request.snapshotConsistent()) {
            findings.add(commonFinding("snapshot.changed", "orchestrator", FindingSeverity.ERROR,
                    "The active ontology or loaded import closure changed between project-QC "
                            + "preconditions/classification and the shared validation snapshot; results "
                            + "are not one revision.", null));
        }
        if (request.preconditionError() != null) {
            findings.add(commonFinding("precondition.changed", "orchestrator", FindingSeverity.ERROR,
                    request.preconditionError(), null));
        }
        if (!request.fingerprint().releaseStable()) {
            findings.add(commonFinding("fingerprint.session_only", "fingerprint",
                    FindingSeverity.WARNING, String.join(" ", request.fingerprint().warnings()),
                    Map.of("stability", request.fingerprint().stability())));
        }

        for (QcStageExecution execution : executions) {
            boolean isRequired = required.contains(execution.stage());
            List<Finding> stageFindings = new ArrayList<>();
            StageStatus status;
            String message = null;
            if (execution.verdict() == QcStageVerdict.ERROR) {
                status = StageStatus.ERROR;
                ran++;
                gateError = true;
                Finding finding = commonFinding(execution.stage() + ".execution", execution.stage(),
                        FindingSeverity.ERROR, execution.message(), execution.details());
                findings.add(finding);
                stageFindings.add(finding);
                message = execution.message();
            } else if (execution.verdict() == QcStageVerdict.SKIPPED) {
                status = isRequired ? StageStatus.ERROR : StageStatus.SKIPPED;
                skipped++;
                message = execution.message();
                if (isRequired) {
                    gateError = true;
                    Finding finding = commonFinding(execution.stage() + ".missing", execution.stage(),
                            FindingSeverity.ERROR, execution.message(), null);
                    findings.add(finding);
                    stageFindings.add(finding);
                }
            } else {
                ran++;
                boolean fails = reaches(execution.verdict(), request.failOn());
                status = fails ? StageStatus.FAIL : StageStatus.PASS;
                if (fails) {
                    gateFail = true;
                }
                if (execution.verdict() != QcStageVerdict.PASS) {
                    Finding finding = commonFinding(execution.stage() + ".finding", execution.stage(),
                            execution.verdict().severity(), execution.stage() + " reported "
                                    + execution.verdict().legacyVerdict(), execution.details());
                    findings.add(finding);
                    stageFindings.add(finding);
                }
            }
            reports.add(new ProjectQcStageReport(execution, true, isRequired, status, message,
                    stageFindings));
        }

        List<String> missingRequired = new ArrayList<>();
        for (String stage : request.requiredStages()) {
            QcStageExecution execution = byStage.get(stage);
            if (execution == null) {
                gateError = true;
                skipped++;
                missingRequired.add(stage);
                String message = "required stage was not scheduled";
                Finding finding = commonFinding(stage + ".missing", stage, FindingSeverity.ERROR,
                        message, null);
                findings.add(finding);
                reports.add(new ProjectQcStageReport(QcStageExecution.skipped(stage, message), false,
                        true, StageStatus.ERROR, message, List.of(finding)));
            } else if (execution.verdict() == QcStageVerdict.SKIPPED
                    || execution.verdict() == QcStageVerdict.ERROR) {
                missingRequired.add(stage);
            }
        }

        Object rdfDatasetFingerprint = null;
        Map<String, Object> rdfDatasetIdentity = null;
        QcStageExecution interoperability = byStage.get(INTEROPERABILITY);
        if (interoperability != null && interoperability.verdict().ran()
                && !interoperability.verdict().executionError()
                && interoperability.details() != null) {
            rdfDatasetFingerprint = interoperability.details().get("rdf_dataset_fingerprint");
            rdfDatasetIdentity = interoperability.details();
        }

        GateStatus gate = gateError ? GateStatus.ERROR : gateFail ? GateStatus.FAIL : GateStatus.PASS;
        return new ProjectQcReport(gate, request, reports, findings, ran, skipped,
                rdfDatasetFingerprint, rdfDatasetIdentity, missingRequired);
    }

    private static boolean reaches(QcStageVerdict verdict, String threshold) {
        if (verdict.severity() == null || "none".equals(threshold)) {
            return false;
        }
        FindingSeverity required = switch (threshold) {
            case "info" -> FindingSeverity.INFO;
            case "warn" -> FindingSeverity.WARNING;
            case "error" -> FindingSeverity.ERROR;
            default -> throw new IllegalArgumentException("unsupported fail_on: " + threshold);
        };
        return verdict.severity().reaches(required);
    }

    private static Finding commonFinding(String id, String source, FindingSeverity severity,
            String message, Map<String, Object> details) {
        return new Finding(id, source, severity, message == null ? id : message,
                null, null, null, id, null, details == null ? Map.of() : details);
    }
}
