package io.github.hakjuoh.protege_mcp.tools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.IRI;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceFingerprints;
import io.github.hakjuoh.protege_mcp.external.ReuseOperation;
import io.github.hakjuoh.protege_mcp.external.ReuseProposal;
import io.github.hakjuoh.protege_mcp.external.ReuseProposalTargetIdentity;
import io.github.hakjuoh.protege_mcp.sssom.SssomEntityIndex;
import io.github.hakjuoh.protege_mcp.sssom.SssomEntityIndexes;
import io.github.hakjuoh.protege_mcp.sssom.SssomMappingStore;
import io.github.hakjuoh.protege_mcp.sssom.SssomPolicies;
import io.github.hakjuoh.protege_mcp.sssom.SssomStoreException;
import io.github.hakjuoh.protege_mcp.sssom.SssomToolService;
import io.github.hakjuoh.protege_mcp.sssom.SssomValidationPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Live Protege adapter for the six shared SSSOM mapping operations. */
public final class MappingTools {

    private static final Set<String> MUTATIONS = Set.of(
            "add_mapping", "remove_mapping", "import_sssom", "export_sssom");

    private MappingTools() {
    }

    public static void register(ToolRegistry tools, ToolContext context) {
        for (String name : List.of("list_mappings", "add_mapping", "remove_mapping",
                "import_sssom", "export_sssom", "validate_mappings")) {
            tools.tool(name, (exchange, request) -> execute(name, context, exchange, request));
        }
    }

    private static CallToolResult execute(String tool, ToolContext context,
            McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = Tools.args(request);
        boolean mutation = MUTATIONS.contains(tool);
        if (!mutation) {
            return run(tool, context, exchange, args,
                    resolve(context, exchange, args, false, true), false);
        }
        if (!Boolean.TRUE.equals(args.get("confirm"))) {
            throw new ToolArgException("confirmation_required",
                    "Mapping filesystem mutations require confirm=true.",
                    Map.of("effects_prevented", true), false);
        }
        Resolved preview = resolveBeforeMutation(context, exchange, args,
                !"export_sssom".equals(tool), false);
        boolean confirmationMode = context.controller().isConfirmWrites();
        requireMappingWriteAllowed(context, tool.replace('_', ' ') + " at " + preview.target);
        if (confirmationMode != context.controller().isConfirmWrites()) {
            throw effectsPrevented(new ToolArgException("confirmation_state_changed",
                    "The live write-confirmation preference changed during authorization.", false));
        }
        if (context.controller().isReadOnly()) throw readOnlyDenied();
        context.writeLock().lock();
        try {
            if (context.controller().isReadOnly()) throw readOnlyDenied();
            if (confirmationMode != context.controller().isConfirmWrites()) {
                throw effectsPrevented(new ToolArgException("confirmation_state_changed",
                        "The live write-confirmation preference changed before execution.", false));
            }
            Resolved resolved = resolveBeforeMutation(context, exchange, args,
                    !"export_sssom".equals(tool), true);
            return run(tool, context, exchange, args, resolved, confirmationMode);
        } finally {
            context.writeLock().unlock();
        }
    }

