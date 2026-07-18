package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        QcSuiteTools.RunConfig config = null;
        QcSuiteTools.SuiteExecution changedExecution = null;
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
        Attribution attribution;
        Object gate = preflight.get("gate");
        if ("pass".equals(gate)) {
            attribution = Attribution.gateBased(false);
        } else if ("error".equals(gate)) {
            attribution = Attribution.unverified();
        } else if (!"fail".equals(gate) || policy.loaded() || config == null) {
            attribution = Attribution.gateBased(true);
        } else {
            attribution = attributeAgainstBaseline(ctx, config, preflight, changedExecution,
                    captured.revision, lockDigest, policy);
        }

        if (ApplyVerify.MODE_ROLLBACK.equals(mode) && (attribution.regression || attribution.gateError)) {
            return render(neutralizedPayload(captured.simulatedPayload), mode, preflight,
                    attribution, true, false, policy, null);
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
                return render(neutralizedPayload(captured.simulatedPayload), mode, preflight,
                        attribution, false, false, policy, applied.errorCode);
            }
            return render(applied.payload, mode, preflight, attribution,
                    false, applied.applied, policy, null);
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

    /** Re-run the same no-policy gate on the UNCHANGED baseline and subtract what it already fails. */
    private static Attribution attributeAgainstBaseline(ToolContext ctx,
            QcSuiteTools.RunConfig config, Map<String, Object> preflight,
            QcSuiteTools.SuiteExecution changedExecution, ModelRevision changedRevision,
            String lockDigest, ProjectPolicy policy) {
        Map<String, Object> baseline;
        QcSuiteTools.SuiteExecution baselineExecution;
        ModelRevision preBaseline;
        ModelRevision postBaseline;
        try {
            // The baseline re-run is a SECOND live snapshot taken after the changed run. Bracket it with
            // revision reads so a concurrent GUI edit that lands between the two runs (or during the
            // baseline run) cannot silently flip attribution and name innocent pre-existing findings as
            // this batch's: a moved revision drops to a fail-closed, unnamed, concurrent-flagged verdict.
            preBaseline = ctx.access().compute(mm ->
                    ctx.revisions().current(mm, lockDigest, policy.digest()).revision());
            baselineExecution = QcSuiteTools.execute(ctx, config);
            baseline = QcSuiteTools.strictResult(baselineExecution, config.requiredStages, config.failOn,
                    0, null, false);
            postBaseline = ctx.access().compute(mm ->
                    ctx.revisions().current(mm, lockDigest, policy.digest()).revision());
        } catch (RuntimeException e) {
            // The pre-existing state cannot be proven — keep the fail-closed gate verdict.
            return new Attribution(true, List.of(), null, null, true, false, false);
        }
        if (!changedRevision.equals(preBaseline) || !preBaseline.equals(postBaseline)) {
            // The workspace moved between (or during) the changed and baseline runs: the two are not one
            // revision, so no finding can be honestly attributed to this batch. Fail closed, name nothing.
            return new Attribution(true, List.of(), null,
                    String.valueOf(baseline.get("gate")), true, false, true);
        }
        String baselineGate = String.valueOf(baseline.get("gate"));
        Map<String, Object> changedReasoner = stageDetails(preflight, "reasoner");
        Map<String, Object> baselineReasoner = stageDetails(baseline, "reasoner");
        Boolean wasInconsistent = baselineReasoner == null || baselineReasoner.get("consistent") == null
                ? null : Boolean.FALSE.equals(baselineReasoner.get("consistent"));
        boolean regression = false;
        boolean conservative = false;
        List<Map<String, Object>> newlyUnsatisfiable = List.of();
        if (changedReasoner != null && Boolean.FALSE.equals(changedReasoner.get("consistent"))) {
            regression = !Boolean.TRUE.equals(wasInconsistent);
        } else if (changedReasoner != null && Boolean.TRUE.equals(wasInconsistent)) {
            // The batch made an INCONSISTENT baseline consistent. Under an inconsistent ontology every
            // class is unsatisfiable, so every class still unsatisfiable afterwards was unsatisfiable
            // before too — none is a fresh regression this batch introduced. A consistency repair that
            // leaves a legacy unsat class is a strict improvement, not something rollback should prevent.
            regression = false;
        } else if (changedReasoner != null) {
            int changedCount = intValue(changedReasoner.get("unsatisfiable_count"));
            int baselineCount = baselineReasoner == null ? 0
                    : intValue(baselineReasoner.get("unsatisfiable_count"));
            List<Map<String, Object>> changedItems = unsatItems(changedReasoner);
            // Prefer the COMPLETE, uncapped IRI sets carried out-of-band by the reasoner stage. The
            // public unsatisfiable_classes.items list is capped at config.limit (25), so diffing it
            // would falsely flag pre-existing classes beyond the window as this batch's regression and
            // — worse — force a conservative regression on EVERY batch once >25 classes are unsat.
            Set<String> fullChanged = QcSuiteTools.reasonerUnsatIris(changedExecution);
            Set<String> fullBaseline = QcSuiteTools.reasonerUnsatIris(baselineExecution);
            if (fullChanged != null && fullBaseline != null) {
                Set<String> freshIris = new LinkedHashSet<>(fullChanged);
                freshIris.removeAll(fullBaseline);
                newlyUnsatisfiable = displayFresh(changedItems, freshIris, config);
                if (!freshIris.isEmpty() || changedCount > baselineCount) {
                    regression = true;
                }
            } else if (fullChanged != null) {
                // The baseline unsatisfiable set is unknown (its reasoner stage did not run consistently
                // and the baseline is not inconsistent — that case is handled above). We cannot prove the
                // changed-run unsat classes pre-existed, so fail closed conservatively WITHOUT naming any
                // class as this batch's (an unproven attribution must not accuse an innocent class).
                if (changedCount > 0) {
                    regression = true;
                    conservative = true;
                }
            } else {
                // Fallback only if the full sets are unavailable: the capped-window comparison with a
                // conservative truncation guard.
                Set<String> baselineIris = new LinkedHashSet<>();
                if (baselineReasoner != null) {
                    for (Map<String, Object> item : unsatItems(baselineReasoner)) {
                        Object iri = item.get("iri");
                        if (iri != null) baselineIris.add(iri.toString());
                    }
                }
                List<Map<String, Object>> fresh = new ArrayList<>();
                for (Map<String, Object> item : changedItems) {
                    Object iri = item.get("iri");
                    if (iri == null || !baselineIris.contains(iri.toString())) {
                        fresh.add(item);
                    }
                }
                newlyUnsatisfiable = List.copyOf(fresh);
                boolean truncated = changedItems.size() < changedCount
                        || (baselineReasoner != null
                                && unsatItems(baselineReasoner).size() < baselineCount);
                if (!fresh.isEmpty() || changedCount > baselineCount) {
                    regression = true;
                } else if (truncated && changedCount > 0) {
                    // A truncated sample cannot prove the failure pre-existed — stay fail closed.
                    regression = true;
                    conservative = true;
                }
            }
        }
        if (!regression && nonReasonerRegression(preflight, baseline, changedExecution,
                baselineExecution)) {
            regression = true;
        }
        return new Attribution(regression, newlyUnsatisfiable, wasInconsistent, baselineGate,
                conservative, false, false);
    }

    /**
     * A display-capped list naming the genuinely-fresh unsatisfiable classes. It iterates the COMPLETE
     * fresh IRI set (not just the changed run's 25-item display window), so a fresh class that sorts
     * past the window is still named; the richer {@code changedItems} entry (with its label) is used
     * when present, otherwise a bare {@code {iri}} entry.
     */
    private static List<Map<String, Object>> displayFresh(List<Map<String, Object>> changedItems,
            Set<String> freshIris, QcSuiteTools.RunConfig config) {
        Map<String, Map<String, Object>> rich = new LinkedHashMap<>();
        for (Map<String, Object> item : changedItems) {
            Object iri = item.get("iri");
            if (iri != null) {
                rich.put(iri.toString(), item);
            }
        }
        List<String> sorted = new ArrayList<>(freshIris);
        sorted.sort(java.util.Comparator.naturalOrder());
        int cap = config == null || config.limit <= 0 ? sorted.size() : config.limit;
        List<Map<String, Object>> out = new ArrayList<>();
        for (String iri : sorted) {
            if (out.size() >= cap) {
                break;
            }
            Map<String, Object> item = rich.get(iri);
            out.add(item != null ? item : Map.of("iri", iri));
        }
        return List.copyOf(out);
    }

    /**
     * Attribute the non-reasoner stages by comparing per-check counts and complete finding identities,
     * not the coarse per-stage finding key or the capped public examples. strictResult mints one finding
     * per failing stage with a generic message, so both an additional violation and a same-count offender
     * replacement must be detected against a baseline that already failed that stage.
     */
    private static boolean nonReasonerRegression(Map<String, Object> preflight,
            Map<String, Object> baseline, QcSuiteTools.SuiteExecution changedExecution,
            QcSuiteTools.SuiteExecution baselineExecution) {
        Object stages = preflight.get("stages");
        if (!(stages instanceof List<?>)) return false;
        for (Object value : (List<?>) stages) {
            if (!(value instanceof Map<?, ?> row) || "reasoner".equals(row.get("stage"))) continue;
            String stage = row.get("stage") == null ? null : row.get("stage").toString();
            if (stage == null) continue;

            // Preferred SOUND path: diff the COMPLETE gating-finding identity sets the stage recorded
            // out of band. A fresh identity present in the changed run but not the baseline is this
            // batch's regression whatever the TOTAL count did (so a batch that removes two standing
            // findings while adding one different finding — count DOWN — is still caught); a pure
            // removal (changed ⊆ baseline) is an improvement, never misattributed as a regression.
            Set<String> changedIds = QcSuiteTools.stageAttributionIdentities(changedExecution, stage);
            Set<String> baselineIds = QcSuiteTools.stageAttributionIdentities(baselineExecution, stage);
            if (changedIds != null && baselineIds != null) {
                for (String id : changedIds) {
                    if (!baselineIds.contains(id)) {
                        return true;
                    }
                }
                continue;   // this stage is proven clear — do not fall back to the coarse heuristic
            }

            // Fallback for a stage without recorded identities: the per-check / checks-less count and
            // digest heuristic. It catches a fresh check, a higher count, and a same-count offender
            // swap, but cannot prove a subset when the count drops, so it is used only when the sound
            // identity sets are unavailable.
            Map<String, Object> changed = stageDetails(preflight, stage);
            Map<String, Object> base = stageDetails(baseline, stage);
            Map<String, CheckIdentity> changedChecks = gatingChecks(changed);
            Map<String, CheckIdentity> baseChecks = gatingChecks(base);
            if (!changedChecks.isEmpty() || !baseChecks.isEmpty()) {
                for (Map.Entry<String, CheckIdentity> entry : changedChecks.entrySet()) {
                    CheckIdentity previous = baseChecks.get(entry.getKey());
                    if (previous == null || entry.getValue().count > previous.count
                            || (entry.getValue().count == previous.count
                                    && entry.getValue().count > 0
                                    && entry.getValue().digest != null
                                    && !Objects.equals(entry.getValue().digest, previous.digest))) {
                        return true;
                    }
                }
            } else if (gatingMagnitude(changed) > gatingMagnitude(base)
                    || (gatingMagnitude(changed) == gatingMagnitude(base)
                            && gatingMagnitude(changed) > 0
                            && changedStageIdentity(changed, base))) {
                return true;
            }
        }
        return false;
    }

    /** Per-check gating counts (error/warn severity) from a stage's {@code checks} detail list. */
    private static Map<String, CheckIdentity> gatingChecks(Map<String, Object> details) {
        Map<String, CheckIdentity> counts = new LinkedHashMap<>();
        if (details == null || !(details.get("checks") instanceof List<?> checks)) return counts;
        for (Object value : checks) {
            if (!(value instanceof Map<?, ?> check)) continue;
            String severity = String.valueOf(check.get("severity"));
            if (!"error".equals(severity) && !"warning".equals(severity) && !"warn".equals(severity)) {
                continue;
            }
            String id = String.valueOf(check.get("id"));
            String digest = check.get("identity_digest") instanceof String
                    ? (String) check.get("identity_digest") : null;
            CheckIdentity current = new CheckIdentity(intValue(check.get("count")), digest);
            counts.merge(id, current, CheckIdentity::merge);
        }
        return counts;
    }

    /** Identity comparison for checks-less stages such as profile. */
    private static boolean changedStageIdentity(Map<String, Object> changed,
            Map<String, Object> baseline) {
        Object changedDigest = changed == null ? null : changed.get("identity_digest");
        Object baselineDigest = baseline == null ? null : baseline.get("identity_digest");
        return changedDigest instanceof String && !Objects.equals(changedDigest, baselineDigest);
    }

    /** Total gating magnitude for a checks-less stage; keys never overlap in these stage summaries. */
    private static int gatingMagnitude(Map<String, Object> details) {
        if (details == null) return 0;
        // profile/invariants/shacl report violations/errors/warnings; cqs reports failed. These key
        // sets are disjoint per stage, so summing never double-counts.
        return intValue(details.get("violations")) + intValue(details.get("errors"))
                + intValue(details.get("warnings")) + intValue(details.get("failed"));
    }

    private static CallToolResult render(Map<String, Object> payload, String mode,
            Map<String, Object> preflight, Attribution attribution, boolean prevented,
            boolean applied, ProjectPolicy policy, String errorCode) {
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("mode", mode);
        verify.put("regression", attribution.regression);
        verify.put("inconsistent", reasonerInconsistent(preflight));
        verify.put("newly_unsatisfiable", attribution.newlyUnsatisfiable);
        verify.put("rolled_back", false);
        verify.put("applied", applied);
        String reasonerStatus = reasonerStatus(preflight);
        verify.put("classification_started", "pass".equals(reasonerStatus)
                || "fail".equals(reasonerStatus) || "error".equals(reasonerStatus));
        verify.put("classification_completed", "pass".equals(reasonerStatus)
                || "fail".equals(reasonerStatus));
        if ("error".equals(reasonerStatus)) {
            verify.put("classification_failed", true);
        }
        String reasonerName = reasonerName(preflight);
        if (reasonerName != null) {
            verify.put("reasoner", reasonerName);
        }
        if (Boolean.TRUE.equals(attribution.wasInconsistent)) {
            verify.put("was_inconsistent", true);
        }
        if ("revision_conflict".equals(errorCode) || attribution.concurrentBaseline) {
            verify.put("concurrent_change", true);
        }
        verify.put("verification_path", "change_set_preflight");
        verify.put("gate", preflight.get("gate"));
        if (attribution.baselineGate != null) {
            verify.put("baseline_gate", attribution.baselineGate);
        }
        verify.put("preflight", preflight);
        if (prevented) verify.put("prevented_before_apply", true);
        if (errorCode != null) verify.put("error_code", errorCode);
        if (policy.digest() != null) verify.put("policy_digest", policy.digest());
        if (prevented && attribution.gateError) {
            verify.put("note", "The isolated gate could not produce a verdict (gate=error), so "
                    + "verify=rollback prevented the batch before live mutation (fail-closed); no undo "
                    + "of unrelated GUI history was needed. Inspect verify.preflight.errors.");
        } else if (prevented) {
            verify.put("note", "The isolated change-set gate attributed a regression to this batch, "
                    + "so verify=rollback prevented it before live mutation; no undo of unrelated GUI "
                    + "history was needed." + (attribution.concurrentBaseline
                            ? " (The workspace changed between the changed and baseline runs, so the "
                            + "verdict stays fail-closed and no specific findings are attributed.)"
                            : attribution.conservative
                            ? " (The baseline could not fully prove pre-existing findings, so the "
                            + "verdict stays fail-closed.)" : ""));
        } else if ("read_only".equals(errorCode)) {
            verify.put("note", "Server is in read-only mode; writes are disabled (toggle in "
                    + "Protégé ▸ Preferences ▸ MCP). The isolated verdict was produced, but nothing "
                    + "was applied.");
        } else if (errorCode != null) {
            verify.put("note", "The isolated verdict was produced, but the live revision/policy "
                    + "changed before commit; nothing was applied (" + errorCode + ").");
        } else if (attribution.gateError) {
            verify.put("note", "The isolated gate could not produce a verdict (gate=error), so nothing "
                    + "was attributed to this batch; verify=report kept it. Inspect "
                    + "verify.preflight.errors.");
        } else if (attribution.regression) {
            verify.put("note", "The policy/change-set gate reported a regression; verify=report kept "
                    + "the batch. Inspect verify.preflight for governance/profile/import/QC details.");
        } else if (!"pass".equals(preflight.get("gate"))) {
            verify.put("note", "The gate reports findings the unchanged baseline already produced "
                    + "(baseline_gate=" + attribution.baselineGate + "), so they are not attributed "
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
    private static Map<String, Object> neutralizedPayload(Map<String, Object> payload) {
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

    private static Map<String, Object> stageDetails(Map<String, Object> preflight, String stage) {
        Object stages = preflight.get("stages");
        if (!(stages instanceof List<?>)) return null;
        for (Object value : (List<?>) stages) {
            if (value instanceof Map<?, ?> row && stage.equals(row.get("stage"))
                    && row.get("details") instanceof Map<?, ?> details) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : details.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out.isEmpty() ? null : out;
            }
        }
        return null;
    }

    private static String reasonerStatus(Map<String, Object> preflight) {
        Object stages = preflight.get("stages");
        if (!(stages instanceof List<?>)) return null;
        for (Object value : (List<?>) stages) {
            if (value instanceof Map<?, ?> row && "reasoner".equals(row.get("stage"))) {
                return row.get("status") == null ? null : row.get("status").toString();
            }
        }
        return null;
    }

    private static String reasonerName(Map<String, Object> preflight) {
        Map<String, Object> details = stageDetails(preflight, "reasoner");
        if (details == null) return null;
        Object configuration = details.get("reasoner_configuration");
        if (configuration instanceof Map<?, ?> map && map.get("reasoner_name") != null) {
            return map.get("reasoner_name").toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> unsatItems(Map<String, Object> reasonerDetails) {
        Object classes = reasonerDetails.get("unsatisfiable_classes");
        if (classes instanceof Map<?, ?> map && map.get("items") instanceof List<?> items) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?>) out.add((Map<String, Object>) item);
            }
            return out;
        }
        return List.of();
    }

    private static int intValue(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static boolean reasonerInconsistent(Map<String, Object> preflight) {
        Map<String, Object> details = stageDetails(preflight, "reasoner");
        return details != null && Boolean.FALSE.equals(details.get("consistent"));
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

    /** The batch-attributed regression decision plus the reasoner evidence backing it. */
    private record Attribution(boolean regression, List<Map<String, Object>> newlyUnsatisfiable,
            Boolean wasInconsistent, String baselineGate, boolean conservative, boolean gateError,
            boolean concurrentBaseline) {
        static Attribution gateBased(boolean regression) {
            return new Attribution(regression, List.of(), null, null, false, false, false);
        }

        /** The gate could not produce a verdict; not a batch regression, but rollback fails closed. */
        static Attribution unverified() {
            return new Attribution(false, List.of(), null, null, false, true, false);
        }
    }

    private record CheckIdentity(int count, String digest) {
        static CheckIdentity merge(CheckIdentity left, CheckIdentity right) {
            String combined = left.digest == null || right.digest == null ? null
                    : FindingIdentity.digest(List.of(left.digest, right.digest));
            return new CheckIdentity(left.count + right.count, combined);
        }
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
