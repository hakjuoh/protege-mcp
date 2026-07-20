package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

class ProjectQcServiceTest {

    private static final String FP = "sha256:" + "a".repeat(64);

    @Test
    void preservesTheReleasedEnvelopeAndThresholdSemantics() {
        ProjectQcReport report = ProjectQcService.aggregate(request(List.of("structural"), "error"),
                List.of(new QcStageExecution("structural", QcStageVerdict.WARNING, null,
                        Map.of("warnings", 2))));

        assertEquals(GateStatus.PASS, report.gate());
        Map<String, Object> json = report.toMap();
        assertEquals("pass", json.get("gate"));
        assertEquals("error", json.get("fail_on"));
        assertEquals(1, json.get("stages_ran"));
        assertEquals(0, json.get("stages_skipped"));
        assertEquals("HermiT 1.3.8.431", json.get("reasoner"));
        assertEquals(FP, json.get("semantic_fingerprint"));

        Map<String, Object> stage = stage(json, 0);
        assertEquals(true, stage.get("ran"));
        assertEquals("warn", stage.get("verdict"));
        assertEquals(true, stage.get("required"));
        assertEquals("pass", stage.get("status"));
        assertNull(stage.get("message"));
        assertEquals(Map.of("warnings", 2), stage.get("findings_summary"));
        assertEquals(1, ((List<?>) json.get("findings")).size(),
                "a below-threshold warning remains visible");
    }

    @Test
    void completedOptionalStageStillUsesTheEstablishedWorstRanStageRule() {
        ProjectQcReport report = ProjectQcService.aggregate(request(List.of("profile"), "warn"),
                List.of(new QcStageExecution("profile", QcStageVerdict.PASS, null, Map.of()),
                        new QcStageExecution("cqs", QcStageVerdict.WARNING, null,
                                Map.of("failed", 1))));

        assertEquals(GateStatus.FAIL, report.gate());
        assertFalse(report.stages().get(1).required());
        assertEquals(StageStatus.FAIL, report.stages().get(1).status());
    }

    @Test
    void missingSkippedAndErroredStagesFailClosedWithStableCounts() {
        ProjectQcReport report = ProjectQcService.aggregate(
                request(List.of("reasoner", "shacl", "structural"), "error"),
                List.of(QcStageExecution.skipped("reasoner", "no reasoner"),
                        QcStageExecution.error("shacl", "bad shapes", Map.of("error", "bad shapes"))));

        assertEquals(GateStatus.ERROR, report.gate());
        assertEquals(1, report.stagesRan());
        assertEquals(2, report.stagesSkipped());
        assertEquals(List.of("reasoner", "shacl", "structural"), report.missingRequiredStages());
        assertEquals(3, report.stages().size());
        assertEquals(StageStatus.ERROR, report.stages().get(0).status());
        assertEquals(StageStatus.ERROR, report.stages().get(1).status());
        assertEquals(StageStatus.ERROR, report.stages().get(2).status());
        Map<String, Object> missing = stage(report.toMap(), 2);
        assertEquals("required stage was not scheduled", missing.get("reason"));
        assertEquals(List.of("stage", "required", "ran", "status", "reason", "message",
                "findings", "details"), new ArrayList<>(missing.keySet()),
                "synthetic rows participate in deterministic contract digests");
    }

    @Test
    void snapshotAndPreconditionErrorsOutrankASeparatePolicyFailure() {
        ProjectQcRequest base = request(List.of("profile"), "warn");
        ProjectQcRequest stale = new ProjectQcRequest(true, 1, "digest", base.requiredStages(),
                base.failOn(), base.fingerprint(), base.selectedReasoner(), false,
                "policy changed", base.snapshotMode(), base.snapshotStages(),
                base.sameValidationSnapshot(), base.closureFingerprint());

        ProjectQcReport report = ProjectQcService.aggregate(stale,
                List.of(new QcStageExecution("profile", QcStageVerdict.WARNING, null, Map.of())));

        assertEquals(GateStatus.ERROR, report.gate());
        assertEquals(List.of("snapshot.changed", "precondition.changed", "profile.finding"),
                report.findings().stream().map(f -> f.id()).toList());
    }