    private static CallToolResult run(String tool, ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args, Resolved resolved,
            boolean confirmationMode) {
        try {
            SssomMappingStore store = new SssomMappingStore(resolved.root, resolved.target);
            SssomMappingStore.MutationGuard guard = MUTATIONS.contains(tool)
                    ? mutationGuard(context, exchange, args, resolved,
                            !"export_sssom".equals(tool), confirmationMode)
                    : SssomMappingStore.MutationGuard.none();
            Map<String, Object> result = switch (tool) {
                case "list_mappings" -> SssomToolService.list(store, resolved.validation,
                        resolved.entities, limit(args), Tools.optString(args, "cursor"));
                case "validate_mappings" -> SssomToolService.validate(store, resolved.validation,
                        resolved.entities, limit(args), Tools.optString(args, "cursor"));
                case "add_mapping" -> SssomToolService.add(store,
                        Tools.reqString(args, "expected_mapping_revision"),
                        stringMap(args.get("mapping"), "mapping"), initialMetadata(args),
                        stringMap(args.get("prefix_map"), "prefix_map"),
                        resolved.validation, resolved.entities, guard);
                case "remove_mapping" -> SssomToolService.remove(store,
                        Tools.reqString(args, "expected_mapping_revision"),
                        Tools.reqString(args, "mapping_id"), resolved.validation,
                        resolved.entities, guard);
                case "import_sssom" -> SssomToolService.importSssom(store,
                        Tools.reqString(args, "expected_mapping_revision"),
                        projectReadPath(resolved, Tools.reqString(args, "source")),
                        importMode(Tools.reqString(args, "mode")), resolved.validation,
                        resolved.entities, guard);
                case "export_sssom" -> SssomToolService.exportSssom(store,
                        Tools.reqString(args, "expected_mapping_revision"),
                        projectWritePath(resolved, Tools.reqString(args, "destination")),
                        Tools.optBool(args, "overwrite", false),
                        Tools.optString(args, "expected_target_digest"),
                        Tools.optBool(args, "spreadsheet_safe", false), resolved.validation,
                        resolved.entities, guard);
                default -> throw new ToolArgException("Unknown mapping tool " + tool);
            };
            return Tools.ok(result);
        } catch (SssomStoreException failure) {
            throw toolFailure(failure);
        } catch (ToolArgException failure) {
            throw MUTATIONS.contains(tool) ? effectsPrevented(failure) : failure;
        } catch (IOException failure) {
            throw new ToolArgException("mapping_io_failed",
                    "Mapping filesystem operation failed before a successful result was established.",
                    Map.of("effects_prevented", !MUTATIONS.contains(tool),
                            "outcome_unknown", MUTATIONS.contains(tool),
                            "retry_requires_state_check", MUTATIONS.contains(tool)), false);
        } catch (IllegalArgumentException failure) {
            ToolArgException invalid = new ToolArgException(failure.getMessage());
            throw MUTATIONS.contains(tool) ? effectsPrevented(invalid) : invalid;
        }
    }

    private static Resolved resolveBeforeMutation(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args,
            boolean writeStore, boolean captureEntities) {
        try {
            return resolve(context, exchange, args, writeStore, captureEntities);
        } catch (ToolArgException refusal) {
            throw effectsPrevented(refusal);
        }
    }

    /** Capture the policy-governed mapping and model identity used by a read-only reuse proposal. */
    static ProposalState proposalState(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> args) {
        Resolved captured = resolve(context, exchange, args, false, false);
        if (!captured.policy.loaded() || captured.policy.version() != 2
                || captured.policy.digest() == null) {
            throw new ToolArgException("provider_policy_required",
                    "Reuse proposals require a valid project policy version 2.", false);
        }
        try {
            SssomMappingStore store = new SssomMappingStore(captured.root, captured.target);
            SssomMappingStore.Snapshot mappingSnapshot = store.read(captured.validation,
                    SssomEntityIndex.unavailable());
            String mappingRevision = mappingSnapshot.mappingRevision();
            ModelRevision modelRevision = context.access().compute(mm -> context.revisions().current(
                    mm, RevisionTools.digestImportLock(captured.policy),
                    captured.policy.digest()).revision());

            Resolved current = resolve(context, exchange, args, false, false);
            SssomMappingStore.Snapshot currentMapping =
                    new SssomMappingStore(current.root, current.target)
                    .read(current.validation, SssomEntityIndex.unavailable());
            String currentMappingRevision = currentMapping.mappingRevision();
            if (!captured.identity().equals(current.identity())
                    || !captured.policy.digest().equals(current.policy.digest())
                    || !mappingRevision.equals(currentMappingRevision)
                    || mappingSnapshot.exists() != currentMapping.exists()) {
                throw new ToolArgException("proposal_input_changed",
                        "Project policy or mapping state changed while the proposal was captured.",
                        Map.of("effects_prevented", true), true);
            }
            ReuseProposalTargetIdentity targetIdentity = ReuseProposalTargetIdentity.create(
                    canonicalIdentity(captured.root), canonicalIdentity(captured.policy.path()),
                    canonicalIdentity(captured.target), mappingSnapshot.exists());
            return new ProposalState(captured.policy, modelRevision, mappingRevision,
                    targetIdentity);
        } catch (SssomStoreException failure) {
            throw toolFailure(failure);
        } catch (IOException failure) {
            throw new ToolArgException("mapping_io_failed",
                    "Mapping state could not be captured for the reuse proposal.",
                    Map.of("effects_prevented", true), true);
        }
    }

