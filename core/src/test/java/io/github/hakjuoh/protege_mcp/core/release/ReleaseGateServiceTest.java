package io.github.hakjuoh.protege_mcp.core.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

class ReleaseGateServiceTest {

    private static final String FP = "sha256:" + "a".repeat(64);

    @Test
    void reproducesPassFailAndErrorPrecedence() {
        assertEquals(GateStatus.PASS, ReleaseGateService.aggregate(
                qc("pass", List.of(stage("structural", "pass", List.of())),
                        List.of("structural"), "error", List.of()),
                List.of(), "pass").gate());

        assertEquals(GateStatus.FAIL, ReleaseGateService.aggregate(
                qc("fail", List.of(stage("structural", "fail", List.of(error("structural")))),
                        List.of("structural"), "error", List.of(error("structural"))),
                List.of(), "fail").gate());

        GateResult error = ReleaseGateService.aggregate(
                qc("pass", List.of(stage("structural", "pass", List.of())),
                        List.of("structural"), "error", List.of()),
                List.of(finding("release.version_iri_missing", FindingSeverity.ERROR)), "pass");
        assertEquals(GateStatus.ERROR, error.gate());
        assertEquals(StageStatus.ERROR, error.stages().get(1).status());
    }

    @Test
    void preservesAnOptionalStageQcFailure() {
        // Project QC deliberately lets any completed stage reaching fail_on fail the legacy gate,
        // even if that stage is not in required_stages. Losing qc.gate here used to turn this case
        // back into PASS when GateResult considered only required stages.
        Map<String, Object> optional = stage("cqs", "fail", List.of(error("cqs")));
        GateResult result = ReleaseGateService.aggregate(
                qc("fail", List.of(stage("structural", "pass", List.of()), optional),
                        List.of("structural"), "error", List.of(error("cqs"))),
                List.of(), "fail");

        assertEquals(GateStatus.FAIL, result.gate());
        assertEquals(StageStatus.FAIL, result.stages().get(2).status(),
                "the required release stage carries the legacy QC failure into the common contract");
    }

    @Test
    void foldsOrphanFindingsExactlyOnceIntoTheReleaseStage() {
        Map<String, Object> staged = error("profile");
        Map<String, Object> orphan = error("snapshot.changed");
        GateResult result = ReleaseGateService.aggregate(
                qc("error", List.of(stage("profile", "error", List.of(staged))),
                        List.of("profile"), "error", List.of(staged, orphan)),
                List.of(), "error");

        assertEquals(List.of("profile.finding", "snapshot.changed.finding"),
                result.findings().stream().map(Finding::id).toList());
        assertEquals(1, result.stages().get(1).findings().size());
    }

    @Test
    void releaseWarningsRespectTheQcThreshold() {
        GateResult result = ReleaseGateService.aggregate(
                qc("pass", List.of(stage("profile", "pass", List.of())),
                        List.of("profile"), "warn", List.of()),
                List.of(finding("release.lossy_format", FindingSeverity.WARNING)), "pass");

        assertEquals(GateStatus.FAIL, result.gate());
    }

    @Test
    void rejectsMalformedLegacyEnvelopesAndDuplicateStages() {
        Map<String, Object> malformed = qc("pass", List.of(), List.of(), "error", List.of());
        malformed.put("stages", Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseGateService.aggregate(malformed, List.of(), "pass"));

        Map<String, Object> duplicate = qc("pass",
                List.of(stage("profile", "pass", List.of()),
                        stage("profile", "pass", List.of())),
                List.of("profile"), "error", List.of());
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseGateService.aggregate(duplicate, List.of(), "pass"));
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseGateService.aggregate(qc("pass", List.of(), List.of(), "error", List.of()),
                        List.of(), "unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseGateService.aggregate(qc("error", List.of(), List.of(), "error", List.of()),
                        List.of(), "pass"));
    }

    private static Map<String, Object> qc(String gate, List<Map<String, Object>> stages,
            List<String> required, String failOn, List<Map<String, Object>> findings) {
        Map<String, Object> qc = new LinkedHashMap<>();
        qc.put("gate", gate);
        qc.put("policy_version", 1);
        qc.put("semantic_fingerprint", FP);
        qc.put("required_stages", new ArrayList<>(required));
        qc.put("stages", new ArrayList<>(stages));
        qc.put("findings", new ArrayList<>(findings));
        qc.put("details", Map.of("fail_on", failOn));
        return qc;
    }

    private static Map<String, Object> stage(String name, String status,
            List<Map<String, Object>> findings) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage", name);
        stage.put("status", status);
        stage.put("findings", findings);
        stage.put("details", Map.of());
        return stage;
    }

    private static Map<String, Object> error(String source) {
        return findingMap(source + ".finding", source, "error", source + " failed");
    }

    private static Finding finding(String id, FindingSeverity severity) {
        return new Finding(id, "release", severity, id, null, null, null, id, null, Map.of());
    }

    private static Map<String, Object> findingMap(String id, String source, String severity,
            String message) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", id);
        finding.put("source", source);
        finding.put("severity", severity);
        finding.put("message", message);
        finding.put("rule_id", id);
        finding.put("details", Map.of());
        return finding;
    }
}
