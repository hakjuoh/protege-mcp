package io.github.hakjuoh.protege_mcp.contracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Strict, composable QC result. A required skipped/missing/errored stage yields {@link GateStatus#ERROR};
 * a completed policy violation yields {@link GateStatus#FAIL}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
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
        Set<String> names = new LinkedHashSet<>();
        for (StageResult stage : stages) {
            if (!names.add(stage.stage())) {
                throw new IllegalArgumentException("duplicate stage result: " + stage.stage());
            }
        }
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
}