    /** Commit the mapping step of an accepted reuse proposal with the ordinary mapping CAS. */
    static Map<String, Object> acceptProposalMapping(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args,
            ReuseProposal proposal, boolean confirmationMode,
            ModelRevision expectedModelRevision) {
        if (proposal == null) throw new IllegalArgumentException("reuse proposal is required");
        final Map<String, String> cells;
        if (proposal.operation() instanceof ReuseOperation.AddMapping add) {
            cells = add.mappingCells();
        } else if (proposal.operation() instanceof ReuseOperation.MintLocalWithMapping mint) {
            cells = mint.mappingCells();
        } else {
            throw new IllegalArgumentException(
                    "reuse action does not contain a mapping operation");
        }
        Resolved resolved = resolveBeforeMutation(context, exchange, args, true, true);
        if (!resolved.policy.loaded() || resolved.policy.version() != 2
                || !proposal.inputIdentity().policyDigest().equals(resolved.policy.digest())) {
            throw effectsPrevented(new ToolArgException("proposal_input_changed",
                    "Project policy changed after the reuse proposal was issued.", true));
        }
        try {
            SssomMappingStore store = new SssomMappingStore(resolved.root, resolved.target);
            SssomMappingStore.Snapshot before = store.read(
                    resolved.validation, SssomEntityIndex.unavailable());
            ReuseProposalTargetIdentity targetIdentity = ReuseProposalTargetIdentity.create(
                    canonicalIdentity(resolved.root), canonicalIdentity(resolved.policy.path()),
                    canonicalIdentity(resolved.target), before.exists());
            if (!proposal.inputIdentity().targetIdentity().equals(targetIdentity)) {
                throw effectsPrevented(new ToolArgException("proposal_input_changed",
                        "Project, policy source, or mapping target changed after the reuse "
                                + "proposal was issued.", true));
            }
            SssomMappingStore.MutationGuard ordinary = mutationGuard(
                    context, exchange, args, resolved, true, confirmationMode);
            SssomMappingStore.MutationGuard acceptance = () -> {
                ordinary.check();
                if (expectedModelRevision != null) {
                    ModelRevision current = context.access().compute(mm -> context.revisions()
                            .current(mm, RevisionTools.digestImportLock(resolved.policy),
                                    resolved.policy.digest()).revision());
                    if (!expectedModelRevision.equals(current)) {
                        throw new SssomStoreException("proposal_input_changed",
                                "Ontology state changed after reuse proposal verification", true);
                    }
                }
            };
            return SssomToolService.add(store,
                    proposal.inputIdentity().mappingRevision(), cells, initialMetadata(args),
                    Map.of(), resolved.validation, resolved.entities,
                    acceptance,
                    proposal.inputIdentity().targetIdentity().mappingExists());
        } catch (SssomStoreException failure) {
            throw toolFailure(failure);
        } catch (ToolArgException failure) {
            throw effectsPrevented(failure);
        } catch (IOException failure) {
            throw new ToolArgException("mapping_io_failed",
                    "Mapping acceptance failed after filesystem execution began; check the "
                            + "mapping revision before retrying.",
                    Map.of("outcome_unknown", true,
                            "mutation_outcome_unknown", true,
                            "retry_requires_state_check", true), false);
        } catch (IllegalArgumentException failure) {
            throw effectsPrevented(new ToolArgException(
                    "reuse_operation_invalid", failure.getMessage(), false));
        }
    }

