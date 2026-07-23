package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.core.qc.RdfQueryService;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceFingerprints;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationArtifact;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationCollisions;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationCommitResults;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationException;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationInputDigests;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationInputIdentity;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationPolicy;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationRequest;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationRequests;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityRegistry;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;
import io.github.hakjuoh.protege_mcp.reasoner.ReasonerIdentity;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Live preview and single-broadcast commit adapter for inference materialization. */
public final class MaterializationTools {
    private static final ReasonerCapabilityRegistry PROFILES = new ReasonerCapabilityRegistry();
    private static final long MAX_MAPPING_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SOURCE_AXIOMS = 500_000L;
    static final int MAX_LIVE_MATERIALIZED_AXIOMS = 500;

    private MaterializationTools() {
    }

    public static void register(ToolRegistry tools, ToolContext context) {
        tools.tool("materialize_inferences", (exchange, request) -> preview(
                context, exchange, Tools.args(request)));
        tools.tool("commit_materialization", (exchange, request) -> commit(
                context, exchange, Tools.args(request)));
    }

    private static CallToolResult preview(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> arguments) {
        CapturedJob captured = captureJob(context, exchange, arguments);
        try {
            MaterializationArtifact artifact = runJob(context, captured);
            publishJobArtifact(context, captured, artifact);
            return Tools.ok(artifact.report());
        } catch (MaterializationException failure) {
            throw toolFailure(failure);
        }
    }

    /** Capture every live and filesystem input before an asynchronous materialization is admitted. */
    static CapturedJob captureJob(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> arguments) {
        DirectAccessPolicy.Rules accessRules = DirectAccessPolicy.resolve(
                context, exchange, optionalString(arguments, "policy_path"));
        return captureJob(context, exchange, arguments, accessRules);
    }

    /** Capture with request-scoped rules already resolved before bounded job admission. */
    static CapturedJob captureJob(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> arguments, DirectAccessPolicy.Rules accessRules) {
        MaterializationRequest request;
        try {
            request = MaterializationRequests.parse(arguments);
        } catch (IllegalArgumentException invalid) {
            throw new ToolArgException("invalid_request", invalid.getMessage(),
                    Map.of("effects_prevented", true), false);
        }
        if ("project_file".equals(request.destination().kind())) {
            throw prevented("materialization_destination_unavailable",
                    "The live adapter supports new_ontology and active_source destinations; "
                            + "use the headless adapter for project_file output.", false);
        }
        if (request.limits().maxAxiomsPerCategory() > MAX_LIVE_MATERIALIZED_AXIOMS
                || request.limits().maxAxiomsTotal() > MAX_LIVE_MATERIALIZED_AXIOMS) {
            throw prevented("materialization_live_limit_exceeded",
                    "Live materialization is capped at 500 axioms to preserve the model-thread "
                            + "stall budget; use headless project_file output for larger runs.", false);
        }
        requireAbsoluteIri(request.destination().identifier(), "destination identifier");
        arguments = accessRules.authorizedPolicyArguments(arguments);
        String configuredPolicy = optionalString(arguments, "policy_path");
        RevisionTools.PolicyState policyState = RevisionTools.resolvePolicy(
                context, configuredPolicy);
        ProjectPolicy policy = policyState.policy();
        String importLockDigest = RevisionTools.digestImportLock(policy);
        String mappingRevision = mappingRevision(policy);
        CapturedSnapshot captured;
        try {
            captured = context.access().compute(manager -> capture(
                    context, manager, policy, importLockDigest, request.destination()));
            try {
                ReasonerIdentity reasonerIdentity = captured.spec().capabilityIdentity();
                ReasonerCapabilityReport capabilities = PROFILES.report(reasonerIdentity);
                MaterializationPolicy.requireAllowed(policy, request, reasonerIdentity);
                String path = policy.path() == null ? null : policy.path().toString();
                MaterializationInputIdentity inputIdentity = new MaterializationInputIdentity(
                        captured.revision(), captured.closureFingerprint(), importLockDigest,
                        mappingRevision, policy.digest(), RevisionTools.preflightDigest(policy),
                        path, reasonerIdentity);
                return new CapturedJob(request, policy, captured, capabilities,
                        inputIdentity, owner(context, exchange));
            } catch (RuntimeException | LinkageError rejected) {
                cleanup(captured.isolated());
                throw rejected;
            }
        } catch (MaterializationException failure) {
            throw toolFailure(failure);
        }
    }

