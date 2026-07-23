package io.github.hakjuoh.protege_mcp.core.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;

import io.github.hakjuoh.protege_mcp.core.owl.VerifiedOntologyRoundTrip;
import io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.core.workspace.FilesystemProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceTransaction;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationArtifact;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationArtifactStore;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationCollisions;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationCommitResults;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationException;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationInputDigests;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationInputIdentity;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationPolicy;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationRequest;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationRequests;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationService;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerIdentity;

/** Preview and atomic project-file publication for the headless materialization tools. */
final class HeadlessMaterializationService {
    private static final String ARTIFACT_OWNER = ArtifactStore.sha256(
            "stdio-local".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final Path policyPath;
    private final OWLReasonerFactory reasonerFactory;
    private final Supplier<FilesystemProjectWorkspace> workspaces;
    private final MaterializationService materializations;
    private final MaterializationArtifactStore artifacts;

    HeadlessMaterializationService(Path policyPath, OWLReasonerFactory reasonerFactory,
            Clock clock, Supplier<FilesystemProjectWorkspace> workspaces) {
        this.policyPath = java.util.Objects.requireNonNull(policyPath, "policyPath");
        this.reasonerFactory = java.util.Objects.requireNonNull(
                reasonerFactory, "reasonerFactory");
        this.workspaces = java.util.Objects.requireNonNull(workspaces, "workspaces");
        Clock utc = java.util.Objects.requireNonNull(clock, "clock");
        this.materializations = new MaterializationService(utc);
        this.artifacts = new MaterializationArtifactStore(
                utc, 32, 128L * 1024 * 1024);
    }

    Map<String, Object> preview(Map<String, Object> arguments) throws IOException {
        requireFixedPolicyPath(arguments);
        final MaterializationRequest request;
        try {
            request = MaterializationRequests.parse(arguments);
        } catch (IllegalArgumentException invalid) {
            throw failure("invalid_request", invalid.getMessage(), false);
        }
        if (!"project_file".equals(request.destination().kind())) {
            throw failure("materialization_destination_unavailable",
                    "The headless adapter supports only project_file destinations.", false);
        }
        try (WorkspaceSnapshot snapshot = workspaces.get().capture()) {
            ProjectPolicy policy = snapshot.policy();
            ReasonerCapabilityReport profile = HeadlessReasonerProfiles.report(
                    reasonerFactory, policy);
            MaterializationPolicy.requireAllowed(policy, request, profile.identity());
            Path target = target(snapshot, request.destination().identifier());
            rejectSourceDestination(snapshot, target);
            MaterializationInputIdentity identity = new MaterializationInputIdentity(
                    snapshot.revision(), snapshot.closureFingerprint(),
                    snapshot.importLockDigest(), mappingRevision(policy), policy.digest(),
                    snapshotAssetDigest(snapshot),
                    policy.path() == null ? null : policy.path().toString(), profile.identity());
            long timeout = HeadlessReasonerProfiles.timeoutMillis(policy);
            OWLOntologyManager isolatedManager = OWLManager.createOWLOntologyManager();
            OWLOntology isolated = RdfQueryService.buildSnapshotOntology(
                    isolatedManager, snapshot.root().getOntologyID(), snapshot.closure());
            MaterializationArtifact artifact = materializations.preview(isolated,
                    ontology -> reasonerFactory.createReasoner(ontology,
                            new SimpleConfiguration(timeout)), profile, request, identity);
            artifacts.put(ARTIFACT_OWNER, artifact);
            return new LinkedHashMap<>(artifact.report());
        } catch (MaterializationException failure) {
            throw failure(failure);
        }
    }

    Map<String, Object> commit(Map<String, Object> arguments) throws IOException {
        requireFixedPolicyPath(arguments);
        String artifactId = requiredString(arguments, "artifact_id");
        String fingerprint = requiredString(arguments, "artifact_fingerprint");
        MaterializationArtifact artifact;
        try {
            artifact = artifacts.require(ARTIFACT_OWNER, artifactId, fingerprint);
        } catch (MaterializationException failure) {
            throw failure(failure);
        }
        if (!materializations.verifyArtifact(artifact)) {
            throw failure("materialization_artifact_corrupt",
                    "The materialization artifact digest could not be verified.", false);
        }
        String collisionMode = string(arguments, "collision_mode");
        if (collisionMode == null) collisionMode = "reject";
        if (!Set.of("reject", "merge", "replace").contains(collisionMode)) {
            throw failure("invalid_request",
                    "collision_mode must be reject, merge, or replace.", false);
        }
        boolean overwrite = bool(arguments, "overwrite", false);
        String expectedTargetDigest = string(arguments, "expected_target_digest");
        try (WorkspaceSnapshot snapshot = workspaces.get().capture()) {
            ProjectPolicy policy = snapshot.policy();
            ReasonerCapabilityReport profile = HeadlessReasonerProfiles.report(
                    reasonerFactory, policy);
            MaterializationPolicy.requireAllowed(policy, artifact.request(), profile.identity());
            recheckIdentity(snapshot, policy, profile.identity(), artifact);
            Path requestedTarget = target(snapshot,
                    artifact.request().destination().identifier());
            WorkspaceTransaction opened;
            try {
                opened = workspaces.get().beginTransaction(snapshot, requestedTarget, true,
                        artifact.request().limits().maxBytes());
            } catch (WorkspaceTransaction.AmbiguousRecoveryException ambiguous) {
                throw ambiguousRecovery(ambiguous);
            } catch (WorkspaceTransaction.OrphanRecoveryAppliedException applied) {
                throw orphanRecoveryApplied(applied);
            } catch (WorkspaceTransaction.ExistingTargetSizeException exceeded) {
                throw failure("materialization_bound_exceeded",
                        "The existing destination exceeds the explicit max_bytes limit.", false);
            } catch (IOException unsafeTarget) {
                throw failure("materialization_destination_invalid",
                        "The destination is not a safe project-confined regular file target.",
                        false);
            }
            try (WorkspaceTransaction transaction = opened) {
                rejectSourceDestination(snapshot, transaction.target());
                boolean exists = transaction.targetExisted();
                String baselineDigest = transaction.baselineSha256();
                if (expectedTargetDigest != null
                        && !expectedTargetDigest.equals(baselineDigest)) {
                    throw failure("materialization_target_changed",
                            "The destination digest differs from expected_target_digest.", true);
                }
                OWLOntologyManager outputManager = OWLManager.createOWLOntologyManager();
                try {
                    OWLOntology output = exists
                            ? loadLocalOntology(outputManager, transaction.snapshotTarget(
                                    artifact.request().limits().maxBytes()))
                            : outputManager.createOntology(IRI.create(
                                    "urn:protege-mcp:materialization:"
                                    + artifact.materializationDigest().substring(
                                            "sha256:".length())));
                    MaterializationCollisions.State collisions =
                            MaterializationCollisions.analyze(output, artifact.axioms());
                    if (collisions.exactOnly(artifact.axioms().size())
                            || ("merge".equals(collisionMode)
                                    && collisions.allExact(artifact.axioms().size()))) {
                        transaction.verifyBaseline();
                        return MaterializationCommitResults.result(artifact, false, 0,
                                collisions.existing(), false, baselineDigest);
                    }
                    requireReplacementPermit(exists, overwrite,
                            expectedTargetDigest, collisions, collisionMode);
                    if ("replace".equals(collisionMode)) {
                        outputManager.removeAxioms(output, collisions.differentForms());
                    }
                    int before = output.getAxiomCount();
                    outputManager.addAxioms(output, artifact.axioms());
                    int added = output.getAxiomCount() - before;
                    if (!MaterializationCollisions.analyze(output, artifact.axioms())
                            .commitComplete(artifact.axioms().size(), collisionMode)) {
                        throw failure("materialization_commit_incomplete",
                                "The in-memory destination did not reach the requested collision state.",
                                false);
                    }
                    VerifiedOntologyRoundTrip.Result serialized = serialize(output, artifact);
                    WorkspaceTransaction.Stage stage = transaction.stageBytes(
                            serialized.content());
                    if (!stage.sha256().equals(serialized.sha256())) {
                        throw failure("materialization_stage_mismatch",
                                "The staged file digest differs from verified serialization.",
                                false);
                    }
                    try {
                        WorkspaceTransaction.Commit committed = transaction.commit();
                        if (!committed.installedSha256().equals(serialized.sha256())) {
                            throw outcomeUnknown(
                                    "The installed file digest could not be verified.", null);
                        }
                        return MaterializationCommitResults.result(artifact, true, added,
                                collisions.existing(), false, committed.installedSha256());
                    } catch (WorkspaceTransaction.CommitAppliedException applied) {
                        throw outcomeUnknown(
                                "The file replacement completed but post-install verification failed.",
                                applied, Map.of(
                                        "replacement_applied", true,
                                        "post_install_verified", false,
                                        "intended_target_sha256",
                                        applied.commit().installedSha256()));
                    } catch (WorkspaceTransaction.GuardedReplacementException guarded) {
                        throw guardedReplacement(guarded);
                    } catch (WorkspaceTransaction.BackupAppliedException backupApplied) {
                        throw backupApplied(backupApplied);
                    } catch (WorkspaceTransaction.OrphanRecoveryAppliedException applied) {
                        throw orphanRecoveryApplied(applied);
                    } catch (WorkspaceTransaction.AmbiguousRecoveryException ambiguous) {
                        throw ambiguousRecovery(ambiguous);
                    } catch (IOException prevented) {
                        throw failure("materialization_file_commit_failed",
                                "The project file was preserved because materialization commit failed.",
                                false);
                    }
                } catch (OWLOntologyCreationException failure) {
                    throw failure("materialization_destination_invalid",
                            "The destination ontology could not be parsed or created.", false);
                } finally {
                    for (OWLOntology ontology : new ArrayList<>(outputManager.getOntologies())) {
                        outputManager.removeOntology(ontology);
                    }
                }
            }
        } catch (MaterializationException failure) {
            throw failure(failure);
        } catch (IOException failure) {
            throw failure("materialization_file_commit_failed",
                    "The project file was preserved because materialization preparation failed.",
                    false);
        }
    }

    private static void requireReplacementPermit(boolean exists, boolean overwrite,
            String expectedTargetDigest, MaterializationCollisions.State collisions,
            String collisionMode) {
        if (exists && !overwrite) {
            throw failure("materialization_target_exists",
                    "The project file exists; pass overwrite=true with its expected digest.",
                    false);
        }
        if (exists && expectedTargetDigest == null) {
            throw failure("materialization_target_digest_required",
                    "Replacing an existing project file requires expected_target_digest.", false);
        }
        if (collisions.logical() > 0 && "reject".equals(collisionMode)) {
            throw new HeadlessExecutionException("materialization_provenance_collision",
                    "The destination contains logical axioms with different provenance.",
                    Map.of("logical_collisions", collisions.logical(),
                            "effects_prevented", true), false, null);
        }
    }

    private static VerifiedOntologyRoundTrip.Result serialize(OWLOntology output,
            MaterializationArtifact artifact) throws IOException {
        try {
            return VerifiedOntologyRoundTrip.serialize(output,
                    new FunctionalSyntaxDocumentFormat(),
                    artifact.request().limits().maxBytes());
        } catch (VerifiedOntologyRoundTrip.ArtifactSizeException exceeded) {
            throw failure("materialization_bound_exceeded",
                    "The verified destination file exceeds the explicit max_bytes limit.", false);
        }
    }

    private void requireFixedPolicyPath(Map<String, Object> arguments) {
        String configured = string(arguments, "policy_path");
        if (configured == null) return;
        final Path requested;
        try {
            requested = Path.of(configured).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            throw failure("invalid_request", "policy_path is invalid.", false);
        }
        if (!policyPath.equals(requested)) {
            throw failure("materialization_policy_mismatch",
                    "The headless adapter is fixed to its startup policy path.", false);
        }
    }

    private static void recheckIdentity(WorkspaceSnapshot snapshot, ProjectPolicy policy,
            ReasonerIdentity reasoner, MaterializationArtifact artifact) {
        MaterializationInputIdentity expected = artifact.inputIdentity();
        boolean revisionMatches = expected.modelRevision().workspaceId()
                        .equals(snapshot.revision().workspaceId())
                && expected.modelRevision().semanticFingerprint()
                        .equals(snapshot.revision().semanticFingerprint())
                && expected.modelRevision().documentFingerprint()
                        .equals(snapshot.revision().documentFingerprint());
        boolean matches = revisionMatches
                && expected.closureFingerprint().equals(snapshot.closureFingerprint())
                && java.util.Objects.equals(expected.importLockDigest(),
                        snapshot.importLockDigest())
                && java.util.Objects.equals(expected.mappingRevision(), mappingRevision(policy))
                && expected.policyDigest().equals(policy.digest())
                && expected.policyAssetDigest().equals(snapshotAssetDigest(snapshot))
                && java.util.Objects.equals(expected.policyPath(),
                        policy.path() == null ? null : policy.path().toString())
                && expected.reasonerIdentity().profileKey().equals(reasoner.profileKey());
        if (!matches) {
            throw failure("materialization_input_changed",
                    "Ontology, imports, mappings, policy, or exact reasoner changed after preview.",
                    true);
        }
    }

    private static Path target(WorkspaceSnapshot snapshot, String configured)
            throws IOException {
        Path root = snapshot.policy().projectRoot().toRealPath();
        return confinedDestination(configured, root);
    }

    private static void rejectSourceDestination(WorkspaceSnapshot snapshot, Path target) {
        boolean source = snapshot.sources().stream().anyMatch(item -> {
            try {
                return item.original().toRealPath().equals(Files.exists(target)
                        ? target.toRealPath() : target.toAbsolutePath().normalize());
            } catch (IOException failure) {
                return true;
            }
        });
        if (source) {
            throw failure("materialization_source_write_denied",
                    "project_file cannot alias an ontology project input.", false);
        }
    }

    private static OWLOntology loadLocalOntology(OWLOntologyManager manager, Path target)
            throws OWLOntologyCreationException, IOException {
        Path directory = Files.createTempDirectory("protege-mcp-materialization-imports-")
                .toRealPath();
        Path placeholder = directory.resolve("missing.ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n");
            IRI local = IRI.create(placeholder.toUri());
            manager.getIRIMappers().add(ignored -> local);
            OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setFollowRedirects(false);
            return manager.loadOntologyFromOntologyDocument(
                    new FileDocumentSource(target.toFile()), configuration);
        } finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort for the private no-network import placeholder.
                    }
                });
            }
        }
    }

    private static String mappingRevision(ProjectPolicy policy) {
        try {
            return MaterializationInputDigests.mappingRevision(
                    policy, 64L * 1024 * 1024);
        } catch (IOException failure) {
            throw failure("materialization_mapping_unreadable",
                    "The policy mapping store could not be fingerprinted.", true);
        }
    }

    private static String snapshotAssetDigest(WorkspaceSnapshot snapshot) {
        List<String> identities = snapshot.sources().stream()
                .map(source -> source.kind() + "\u0000" + source.original() + "\u0000"
                        + source.sha256() + "\u0000" + source.bytes())
                .sorted().toList();
        return ArtifactStore.sha256(String.join("\n", identities)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Path confinedDestination(String configured, Path root) throws IOException {
        Path raw = Path.of(configured);
        Path candidate = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
        Path parent = candidate.getParent();
        if (parent == null || Files.isSymbolicLink(parent)) {
            throw new IOException("materialization destination has no safe parent");
        }
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(root.toRealPath())) {
            throw new IOException("materialization destination is outside the project");
        }
        return realParent.resolve(candidate.getFileName());
    }

    private static String requiredString(Map<String, Object> arguments, String key) {
        String value = string(arguments, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static boolean bool(Map<String, Object> arguments, String key,
            boolean fallback) {
        Object value = arguments.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(key + " must be boolean");
        }
        return flag;
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 4096) {
            throw new IllegalArgumentException(key + " must be a non-blank bounded string");
        }
        return text;
    }

    private static HeadlessExecutionException outcomeUnknown(String message, Throwable cause) {
        return outcomeUnknown(message, cause, Map.of());
    }

    private static HeadlessExecutionException outcomeUnknown(String message, Throwable cause,
            Map<String, Object> evidence) {
        Map<String, Object> details = new LinkedHashMap<>(evidence);
        details.put("outcome_unknown", true);
        details.put("mutation_outcome_unknown", true);
        details.put("retry_requires_state_check", true);
        return new HeadlessExecutionException("materialization_commit_outcome_unknown",
                message, details, false, cause);
    }

    static HeadlessExecutionException backupApplied(
            WorkspaceTransaction.BackupAppliedException applied) {
        WorkspaceTransaction.BackupSideEffect receipt = applied.receipt();
        boolean outcomeKnown = receipt.locationCurrent() && receipt.backupStateKnown()
                && receipt.targetStateKnown();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("effects_prevented", false);
        details.put("backup_publication_applied", true);
        details.put("backup_location_current", receipt.locationCurrent());
        details.put("backup_state_known", receipt.backupStateKnown());
        details.put("backup_verified", receipt.backupVerified());
        details.put("target_state_known", receipt.targetStateKnown());
        if (receipt.targetStateKnown()) {
            details.put("target_preserved", receipt.targetPreserved());
        }
        if (receipt.backupSha256() != null) {
            details.put("backup_sha256", receipt.backupSha256());
        }
        details.put("outcome_known", outcomeKnown);
        return new HeadlessExecutionException("materialization_backup_published",
                "Backup publication completed before destination publication failed; the receipt "
                        + "states which backup and target facts were verified.",
                details, false, applied);
    }

    static HeadlessExecutionException guardedReplacement(
            WorkspaceTransaction.GuardedReplacementException guarded) {
        WorkspaceTransaction.GuardedReplacementSideEffect receipt = guarded.receipt();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("effects_prevented", !(receipt.sourceMoved() || receipt.originalMoved()
                || receipt.publicationApplied() || receipt.backupPublished()));
        boolean outcomeKnown = receipt.locationCurrent() && receipt.targetStateKnown()
                && receipt.stagedStateKnown()
                && (!receipt.originalMoved() || receipt.displacedStateKnown())
                && (receipt.backupPath() == null || receipt.backupStateKnown());
        details.put("outcome_known", outcomeKnown);
        details.put("location_current", receipt.locationCurrent());
        details.put("source_moved", receipt.sourceMoved());
        details.put("staged_state_known", receipt.stagedStateKnown());
        details.put("staged_retained", receipt.stagedRetained());
        details.put("original_moved", receipt.originalMoved());
        details.put("displaced_state_known", receipt.displacedStateKnown());
        details.put("displaced_matched", receipt.displacedMatched());
        details.put("target_state_known", receipt.targetStateKnown());
        details.put("publication_applied", receipt.publicationApplied());
        details.put("publication_verified", receipt.publicationVerified());
        details.put("intended_target_sha256", receipt.intendedSha256());
        details.put("backup_published", receipt.backupPublished());
        details.put("backup_state_known", receipt.backupStateKnown());
        details.put("backup_verified", receipt.backupVerified());
        if (receipt.stagedSha256() != null) {
            details.put("staged_sha256", receipt.stagedSha256());
        }
        if (receipt.displacedPath() != null) {
            details.put("displaced_path", receipt.displacedPath().toString());
        }
        if (receipt.displacedSha256() != null) {
            details.put("displaced_sha256", receipt.displacedSha256());
        }
        if (receipt.retainedStagePath() != null) {
            details.put("retained_stage_path", receipt.retainedStagePath().toString());
        }
        if (receipt.backupPath() != null) {
            details.put("backup_path", receipt.backupPath().toString());
        }
        if (receipt.backupSha256() != null) {
            details.put("backup_sha256", receipt.backupSha256());
        }
        if (receipt.targetStateKnown()) {
            details.put("target_present", receipt.targetPresent());
            if (receipt.targetSha256() != null) {
                details.put("target_sha256", receipt.targetSha256());
            }
        }
        return new HeadlessExecutionException("materialization_guarded_replacement_incomplete",
                "Guarded publication retained private transaction evidence without overwriting "
                        + "a concurrent target; inspect the fact-granular receipt.",
                details, false, guarded);
    }

    static HeadlessExecutionException orphanRecoveryApplied(
            WorkspaceTransaction.OrphanRecoveryAppliedException applied) {
        WorkspaceTransaction.OrphanRecoverySideEffect receipt = applied.receipt();
        boolean outcomeKnown = receipt.recoveryStateKnown()
                && receipt.locationCurrent() && receipt.targetStateKnown();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("effects_prevented", false);
        details.put("outcome_known", outcomeKnown);
        details.put("retry_requires_state_check", true);
        details.put("recovery_state_known", receipt.recoveryStateKnown());
        details.put("location_current", receipt.locationCurrent());
        details.put("target_state_known", receipt.targetStateKnown());
        details.put("target_restored", receipt.targetRestored());
        details.put("directories_cleaned", receipt.directoriesCleaned());
        if (receipt.targetStateKnown()) {
            details.put("target_present", receipt.targetPresent());
            if (receipt.targetSha256() != null) {
                details.put("target_sha256", receipt.targetSha256());
            }
        }
        if (receipt.restoredSha256() != null) {
            details.put("restored_sha256", receipt.restoredSha256());
        }
        return new HeadlessExecutionException("materialization_workspace_recovery_applied",
                "Locked workspace recovery changed transaction state; inspect the receipt and retry.",
                details, false, applied);
    }

    static HeadlessExecutionException ambiguousRecovery(
            WorkspaceTransaction.AmbiguousRecoveryException ambiguous) {
        WorkspaceTransaction.AmbiguousRecoverySideEffect receipt = ambiguous.receipt();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("effects_prevented", true);
        details.put("outcome_known", true);
        details.put("manual_intervention_required", true);
        details.put("evidence_count", receipt.evidenceCount());
        details.put("evidence_paths", receipt.evidencePaths().stream()
                .map(Path::toString).toList());
        details.put("resolution",
                "Stop all writers, preserve the target and evidence directories, compare hashes, "
                        + "move resolved evidence out of the project, then retry.");
        return new HeadlessExecutionException("materialization_workspace_recovery_ambiguous",
                "Workspace recovery evidence is ambiguous and requires offline operator review.",
                details, false, ambiguous);
    }

    private static HeadlessExecutionException failure(MaterializationException failure) {
        return new HeadlessExecutionException(failure.code(), failure.getMessage(),
                failure.details(), failure.retryable(), failure);
    }

    private static HeadlessExecutionException failure(String code, String message,
            boolean retryable) {
        return new HeadlessExecutionException(code, message,
                Map.of("effects_prevented", true), retryable, null);
    }
}
