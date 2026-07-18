package io.github.hakjuoh.protege_mcp.tools;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService;
import io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService.Candidates;
import io.github.hakjuoh.protege_mcp.core.diff.InferredDiffService.Evaluation;
import io.github.hakjuoh.protege_mcp.core.owl.OwlDocumentSignature;

/**
 * Plugin orchestration for {@code semantic_diff mode=inferred|both} (ADR 0004): captures the
 * selected (or requested) reasoner recipe and both flattened sides in one model-thread hop, then
 * evaluates them OFF the EDT on a daemon thread under one total budget, with at most ONE live
 * reasoner instance at a time — the captured configuration object is shared, and some factories
 * mutate it in place, so the left side is fully evaluated and disposed before the right side's
 * instance is constructed.
 */
final class InferredDiffOrchestrator {

    /** Reported/checked disjointness candidates (ADR 0004: capped, disclosed truncation). */
    static final int DISJOINTNESS_REPORT_CAP = 500;
    /**
     * Per-side verdict superset bound. Verdicts must be taken while a side's reasoner is alive —
     * before the other side's evaluation (and therefore the final candidate list) exists — so both
     * sides answer a deterministic superset; a candidate that ranks beyond this bound yields an
     * honestly errored disjointness category rather than a silent gap.
     */
    static final int VERDICT_SUPERSET_CAP = 2_000;

    static final int DEFAULT_TIMEOUT_MS = 120_000;

    private InferredDiffOrchestrator() {
    }

    /**
     * Everything the single model-thread hop must capture for the off-EDT evaluation. Beside the
     * shared Σ (the entailment categories' signature), the two PER-SIDE named signatures back the
     * module-ownership policy delta (ADR 0004 decision 4) and the live prefix map backs the
     * stage-delta SPARQL snapshots, so no later model hop is needed.
     */
    record Captured(OWLOntology leftFlattened, OWLOntology rightFlattened,
            InferredDiffService.Sigma sigma,
            Set<String> leftSignature, Set<String> rightSignature, Map<String, String> prefixes,
            IsolatedReasonerSpec spec) { }

    /**
     * Model-thread capture. {@code reasonerName} selects among the installed Protégé reasoner
     * factories (ADR 0004 decision 9); {@code null} uses the current selection. Throws
     * {@link ToolArgException} when {@code mode=inferred|both} has no usable reasoner.
     */
    static Captured capture(OWLModelManager mm, OWLOntology left, OWLOntology right,
            boolean includeImports, String reasonerName) {
        IsolatedReasonerSpec spec = captureSpec(mm, reasonerName);
        OWLOntology leftFlat = SparqlTools.buildSnapshotOntology(OwlManagers.create(),
                left.getOntologyID(), left.getImportsClosure());
        OWLOntology rightFlat = SparqlTools.buildSnapshotOntology(OwlManagers.create(),
                right.getOntologyID(), right.getImportsClosure());
        // Σ per the requested scope (ADR 0004 decision 2): the intersection of the two sides'
        // named class/individual signatures — root-document-scoped signatures for
        // include_imports=false (classification still sees each side's whole closure), closure
        // signatures otherwise. Document-scoped signatures avoid the closure-wide
        // REPAIR_ILLEGAL_PUNNINGS signature pollution the asserted diff already sidesteps.
        Set<String> leftSignature = namedSignature(left, includeImports);
        Set<String> rightSignature = namedSignature(right, includeImports);
        // TYPED Σ (per entity kind): an IRI punned as a class on one side and an individual on the
        // other must not enter the class categories or the disjointness candidates.
        Set<String> classSigma = typedSignature(left, includeImports, true);
        classSigma.retainAll(typedSignature(right, includeImports, true));
        Set<String> individualSigma = typedSignature(left, includeImports, false);
        individualSigma.retainAll(typedSignature(right, includeImports, false));
        InferredDiffService.Sigma sigma = new InferredDiffService.Sigma(classSigma, individualSigma);
        return new Captured(leftFlat, rightFlat, sigma, leftSignature, rightSignature,
                SparqlTools.prefixMap(mm, left), spec);
    }