    /** Compute a materialization solely from a detached capture; the service owns snapshot cleanup. */
    static MaterializationArtifact runJob(ToolContext context, CapturedJob captured) {
        return context.materializations().preview(
                captured.snapshot.isolated(), captured.snapshot.spec()::create,
                captured.capabilities, captured.request, captured.inputIdentity);
    }

    /** Publish an already-computed preview into the store consumed by commit_materialization. */
    static void publishJobArtifact(
            ToolContext context, CapturedJob captured, MaterializationArtifact artifact) {
        context.materializationArtifacts().put(captured.owner, artifact);
    }

    /** Release an immutable snapshot when admission or idempotent recovery skips execution. */
    static void discardJob(CapturedJob captured) {
        if (captured != null) cleanup(captured.snapshot.isolated());
    }

    private static CapturedSnapshot capture(ToolContext context, OWLModelManager manager,
            ProjectPolicy policy, String importLockDigest,
            MaterializationRequest.Destination destination) {
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(
                manager.getOWLReasonerManager());
        if (spec == null) {
            throw prevented("materialization_reasoner_required",
                    "Select a reasoner in Protege before materialization.", false);
        }
        OWLOntology active = manager.getActiveOntology();
        validateDestination(manager, destination);
        long axiomCount = active.getImportsClosure().stream()
                .mapToLong(OWLOntology::getAxiomCount).sum();
        if (axiomCount > MAX_SOURCE_AXIOMS) {
            throw prevented("materialization_source_too_large",
                    "The imports closure exceeds the live snapshot axiom bound.", false);
        }
        WorkspaceRevisionTracker.RevisionSnapshot revision = context.revisions().current(
                manager, importLockDigest, policy.digest());
        String closureFingerprint = WorkspaceFingerprints.closure(active.getImportsClosure());
        OWLOntologyManager isolatedManager = OWLManager.createOWLOntologyManager();
        OWLOntology isolated = RdfQueryService.buildSnapshotOntology(
                isolatedManager, active.getOntologyID(), active.getImportsClosure());
        return new CapturedSnapshot(spec, isolated, revision.revision(), closureFingerprint);
    }

