package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.qc.ProjectQcRequest;
import io.github.hakjuoh.protege_mcp.core.qc.ProjectQcService;
import io.github.hakjuoh.protege_mcp.core.qc.QcStageExecution;
import io.github.hakjuoh.protege_mcp.core.qc.QcStageVerdict;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Pure aggregation and strict-gate rendering for completed QC stage executions. */
final class QcGate {
    private static final String REASONER = "reasoner";

    private QcGate() {
    }

    /** Collapse the stage results into one gate verdict. */
    static CallToolResult aggregate(List<QcStageResult> results, String failOn) {
        List<Map<String, Object>> stagesJson = new ArrayList<>();
        int worst = 0;
        int ran = 0;
        for (QcStageResult result : results) {
            stagesJson.add(result.toJson());
            if (result.ran) {
                ran++;
                worst = Math.max(worst, level(result.verdict));
            }
        }
        String gate = worst >= threshold(failOn) ? QcSuiteTools.FAIL : QcSuiteTools.PASS;
        Tools.Json json = Tools.json()
                .put("gate", gate)
                .put("fail_on", failOn)
                .put("stages_ran", ran)
                .put("stages", stagesJson);
        if (ran == 0) {
            // Guard the vacuous pass: with zero stages actually run (all requested stages skipped — e.g.
            // their backing data was absent) the 'pass' gate attests to nothing. Surface that explicitly.
            json.put("note", "No stages ran — every requested stage was skipped (see each stage's "
                    + "reason), so the 'pass' gate is vacuous, not an attestation of quality.");
        }
        return json.result();
    }

    /**
     * The COMPLETE, uncapped set of unsatisfiable-class IRIs the reasoner stage found (or null when the
     * stage did not run consistently). Verified-apply attribution diffs these full sets so a display cap
     * on the public {@code unsatisfiable_classes.items} never truncates a baseline comparison.
     */
    static Set<String> reasonerUnsatIris(QcSuiteExecution execution) {
        if (execution == null) return null;
        for (QcStageResult result : execution.results) {
            if (REASONER.equals(result.stage)) {
                return result.attributionUnsatIris();
            }
        }
        return null;
    }

    /**
     * The complete gating-finding identity set a non-reasoner stage recorded for attribution, or null
     * when the stage did not run or is not instrumented. Verified-apply attribution set-diffs these so
     * a swap that lowers the total count is still caught and a pure removal is not misattributed.
     */
    static Set<String> stageAttributionIdentities(QcSuiteExecution execution, String stage) {
        if (execution == null) return null;
        for (QcStageResult result : execution.results) {
            if (stage.equals(result.stage)) {
                return result.attributionIdentities();
            }
        }
        return null;
    }

    /** Strict pass/fail/error gate used by project policy and opt-in strict legacy calls. */
    static Map<String, Object> strictResult(QcSuiteExecution execution, Set<String> requiredStages,
            String failOn, int policyVersion, String policyDigest, boolean policyLoaded) {
        List<QcStageExecution> stages = new ArrayList<>();
        for (QcStageResult result : execution.results) {
            QcStageVerdict verdict = result.executionError ? QcStageVerdict.ERROR
                    : !result.ran ? QcStageVerdict.SKIPPED
                    : QcStageVerdict.fromLegacy(result.verdict);
            stages.add(new QcStageExecution(result.stage, verdict, result.reason, result.summary));
        }
        ProjectQcRequest request = new ProjectQcRequest(policyLoaded, policyVersion, policyDigest,
                new ArrayList<>(requiredStages), failOn, execution.fingerprint,
                execution.selectedReasoner, execution.snapshotConsistent, execution.preconditionError,
                execution.snapshotMode, execution.snapshotStages, execution.sameValidationSnapshot,
                execution.closureFingerprint);
        return ProjectQcService.aggregate(request, stages).toMap();
    }

    static int level(String verdict) {
        if (QcSuiteTools.FAIL.equals(verdict)) {
            return 3;
        }
        if (QcSuiteTools.WARN.equals(verdict)) {
            return 2;
        }
        if (QcSuiteTools.INFO.equals(verdict)) {
            return 1;
        }
        return 0;
    }

    static int threshold(String failOn) {
        return switch (failOn) {
            case "info" -> 1;
            case "warn" -> 2;
            case "error" -> 3;
            default -> Integer.MAX_VALUE;
        };
    }

}
