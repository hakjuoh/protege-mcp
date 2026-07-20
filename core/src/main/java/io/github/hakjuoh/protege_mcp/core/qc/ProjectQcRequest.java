package io.github.hakjuoh.protege_mcp.core.qc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;

/** Complete immutable context needed to aggregate one captured project-QC execution. */
public record ProjectQcRequest(
        boolean policyLoaded,
        int policyVersion,
        String policyDigest,
        List<String> requiredStages,
        String failOn,
        OntologyFingerprint fingerprint,
        String selectedReasoner,
        boolean snapshotConsistent,
        String preconditionError,
        String snapshotMode,
        List<String> snapshotStages,
        boolean sameValidationSnapshot,
        String closureFingerprint) {

    public ProjectQcRequest {
        if (policyVersion < 0 || (policyLoaded && policyVersion < 1)) {
            throw new IllegalArgumentException(
                    "policy_version must be positive when a policy is loaded");
        }
        if (policyDigest != null && policyDigest.isBlank()) {
            throw new IllegalArgumentException("policy_digest must not be blank");
        }
        if (requiredStages == null) {
            throw new IllegalArgumentException("required_stages must not be null");
        }
        List<String> requiredCopy = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String stage : requiredStages) {
            if (stage == null || stage.isBlank()) {
                throw new IllegalArgumentException("required_stages entries must not be blank");
            }
            if (!seen.add(stage)) {
                throw new IllegalArgumentException("duplicate required stage: " + stage);
            }
            requiredCopy.add(stage);
        }
        requiredStages = Collections.unmodifiableList(requiredCopy);
        failOn = normalizeThreshold(failOn);
        if (fingerprint == null) {
            throw new IllegalArgumentException("fingerprint must not be null");
        }
        snapshotMode = snapshotMode == null || snapshotMode.isBlank() ? "isolated" : snapshotMode;
        snapshotStages = snapshotStages == null ? List.of() : List.copyOf(snapshotStages);
        closureFingerprint = closureFingerprint == null
                ? fingerprint.semanticFingerprint() : closureFingerprint;
    }

    private static String normalizeThreshold(String value) {
        if (value == null || value.isBlank()) {
            return "error";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> "none";
            case "info" -> "info";
            case "warn", "warning" -> "warn";
            case "error" -> "error";
            default -> throw new IllegalArgumentException(
                    "fail_on must be none, info, warn, or error");
        };
    }
}
