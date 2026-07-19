package io.github.hakjuoh.protege_mcp.tools;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import io.github.hakjuoh.protege_mcp.policy.ReasonerNames;
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

    static final String PASS = "pass";
    static final String INFO = "info";
    static final String WARN = "warn";
    static final String FAIL = "fail";

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("run_qc_suite",
                (ex, req) -> {
                    String shapes = Tools.optString(Tools.args(req), "shacl_shapes_path");
                    Map<String, Object> arguments = Tools.args(req);
                    String policyPath = Tools.optString(arguments, "policy_path");
                    DirectAccessPolicy.Rules rules = null;
                    if (policyPath != null) {
                        rules = DirectAccessPolicy.resolve(ctx, ex, policyPath);
                        arguments = rules.authorizedPolicyArguments(arguments);
                    }
                    if (shapes != null) {
                        if (rules == null) rules = DirectAccessPolicy.resolve(ctx, ex);
                        arguments = new LinkedHashMap<>(arguments);
                        arguments.put("shacl_shapes_path", rules.readPath(shapes).toString());
                    }
                    return runSuite(ctx, arguments, rules);
                });
    }

    // ================================================================== orchestration

    private static CallToolResult runSuite(ToolContext ctx, Map<String, Object> a) {
        return runSuite(ctx, a, null);
    }

    private static CallToolResult runSuite(ToolContext ctx, Map<String, Object> a,
            DirectAccessPolicy.Rules rules) {
        // Parsed on BOTH branches: an invalid value is rejected with the same ToolArgException the
        // strict surfaces use (the catalog schema enum is advisory — the server does not validate
        // tool inputs), and a request the legacy branch cannot honor is refused explicitly rather
        // than silently ignored.
        LockMode lockMode = LockMode.parse(Tools.optString(a, "lock_mode"));
        if (Tools.optString(a, "policy_path") != null) {
            return ProjectQcTools.run(ctx, a, false, rules);
        }
        if (lockMode.requested()) {
            return Tools.error("lock_mode=" + lockMode.value() + " requires the strict policy "
                    + "branch: pass policy_path (or call run_project_qc). The legacy inline gate "
                    + "performs no lock verification, so the request is refused rather than "
                    + "silently ignored.");
        }
        QcRunConfig config = QcRunConfig.legacy(a);
        QcSuiteExecution execution = execute(ctx, config);
        boolean strictMissing = Tools.optBool(a, "error_on_missing_required", false);
        if (strictMissing || !config.requiredStages.isEmpty()) {
            Set<String> required = legacyRequiredStages(config, strictMissing);
            return Tools.ok(strictResult(execution, required, config.failOn,
                    0, null, false));
        }
        return aggregate(execution.results, config.failOn);
    }

    /** Explicit required_stages wins; strict mode promotes all requested stages only as a fallback. */
    static Set<String> legacyRequiredStages(QcRunConfig config, boolean strictMissing) {
        return config.requiredStages.isEmpty() && strictMissing
                ? config.stages : config.requiredStages;
    }

    /** Shared orchestration used by legacy run_qc_suite and strict run_project_qc. */
    static QcSuiteExecution execute(ToolContext ctx, QcRunConfig config) {
        return execute(ctx, config, Collections.emptyList());
    }

    /** Execute the existing isolated QC pipeline after applying an exact private preflight delta. */
    static QcSuiteExecution execute(ToolContext ctx, QcRunConfig config, List<NormalizedChange> changes) {
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
        List<QcStageResult> results = new ArrayList<>();
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
            List<Map<String, Object>> projectChecks = new ArrayList<>(config.projectGovernanceChecks);
            // Project-mode contract only: a legacy gate that captured the import report solely for a
            // request-level lock_mode must not gain module governance checks it never ran before.
            if (config.projectMode) {
                projectChecks.addAll(ModulePolicyGovernance.importChecks(p1.importReport, config.limit));
            }
            results.add(governanceStage(p1.validationSnapshot, config.iriPattern,
                    config.requiredNamespaces, config.requiredAnnotations, config.checkOwnership,
                    config.policyGovernance, projectChecks, config.limit));
        }
        if (config.stages.contains(STRUCTURAL)) {
            results.add(structuralStage(p1.validationSnapshot, config.disabledStructural,
                    config.structuralSeverity, config.projectMode));
        }
        int rowLimit = Math.max(config.limit, 1000);
        if (config.stages.contains(INVARIANTS)) {
            QcStageResult invariantsResult = invariantsStage(p1.validationSnapshot.queries(), config.invariants,
                    rowLimit, config.timeout);
            if (config.projectMode && invariantsResult.summary != null
                    && (num(invariantsResult.summary.get("errors")) > 0
                            || num(invariantsResult.summary.get("inference_caveats")) > 0)) {
                // Fail closed exactly like the CQ stage: an include_inferred invariant that ran over a
                // TRUNCATED inferred snapshot could miss an inference-only violation and false-pass, so
                // in project mode a truncation caveat is a gate error, not a silent pass.
                invariantsResult = QcStageResult.errored(INVARIANTS,
                        "one or more required invariants could not execute with complete inferred data");
            }
            results.add(invariantsResult);
        }
        if (config.stages.contains(CQS)) {
            QcStageResult cqResult = cqsStage(p1.validationSnapshot.queries(), p1.cqs, rowLimit, config.timeout);
            if (config.projectMode && cqResult.summary != null
                    && (num(cqResult.summary.get("errors")) > 0
                            || num(cqResult.summary.get("inference_caveats")) > 0)) {
                cqResult = QcStageResult.errored(CQS,
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
        return new QcSuiteExecution(results, p1.fingerprint, snapshotConsistent,
                p1.selectedReasoner, preconditionError,
                p1.validationSnapshot == null ? "none" : "isolated", isolatedStages,
                p1.validationSnapshot != null || config.stages.isEmpty(), p1.closureFingerprint,
                p1.missingImports, p1.importReport);
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
        ImportTools.ImportReport importReport;
    }

    private record ExecutionPrecondition(String semanticFingerprint, String closureFingerprint,
            String selectedReasoner) { }

    private static Phase1 phase1(OWLModelManager mm, QcRunConfig config,
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
        if (config.projectMode || config.captureImportReport) {
            // Capture the import-closure state in the SAME model-thread hop as the snapshot below, so a
            // concurrent edit cannot resolve a missing import between QC and a fail-closed import check.
            // A request-level lock_mode also needs this outside project mode; the missing-import list
            // stays a project-mode contract (no-policy gates do not fail closed on it).
            p1.importReport = ImportTools.analyze(mm.getActiveOntology());
            if (config.projectMode) {
                p1.missingImports = p1.importReport.missingImports;
            }
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
    private static ReasoningOutcome runIsolatedReasoner(Phase1 p1, QcRunConfig config) {
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

    private static ReasoningOutcome computeIsolatedReasoner(Phase1 p1, QcRunConfig config,
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
            Set<String> unsatIris = null;
            if (consistent) {
                Set<org.semanticweb.owlapi.model.OWLClass> classes =
                        reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom();
                unsat = classes.size();
                unsatisfiable = Tools.entityList(snapshot.modelManager(), classes, config.limit);
                // Capture the COMPLETE IRI set (the display list above is capped at config.limit) so
                // verified-apply attribution can diff full sets instead of a truncated window.
                unsatIris = new java.util.LinkedHashSet<>();
                for (org.semanticweb.owlapi.model.OWLClass cls : classes) {
                    unsatIris.add(cls.getIRI().toString());
                }
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
                    return new ReasoningOutcome(QcStageResult.errored(REASONER, swrl), snapshot);
                }
            }
            QcStageResult stage = stageRequested
                    ? new QcStageResult(REASONER, true,
                            consistent && unsat == 0 ? PASS : FAIL, summary, null)
                    : null;
            if (stage != null && unsatIris != null) {
                stage.attributionUnsatIris = java.util.Set.copyOf(unsatIris);
            }
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
        QcStageResult stage = stageRequested
                ? (p1.reasonerSpec == null && p1.reasonerCaptureError == null
                        ? QcStageResult.skipped(REASONER, reason)
                        : QcStageResult.errored(REASONER, reason))
                : null;
        return new ReasoningOutcome(stage, snapshot);
    }

    static boolean reasonerMatches(String required, IsolatedReasonerSpec spec,
            String selectedName) {
        List<ReasonerNames.Candidate> selection = new ArrayList<>();
        selection.add(new ReasonerNames.Candidate(spec.reasonerName(), spec.reasonerId()));
        if (selectedName != null) {
            selection.add(new ReasonerNames.Candidate(selectedName, null));
        }
        return !ReasonerNames.resolve(required, selection).candidates().isEmpty();
    }

    private record ReasoningOutcome(QcStageResult stage, IsolatedValidationSnapshot snapshot) { }

    static QcStageResult interoperabilityStage(IsolatedValidationSnapshot snapshot,
            QcInteroperabilityConfig interoperability) {
        if (interoperability == null) {
            return QcStageResult.skipped(INTEROPERABILITY,
                    "interoperability requires a validated project policy");
        }
        try {
            if (!RdfDatasetFingerprints.CANONICALIZATION_ALGORITHM.equals(
                    interoperability.canonicalizationAlgorithm)
                    || !RdfDatasetFingerprints.HASH_ALGORITHM.equals(interoperability.hashAlgorithm)
                    || !RdfDatasetFingerprints.SCOPE.equals(interoperability.scope)) {
                return QcStageResult.errored(INTEROPERABILITY,
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
            return new QcStageResult(INTEROPERABILITY, true, PASS, summary, null);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return QcStageResult.errored(INTEROPERABILITY,
                    "RDF dataset canonicalization failed: " + message);
        }
    }

    // ================================================================== stages

    /** Legacy live-reasoner verdict retained for direct method-level compatibility tests. */
    static QcStageResult reasonerStage(OWLModelManager mm, int limit) {
        Map<String, Object> v = ValidationTools.reasonerVerdict(mm, limit);
        if (!Boolean.TRUE.equals(v.get("results_available"))) {
            String status = String.valueOf(v.get("status"));
            // No reasoner selected at all is a legitimate skip (the user opted out of reasoning). But a
            // reasoner that is OUT_OF_SYNC, or was reset to uninitialized because classification failed,
            // must NOT silently pass the gate — surface it as a ran WARN so fail_on=warn trips and the
            // JSON is never falsely reassuring about consistency / unsatisfiable classes.
            if ("NO_REASONER_FACTORY_CHOSEN".equals(status)) {
                return QcStageResult.skipped(REASONER, "no reasoner is selected in Protégé.");
            }
            Map<String, Object> stale = new LinkedHashMap<>();
            stale.put("status", v.get("status"));
            stale.put("reason", "no current reasoner results — the reasoner is not classified/in-sync "
                    + "(run_reasoner was not run, or the last classification failed; see the Protégé log).");
            return new QcStageResult(REASONER, true, WARN, stale, null);
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
        return new QcStageResult(REASONER, true, verdict, summary, null);
    }

    static QcStageResult structuralStage(OWLModelManager mm) {
        return structuralStage(mm, Collections.emptySet(), Collections.emptyMap(), false);
    }

    static QcStageResult structuralStage(OWLModelManager mm, Set<String> disabled,
            Map<String, String> severityOverrides, boolean surfaceInfo) {
        Set<OWLOntology> scope = Collections.singleton(mm.getActiveOntology());
        return structuralStage(ValidationTools.analyze(scope), disabled, severityOverrides, surfaceInfo);
    }

    static QcStageResult structuralStage(IsolatedValidationSnapshot snapshot, Set<String> disabled,
            Map<String, String> severityOverrides, boolean surfaceInfo) {
        Set<OWLOntology> scope = Collections.singleton(snapshot.active());
        return structuralStage(ValidationTools.analyze(scope, snapshot.closure()), disabled,
                severityOverrides, surfaceInfo);
    }

    private static QcStageResult structuralStage(List<ValidationTools.Finding> findings, Set<String> disabled,
            Map<String, String> severityOverrides, boolean surfaceInfo) {
        int total = 0;
        int warnings = 0;   // only warning-severity smells gate; info findings are reported, not gated
        int errors = 0;
        List<Map<String, Object>> checks = new ArrayList<>();
        Set<String> gatingIdentities = new LinkedHashSet<>();
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
                List<String> identities = new ArrayList<>();
                List<String> entityIdentities = new ArrayList<>();
                f.entities.forEach(entity -> {
                    String id = FindingIdentity.entity(entity);
                    identities.add(id);
                    entityIdentities.add(id);
                });
                f.details.forEach(detail -> identities.add("detail\u0000" + detail));
                c.put("identity_digest", FindingIdentity.digest(identities));
                checks.add(c);
                if ("error".equals(severity) || "warning".equals(severity)
                        || "warn".equals(severity)) {
                    // Only gating-severity findings feed attribution; qualify each identity with the
                    // check id so the same entity under two different smells stays distinct.
                    for (String identity : entityIdentities) {
                        gatingIdentities.add(f.id + "|" + identity);
                    }
                }
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
        QcStageResult stage = new QcStageResult(STRUCTURAL, true,
                errors > 0 ? FAIL : warnings > 0 ? WARN : surfaceInfo && total > 0 ? INFO : PASS,
                summary, null);
        stage.attributionIdentities = Set.copyOf(gatingIdentities);
        return stage;
    }

    static QcStageResult governanceStage(OWLModelManager mm, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, int limit) {
        List<Map<String, Object>> checks = GovernanceTools.policyChecks(mm, iriPattern,
                requiredNamespaces, requiredAnnotations, checkOwnership, limit);
        return governanceStage(checks);
    }

    static QcStageResult governanceStage(IsolatedValidationSnapshot snapshot, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, int limit) {
        return governanceStage(snapshot, iriPattern, requiredNamespaces, requiredAnnotations,
                checkOwnership, PolicyGovernance.Rules.empty(), List.of(), limit);
    }

    static QcStageResult governanceStage(IsolatedValidationSnapshot snapshot, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, PolicyGovernance.Rules rules, int limit) {
        return governanceStage(snapshot, iriPattern, requiredNamespaces, requiredAnnotations,
                checkOwnership, rules, List.of(), limit);
    }

    static QcStageResult governanceStage(IsolatedValidationSnapshot snapshot, Pattern iriPattern,
            List<String> requiredNamespaces, List<String> requiredAnnotations,
            boolean checkOwnership, PolicyGovernance.Rules rules,
            List<Map<String, Object>> projectChecks, int limit) {
        // Collect the STABLE gating identities of the intrinsic governance checks (ownership /
        // import_layering is enabled by default even with no policy) for verified-apply attribution,
        // PLUS the complete per-row identity sets the rule-driven PolicyGovernance / module project
        // checks now publish through the reserved side channel (ADR 0004 decision 3: member-level
        // stage deltas need the full sets, not count/digest heuristics). No-policy behavior is
        // unchanged — without a validated policy those checks contribute no identities.
        Set<String> gatingIdentities = new LinkedHashSet<>();
        List<Map<String, Object>> checks = GovernanceTools.policyChecks(snapshot.modelManager(),
                snapshot.active(), snapshot.closure(), iriPattern, requiredNamespaces,
                requiredAnnotations, checkOwnership, limit, gatingIdentities);
        checks.addAll(PolicyGovernance.checks(snapshot.active(), snapshot.closure(), rules,
                LocalDate.now(ZoneOffset.UTC), limit));
        checks.addAll(projectChecks);
        // Drain the side channel from a COPY of every check map before the list becomes the
        // serialized summary: the reserved key must never appear in a public result, and the
        // originals stay intact because config-owned module checks are reused by the baseline
        // attribution re-run (mutating them would starve the second run's identity sets).
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            Map<String, Object> copy = new LinkedHashMap<>(check);
            ModulePolicyGovernance.drainAttributionIdentities(copy, gatingIdentities);
            sanitized.add(copy);
        }
        QcStageResult stage = governanceStage(sanitized);
        stage.attributionIdentities = Set.copyOf(gatingIdentities);
        return stage;
    }

    private static QcStageResult governanceStage(List<Map<String, Object>> checks) {
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
        return new QcStageResult(GOVERNANCE, true, errors > 0 ? FAIL : warnings > 0 ? WARN : PASS,
                summary, null);
    }

    private static QcStageResult profileStage(Phase1 p1, String profileName, int limit) {
        Profiles profile = GovernanceTools.profileFor(profileName);
        if (profile == null || p1.validationSnapshot == null) {
            return QcStageResult.skipped(PROFILE, "profile check skipped (owl_profile=" + profileName + ").");
        }
        Set<String> profileIdentities = new LinkedHashSet<>();
        Map<String, Object> check = GovernanceTools.profileCheck(profile, profileName,
                p1.validationSnapshot.queries().assertedOntology(),
                p1.validationSnapshot.active().getAxioms(),
                p1.validationSnapshot.active().getAnnotations(), limit, profileIdentities);
        // Gate on OWNED conformance: a module that merely imports a non-DL upstream (e.g. BFO) must not fail
        // this stage — only its OWN axioms leaving the profile should. Imported violations are context only.
        boolean ownedInProfile = Boolean.TRUE.equals(check.get("owned_in_profile"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", profileName);
        summary.put("in_profile", check.get("in_profile"));
        summary.put("owned_in_profile", ownedInProfile);
        summary.put("violations", check.get("count"));
        summary.put("identity_digest", check.get("identity_digest"));
        if (check.get("imported_violations") != null) {
            summary.put("imported_violations", check.get("imported_violations"));
        }
        QcStageResult stage = new QcStageResult(PROFILE, true, ownedInProfile ? PASS : FAIL, summary, null);
        stage.attributionIdentities = Set.copyOf(profileIdentities);
        return stage;
    }

    static QcStageResult invariantsStage(SuiteSnapshot snap, List<Invariants.Invariant> invariants,
            int limit, long timeout) {
        if (invariants.isEmpty()) {
            return QcStageResult.skipped(INVARIANTS, "no invariants supplied — pass an 'invariants' array "
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
        summary.put("identity_digest", resultIdentityDigest(run.get("invariants"), "violated"));
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
        QcStageResult stage = new QcStageResult(INVARIANTS, true, verdict, summary, null);
        stage.attributionIdentities = Set.copyOf(resultIdentities(run.get("invariants"), "violated"));
        return stage;
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

    static QcStageResult cqsStage(SuiteSnapshot snap, List<CompetencyQuestion> cqs, int limit,
            long timeout) {
        if (cqs == null || cqs.isEmpty()) {
            return QcStageResult.skipped(CQS, "no competency questions found — add some with "
                    + "add_competency_question.");
        }
        Map<String, Object> run = CqRunner.run(snap, cqs, limit, timeout, CqRunner.FAIL_ON_ANY);
        int failed = ((Number) run.get("failed")).intValue();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", run.get("total"));
        summary.put("passed", run.get("passed"));
        summary.put("failed", failed);
        summary.put("identity_digest", resultIdentityDigest(run.get("questions"), "pass"));
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
        QcStageResult stage = new QcStageResult(CQS, true, failed > 0 ? FAIL : PASS, summary, null);
        stage.attributionIdentities = Set.copyOf(resultIdentities(run.get("questions"), "pass"));
        return stage;
    }

    /** Digest complete failed result rows while ignoring volatile execution timing. */
    private static String resultIdentityDigest(Object value, String verdictKey) {
        return FindingIdentity.digest(resultIdentities(value, verdictKey));
    }

    /**
     * The complete per-row identity strings of the FAILED result rows (a CQ that did not pass, an
     * invariant that was violated or errored), with volatile execution timing removed — exactly the
     * strings {@code resultIdentityDigest} folds into the public digest, now also published through
     * the {@code QcStageResult.attributionIdentities} side channel (ADR 0004 decision 3).
     */
    static List<String> resultIdentities(Object value, String verdictKey) {
        List<String> identities = new ArrayList<>();
        if (!(value instanceof List<?> rows)) return identities;
        for (Object valueRow : rows) {
            if (!(valueRow instanceof Map<?, ?> row)) continue;
            boolean failed = "pass".equals(verdictKey)
                    ? !Boolean.TRUE.equals(row.get(verdictKey))
                    : Boolean.TRUE.equals(row.get(verdictKey)) || row.get("error") != null;
            if (!failed) continue;
            Map<String, Object> stable = new LinkedHashMap<>();
            row.forEach((key, entryValue) -> {
                if (!"ms".equals(String.valueOf(key))) stable.put(String.valueOf(key), entryValue);
            });
            identities.add(FindingIdentity.object(stable));
        }
        return identities;
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
    static int countCqErrors(Object questions) {
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
    static int countInferenceCaveats(Object questions) {
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
    static QcStageResult shaclStage(byte[] dataTurtle, String shapesText, String shapesPath,
            int limit, long timeout) {
        if (!hasText(shapesText, shapesPath)) {
            return QcStageResult.skipped(SHACL, "no SHACL shapes supplied — pass 'shacl_shapes' (inline "
                    + "Turtle) or 'shacl_shapes_path' (a local file).");
        }
        if (dataTurtle == null) {
            return QcStageResult.skipped(SHACL, "SHACL data snapshot was not captured.");
        }
        Map<String, Object> r;
        List<String> gatingIdentities = new ArrayList<>();
        try {
            r = ShaclTools.validate(dataTurtle, shapesText, shapesPath, limit, timeout,
                    gatingIdentities);
        } catch (RuntimeException e) {
            // A malformed shapes graph is a config error for THIS stage — surface it as a failed (errored)
            // stage rather than aborting the whole suite.
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return QcStageResult.errored(SHACL, String.valueOf(summary.get("error")));
        }
        int violations = num(r.get("violations"));
        int warnings = num(r.get("warnings"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("conforms", r.get("conforms"));
        summary.put("violations", violations);
        summary.put("warnings", warnings);
        summary.put("infos", r.get("infos"));
        summary.put("identity_digest", r.get("identity_digest"));
        // Violations fail; a warning-only report warns; info-only (or conformant) passes.
        String verdict = violations > 0 ? FAIL : warnings > 0 ? WARN : num(r.get("infos")) > 0 ? INFO : PASS;
        QcStageResult stage = new QcStageResult(SHACL, true, verdict, summary, null);
        stage.attributionIdentities = Set.copyOf(gatingIdentities);
        return stage;
    }

    /** Strict project stage over the union of every policy-referenced shapes file. */
    static QcStageResult shaclStage(byte[] dataTurtle, List<Path> shapesPaths, int limit, long timeout) {
        if (shapesPaths == null || shapesPaths.isEmpty()) {
            return QcStageResult.skipped(SHACL, "no policy SHACL shapes were resolved.");
        }
        if (dataTurtle == null) {
            return QcStageResult.errored(SHACL, "SHACL data snapshot was not captured.");
        }
        try {
            List<String> gatingIdentities = new ArrayList<>();
            Map<String, Object> result = ShaclTools.validate(dataTurtle, shapesPaths, limit, timeout,
                    gatingIdentities);
            int violations = num(result.get("violations"));
            int warnings = num(result.get("warnings"));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("conforms", result.get("conforms"));
            summary.put("violations", violations);
            summary.put("warnings", warnings);
            summary.put("infos", result.get("infos"));
            summary.put("identity_digest", result.get("identity_digest"));
            summary.put("shape_files", shapesPaths.stream().map(Path::toString).toList());
            QcStageResult stage = new QcStageResult(SHACL, true,
                    violations > 0 ? FAIL : warnings > 0 ? WARN
                            : num(result.get("infos")) > 0 ? INFO : PASS, summary, null);
            stage.attributionIdentities = Set.copyOf(gatingIdentities);
            return stage;
        } catch (RuntimeException e) {
            return QcStageResult.errored(SHACL, e.getMessage() == null
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

    // ================================================================== stage / gate helpers

    /** Compatibility seam for existing package callers; pure gate construction lives in {@link QcGate}. */
    static CallToolResult aggregate(List<QcStageResult> results, String failOn) {
        return QcGate.aggregate(results, failOn);
    }

    static Map<String, Object> strictResult(QcSuiteExecution execution,
            Set<String> requiredStages, String failOn, int policyVersion, String policyDigest,
            boolean policyLoaded) {
        return QcGate.strictResult(execution, requiredStages, failOn, policyVersion, policyDigest,
                policyLoaded);
    }

    static Set<String> reasonerUnsatIris(QcSuiteExecution execution) {
        return QcGate.reasonerUnsatIris(execution);
    }

    static Set<String> stageAttributionIdentities(QcSuiteExecution execution, String stage) {
        return QcGate.stageAttributionIdentities(execution, stage);
    }

    /** Compatibility seams for configuration parsing now owned by {@link QcRunConfig}. */
    static Set<String> normalizeStages(List<String> requested) {
        return QcRunConfig.normalizeStages(requested);
    }

    static Set<String> normalizeOptionalStages(List<String> requested) {
        return QcRunConfig.normalizeOptionalStages(requested);
    }

    static String normalizeFailOn(String raw) {
        return QcRunConfig.normalizeFailOn(raw);
    }

    static Pattern compilePattern(String raw) {
        return QcRunConfig.compilePattern(raw);
    }

    static int level(String verdict) {
        return QcGate.level(verdict);
    }

    static int threshold(String failOn) {
        return QcGate.threshold(failOn);
    }

}
