package io.github.hakjuoh.protege_mcp.tools;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;

/** Data-only result of one shared-snapshot QC suite execution. */
final class QcSuiteExecution {
    final List<QcStageResult> results;
    final OntologyFingerprint fingerprint;
    final boolean snapshotConsistent;
    final String selectedReasoner;
    final String preconditionError;
    final String snapshotMode;
    final List<String> snapshotStages;
    final boolean sameValidationSnapshot;
    final String closureFingerprint;
    /** Unresolved imports observed in the SAME model-thread hop as the snapshot (project mode). */
    final List<Map<String, Object>> missingImports;
    /** Full already-loaded graph captured in the snapshot hop (project mode only). */
    final ImportTools.ImportReport importReport;

    QcSuiteExecution(List<QcStageResult> results, OntologyFingerprint fingerprint,
            boolean snapshotConsistent, String selectedReasoner, String preconditionError) {
        this(results, fingerprint, snapshotConsistent, selectedReasoner, preconditionError,
                "isolated", results.stream()
                        .filter(result -> result.ran && !"reasoner".equals(result.stage))
                        .map(result -> result.stage).toList(), true,
                fingerprint.semanticFingerprint(), Collections.emptyList());
    }

    QcSuiteExecution(List<QcStageResult> results, OntologyFingerprint fingerprint,
            boolean snapshotConsistent, String selectedReasoner, String preconditionError,
            String snapshotMode, List<String> snapshotStages, boolean sameValidationSnapshot,
            String closureFingerprint, List<Map<String, Object>> missingImports) {
        this(results, fingerprint, snapshotConsistent, selectedReasoner, preconditionError,
                snapshotMode, snapshotStages, sameValidationSnapshot, closureFingerprint,
                missingImports, null);
    }

    QcSuiteExecution(List<QcStageResult> results, OntologyFingerprint fingerprint,
            boolean snapshotConsistent, String selectedReasoner, String preconditionError,
            String snapshotMode, List<String> snapshotStages, boolean sameValidationSnapshot,
            String closureFingerprint, List<Map<String, Object>> missingImports,
            ImportTools.ImportReport importReport) {
        this.results = List.copyOf(results);
        this.fingerprint = fingerprint;
        this.snapshotConsistent = snapshotConsistent;
        this.selectedReasoner = selectedReasoner;
        this.preconditionError = preconditionError;
        this.snapshotMode = snapshotMode;
        this.snapshotStages = List.copyOf(snapshotStages);
        this.sameValidationSnapshot = sameValidationSnapshot;
        this.closureFingerprint = closureFingerprint;
        this.missingImports = List.copyOf(missingImports);
        this.importReport = importReport;
    }
}
