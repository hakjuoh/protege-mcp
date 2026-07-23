package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.auth.CapabilityAuthorizer;
import io.github.hakjuoh.protege_mcp.core.diff.SemanticDiffService;
import io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService;
import io.github.hakjuoh.protege_mcp.core.workspace.SecureProjectFileReader;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceFingerprints;
import io.github.hakjuoh.protege_mcp.jobs.JobArtifact;
import io.github.hakjuoh.protege_mcp.jobs.JobAdmission;
import io.github.hakjuoh.protege_mcp.jobs.JobCancelResult;
import io.github.hakjuoh.protege_mcp.jobs.JobCommitLease;
import io.github.hakjuoh.protege_mcp.jobs.JobDescriptor;
import io.github.hakjuoh.protege_mcp.jobs.JobDigests;
import io.github.hakjuoh.protege_mcp.jobs.JobException;
import io.github.hakjuoh.protege_mcp.jobs.JobInputIdentity;
import io.github.hakjuoh.protege_mcp.jobs.JobOwner;
import io.github.hakjuoh.protege_mcp.jobs.JobPage;
import io.github.hakjuoh.protege_mcp.jobs.JobPreCommitGuard;
import io.github.hakjuoh.protege_mcp.jobs.JobResultType;
import io.github.hakjuoh.protege_mcp.jobs.JobStartResult;
import io.github.hakjuoh.protege_mcp.jobs.JobSubmission;
import io.github.hakjuoh.protege_mcp.jobs.JobTask;
import io.github.hakjuoh.protege_mcp.jobs.JobTaskOutput;
import io.github.hakjuoh.protege_mcp.jobs.JobType;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.reasoner.CapabilityStatus;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationArtifact;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationException;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationInputIdentity;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Live-only public adapter for the bounded per-window asynchronous job runtime. */
public final class JobTools {
    private static final ObjectMapper JSON = ContractJson.mapper();
    private static final ReasonerCapabilityRegistry REASONER_PROFILES =
            new ReasonerCapabilityRegistry();
    private static final long MAX_SNAPSHOT_AXIOMS = 500_000L;
    private static final long MAX_SECONDARY_BYTES = 64L * 1024 * 1024;

    private JobTools() {
    }

    public static void register(ToolRegistry tools, ToolContext context) {
        tools.tool("start_job", (exchange, request) ->
                start(context, exchange, Tools.args(request)));
        tools.tool("get_job", (exchange, request) -> {
            Map<String, Object> arguments = Tools.args(request);
            requireKeys(arguments, Set.of("job_id"));
            JobOwner owner = owner(context, principal(exchange));
            JobDescriptor descriptor = call(() -> context.jobs().get(
                    owner, Tools.reqString(arguments, "job_id")));
            return Tools.ok(Map.of("job", jsonMap(descriptor)));
        });
        tools.tool("cancel_job", (exchange, request) -> {
            Map<String, Object> arguments = Tools.args(request);
            requireKeys(arguments, Set.of("job_id"));
            JobOwner owner = owner(context, principal(exchange));
            JobCancelResult cancelled = call(() -> context.jobs().cancel(
                    owner, Tools.reqString(arguments, "job_id")));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("job", jsonMap(cancelled.job()));
            result.put("outcome", cancelled.outcome().id());
            return Tools.ok(result);
        });
        tools.tool("list_jobs", (exchange, request) -> {
            Map<String, Object> arguments = Tools.args(request);
            requireKeys(arguments, Set.of("limit", "cursor"));
            JobPage page = call(() -> context.jobs().list(
                    owner(context, principal(exchange)),
                    Tools.optInt(arguments, "limit", 50),
                    Tools.optString(arguments, "cursor")));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobs", page.jobs().stream().map(JobTools::jsonMap).toList());
            if (page.nextCursor() != null) result.put("next_cursor", page.nextCursor());
            return Tools.ok(result);
        });
        tools.tool("export_job_artifact", (exchange, request) ->
                JobArtifactExporter.export(
                        context, exchange, Tools.args(request)));
    }

