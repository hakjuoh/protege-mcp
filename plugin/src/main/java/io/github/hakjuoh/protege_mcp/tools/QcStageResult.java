package io.github.hakjuoh.protege_mcp.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One QC stage's outcome plus its non-serialized attribution evidence. */
final class QcStageResult {
    final String stage;
    final boolean ran;
    final String verdict;              // pass | warn | fail (only meaningful when ran)
    final Map<String, Object> summary; // nullable
    final String reason;               // why it was skipped (when !ran)
    final boolean executionError;
    // Attribution side channel (never serialized): the COMPLETE, uncapped set of unsatisfiable
    // class IRIs behind the reasoner stage's display-capped `unsatisfiable_classes.items`. The
    // verified-apply baseline comparison must diff full sets, not the 25-item public window.
    Set<String> attributionUnsatIris;
    // Attribution side channel (never serialized): the COMPLETE set of gating-finding identities
    // for a non-reasoner stage (profile/structural). Baseline attribution set-diffs these so a
    // batch that swaps a fresh violation in while removing others (total count DOWN) is still
    // caught, and a pure removal (subset) is not misattributed as a regression.
    Set<String> attributionIdentities;

    QcStageResult(String stage, boolean ran, String verdict, Map<String, Object> summary,
            String reason) {
        this(stage, ran, verdict, summary, reason, false);
    }

    private QcStageResult(String stage, boolean ran, String verdict, Map<String, Object> summary,
            String reason, boolean executionError) {
        this.stage = stage;
        this.ran = ran;
        this.verdict = verdict;
        this.summary = summary;
        this.reason = reason;
        this.executionError = executionError;
    }

    static QcStageResult skipped(String stage, String reason) {
        return new QcStageResult(stage, false, null, null, reason);
    }

    static QcStageResult errored(String stage, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("error", reason);
        return new QcStageResult(stage, true, QcSuiteTools.FAIL, summary, reason, true);
    }

    /** Uncapped unsatisfiable-class IRIs for attribution; null unless the reasoner ran consistent. */
    Set<String> attributionUnsatIris() {
        return attributionUnsatIris;
    }

    /** Complete gating-finding identities for a non-reasoner stage; null when not instrumented. */
    Set<String> attributionIdentities() {
        return attributionIdentities;
    }

    Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage);
        result.put("ran", ran);
        if (ran) {
            result.put("verdict", verdict);
            if (executionError) {
                result.put("error", true);
            }
            if (summary != null) {
                result.put("findings_summary", summary);
            }
        } else {
            result.put("reason", reason);
        }
        return result;
    }
}
