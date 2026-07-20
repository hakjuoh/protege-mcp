package io.github.hakjuoh.protege_mcp.core.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.audit.AuditEvent;
import io.github.hakjuoh.protege_mcp.core.audit.AuditExportService;
import io.github.hakjuoh.protege_mcp.core.audit.AuditFacts;
import io.github.hakjuoh.protege_mcp.core.audit.AuditLog;
import io.github.hakjuoh.protege_mcp.core.audit.AuditSettings;
import io.github.hakjuoh.protege_mcp.core.qc.HeadlessProjectQcService;
import io.github.hakjuoh.protege_mcp.core.release.HeadlessReleaseGateService;
import io.github.hakjuoh.protege_mcp.core.release.HeadlessReleaseService;
import io.github.hakjuoh.protege_mcp.core.workspace.FilesystemProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.ImportLockService;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/** Serialized application-service adapter behind the project-confined stdio tool subset. */
public final class HeadlessToolService {

    public static final Set<String> DEFAULT_CAPABILITIES = Set.of(
            Capability.ONTOLOGY_READ.value(), Capability.ONTOLOGY_ADMIN.value(),
            Capability.ONTOLOGY_RELEASE.value(), Capability.FILESYSTEM_PROJECT_READ.value(),
            // Intentional: stdio's OS child-process boundary is the principal, and the fixed surface
            // has no generic server-admin operation. This scope enables only explicit audit export;
            // network and external-filesystem authority remain absent.
            Capability.FILESYSTEM_PROJECT_WRITE.value(), Capability.SERVER_ADMIN.value());

    private final Path policyPath;
    private final OWLReasonerFactory reasonerFactory;
    private final Clock clock;
    private final String workspaceId = "stdio-" + UUID.randomUUID();
    private Path cachedAuditRoot;
    private AuditSettings cachedAuditSettings;
    private AuditLog cachedAuditLog;

    public HeadlessToolService(Path policyPath, OWLReasonerFactory reasonerFactory, Clock clock) {
        if (policyPath == null || reasonerFactory == null || clock == null) {
            throw new IllegalArgumentException("headless tool service arguments must not be null");
        }
        this.policyPath = policyPath.toAbsolutePath().normalize();
        this.reasonerFactory = reasonerFactory;
        this.clock = clock.withZone(ZoneOffset.UTC);
    }

    public Path policyPath() {
        return policyPath;
    }