    /** Recheck the exact proposal target from inside the joined model-thread mint boundary. */
    static void requireProposalTarget(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> args, ReuseProposal proposal, ProjectPolicy loadedPolicy) {
        if (proposal == null || loadedPolicy == null) {
            throw new IllegalArgumentException("proposal and loaded policy are required");
        }
        Resolved current = resolve(context, exchange, args, false, false);
        if (!current.policy.loaded() || current.policy.version() != 2
                || !loadedPolicy.digest().equals(current.policy.digest())
                || !canonicalIdentity(loadedPolicy.path()).equals(
                        canonicalIdentity(current.policy.path()))) {
            throw new ToolArgException("proposal_input_changed",
                    "Project policy source changed before ontology mint execution.",
                    Map.of("effects_prevented", true), true);
        }
        try {
            SssomMappingStore.Snapshot mapping = new SssomMappingStore(
                    current.root, current.target).read(
                            current.validation, SssomEntityIndex.unavailable());
            ReuseProposalTargetIdentity target = ReuseProposalTargetIdentity.create(
                    canonicalIdentity(current.root), canonicalIdentity(current.policy.path()),
                    canonicalIdentity(current.target), mapping.exists());
            if (!proposal.inputIdentity().targetIdentity().equals(target)
                    || !proposal.inputIdentity().mappingRevision()
                            .equals(mapping.mappingRevision())) {
                throw new ToolArgException("proposal_input_changed",
                        "Project or mapping target changed before ontology mint execution.",
                        Map.of("effects_prevented", true), true);
            }
        } catch (SssomStoreException failure) {
            throw toolFailure(failure);
        } catch (IOException failure) {
            throw new ToolArgException("proposal_input_changed",
                    "Mapping target could not be revalidated before ontology mint execution.",
                    Map.of("effects_prevented", true), true);
        }
    }

    private static ToolArgException effectsPrevented(ToolArgException failure) {
        if (Boolean.TRUE.equals(failure.details().get("effects_prevented"))) return failure;
        Map<String, Object> details = new LinkedHashMap<>(failure.details());
        details.remove("outcome_unknown");
        details.remove("mutation_outcome_unknown");
        details.remove("retry_requires_state_check");
        details.put("effects_prevented", true);
        ToolArgException prevented = new ToolArgException(failure.code(), failure.getMessage(),
                details, failure.retryable(), failure.secretCanaries());
        prevented.initCause(failure);
        return prevented;
    }

    private static void requireMappingWriteAllowed(ToolContext context, String summary) {
        if (context.controller().isReadOnly()) throw readOnlyDenied();
        if (context.controller().isConfirmWrites()) {
            WriteConfirmer confirmer = context.confirmer();
            if (confirmer == null || !confirmer.confirm(summary)) {
                throw new ToolArgException("write_declined", "Write declined by the user.",
                        Map.of("effects_prevented", true), false);
            }
        }
    }

    private static ToolArgException readOnlyDenied() {
        return new ToolArgException("read_only",
                "Server is in read-only mode; writes are disabled.",
                Map.of("effects_prevented", true), false);
    }

