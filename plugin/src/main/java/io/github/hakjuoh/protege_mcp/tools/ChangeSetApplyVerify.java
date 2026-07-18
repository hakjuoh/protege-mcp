package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Internal 0.6.1 migration of {@code apply_changes verify=} onto change-set preflight. */
final class ChangeSetApplyVerify {

    private ChangeSetApplyVerify() {
    }

    static CallToolResult apply(ToolContext ctx, String mode, int timeoutMs, String summary,
            List<Map<String, Object>> operations, boolean strict) {
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, summary);
        if (denied != null) return denied;

        RevisionTools.PolicyState state = RevisionTools.resolvePolicy(ctx, null);
        ProjectPolicy policy = state.policy();
        String lockDigest = RevisionTools.digestImportLock(policy);
        String preflightDigest = null;
        List<String> setupErrors = new ArrayList<>();
        if (state.error() != null) setupErrors.add(state.error());
        if (policy.loaded() && !policy.valid()) {
            policy.issues().stream().filter(issue -> "error".equals(issue.severity()))
                    .forEach(issue -> setupErrors.add(issue.code() + ": " + issue.message()));
        }
        if (setupErrors.isEmpty()) {
            try {
                preflightDigest = RevisionTools.preflightDigest(policy);
            } catch (RuntimeException e) {
                setupErrors.add(message(e));
            }
        }
        // When setup could not produce a digest (loaded-but-invalid policy or an asset-hash failure),
        // the commit hop must not demand one back: report mode is contracted to commit either way.
        final boolean digestPinned = setupErrors.isEmpty();
        final String pinnedPreflightDigest = preflightDigest;

        Captured captured = ctx.access().compute(mm -> {
            ModelRevision revision = ctx.revisions().current(mm, lockDigest, policy.digest()).revision();
            ChangePlanner.Plan plan = ChangePlanner.planForApply(mm, operations, strict);
            // Preserve the released apply_changes payload even when rollback-mode preflight prevents a
            // live apply: run the existing parser/renderer against the pinned revision without applying.
            Map<String, Object> payload = WriteTools.simulateBatchData(mm, operations, strict);
            return new Captured(revision, plan, payload, ChangeSetTools.reasonerSelection(mm));
        });

        // 0.4.x fail-closed contract: with no policy gate configured, rollback's only guard is the
        // reasoner verdict, so refuse outright rather than applying with zero satisfiability checking.
        if (ApplyVerify.MODE_ROLLBACK.equals(mode) && !policy.loaded()
                && captured.reasonerSelection == ChangeSetTools.ReasonerSelection.NONE) {
            return Tools.error("apply_changes verify=rollback needs a reasoner to guard the batch "
                    + "when no project policy is loaded, but none is selected. Select one "
                    + "(set_reasoner), load a project policy, or call with verify=none or "
                    + "verify=report.");
        }

        Map<String, Object> preflight;
        QcRunConfig config = null;
        QcSuiteExecution changedExecution = null;
        if (!setupErrors.isEmpty()) {
            preflight = errorPreflight(policy, setupErrors);
        } else {
            try {
                Map<String, Object> configArgs = new LinkedHashMap<>();
                configArgs.put("timeout_ms", timeoutMs);
                config = ChangeSetTools.preflightConfig(
                        state, configArgs, captured.reasonerSelection);
                changedExecution = QcSuiteTools.execute(
                        ctx, config, captured.plan.changes());
                int version = policy.loaded() && policy.effective().get("version") instanceof Number
                        ? ((Number) policy.effective().get("version")).intValue() : 0;
                preflight = QcSuiteTools.strictResult(changedExecution, config.requiredStages,
                        config.failOn, version, policy.digest(), policy.loaded());
                ProjectQcTools.enforceImportIntegrity(policy, changedExecution, preflight);
            } catch (RuntimeException e) {
                preflight = errorPreflight(policy, List.of("preflight_error: " + message(e)));
            }
        }

        // Attribution keeps the released meaning of `regression`: caused BY THIS BATCH. With no
        // policy, a gate=fail verdict is re-run against the unchanged baseline and findings the
        // baseline already produced do not become this batch's regression (a policy gate stays
        // result-state: its required stages are an absolute contract). A gate=error verdict is not a
        // regression at all — the gate could not be computed (invalid policy, asset-hash failure, or
        // an isolated classification timeout); report mode stays honest (regression=false) while
        // rollback still fails closed on the unverifiable gate below.
        ChangeSetRegressionAttributor.Attribution attribution;
        Object gate = preflight.get("gate");
        if ("pass".equals(gate)) {
            attribution = ChangeSetRegressionAttributor.Attribution.gateBased(false);
        } else if ("error".equals(gate)) {
            attribution = ChangeSetRegressionAttributor.Attribution.unverified();
        } else if (!"fail".equals(gate) || policy.loaded() || config == null) {
            attribution = ChangeSetRegressionAttributor.Attribution.gateBased(true);
        } else {
            attribution = ChangeSetRegressionAttributor.attributeAgainstBaseline(ctx, config,
                    preflight, changedExecution, captured.revision, lockDigest, policy);
        }

