package io.github.hakjuoh.protege_mcp.contracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Strict, composable QC result. A required skipped/missing/errored stage yields {@link GateStatus#ERROR};
 * a completed policy violation yields {@link GateStatus#FAIL}.
 */
public record GateResult(
        @JsonProperty("gate") GateStatus gate,
        @JsonProperty("policy_version") int policyVersion,
        @JsonProperty("semantic_fingerprint") String semanticFingerprint,
        @JsonProperty("required_stages") List<String> requiredStages,
        @JsonProperty("stages_ran") int stagesRan,
        @JsonProperty("stages_skipped") int stagesSkipped,
        @JsonProperty("stages") List<StageResult> stages,
        @JsonProperty("findings") List<Finding> findings,
        @JsonProperty("artifacts") List<ArtifactReference> artifacts,
        @JsonProperty("details") Map<String, Object> details) {

    public GateResult {
        if (gate == null) {
            throw new IllegalArgumentException("gate must not be null");
        }
        if (policyVersion < 1) {
            throw new IllegalArgumentException("policy_version must be at least 1");
        }
        semanticFingerprint = ContractValues.fingerprint(semanticFingerprint, "semantic_fingerprint");
        requiredStages = ContractValues.strings(requiredStages, "required_stages", false);
        stages = ContractValues.list(stages, "stages");
        findings = ContractValues.list(findings, "findings");
        artifacts = ContractValues.list(artifacts, "artifacts");
        details = ContractValues.map(details);
        if (stagesRan < 0 || stagesSkipped < 0 || stagesRan + stagesSkipped != stages.size()) {
            throw new IllegalArgumentException("stage counts must be non-negative and equal stages.size()");
        }
        Map<String, StageResult> byName = new LinkedHashMap<>();
        int actualRan = 0;
        int actualSkipped = 0;
        List<Finding> stagedFindings = new ArrayList<>();
        for (StageResult stage : stages) {
            if (byName.putIfAbsent(stage.stage(), stage) != null) {
                throw new IllegalArgumentException("duplicate stage result: " + stage.stage());
            }
            if (stage.status() == StageStatus.SKIPPED) {
                actualSkipped++;
            } else {
                actualRan++;
            }
            stagedFindings.addAll(stage.findings());
        }
        if (stagesRan != actualRan || stagesSkipped != actualSkipped) {
            throw new IllegalArgumentException("stage counts do not match stage statuses");
        }
        if (!findings.equals(stagedFindings)) {
            throw new IllegalArgumentException("findings must equal the ordered findings from stages");
        }

        Object failOnValue = details.get("fail_on");
        if (!(failOnValue instanceof String)) {
            throw new IllegalArgumentException("details.fail_on must be info, warning, or error");
        }
        FindingSeverity failOn = FindingSeverity.fromJson((String) failOnValue);
        List<String> missingRequired = new ArrayList<>();
        boolean requiredExecutionError = false;
        boolean requiredPolicyFailure = false;
        for (String required : requiredStages) {
            StageResult stage = byName.get(required);
            if (stage == null) {
                missingRequired.add(required);
                requiredExecutionError = true;
                continue;
            }
            if (stage.status() == StageStatus.ERROR || stage.status() == StageStatus.SKIPPED) {
                requiredExecutionError = true;
            }
            if (stage.status() == StageStatus.FAIL
                    || stage.findings().stream().anyMatch(f -> f.severity().reaches(failOn))) {
                requiredPolicyFailure = true;
            }
        }
        List<String> declaredMissing = detailStrings(details.get("missing_required_stages"));
        if (!missingRequired.equals(declaredMissing)) {
            throw new IllegalArgumentException("details.missing_required_stages must match absent required stages");
        }
        GateStatus expected = requiredExecutionError ? GateStatus.ERROR
                : requiredPolicyFailure ? GateStatus.FAIL : GateStatus.PASS;
        if (gate != expected) {
            throw new IllegalArgumentException("gate must be " + expected.json()
                    + " for the supplied required stages and fail_on threshold");
        }
        Map<String, Object> normalizedDetails = new LinkedHashMap<>(details);
        normalizedDetails.put("fail_on", failOn.json());
        if (normalizedDetails.containsKey("missing_required_stages")) {
            normalizedDetails.put("missing_required_stages",
                    Collections.unmodifiableList(new ArrayList<>(declaredMissing)));
        }
        details = Collections.unmodifiableMap(normalizedDetails);
    }

    /** Aggregate completed stage results under strict required-stage semantics. */
    public static GateResult aggregate(int policyVersion, String semanticFingerprint,
            List<String> requiredStages, List<StageResult> stageResults, FindingSeverity failOn) {
        List<String> required = ContractValues.strings(requiredStages, "required_stages", false);
        List<StageResult> supplied = ContractValues.list(stageResults, "stages");
        if (failOn == null) {
            throw new IllegalArgumentException("fail_on must not be null");
        }

        Map<String, StageResult> byName = new LinkedHashMap<>();
        for (StageResult stage : supplied) {
            if (byName.putIfAbsent(stage.stage(), stage) != null) {
                throw new IllegalArgumentException("duplicate stage result: " + stage.stage());
            }
        }

        List<StageResult> stages = new ArrayList<>(supplied);
        List<String> missingRequired = new ArrayList<>();
        for (String requiredStage : required) {
            if (!byName.containsKey(requiredStage)) {
                missingRequired.add(requiredStage);
            }
        }

        GateStatus gate = missingRequired.isEmpty() ? GateStatus.PASS : GateStatus.ERROR;
        for (String requiredStage : required) {
            StageResult stage = byName.get(requiredStage);
            if (stage == null) {
                continue;
            }
            if (stage.status() == StageStatus.ERROR || stage.status() == StageStatus.SKIPPED) {
                gate = GateStatus.ERROR;
                break;
            }
            if ((stage.status() == StageStatus.FAIL
                    || stage.findings().stream().anyMatch(f -> f.severity().reaches(failOn)))
                    && gate != GateStatus.ERROR) {
                gate = GateStatus.FAIL;
            }
        }

        int ran = 0;
        int skipped = 0;
        List<Finding> findings = new ArrayList<>();
        for (StageResult stage : stages) {
            if (stage.status() == StageStatus.SKIPPED) {
                skipped++;
            } else {
                ran++;
            }
            findings.addAll(stage.findings());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fail_on", failOn.json());
        if (!missingRequired.isEmpty()) {
            details.put("missing_required_stages", Collections.unmodifiableList(missingRequired));
        }
        return new GateResult(gate, policyVersion, semanticFingerprint, required, ran, skipped,
                stages, findings, Collections.emptyList(), details);
    }

    private static List<String> detailStrings(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("details.missing_required_stages must be an array");
        }
        List<String> result = new ArrayList<>();
        for (Object entry : (List<?>) value) {
            if (!(entry instanceof String) || ((String) entry).trim().isEmpty()) {
                throw new IllegalArgumentException("details.missing_required_stages entries must be strings");
            }
            result.add((String) entry);
        }
        return result;
    }
}
