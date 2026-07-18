package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Memory-only isolated preflight and optimistic one-broadcast commit workflow. */
public final class ChangeSetTools {

    private ChangeSetTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("preview_change_set",
                (ex, req) -> {
                    Map<String, Object> arguments = Tools.args(req);
                    DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(ctx, ex,
                            Tools.optString(arguments, "policy_path"));
                    return preview(ctx, rules.authorizedPolicyArguments(arguments));
                });
        tools.tool("commit_change_set",
                (ex, req) -> {
                    DirectAccessPolicy.requireCapability(ex, DirectAccessPolicy.PROJECT_READ);
                    return commit(ctx, Tools.args(req));
                });
        tools.tool("discard_change_set",
                (ex, req) -> {
                    String id = Tools.reqString(Tools.args(req), "change_set_id");
                    boolean discarded = ctx.changeSets().discard(id);
                    return Tools.json().put("change_set_id", id).put("discarded", discarded)
                            .putIfNotNull("error_code", discarded ? null : "unknown_change_set").result();
                });
    }

    static CallToolResult preview(ToolContext ctx, Map<String, Object> arguments) {
        List<Map<String, Object>> operations = Tools.objList(arguments, "operations");
        boolean strict = Tools.optBool(arguments, "strict", false);
        return previewPrepared(ctx, arguments, mm -> ChangePlanner.plan(mm, operations, strict));
    }

    /** Shared preview/QC/cache pipeline for low-level and high-level curation planners. */
    static CallToolResult previewPrepared(ToolContext ctx, Map<String, Object> arguments,
            PreparedPlanner planner) {
        String impact = Tools.optString(arguments, "include_impact");
        if (impact != null && !Set.of("none", "asserted").contains(impact.toLowerCase())) {
            return Tools.error("include_impact must be none or asserted for preview_change_set.");
        }
        boolean assertedImpact = impact == null || "asserted".equalsIgnoreCase(impact);
        // Every statically-checkable argument is rejected BEFORE any model hop or QC work — an
        // inevitably refused request must not first consume an isolated classification. Read as a
        // LONG: Number.intValue() wraps 2^32+1 to 1, silently converting an absurd ttl into a valid
        // one instead of rejecting it.
        long ttlSeconds = 900;
        Object rawTtl = arguments.get("ttl_seconds");
        if (rawTtl != null) {
            try {
                // EXACT integral conversion: longValue() on a Jackson-parsed BigInteger (2^64+1) or
                // a fractional double silently coerces an absurd ttl into a plausible one.
                ttlSeconds = new java.math.BigDecimal(String.valueOf(rawTtl).trim()).longValueExact();
            } catch (NumberFormatException | ArithmeticException e) {
                return Tools.error("ttl_seconds must be an integer between 1 and 3600.");
            }
        }
        if (ttlSeconds <= 0 || ttlSeconds > 3600) {
            return Tools.error("ttl_seconds must be between 1 and 3600.");
        }
        String configuredPolicyPath = Tools.optString(arguments, "policy_path");
        // policy_path is retained verbatim. Reject an impossible entry before policy discovery or QC;
        // the final store guard still accounts for every structure added later.
        ChangeSetStore.validateEntryBounds(0, estimate(configuredPolicyPath));
        RevisionTools.PolicyState policyState = RevisionTools.resolvePolicy(ctx, configuredPolicyPath);
        ProjectPolicy policy = policyState.policy();
        String importLockDigest = RevisionTools.digestImportLock(policy);
        String preflightDigest;
        try {
            preflightDigest = RevisionTools.preflightDigest(policy);
        } catch (RuntimeException e) {
            return Tools.error(e.getMessage());
        }

        Captured captured = ctx.access().compute(mm -> {
            var revision = ctx.revisions().current(mm, importLockDigest, policy.digest()).revision();
            return new Captured(revision, planner.plan(mm), reasonerSelection(mm));
        });

        List<String> reasons = new ArrayList<>(captured.plan.errors());
        if (policyState.error() != null) {
            reasons.add(policyState.error());
        }
        if (policy.loaded() && !policy.valid()) {
            policy.issues().stream().filter(issue -> "error".equals(issue.severity()))
                    .forEach(issue -> reasons.add(issue.code() + ": " + issue.message()));
        }

        // Everything below is already retained even before preflight is produced. Enforce this known
        // lower bound now so a huge all-no-op plan cannot force snapshot/profile/reasoner work only to
        // be rejected by the cache after QC. The store repeats the check with preflight included.
        String retainedPolicyPath = policy.path() == null ? null : policy.path().toString();
        long retainedBeforePreflight = captured.plan.estimatedBytes()
                + estimate(captured.plan.operations()) + estimate(captured.plan.summary())
                + estimate(reasons) + estimate(configuredPolicyPath) + estimate(retainedPolicyPath);
        ChangeSetStore.validateEntryBounds(captured.plan.changes().size(), retainedBeforePreflight);

        Map<String, Object> preflight = null;
        if (reasons.isEmpty()) {
            try {
                QcRunConfig config = preflightConfig(policyState, arguments,
                        captured.reasonerSelection);
                QcSuiteExecution execution = QcSuiteTools.execute(ctx, config,
                        captured.plan.changes());
                int version = policy.loaded() && policy.effective().get("version") instanceof Number
                        ? ((Number) policy.effective().get("version")).intValue() : 0;
                preflight = QcSuiteTools.strictResult(execution, config.requiredStages, config.failOn,
                        version, policy.digest(), policy.loaded());
                // Preview must fail closed on an incomplete import closure exactly like run_project_qc,
                // so a change set cannot become committable over a truncated closure.
                ProjectQcTools.enforceImportIntegrity(policy, execution, preflight);
                if (!"pass".equals(preflight.get("gate"))) {
                    reasons.add("preflight_" + preflight.get("gate"));
                }
            } catch (RuntimeException e) {
                reasons.add("preflight_error: " + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }

        // A long reasoner/QC pass may race a GUI edit or an asset replacement. Never cache a preview
        // whose normalized delta and gate did not evaluate the same base revision/inputs. Re-RESOLVE the
        // policy here (not just re-hash the cached one) so a project.yaml edited, created, or deleted
        // mid-QC — which changes the policy digest and/or the resolved asset set — also invalidates.
        String finalPreflightDigest = null;
        String finalImportLockDigest = null;
        String finalPolicyDigest = null;
        boolean finalResolveFailed = false;
        try {
            ProjectPolicy finalPolicy = RevisionTools.resolvePolicy(ctx, configuredPolicyPath).policy();
            finalPreflightDigest = RevisionTools.preflightDigest(finalPolicy);
            finalImportLockDigest = RevisionTools.digestImportLock(finalPolicy);
            finalPolicyDigest = finalPolicy.digest();
        } catch (RuntimeException e) {
            // Could not re-read the policy/assets to prove they were unchanged — fail closed. (A null
            // triple here would otherwise collide with the legitimate no-policy null case.)
            finalResolveFailed = true;
        }
        ModelRevision finalRevision = ctx.access().compute(mm -> ctx.revisions().current(
                mm, importLockDigest, policy.digest()).revision());
        if (finalResolveFailed
                || !captured.revision.equals(finalRevision)
                || !java.util.Objects.equals(preflightDigest, finalPreflightDigest)
                || !java.util.Objects.equals(importLockDigest, finalImportLockDigest)
                || !java.util.Objects.equals(policy.digest(), finalPolicyDigest)) {
            Map<String, Object> invalidated = basePreview(captured, preflight, reasons, assertedImpact);
            invalidated.put("committable", false);
            invalidated.put("preview_invalidated", true);
            invalidated.put("error_code", "revision_changed_during_preview");
            invalidated.put("current_revision", RevisionTools.revisionJson(finalRevision));
            putSatisfiability(invalidated, preflight);
            return Tools.ok(invalidated);
        }

        boolean committable = reasons.isEmpty() && captured.plan.committable();
        // (assertedImpact controls the operations detail in basePreview below.)
        // Size accounting must cover EVERYTHING the entry retains — the normalized axioms alone
        // undercount badly for a mostly-no-op preview, whose operation rows (or a caller-supplied
        // giant policy_path echoed into the reasons) can dwarf its delta.
        ChangeSetStore.Draft draft = new ChangeSetStore.Draft(captured.revision, configuredPolicyPath,
                retainedPolicyPath, policy.digest(), preflightDigest,
                importLockDigest, captured.plan.changes(), captured.plan.operations(),
                captured.plan.summary(), preflight, committable, reasons,
                captured.plan.estimatedBytes() + estimate(preflight)
                        + estimate(captured.plan.operations()) + estimate(captured.plan.summary())
                        + estimate(reasons) + estimate(configuredPolicyPath)
                        + estimate(retainedPolicyPath));
        ChangeSetStore.Entry entry = ctx.changeSets().put(draft, ttlSeconds * 1_000L);
        Map<String, Object> result = basePreview(captured, preflight, reasons, assertedImpact);
        result.put("change_set_id", entry.id);
        result.put("expires_at", entry.expiresAtIso());
        result.put("committable", committable);
        putSatisfiability(result, preflight);
        result.put("policy_loaded", policy.loaded());
        if (policy.digest() != null) {
            result.put("policy_digest", policy.digest());
            result.put("preflight_contract_digest", preflightDigest);
        }
        result.put("snapshot_consistent", true);
        result.put("live_mutated", false);
        return Tools.ok(result);
    }

    private static Map<String, Object> basePreview(Captured captured, Map<String, Object> preflight,
            List<String> reasons, boolean assertedImpact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_revision", RevisionTools.revisionJson(captured.revision));
        result.put("normalized_changes", captured.plan.changes().size());
        // include_impact=asserted (default) returns the per-operation asserted detail; =none returns only
        // the counts/summary for a lighter response (inferred impact belongs to semantic_diff either way).
        result.put("include_impact", assertedImpact ? "asserted" : "none");
        if (assertedImpact) {
            result.put("operations", captured.plan.operations());
        }
        result.put("summary", captured.plan.summary());
        result.put("preflight", preflight);
        result.put("committable_reasons", List.copyOf(reasons));
        return result;
    }

    static QcRunConfig preflightConfig(RevisionTools.PolicyState state,
            Map<String, Object> arguments, ReasonerSelection selection) {
        // apply_changes' documented timeout_ms is the preflight budget. It can only TIGHTEN a
        // policy's reasoning.timeout_ms — the policy stays the reproducible ceiling.
        Integer requestedTimeout = arguments.get("timeout_ms") instanceof Number number
                ? Math.max(1, number.intValue()) : null;
        if (state.policy().loaded() && state.policy().valid()) {
            QcRunConfig config = ProjectQcTools.config(state.policy(), state.live(),
                    arguments);
            return requestedTimeout == null || requestedTimeout >= config.timeout
                    ? config : config.withTimeout(requestedTimeout);
        }
        List<String> requested = Tools.stringList(arguments, "gates");
        List<String> required = requested;
        if (requested.isEmpty()) {
            // The no-policy default gates satisfiability whenever it can: the reasoner stage is
            // ALWAYS scheduled, so whichever reasoner is selected when the QC snapshot captures —
            // including one selected after this probe — classifies the changed snapshot, and a FAIL
            // verdict (inconsistency or any unsatisfiable class) blocks the commit — the change-set
            // counterpart of apply_changes verify=rollback, and stricter: it evaluates the RESULT
            // state, so a pre-existing unsatisfiable class also refuses until fixed (pass explicit
            // gates to override). Once the probe OBSERVED a selection the stage is also REQUIRED, so
            // a selection that turns out broken, or vanishes before the QC snapshot, fails the gate
            // closed instead of silently previewing without the verdict; with no selection anywhere
            // the scheduled stage skips and the preview reports satisfiability_checked=false.
            required = List.of("profile", "governance", "structural");
            requested = List.of("reasoner", "profile", "governance", "structural");
            if (selection != ReasonerSelection.NONE) {
                required = requested;
            }
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("stages", requested);
        legacy.put("required_stages", required);
        legacy.put("fail_on", "warn");
        legacy.put("limit", 25);
        if (requestedTimeout != null) {
            legacy.put("timeout_ms", requestedTimeout);
        }
        return QcRunConfig.legacy(legacy);
    }

    /** The reasoner-selection state the preview's captured hop observed. */
    enum ReasonerSelection {
        /** No reasoner subsystem, or Protégé's None selection: preview without the stage, honestly. */
        NONE,
        /** A working selection was captured: the stage runs and is required. */
        SELECTED,
        /** A selection EXISTS but its metadata is unusable: schedule the stage so it fails closed. */
        BROKEN
    }

    /**
     * Probe the workspace's reasoner selection for the default preview gate. A missing reasoner
     * None selection is represented by a successful manager capture with no current factory — nothing
     * is selected, so the preview says {@code satisfiability_checked=false}. A null manager or any
     * lookup/capture exception is BROKEN: the stage is still scheduled as required, so the QC pass
     * reproduces the failure as a gate error instead of silently dropping the one verdict the user
     * configured a reasoner to provide.
     */
    static ReasonerSelection reasonerSelection(OWLModelManager mm) {
        OWLReasonerManager manager;
        try {
            manager = mm.getOWLReasonerManager();
        } catch (RuntimeException e) {
            return ReasonerSelection.BROKEN;
        }
        if (manager == null) {
            return ReasonerSelection.BROKEN;
        }
        try {
            return IsolatedReasonerSpec.capture(manager) != null
                    ? ReasonerSelection.SELECTED : ReasonerSelection.NONE;
        } catch (RuntimeException e) {
            return ReasonerSelection.BROKEN;
        }
    }

    /** Every preview shape — cached or invalidated — must say whether a reasoner verdict gated it. */
    private static void putSatisfiability(Map<String, Object> result, Map<String, Object> preflight) {
        boolean satisfiability = satisfiabilityChecked(preflight);
        result.put("satisfiability_checked", satisfiability);
        if (!satisfiability) {
            result.put("satisfiability_note", "The preflight gate did not produce a reasoner verdict: "
                    + "no reasoner may be selected, the policy/gates may omit the stage, or reasoner "
                    + "setup/classification may have failed. Consistency and satisfiability were NOT "
                    + "checked for this preview. Inspect the preflight reasoner row and policy_loaded "
                    + "before retrying; use list_reasoners/set_reasoner only when that stage expects a "
                    + "selection.");
        }
    }

    /** True when the preflight's reasoner stage ran to a pass/fail verdict for this preview. */
    private static boolean satisfiabilityChecked(Map<String, Object> preflight) {
        if (preflight == null || !(preflight.get("stages") instanceof List<?> rows)) {
            return false;
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> stage && "reasoner".equals(stage.get("stage"))) {
                Object status = stage.get("status");
                return "pass".equals(status) || "fail".equals(status);
            }
        }
        return false;
    }

    /**
     * EDT wait bound for the single commit hop. The hop runs two WorkspaceRevisionTracker.current()
     * calls (four full canonical-serialization fingerprints) around the applyChanges broadcast, which
     * on a large ontology legitimately outlives the default 30 s wait — and a wait that times out
     * cannot cancel a body that already started, so a short bound would report a failure while the
     * delta still lands. Must stay equal to the document tools' save/merge bound, which exists for
     * the same slow-but-succeeding reason.
     */
    static final long COMMIT_TIMEOUT_MS = 120_000L;

    static CallToolResult commit(ToolContext ctx, Map<String, Object> arguments) {
        String id = Tools.reqString(arguments, "change_set_id");
        ModelRevision expected = expectedRevision(arguments.get("expected_revision"));
        String confirmedPolicy = Tools.optString(arguments, "confirm_policy_digest");
        ChangeSetStore.Lookup lookup = ctx.changeSets().claim(id);
        if (lookup.entry() == null) {
            return outcome(id, false, lookup.error(), null);
        }
        ChangeSetStore.Entry entry = lookup.entry();
        boolean consume = false;
        try {
            if (!entry.baseRevision.equals(expected)) {
                return outcome(id, false, "revision_conflict",
                        Map.of("base_revision", RevisionTools.revisionJson(entry.baseRevision)));
            }
            if (confirmedPolicy != null && !confirmedPolicy.equals(entry.policyDigest)) {
                return outcome(id, false, "policy_digest_conflict", null);
            }
            if (!entry.committable) {
                return outcome(id, false, "change_set_not_committable",
                        Map.of("reasons", entry.reasons));
            }
            if (ctx.controller().isReadOnly()) {
                return outcome(id, false, "read_only", null);
            }
            if (ctx.controller().isConfirmWrites()) {
                WriteConfirmer confirmer = ctx.confirmer();
                if (confirmer == null || !confirmer.confirm("commit change set " + id + " ("
                        + entry.changes.size() + " normalized change(s))")) {
                    return outcome(id, false, "write_declined", null);
                }
            }

            ctx.writeLock().lock();
            try {
                // Policy revalidation deliberately happens INSIDE the hop (see commitOnModelThread):
                // the hop can queue behind a busy EDT for up to COMMIT_TIMEOUT_MS, so any check made
                // here would already be stale by the time the delta applies. No pre-hop fast-fail
                // either — the resolution costs a policy read, and doing it twice buys nothing.
                CommitResult committed;
                try {
                    committed = ctx.access().compute(
                            mm -> commitOnModelThread(ctx, mm, entry), COMMIT_TIMEOUT_MS);
                } catch (McpAccessException e) {
                    // The bounded EDT wait can expire (or be interrupted) after the body has already
                    // started, and OntologyAccess cannot cancel a running body — so the delta may
                    // still be applied even though this call reports a failure. Never claim that
                    // nothing happened.
                    throw new McpAccessException(e.getMessage() + " The commit body may still "
                            + "complete on the Protégé UI thread: call get_model_revision and compare "
                            + "it with the preview's base_revision before retrying.", e);
                }
                if (!committed.committed) {
                    return outcome(id, false, committed.errorCode, committed.details);
                }
                consume = true;
                Map<String, Object> details = new LinkedHashMap<>(committed.details);
                details.put("new_revision", RevisionTools.revisionJson(committed.newRevision));
                details.put("normalized_changes", entry.changes.size());
                return outcome(id, true, null, details);
            } finally {
                ctx.writeLock().unlock();
            }
        } finally {
            if (consume) {
                ctx.changeSets().consume(entry);
            } else {
                ctx.changeSets().release(entry);
            }
        }
    }

    private static CommitResult commitOnModelThread(ToolContext ctx, OWLModelManager mm,
            ChangeSetStore.Entry entry) {
        if (ctx.controller().isReadOnly()) {
            return CommitResult.error("read_only", null);
        }
        // Compare revisions with the PINNED policy coordinates: they reproduce exactly the
        // fingerprint inputs the preview's base_revision was built with, and the policy verification
        // below proves the live policy still matches them before anything applies.
        ModelRevision live = ctx.revisions().current(mm, entry.importLockDigest,
                entry.policyDigest).revision();
        if (!entry.baseRevision.equals(live)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("base_revision", RevisionTools.revisionJson(entry.baseRevision));
            details.put("current_revision", RevisionTools.revisionJson(live));
            return CommitResult.error("revision_conflict", details);
        }
        OWLOntology target = mm.getActiveOntology();
        List<OWLOntologyChange> bound = entry.changes.stream().map(change -> change.bind(target)).toList();
        // The effective-policy verification runs LAST — after the fingerprint comparison above
        // (seconds on a large ontology) and immediately before applyChanges — so the window in which
        // an external rewrite of project.yaml (e.g. a VCS checkout) can slip past undetected is
        // bounded by this resolve+digest step, typically milliseconds; no process can close that
        // final filesystem race entirely. Re-run the SAME resolution the preview used (its explicit
        // policy_path, or discovery when none was given): a project.yaml created nearer the ontology
        // document after the preview would shadow the pinned policy, and loading the pinned file
        // directly would commit the delta under superseded rules. A different effective policy file
        // is a conflict regardless of digests; the digest checks below then catch in-place edits of
        // the same file/assets. Their bounded streaming file hashing runs deliberately on the model
        // thread — deciding off-thread would reopen the race.
        ProjectPolicy currentPolicy = effectivePolicy(mm, entry.configuredPolicyPath);
        String currentPolicyPath = currentPolicy.path() == null
                ? null : currentPolicy.path().toString();
        if (!java.util.Objects.equals(entry.policyPath, currentPolicyPath)) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("pinned_policy_path", entry.policyPath);
            conflict.put("effective_policy_path", currentPolicyPath);
            return CommitResult.error("policy_conflict", conflict);
        }
        if (!java.util.Objects.equals(entry.policyDigest, currentPolicy.digest())
                || !java.util.Objects.equals(entry.preflightDigest,
                        RevisionTools.preflightDigest(currentPolicy))
                || !java.util.Objects.equals(entry.importLockDigest,
                        RevisionTools.digestImportLock(currentPolicy))) {
            return CommitResult.error("policy_digest_conflict", null);
        }
        int before = historyDepth(mm);
        if (!bound.isEmpty()) {
            mm.applyChanges(bound);
        }
        int after = historyDepth(mm);
        ModelRevision newRevision = ctx.revisions().current(mm, entry.importLockDigest,
                entry.policyDigest).revision();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("undo_logged", before >= 0 && after == before + 1);
        details.put("undo_depth_before", before < 0 ? null : before);
        details.put("undo_depth_after", after < 0 ? null : after);
        details.put("single_broadcast", !bound.isEmpty());
        details.put("effective_changes", bound.size());
        // The delta was applied through ONE applyChanges broadcast, which has already mutated the live
        // ontology. If a listener logged extra undo entries (depth grew by more than one), the commit
        // still succeeded — reporting committed=false here would strand the client (it thinks nothing
        // happened and retries into a revision_conflict against a model that already contains the delta).
        // Surface the anomaly as a warning instead; a single Undo may not fully revert.
        if (before >= 0 && after > before + 1) {
            details.put("undo_log_warning", "The commit produced more than one undo entry (" + before
                    + "→" + after + "); a single Undo may not fully revert it.");
        }
        return new CommitResult(true, null, newRevision, details);
    }

    /**
     * In-hop equivalent of {@link RevisionTools#resolvePolicy}: the shared resolver marshals its own
     * model-thread hop to capture live context, which cannot be nested inside the commit hop. The
     * live inputs the loader needs — document path, active ontology IRI, installed reasoners — are
     * model state, so they are re-derived from {@code mm} directly and the loader runs synchronously.
     */
    static ProjectPolicy effectivePolicy(OWLModelManager mm, String configured) {
        Path explicit = null;
        if (configured != null) {
            try {
                explicit = Path.of(configured);
            } catch (InvalidPathException e) {
                // A committable entry always carries a parseable configured path (the preview fails
                // closed on an invalid one); treat a corrupt value as no effective policy so the
                // pinned-path comparison refuses the commit.
                return ProjectPolicy.notFound();
            }
        }
        OWLOntology active = mm.getActiveOntology();
        String ontologyIri = active.getOntologyID().getOntologyIRI().orNull() == null
                ? null : active.getOntologyID().getOntologyIRI().orNull().toString();
        File document = SidecarPaths.toFile(mm.getOWLOntologyManager().getOntologyDocumentIRI(active));
        List<String> reasoners = new ArrayList<>();
        try {
            for (var info : mm.getOWLReasonerManager().getInstalledReasonerFactories()) {
                if (info.getReasonerName() != null) {
                    reasoners.add(info.getReasonerName());
                }
            }
        } catch (RuntimeException unavailableInHeadlessAdapter) {
            // Mirrors ProjectPolicyTools.capture: without a plugin registry, a required-reasoner
            // policy simply validates as unavailable — validity is not compared at commit time.
        }
        return ProjectPolicyLoader.load(explicit, document == null ? null : document.toPath(),
                ontologyIri, reasoners);
    }

    private static int historyDepth(OWLModelManager mm) {
        try {
            HistoryManager history = mm.getHistoryManager();
            return history == null ? -1 : history.getLoggedChanges().size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    static ModelRevision expectedRevision(Object value) {
        if (!(value instanceof Map)) {
            throw new ToolArgException("expected_revision must be an object with all four coordinates.");
        }
        Map<String, Object> map = (Map<String, Object>) value;
        Set<String> expectedKeys = Set.of("workspace_id", "session_revision", "semantic_fingerprint",
                "document_fingerprint");
        if (!map.keySet().equals(expectedKeys)) {
            throw new ToolArgException("expected_revision must contain exactly: "
                    + String.join(", ", expectedKeys));
        }
        Object session = map.get("session_revision");
        if (!(session instanceof Byte || session instanceof Short || session instanceof Integer
                || session instanceof Long)) {
            throw new ToolArgException("expected_revision.session_revision must be an integer.");
        }
        if (!(map.get("workspace_id") instanceof String)
                || !(map.get("semantic_fingerprint") instanceof String)
                || !(map.get("document_fingerprint") instanceof String)) {
            throw new ToolArgException("expected_revision string coordinates must be strings.");
        }
        return new ModelRevision((String) map.get("workspace_id"), ((Number) session).longValue(),
                (String) map.get("semantic_fingerprint"), (String) map.get("document_fingerprint"));
    }

    private static CallToolResult outcome(String id, boolean committed, String errorCode,
            Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("change_set_id", id);
        result.put("committed", committed);
        if (errorCode != null) {
            result.put("error_code", errorCode);
        }
        if (details != null) {
            result.putAll(details);
        }
        return Tools.ok(result);
    }

    private static long estimate(Object retained) {
        return retained == null ? 0L : retained.toString().length() * 2L;
    }

    private record Captured(ModelRevision revision, ChangePlanner.Plan plan,
            ReasonerSelection reasonerSelection) { }

    @FunctionalInterface
    interface PreparedPlanner {
        ChangePlanner.Plan plan(OWLModelManager modelManager);
    }

    private record CommitResult(boolean committed, String errorCode, ModelRevision newRevision,
            Map<String, Object> details) {
        static CommitResult error(String code, Map<String, Object> details) {
            return new CommitResult(false, code, null,
                    details == null ? Collections.emptyMap() : details);
        }
    }
}