        if (ApplyVerify.MODE_ROLLBACK.equals(mode)
                && (attribution.regression() || attribution.gateError())) {
            return ChangeSetApplyResultRenderer.render(
                    ChangeSetApplyResultRenderer.neutralizedPayload(captured.simulatedPayload),
                    mode, preflight, attribution, true, false, policy, null);
        }

        ctx.writeLock().lock();
        try {
            ApplyResult applied;
            try {
                // The hop's fingerprints + asset hashing + broadcast legitimately outlive the default
                // 30 s wait on a large ontology — same workload class and bound as commit_change_set.
                applied = ctx.access().compute(mm -> applyOnModelThread(ctx, mm, captured,
                        operations, strict, policy, lockDigest, pinnedPreflightDigest, digestPinned),
                        ChangeSetTools.COMMIT_TIMEOUT_MS);
            } catch (McpAccessException e) {
                // The bounded wait can expire after the body started, and a running body cannot be
                // cancelled — the batch may still land. Never claim that nothing happened.
                throw new McpAccessException(e.getMessage() + " The verified apply body may still "
                        + "complete on the Protégé UI thread: call get_model_revision and compare "
                        + "it with the revision this call observed before retrying.", e);
            }
            if (!applied.committed) {
                return ChangeSetApplyResultRenderer.render(
                        ChangeSetApplyResultRenderer.neutralizedPayload(captured.simulatedPayload),
                        mode, preflight, attribution, false, false, policy, applied.errorCode);
            }
            return ChangeSetApplyResultRenderer.render(applied.payload, mode, preflight,
                    attribution, false, applied.applied, policy, null);
        } finally {
            ctx.writeLock().unlock();
        }
    }

    private static ApplyResult applyOnModelThread(ToolContext ctx, OWLModelManager mm,
            Captured captured, List<Map<String, Object>> operations, boolean strict,
            ProjectPolicy pinnedPolicy, String lockDigest, String preflightDigest,
            boolean digestPinned) {
        if (ctx.controller().isReadOnly()) return ApplyResult.error("read_only");
        ModelRevision current = ctx.revisions().current(mm, lockDigest, pinnedPolicy.digest()).revision();
        if (!captured.revision.equals(current)) return ApplyResult.error("revision_conflict");

        ProjectPolicy effective = ChangeSetTools.effectivePolicy(mm, null);
        String pinnedPath = pinnedPolicy.path() == null ? null : pinnedPolicy.path().toString();
        String effectivePath = effective.path() == null ? null : effective.path().toString();
        if (!Objects.equals(pinnedPath, effectivePath)
                || !Objects.equals(pinnedPolicy.digest(), effective.digest())
                || !Objects.equals(lockDigest, RevisionTools.digestImportLock(effective))) {
            return ApplyResult.error("policy_conflict");
        }
        if (digestPinned) {
            String effectiveDigest;
            try {
                effectiveDigest = RevisionTools.preflightDigest(effective);
            } catch (RuntimeException e) {
                return ApplyResult.error("policy_conflict");
            }
            if (!Objects.equals(preflightDigest, effectiveDigest)) {
                return ApplyResult.error("policy_conflict");
            }
        }

        // Re-plan at the pinned revision and require the exact normalized delta evaluated by QC.
        ChangePlanner.Plan currentPlan = ChangePlanner.planForApply(mm, operations, strict);
        if (!captured.plan.changes().equals(currentPlan.changes())) {
            return ApplyResult.error("normalized_change_conflict");
        }
        // Land EXACTLY that delta in one broadcast (one undo entry). Re-deriving live changes from
        // the operations (the old applyBatchData path) could add declaration side effects the gate
        // never evaluated — a net-zero cancellation batch would still land Declaration axioms.
        Map<String, Object> payload = WriteTools.simulateBatchData(mm, operations, strict);
        OWLOntology target = mm.getActiveOntology();
        List<OWLOntologyChange> bound = currentPlan.changes().stream()
                .map(change -> change.bind(target)).toList();
        if (!bound.isEmpty()) {
            mm.applyChanges(bound);
        }
        if (payload.get("summary") instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mutable = (Map<String, Object>) map;
            mutable.put("single_undo", !bound.isEmpty());
        }
        return new ApplyResult(true, !bound.isEmpty(), null, payload);
    }

    private static Map<String, Object> errorPreflight(ProjectPolicy policy, List<String> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate", "error");
        result.put("policy_loaded", policy.loaded());
        if (policy.digest() != null) result.put("policy_digest", policy.digest());
        result.put("required_stages", List.of());
        result.put("stages_ran", 0);
        result.put("stages_skipped", 0);
        result.put("stages", List.of());
        result.put("findings", errors.stream().map(error -> Map.of(
                "id", "preflight.configuration", "source", "policy", "severity", "error",
                "message", error, "details", Map.of())).toList());
        result.put("errors", errors);
        return result;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Captured(ModelRevision revision, ChangePlanner.Plan plan,
            Map<String, Object> simulatedPayload,
            ChangeSetTools.ReasonerSelection reasonerSelection) { }

    private record ApplyResult(boolean committed, boolean applied, String errorCode,
            Map<String, Object> payload) {
        static ApplyResult error(String code) {
            return new ApplyResult(false, false, code, Map.of());
        }
    }
}
