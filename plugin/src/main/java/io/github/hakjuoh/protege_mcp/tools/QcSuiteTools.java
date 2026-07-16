package io.github.hakjuoh.protege_mcp.tools;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.profiles.Profiles;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.RdfDatasetFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.RdfDatasetFingerprints;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code run_qc_suite} (F5): one umbrella gate over the already-shipping QC cores — the reasoner verdict,
 * OWL 2 profile conformance, the structural {@code validate_ontology} checks, the {@code verify_ontology}
 * invariants, and the competency-question suite — collapsed into a single {@code gate}. Every stage,
 * including reasoning and inferred-query materialization, evaluates one isolated point-in-time snapshot
 * using a point-in-time capture of Protégé's selected reasoner configuration. It composes the plain-data
 * cores directly (the established idiom:
 * {@code validate_ontology} composes {@code reasonerVerdict}, the curation macros compose {@code
 * WriteTools} cores), never the MCP tool lambdas.
 *
 * <p>Graceful-skip is the contract: a stage whose backing data is absent — no selected reasoner, no
 * invariants supplied, no competency questions stored, SHACL not built — returns {@code ran:false} with a
 * reason, never a thrown exception. The overall gate is the worst <em>ran</em> stage versus {@code fail_on}.
 */
public final class QcSuiteTools {

    private QcSuiteTools() {
    }

    private static final String INTEROPERABILITY = "interoperability";
    private static final String REASONER = "reasoner";
    private static final String PROFILE = "profile";
    private static final String GOVERNANCE = "governance";
    private static final String STRUCTURAL = "structural";
    private static final String INVARIANTS = "invariants";
    private static final String CQS = "cqs";
    private static final String SHACL = "shacl";

    private static final List<String> ALL_STAGES = Arrays.asList(
            INTEROPERABILITY, REASONER, PROFILE, GOVERNANCE, STRUCTURAL, INVARIANTS, CQS, SHACL);
    private static final List<String> DEFAULT_STAGES = Arrays.asList(REASONER, PROFILE, STRUCTURAL);