    private static IsolatedReasonerSpec captureSpec(OWLModelManager mm, String reasonerName) {
        OWLReasonerManager manager;
        try {
            manager = mm.getOWLReasonerManager();
        } catch (RuntimeException e) {
            throw new ToolArgException("The reasoner subsystem is unavailable: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (manager == null) {
            throw new ToolArgException("The reasoner subsystem is unavailable.");
        }
        if (reasonerName != null) {
            for (var info : manager.getInstalledReasonerFactories()) {
                if (reasonerName.equalsIgnoreCase(info.getReasonerName())
                        || reasonerName.equals(info.getReasonerId())) {
                    return IsolatedReasonerSpec.capture(info);
                }
            }
            throw new ToolArgException("No installed reasoner matches '" + reasonerName
                    + "' (see list_reasoners).");
        }
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(manager);
        if (spec == null) {
            throw new ToolArgException("semantic_diff mode=inferred|both requires a reasoner: "
                    + "select one in Protégé (or pass reasoner=...) and retry.");
        }
        return spec;
    }

    private static Set<String> typedSignature(OWLOntology ontology, boolean includeImports,
            boolean classes) {
        Set<String> iris = new TreeSet<>();
        Set<OWLOntology> scope = includeImports ? ontology.getImportsClosure() : Set.of(ontology);
        for (OWLOntology member : scope) {
            for (OWLEntity entity : OwlDocumentSignature.of(member)) {
                if (classes ? entity instanceof OWLClass : entity instanceof OWLNamedIndividual) {
                    iris.add(entity.getIRI().toString());
                }
            }
        }
        return iris;
    }

    private static Set<String> namedSignature(OWLOntology ontology, boolean includeImports) {
        Set<String> sigma = new TreeSet<>();
        Set<OWLOntology> scope = includeImports ? ontology.getImportsClosure() : Set.of(ontology);
        for (OWLOntology member : scope) {
            for (OWLEntity entity : OwlDocumentSignature.of(member)) {
                if (entity instanceof OWLClass || entity instanceof OWLNamedIndividual) {
                    sigma.add(entity.getIRI().toString());
                }
            }
        }
        return sigma;
    }

    /**
     * Off-EDT evaluation under one total budget covering ALL reasoner interaction. Returns
     * {@code {inferred: <category map or {error}>, reasoner: <parity metadata>}}; never throws for
     * reasoner/timeout failures — those surface as the errored inferred section the classification
     * then treats as missing evidence (ADR 0004 decisions 5 and 8).
     */
    static Map<String, Object> run(Captured captured, int timeoutMs, int limit) {
        return run(captured, timeoutMs, limit, null);
    }

    /**
     * As above, additionally evaluating policy-required stage deltas (ADR 0004 decision 3) under
     * the SAME total budget when {@code plan} is non-null; the result then also carries a
     * {@code stage_deltas} section, which fails closed per stage (and as a whole on abandonment).
     */
    static Map<String, Object> run(Captured captured, int timeoutMs, int limit, StagePlan plan) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        InferredDiffService.BudgetGuard guard = () -> {
            if (System.nanoTime() > deadline) {
                throw new ToolArgException(
                        "inferred diff budget of " + timeoutMs + " ms expired");
            }
        };
        // Dispose ownership across abandonment (the QC pattern): the worker publishes its live
        // reasoner here; whichever thread getAndSet(null)s it first disposes it, and an abandoned
        // worker refuses to construct the next instance — so the one-live-instance discipline
        // holds even when the outer wait gives up.
        java.util.concurrent.atomic.AtomicReference<OWLReasoner> live =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean abandoned =
                new java.util.concurrent.atomic.AtomicBoolean();
        FutureTask<Map<String, Object>> task = new FutureTask<>(
                () -> evaluateBothSides(captured, guard, limit, plan, deadline, live, abandoned));
        Thread worker = new Thread(task, "protege-mcp-inferred-diff");
        worker.setDaemon(true);
        worker.start();
        try {
            // Small grace beyond the budget: the guard fires between reasoner interactions, so a
            // healthy run self-reports expiry as errored categories rather than being abandoned.
            return task.get(timeoutMs + 2_000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandoned.set(true);
            worker.interrupt();
            OWLReasoner leaked = live.getAndSet(null);
            if (leaked != null) {
                try {
                    leaked.dispose();
                } catch (RuntimeException ignored) {
                    // Best-effort interrupt of an abandoned classification.
                }
            }
            return erroredSection("the inferred evaluation did not complete within "
                    + timeoutMs + " ms and was abandoned", plan != null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return erroredSection(cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage(), plan != null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return erroredSection("interrupted while evaluating the inferred diff", plan != null);
        }
    }

    private static Map<String, Object> erroredSection(String message, boolean withStageDeltas) {
        Map<String, Object> inferred = new LinkedHashMap<>();
        inferred.put("entailment_set", InferredDiffService.ENTAILMENT_SET);
        inferred.put("error", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inferred", inferred);
        if (withStageDeltas) {
            // The stage deltas were abandoned with the rest of the evaluation: fail closed as a
            // whole, never as silently absent or empty deltas.
            Map<String, Object> deltas = new LinkedHashMap<>();
            deltas.put("error", message);
            out.put("stage_deltas", deltas);
        }
        return out;
    }

    private static Map<String, Object> evaluateBothSides(Captured captured,
            InferredDiffService.BudgetGuard guard, int limit, StagePlan plan, long deadlineNanos,
            java.util.concurrent.atomic.AtomicReference<OWLReasoner> live,
            java.util.concurrent.atomic.AtomicBoolean abandoned) {
        // The verdict superset must be known before either side is evaluated (see the field note).
        TreeSet<List<String>> superset = new TreeSet<>(
                java.util.Comparator.<List<String>, String>comparing(pair -> pair.get(0))
                        .thenComparing(pair -> pair.get(1)));
        superset.addAll(InferredDiffService.assertedCandidatePairs(
                captured.leftFlattened(), captured.sigma()));
        superset.addAll(InferredDiffService.assertedCandidatePairs(
                captured.rightFlattened(), captured.sigma()));
        List<List<String>> supersetPairs = new ArrayList<>();
        for (List<String> pair : superset) {
            if (supersetPairs.size() >= VERDICT_SUPERSET_CAP) {
                break;
            }
            supersetPairs.add(pair);
        }
        Candidates verdictSuperset = new Candidates(List.copyOf(supersetPairs),
                superset.size(), superset.size() > supersetPairs.size());

        SideResult left = evaluateSide(captured.leftFlattened(), captured, verdictSuperset,
                guard, deadlineNanos, live, abandoned);
        SideResult right = evaluateSide(captured.rightFlattened(), captured, verdictSuperset,
                guard, deadlineNanos, live, abandoned);

        Candidates candidates = InferredDiffService.disjointnessCandidates(
                left.evaluation(), right.evaluation(), DISJOINTNESS_REPORT_CAP);
        Map<String, Object> inferred = InferredDiffService.compare(left.evaluation(),
                right.evaluation(), left.verdicts(), right.verdicts(), candidates, limit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inferred", inferred);
        out.put("reasoner", left.parity() != null ? left.parity() : right.parity());
        if (plan != null) {
            // Both reasoners are disposed by now; the stage deltas are reasoner-free evaluations of
            // the two captured flattened sides under the remaining shared budget.
            out.put("stage_deltas", stageDeltas(captured, plan, guard, limit, deadlineNanos));
        }
        return out;
    }

    private record SideResult(Evaluation evaluation, Map<String, Boolean> verdicts,
            Map<String, Object> parity) { }

    /** Evaluate one side with ONE reasoner instance, disposed before this method returns. */
    private static SideResult evaluateSide(OWLOntology flattened, Captured captured,
            Candidates verdictSuperset, InferredDiffService.BudgetGuard guard, long deadlineNanos,
            java.util.concurrent.atomic.AtomicReference<OWLReasoner> live,
            java.util.concurrent.atomic.AtomicBoolean abandoned) {
        OWLReasoner reasoner = null;
        Map<String, Object> parity = null;
        try {
            guard.check();
            if (abandoned.get()) {
                throw new ToolArgException("the inferred evaluation was abandoned");
            }
            reasoner = captured.spec().create(flattened);
            if (!live.compareAndSet(null, reasoner)) {
                // The outer thread already took ownership (abandonment): stop immediately.
                reasoner.dispose();
                reasoner = null;
                throw new ToolArgException("the inferred evaluation was abandoned");
            }
            parity = captured.spec().metadata(reasoner);
            Evaluation evaluation = InferredDiffService.evaluate(flattened, reasoner,
                    captured.sigma(), guard);
            Map<String, Boolean> verdicts = null;
            if (evaluation.consistent() && !evaluation.categoryErrors().containsKey("consistency")) {
                try {
                    // Sub-budget (a quarter of what remains): an oversized candidate matrix on
                    // this side must starve only this side's DISJOINTNESS verdicts — never the
                    // other side's categories — mirroring ADR 0004's disjointness-runs-last rule
                    // across the sequential-side capture.
                    long remaining = deadlineNanos - System.nanoTime();
                    long subDeadline = System.nanoTime() + Math.max(remaining / 4,
                            TimeUnit.MILLISECONDS.toNanos(250));
                    InferredDiffService.BudgetGuard subGuard = () -> {
                        guard.check();
                        if (System.nanoTime() > subDeadline) {
                            throw new ToolArgException(
                                    "disjointness verdict sub-budget expired on this side");
                        }
                    };
                    verdicts = InferredDiffService.disjointnessVerdicts(flattened, reasoner,
                            verdictSuperset, subGuard);
                } catch (RuntimeException e) {
                    // Missing verdicts surface as an errored disjointness category downstream.
                    verdicts = null;
                }
            } else {
                verdicts = Map.of();
            }
            return new SideResult(evaluation, verdicts, parity);
        } catch (RuntimeException creationFailure) {
            Map<String, String> errors = new java.util.TreeMap<>();
            String message = creationFailure.getMessage() == null
                    ? creationFailure.getClass().getSimpleName() : creationFailure.getMessage();
            errors.put("consistency", "reasoner unavailable: " + message);
            return new SideResult(new Evaluation(true, captured.sigma(), Set.of(), Map.of(),
                    Map.of(), Map.of(), Set.of(), errors), null, parity);
        } finally {
            OWLReasoner owned = reasoner == null ? null : live.getAndSet(null) == reasoner
                    ? reasoner : null;
            if (owned != null) {
                try {
                    owned.dispose();
                } catch (RuntimeException ignored) {
                    // Disposal failures must not mask the evaluation result.
                }
            }
        }
    }

    // ================================================================== policy-driven categories

    /** The four policy stages whose member-level finding-identity deltas semantic_diff reports. */
    private static final List<String> DELTA_STAGES = List.of("cqs", "invariants", "shacl",
            "governance");

    /**
     * Policy-resolved assets for the stage-delta evaluation, prepared BEFORE the budgeted worker
     * starts. Per-stage preparation failures fail closed: the stage is still reported, as an
     * errored delta naming the problem — never silently absent.
     */
    static final class StagePlan {
        final List<String> stages;
        final Map<String, String> preparationErrors = new java.util.TreeMap<>();
        List<CompetencyQuestion> cqs = List.of();
        List<Invariants.Invariant> invariants = List.of();
        List<java.nio.file.Path> shaclPaths = List.of();
        PolicyGovernance.Rules governanceRules = PolicyGovernance.Rules.empty();

        StagePlan(List<String> stages) {
            this.stages = List.copyOf(stages);
        }
    }

    /**
     * Resolve the loaded, VALID policy's required stages (∩ the four delta stages) and their
     * assets. Returns {@code null} when the policy requires none of them — the caller then reports
     * the category as unavailable with a reason instead of an empty object.
     */
    static StagePlan stagePlan(io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy) {
        Object validation = policy.effective().get("validation");
        Object required = validation instanceof Map<?, ?> map ? map.get("required_stages") : null;
        List<String> requiredStages = required instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        List<String> stages = DELTA_STAGES.stream().filter(requiredStages::contains).toList();
        if (stages.isEmpty()) {
            return null;
        }
        StagePlan plan = new StagePlan(stages);
        if (stages.contains("cqs")) {
            try {
                List<CompetencyQuestion> cqs = ProjectQcTools.loadPolicyCqs(policy);
                if (cqs == null) {
                    // The ontology-annotations convention stores the CQ set inside the diffed
                    // artifact itself, so no single policy-pinned set exists for BOTH sides.
                    plan.preparationErrors.put("cqs", "the policy's ontology-annotations "
                            + "competency-question convention stores CQs inside the ontology being "
                            + "diffed, so one policy-pinned CQ set cannot be evaluated against both "
                            + "sides");
                } else {
                    plan.cqs = cqs;
                }
            } catch (RuntimeException e) {
                plan.preparationErrors.put("cqs", message(e));
            }
        }
        if (stages.contains("invariants")) {
            try {
                plan.invariants = InvariantFiles.load(policy.assets()
                        .getOrDefault("invariants", List.of()));
            } catch (RuntimeException e) {
                plan.preparationErrors.put("invariants", message(e));
            }
        }
        if (stages.contains("shacl")) {
            List<java.nio.file.Path> shapes = policy.assets().getOrDefault("shacl", List.of());
            if (shapes.isEmpty()) {
                plan.preparationErrors.put("shacl", "no policy SHACL shapes were resolved");
            } else {
                plan.shaclPaths = shapes;
            }
        }
        if (stages.contains("governance")) {
            try {
                plan.governanceRules = ProjectQcTools.governanceRules(policy.effective());
            } catch (RuntimeException e) {
                plan.preparationErrors.put("governance", message(e));
            }
        }
        return plan;
    }

    /**
     * Member-level entered/left finding-identity deltas per policy-required stage (ADR 0004
     * decision 3): each stage's result cores are evaluated against BOTH captured flattened sides
     * and their complete per-row identity sets — the same strings the QC stages publish through
     * the attribution side channels — are set-diffed. Fail-closed per stage: a side whose checks
     * cannot execute with the requested data (an inference-dependent CQ or invariant, an
     * unreadable shapes file, an expired budget) errors that stage's delta, never empties it.
     */
    private static Map<String, Object> stageDeltas(Captured captured, StagePlan plan,
            InferredDiffService.BudgetGuard guard, int limit, long deadlineNanos) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        // The asserted SPARQL snapshots are shared by the cqs/invariants/shacl stages: built
        // lazily, once per side. Governance reads the flattened OWL sides directly.
        SuiteSnapshot[] snapshots = new SuiteSnapshot[2];
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int rowLimit = Math.max(limit, 1000);
        for (String stage : plan.stages) {
            String preparationError = plan.preparationErrors.get(stage);
            if (preparationError != null) {
                out.put(stage, stageScoped(stage, erroredDelta(preparationError)));
                continue;
            }
            Set<String> leftIdentities;
            try {
                guard.check();
                leftIdentities = stageSide(stage, true, captured, plan, snapshots, today,
                        rowLimit, deadlineNanos);
            } catch (RuntimeException e) {
                out.put(stage, stageScoped(stage, erroredDelta("left side: " + message(e))));
                continue;
            }
            try {
                guard.check();
                Set<String> rightIdentities = stageSide(stage, false, captured, plan, snapshots,
                        today, rowLimit, deadlineNanos);
                Map<String, Object> delta = stageScoped(stage, new LinkedHashMap<>());
                delta.put("entered", boundedMembers(minus(rightIdentities, leftIdentities), limit));
                delta.put("left", boundedMembers(minus(leftIdentities, rightIdentities), limit));
                out.put(stage, delta);
            } catch (RuntimeException e) {
                out.put(stage, stageScoped(stage, erroredDelta("right side: " + message(e))));
            }
        }
        return out;
    }

    /**
     * Machine-readable reduced-scope disclosure for the governance delta: it covers ONLY the
     * policy's rule-driven checks ({@link PolicyGovernance} annotation/lifecycle/waiver rows,
     * evaluated per side over each flattened ontology). {@code run_project_qc}'s governance stage
     * additionally runs the intrinsic checks (iri_policy pattern, required namespaces/annotations,
     * ownership, import layering) and the module/import project checks, which need live workspace
     * context — renderings, the loaded import graph, on-disk module documents — that a foreign
     * right side does not have. The scope field keeps consumers from assuming that parity.
     */
    private static Map<String, Object> stageScoped(String stage, Map<String, Object> delta) {
        if ("governance".equals(stage)) {
            Map<String, Object> scoped = new LinkedHashMap<>();
            scoped.put("scope", "policy_rules_only");
            scoped.putAll(delta);
            delta.clear();
            delta.putAll(scoped);
        }
        return delta;
    }

    /** One side's complete gating finding-identity set for one stage. */
    private static Set<String> stageSide(String stage, boolean leftSide, Captured captured,
            StagePlan plan, SuiteSnapshot[] snapshots, LocalDate today, int rowLimit,
            long deadlineNanos) {
        OWLOntology flattened = leftSide ? captured.leftFlattened() : captured.rightFlattened();
        if ("governance".equals(stage)) {
            List<Map<String, Object>> checks = PolicyGovernance.checks(flattened,
                    java.util.Set.of(flattened), plan.governanceRules, today, rowLimit);
            TreeSet<String> identities = new TreeSet<>();
            for (Map<String, Object> check : checks) {
                ModulePolicyGovernance.drainAttributionIdentities(check, identities);
            }
            return identities;
        }
        int index = leftSide ? 0 : 1;
        SuiteSnapshot snapshot = snapshots[index];
        if (snapshot == null) {
            snapshot = SuiteSnapshot.captureIsolated(flattened, java.util.Set.of(flattened),
                    captured.prefixes()).withInferenceError("semantic_diff stage deltas evaluate "
                            + "each side's asserted graph; an inference-dependent check cannot run "
                            + "soundly here and fails closed");
            snapshots[index] = snapshot;
        }
        long remainingMs = Math.max(1L,
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        switch (stage) {
            case "cqs": {
                Map<String, Object> run = CqRunner.run(snapshot, plan.cqs, rowLimit, remainingMs,
                        CqRunner.FAIL_ON_ANY);
                int blocked = QcSuiteTools.countCqErrors(run.get("questions"))
                        + QcSuiteTools.countInferenceCaveats(run.get("questions"));
                if (blocked > 0) {
                    throw new ToolArgException(blocked + " competency question(s) could not "
                            + "execute with their requested data");
                }
                return new TreeSet<>(QcSuiteTools.resultIdentities(run.get("questions"), "pass"));
            }
            case "invariants": {
                Map<String, Object> run = Invariants.run(snapshot, plan.invariants, rowLimit,
                        remainingMs, "error");
                int blocked = ((Number) run.get("errors")).intValue()
                        + QcSuiteTools.countInferenceCaveats(run.get("invariants"));
                if (blocked > 0) {
                    throw new ToolArgException(blocked + " invariant(s) could not execute with "
                            + "complete data");
                }
                return new TreeSet<>(QcSuiteTools.resultIdentities(run.get("invariants"),
                        "violated"));
            }
            case "shacl": {
                TreeSet<String> identities = new TreeSet<>();
                ShaclTools.validate(snapshot.assertedTurtle(), plan.shaclPaths, rowLimit,
                        remainingMs, identities);
                return identities;
            }
            default:
                throw new IllegalStateException("unknown stage delta: " + stage);
        }
    }

    private static Map<String, Object> erroredDelta(String message) {
        Map<String, Object> errored = new LinkedHashMap<>();
        errored.put("error", message);
        return errored;
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static TreeSet<String> minus(Set<String> base, Set<String> remove) {
        TreeSet<String> out = new TreeSet<>(base);
        out.removeAll(remove);
        return out;
    }

    private static Map<String, Object> boundedMembers(TreeSet<String> values, int limit) {
        List<String> items = new ArrayList<>();
        for (String value : values) {
            if (items.size() >= Math.max(0, limit)) {
                break;
            }
            items.add(value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", values.size());
        out.put("items", items);
        if (values.size() > items.size()) {
            out.put("truncated", true);
        }
        return out;
    }

    /** The shared unavailable shape for both policy-driven categories. */
    static Map<String, Object> unavailable(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("reason", reason);
        return out;
    }

    /**
     * The module-ownership policy delta (ADR 0004 decision 4). The owner of a term is computed
     * from its IRI and the policy's {@code modules[].owned_namespaces} alone, using the released
     * boundary-aware most-specific-namespace matcher — no reasoner. Because the matcher is
     * IRI-deterministic under one policy, an owner can differ between sides only through per-side
     * signature membership: exactly how a term legitimately moves between two policy-conformant
     * modules (its IRI leaves one owned namespace and enters another), which produces no
     * governance violation on either side.
     */
    static Map<String, Object> moduleOwnership(Set<String> leftSignature,
            Set<String> rightSignature, List<Map<String, Object>> modules, int limit) {
        Map<String, TreeSet<String>> owners = new LinkedHashMap<>();
        for (Map<String, Object> module : modules) {
            Object label = module.get("ontology_iri");
            Object namespaces = module.get("owned_namespaces");
            if (!(label instanceof String) || !(namespaces instanceof List<?>)) {
                continue;
            }
            for (Object namespace : (List<?>) namespaces) {
                if (namespace instanceof String) {
                    owners.computeIfAbsent((String) namespace, key -> new TreeSet<>())
                            .add((String) label);
                }
            }
        }
        if (owners.isEmpty()) {
            return unavailable("no project policy with modules[].owned_namespaces was loaded");
        }
        TreeSet<String> union = new TreeSet<>(leftSignature);
        union.addAll(rightSignature);
        int total = 0;
        List<Map<String, Object>> changes = new ArrayList<>();
        int cap = Math.max(0, limit);
        for (String iri : union) {
            String was = leftSignature.contains(iri) ? owningModules(iri, owners) : null;
            String now = rightSignature.contains(iri) ? owningModules(iri, owners) : null;
            if (java.util.Objects.equals(was, now)) {
                continue;
            }
            total++;
            if (changes.size() < cap) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("iri", iri);
                row.put("was_module", was);
                row.put("now_module", now);
                changes.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("count", total);
        out.put("changes", changes);
        if (total > changes.size()) {
            out.put("truncated", true);
        }
        return out;
    }

    /** Owning module label(s) for a term, or null when no owned namespace matches. */
    private static String owningModules(String iri, Map<String, TreeSet<String>> owners) {
        String namespace = ModulePolicyGovernance.mostSpecificOwnedNamespace(iri, owners.keySet());
        return namespace == null ? null : String.join(", ", owners.get(namespace));
    }

    /**
     * The single result-level compatibility block (ADR 0004 decisions 7-8): starts from the
     * asserted classification when present (its caveat strings preserved verbatim), and fails
     * closed on inferred evidence — a consistency transition, newly unsatisfiable classes, an
     * errored inferred section, or any errored category forces {@code potentially_breaking} with a
     * caveat naming the missing evidence.
     */
    static Map<String, Object> mergedCompatibility(Map<String, Object> assertedCompatibility,
            Map<String, Object> inferred) {
        return mergedCompatibility(assertedCompatibility, inferred, null);
    }

    /**
     * As above, additionally failing closed on errored stage deltas: an evaluated stage whose
     * delta errored (or a wholly errored {@code stage_deltas} section) forces
     * {@code potentially_breaking} with a caveat naming the stage — a verdict is never computed
     * over stage evidence that failed to materialize.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mergedCompatibility(Map<String, Object> assertedCompatibility,
            Map<String, Object> inferred, Map<String, Object> stageDeltas) {
        Map<String, Object> merged = new LinkedHashMap<>();
        String classification = "non_breaking";
        List<String> caveats = new ArrayList<>();
        if (assertedCompatibility != null) {
            classification = String.valueOf(assertedCompatibility.get("classification"));
            merged.putAll(assertedCompatibility);
            Object caveat = assertedCompatibility.get("caveat");
            if (caveat != null) {
                // Preserved verbatim; inferred caveats append after it (ADR 0004 decision 7).
                caveats.add(String.valueOf(caveat));
            }
        } else {
            merged.put("policy_driven", false);
        }

        List<String> forced = new ArrayList<>();
        if (inferred.containsKey("error")) {
            forced.add("the inferred section errored (" + inferred.get("error") + ")");
        }
        Object erroredCategories = inferred.get("errored_categories");
        if (erroredCategories instanceof List<?> errored && !errored.isEmpty()) {
            forced.add("inferred categories " + errored + " errored");
        }
        if (inferred.get("consistency") instanceof Map<?, ?> consistency
                && Boolean.TRUE.equals(consistency.get("changed"))) {
            forced.add("the consistency verdict changed");
        }
        if (Boolean.TRUE.equals(inferred.get("categories_suppressed"))) {
            forced.add("member-level inferred categories were suppressed by an inconsistency");
        }
        if (inferred.get("satisfiability") instanceof Map<?, ?> satisfiability
                && satisfiability.get("newly_unsatisfiable") instanceof Map<?, ?> newly
                && newly.get("count") instanceof Integer count && count > 0) {
            forced.add(count + " class(es) became unsatisfiable");
        }
        if (stageDeltas != null) {
            if (stageDeltas.containsKey("error")) {
                forced.add("the policy stage deltas errored (" + stageDeltas.get("error") + ")");
            } else {
                for (Map.Entry<String, Object> entry : stageDeltas.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> stage && stage.containsKey("error")) {
                        forced.add("the '" + entry.getKey() + "' stage delta errored ("
                                + stage.get("error") + ")");
                    }
                }
            }
        }
        if (!forced.isEmpty()) {
            classification = "potentially_breaking";
            caveats.add("Inferred evidence forces potentially_breaking: "
                    + String.join("; ", forced) + ".");
        }
        merged.put("classification", classification);
        if (!caveats.isEmpty()) {
            merged.put("caveat", String.join(" ", caveats));
        }
        return merged;
    }
}
