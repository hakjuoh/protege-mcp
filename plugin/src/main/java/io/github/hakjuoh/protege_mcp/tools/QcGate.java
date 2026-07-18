package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Pure aggregation and strict-gate rendering for completed QC stage executions. */
final class QcGate {
    private static final String INTEROPERABILITY = "interoperability";
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
        Map<String, QcStageResult> byStage = new LinkedHashMap<>();
        for (QcStageResult result : execution.results) {
            byStage.put(result.stage, result);
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        int ran = 0;
        int skipped = 0;
        boolean gateError = !execution.snapshotConsistent || execution.preconditionError != null;
        boolean gateFail = false;

        if (!execution.snapshotConsistent) {
            findings.add(commonFinding("snapshot.changed", "orchestrator", "error",
                    "The active ontology or loaded import closure changed between project-QC "
                            + "preconditions/classification and the shared validation snapshot; results "
                            + "are not one revision.", null));
        }
        if (execution.preconditionError != null) {
            findings.add(commonFinding("precondition.changed", "orchestrator", "error",
                    execution.preconditionError, null));
        }
        if (!execution.fingerprint.releaseStable()) {
            findings.add(commonFinding("fingerprint.session_only", "fingerprint", "warning",
                    String.join(" ", execution.fingerprint.warnings()),
                    Map.of("stability", execution.fingerprint.stability())));
        }

        for (String stage : execution.results.stream().map(result -> result.stage).toList()) {
            QcStageResult result = byStage.get(stage);
            boolean required = requiredStages.contains(stage);
            Map<String, Object> row = result.toJson();
            row.put("required", required);
            List<Map<String, Object>> stageFindings = new ArrayList<>();
            String stageMessage = null;
            String status;
            if (result.executionError) {
                status = "error";
                ran++;
                // A stage that ERRORED could not produce a verdict; fail closed regardless of whether it
                // was required. The `required` flag governs only the missing/skipped→error escalation.
                gateError = true;
                Map<String, Object> finding = commonFinding(stage + ".execution", stage, "error",
                        result.reason, result.summary);
                findings.add(finding);
                stageFindings.add(finding);
                stageMessage = result.reason;
            } else if (!result.ran) {
                status = required ? "error" : "skipped";
                skipped++;
                stageMessage = result.reason;
                if (required) {
                    gateError = true;
                    Map<String, Object> finding = commonFinding(stage + ".missing", stage, "error",
                            result.reason, null);
                    findings.add(finding);
                    stageFindings.add(finding);
                }
            } else {
                ran++;
                boolean fails = level(result.verdict) >= threshold(failOn);
                status = fails ? "fail" : "pass";
                // The gate is the worst RAN stage versus fail_on (the documented contract). A ran stage
                // that reaches fail_on fails the gate whether or not it was required; `required` only
                // adds the missing/skipped→error semantics handled above.
                if (fails) {
                    gateFail = true;
                }
                if (!QcSuiteTools.PASS.equals(result.verdict)) {
                    String severity = QcSuiteTools.FAIL.equals(result.verdict) ? "error"
                            : QcSuiteTools.WARN.equals(result.verdict) ? "warning" : "info";
                    Map<String, Object> finding = commonFinding(stage + ".finding", stage, severity,
                            stage + " reported " + result.verdict, result.summary);
                    findings.add(finding);
                    stageFindings.add(finding);
                }
            }
            row.put("status", status);
            row.put("message", stageMessage);
            row.put("findings", stageFindings);
            row.put("details", result.summary == null ? Collections.emptyMap() : result.summary);
            stages.add(row);
        }
        // A required stage absent from execution is also an error (e.g. a caller passed a stale/future id).
        for (String required : requiredStages) {
            if (!byStage.containsKey(required)) {
                gateError = true;
                skipped++;
                Map<String, Object> finding = commonFinding(required + ".missing", required, "error",
                        "required stage was not scheduled", null);
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("stage", required);
                missing.put("required", true);
                missing.put("ran", false);
                missing.put("status", "error");
                missing.put("reason", "required stage was not scheduled");
                missing.put("message", "required stage was not scheduled");
                missing.put("findings", List.of(finding));
                missing.put("details", Collections.emptyMap());
                stages.add(missing);
                findings.add(finding);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate", gateError ? "error" : gateFail ? "fail" : "pass");
        result.put("policy_loaded", policyLoaded);
        if (policyVersion > 0) {
            result.put("policy_version", policyVersion);
        }
        if (policyDigest != null) {
            result.put("policy_digest", policyDigest);
        }
        result.put("semantic_fingerprint", execution.fingerprint.semanticFingerprint());
        QcStageResult interoperability = byStage.get(INTEROPERABILITY);
        if (interoperability != null && interoperability.ran && !interoperability.executionError
                && interoperability.summary != null) {
            result.put("rdf_dataset_fingerprint",
                    interoperability.summary.get("rdf_dataset_fingerprint"));
            result.put("rdf_dataset_identity", interoperability.summary);
        }
        result.put("fingerprint_stability", execution.fingerprint.stability());
        result.put("release_stable", execution.fingerprint.releaseStable());
        result.put("fingerprint_warnings", execution.fingerprint.warnings());
        result.put("reasoner", execution.selectedReasoner);
        result.put("required_stages", new ArrayList<>(requiredStages));
        result.put("stages_ran", ran);
        result.put("stages_skipped", skipped);
        result.put("fail_on", failOn);
        result.put("stages", stages);
        result.put("findings", findings);
        result.put("artifacts", Collections.emptyList());
        result.put("snapshot_consistent", execution.snapshotConsistent);
        Map<String, Object> validationSnapshot = new LinkedHashMap<>();
        validationSnapshot.put("mode", execution.snapshotMode);
        validationSnapshot.put("same_snapshot", execution.sameValidationSnapshot);
        validationSnapshot.put("semantic_fingerprint", execution.fingerprint.semanticFingerprint());
        validationSnapshot.put("closure_fingerprint", execution.closureFingerprint);
        validationSnapshot.put("stages", execution.snapshotStages);
        result.put("validation_snapshot", validationSnapshot);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fail_on", "warn".equals(failOn) ? "warning" : failOn);
        List<String> missingRequired = new ArrayList<>();
        for (String required : requiredStages) {
            QcStageResult stage = byStage.get(required);
            if (stage == null || !stage.ran || stage.executionError) {
                missingRequired.add(required);
            }
        }
        if (!missingRequired.isEmpty()) {
            details.put("missing_required_stages", missingRequired);
        }
        result.put("details", details);
        return result;
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

    private static Map<String, Object> commonFinding(String id, String source, String severity,
            String message, Map<String, Object> details) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", id);
        finding.put("source", source);
        finding.put("severity", severity);
        finding.put("message", message == null ? id : message);
        finding.put("focus_iri", null);
        finding.put("axiom", null);
        finding.put("path", null);
        finding.put("rule_id", id);
        finding.put("waiver", null);
        finding.put("details", details == null ? Collections.emptyMap() : details);
        return finding;
    }
}