    private static CallToolResult commit(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> arguments) {
        requireKeys(arguments, Set.of("artifact_id", "artifact_fingerprint", "confirm",
                "policy_path", "allow_source", "collision_mode", "overwrite",
                "expected_target_digest"));
        if (!Boolean.TRUE.equals(arguments.get("confirm"))) {
            throw prevented("confirmation_required",
                    "Materialization commit requires confirm=true.", false);
        }
        String artifactId = requiredString(arguments, "artifact_id");
        String artifactFingerprint = requiredString(arguments, "artifact_fingerprint");
        MaterializationArtifact artifact = requireArtifact(
                context, owner(context, exchange), artifactId, artifactFingerprint);
        String requestedPolicyArgument = optionalString(arguments, "policy_path");
        String artifactPolicyPath = artifact.inputIdentity().policyPath();
        DirectAccessPolicy.Rules accessRules = DirectAccessPolicy.resolve(
                context, exchange, requestedPolicyArgument == null
                        ? artifactPolicyPath : requestedPolicyArgument);
        arguments = accessRules.authorizedPolicyArguments(arguments);
        String requestedPolicy = requestedPolicyArgument == null
                ? artifactPolicyPath : optionalString(arguments, "policy_path");
        if (requestedPolicy != null) {
            try {
                requestedPolicy = Path.of(requestedPolicy).toAbsolutePath().normalize().toString();
            } catch (RuntimeException invalid) {
                throw prevented("invalid_request", "policy_path is invalid.", false);
            }
            if (!requestedPolicy.equals(artifact.inputIdentity().policyPath())) {
                throw prevented("materialization_policy_mismatch",
                        "policy_path differs from the preview artifact.", false);
            }
        }
        if (arguments.containsKey("overwrite")
                || arguments.containsKey("expected_target_digest")) {
            throw prevented("invalid_request",
                    "overwrite and expected_target_digest apply only to project_file commits.",
                    false);
        }
        if ("project_file".equals(artifact.request().destination().kind())) {
            throw prevented("materialization_destination_unavailable",
                    "The live adapter cannot commit project_file artifacts.", false);
        }
        boolean source = "active_source".equals(artifact.request().destination().kind());
        if (source && !Boolean.TRUE.equals(arguments.get("allow_source"))) {
            throw prevented("materialization_source_write_confirmation_required",
                    "Writing the active source requires allow_source=true.", false);
        }
        String collisionMode = optionalString(arguments, "collision_mode");
        if (collisionMode == null) collisionMode = "reject";
        if (!Set.of("reject", "merge", "replace").contains(collisionMode)) {
            throw prevented("invalid_request",
                    "collision_mode must be reject, merge, or replace.", false);
        }
        boolean confirmationMode = context.controller().isConfirmWrites();
        requireWriteAllowed(context, "commit inference materialization " + artifactId);
        if (confirmationMode != context.controller().isConfirmWrites()) {
            throw prevented("confirmation_state_changed",
                    "The write-confirmation preference changed during authorization.", true);
        }
        context.writeLock().lock();
        try {
            if (context.controller().isReadOnly()) throw readOnly();
            if (confirmationMode != context.controller().isConfirmWrites()) {
                throw prevented("confirmation_state_changed",
                        "The write-confirmation preference changed before commit.", true);
            }
            MaterializationArtifact currentArtifact = requireArtifact(
                    context, owner(context, exchange), artifactId, artifactFingerprint);
            if (!context.materializations().verifyArtifact(currentArtifact)) {
                throw prevented("materialization_artifact_corrupt",
                        "The materialization artifact digest could not be verified.", false);
            }
            String finalCollisionMode = collisionMode;
            try {
                IsolatedReasonerSpec commitSpec = context.access().compute(manager ->
                        IsolatedReasonerSpec.capture(manager.getOWLReasonerManager()));
                if (commitSpec == null) {
                    throw prevented("materialization_input_changed",
                            "The selected reasoner is no longer available.", true);
                }
                ReasonerIdentity commitIdentity = commitSpec.capabilityIdentity();
                return context.access().computeMutation(manager -> commitOnModelThread(
                        context, manager, currentArtifact, finalCollisionMode,
                        commitSpec.selectionKey(), commitIdentity), 30_000L);
            } catch (MaterializationException failure) {
                throw toolFailure(failure);
            }
        } finally {
            context.writeLock().unlock();
        }
    }