    private static Resolved resolve(ToolContext context, McpSyncServerExchange exchange,
            Map<String, Object> args, boolean writeStore, boolean captureEntities) {
        String configuredPolicy = Tools.optString(args, "policy_path");
        DirectAccessPolicy.Rules rules = DirectAccessPolicy.resolve(
                context, exchange, configuredPolicy);
        ProjectPolicy policy = rules.policy();
        if (policy.loaded() && !policy.valid()) {
            throw new ToolArgException("invalid_project_policy",
                    "Invalid project policy cannot authorize mapping access.", false);
        }
        Path root = policy.loaded() ? policy.projectRoot() : activeDocumentDirectory(context);
        if (root == null) {
            throw new ToolArgException("mapping_project_unavailable",
                    "No canonical local project directory is available for mappings.", false);
        }
        try {
            root = root.toRealPath();
        } catch (IOException unsafe) {
            throw new ToolArgException("mapping_project_unavailable",
                    "The mapping project directory is unavailable.", false);
        }
        String requested = Tools.optString(args, "path");
        String configured;
        if (policy.loaded() && policy.version() == 2) {
            Object mappings = policy.effective().get("mappings");
            if (!(mappings instanceof Map<?, ?> map)
                    || !(map.get("path") instanceof String governed)) {
                throw new ToolArgException("Policy v2 mappings.path is unavailable.");
            }
            configured = governed;
            if (requested != null) {
                Path override = authorizeStorePath(rules, requested, writeStore);
                Path expected = authorizeStorePath(rules, configured, writeStore);
                if (!override.equals(expected)) {
                    throw new ToolArgException("Policy v2 mapping path cannot be overridden.");
                }
            }
        } else {
            if (requested == null) {
                throw new ToolArgException(policy.loaded()
                        ? "Policy v1 mapping operations require explicit path."
                        : "Without a policy, mapping operations require an explicit path under the active document directory.");
            }
            configured = policy.loaded() ? requested : relativeToRoot(requested, root);
        }
        Path target = authorizeStorePath(rules, configured, writeStore);
        if (!target.normalize().startsWith(root)) {
            throw new ToolArgException("Mapping store must remain under the canonical project directory.");
        }
        ModelCapture model = captureEntities ? context.access().compute(mm -> {
            var closure = mm.getActiveOntology().getImportsClosure();
            return new ModelCapture(SssomEntityIndexes.fromOntologies(closure),
                    WorkspaceFingerprints.closure(closure));
        }) : new ModelCapture(SssomEntityIndex.unavailable(), null);
        return new Resolved(policy, rules, root, target, SssomPolicies.from(policy), model.entities(),
                configuredPolicy, model.closureFingerprint());
    }

    private static SssomMappingStore.MutationGuard mutationGuard(ToolContext context,
            McpSyncServerExchange exchange, Map<String, Object> args, Resolved captured,
            boolean writeStore, boolean confirmationMode) {
        return () -> {
            try {
                if (context.controller().isReadOnly()) {
                    throw new IOException("server changed to read-only mode");
                }
                if (confirmationMode != context.controller().isConfirmWrites()) {
                    throw new IOException("write-confirmation preference changed");
                }
                Resolved current = resolve(context, exchange, args, writeStore, false);
                if (!captured.identity().equals(current.identity())) {
                    throw new IOException("mapping policy or project identity changed");
                }
                String currentModel = context.access().compute(mm ->
                        WorkspaceFingerprints.closure(
                                mm.getActiveOntology().getImportsClosure()));
                if (!java.util.Objects.equals(captured.closureFingerprint, currentModel)) {
                    throw new IOException("ontology closure changed during mapping validation");
                }
                if (context.controller().isReadOnly()
                        || confirmationMode != context.controller().isConfirmWrites()) {
                    throw new IOException("mapping write authorization changed");
                }
            } catch (ToolArgException changed) {
                throw new IOException("mapping authorization changed", changed);
            }
        };
    }

    private static Path authorizeStorePath(DirectAccessPolicy.Rules rules,
            String configured, boolean write) {
        return write ? rules.writePath(configured) : rules.readPath(configured);
    }

    private static Path projectReadPath(Resolved resolved, String configured) {
        Path path = resolved.rules.readPath(resolved.policy.loaded()
                ? configured : relativeToRoot(configured, resolved.root));
        if (!path.startsWith(resolved.root)) {
            throw new ToolArgException("SSSOM import source must remain inside the project.");
        }
        return path;
    }

    private static Path projectWritePath(Resolved resolved, String configured) {
        Path path = resolved.rules.writePath(resolved.policy.loaded()
                ? configured : relativeToRoot(configured, resolved.root));
        if (!path.startsWith(resolved.root)) {
            throw new ToolArgException("SSSOM export destination must remain inside the project.");
        }
        return path;
    }

    private static String relativeToRoot(String configured, Path root) {
        try {
            Path raw = Path.of(configured);
            return (raw.isAbsolute() ? raw : root.resolve(raw)).normalize().toString();
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new ToolArgException("Invalid mapping path: " + invalid.getMessage());
        }
    }

