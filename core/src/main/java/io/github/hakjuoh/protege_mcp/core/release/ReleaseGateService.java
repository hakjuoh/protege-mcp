package io.github.hakjuoh.protege_mcp.core.release;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.StageResult;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

/** Pure release-gate aggregation shared by Protégé and headless adapters. */
public final class ReleaseGateService {

    private static final String RELEASE_STAGE = "release";

    private ReleaseGateService() {
    }

    /**
     * Adapt the established JSON-shaped project-QC envelope into the common gate contract, append
     * release-only findings, and preserve the project-QC verdict. The adapter deliberately accepts a
     * map because the released plugin envelope predates the typed core report; headless callers can
     * therefore consume the same contract while the delivery adapters are migrated independently.
     */
    public static GateResult aggregate(Map<String, Object> projectQc,
            List<Finding> releaseFindings, String projectQcGate) {
        if (projectQc == null || releaseFindings == null) {
            throw new IllegalArgumentException("projectQc and releaseFindings must not be null");
        }
        int policyVersion = projectQc.get("policy_version") instanceof Number number
                ? number.intValue() : 1;
        String fingerprint = string(projectQc.get("semantic_fingerprint"));
        FindingSeverity failOn = failOn(projectQc);

        List<StageResult> stages = new ArrayList<>();
        List<Finding> stagedFindings = new ArrayList<>();
        Object rawStages = projectQc.get("stages");
        if (rawStages != null && !(rawStages instanceof List<?>)) {
            throw new IllegalArgumentException("project QC stages must be an array");
        }
        for (Object rawStage : rawStages instanceof List<?> list ? list : List.of()) {
            if (!(rawStage instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("project QC stage must be an object");
            }
            String name = string(map.get("stage"));
            StageStatus status = StageStatus.fromJson(string(map.get("status")));
            List<Finding> findings = findings(map.get("findings"));
            stagedFindings.addAll(findings);
            String message = string(map.get("message"));
            if ((status == StageStatus.ERROR || status == StageStatus.SKIPPED)
                    && (message == null || message.isBlank())) {
                message = name + " " + status.json();
            }
            stages.add(new StageResult(name, status, message, findings, object(map.get("details"))));
        }

        // Snapshot/precondition/fingerprint findings live only at the top level of the legacy QC
        // envelope. Preserve them exactly once in the synthetic release stage.
        List<Finding> releaseStageFindings = new ArrayList<>();
        Object rawFindings = projectQc.get("findings");
        if (rawFindings != null && !(rawFindings instanceof List<?>)) {
            throw new IllegalArgumentException("project QC findings must be an array");
        }
        for (Finding finding : findings(rawFindings)) {
            if (!containsFinding(stagedFindings, finding)) {
                releaseStageFindings.add(finding);
            }
        }
        releaseStageFindings.addAll(releaseFindings);

        String envelopeGate = string(projectQc.get("gate"));
        String normalizedEnvelopeGate = envelopeGate == null ? null
                : envelopeGate.trim().toLowerCase();
        String normalizedQcGate = projectQcGate == null ? normalizedEnvelopeGate
                : projectQcGate.trim().toLowerCase();
        if (normalizedQcGate != null
                && !Set.of("pass", "fail", "error").contains(normalizedQcGate)) {
            throw new IllegalArgumentException("projectQcGate must be pass, fail, or error");
        }
        if (normalizedEnvelopeGate != null
                && !Set.of("pass", "fail", "error").contains(normalizedEnvelopeGate)) {
            throw new IllegalArgumentException("project QC gate must be pass, fail, or error");
        }
        if (normalizedEnvelopeGate != null && projectQcGate != null
                && !normalizedEnvelopeGate.equals(normalizedQcGate)) {
            throw new IllegalArgumentException("projectQcGate must match project QC gate");
        }
        boolean releaseError = "error".equals(normalizedQcGate)
                || releaseStageFindings.stream()
                        .anyMatch(finding -> finding.severity() == FindingSeverity.ERROR);
        boolean releaseFail = !releaseError && "fail".equals(normalizedQcGate);
        StageStatus releaseStatus = releaseError ? StageStatus.ERROR
                : releaseFail ? StageStatus.FAIL : StageStatus.PASS;
        String releaseMessage = releaseError ? "release checks reported a blocking error"
                : releaseFail ? "project QC reported a policy failure" : null;
        stages.add(new StageResult(RELEASE_STAGE, releaseStatus, releaseMessage,
                releaseStageFindings, Map.of()));

        Set<String> required = new LinkedHashSet<>();
        Object rawRequired = projectQc.get("required_stages");
        if (rawRequired != null && !(rawRequired instanceof List<?>)) {
            throw new IllegalArgumentException("project QC required_stages must be an array");
        }
        if (rawRequired instanceof List<?> list) {
            list.forEach(value -> required.add(String.valueOf(value)));
        }
        required.add(RELEASE_STAGE);
        return GateResult.aggregate(policyVersion, fingerprint, new ArrayList<>(required), stages,
                failOn);
    }

    private static FindingSeverity failOn(Map<String, Object> projectQc) {
        Object details = projectQc.get("details");
        String value = details instanceof Map<?, ?> map ? string(map.get("fail_on"))
                : string(projectQc.get("fail_on"));
        if (value == null || "none".equalsIgnoreCase(value)) {
            // Release-specific errors remain blocking even when project QC itself has no threshold.
            return FindingSeverity.ERROR;
        }
        if ("warn".equalsIgnoreCase(value)) {
            return FindingSeverity.WARNING;
        }
        return FindingSeverity.fromJson(value);
    }

    private static List<Finding> findings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("findings must be an array");
        }
        List<Finding> findings = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("finding must be an object");
            }
            findings.add(toFinding(map));
        }
        return findings;
    }

    private static Finding toFinding(Map<?, ?> finding) {
        Map<String, Object> waiver = object(finding.get("waiver"));
        return new Finding(string(finding.get("id")), string(finding.get("source")),
                FindingSeverity.fromJson(string(finding.get("severity"))),
                string(finding.get("message")), absoluteOrNull(string(finding.get("focus_iri"))),
                string(finding.get("axiom")), string(finding.get("path")),
                string(finding.get("rule_id")), waiver.isEmpty() ? null : waiver,
                object(finding.get("details")));
    }

    private static boolean containsFinding(List<Finding> haystack, Finding needle) {
        for (Finding finding : haystack) {
            if (finding.id().equals(needle.id()) && finding.message().equals(needle.message())
                    && java.util.Objects.equals(finding.focusIri(), needle.focusIri())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String absoluteOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value).isAbsolute() ? value : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