    static CallToolResult commitOnModelThread(ToolContext context,
            OWLModelManager manager, MaterializationArtifact artifact, String collisionMode,
            IsolatedReasonerSpec.SelectionKey selectionKey,
            ReasonerIdentity reasonerIdentity) {
        MaterializationInputIdentity expected = artifact.inputIdentity();
        ProjectPolicy policy = ChangeSetTools.effectivePolicy(manager, expected.policyPath());
        IsolatedReasonerSpec spec = IsolatedReasonerSpec.capture(manager.getOWLReasonerManager());
        if (spec == null || !selectionKey.equals(spec.selectionKey())) {
            throw prevented("materialization_input_changed",
                    "The selected reasoner changed during commit authorization.", true);
        }
        ReasonerIdentity finalReasonerIdentity = spec.capabilityIdentity();
        if (!reasonerIdentity.profileKey().equals(finalReasonerIdentity.profileKey())) {
            throw prevented("materialization_input_changed",
                    "The exact reasoner configuration changed during commit authorization.", true);
        }
        MaterializationPolicy.requireAllowed(
                policy, artifact.request(), finalReasonerIdentity);
        if (!expected.reasonerIdentity().profileKey()
                .equals(finalReasonerIdentity.profileKey())) {
            throw prevented("materialization_input_changed",
                    "The exact reasoner or semantic configuration changed after preview.", true);
        }
        if (!Objects.equals(expected.policyDigest(), policy.digest())
                || !Objects.equals(expected.policyPath(),
                        policy.path() == null ? null : policy.path().toString())
                || !Objects.equals(expected.importLockDigest(),
                        RevisionTools.digestImportLock(policy))
                || !Objects.equals(expected.mappingRevision(), mappingRevision(policy))) {
            throw prevented("materialization_input_changed",
                    "Project policy, imports, or mappings changed after preview.", true);
        }
        if (!Objects.equals(expected.policyAssetDigest(),
                RevisionTools.preflightDigest(policy))) {
            throw prevented("materialization_input_changed",
                    "Project policy assets changed after preview.", true);
        }

        OWLOntology active = manager.getActiveOntology();
        validateActiveDestination(active, artifact.request().destination());
        ModelRevision current = currentRevision(context, manager, policy);
        String closure = WorkspaceFingerprints.closure(active.getImportsClosure());
        if (!sameInputRevision(expected.modelRevision(), current)
                || !expected.closureFingerprint().equals(closure)) {
            throw new ToolArgException("materialization_revision_conflict",
                    "The source ontology changed after materialization preview.",
                    Map.of("expected_revision", RevisionTools.revisionJson(
                                    expected.modelRevision()),
                            "current_revision", RevisionTools.revisionJson(current),
                            "effects_prevented", true), true);
        }
        OWLOntology target = findTargetOntology(manager, artifact.request().destination());
        if (target != null && "new_ontology".equals(
                artifact.request().destination().kind()) && target.equals(active)) {
            throw prevented("materialization_destination_conflict",
                    "new_ontology must not alias the active source ontology.", false);
        }
        MaterializationCollisions.State collisions = target == null
                ? new MaterializationCollisions.State(0, 0, Set.of())
                : MaterializationCollisions.analyze(target, artifact.axioms());
        if (target != null && (collisions.exactOnly(artifact.axioms().size())
                || ("merge".equals(collisionMode)
                        && collisions.allExact(artifact.axioms().size())))) {
            return Tools.ok(commitResult(artifact, false, 0, collisions.existing(),
                    false, null, current));
        }
        if (target != null && "new_ontology".equals(
                artifact.request().destination().kind())) {
            throw prevented("materialization_destination_conflict",
                    "The new_ontology destination is already loaded with different content.",
                    false);
        }
        if (collisions.logical() > 0 && "reject".equals(collisionMode)) {
            throw new ToolArgException("materialization_provenance_collision",
                    "The destination contains logical axioms with different provenance.",
                    Map.of("logical_collisions", collisions.logical(),
                            "effects_prevented", true), false);
        }

        if (artifact.axioms().isEmpty()) {
            return Tools.ok(commitResult(artifact, false, 0, 0,
                    false, null, current));
        }
        int undoBefore = undoDepth(manager);
        boolean created = target == null;
        if (created) target = createTargetOntology(manager, artifact.request().destination(), active);
        OWLOntology destinationOntology = target;
        List<OWLOntologyChange> changes = new ArrayList<>();
        if ("replace".equals(collisionMode)) {
            collisions.differentForms().forEach(existing ->
                    changes.add(new RemoveAxiom(destinationOntology, existing)));
        }
        artifact.axioms().stream()
                .filter(axiom -> !destinationOntology.containsAxiom(axiom))
                .forEach(axiom -> changes.add(new AddAxiom(destinationOntology, axiom)));
        int additions = (int) changes.stream().filter(AddAxiom.class::isInstance).count();
        if (changes.isEmpty()) {
            return Tools.ok(commitResult(artifact, false, 0, collisions.existing(),
                    false, null, current));
        }
        try {
            manager.applyChanges(changes);
        } catch (RuntimeException outcomeUnknown) {
            boolean complete = MaterializationCollisions.analyze(
                    destinationOntology, artifact.axioms()).commitComplete(
                            artifact.axioms().size(), collisionMode);
            if (created && !complete) removeCreatedOntology(manager, destinationOntology);
            throw new ToolArgException("materialization_commit_outcome_unknown",
                    "The ontology adapter failed during materialization; axiom state was "
                            + "rechecked but listener, history, and dirty-state outcomes are unknown.",
                    Map.of("outcome_unknown", true,
                            "mutation_outcome_unknown", true,
                            "axiom_state_complete", complete,
                            "retry_requires_state_check", true), false);
        }
        if (!MaterializationCollisions.analyze(destinationOntology,
                artifact.axioms()).commitComplete(
                        artifact.axioms().size(), collisionMode)) {
            if (created) removeCreatedOntology(manager, destinationOntology);
            throw new ToolArgException("materialization_commit_outcome_unknown",
                    "The ontology adapter did not retain every materialized axiom.",
                    Map.of("outcome_unknown", true,
                            "mutation_outcome_unknown", true), false);
        }
        ModelRevision revision = currentRevision(context, manager, policy);
        int undoAfter = undoDepth(manager);
        boolean singleUndo = !created && undoBefore >= 0 && undoAfter == undoBefore + 1;
        return Tools.ok(commitResult(artifact, true, additions, collisions.existing(),
                singleUndo, null, revision));
    }

