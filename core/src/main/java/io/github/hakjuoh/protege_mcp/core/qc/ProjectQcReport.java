package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;

/** Adapter-neutral strict project-QC result and its stable public JSON projection. */
public record ProjectQcReport(
        GateStatus gate,
        ProjectQcRequest request,
        List<ProjectQcStageReport> stages,
        List<Finding> findings,
        int stagesRan,
        int stagesSkipped,
        Object rdfDatasetFingerprint,
        Map<String, Object> rdfDatasetIdentity,
        List<String> missingRequiredStages) {

    public ProjectQcReport {
        if (gate == null || request == null || stages == null || findings == null
                || missingRequiredStages == null) {
            throw new IllegalArgumentException("project QC report fields must not be null");
        }
        stages = Collections.unmodifiableList(new ArrayList<>(stages));
        findings = Collections.unmodifiableList(new ArrayList<>(findings));
        rdfDatasetIdentity = rdfDatasetIdentity == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(rdfDatasetIdentity));
        missingRequiredStages = Collections.unmodifiableList(
                new ArrayList<>(missingRequiredStages));
    }

    /** Preserve the released project-QC envelope while sharing its implementation across adapters. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate", gate.json());
        result.put("policy_loaded", request.policyLoaded());
        if (request.policyVersion() > 0) {
            result.put("policy_version", request.policyVersion());
        }
        if (request.policyDigest() != null) {
            result.put("policy_digest", request.policyDigest());
        }
        result.put("semantic_fingerprint", request.fingerprint().semanticFingerprint());
        if (rdfDatasetIdentity != null) {
            result.put("rdf_dataset_fingerprint", rdfDatasetFingerprint);
            result.put("rdf_dataset_identity", new LinkedHashMap<>(rdfDatasetIdentity));
        }
        result.put("fingerprint_stability", request.fingerprint().stability());
        result.put("release_stable", request.fingerprint().releaseStable());
        result.put("fingerprint_warnings", request.fingerprint().warnings());
        result.put("reasoner", request.selectedReasoner());
        result.put("required_stages", new ArrayList<>(request.requiredStages()));
        result.put("stages_ran", stagesRan);
        result.put("stages_skipped", stagesSkipped);
        result.put("fail_on", request.failOn());
        result.put("stages", new ArrayList<>(
                stages.stream().map(ProjectQcStageReport::toMap).toList()));
        result.put("findings", new ArrayList<>(
                findings.stream().map(ProjectQcReport::findingMap).toList()));
        result.put("artifacts", new ArrayList<>());
        result.put("snapshot_consistent", request.snapshotConsistent());
        Map<String, Object> validationSnapshot = new LinkedHashMap<>();
        validationSnapshot.put("mode", request.snapshotMode());
        validationSnapshot.put("same_snapshot", request.sameValidationSnapshot());
        validationSnapshot.put("semantic_fingerprint", request.fingerprint().semanticFingerprint());
        validationSnapshot.put("closure_fingerprint", request.closureFingerprint());
        validationSnapshot.put("stages", request.snapshotStages());
        result.put("validation_snapshot", validationSnapshot);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fail_on", "warn".equals(request.failOn()) ? "warning" : request.failOn());
        if (!missingRequiredStages.isEmpty()) {
            details.put("missing_required_stages", new ArrayList<>(missingRequiredStages));
        }
        result.put("details", details);
        return result;
    }

    static Map<String, Object> findingMap(Finding finding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", finding.id());
        map.put("source", finding.source());
        map.put("severity", finding.severity().json());
        map.put("message", finding.message());
        map.put("focus_iri", finding.focusIri());
        map.put("axiom", finding.axiom());
        map.put("path", finding.path());
        map.put("rule_id", finding.ruleId());
        map.put("waiver", finding.waiver() == null ? null
                : new LinkedHashMap<>(finding.waiver()));
        map.put("details", new LinkedHashMap<>(finding.details()));
        return map;
    }
}