    private static CallToolResult start(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> arguments) {
        requireKeys(arguments, Set.of("type", "idempotency_key", "request"));
        JobType type;
        try {
            type = JobType.fromId(Tools.reqString(arguments, "type"));
        } catch (IllegalArgumentException invalid) {
            throw prevented("unknown_job_type", "Unknown asynchronous job type.", false);
        }
        Map<String, Object> request = object(arguments.get("request"), "request");
        AuthenticatedPrincipal principal = principal(exchange);
        Set<String> required = required(type);
        requireAuthorized(principal, required);
        String idempotencyKey = Tools.reqString(arguments, "idempotency_key");
        JobOwner owner = owner(context, principal);
        DirectAccessPolicy.Rules access = DirectAccessPolicy.resolve(
                context, exchange, Tools.optString(request, "policy_path"));
        try (JobAdmission admission = call(() -> context.reserveJob(
                access.policy(), owner, type, idempotencyKey))) {
            CapturedOperation operation = switch (type) {
                case CLASSIFICATION -> captureClassification(
                        context, principal, request, access);
                case PROJECT_QC -> captureProjectQc(
                        context, principal, request, access);
                case SEMANTIC_DIFF -> captureSemanticDiff(
                        context, principal, request, access);
                case INFERENCE_MATERIALIZATION -> captureMaterialization(
                        context, exchange, principal, request, access);
            };
            JobSubmission submission = new JobSubmission(
                    owner, type, idempotencyKey,
                    operation.identity(), required, operation.cancellationProven(),
                    operation.publicationGuard(), operation.task());
            JobStartResult started = call(() -> context.startJob(submission, admission));
            return Tools.ok(Map.of(
                    "job", jsonMap(started.job()), "reused", started.reused()));
        }
    }

