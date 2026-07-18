package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Attributes isolated QC findings to the proposed batch rather than the unchanged baseline. */
final class ChangeSetRegressionAttributor {

    private ChangeSetRegressionAttributor() {
    }

    /** Re-run the same no-policy gate on the UNCHANGED baseline and subtract what it already fails. */
    static Attribution attributeAgainstBaseline(ToolContext ctx,
            QcRunConfig config, Map<String, Object> preflight,
            QcSuiteExecution changedExecution, ModelRevision changedRevision,
            String lockDigest, ProjectPolicy policy) {
        Map<String, Object> baseline;
        QcSuiteExecution baselineExecution;
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
        Map<String, Object> changedReasoner = ChangeSetPreflightView.stageDetails(
                preflight, "reasoner");
        Map<String, Object> baselineReasoner = ChangeSetPreflightView.stageDetails(
                baseline, "reasoner");
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
            int changedCount = ChangeSetPreflightView.intValue(
                    changedReasoner.get("unsatisfiable_count"));
            int baselineCount = baselineReasoner == null ? 0
                    : ChangeSetPreflightView.intValue(baselineReasoner.get("unsatisfiable_count"));
            List<Map<String, Object>> changedItems = ChangeSetPreflightView.unsatItems(changedReasoner);
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
                    for (Map<String, Object> item : ChangeSetPreflightView.unsatItems(baselineReasoner)) {
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
                                && ChangeSetPreflightView.unsatItems(baselineReasoner).size()
                                        < baselineCount);
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
            Set<String> freshIris, QcRunConfig config) {
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
            Map<String, Object> baseline, QcSuiteExecution changedExecution,
            QcSuiteExecution baselineExecution) {
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
                continue;
            }

            // Fallback for a stage without recorded identities: the per-check / checks-less count and
            // digest heuristic. It catches a fresh check, a higher count, and a same-count offender
            // swap, but cannot prove a subset when the count drops, so it is used only when the sound
            // identity sets are unavailable.
            Map<String, Object> changed = ChangeSetPreflightView.stageDetails(preflight, stage);
            Map<String, Object> base = ChangeSetPreflightView.stageDetails(baseline, stage);
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
            CheckIdentity current = new CheckIdentity(
                    ChangeSetPreflightView.intValue(check.get("count")), digest);
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
        return ChangeSetPreflightView.intValue(details.get("violations"))
                + ChangeSetPreflightView.intValue(details.get("errors"))
                + ChangeSetPreflightView.intValue(details.get("warnings"))
                + ChangeSetPreflightView.intValue(details.get("failed"));
    }

    /** The batch-attributed regression decision plus the reasoner evidence backing it. */
    record Attribution(boolean regression, List<Map<String, Object>> newlyUnsatisfiable,
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
}