    private static OWLOntology findTargetOntology(OWLModelManager manager,
            MaterializationRequest.Destination destination) {
        if ("active_source".equals(destination.kind())) return manager.getActiveOntology();
        String identifier = destination.identifier();
        List<OWLOntology> ontologyMatches = new ArrayList<>();
        List<OWLOntology> versionMatches = new ArrayList<>();
        for (OWLOntology ontology : manager.getOntologies()) {
            OWLOntologyID id = ontology.getOntologyID();
            if (id.getOntologyIRI().isPresent()
                    && identifier.equals(id.getOntologyIRI().get().toString())) {
                ontologyMatches.add(ontology);
            }
            if (id.getVersionIRI().isPresent()
                    && identifier.equals(id.getVersionIRI().get().toString())) {
                versionMatches.add(ontology);
            }
        }
        Set<OWLOntology> allMatches = new java.util.LinkedHashSet<>(ontologyMatches);
        allMatches.addAll(versionMatches);
        if (allMatches.size() > 1) {
            throw prevented("materialization_destination_ambiguous",
                    "The destination identifier matches multiple loaded ontologies.", false);
        }
        if (!ontologyMatches.isEmpty()) return ontologyMatches.get(0);
        return versionMatches.isEmpty() ? null : versionMatches.get(0);
    }

    private static OWLOntology createTargetOntology(OWLModelManager manager,
            MaterializationRequest.Destination destination, OWLOntology source) {
        IRI iri = IRI.create(destination.identifier());
        try {
            OWLOntology created = manager.createNewOntology(
                    new OWLOntologyID(iri), (java.net.URI) null);
            if (source != null && !source.equals(created)
                    && manager.getOntologies().contains(source)) {
                manager.setActiveOntology(source);
            }
            return created;
        } catch (OWLOntologyCreationException failure) {
            throw prevented("materialization_destination_conflict",
                    "The new ontology destination could not be created.", false);
        }
    }

    private static void removeCreatedOntology(OWLModelManager manager, OWLOntology created) {
        try {
            manager.removeOntology(created);
        } catch (RuntimeException cleanupFailure) {
            throw new ToolArgException("materialization_commit_outcome_unknown",
                    "A failed materialization left a newly created ontology in an unknown state.",
                    Map.of("outcome_unknown", true,
                            "mutation_outcome_unknown", true), false);
        }
    }

    private static Map<String, Object> commitResult(MaterializationArtifact artifact,
            boolean committed, int added, int existing, boolean singleUndo,
            String targetDigest, ModelRevision revision) {
        Map<String, Object> out = new LinkedHashMap<>(MaterializationCommitResults.result(
                artifact, committed, added, existing, singleUndo, targetDigest));
        if (revision != null) out.put("new_revision", RevisionTools.revisionJson(revision));
        return out;
    }

    private static ModelRevision currentRevision(ToolContext context,
            OWLModelManager manager, ProjectPolicy policy) {
        return context.revisions().current(manager, RevisionTools.digestImportLock(policy),
                policy.digest()).revision();
    }

    private static boolean sameInputRevision(ModelRevision expected, ModelRevision current) {
        return expected.workspaceId().equals(current.workspaceId())
                && expected.semanticFingerprint().equals(current.semanticFingerprint())
                && expected.documentFingerprint().equals(current.documentFingerprint());
    }