    private static CapturedOperation captureClassification(ToolContext context,
            AuthenticatedPrincipal principal, Map<String, Object> request,
            DirectAccessPolicy.Rules access) {
        requireKeys(request, Set.of("limit", "policy_path"));
        int limit = Tools.optInt(request, "limit", 100);
        if (limit < 0 || limit > 10_000) {
            throw prevented("invalid_request",
                    "classification limit must be between 0 and 10000.", false);
        }
        ProjectCoordinates project = project(context, access.policy());
        ClassificationCapture captured = context.access().compute(manager -> {
            OWLOntology active = manager.getActiveOntology();
            requireSnapshotBound(active);
            IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                    manager.getOWLReasonerManager());
            if (spec == null) {
                throw prevented("job_reasoner_required",
                        "Select a reasoner before starting classification.", false);
            }
            ModelRevision revision = context.revisions().current(
                    manager, project.importLockDigest(), project.policy().digest()).revision();
            String closure = WorkspaceFingerprints.closure(active.getImportsClosure());
            OWLOntology snapshot = RdfQueryService.buildSnapshotOntology(
                    OWLManager.createOWLOntologyManager(), active.getOntologyID(),
                    active.getImportsClosure());
            return new ClassificationCapture(revision, closure, spec, snapshot);
        });
        try (CaptureOwner captures = new CaptureOwner()
                .add(() -> cleanup(captured.snapshot()))) {
            boolean cancellation = context.reasonerCancellation().proven(captured.spec());
            if (!cancellation) {
                throw prevented("job_reasoner_not_cancellable",
                        "The exact reasoner profile has not proven bounded cancellation.",
                        false);
            }
            ReasonerCapabilityReport capabilities = REASONER_PROFILES.report(
                    captured.spec().capabilityIdentity());
            if (capabilities.owlStatus("class_hierarchy") != CapabilityStatus.SUPPORTED) {
                throw prevented("job_classification_not_supported",
                        "The exact reasoner profile does not support class-hierarchy classification.",
                        false);
            }
            String reasonerDigest = captured.spec().capabilityIdentity().profileKey();
            JobInputIdentity identity = identity(project, captured.revision(),
                    captured.closureFingerprint(), reasonerDigest, request, List.of());
            JobPreCommitGuard guard = guard(context, principal, project, identity,
                    reasonerDigest, null);
            ReasonerCancellationController reasonerCancellation =
                    new ReasonerCancellationController();
            JobTask task = captures.transfer(execution -> {
                execution.progress("classifying",
                        "Classifying the captured ontology snapshot.");
                OWLReasoner reasoner = null;
                try {
                    execution.checkCancelled();
                    reasoner = captured.spec().create(captured.snapshot());
                    if (!reasonerCancellation.register(reasoner)) {
                        execution.checkCancelled();
                    }
                reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                CapabilityStatus consistencyCapability =
                        capabilities.owlStatus("consistency");
                CapabilityStatus satisfiabilityCapability =
                        capabilities.owlStatus("satisfiability");
                String consistencyStatus = consistencyCapability.value();
                String unsatisfiableStatus = satisfiabilityCapability.value();
                Integer unsatisfiableCount = null;
                List<String> unsatisfiable = List.of();
                if (consistencyCapability == CapabilityStatus.SUPPORTED) {
                    boolean consistent = reasoner.isConsistent();
                    consistencyStatus = consistent ? "consistent" : "inconsistent";
                    if (!consistent) {
                        unsatisfiableStatus = "not_applicable";
                    } else if (satisfiabilityCapability == CapabilityStatus.SUPPORTED) {
                        unsatisfiable = reasoner.getUnsatisfiableClasses()
                                .getEntitiesMinusBottom().stream()
                                .map(value -> value.getIRI().toString()).sorted().toList();
                        unsatisfiableCount = unsatisfiable.size();
                        unsatisfiableStatus = "complete";
                    }
                }
                boolean capabilityLimited = !"consistent".equals(consistencyStatus)
                        && !"inconsistent".equals(consistencyStatus)
                        || !"complete".equals(unsatisfiableStatus)
                                && !"not_applicable".equals(unsatisfiableStatus);
                execution.checkCancelled();
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("kind", "classification");
                report.put("classification_completed", true);
                report.put("consistency_status", consistencyStatus);
                report.put("unsatisfiable_status", unsatisfiableStatus);
                report.put("unsatisfiable_count", unsatisfiableCount);
                report.put("unsatisfiable_classes",
                        unsatisfiable.stream().limit(limit).toList());
                report.put("unsatisfiable_truncated", unsatisfiable.size() > limit);
                report.put("capability_limited", capabilityLimited);
                report.put("reasoner", captured.spec().capabilityIdentity().toMap());
                JobArtifact.Reference artifact = execution.stageArtifact(
                        "application/json", jsonBytes(report));
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("kind", "classification");
                summary.put("classification_completed", true);
                summary.put("consistency_status", consistencyStatus);
                summary.put("unsatisfiable_status", unsatisfiableStatus);
                summary.put("unsatisfiable_count", unsatisfiableCount);
                summary.put("capability_limited", capabilityLimited);
                summary.put("reasoner_digest", reasonerDigest);
                summary.put("report_artifact_id", artifact.artifactId());
                return new JobTaskOutput(
                        JobResultType.CLASSIFICATION, summary, false);
                } finally {
                    reasonerCancellation.release(reasoner);
                    reasonerCancellation.awaitStoppedUninterruptibly(
                            Duration.ofSeconds(5));
                }
            }, reasonerCancellation::requestCancellation);
            return new CapturedOperation(identity, true, guard, task);
        }
    }

    private static CapturedOperation captureProjectQc(ToolContext context,
            AuthenticatedPrincipal principal, Map<String, Object> request,
            DirectAccessPolicy.Rules access) {
        requireKeys(request, Set.of("limit", "policy_path", "lock_mode"));
        String policyPath = Tools.optString(request, "policy_path");
        Map<String, Object> authorized = access.authorizedPolicyArguments(request);
        ProjectQcTools.CapturedJob captured =
                ProjectQcTools.captureJob(context, authorized, access);
        try (CaptureOwner captures = new CaptureOwner()
                .add(() -> ProjectQcTools.discardJob(captured))) {
            ProjectPolicy policy = captured.policy();
            ProjectCoordinates currentProject = project(context, policy);
            if (!captured.preflightAssetDigest().equals(
                    currentProject.preflightAssetDigest())
                    || !Objects.equals(captured.importLockDigest(),
                            currentProject.importLockDigest())) {
                throw prevented("job_input_changed",
                        "Project policy assets changed after project-QC capture.", true);
            }
            ProjectCoordinates project = new ProjectCoordinates(
                    policy, captured.importLockDigest(),
                    currentProject.mappingRevision(),
                    captured.preflightAssetDigest());
            QcSuiteTools.CapturedExecution qc = captured.captured();
            ModelRevision revision = qc.modelRevision();
            IsolatedReasonerSpec spec = qc.reasonerSpec();
            String reasonerDigest = spec == null
                    ? null : spec.capabilityIdentity().profileKey();
            boolean cancellation = spec == null
                    || context.reasonerCancellation().proven(spec);
            if (!cancellation) {
                throw prevented("job_reasoner_not_cancellable",
                        "The exact project-QC reasoner profile has not proven bounded cancellation.",
                        false);
            }
            JobInputIdentity identity = identity(project, revision,
                    qc.closureFingerprint(), reasonerDigest, authorized, List.of());
            JobPreCommitGuard guard = guard(context, principal, project, identity,
                    reasonerDigest, null);
            JobTask task = captures.transfer(execution -> {
                execution.progress("project_qc",
                        "Running project QC over the captured snapshot.");
                execution.checkCancelled();
                Map<String, Object> report;
                try {
                    report = ProjectQcTools.runJob(captured);
                } catch (ToolArgException failure) {
                    throw jobFailure(failure);
                }
                execution.checkCancelled();
                JobArtifact.Reference artifact = execution.stageArtifact(
                        "application/json", jsonBytes(report));
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("kind", "project_qc");
                summary.put("gate", enumValue(report.get("gate"),
                        Set.of("pass", "fail", "error"), "error"));
                summary.put("stages_ran", number(report.get("stages_ran")));
                summary.put("stages_skipped", number(report.get("stages_skipped")));
                summary.put("finding_count",
                        report.get("findings") instanceof List<?> findings
                                ? findings.size() : 0);
                summary.put("semantic_fingerprint",
                        String.valueOf(report.getOrDefault(
                                "semantic_fingerprint", revision.semanticFingerprint())));
                summary.put("report_artifact_id", artifact.artifactId());
                return new JobTaskOutput(JobResultType.PROJECT_QC, summary, false);
            }, () -> { });
            return new CapturedOperation(identity, true, guard, task);
        }
    }

    private static CapturedOperation captureSemanticDiff(ToolContext context,
            AuthenticatedPrincipal principal, Map<String, Object> request,
            DirectAccessPolicy.Rules baseAccess) {
        requireKeys(request, Set.of(
                "right_document", "limit", "policy_path", "network", "include_imports"));
        if (Tools.optBool(request, "include_imports", false)) {
            throw prevented("job_semantic_diff_scope_unsupported",
                    "The 0.8 asynchronous semantic diff captures one secondary root document; "
                            + "use synchronous semantic_diff for import-closure comparison.", false);
        }
        int limit = Tools.optInt(request, "limit", 50);
        if (limit < 0 || limit > 10_000) {
            throw prevented("invalid_request",
                    "semantic-diff limit must be between 0 and 10000.", false);
        }
        String rightDocument = Tools.reqString(request, "right_document");
        String network = Tools.optString(request, "network");
        if (network != null && !"deny".equals(network)) {
            throw prevented("job_semantic_diff_network_unsupported",
                    "Asynchronous semantic diff requires network=deny.", false);
        }
        DirectAccessPolicy.Rules access = baseAccess.withRequestNetwork(
                network);
        DirectAccessPolicy.Source source = access.authorizeSource(rightDocument);
        if (source.network()) {
            throw prevented("job_semantic_diff_network_unsupported",
                    "Asynchronous semantic diff requires a project-confined local secondary "
                            + "document captured once; use synchronous semantic_diff for network input.",
                    false);
        }
        Path rightPath = Path.of(source.value()).toAbsolutePath().normalize();
        ProjectCoordinates project = project(context, access.policy());
        byte[] rightBytes = readBounded(
                project.policy().projectRoot(), rightPath);
        String rightDigest = JobDigests.digest(rightBytes);
        String provenanceDigest = JobDigests.digest(rightPath.toString());
        List<String> unresolved = new ArrayList<>();
        try (CaptureOwner captures = new CaptureOwner()) {
            OWLOntology right = DiffTools.loadRootDocument(
                    rightBytes, rightPath, unresolved);
            captures.add(() -> cleanup(right));
            SemanticCapture captured = context.access().compute(manager -> {
                OWLOntology active = manager.getActiveOntology();
                requireSnapshotBound(active);
                ModelRevision revision = context.revisions().current(
                        manager, project.importLockDigest(), project.policy().digest()).revision();
                String closure = WorkspaceFingerprints.closure(active.getImportsClosure());
                OWLOntology left = RdfQueryService.buildSnapshotOntology(
                        OWLManager.createOWLOntologyManager(), active.getOntologyID(),
                        Set.of(active));
                return new SemanticCapture(revision, closure, left, right, unresolved);
            });
            captures.add(() -> cleanup(captured.left()));
            List<JobInputIdentity.SecondaryInput> secondary = List.of(
                    new JobInputIdentity.SecondaryInput(
                            "right_document", rightDigest, provenanceDigest));
            JobInputIdentity identity = identity(project, captured.revision(),
                    captured.closureFingerprint(), null, request, secondary);
            JobPreCommitGuard guard = guard(context, principal, project, identity,
                    null, new SecondaryPath(rightPath, provenanceDigest));
            JobTask task = captures.transfer(execution -> {
                execution.progress("semantic_diff",
                        "Comparing the captured ontology documents.");
                execution.checkCancelled();
                Map<String, Object> report = SemanticDiffService.diff(
                        captured.left(), captured.right(), false, limit,
                        captured.unresolvedImports());
                execution.checkCancelled();
                JobArtifact.Reference artifact = execution.stageArtifact(
                        "application/json", jsonBytes(report));
                Map<?, ?> asserted = report.get("asserted_axioms") instanceof Map<?, ?> map
                        ? map : Map.of();
                Map<?, ?> added = asserted.get("added") instanceof Map<?, ?> map
                        ? map : Map.of();
                Map<?, ?> removed = asserted.get("removed") instanceof Map<?, ?> map
                        ? map : Map.of();
                Map<?, ?> compatibility = report.get("compatibility") instanceof Map<?, ?> map
                        ? map : Map.of();
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("kind", "semantic_diff");
                summary.put("identical", Boolean.TRUE.equals(report.get("identical")));
                summary.put("compatibility", enumValue(
                        compatibility.get("classification"),
                        Set.of("metadata_only", "non_breaking", "potentially_breaking"),
                        "potentially_breaking"));
                summary.put("added_axioms", number(added.get("count")));
                summary.put("removed_axioms", number(removed.get("count")));
                summary.put("report_artifact_id", artifact.artifactId());
                return new JobTaskOutput(JobResultType.SEMANTIC_DIFF, summary, false);
            }, () -> { });
            return new CapturedOperation(identity, true, guard, task);
        }
    }

    private static CapturedOperation captureMaterialization(ToolContext context,
            McpSyncServerExchange exchange, AuthenticatedPrincipal principal,
            Map<String, Object> request, DirectAccessPolicy.Rules access) {
        requireKeys(request, Set.of(
                "categories", "destination", "provenance", "limits", "policy_path"));
        MaterializationTools.CapturedJob captured =
                MaterializationTools.captureJob(context, exchange, request, access);
        try (CaptureOwner captures = new CaptureOwner()
                .add(() -> MaterializationTools.discardJob(captured))) {
            MaterializationInputIdentity materialization = captured.inputIdentity();
            JobInputIdentity identity = new JobInputIdentity(
                    materialization.modelRevision(),
                    materialization.closureFingerprint(),
                    materialization.importLockDigest(),
                    materialization.mappingRevision(),
                    materialization.policyDigest(),
                    materialization.policyAssetDigest(),
                    materialization.reasonerIdentity().profileKey(),
                    requestDigest(request), List.of());
            ProjectCoordinates project = project(context, captured.policy());
            boolean cancellation = context.reasonerCancellation()
                    .proven(captured.reasonerSpec());
            if (!cancellation) {
                throw prevented("job_reasoner_not_cancellable",
                        "The exact materialization reasoner profile has not proven bounded cancellation.",
                        false);
            }
            JobPreCommitGuard guard = guard(context, principal, project, identity,
                    materialization.reasonerIdentity().profileKey(), null);
            JobTask task = captures.transfer(execution -> {
                execution.progress("materializing",
                        "Enumerating inferences over the captured ontology snapshot.");
                try {
                    execution.checkCancelled();
                    MaterializationArtifact artifact =
                            MaterializationTools.runJob(context, captured);
                    execution.checkCancelled();
                    Map<String, Object> report = artifact.report();
                    JobArtifact.Reference reportArtifact = execution.stageArtifact(
                            "application/json", jsonBytes(report));
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("kind", "inference_materialization");
                    summary.put("materialization_artifact_id", artifact.artifactId());
                    summary.put("materialization_artifact_fingerprint",
                            artifact.artifactFingerprint());
                    summary.put("materialization_digest", artifact.materializationDigest());
                    summary.put("axiom_count", artifact.axioms().size());
                    summary.put("report_artifact_id", reportArtifact.artifactId());
                    JobTaskOutput prepared = new JobTaskOutput(
                            JobResultType.INFERENCE_MATERIALIZATION, summary, false);
                    return execution.withCommitPermit(
                            prepared, guard, () -> {
                                MaterializationTools.publishJobArtifact(
                                        context, captured, artifact);
                                return prepared;
                            });
                } catch (MaterializationException failure) {
                    throw new JobException(failure.code(), failure.getMessage(),
                            failure.details(), failure.retryable());
                } catch (ToolArgException failure) {
                    throw jobFailure(failure);
                }
            }, () -> { });
            return new CapturedOperation(identity, true, guard, task);
        }
    }


    private static JobPreCommitGuard guard(ToolContext context,
            AuthenticatedPrincipal principal, ProjectCoordinates project,
            JobInputIdentity expected, String reasonerDigest, SecondaryPath secondary) {
        return () -> {
            PrincipalExecutionGate.Lease lease;
            try {
                lease = context.executions().acquire(principal);
            } catch (ToolArgException revoked) {
                throw jobFailure(revoked);
            }
            try {
                ProjectCoordinates current = project(
                        context, project.policy().path() == null
                                ? null : project.policy().path().toString());
                VerificationCapture live = context.access().compute(manager -> {
                    ModelRevision revision = context.revisions().current(
                            manager, current.importLockDigest(),
                            current.policy().digest()).revision();
                    String closure = WorkspaceFingerprints.closure(
                            manager.getActiveOntology().getImportsClosure());
                    String currentReasoner = null;
                    if (reasonerDigest != null) {
                        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                                manager.getOWLReasonerManager());
                        currentReasoner = spec == null
                                ? null : spec.capabilityIdentity().profileKey();
                    }
                    return new VerificationCapture(revision, closure, currentReasoner);
                });
                List<JobInputIdentity.SecondaryInput> inputs = List.of();
                if (secondary != null) {
                    byte[] bytes = readBounded(
                            current.policy().projectRoot(), secondary.path());
                    inputs = List.of(new JobInputIdentity.SecondaryInput(
                            "right_document", JobDigests.digest(bytes),
                            secondary.provenanceDigest()));
                }
                JobInputIdentity actual = identity(current, live.revision(),
                        live.closureFingerprint(), live.reasonerDigest(),
                        Map.of(), inputs, expected.normalizedRequestDigest());
                if (!expected.identityDigest().equals(actual.identityDigest())) {
                    throw new JobException("job_input_changed",
                            "Ontology, policy, mapping, reasoner, or secondary input changed "
                                    + "before result publication.",
                            Map.of("effects_prevented", true), true);
                }
                return lease::close;
            } catch (RuntimeException | Error failure) {
                lease.close();
                throw failure;
            }
        };
    }

    private static ProjectCoordinates project(ToolContext context,
            McpSyncServerExchange exchange, String policyPath) {
        DirectAccessPolicy.Rules rules =
                DirectAccessPolicy.resolve(context, exchange, policyPath);
        return project(context, rules.policy());
    }

    private static ProjectCoordinates project(ToolContext context, String policyPath) {
        RevisionTools.PolicyState state =
                RevisionTools.resolvePolicy(context, policyPath);
        if (state.error() != null) {
            throw prevented("job_policy_unavailable",
                    "The project policy could not be resolved.", true);
        }
        return project(context, state.policy());
    }

    private static ProjectCoordinates project(
            ToolContext context, ProjectPolicy policy) {
        if (policy == null || !policy.loaded() || !policy.valid()
                || policy.digest() == null || policy.projectRoot() == null) {
            throw prevented("job_policy_required",
                    "A valid discovered project policy is required for asynchronous jobs.",
                    false);
        }
        return new ProjectCoordinates(policy, RevisionTools.digestImportLock(policy),
                MaterializationTools.mappingRevision(policy),
                RevisionTools.preflightDigest(policy));
    }

    private static JobInputIdentity identity(ProjectCoordinates project,
            ModelRevision revision, String closure, String reasonerDigest,
            Map<String, Object> request,
            List<JobInputIdentity.SecondaryInput> secondaryInputs) {
        return identity(project, revision, closure, reasonerDigest, request,
                secondaryInputs, requestDigest(request));
    }

    private static JobInputIdentity identity(ProjectCoordinates project,
            ModelRevision revision, String closure, String reasonerDigest,
            Map<String, Object> request,
            List<JobInputIdentity.SecondaryInput> secondaryInputs,
            String normalizedRequestDigest) {
        return new JobInputIdentity(revision, closure,
                project.importLockDigest(), project.mappingRevision(),
                project.policy().digest(), project.preflightAssetDigest(),
                reasonerDigest, normalizedRequestDigest, secondaryInputs);
    }

    private static String requestDigest(Map<String, Object> request) {
        return JobDigests.digest(jsonBytes(canonicalJson(
                request == null ? Map.of() : request)));
    }

    private static Object canonicalJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String text)) {
                    throw prevented("invalid_request",
                            "Job request object keys must be strings.", false);
                }
                sorted.put(text, canonicalJson(nested));
            });
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JobTools::canonicalJson).toList();
        }
        return value;
    }

    private static Set<String> required(JobType type) {
        return switch (type) {
            case CLASSIFICATION -> Set.of(
                    Capability.ONTOLOGY_READ.value(),
                    Capability.FILESYSTEM_PROJECT_READ.value());
            case PROJECT_QC, SEMANTIC_DIFF, INFERENCE_MATERIALIZATION -> Set.of(
                    Capability.ONTOLOGY_READ.value(),
                    Capability.FILESYSTEM_PROJECT_READ.value());
        };
    }

    private static void requireAuthorized(
            AuthenticatedPrincipal principal, Set<String> required) {
        List<String> missing = CapabilityAuthorizer.missing(
                principal == null ? null : principal.capabilities(), required);
        if (!missing.isEmpty()) {
            throw new ToolArgException("authorization_denied",
                    "Authorization denied for asynchronous job; missing capabilities: "
                            + String.join(", ", missing) + ".",
                    Map.of("missing_capabilities", missing,
                            "effects_prevented", true), false);
        }
    }

    private static JobOwner owner(
            ToolContext context, AuthenticatedPrincipal principal) {
        AuthenticatedPrincipal effective = principal == null
                ? AuthenticatedPrincipal.staticAdmin() : principal;
        return new JobOwner(context.revisions().workspaceId(),
                JobDigests.digest(effective.type(), effective.clientId()),
                JobDigests.digest(effective.clientId()),
                JobDigests.digest(effective.grantId()));
    }

    private static AuthenticatedPrincipal principal(McpSyncServerExchange exchange) {
        if (exchange == null) return AuthenticatedPrincipal.staticAdmin();
        Object value = exchange.transportContext() == null ? null
                : exchange.transportContext().get(AuthenticatedPrincipal.CONTEXT_KEY);
        return value instanceof AuthenticatedPrincipal principal ? principal : null;
    }

    private static byte[] readBounded(Path projectRoot, Path path) {
        try {
            return SecureProjectFileReader.capture(
                    projectRoot, path, MAX_SECONDARY_BYTES).bytes();
        } catch (ToolArgException known) {
            throw known;
        } catch (IOException failure) {
            throw prevented("job_secondary_input_invalid",
                    "The semantic-diff secondary input could not be captured as a stable, "
                            + "bounded project file.", true);
        }
    }

    private static void requireSnapshotBound(OWLOntology active) {
        long axioms = active.getImportsClosure().stream()
                .mapToLong(OWLOntology::getAxiomCount).sum();
        if (axioms > MAX_SNAPSHOT_AXIOMS) {
            throw prevented("job_snapshot_too_large",
                    "The ontology imports closure exceeds the live asynchronous snapshot bound.",
                    false);
        }
    }

    /** Exception-safe owner that transfers detached captures into exactly one retained task. */
    private static final class CaptureOwner implements AutoCloseable {
        private final List<Runnable> cleanup = new ArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();
        private boolean transferred;

        CaptureOwner add(Runnable action) {
            if (transferred) {
                throw new IllegalStateException("capture ownership was already transferred");
            }
            cleanup.add(Objects.requireNonNull(action, "action"));
            return this;
        }

        JobTask transfer(JobTask delegate, Runnable cancellation) {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(cancellation, "cancellation");
            if (transferred) {
                throw new IllegalStateException("capture ownership was already transferred");
            }
            transferred = true;
            return new JobTask() {
                private final java.util.concurrent.atomic.AtomicBoolean claimed =
                        new java.util.concurrent.atomic.AtomicBoolean();

                @Override
                public JobTaskOutput execute(
                        io.github.hakjuoh.protege_mcp.jobs.JobExecution execution)
                        throws Exception {
                    if (!claimed.compareAndSet(false, true)) {
                        throw new IllegalStateException(
                                "job task capture was already consumed");
                    }
                    try {
                        return delegate.execute(execution);
                    } finally {
                        release();
                    }
                }

                @Override
                public void requestCancellation() {
                    cancellation.run();
                }

                @Override
                public void discard() {
                    if (claimed.compareAndSet(false, true)) release();
                }
            };
        }

        private void release() {
            if (!released.compareAndSet(false, true)) return;
            for (int index = cleanup.size() - 1; index >= 0; index--) {
                try {
                    cleanup.get(index).run();
                } catch (RuntimeException | LinkageError ignored) {
                    // Every remaining capture must still receive its cleanup attempt.
                }
            }
        }

        @Override
        public void close() {
            if (!transferred) release();
        }
    }

    private static void cleanup(OWLOntology ontology) {
        if (ontology == null) return;
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        for (OWLOntology loaded : new ArrayList<>(manager.getOntologies())) {
            manager.removeOntology(loaded);
        }
    }

    private static byte[] jsonBytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("job report is not JSON serializable", invalid);
        }
    }

    private static Map<String, Object> jsonMap(Object value) {
        return JSON.convertValue(value, new TypeReference<Map<String, Object>>() { });
    }

    private static int number(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static String enumValue(
            Object value, Set<String> allowed, String fallback) {
        String candidate = value == null ? null : String.valueOf(value);
        return allowed.contains(candidate) ? candidate : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw prevented("invalid_request", field + " must be an object.", false);
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    private static void requireKeys(Map<String, Object> arguments, Set<String> allowed) {
        Set<String> unknown = new java.util.TreeSet<>(arguments.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw prevented("invalid_request",
                    "Unknown argument(s): " + String.join(", ", unknown), false);
        }
    }

    private static JobException jobFailure(ToolArgException failure) {
        Map<String, Object> details = new LinkedHashMap<>(failure.details());
        details.remove("outcome_unknown");
        return new JobException(failure.code(), failure.getMessage(),
                details, failure.retryable());
    }

    private static ToolArgException prevented(
            String code, String message, boolean retryable) {
        return new ToolArgException(code, message,
                Map.of("effects_prevented", true), retryable);
    }

    private static <T> T call(java.util.concurrent.Callable<T> action) {
        try {
            return action.call();
        } catch (JobException failure) {
            throw new ToolArgException(failure.error().code(),
                    failure.error().message(), failure.error().details(),
                    failure.error().retryable());
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ProjectCoordinates(ProjectPolicy policy, String importLockDigest,
            String mappingRevision, String preflightAssetDigest) { }

    private record ClassificationCapture(ModelRevision revision,
            String closureFingerprint, IsolatedReasonerSpec spec, OWLOntology snapshot) { }

    private record SemanticCapture(ModelRevision revision, String closureFingerprint,
            OWLOntology left, OWLOntology right, List<String> unresolvedImports) { }

    private record VerificationCapture(ModelRevision revision,
            String closureFingerprint, String reasonerDigest) { }

    private record SecondaryPath(Path path, String provenanceDigest) { }

    private record CapturedOperation(JobInputIdentity identity,
            boolean cancellationProven, JobPreCommitGuard publicationGuard,
            JobTask task) { }

}