    /** Serialize workspace reads/writes so one stdio session cannot race its own transactions. */
    public synchronized Map<String, Object> execute(String tool, Map<String, Object> arguments,
            Set<String> effectiveCapabilities, int maxInboundBytes, int maxOutboundBytes)
            throws IOException {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Set<String> capabilities = effectiveCapabilities == null ? Set.of()
                : Set.copyOf(effectiveCapabilities);
        AuditState audit = auditState(false);
        AuditEvent.Actor actor = actor(capabilities);
        List<String> confirmations = AuditFacts.confirmationReferences(args);
        boolean mutation = mutationExpected(HeadlessToolCatalog.definition(tool).requiredCapabilities());
        audit.log().append(event(tool, AuditEvent.Outcome.STARTED, actor, audit.ontologyIri(),
                null, null, Map.of(), confirmations, null));
        final Map<String, Object> result;
        try {
            result = switch (tool) {
                case HeadlessToolCatalog.SURFACE_TOOL -> surface(capabilities,
                        maxInboundBytes, maxOutboundBytes);
                case "validate_project_policy" -> policyResult(loadPolicy());
                case "run_project_qc" -> runProjectQc(args);
                case "verify_import_lock" -> verifyImportLock();
                case "write_import_lock" -> writeImportLock(args);
                case "run_release_gate" -> release(args, true);
                case "prepare_release" -> release(args, bool(args, "dry_run", true));
                case "export_audit_log" -> exportAudit(args);
                default -> throw new IllegalArgumentException("Unknown headless tool: " + tool);
            };
        } catch (IOException | RuntimeException failure) {
            try {
                audit.log().append(event(tool, AuditEvent.Outcome.FAILED, actor, audit.ontologyIri(),
                        mutation ? Boolean.FALSE : null, null,
                        Map.of("failure_type", failure.getClass().getSimpleName()),
                        confirmations, null));
            } catch (RuntimeException auditFailure) {
                // The tool's own error is the actionable one; keep it.
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
        try {
            audit.log().append(event(tool, AuditEvent.Outcome.SUCCEEDED, actor, audit.ontologyIri(),
                    AuditFacts.committed(result, mutation), AuditFacts.gate(result),
                    AuditFacts.summary(result), confirmations,
                    AuditFacts.releaseManifest(tool, result)));
        } catch (RuntimeException auditFailure) {
            IllegalStateException attribution = new IllegalStateException(
                    "Audit attribution failed after '" + tool + "' completed; its outcome — "
                    + "including any committed changes — still stands and was NOT rolled back. "
                    + "Do not retry before checking the current state. " + rootMessage(auditFailure));
            attribution.addSuppressed(auditFailure);
            throw attribution;
        }
        return result;
    }

    /** Attribute an authorization refusal that never enters {@link #execute}. */
    public synchronized void recordDenied(String tool, Set<String> effectiveCapabilities,
            Set<String> requiredCapabilities) {
        AuditState audit = auditState(false);
        boolean mutation = mutationExpected(requiredCapabilities);
        audit.log().append(event(tool, AuditEvent.Outcome.DENIED,
                actor(effectiveCapabilities == null ? Set.of() : effectiveCapabilities),
                audit.ontologyIri(), mutation ? Boolean.FALSE : null, null,
                Map.of("required_capabilities", requiredCapabilities.stream().sorted().toList()),
                List.of(), null));
    }

    private Map<String, Object> surface(Set<String> effectiveCapabilities,
            int maxInboundBytes, int maxOutboundBytes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transport", "stdio");
        result.put("policy_path", policyPath.toString());
        result.put("project_confined", true);
        result.put("network", "deny");
        result.put("external_paths", "deny");
        result.put("gui", false);
        result.put("undo", false);
        result.put("effective_capabilities", effectiveCapabilities.stream().sorted().toList());
        result.put("max_inbound_message_bytes", maxInboundBytes);
        result.put("max_outbound_message_bytes", maxOutboundBytes);
        List<Map<String, Object>> supported = new ArrayList<>();
        for (HeadlessToolCatalog.Definition definition : HeadlessToolCatalog.definitions()) {
            supported.add(Map.of("name", definition.name(), "required_capabilities",
                    definition.requiredCapabilities().stream().sorted().toList()));
        }
        result.put("supported_tools", supported);
        result.put("unavailable_live_tools", HeadlessToolCatalog.unavailableLiveToolNames());
        result.put("unavailable_reason", "These operations require the live Protégé adapter or have "
                + "no project-confined headless implementation; use the in-Protégé MCP server.");
        return result;
    }

    private Map<String, Object> runProjectQc(Map<String, Object> args) throws IOException {
        int limit = integer(args, "limit", 25);
        HeadlessProjectQcService.Result result = HeadlessProjectQcService.run(
                workspace(), reasonerFactory, limit, LocalDate.now(clock));
        return new LinkedHashMap<>(result.output());
    }

    private Map<String, Object> verifyImportLock() {
        Map<String, Object> result = new LinkedHashMap<>();
        ProjectPolicy policy = loadPolicy();
        result.put("policy_loaded", policy.loaded());
        result.put("policy_valid", policy.valid());
        if (!policy.valid()) {
            result.put("valid", false);
            result.put("verified", false);
            result.put("errors", policyResult(policy).get("errors"));
            return result;
        }
        Map<String, Object> imports = object(policy.effective().get("imports"));
        if (!"locked".equals(imports.get("mode"))) {
            result.put("valid", true);
            result.put("verified", false);
            result.put("reason", "imports.mode is not locked");
            return result;
        }
        try (WorkspaceSnapshot snapshot = workspace().capture()) {
            result.put("valid", true);
            result.put("verified", true);
            result.put("import_lock_digest", snapshot.importLockDigest());
            result.put("closure_fingerprint", snapshot.closureFingerprint());
            result.put("semantic_fingerprint", snapshot.fingerprint().semanticFingerprint());
        } catch (IOException | RuntimeException invalid) {
            result.put("valid", false);
            result.put("verified", true);
            result.put("error", message(invalid));
        }
        return result;
    }

    private Map<String, Object> writeImportLock(Map<String, Object> args) throws IOException {
        ProjectPolicy policy = loadPolicy();
        if (!FilesystemProjectWorkspace.isImportLockBootstrapEligible(policy)) {
            throw new IllegalArgumentException("project policy is invalid for import-lock generation");
        }
        Path output = path(args, "output");
        boolean dryRun = bool(args, "dry_run", true);
        ImportLockService.Result generated = ImportLockService.generate(workspace(), output, dryRun);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("written", generated.written());
        result.put("dry_run", generated.dryRun());
        result.put("path", generated.path());
        result.put("sha256", generated.sha256());
        result.put("entry_count", generated.entryCount());
        result.put("bytes", generated.bytes());
        result.put("backup_path", generated.backupPath());
        result.put("candidate", ContractJson.mapper().readValue(generated.content(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        result.put("network", "deny");
        return result;
    }

    private Map<String, Object> release(Map<String, Object> args, boolean dryRun) throws IOException {
        int limit = integer(args, "limit", 50);
        Path output = path(args, "output_dir");
        Path baseline = path(args, "baseline_manifest");
        String createdAt = string(args, "created_at");
        if (dryRun && createdAt == null) createdAt = HeadlessReleaseGateService.PREVIEW;
        HeadlessReleaseService.Result release = HeadlessReleaseService.execute(
                workspace(), reasonerFactory,
                new HeadlessReleaseService.Request(output, baseline, dryRun, createdAt, limit), clock);
        return HeadlessReleaseResults.project(release);
    }

    private Map<String, Object> exportAudit(Map<String, Object> args) {
        boolean dryRun = bool(args, "dry_run", true);
        String output = string(args, "output");
        AuditState audit = auditState(true);
        String target = AuditExportService.previewOutput(audit.projectRoot(), output);
        if (dryRun) return new LinkedHashMap<>(Map.of(
                "exported", false, "dry_run", true, "path", target));
        AuditExportService.Result exported = AuditExportService.export(
                audit.log().projectAuditDirectory(), audit.projectRoot(), output);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exported", true);
        result.put("dry_run", false);
        result.put("path", exported.path());
        result.put("sha256", exported.sha256());
        result.put("bytes", exported.bytes());
        result.put("event_count", exported.eventCount());
        result.put("source_count", exported.sourceCount());
        return result;
    }

    private AuditState auditState(boolean requireValidPolicy) {
        ProjectPolicy policy = loadPolicy();
        if (requireValidPolicy && (!policy.loaded() || !policy.valid() || policy.projectRoot() == null)) {
            throw new IllegalArgumentException(
                    "a valid project policy is required to export the project audit log");
        }
        Path root = policy.projectRoot();
        if (root == null || !Files.exists(root)) root = nearestExisting(policyPath.getParent());
        AuditSettings settings = policy.valid() ? AuditSettings.from(policy) : AuditSettings.defaults();
        root = root.toAbsolutePath().normalize();
        if (cachedAuditLog == null || !root.equals(cachedAuditRoot)
                || !settings.equals(cachedAuditSettings)) {
            cachedAuditLog = AuditLog.create(root, workspaceId, settings);
            cachedAuditRoot = root;
            cachedAuditSettings = settings;
        }
        String ontologyIri = policy.effective().get("root_ontology") instanceof String value
                ? value : null;
        return new AuditState(cachedAuditLog, root, ontologyIri);
    }

    private static Path nearestExisting(Path candidate) {
        Path current = candidate;
        while (current != null && !Files.exists(current)) current = current.getParent();
        if (current != null) return current;
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static AuditEvent event(String operation, AuditEvent.Outcome outcome,
            AuditEvent.Actor actor, String ontologyIri, Boolean committed, String gate,
            Map<String, Object> summary, List<String> confirmations, String releaseManifest) {
        return new AuditEvent(operation, outcome, actor, ontologyIri, null, gate, committed,
                summary, confirmations, releaseManifest);
    }

    private static AuditEvent.Actor actor(Set<String> capabilities) {
        return new AuditEvent.Actor("stdio-local", "Local stdio client", "stdio", capabilities);
    }

    private static boolean mutationExpected(Set<String> required) {
        return required.contains(Capability.ONTOLOGY_CURATE.value())
                || required.contains(Capability.ONTOLOGY_ADMIN.value())
                || required.contains(Capability.FILESYSTEM_PROJECT_WRITE.value());
    }

    private record AuditState(AuditLog log, Path projectRoot, String ontologyIri) {
    }

    private FilesystemProjectWorkspace workspace() {
        return new FilesystemProjectWorkspace(policyPath);
    }

    private ProjectPolicy loadPolicy() {
        return ProjectPolicyLoader.load(policyPath, null, null, null, true);
    }

    private static Map<String, Object> policyResult(ProjectPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy_loaded", policy.loaded() && policy.digest() != null);
        result.put("valid", policy.valid());
        result.put("policy_path", policy.path() == null ? null : policy.path().toString());
        result.put("project_root", policy.projectRoot() == null ? null : policy.projectRoot().toString());
        result.put("policy_digest", policy.digest());
        result.put("errors", policy.issues().stream().filter(issue -> "error".equals(issue.severity()))
                .map(issue -> issue.toJson()).toList());
        result.put("warnings", policy.issues().stream().filter(issue -> !"error".equals(issue.severity()))
                .map(issue -> issue.toJson()).toList());
        result.put("network", "deny");
        result.put("external_paths", "deny");
        return result;
    }

    private static int integer(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be an integer");
        int parsed = number.intValue();
        if (parsed < 0 || parsed > 1000 || number.doubleValue() != parsed) {
            throw new IllegalArgumentException(key + " must be an integer between 0 and 1000");
        }
        return parsed;
    }

    private static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(key + " must be boolean");
        return flag;
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || text.length() > 4096) {
            throw new IllegalArgumentException(key + " must be a non-blank bounded string");
        }
        return text;
    }

    private static Path path(Map<String, Object> args, String key) {
        String value = string(args, key);
        return value == null ? null : Path.of(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /**
     * The deepest causal message: AuditFileMutex labels every wrapped I/O failure as a
     * lock-acquisition problem, but the root cause (e.g. a corrupted stream) is the actionable one.
     */
    private static String rootMessage(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null) deepest = deepest.getCause();
        String message = deepest.getMessage();
        return message == null || message.isBlank() ? message(failure) : message;
    }
}