    private static Path activeDocumentDirectory(ToolContext context) {
        return context.access().compute(mm -> {
            IRI document = mm.getOWLOntologyManager().getOntologyDocumentIRI(mm.getActiveOntology());
            if (document == null || !"file".equalsIgnoreCase(document.toURI().getScheme())) return null;
            File file = new File(document.toURI());
            return file.toPath().toAbsolutePath().normalize().getParent();
        });
    }

    private static String canonicalIdentity(Path path) {
        if (path == null) {
            throw new ToolArgException("Canonical proposal target identity is unavailable.");
        }
        Path candidate = path.toAbsolutePath().normalize();
        try {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate.toRealPath().toString();
            }
            Deque<Path> suffix = new ArrayDeque<>();
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                Path name = existing.getFileName();
                if (name != null) suffix.push(name);
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new IOException("no existing proposal target ancestor");
            }
            Path canonical = existing.toRealPath();
            while (!suffix.isEmpty()) canonical = canonical.resolve(suffix.pop());
            return canonical.normalize().toString();
        } catch (IOException unsafe) {
            throw new ToolArgException("Canonical proposal target identity is unavailable.");
        }
    }

    private static int limit(Map<String, Object> args) {
        Object value = args.get("limit");
        if (value == null) return SssomToolService.DEFAULT_PAGE_SIZE;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new ToolArgException("limit must be an integer");
        }
        return number.intValue();
    }

    private static Map<String, Object> initialMetadata(Map<String, Object> args) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String mappingSet = Tools.optString(args, "mapping_set_id");
        String license = Tools.optString(args, "license");
        if (mappingSet != null) metadata.put("mapping_set_id", mappingSet);
        if (license != null) metadata.put("license", license);
        return metadata;
    }

    private static Map<String, String> stringMap(Object raw, String field) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map) || map.size() > 128) {
            throw new ToolArgException(field + " must be an object with at most 128 entries");
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank()
                    || !(value instanceof String text) || text.length() > 65_536) {
                throw new ToolArgException(field + " requires bounded string keys and values");
            }
            result.put(name, text);
        });
        return result;
    }

    private static SssomMappingStore.ImportMode importMode(String value) {
        return switch (value) {
            case "replace" -> SssomMappingStore.ImportMode.REPLACE;
            case "merge" -> SssomMappingStore.ImportMode.MERGE;
            default -> throw new ToolArgException("mode must be replace or merge");
        };
    }

    private static ToolArgException toolFailure(SssomStoreException failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("effects_prevented", failure.effectsPrevented());
        details.put("outcome_unknown", failure.outcomeUnknown());
        if (failure.outcomeUnknown()) details.put("retry_requires_state_check", true);
        if (!failure.findings().isEmpty()) {
            details.put("findings", failure.findings().stream().limit(25)
                    .map(finding -> finding.toJson()).toList());
            details.put("findings_truncated", failure.findings().size() > 25);
        }
        boolean retryable = Set.of("mapping_revision_conflict", "mapping_existence_conflict",
                "proposal_input_changed", "project_lock_unavailable",
                "cursor_revision_conflict").contains(failure.code());
        return new ToolArgException(failure.code(), failure.getMessage(), details, retryable);
    }

    private record Resolved(ProjectPolicy policy, DirectAccessPolicy.Rules rules,
            Path root, Path target, SssomValidationPolicy validation,
            SssomEntityIndex entities, String configuredPolicy, String closureFingerprint) {
        Identity identity() {
            return new Identity(policy.loaded(), policy.digest(),
                    policy.path() == null ? "" : canonicalIdentity(policy.path()), root, target,
                    configuredPolicy == null ? "" : configuredPolicy);
        }
    }

    private record Identity(boolean policyLoaded, String policyDigest, String policySource,
            Path root, Path target, String configuredPolicy) {
    }

    private record ModelCapture(SssomEntityIndex entities, String closureFingerprint) {
    }

    record ProposalState(ProjectPolicy policy, ModelRevision modelRevision,
            String mappingRevision, ReuseProposalTargetIdentity targetIdentity) {

        boolean mappingExists() {
            return targetIdentity.mappingExists();
        }
    }
}
