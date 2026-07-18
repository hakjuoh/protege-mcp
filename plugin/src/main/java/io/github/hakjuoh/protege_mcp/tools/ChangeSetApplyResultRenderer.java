package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Builds the released apply_changes payload from the verified-apply outcome. */
final class ChangeSetApplyResultRenderer {

    private ChangeSetApplyResultRenderer() {
    }

    static CallToolResult render(Map<String, Object> payload, String mode,
            Map<String, Object> preflight, ChangeSetRegressionAttributor.Attribution attribution,
            boolean prevented, boolean applied, ProjectPolicy policy, String errorCode) {
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("mode", mode);
        verify.put("regression", attribution.regression());
        verify.put("inconsistent", ChangeSetPreflightView.reasonerInconsistent(preflight));
        verify.put("newly_unsatisfiable", attribution.newlyUnsatisfiable());
        verify.put("rolled_back", false);
        verify.put("applied", applied);
        String reasonerStatus = ChangeSetPreflightView.reasonerStatus(preflight);
        verify.put("classification_started", "pass".equals(reasonerStatus)
                || "fail".equals(reasonerStatus) || "error".equals(reasonerStatus));
        verify.put("classification_completed", "pass".equals(reasonerStatus)
                || "fail".equals(reasonerStatus));
        if ("error".equals(reasonerStatus)) {
            verify.put("classification_failed", true);
        }
        String reasonerName = ChangeSetPreflightView.reasonerName(preflight);
        if (reasonerName != null) {
            verify.put("reasoner", reasonerName);
        }
        if (Boolean.TRUE.equals(attribution.wasInconsistent())) {
            verify.put("was_inconsistent", true);
        }
        if ("revision_conflict".equals(errorCode) || attribution.concurrentBaseline()) {
            verify.put("concurrent_change", true);
        }
        verify.put("verification_path", "change_set_preflight");
        verify.put("gate", preflight.get("gate"));
        if (attribution.baselineGate() != null) {
            verify.put("baseline_gate", attribution.baselineGate());
        }
        verify.put("preflight", preflight);
        if (prevented) verify.put("prevented_before_apply", true);
        if (errorCode != null) verify.put("error_code", errorCode);
        if (policy.digest() != null) verify.put("policy_digest", policy.digest());
        if (prevented && attribution.gateError()) {
            verify.put("note", "The isolated gate could not produce a verdict (gate=error), so "
                    + "verify=rollback prevented the batch before live mutation (fail-closed); no undo "
                    + "of unrelated GUI history was needed. Inspect verify.preflight.errors.");
        } else if (prevented) {
            verify.put("note", "The isolated change-set gate attributed a regression to this batch, "
                    + "so verify=rollback prevented it before live mutation; no undo of unrelated GUI "
                    + "history was needed." + (attribution.concurrentBaseline()
                            ? " (The workspace changed between the changed and baseline runs, so the "
                            + "verdict stays fail-closed and no specific findings are attributed.)"
                            : attribution.conservative()
                            ? " (The baseline could not fully prove pre-existing findings, so the "
                            + "verdict stays fail-closed.)" : ""));
        } else if ("read_only".equals(errorCode)) {
            verify.put("note", "Server is in read-only mode; writes are disabled (toggle in "
                    + "Protégé ▸ Preferences ▸ MCP). The isolated verdict was produced, but nothing "
                    + "was applied.");
        } else if (errorCode != null) {
            verify.put("note", "The isolated verdict was produced, but the live revision/policy "
                    + "changed before commit; nothing was applied (" + errorCode + ").");
        } else if (attribution.gateError()) {
            verify.put("note", "The isolated gate could not produce a verdict (gate=error), so nothing "
                    + "was attributed to this batch; verify=report kept it. Inspect "
                    + "verify.preflight.errors.");
        } else if (attribution.regression()) {
            verify.put("note", "The policy/change-set gate reported a regression; verify=report kept "
                    + "the batch. Inspect verify.preflight for governance/profile/import/QC details.");
        } else if (!"pass".equals(preflight.get("gate"))) {
            verify.put("note", "The gate reports findings the unchanged baseline already produced "
                    + "(baseline_gate=" + attribution.baselineGate() + "), so they are not attributed "
                    + "to this batch; the batch was committed. Inspect verify.preflight.");
        } else if (!applied) {
            verify.put("note", "The batch passed the same isolated gate used by preview_change_set "
                    + "and normalized to no-ops; nothing needed to land.");
        } else {
            verify.put("note", "The batch passed the same isolated gate used by preview_change_set "
                    + "and was committed as one live applyChanges broadcast.");
        }
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("verify", verify);
        return Tools.ok(out);
    }

    /**
     * A prevented or conflicted batch applied NOTHING: the released row/summary fields (applied,
     * removed, single_undo, new_entities) describe what actually landed, so the simulated
     * predictions must not survive into the response as claims.
     */
    static Map<String, Object> neutralizedPayload(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>(payload);
        if (out.get("operations") instanceof List<?> rows) {
            List<Map<String, Object>> neutral = new ArrayList<>();
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> row)) continue;
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : row.entrySet()) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                if (copy.containsKey("applied")) copy.put("applied", false);
                if (copy.containsKey("removed")) copy.put("removed", false);
                copy.remove("new_entities");
                neutral.add(copy);
            }
            out.put("operations", neutral);
        }
        if (out.get("summary") instanceof Map<?, ?> summary) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : summary.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            copy.put("added", 0);
            copy.put("removed", 0);
            copy.put("no_ops", 0);
            copy.put("single_undo", false);
            copy.put("new_entities", List.of());
            out.put("summary", copy);
        }
        return out;
    }
}