    @Test
    void sessionOnlyFingerprintIsVisibleWithoutChangingAnOtherwisePassingGate() {
        OntologyFingerprint session = new OntologyFingerprint(2, FP, FP, "session_only", false,
                List.of("anonymous individuals use session-local identifiers"));
        ProjectQcRequest request = new ProjectQcRequest(true, 1, "digest", List.of("profile"),
                "error", session, null, true, null, "isolated", List.of("profile"), true, FP);

        ProjectQcReport report = ProjectQcService.aggregate(request,
                List.of(new QcStageExecution("profile", QcStageVerdict.PASS, null, Map.of())));

        assertEquals(GateStatus.PASS, report.gate());
        assertEquals("fingerprint.session_only", report.findings().get(0).id());
        assertEquals("session_only", report.findings().get(0).details().get("stability"));
    }

    @Test
    void interoperabilityIdentityIsProjectedOnlyAfterSuccessfulExecution() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rdf_dataset_fingerprint", FP);
        details.put("scope", "asserted-root");
        ProjectQcReport report = ProjectQcService.aggregate(
                request(List.of("interoperability"), "error"),
                List.of(new QcStageExecution("interoperability", QcStageVerdict.PASS, null,
                        details)));

        assertEquals(FP, report.toMap().get("rdf_dataset_fingerprint"));
        assertEquals(details, report.toMap().get("rdf_dataset_identity"));

        ProjectQcReport failed = ProjectQcService.aggregate(
                request(List.of("interoperability"), "error"),
                List.of(QcStageExecution.error("interoperability", "canonicalization failed",
                        details)));
        assertFalse(failed.toMap().containsKey("rdf_dataset_fingerprint"));
    }

    @Test
    void rejectsDuplicateStagesAndDefensivelyCopiesInputs() {
        List<String> required = new ArrayList<>(List.of("profile"));
        ProjectQcRequest request = request(required, "warning");
        required.add("later");
        assertEquals(List.of("profile"), request.requiredStages());
        assertEquals("warn", request.failOn());
        assertThrows(UnsupportedOperationException.class,
                () -> request.requiredStages().add("later"));

        QcStageExecution pass = new QcStageExecution("profile", QcStageVerdict.PASS, null, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> ProjectQcService.aggregate(request, List.of(pass, pass)));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectQcRequest(true, 0, null, List.of(), "error", stable(), null,
                        true, null, "isolated", List.of(), true, FP));
    }

    @Test
    @SuppressWarnings("unchecked")
    void immutableCoreReportProjectsFreshLegacyMutableCollections() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("rdf_dataset_fingerprint", FP);
        ProjectQcReport report = ProjectQcService.aggregate(
                request(List.of("interoperability"), "error"),
                List.of(new QcStageExecution("interoperability", QcStageVerdict.WARNING, null,
                        identity)));

        Map<String, Object> projected = report.toMap();
        ((List<String>) projected.get("required_stages")).add("postprocessor");
        ((List<Map<String, Object>>) projected.get("findings")).add(new LinkedHashMap<>());
        ((List<Map<String, Object>>) stage(projected, 0).get("findings")).add(new LinkedHashMap<>());
        ((Map<String, Object>) stage(projected, 0).get("details")).put("postprocessed", true);
        ((Map<String, Object>) projected.get("rdf_dataset_identity")).put("postprocessed", true);

        assertEquals(List.of("interoperability"), report.request().requiredStages());
        assertFalse(report.rdfDatasetIdentity().containsKey("postprocessed"));
        assertFalse(report.stages().get(0).execution().details().containsKey("postprocessed"));
    }

    private static ProjectQcRequest request(List<String> required, String failOn) {
        return new ProjectQcRequest(true, 1, "digest", required, failOn, stable(),
                "HermiT 1.3.8.431", true, null, "isolated", List.of("structural"), true, FP);
    }

    private static OntologyFingerprint stable() {
        return new OntologyFingerprint(2, FP, FP, "cross_restart", true, List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stage(Map<String, Object> report, int index) {
        return (Map<String, Object>) ((List<?>) report.get("stages")).get(index);
    }
}