    private static int undoDepth(OWLModelManager manager) {
        try {
            return manager.getHistoryManager().getLoggedChanges().size();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private static void validateDestination(OWLModelManager manager,
            MaterializationRequest.Destination destination) {
        OWLOntology active = manager.getActiveOntology();
        validateActiveDestination(active, destination);
        if ("new_ontology".equals(destination.kind())
                && findTargetOntology(manager, destination) != null) {
            throw prevented("materialization_destination_conflict",
                    "The new_ontology destination is already loaded.", false);
        }
    }

    private static void validateActiveDestination(OWLOntology active,
            MaterializationRequest.Destination destination) {
        String activeIri = active.getOntologyID().getOntologyIRI().isPresent()
                ? active.getOntologyID().getOntologyIRI().get().toString() : null;
        if ("active_source".equals(destination.kind())
                && !Objects.equals(activeIri, destination.identifier())) {
            throw prevented("materialization_destination_conflict",
                    "The active_source identifier does not match the active ontology.", false);
        }
    }

    private static void cleanup(OWLOntology ontology) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        for (OWLOntology loaded : new ArrayList<>(manager.getOntologies())) {
            manager.removeOntology(loaded);
        }
    }

    private static void requireWriteAllowed(ToolContext context, String summary) {
        if (context.controller().isReadOnly()) throw readOnly();
        if (context.controller().isConfirmWrites()) {
            WriteConfirmer confirmer = context.confirmer();
            if (confirmer == null || !confirmer.confirm(summary)) {
                throw prevented("write_declined", "Write declined by the user.", false);
            }
        }
    }

    private static ToolArgException readOnly() {
        return prevented("read_only",
                "Server is in read-only mode; writes are disabled.", false);
    }

    private static MaterializationArtifact requireArtifact(ToolContext context, String owner,
            String artifactId, String fingerprint) {
        try {
            return context.materializationArtifacts().require(owner, artifactId, fingerprint);
        } catch (MaterializationException failure) {
            throw toolFailure(failure);
        }
    }

    private static String owner(ToolContext context, McpSyncServerExchange exchange) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.staticAdmin();
        if (exchange != null && exchange.transportContext() != null) {
            Object value = exchange.transportContext().get(AuthenticatedPrincipal.CONTEXT_KEY);
            if (value instanceof AuthenticatedPrincipal authenticated) principal = authenticated;
        }
        String identity = principal.type() + "\u0000" + principal.clientId() + "\u0000"
                + principal.grantId() + "\u0000" + context.revisions().workspaceId();
        return ArtifactStore.sha256(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String mappingRevision(ProjectPolicy policy) {
        try {
            return MaterializationInputDigests.mappingRevision(policy, MAX_MAPPING_BYTES);
        } catch (IOException failure) {
            throw prevented("materialization_mapping_unreadable",
                    "The policy mapping store could not be fingerprinted.", true);
        }
    }

    private static void requireAbsoluteIri(String value, String name) {
        try {
            if (!IRI.create(value).isAbsolute()) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw prevented("invalid_request", name + " must be an absolute IRI.", false);
        }
    }

    private static String requiredString(Map<String, Object> arguments, String key) {
        String value = optionalString(arguments, key);
        if (value == null) throw prevented("invalid_request", key + " is required.", false);
        return value;
    }

    private static String optionalString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) return null;
        if (value instanceof String text && !text.isBlank() && text.length() <= 4096) return text;
        throw prevented("invalid_request", key + " must be a bounded string.", false);
    }

    private static void requireKeys(Map<String, Object> arguments, Set<String> allowed) {
        Set<String> unknown = new java.util.TreeSet<>(arguments.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw prevented("invalid_request", "Unknown argument(s): "
                    + String.join(", ", unknown), false);
        }
    }

    private static ToolArgException toolFailure(MaterializationException failure) {
        return new ToolArgException(failure.code(), failure.getMessage(),
                failure.details(), failure.retryable());
    }

    private static ToolArgException prevented(String code, String message, boolean retryable) {
        return new ToolArgException(code, message,
                Map.of("effects_prevented", true), retryable);
    }

    private record CapturedSnapshot(IsolatedReasonerSpec spec, OWLOntology isolated,
            ModelRevision revision, String closureFingerprint) { }

    static final class CapturedJob {
        private final MaterializationRequest request;
        private final ProjectPolicy policy;
        private final CapturedSnapshot snapshot;
        private final ReasonerCapabilityReport capabilities;
        private final MaterializationInputIdentity inputIdentity;
        private final String owner;

        private CapturedJob(MaterializationRequest request, ProjectPolicy policy,
                CapturedSnapshot snapshot, ReasonerCapabilityReport capabilities,
                MaterializationInputIdentity inputIdentity, String owner) {
            this.request = request;
            this.policy = policy;
            this.snapshot = snapshot;
            this.capabilities = capabilities;
            this.inputIdentity = inputIdentity;
            this.owner = owner;
        }

        MaterializationInputIdentity inputIdentity() {
            return inputIdentity;
        }

        IsolatedReasonerSpec reasonerSpec() {
            return snapshot.spec();
        }

        ProjectPolicy policy() {
            return policy;
        }
    }

}