    static final String PASS = "pass";
    static final String INFO = "info";
    static final String WARN = "warn";
    static final String FAIL = "fail";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("run_qc_suite",
                "Run an aggregate quality-control gate over the active ontology and collapse it to ONE "
                        + "verdict. Composable stages (default reasoner + profile + structural): "
                        + "'interoperability' (project-policy RO-Crate contract plus W3C RDFC-1.0 "
                        + "root-dataset fingerprint), 'reasoner' "
                        + "(consistency + no unsatisfiable classes), 'profile' (OWL 2 profile conformance, "
                        + "owl_profile default DL), 'structural' (validate_ontology's modelling-quality "
                        + "checks), 'governance' (IRI/annotation/import-layering project rules), "
                        + "'invariants' (verify_ontology-style SPARQL invariants — pass them in "
                        + "'invariants'), 'cqs' (the competency-question suite), and 'shacl' (validate "
                        + "against the SHACL shapes supplied in 'shacl_shapes'/'shacl_shapes_path'). "
                        + "Every stage, including reasoning and inferred queries, runs against one isolated "
                        + "shared snapshot. A stage whose backing data is absent "
                        + "(no selected reasoner, no invariants, no CQs, no SHACL shapes) is "
                        + "skipped with "
                        + "a reason in legacy mode. Pass policy_path to run the strict project-policy "
                        + "workflow, where any missing required stage is an error. The overall 'gate' fails "
                        + "when the worst stage reaches 'fail_on' (none | warn | error, default error).",
                suiteSchema(),
                (ex, req) -> Tools.guard(() -> runSuite(ctx, Tools.args(req))));
    }

    // ================================================================== orchestration

    private static CallToolResult runSuite(ToolContext ctx, Map<String, Object> a) {
        if (Tools.optString(a, "policy_path") != null) {
            return ProjectQcTools.run(ctx, a, false);
        }
        RunConfig config = RunConfig.legacy(a);
        SuiteExecution execution = execute(ctx, config);
        boolean strictMissing = Tools.optBool(a, "error_on_missing_required", false);
        if (strictMissing || !config.requiredStages.isEmpty()) {
            Set<String> required = legacyRequiredStages(config, strictMissing);
            return Tools.ok(strictResult(execution, required, config.failOn,
                    0, null, false));
        }
        return aggregate(execution.results, config.failOn);
    }

    /** Explicit required_stages wins; strict mode promotes all requested stages only as a fallback. */
    static Set<String> legacyRequiredStages(RunConfig config, boolean strictMissing) {
        return config.requiredStages.isEmpty() && strictMissing
                ? config.stages : config.requiredStages;
    }

    /** Shared orchestration used by legacy run_qc_suite and strict run_project_qc. */
    static SuiteExecution execute(ToolContext ctx, RunConfig config) {
        return execute(ctx, config, Collections.emptyList());
    }

    /** Execute the existing isolated QC pipeline after applying an exact private preflight delta. */
    static SuiteExecution execute(ToolContext ctx, RunConfig config, List<NormalizedChange> changes) {
        Phase1 p1 = ctx.access().compute(mm -> phase1(mm, config, changes));
        boolean snapshotConsistent = true;
        String preconditionError = null;
        if (config.projectMode && config.requiredOntologyIri != null
                && !config.requiredOntologyIri.equals(p1.activeOntologyIri)) {
            preconditionError = "active ontology does not match project policy: expected '"
                    + config.requiredOntologyIri + "', found '" + p1.activeOntologyIri + "'";
        }
        ReasoningOutcome reasoning = runIsolatedReasoner(p1, config);
        p1.validationSnapshot = reasoning.snapshot;
        List<StageResult> results = new ArrayList<>();
        if (config.stages.contains(INTEROPERABILITY)) {
            results.add(interoperabilityStage(p1.validationSnapshot, config.interoperability));
        }
        // reasoning.stage is non-null when the stage was scheduled, and ALSO when an inferences-only
        // run (needInferred without the reasoner stage) produced a project-mode gate error — e.g. SWRL
        // rules the selected reasoner silently ignores. That error must surface and gate even though
        // 'reasoner' is not a configured stage: dropping it would let include_inferred invariants/CQs
        // false-pass over a rule-blind inferred snapshot. A successful inferences-only run yields no
        // stage result, so no phantom reasoner stage is ever added.
        if (reasoning.stage != null) {
            results.add(reasoning.stage);
        }
        if (config.stages.contains(PROFILE)) {
            results.add(profileStage(p1, config.profileName, config.limit));
        }
        if (config.stages.contains(GOVERNANCE)) {
            results.add(governanceStage(p1.validationSnapshot, config.iriPattern,
                    config.requiredNamespaces, config.requiredAnnotations, config.checkOwnership,
                    config.policyGovernance, config.limit));
        }
        if (config.stages.contains(STRUCTURAL)) {
            results.add(structuralStage(p1.validationSnapshot, config.disabledStructural,
                    config.structuralSeverity, config.projectMode));
        }
        int rowLimit = Math.max(config.limit, 1000);
        if (config.stages.contains(INVARIANTS)) {
            StageResult invariantsResult = invariantsStage(p1.validationSnapshot.queries(), config.invariants,
                    rowLimit, config.timeout);
            if (config.projectMode && invariantsResult.summary != null
                    && (num(invariantsResult.summary.get("errors")) > 0
                            || num(invariantsResult.summary.get("inference_caveats")) > 0)) {
                // Fail closed exactly like the CQ stage: an include_inferred invariant that ran over a
                // TRUNCATED inferred snapshot could miss an inference-only violation and false-pass, so
                // in project mode a truncation caveat is a gate error, not a silent pass.
                invariantsResult = StageResult.errored(INVARIANTS,
                        "one or more required invariants could not execute with complete inferred data");
            }
            results.add(invariantsResult);
        }
        if (config.stages.contains(CQS)) {
            StageResult cqResult = cqsStage(p1.validationSnapshot.queries(), p1.cqs, rowLimit, config.timeout);
            if (config.projectMode && cqResult.summary != null
                    && (num(cqResult.summary.get("errors")) > 0
                            || num(cqResult.summary.get("inference_caveats")) > 0)) {
                cqResult = StageResult.errored(CQS,
                        "one or more required competency questions could not execute with their requested data");
            }
            results.add(cqResult);
        }
        if (config.stages.contains(SHACL)) {
            results.add(config.shaclPaths.isEmpty()
                    ? shaclStage(hasText(config.shaclShapes, config.shaclShapesPath)
                                    ? p1.validationSnapshot.assertedTurtle() : null, config.shaclShapes,
                            config.shaclShapesPath, rowLimit, config.timeout)
                    : shaclStage(p1.validationSnapshot.assertedTurtle(), config.shaclPaths,
                            rowLimit, config.timeout));
        }
        List<String> isolatedStages = results.stream()
                .filter(result -> result.ran && !result.executionError)
                .map(result -> result.stage).toList();
        return new SuiteExecution(results, p1.fingerprint, snapshotConsistent,
                p1.selectedReasoner, preconditionError,
                p1.validationSnapshot == null ? "none" : "isolated", isolatedStages,
                p1.validationSnapshot != null || config.stages.isEmpty(), p1.closureFingerprint,
                p1.missingImports);
    }

    /** Everything phase 1 gathers on the model thread. */
    private static final class Phase1 {
        List<CompetencyQuestion> cqs;
        IsolatedValidationSnapshot validationSnapshot;
        OntologyFingerprint fingerprint;
        String closureFingerprint;
        String selectedReasoner;
        String activeOntologyIri;
        IsolatedReasonerSpec reasonerSpec;
        String reasonerCaptureError;
        boolean needInferred;
        List<Map<String, Object>> missingImports = Collections.emptyList();
    }

    private static Phase1 phase1(OWLModelManager mm, RunConfig config,
            List<NormalizedChange> changes) {
        Phase1 p1 = new Phase1();
        p1.selectedReasoner = ProjectPolicyTools.selectedReasoner(mm);
        p1.activeOntologyIri = mm.getActiveOntology().getOntologyID().getOntologyIRI().orNull() == null
                ? null : mm.getActiveOntology().getOntologyID().getOntologyIRI().orNull().toString();
        p1.needInferred = (config.policyCqs != null
                && config.policyCqs.stream().anyMatch(cq -> cq.includeInferred))
                || config.invariants.stream().anyMatch(i -> i.includeInferred);
        if (config.stages.contains(CQS)) {
            // Project mode with a resolved policy CQ asset supplies policyCqs directly. Project mode
            // with the ontology-annotations convention leaves policyCqs null but MUST read only the
            // annotations store — never aggregate stray robot-dir/sidecar CQs sitting beside the
            // document (outside the policy's declared, project-root-confined assets). Legacy mode with
            // no policy keeps discovering across every convention.
            p1.cqs = config.policyCqs != null ? config.policyCqs
                    : config.projectMode ? loadAnnotationCqs(mm) : loadCqs(mm);
            if (p1.cqs.stream().anyMatch(cq -> cq.includeInferred)) {
                p1.needInferred = true;
            }
        }
        if (config.stages.contains(REASONER) || p1.needInferred) {
            // A successful manager capture with no current factory is Protégé's None selection, not
            // a capture error: nothing is selected, so the stage skips. Every lookup/capture exception
            // is an error — a damaged live registry must never be reinterpreted as a deliberate None
            // selection based on its exception subtype.
            OWLReasonerManager reasonerManager = null;
            try {
                reasonerManager = mm.getOWLReasonerManager();
            } catch (RuntimeException e) {
                p1.reasonerCaptureError = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (reasonerManager == null && p1.reasonerCaptureError == null) {
                p1.reasonerCaptureError = "reasoner manager lookup returned null";
            }
            if (reasonerManager != null) {
                try {
                    p1.reasonerSpec = IsolatedReasonerSpec.capture(reasonerManager);
                    if (p1.selectedReasoner == null && p1.reasonerSpec != null) {
                        p1.selectedReasoner = p1.reasonerSpec.reasonerName();
                    }
                } catch (RuntimeException e) {
                    p1.reasonerCaptureError = e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
        }
        if (config.projectMode) {
            // Capture the import-closure state in the SAME model-thread hop as the snapshot below, so a
            // concurrent edit cannot resolve a missing import between QC and a fail-closed import check.
            p1.missingImports = ImportTools.analyze(mm.getActiveOntology()).missingImports;
        }
        boolean needSnapshot = !config.stages.isEmpty();
        if (needSnapshot) {
            p1.validationSnapshot = IsolatedValidationSnapshot.capture(mm);
            p1.validationSnapshot = p1.validationSnapshot.withChanges(changes);
            p1.fingerprint = p1.validationSnapshot.fingerprint();
            p1.closureFingerprint = p1.validationSnapshot.closureFingerprint();
        } else {
            p1.fingerprint = OntologyFingerprints.compute(mm.getActiveOntology());
            p1.closureFingerprint = IsolatedValidationSnapshot.closureFingerprint(
                    mm.getActiveOntology().getImportsClosure());
        }
        return p1;
    }

    /**
     * Run logical validation and optional inferred materialization over the captured flattened closure.
     * No live reasoner state, ontology, import, renderer, or Undo history is touched after phase 1.
     */
    private static ReasoningOutcome runIsolatedReasoner(Phase1 p1, RunConfig config) {
        boolean stageRequested = config.stages.contains(REASONER);
        boolean reasoningNeeded = stageRequested || p1.needInferred;
        if (!reasoningNeeded) {
            return new ReasoningOutcome(null, p1.validationSnapshot);
        }
        if (p1.reasonerCaptureError != null) {
            return reasoningUnavailable(p1, stageRequested,
                    "could not capture selected reasoner configuration: " + p1.reasonerCaptureError);
        }
        if (p1.reasonerSpec == null) {
            return reasoningUnavailable(p1, stageRequested, "no reasoner is selected in Protégé.");
        }
        if (config.projectMode && config.requiredReasoner != null
                && !reasonerMatches(config.requiredReasoner, p1.reasonerSpec, p1.selectedReasoner)) {
            return reasoningUnavailable(p1, stageRequested, "policy requires reasoner '"
                    + config.requiredReasoner + "' but '" + p1.reasonerSpec.reasonerName()
                    + "' is selected");
        }

        AtomicReference<OWLReasoner> running = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        FutureTask<ReasoningOutcome> task = new FutureTask<>(
                () -> computeIsolatedReasoner(p1, config, stageRequested, running, cancelled));
        Thread worker = new Thread(task, "protege-mcp-isolated-qc-reasoner");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(Math.max(config.timeout, 1), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Signal cancellation BEFORE disposing: if the timeout fired while the worker was still
            // inside the reasoner factory (no reference yet), the flag makes it abandon the now-pointless
            // classification and dispose the freshly built reasoner instead of running it to completion.
            cancelled.set(true);
            task.cancel(true);
            stopPrivateReasoner(running);
            return reasoningUnavailable(p1, stageRequested, "isolated classification timed out after "
                    + config.timeout + " ms; no live state or result was applied");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            task.cancel(true);
            stopPrivateReasoner(running);
            return reasoningUnavailable(p1, stageRequested,
                    "isolated classification was interrupted; no result was applied");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return reasoningUnavailable(p1, stageRequested, "isolated classification failed: "
                    + (cause == null || cause.getMessage() == null
                            ? (cause == null ? e.getClass().getSimpleName()
                                    : cause.getClass().getSimpleName())
                            : cause.getMessage()));
        }
    }

    private static ReasoningOutcome computeIsolatedReasoner(Phase1 p1, RunConfig config,
            boolean stageRequested, AtomicReference<OWLReasoner> running,
            java.util.concurrent.atomic.AtomicBoolean cancelled) {
        OWLReasoner reasoner = null;
        IsolatedValidationSnapshot snapshot = p1.validationSnapshot;
        try {
            reasoner = p1.reasonerSpec.create(snapshot.queries().assertedOntology());
            running.set(reasoner);
            if (cancelled.get()) {
                // The caller already timed out/was interrupted while the factory was still building the
                // reasoner. Abandon classification now; the finally block (or the timeout path's
                // stopPrivateReasoner, whichever wins the CAS) disposes the reasoner exactly once.
                return reasoningUnavailable(p1, stageRequested,
                        "isolated classification was cancelled before it started");
            }
            boolean consistent = reasoner.isConsistent();
            int unsat = 0;
            Map<String, Object> unsatisfiable = Map.of("count", 0, "items", List.of());
            if (consistent) {
                Set<org.semanticweb.owlapi.model.OWLClass> classes =
                        reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom();
                unsat = classes.size();
                unsatisfiable = Tools.entityList(snapshot.modelManager(), classes, config.limit);
            }
            if (p1.needInferred) {
                snapshot = consistent
                        ? snapshot.withInferences(reasoner)
                        : snapshot.withInferenceError("The isolated ontology is inconsistent; inferred "
                                + "query data cannot be materialized soundly.");
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "ISOLATED");
            summary.put("consistent", consistent);
            summary.put("unsatisfiable_count", unsat);
            summary.put("unsatisfiable_classes", unsatisfiable);
            summary.put("reasoner_configuration",
                    p1.reasonerSpec.metadata(reasoner));
            String swrl = ReasonerTools.swrlIgnoredWarning(snapshot.closure(),
                    p1.reasonerSpec.reasonerName(), p1.reasonerSpec.reasonerId());
            if (swrl != null) {
                summary.put("warning", swrl);
                if (config.projectMode) {
                    return new ReasoningOutcome(StageResult.errored(REASONER, swrl), snapshot);
                }
            }
            StageResult stage = stageRequested
                    ? new StageResult(REASONER, true,
                            consistent && unsat == 0 ? PASS : FAIL, summary, null)
                    : null;
            return new ReasoningOutcome(stage, snapshot);
        } catch (RuntimeException e) {
            String message = "isolated classification failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return reasoningUnavailable(p1, stageRequested, message);
        } finally {
            if (reasoner != null && running.compareAndSet(reasoner, null)) {
                dispose(reasoner);
            }
        }
    }

    /** Transfer timeout cleanup ownership away from the worker before interrupting/disposal. */
    private static void stopPrivateReasoner(AtomicReference<OWLReasoner> running) {
        OWLReasoner reasoner = running.getAndSet(null);
        if (reasoner == null) {
            return;
        }
        try {
            reasoner.interrupt();
        } catch (RuntimeException ignored) {
            // Continue to dispose: some third-party reasoners do not implement cooperative interruption.
        }
        dispose(reasoner);
    }

    private static void dispose(OWLReasoner reasoner) {
        try {
            reasoner.dispose();
        } catch (RuntimeException ignored) {
            // A broken third-party dispose must not hide the already-computed gate/timeout result.
        }
    }

    private static ReasoningOutcome reasoningUnavailable(Phase1 p1, boolean stageRequested,
            String reason) {
        IsolatedValidationSnapshot snapshot = p1.needInferred
                ? p1.validationSnapshot.withInferenceError(reason) : p1.validationSnapshot;
        StageResult stage = stageRequested
                ? (p1.reasonerSpec == null && p1.reasonerCaptureError == null
                        ? StageResult.skipped(REASONER, reason)
                        : StageResult.errored(REASONER, reason))
                : null;
        return new ReasoningOutcome(stage, snapshot);
    }

    private static boolean reasonerMatches(String required, IsolatedReasonerSpec spec,
            String selectedName) {
        return required.equalsIgnoreCase(spec.reasonerName())
                || (spec.reasonerId() != null && required.equalsIgnoreCase(spec.reasonerId()))
                || (selectedName != null && required.equalsIgnoreCase(selectedName));
    }

    private record ReasoningOutcome(StageResult stage, IsolatedValidationSnapshot snapshot) { }

    static StageResult interoperabilityStage(IsolatedValidationSnapshot snapshot,
            InteroperabilityConfig interoperability) {
        if (interoperability == null) {
            return StageResult.skipped(INTEROPERABILITY,
                    "interoperability requires a validated project policy");
        }
        try {
            if (!RdfDatasetFingerprints.CANONICALIZATION_ALGORITHM.equals(
                    interoperability.canonicalizationAlgorithm)
                    || !RdfDatasetFingerprints.HASH_ALGORITHM.equals(interoperability.hashAlgorithm)
                    || !RdfDatasetFingerprints.SCOPE.equals(interoperability.scope)) {
                return StageResult.errored(INTEROPERABILITY,
                        "unsupported RDF dataset identity contract");
            }
            RdfDatasetFingerprint fingerprint = RdfDatasetFingerprints.compute(snapshot.active(),
                    snapshot.activeImportIris(), interoperability.timeoutMs);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("profile", interoperability.profile);
            summary.put("additional_profiles", interoperability.additionalProfiles);
            summary.put("ro_crate_format", interoperability.roCrateFormat);
            summary.put("manifest_path", interoperability.manifestPath);
            summary.put("root_artifact", interoperability.rootArtifact);
            summary.put("canonicalization_algorithm", fingerprint.canonicalizationAlgorithm());
            summary.put("hash_algorithm", fingerprint.hashAlgorithm());
            summary.put("scope", fingerprint.scope());
            summary.put("rdf_dataset_fingerprint", fingerprint.rdfDatasetFingerprint());
            summary.put("canonical_nquads_bytes", fingerprint.canonicalNQuadsBytes());
            return new StageResult(INTEROPERABILITY, true, PASS, summary, null);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return StageResult.errored(INTEROPERABILITY,
                    "RDF dataset canonicalization failed: " + message);
        }
    }

    // ================================================================== stages

    /** Legacy live-reasoner verdict retained for direct method-level compatibility tests. */
    static StageResult reasonerStage(OWLModelManager mm, int limit) {
        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, limit);
        if (!Boolean.TRUE.equals(v.get("results_available"))) {
            String status = String.valueOf(v.get("status"));
            // No reasoner selected at all is a legitimate skip (the user opted out of reasoning). But a
            // reasoner that is OUT_OF_SYNC, or was reset to uninitialized because classification failed,
            // must NOT silently pass the gate — surface it as a ran WARN so fail_on=warn trips and the
            // JSON is never falsely reassuring about consistency / unsatisfiable classes.
            if ("NO_REASONER_FACTORY_CHOSEN".equals(status)) {
                return StageResult.skipped(REASONER, "no reasoner is selected in Protégé.");
            }
            Map<String, Object> stale = new LinkedHashMap<>();
            stale.put("status", v.get("status"));
            stale.put("reason", "no current reasoner results — the reasoner is not classified/in-sync "
                    + "(run_reasoner was not run, or the last classification failed; see the Protégé log).");
            return new StageResult(REASONER, true, WARN, stale, null);
        }
        Object consistent = v.get("consistent");
        int unsat = v.get("unsatisfiable_count") instanceof Number
                ? ((Number) v.get("unsatisfiable_count")).intValue() : 0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", v.get("status"));
        summary.put("consistent", consistent);
        summary.put("unsatisfiable_count", unsat);
        // Like the cqs stage's caveats: surfaced, deliberately not gated — an ELK + SWRL setup can
        // be intentional, but a pass must never read as "the rules were checked".
        String swrl = ReasonerTools.swrlIgnoredWarning(mm);
        if (swrl != null) {
            summary.put("warning", swrl);
        }
        String verdict = (Boolean.TRUE.equals(consistent) && unsat == 0) ? PASS : FAIL;
        return new StageResult(REASONER, true, verdict, summary, null);
    }

    static StageResult structuralStage(OWLModelManager mm) {
        return structuralStage(mm, Collections.emptySet(), Collections.emptyMap(), false);
    }

    static StageResult structuralStage(OWLModelManager mm, Set<String> disabled,
            Map<String, String> severityOverrides, boolean surfaceInfo) {
        Set<OWLOntology> scope = Collections.singleton(mm.getActiveOntology());
        return structuralStage(ValidationTools.analyze(scope), disabled, severityOverrides, surfaceInfo);
    }

    static StageResult structuralStage(IsolatedValidationSnapshot snapshot, Set<String> disabled,
            Map<String, String> severityOverrides, boolean surfaceInfo) {
        Set<OWLOntology> scope = Collections.singleton(snapshot.active());
        return structuralStage(ValidationTools.analyze(scope, snapshot.closure()), disabled,
                severityOverrides, surfaceInfo);
    }

    private static StageResult structuralStage(List<ValidationTools.Finding> findings, Set<String> disabled,
            Map<String, String> severityOverrides, boolean surfaceInfo) {
        int total = 0;
        int warnings = 0;   // only warning-severity smells gate; info findings are reported, not gated
        int errors = 0;
        List<Map<String, Object>> checks = new ArrayList<>();
        for (ValidationTools.Finding f : findings) {
            if (disabled.contains(f.id)) {
                continue;
            }
            String severity = severityOverrides.getOrDefault(f.id, f.severity);
            total += f.count();
            if (f.count() > 0) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", f.id);
                c.put("severity", severity);
                c.put("count", f.count());
                checks.add(c);
                if ("error".equals(severity)) {
                    errors += f.count();
                } else if ("warning".equals(severity) || "warn".equals(severity)) {
                    warnings += f.count();
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_issues", total);
        summary.put("warnings", warnings);
        summary.put("errors", errors);
        summary.put("checks", checks);
        return new StageResult(STRUCTURAL, true,
                errors > 0 ? FAIL : warnings > 0 ? WARN : surfaceInfo && total > 0 ? INFO : PASS,
                summary, null);
    }

    static StageResult governanceStage(OWLModelManager mm, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, int limit) {
        List<Map<String, Object>> checks = GovernanceTools.policyChecks(mm, iriPattern,
                requiredNamespaces, requiredAnnotations, checkOwnership, limit);
        return governanceStage(checks);
    }

    static StageResult governanceStage(IsolatedValidationSnapshot snapshot, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, int limit) {
        return governanceStage(snapshot, iriPattern, requiredNamespaces, requiredAnnotations,
                checkOwnership, PolicyGovernance.Rules.empty(), limit);
    }

    static StageResult governanceStage(IsolatedValidationSnapshot snapshot, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, PolicyGovernance.Rules rules, int limit) {
        List<Map<String, Object>> checks = GovernanceTools.policyChecks(snapshot.modelManager(),
                snapshot.active(), snapshot.closure(), iriPattern, requiredNamespaces,
                requiredAnnotations, checkOwnership, limit);
        checks.addAll(PolicyGovernance.checks(snapshot.active(), snapshot.closure(), rules,
                LocalDate.now(ZoneOffset.UTC), limit));
        return governanceStage(checks);
    }

    private static StageResult governanceStage(List<Map<String, Object>> checks) {
        int warnings = 0;
        int errors = 0;
        for (Map<String, Object> check : checks) {
            int count = num(check.get("count"));
            String severity = String.valueOf(check.get("severity"));
            if ("error".equals(severity)) {
                errors += count;
            } else if ("warning".equals(severity) || "warn".equals(severity)) {
                warnings += count;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("violations", warnings + errors);
        summary.put("warnings", warnings);
        summary.put("errors", errors);
        summary.put("checks", checks);
        return new StageResult(GOVERNANCE, true, errors > 0 ? FAIL : warnings > 0 ? WARN : PASS,
                summary, null);
    }

    private static StageResult profileStage(Phase1 p1, String profileName, int limit) {
        Profiles profile = GovernanceTools.profileFor(profileName);
        if (profile == null || p1.validationSnapshot == null) {
            return StageResult.skipped(PROFILE, "profile check skipped (owl_profile=" + profileName + ").");
        }
        Map<String, Object> check = GovernanceTools.profileCheck(profile, profileName,
                p1.validationSnapshot.queries().assertedOntology(),
                p1.validationSnapshot.active().getAxioms(),
                p1.validationSnapshot.active().getAnnotations(), limit);
        // Gate on OWNED conformance: a module that merely imports a non-DL upstream (e.g. BFO) must not fail
        // this stage — only its OWN axioms leaving the profile should. Imported violations are context only.
        boolean ownedInProfile = Boolean.TRUE.equals(check.get("owned_in_profile"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", profileName);
        summary.put("in_profile", check.get("in_profile"));
        summary.put("owned_in_profile", ownedInProfile);
        summary.put("violations", check.get("count"));
        if (check.get("imported_violations") != null) {
            summary.put("imported_violations", check.get("imported_violations"));
        }
        return new StageResult(PROFILE, true, ownedInProfile ? PASS : FAIL, summary, null);
    }

    static StageResult invariantsStage(SuiteSnapshot snap, List<Invariants.Invariant> invariants,
            int limit, long timeout) {
        if (invariants.isEmpty()) {
            return StageResult.skipped(INVARIANTS, "no invariants supplied — pass an 'invariants' array "
                    + "(same shape as verify_ontology's queries[]).");
        }
        // Run at an "error" gate purely to read the worst severity as a 3-level stage verdict; the SUITE's
        // own fail_on is applied by aggregate() over that verdict's level (never passed down here).
        Map<String, Object> run = Invariants.run(snap, invariants, limit, timeout, "error");
        int violations = ((Number) run.get("violations")).intValue();
        int errors = ((Number) run.get("errors")).intValue();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checked", run.get("checked"));
        summary.put("violations", violations);
        summary.put("errors", errors);   // checks that could not run / were rejected — fail-closed
        // An include_inferred invariant that ran but over a truncated inferred snapshot is surfaced
        // (never silently gated to PASS): project mode escalates this to a stage error above.
        int inferenceCaveats = countInferenceCaveats(run.get("invariants"));
        if (inferenceCaveats > 0) {
            summary.put("inference_caveats", inferenceCaveats);
        }
        // run.gate=="fail" ⇔ an ERROR-severity issue is present (a violation OR a fail-closed errored check —
        // Invariants.run's worst folds both) → the stage FAILs. A sub-error issue (a warn/info violation OR
        // a warn/info errored check) must still surface as WARN so the suite's fail_on=warn can trip: a
        // check that could not run is NEVER silently dropped to PASS. aggregate() maps this level vs fail_on.
        int issueLevel = invariantIssueLevel(run.get("invariants"));
        String verdict = issueLevel >= 3 ? FAIL : issueLevel == 2 ? WARN : issueLevel == 1 ? INFO : PASS;
        return new StageResult(INVARIANTS, true, verdict, summary, null);
    }

    @SuppressWarnings("unchecked")
    private static int invariantIssueLevel(Object value) {
        int worst = 0;
        if (!(value instanceof List)) {
            return worst;
        }
        for (Object row : (List<Object>) value) {
            if (!(row instanceof Map)) {
                continue;
            }
            Map<String, Object> invariant = (Map<String, Object>) row;
            if (!Boolean.TRUE.equals(invariant.get("violated")) && invariant.get("error") == null) {
                continue;
            }
            String severity = String.valueOf(invariant.get("severity"));
            worst = Math.max(worst, "error".equals(severity) ? 3 : "warn".equals(severity) ? 2 : 1);
        }
        return worst;
    }

    static StageResult cqsStage(SuiteSnapshot snap, List<CompetencyQuestion> cqs, int limit,
            long timeout) {
        if (cqs == null || cqs.isEmpty()) {
            return StageResult.skipped(CQS, "no competency questions found — add some with "
                    + "add_competency_question.");
        }
        Map<String, Object> run = CqRunner.run(snap, cqs, limit, timeout, CqRunner.FAIL_ON_ANY);
        int failed = ((Number) run.get("failed")).intValue();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", run.get("total"));
        summary.put("passed", run.get("passed"));
        summary.put("failed", failed);
        int errors = countCqErrors(run.get("questions"));
        if (errors > 0) {
            summary.put("errors", errors);
        }
        // Surface (never gate on) per-CQ caveats — e.g. an include_inferred CQ that degraded to the asserted
        // triples because no reasoner was classified — so the aggregate gate is not silently reassuring.
        int caveated = countCaveated(run.get("questions"));
        if (caveated > 0) {
            summary.put("caveats", caveated);
        }
        int inferenceCaveats = countInferenceCaveats(run.get("questions"));
        if (inferenceCaveats > 0) {
            summary.put("inference_caveats", inferenceCaveats);
        }
        return new StageResult(CQS, true, failed > 0 ? FAIL : PASS, summary, null);
    }

    /** Count the per-CQ result maps that carry a {@code caveats} entry (a soft degradation qualifier). */
    @SuppressWarnings("unchecked")
    private static int countCaveated(Object questions) {
        if (!(questions instanceof List)) {
            return 0;
        }
        int n = 0;
        for (Object q : (List<Object>) questions) {
            if (q instanceof Map && ((Map<String, Object>) q).containsKey("caveats")) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static int countCqErrors(Object questions) {
        if (!(questions instanceof List)) {
            return 0;
        }
        int count = 0;
        for (Object row : (List<Object>) questions) {
            if (row instanceof Map && ((Map<String, Object>) row).get("error") != null) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static int countInferenceCaveats(Object questions) {
        if (!(questions instanceof List)) {
            return 0;
        }
        int count = 0;
        for (Object row : (List<Object>) questions) {
            if (!(row instanceof Map)) {
                continue;
            }
            Object caveats = ((Map<String, Object>) row).get("caveats");
            if (!(caveats instanceof List)) {
                continue;
            }
            boolean affected = ((List<Object>) caveats).stream().map(String::valueOf)
                    .anyMatch(c -> c.startsWith("include_inferred requested")
                            || c.startsWith("ran_with_caveat: inferences truncated"));
            if (affected) {
                count++;
            }
        }
        return count;
    }

    private static List<CompetencyQuestion> loadCqs(OWLModelManager mm) {
        CqContext c = CqContext.of(mm);
        List<CompetencyQuestion> out = new ArrayList<>();
        for (CqStore store : CqStores.all()) {
            if (store.detect(c)) {
                out.addAll(store.load(c).ok);
            }
        }
        return out;
    }

    /** Project-mode ontology-annotations convention: load only the in-ontology annotation CQ store. */
    static List<CompetencyQuestion> loadAnnotationCqs(OWLModelManager mm) {
        CqContext c = CqContext.of(mm);
        CqStore store = CqStores.byId(Cq.CONV_ANNOTATIONS);
        if (!store.detect(c)) {
            return new ArrayList<>();
        }
        CqStore.LoadResult loaded = store.load(c);
        if (!loaded.skipped.isEmpty()) {
            List<String> errors = loaded.skipped.stream()
                    .map(warning -> warning.source + ": " + warning.reason).toList();
            throw new ToolArgException("Ontology-annotation competency questions contain unreadable "
                    + "entries: " + String.join("; ", errors));
        }
        Set<String> ids = new LinkedHashSet<>();
        for (CompetencyQuestion cq : loaded.ok) {
            if (!ids.add(cq.id)) {
                throw new ToolArgException("Duplicate ontology-annotation competency-question id: "
                        + cq.id);
            }
        }
        return new ArrayList<>(loaded.ok);
    }

    /** Validate the captured data snapshot against the supplied SHACL shapes (graceful when none). */
    static StageResult shaclStage(byte[] dataTurtle, String shapesText, String shapesPath,
            int limit, long timeout) {
        if (!hasText(shapesText, shapesPath)) {
            return StageResult.skipped(SHACL, "no SHACL shapes supplied — pass 'shacl_shapes' (inline "
                    + "Turtle) or 'shacl_shapes_path' (a local file).");
        }
        if (dataTurtle == null) {
            return StageResult.skipped(SHACL, "SHACL data snapshot was not captured.");
        }
        Map<String, Object> r;
        try {
            r = ShaclTools.validate(dataTurtle, shapesText, shapesPath, limit, timeout);
        } catch (RuntimeException e) {
            // A malformed shapes graph is a config error for THIS stage — surface it as a failed (errored)
            // stage rather than aborting the whole suite.
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return StageResult.errored(SHACL, String.valueOf(summary.get("error")));
        }
        int violations = num(r.get("violations"));
        int warnings = num(r.get("warnings"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("conforms", r.get("conforms"));
        summary.put("violations", violations);
        summary.put("warnings", warnings);
        summary.put("infos", r.get("infos"));
        // Violations fail; a warning-only report warns; info-only (or conformant) passes.
        String verdict = violations > 0 ? FAIL : warnings > 0 ? WARN : num(r.get("infos")) > 0 ? INFO : PASS;
        return new StageResult(SHACL, true, verdict, summary, null);
    }

    /** Strict project stage over the union of every policy-referenced shapes file. */
    static StageResult shaclStage(byte[] dataTurtle, List<Path> shapesPaths, int limit, long timeout) {
        if (shapesPaths == null || shapesPaths.isEmpty()) {
            return StageResult.skipped(SHACL, "no policy SHACL shapes were resolved.");
        }
        if (dataTurtle == null) {
            return StageResult.errored(SHACL, "SHACL data snapshot was not captured.");
        }
        try {
            Map<String, Object> result = ShaclTools.validate(dataTurtle, shapesPaths, limit, timeout);
            int violations = num(result.get("violations"));
            int warnings = num(result.get("warnings"));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("conforms", result.get("conforms"));
            summary.put("violations", violations);
            summary.put("warnings", warnings);
            summary.put("infos", result.get("infos"));
            summary.put("shape_files", shapesPaths.stream().map(Path::toString).toList());
            return new StageResult(SHACL, true,
                    violations > 0 ? FAIL : warnings > 0 ? WARN
                            : num(result.get("infos")) > 0 ? INFO : PASS, summary, null);
        } catch (RuntimeException e) {
            return StageResult.errored(SHACL, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static boolean hasText(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    // ================================================================== aggregation (pure)

    /** Collapse the stage results into one gate verdict. Package-private for headless testing. */
    static CallToolResult aggregate(List<StageResult> results, String failOn) {
        List<Map<String, Object>> stagesJson = new ArrayList<>();
        int worst = 0;
        int ran = 0;
        for (StageResult r : results) {
            stagesJson.add(r.toJson());
            if (r.ran) {
                ran++;
                worst = Math.max(worst, level(r.verdict));
            }
        }
        String gate = worst >= threshold(failOn) ? FAIL : PASS;
        Tools.Json json = Tools.json()
                .put("gate", gate)
                .put("fail_on", failOn)
                .put("stages_ran", ran)
                .put("stages", stagesJson);
        if (ran == 0) {
            // Guard the vacuous pass: with zero stages actually run (all requested stages skipped — e.g.
            // their backing data was absent) the 'pass' gate attests to nothing. Surface that explicitly.
            json.put("note", "No stages ran — every requested stage was skipped (see each stage's "
                    + "reason), so the 'pass' gate is vacuous, not an attestation of quality.");
        }
        return json.result();
    }

    /** Strict pass/fail/error gate used by project policy and opt-in strict legacy calls. */
    static Map<String, Object> strictResult(SuiteExecution execution, Set<String> requiredStages,
            String failOn, int policyVersion, String policyDigest, boolean policyLoaded) {
        Map<String, StageResult> byStage = new LinkedHashMap<>();
        for (StageResult result : execution.results) {
            byStage.put(result.stage, result);
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        int ran = 0;
        int skipped = 0;
        boolean gateError = !execution.snapshotConsistent || execution.preconditionError != null;
        boolean gateFail = false;

        if (!execution.snapshotConsistent) {
            findings.add(commonFinding("snapshot.changed", "orchestrator", "error",
                    "The active ontology or loaded import closure changed between project-QC "
                            + "preconditions/classification and the shared validation snapshot; results "
                            + "are not one revision.", null));
        }
        if (execution.preconditionError != null) {
            findings.add(commonFinding("precondition.changed", "orchestrator", "error",
                    execution.preconditionError, null));
        }
        if (!execution.fingerprint.releaseStable()) {
            findings.add(commonFinding("fingerprint.session_only", "fingerprint", "warning",
                    String.join(" ", execution.fingerprint.warnings()),
                    Map.of("stability", execution.fingerprint.stability())));
        }

        for (String stage : execution.results.stream().map(r -> r.stage).toList()) {
            StageResult result = byStage.get(stage);
            boolean required = requiredStages.contains(stage);
            Map<String, Object> row = result.toJson();
            row.put("required", required);
            List<Map<String, Object>> stageFindings = new ArrayList<>();
            String stageMessage = null;
            String status;
            if (result.executionError) {
                status = "error";
                ran++;
                // A stage that ERRORED could not produce a verdict; fail closed regardless of whether it
                // was required. The `required` flag governs only the missing/skipped→error escalation.
                gateError = true;
                Map<String, Object> finding = commonFinding(stage + ".execution", stage, "error",
                        result.reason, result.summary);
                findings.add(finding);
                stageFindings.add(finding);
                stageMessage = result.reason;
            } else if (!result.ran) {
                status = required ? "error" : "skipped";
                skipped++;
                stageMessage = result.reason;
                if (required) {
                    gateError = true;
                    Map<String, Object> finding = commonFinding(stage + ".missing", stage, "error",
                            result.reason, null);
                    findings.add(finding);
                    stageFindings.add(finding);
                }
            } else {
                ran++;
                boolean fails = level(result.verdict) >= threshold(failOn);
                status = fails ? "fail" : "pass";
                // The gate is the worst RAN stage versus fail_on (the documented contract). A ran stage
                // that reaches fail_on fails the gate whether or not it was required; `required` only
                // adds the missing/skipped→error semantics handled above.
                if (fails) {
                    gateFail = true;
                }
                if (!PASS.equals(result.verdict)) {
                    String severity = FAIL.equals(result.verdict) ? "error"
                            : WARN.equals(result.verdict) ? "warning" : "info";
                    Map<String, Object> finding = commonFinding(stage + ".finding", stage, severity,
                            stage + " reported " + result.verdict, result.summary);
                    findings.add(finding);
                    stageFindings.add(finding);
                }
            }
            row.put("status", status);
            row.put("message", stageMessage);
            row.put("findings", stageFindings);
            row.put("details", result.summary == null ? Collections.emptyMap() : result.summary);
            stages.add(row);
        }
        // A required stage absent from execution is also an error (e.g. a caller passed a stale/future id).
        for (String required : requiredStages) {
            if (!byStage.containsKey(required)) {
                gateError = true;
                skipped++;
                Map<String, Object> finding = commonFinding(required + ".missing", required, "error",
                        "required stage was not scheduled", null);
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("stage", required);
                missing.put("required", true);
                missing.put("ran", false);
                missing.put("status", "error");
                missing.put("reason", "required stage was not scheduled");
                missing.put("message", "required stage was not scheduled");
                missing.put("findings", List.of(finding));
                missing.put("details", Collections.emptyMap());
                stages.add(missing);
                findings.add(finding);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gate", gateError ? "error" : gateFail ? "fail" : "pass");
        result.put("policy_loaded", policyLoaded);
        if (policyVersion > 0) {
            result.put("policy_version", policyVersion);
        }
        if (policyDigest != null) {
            result.put("policy_digest", policyDigest);
        }
        result.put("semantic_fingerprint", execution.fingerprint.semanticFingerprint());
        StageResult interoperability = byStage.get(INTEROPERABILITY);
        if (interoperability != null && interoperability.ran && !interoperability.executionError
                && interoperability.summary != null) {
            result.put("rdf_dataset_fingerprint",
                    interoperability.summary.get("rdf_dataset_fingerprint"));
            result.put("rdf_dataset_identity", interoperability.summary);
        }
        result.put("fingerprint_stability", execution.fingerprint.stability());
        result.put("release_stable", execution.fingerprint.releaseStable());
        result.put("fingerprint_warnings", execution.fingerprint.warnings());
        result.put("reasoner", execution.selectedReasoner);
        result.put("required_stages", new ArrayList<>(requiredStages));
        result.put("stages_ran", ran);
        result.put("stages_skipped", skipped);
        result.put("fail_on", failOn);
        result.put("stages", stages);
        result.put("findings", findings);
        result.put("artifacts", Collections.emptyList());
        result.put("snapshot_consistent", execution.snapshotConsistent);
        Map<String, Object> validationSnapshot = new LinkedHashMap<>();
        validationSnapshot.put("mode", execution.snapshotMode);
        validationSnapshot.put("same_snapshot", execution.sameValidationSnapshot);
        validationSnapshot.put("semantic_fingerprint", execution.fingerprint.semanticFingerprint());
        validationSnapshot.put("closure_fingerprint", execution.closureFingerprint);
        validationSnapshot.put("stages", execution.snapshotStages);
        result.put("validation_snapshot", validationSnapshot);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fail_on", "warn".equals(failOn) ? "warning" : failOn);
        List<String> missingRequired = new ArrayList<>();
        for (String required : requiredStages) {
            StageResult stage = byStage.get(required);
            if (stage == null || !stage.ran || stage.executionError) {
                missingRequired.add(required);
            }
        }
        if (!missingRequired.isEmpty()) {
            details.put("missing_required_stages", missingRequired);
        }
        result.put("details", details);
        return result;
    }

    private static Map<String, Object> commonFinding(String id, String source, String severity,
            String message, Map<String, Object> details) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("id", id);
        finding.put("source", source);
        finding.put("severity", severity);
        finding.put("message", message == null ? id : message);
        finding.put("focus_iri", null);
        finding.put("axiom", null);
        finding.put("path", null);
        finding.put("rule_id", id);
        finding.put("waiver", null);
        finding.put("details", details == null ? Collections.emptyMap() : details);
        return finding;
    }

    /** One stage's outcome. */
    static final class StageResult {
        final String stage;
        final boolean ran;
        final String verdict;              // pass | warn | fail (only meaningful when ran)
        final Map<String, Object> summary; // nullable
        final String reason;               // why it was skipped (when !ran)
        final boolean executionError;

        StageResult(String stage, boolean ran, String verdict, Map<String, Object> summary,
                String reason) {
            this.stage = stage;
            this.ran = ran;
            this.verdict = verdict;
            this.summary = summary;
            this.reason = reason;
            this.executionError = false;
        }

        private StageResult(String stage, boolean ran, String verdict, Map<String, Object> summary,
                String reason, boolean executionError) {
            this.stage = stage;
            this.ran = ran;
            this.verdict = verdict;
            this.summary = summary;
            this.reason = reason;
            this.executionError = executionError;
        }

        static StageResult skipped(String stage, String reason) {
            return new StageResult(stage, false, null, null, reason);
        }

        static StageResult errored(String stage, String reason) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("error", reason);
            return new StageResult(stage, true, FAIL, summary, reason, true);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", stage);
            m.put("ran", ran);
            if (ran) {
                m.put("verdict", verdict);
                if (executionError) {
                    m.put("error", true);
                }
                if (summary != null) {
                    m.put("findings_summary", summary);
                }
            } else {
                m.put("reason", reason);
            }
            return m;
        }
    }

    /** Data-only result of one shared-snapshot suite execution. */
    static final class SuiteExecution {
        final List<StageResult> results;
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

        SuiteExecution(List<StageResult> results, OntologyFingerprint fingerprint,
                boolean snapshotConsistent, String selectedReasoner, String preconditionError) {
            this(results, fingerprint, snapshotConsistent, selectedReasoner, preconditionError,
                    "isolated", results.stream()
                            .filter(result -> result.ran && !REASONER.equals(result.stage))
                            .map(result -> result.stage).toList(), true,
                    fingerprint.semanticFingerprint(), Collections.emptyList());
        }

        SuiteExecution(List<StageResult> results, OntologyFingerprint fingerprint,
                boolean snapshotConsistent, String selectedReasoner, String preconditionError,
                String snapshotMode, List<String> snapshotStages, boolean sameValidationSnapshot,
                String closureFingerprint, List<Map<String, Object>> missingImports) {
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
        }
    }

    private record ExecutionPrecondition(String semanticFingerprint, String closureFingerprint,
            String selectedReasoner) { }

    /** Inputs shared by legacy and project-policy execution. */
    static final class RunConfig {
        final Set<String> stages;
        final Set<String> requiredStages;
        final String failOn;
        final String profileName;
        final int limit;
        final int timeout;
        final List<Invariants.Invariant> invariants;
        final List<CompetencyQuestion> policyCqs;
        final String shaclShapes;
        final String shaclShapesPath;
        final List<Path> shaclPaths;
        final Pattern iriPattern;
        final List<String> requiredNamespaces;
        final List<String> requiredAnnotations;
        final boolean checkOwnership;
        final PolicyGovernance.Rules policyGovernance;
        final Set<String> disabledStructural;
        final Map<String, String> structuralSeverity;
        final boolean projectMode;
        final String requiredReasoner;
        final String requiredOntologyIri;
        final InteroperabilityConfig interoperability;

        RunConfig(Set<String> stages, Set<String> requiredStages, String failOn, String profileName,
                int limit, int timeout, List<Invariants.Invariant> invariants,
                List<CompetencyQuestion> policyCqs, String shaclShapes, String shaclShapesPath,
                List<Path> shaclPaths, Pattern iriPattern, List<String> requiredNamespaces,
                List<String> requiredAnnotations, boolean checkOwnership,
                PolicyGovernance.Rules policyGovernance,
                Set<String> disabledStructural, Map<String, String> structuralSeverity,
                boolean projectMode, String requiredReasoner, String requiredOntologyIri,
                InteroperabilityConfig interoperability) {
            this.stages = Collections.unmodifiableSet(new LinkedHashSet<>(stages));
            this.requiredStages = Collections.unmodifiableSet(new LinkedHashSet<>(requiredStages));
            this.failOn = failOn;
            this.profileName = profileName;
            this.limit = limit;
            this.timeout = timeout;
            this.invariants = List.copyOf(invariants);
            this.policyCqs = policyCqs == null ? null : List.copyOf(policyCqs);
            this.shaclShapes = shaclShapes;
            this.shaclShapesPath = shaclShapesPath;
            this.shaclPaths = List.copyOf(shaclPaths);
            this.iriPattern = iriPattern;
            this.requiredNamespaces = List.copyOf(requiredNamespaces);
            this.requiredAnnotations = List.copyOf(requiredAnnotations);
            this.checkOwnership = checkOwnership;
            this.policyGovernance = policyGovernance == null
                    ? PolicyGovernance.Rules.empty() : policyGovernance;
            this.disabledStructural = Collections.unmodifiableSet(new LinkedHashSet<>(disabledStructural));
            this.structuralSeverity = Collections.unmodifiableMap(new LinkedHashMap<>(structuralSeverity));
            this.projectMode = projectMode;
            this.requiredReasoner = requiredReasoner;
            this.requiredOntologyIri = requiredOntologyIri;
            this.interoperability = interoperability;
        }

        static RunConfig legacy(Map<String, Object> a) {
            Set<String> stages = normalizeStages(Tools.stringList(a, "stages"));
            Set<String> required = normalizeOptionalStages(Tools.stringList(a, "required_stages"));
            stages.addAll(required);
            String profile = GovernanceTools.normalizeProfile(Tools.optString(a, "owl_profile"));
            int limit = Tools.optInt(a, "limit", 25);
            if (limit < 0 || limit > 10_000) {
                throw new ToolArgException("limit must be between 0 and 10000.");
            }
            int timeout = Tools.optInt(a, "timeout_ms", 120_000);
            timeout = timeout <= 0 ? 120_000 : timeout;
            List<Invariants.Invariant> invariants = stages.contains(INVARIANTS)
                    ? Invariants.parse(Tools.objList(a, "invariants")) : Collections.emptyList();
            Pattern iriPattern = compilePattern(Tools.optString(a, "iri_pattern"));
            return new RunConfig(stages, required, normalizeFailOn(Tools.optString(a, "fail_on")),
                    profile, limit, timeout, invariants, null,
                    Tools.optString(a, "shacl_shapes"), Tools.optString(a, "shacl_shapes_path"),
                    Collections.emptyList(), iriPattern,
                    Tools.stringList(a, "required_namespaces"),
                    Tools.stringList(a, "required_annotations"),
                    Tools.optBool(a, "check_ownership", true), PolicyGovernance.Rules.empty(),
                    Collections.emptySet(),
                    Collections.emptyMap(), false, null, null, null);
        }
    }

    static final class InteroperabilityConfig {
        final String profile;
        final List<String> additionalProfiles;
        final String roCrateFormat;
        final String manifestPath;
        final String rootArtifact;
        final String canonicalizationAlgorithm;
        final String hashAlgorithm;
        final String scope;
        final int timeoutMs;

        InteroperabilityConfig(String profile, List<String> additionalProfiles,
                String roCrateFormat, String manifestPath, String rootArtifact,
                String canonicalizationAlgorithm, String hashAlgorithm, String scope, int timeoutMs) {
            this.profile = profile;
            this.additionalProfiles = List.copyOf(additionalProfiles);
            this.roCrateFormat = roCrateFormat;
            this.manifestPath = manifestPath;
            this.rootArtifact = rootArtifact;
            this.canonicalizationAlgorithm = canonicalizationAlgorithm;
            this.hashAlgorithm = hashAlgorithm;
            this.scope = scope;
            this.timeoutMs = timeoutMs;
        }
    }

    // ================================================================== stage / gate helpers

    static Set<String> normalizeStages(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return new LinkedHashSet<>(DEFAULT_STAGES);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : requested) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (!ALL_STAGES.contains(v)) {
                throw new ToolArgException("Unknown stage '" + s + "'. Use any of: "
                        + String.join(", ", ALL_STAGES) + ".");
            }
            out.add(v);
        }
        return out;
    }

    static String normalizeFailOn(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "error";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "none":
            case "warn":
            case "error":
                return s;
            default:
                throw new ToolArgException("fail_on must be none, warn, or error (not '" + raw + "').");
        }
    }

    static int level(String verdict) {
        if (FAIL.equals(verdict)) {
            return 3;
        }
        if (WARN.equals(verdict)) {
            return 2;
        }
        if (INFO.equals(verdict)) {
            return 1;
        }
        return 0;
    }

    static int threshold(String failOn) {
        switch (failOn) {
            case "info":
                return 1;
            case "warn":
                return 2;
            case "error":
                return 3;
            case "none":
            default:
                return Integer.MAX_VALUE;
        }
    }

    static Set<String> normalizeOptionalStages(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String stage : requested) {
            String normalized = stage.trim().toLowerCase(Locale.ROOT);
            if (!ALL_STAGES.contains(normalized)) {
                throw new ToolArgException("Unknown required stage '" + stage + "'. Use any of: "
                        + String.join(", ", ALL_STAGES) + ".");
            }
            out.add(normalized);
        }
        return out;
    }

    static Pattern compilePattern(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Pattern.compile(raw);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new ToolArgException("iri_pattern is not a valid regular expression: " + e.getMessage());
        }
    }

    /** Hand-assembled schema: stages[] + owl_profile + fail_on + limit + timeout_ms + invariants[]. */
    private static Map<String, Object> suiteSchema() {
        Map<String, Object> schema = Tools.schema()
                .strArray("stages", "Subset of: interoperability, reasoner, profile, governance, "
                        + "structural, invariants, cqs, shacl (default reasoner, profile, structural; "
                        + "interoperability requires project policy).")
                .strArray("required_stages", "Stages that must complete; missing/skipped/error becomes gate=error.")
                .bool("error_on_missing_required", "Fail closed when a required/requested stage cannot run.")
                .str("policy_path", "Optional project policy path; delegates to strict project QC.")
                .str("owl_profile", "OWL 2 profile for the profile stage: DL (default), EL, QL, RL.")
                .str("fail_on", "Gate severity: none | warn | error (default error).")
                .strArray("required_namespaces", "Governance: allowed owned-entity namespaces.")
                .str("iri_pattern", "Governance: Java regex for owned entity IRIs.")
                .strArray("required_annotations", "Governance: required annotation properties.")
                .bool("check_ownership", "Governance: enforce import layering (default true).")
                .integer("limit", "Max samples per check (default 25).")
                .integer("timeout_ms", "Time budget in ms for the SPARQL and SHACL stages (default 120000).")
                .str("shacl_shapes", "SHACL shapes graph as Turtle (inline) for the 'shacl' stage.")
                .str("shacl_shapes_path", "Local file path to a SHACL shapes document for the 'shacl' stage.")
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("sparql", Tools.stringProperty("A SPARQL SELECT/ASK whose results are violations."));
        itemProps.put("id", Tools.stringProperty("Optional stable id."));
        itemProps.put("message", Tools.stringProperty("Human message for a violation."));
        itemProps.put("severity", Tools.stringProperty("error (default) | warn | info."));
        itemProps.put("include_inferred", Tools.boolProperty("Run over inferred triples (default false)."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", Arrays.asList("sparql"));
        item.put("additionalProperties", false);
        Map<String, Object> invariants = new LinkedHashMap<>();
        invariants.put("type", "array");
        invariants.put("items", item);
        invariants.put("description", "Invariants for the 'invariants' stage (verify_ontology queries[]).");
        props.put("invariants", invariants);
        return schema;
    }
}
