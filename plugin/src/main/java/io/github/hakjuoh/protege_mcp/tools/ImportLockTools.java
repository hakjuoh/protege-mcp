package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.core.workspace.ImportLockFile;
import io.github.hakjuoh.protege_mcp.core.workspace.OfflineCatalog;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** Deterministic local import lock generation/verification and no-network catalog validation. */
public final class ImportLockTools {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ImportLockTools() {
    }

    public static void register(ToolRegistry tools, ToolContext ctx) {
        tools.tool("write_import_lock",
                (ex, req) -> write(ctx, Tools.args(req), () -> { }, ex));

        tools.tool("verify_import_lock",
                (ex, req) -> verify(ctx, Tools.args(req), () -> { }, ex));

        tools.tool("validate_catalog",
                (ex, req) -> validateCatalog(ctx, Tools.args(req),
                        DirectAccessPolicy.resolve(ctx, ex)));
    }

    /**
     * EDT wait bound shared by every lock-tool revision hop: write's capture and install hops AND
     * verify's two coordinate hops. Each hop runs a WorkspaceRevisionTracker fingerprint (two full
     * canonical-serialization renders), which on a large ontology legitimately outlives the default
     * 30 s wait — a default-bound verify would spuriously time out against the very lock write just
     * installed — and the install hop replaces the lock file, so a wait that expires after its body
     * starts would report a failure while the lock still lands. Kept equal to the change-set commit
     * bound, which exists for the same slow-but-succeeding reason.
     */
    static final long WRITE_HOP_TIMEOUT_MS = ChangeSetTools.COMMIT_TIMEOUT_MS;

    static CallToolResult write(ToolContext ctx, Map<String, Object> arguments) {
        return write(ctx, arguments, () -> { }, null);
    }

    /** Test seam: run one action after off-thread hashing but before the final install guard. */
    static CallToolResult write(ToolContext ctx, Map<String, Object> arguments,
            Runnable afterCapture) {
        return write(ctx, arguments, afterCapture, null);
    }

    private static CallToolResult write(ToolContext ctx, Map<String, Object> arguments,
            Runnable afterCapture, McpSyncServerExchange exchange) {
        LockAccess access = LockAccess.open(ctx, exchange, arguments, true);
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, "write deterministic import lock");
        if (denied != null) return denied;
        String configuredPolicy = Tools.optString(access.arguments, "policy_path");
        String configuredPath = Tools.optString(access.arguments, "path");
        WriteCoordinates coordinates = ctx.access().compute(mm -> {
            RevisionTools.PolicyState policy = resolvePolicyOnModelThread(mm, configuredPolicy);
            final Path target;
            final String reported;
            if (access.explicitTarget != null) {
                target = access.explicitTarget;
                reported = access.reportedPath;
            } else {
                // The beside-active lockfile is DERIVED from the open document, not caller-selected, so
                // it stays authorized under the no-policy compatibility opt-out (policy_required mode),
                // matching save_ontology/write_catalog.
                Path requestedTarget = resolveLockPath(mm, policy);
                target = access.rules.implicitPath(requestedTarget, true);
                reported = requestedTarget.toString();
            }
            Coordinates gathered = gather(mm, target);
            // The import lock being replaced must not participate in its own pre-write revision token.
            // The listener-backed session coordinate plus the active semantic/document coordinates cover
            // active/import edits, ontology switches, and prefix/document changes during hashing.
            ModelRevision revision = ctx.revisions().current(mm, null, null).revision();
            return new WriteCoordinates(gathered, revision, policyPath(policy.policy()),
                    policy.policy().digest(), reported);
        }, WRITE_HOP_TIMEOUT_MS);
        LockCapture capture = capture(coordinates.coordinates); // hashes off the model thread
        if (!capture.errors.isEmpty()) {
            return Tools.ok(Map.of("written", false, "valid", false, "errors", capture.errors));
        }
        final Map<String, Object> lock = lockJson(capture);
        final byte[] bytes;
        try {
            bytes = (JSON.writeValueAsString(lock) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolArgException("Could not render import lock: " + e.getMessage());
        }
        afterCapture.run();

        ctx.writeLock().lock();
        try {
            try {
                return ctx.access().compute(mm -> installCapturedLock(ctx, mm, coordinates, capture,
                        lock, bytes, configuredPolicy, configuredPath), WRITE_HOP_TIMEOUT_MS);
            } catch (McpAccessException e) {
                // The bounded EDT wait can expire (or be interrupted) after the body has already
                // started, and OntologyAccess cannot cancel a running body — so the lock may still be
                // installed even though this call reports a failure. Never claim nothing happened.
                throw new McpAccessException(e.getMessage() + " The install body may still complete "
                        + "on the Protégé UI thread: call verify_import_lock (or read the lock file) "
                        + "to see whether this capture landed before retrying.", e);
            }
        } finally {
            ctx.writeLock().unlock();
        }
    }

    private static CallToolResult installCapturedLock(ToolContext ctx,
            org.protege.editor.owl.model.OWLModelManager mm, WriteCoordinates coordinates,
            LockCapture capture, Map<String, Object> lock, byte[] bytes,
            String configuredPolicy, String configuredPath) {
        if (ctx.controller().isReadOnly()) {
            return WriteTools.readOnlyDenied();
        }
        ModelRevision current = ctx.revisions().current(mm, null, null).revision();
        if (!coordinates.revision.equals(current)) {
            return Tools.json().put("written", false).put("valid", false)
                    .put("error_code", "revision_conflict")
                    .put("base_revision", RevisionTools.revisionJson(coordinates.revision))
                    .put("current_revision", RevisionTools.revisionJson(current))
                    .put("path", capture.path.toString()).result();
        }

        // With a policy-selected target, repeat discovery at install time. A newly created nearer
        // project.yaml, an edited policy, or a different resolved lockfile must not let an old capture
        // overwrite a path that is no longer effective. Explicit paths intentionally remain independent
        // of policy validity, matching resolveLockPath's documented bootstrap behavior.
        if (configuredPath == null) {
            RevisionTools.PolicyState currentPolicy = resolvePolicyOnModelThread(mm, configuredPolicy);
            final Path currentTarget;
            try {
                currentTarget = resolveLockPath(mm, currentPolicy);
            } catch (RuntimeException changedPolicy) {
                return policyConflict(capture.path, changedPolicy.getMessage());
            }
            if (!capture.path.toAbsolutePath().normalize()
                    .equals(DirectAccessPolicy.canonicalCandidate(
                            currentTarget.toAbsolutePath().normalize()))
                    || !java.util.Objects.equals(coordinates.policyPath,
                            policyPath(currentPolicy.policy()))
                    || !java.util.Objects.equals(coordinates.policyDigest,
                            currentPolicy.policy().digest())) {
                return policyConflict(capture.path,
                        "The effective project policy or import-lock target changed during capture.");
            }
        }

        try {
            atomicWrite(capture.path, bytes);
        } catch (IOException e) {
            throw new ToolArgException("Could not write import lock: " + e.getMessage());
        }
        return Tools.json().put("written", true).put("path", coordinates.reportedPath)
                .put("entry_count", capture.entries.size()).put("sha256", RevisionTools.sha256(bytes))
                .put("lock", lock).result();
    }

    private static CallToolResult policyConflict(Path path, String message) {
        return Tools.json().put("written", false).put("valid", false)
                .put("error_code", "policy_conflict").put("path", path.toString())
                .putIfNotNull("message", message).result();
    }

    private static String policyPath(ProjectPolicy policy) {
        return policy.path() == null ? null : policy.path().toString();
    }

    /** In-model-thread policy reload used only by the final install guard (nested EDT hops are invalid). */
    private static RevisionTools.PolicyState resolvePolicyOnModelThread(
            org.protege.editor.owl.model.OWLModelManager mm, String configured) {
        Path explicit = null;
        String error = null;
        if (configured != null) {
            try {
                explicit = Path.of(configured);
            } catch (InvalidPathException e) {
                error = "Invalid policy_path '" + configured + "': " + e.getMessage();
            }
        }
        ProjectPolicyTools.PolicyContext live = ProjectPolicyTools.capture(mm);
        ProjectPolicy policy = error == null
                ? io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(explicit,
                        live.documentPath(), live.activeOntologyIri(), live.installedReasoners())
                : ProjectPolicy.notFound();
        return new RevisionTools.PolicyState(policy, live, error);
    }

    static CallToolResult verify(ToolContext ctx, Map<String, Object> arguments) {
        return verify(ctx, arguments, () -> { }, null);
    }

    /** Test seam for a concurrent model/import change after off-thread hashing. */
    static CallToolResult verify(ToolContext ctx, Map<String, Object> arguments, Runnable afterCapture) {
        return verify(ctx, arguments, afterCapture, null);
    }

    private static CallToolResult verify(ToolContext ctx, Map<String, Object> arguments,
            Runnable afterCapture, McpSyncServerExchange exchange) {
        LockAccess access = LockAccess.open(ctx, exchange, arguments, false);
        String configuredPolicy = Tools.optString(access.arguments, "policy_path");
        VerifyCoordinates initial = ctx.access().compute(mm -> {
            RevisionTools.PolicyState policy = resolvePolicyOnModelThread(mm, configuredPolicy);
            Coordinates coordinates = gather(mm, access.explicitTarget != null ? access.explicitTarget
                    : access.rules.implicitPath(resolveLockPath(mm, policy), false));
            ModelRevision revision = ctx.revisions().current(mm, null, policy.policy().digest()).revision();
            return new VerifyCoordinates(coordinates, revision, policy.policy().digest());
        }, WRITE_HOP_TIMEOUT_MS);
        LockCapture current = capture(initial.coordinates); // hashes off the model thread
        afterCapture.run();
        VerifyCoordinates finalState = ctx.access().compute(mm -> new VerifyCoordinates(
                gather(mm, initial.coordinates.path),
                ctx.revisions().current(mm, null, initial.policyDigest).revision(),
                initial.policyDigest),
                WRITE_HOP_TIMEOUT_MS);
        List<String> errors = new ArrayList<>(current.errors);
        if (!initial.revision.equals(finalState.revision)
                || !initial.coordinates.equals(finalState.coordinates)) {
            errors.add("ontology/import state changed while the lock was being verified; retry against "
                    + "a stable workspace revision");
        }
        return Tools.ok(compareCapture(current, errors));
    }

    /**
     * Authorize an explicit policy reference against the currently discovered project without
     * requiring that referenced policy to validate yet. Lock-path resolution deliberately owns
     * that validation so it can preserve its fail-closed diagnostics and allow an explicit
     * {@code path} to bootstrap a policy-declared lockfile. An invalid platform path performs no
     * filesystem access and is left untouched for {@link RevisionTools#resolvePolicy} to report.
     */
    private static Map<String, Object> authorizePolicyArgument(Map<String, Object> arguments,
            DirectAccessPolicy.Rules rules) {
        String configured = Tools.optString(arguments, "policy_path");
        if (configured == null) return arguments;
        try {
            Path.of(configured);
        } catch (InvalidPathException e) {
            return arguments;
        }
        Path authorized = rules.readPath(configured);
        Map<String, Object> rewritten = new LinkedHashMap<>(arguments);
        rewritten.put("policy_path", authorized.toString());
        return rewritten;
    }

    /**
     * Request-scoped authorization shared by write() and verify(): the resolved direct-access rules
     * plus, when the caller supplied an explicit {@code path}, that path already authorized. An
     * explicit path is authorized here — from the RAW request string, so a relative path is rooted at
     * the policy's canonical project_root, never at the process working directory — while the
     * policy-defaulted target can only be authorized inside the model hop that resolves it.
     *
     * <p>The documented explicit-path bootstrap must survive a discovered project policy that is
     * loaded but invalid (typically because the declared imports.lockfile does not exist yet), so a
     * request in exactly that state bypasses the rules and is authorized by
     * {@link #bootstrapExplicitPath}: same capability and canonical-containment enforcement, no
     * policy-validity requirement, no policy-granted widening. {@code rules} is null only then.
     */
    private static final class LockAccess {
        final DirectAccessPolicy.Rules rules;
        final Path explicitTarget;
        final String reportedPath;
        final Map<String, Object> arguments;

        private LockAccess(DirectAccessPolicy.Rules rules, Path explicitTarget, String reportedPath,
                Map<String, Object> arguments) {
            this.rules = rules;
            this.explicitTarget = explicitTarget;
            this.reportedPath = reportedPath;
            this.arguments = arguments;
        }

        static LockAccess open(ToolContext ctx, McpSyncServerExchange exchange,
                Map<String, Object> arguments, boolean write) {
            String configuredPolicy = Tools.optString(arguments, "policy_path");
            String configuredPath = Tools.optString(arguments, "path");
            DirectAccessPolicy.Rules rules;
            ProjectPolicy discovered;
            try {
                rules = DirectAccessPolicy.resolve(ctx, exchange);
                discovered = rules.policy();
            } catch (ToolArgException refusal) {
                // Older DirectAccessPolicy revisions reject a loaded-but-invalid discovered policy
                // when resolving, before any argument is seen; current ones defer that rejection to
                // the use-time readPath/writePath. Either way only the documented bootstrap request
                // shape may continue, and only once the invalid-policy cause is independently
                // re-verified — a capability or policy-resolution refusal is never downgraded.
                if (configuredPath == null || configuredPolicy != null) throw refusal;
                rules = null;
                discovered = verifiedInvalidDiscovery(ctx, exchange, refusal);
            }
            boolean bootstrap = configuredPath != null && configuredPolicy == null
                    && discovered.loaded() && !discovered.valid();
            Path explicitTarget = null;
            String reportedPath = null;
            if (configuredPath != null) {
                explicitTarget = bootstrap
                        ? bootstrapExplicitPath(discovered, exchange, configuredPath, write)
                        : write ? rules.writePath(configuredPath) : rules.readPath(configuredPath);
                // Report the request-form absolute path (matching a plain save); a relative request
                // has no caller-visible absolute form, so it reports the authorized target instead.
                Path raw = Path.of(configuredPath);
                reportedPath = raw.isAbsolute()
                        ? raw.toAbsolutePath().normalize().toString() : explicitTarget.toString();
            }
            return new LockAccess(rules, explicitTarget, reportedPath,
                    rules == null ? arguments : authorizePolicyArgument(arguments, rules));
        }
    }

    /** Re-derive the loaded-but-invalid discovered policy behind a resolve()-time refusal, or rethrow. */
    private static ProjectPolicy verifiedInvalidDiscovery(ToolContext ctx,
            McpSyncServerExchange exchange, ToolArgException refusal) {
        return DirectAccessPolicy.verifiedInvalidDiscovery(ctx, exchange, refusal);
    }

    /**
     * Containment-only authorization for the documented explicit-path bootstrap: an explicit lock
     * path stays usable while the discovered policy is loaded but invalid, so the declared lockfile
     * can be created at all. See {@link DirectAccessPolicy#bootstrapExplicitPath}.
     */
    private static Path bootstrapExplicitPath(ProjectPolicy policy, McpSyncServerExchange exchange,
            String configured, boolean write) {
        return DirectAccessPolicy.bootstrapExplicitPath(policy, exchange, configured, write, "lock");
    }

    /** {@code lockfile_source} label: the lockfile is pinned by the reviewed project policy. */
    static final String SOURCE_POLICY_DECLARED = "policy_declared";
    /** {@code lockfile_source} label: the beside-document {@code imports.lock.json} default. */
    static final String SOURCE_BESIDE_DOCUMENT = "beside_document";
    /** Trust label for a lockfile that is co-located with — and writable by — the ontology folder. */
    static final String BESIDE_DOCUMENT_TRUST_NOTE = "The lockfile is not policy-pinned: it lives "
            + "beside (and is writable with) the ontology folder, so this verification attests "
            + "accident-safety (content unchanged since lock time), not tamper-evidence.";

    /** Compare the lock against the exact already-loaded graph captured by project QC/preflight. */
    static Map<String, Object> verifyForGate(ProjectPolicy policy,
            ImportTools.ImportReport importReport, OntologyFingerprint activeFingerprint,
            String closureFingerprint) {
        // Locked policies keep resolving ONLY the policy asset (ADR 0005 decision 4): the
        // externally-resolved variant below must never widen this resolution rule.
        List<Path> paths = policy.assets().getOrDefault("import_lock", List.of());
        if (paths.size() != 1) {
            return Map.of("valid", false, "errors",
                    List.of("locked policy did not resolve exactly one imports.lockfile"),
                    "missing_entries", List.of(), "extra_entries", List.of(),
                    "mismatched_entries", List.of(), "entry_count", 0);
        }
        return verifyForGate(paths.get(0), importReport, activeFingerprint, closureFingerprint);
    }

    /**
     * The same gate comparison-plus-loaded-content attestation against an externally resolved lock
     * path — the request-triggered ({@code lock_mode=verify|required}) gate entry for unlocked or
     * absent policies. Resolution stays the caller's responsibility (see
     * {@link #resolveRequestGateLock}); this method never consults {@code imports.lockfile}.
     */
    static Map<String, Object> verifyForGate(Path lockPath,
            ImportTools.ImportReport importReport, OntologyFingerprint activeFingerprint,
            String closureFingerprint) {
        LockCapture current = capture(gather(importReport, lockPath));
        Map<String, Object> comparison = compareCapture(current, new ArrayList<>(current.errors));
        if (!Boolean.TRUE.equals(comparison.get("valid"))) {
            return comparison;
        }
        return attestLoadedContent(comparison, current, importReport, activeFingerprint,
                closureFingerprint);
    }

    /** The lockfile a request-triggered gate verification resolved, with its trust source label. */
    record GateLock(Path path, String source) { }

    /**
     * Resolve the lockfile for a request-triggered gate verification with the released
     * {@code verify_import_lock} TOOL rules (ADR 0005 decision 4): the policy-declared
     * {@code imports.lockfile} asset when exactly one resolves; the beside-active-document
     * {@code imports.lock.json} default only when no lockfile is declared or no policy is loaded.
     * The released refusal states — a declared-but-unresolved lockfile, an invalid policy, no local
     * document folder — abort the request as {@link ToolArgException} rather than emitting a
     * finding. The beside-document default derives from the SAME-hop import report the gate
     * captured, so the verification and the QC snapshot see one document — and is authorized
     * through the request's {@link DirectAccessPolicy.Rules} (canonical, symlink-resolved project
     * containment) BEFORE any existence check or read, exactly like the released
     * {@code verify_import_lock} tool.
     */
    static GateLock resolveRequestGateLock(ProjectPolicy policy,
            ImportTools.ImportReport importReport, DirectAccessPolicy.Rules rules) {
        if (policy.loaded()) {
            List<Path> policyPaths = policy.assets().getOrDefault("import_lock", List.of());
            String declared = declaredLockfile(policy);
            boolean lockfileIssue = policy.issues().stream().anyMatch(issue ->
                    "error".equals(issue.severity()) && "imports.lockfile".equals(issue.path()));
            if (declared != null && policyPaths.size() != 1 && (lockfileIssue || policy.valid())) {
                throw new ToolArgException("The project policy declares imports.lockfile '" + declared
                        + "' but it did not resolve to a file (for example it does not exist yet), "
                        + "so lock_mode cannot verify it. Create the declared lockfile "
                        + "(write_import_lock) first; the lock is never read anywhere else.");
            }
            if (!policy.valid()) {
                throw new ToolArgException("Cannot resolve the lockfile for lock_mode: the project "
                        + "policy is invalid (" + policyErrorCodes(policy)
                        + " — see validate_project_policy).");
            }
            if (policyPaths.size() == 1) {
                return new GateLock(policyPaths.get(0), SOURCE_POLICY_DECLARED);
            }
        }
        java.io.File active = importReport == null ? null
                : localDocumentFile(importReport.root.get("document_iri"));
        if (active == null || active.getParentFile() == null) {
            throw new ToolArgException("The active ontology has no local document folder, so no "
                    + "beside-document imports.lock.json can be resolved for lock_mode.");
        }
        return new GateLock(authorizeBesideDocumentLock(rules,
                active.getParentFile().toPath().resolve("imports.lock.json")),
                SOURCE_BESIDE_DOCUMENT);
    }

    /**
     * Authorize READING a beside-document lockfile against the request's filesystem rules before
     * any existence probe or byte is read. {@code implicitPath} pins the canonical, symlink-resolved
     * path and refuses an out-of-project escape under a confining policy — a symlinked
     * {@code imports.lock.json} pointing outside {@code project_root} must never leak out-of-project
     * content into a caller-visible comparison. Fails closed when no request-scoped rules were
     * threaded (a caller that cannot authorize the read must not perform it).
     */
    private static Path authorizeBesideDocumentLock(DirectAccessPolicy.Rules rules, Path lockPath) {
        if (rules == null) {
            throw new ToolArgException("lock_mode cannot authorize the beside-document lockfile "
                    + "read on this surface: no request-scoped filesystem rules are available.");
        }
        return rules.implicitPath(lockPath, false);
    }

    /**
     * Verify a TO-BE-LOADED closure's coordinates and SHA-256 hashes against the lockfile resolved
     * for the loaded document — the {@code lock_mode} entry for {@code load_ontology} and
     * {@code merge_ontology_document} (ADR 0005 decision 4). The lockfile resolves beside the loaded
     * document by default; the policy-declared lockfile applies only when the loaded document is the
     * project's resolved root artifact. A mismatch, or {@code required} with no lockfile, refuses
     * the load with a structured {@link ToolArgException} while the closure still lives in the
     * throwaway parse manager — nothing has been applied to the workspace. The loaded-content
     * attestation is deliberately NOT run here: it requires the closure fingerprint captured in the
     * same model-thread hop as a QC snapshot, which loading operations do not have (gate-only).
     * The beside-document lockfile read is authorized through the request's rules (canonical,
     * symlink-resolved project containment) BEFORE any existence check or read. Returns the
     * verification map for the tool's structured result.
     */
    static Map<String, Object> verifyClosureBeforeLoad(DirectAccessPolicy.Rules rules,
            ImportTools.ImportReport report, java.io.File loadedDocument, LockMode mode) {
        ProjectPolicy policy = rules == null ? null : rules.policy();
        if (policy != null && policy.loaded() && !policy.valid()) {
            // Unreachable through the tool handlers (source authorization already fails closed on an
            // invalid policy), but never trust an invalid policy to resolve a lockfile.
            throw new ToolArgException("Cannot resolve the lockfile for lock_mode: the project "
                    + "policy is invalid (" + policyErrorCodes(policy)
                    + " — see validate_project_policy).");
        }
        Path document = loadedDocument == null ? null
                : loadedDocument.getAbsoluteFile().toPath().normalize();
        Path lockPath = null;
        String source = SOURCE_BESIDE_DOCUMENT;
        if (policy != null && policy.loaded() && isProjectRootArtifact(policy, document)) {
            List<Path> policyPaths = policy.assets().getOrDefault("import_lock", List.of());
            if (policyPaths.size() == 1) {
                lockPath = policyPaths.get(0);
                source = SOURCE_POLICY_DECLARED;
            } else if (declaredLockfile(policy) != null) {
                // The project's root artifact is verified against the DECLARED lockfile only; a
                // declared-but-unresolved lockfile is the file-absent state for this load.
                return absentLockResult(policy.projectRoot() == null ? null
                        : policy.projectRoot().resolve(declaredLockfile(policy)),
                        SOURCE_POLICY_DECLARED, mode);
            }
        }
        if (lockPath == null && source.equals(SOURCE_BESIDE_DOCUMENT)) {
            if (document == null || document.getParent() == null) {
                return absentLockResult(null, SOURCE_BESIDE_DOCUMENT, mode);
            }
            lockPath = authorizeBesideDocumentLock(rules,
                    document.getParent().resolve("imports.lock.json"));
        }
        if (!Files.isRegularFile(lockPath)) {
            return absentLockResult(lockPath, source, mode);
        }
        LockCapture current = capture(gather(report, lockPath));
        Map<String, Object> verification = new LinkedHashMap<>(
                compareCapture(current, new ArrayList<>(current.errors)));
        verification.put("lockfile_source", source);
        if (SOURCE_BESIDE_DOCUMENT.equals(source)) {
            verification.put("lockfile_note", BESIDE_DOCUMENT_TRUST_NOTE);
        }
        if (!Boolean.TRUE.equals(verification.get("valid"))) {
            throw new ToolArgException("Import lock verification (lock_mode=" + mode.value()
                    + ") refused the load before any workspace change: the document's import closure "
                    + "does not match the lockfile " + lockPath + " (errors: "
                    + verification.get("errors") + "; missing_entries: "
                    + verification.get("missing_entries") + "; extra_entries: "
                    + verification.get("extra_entries") + "; mismatched_entries: "
                    + verification.get("mismatched_entries") + "). Nothing was loaded or merged.");
        }
        return verification;
    }

    /** The clean skip (verify) or structured refusal (required) for a lockfile that does not exist. */
    private static Map<String, Object> absentLockResult(Path lockPath, String source, LockMode mode) {
        String location = lockPath == null
                ? "the loaded document is not a local file, so no beside-document lockfile can exist"
                : "no lockfile exists at " + lockPath;
        if (mode == LockMode.REQUIRED) {
            throw new ToolArgException("lock_mode=required refused the load before any workspace "
                    + "change: " + location + ". Create the lock (write_import_lock) or load with "
                    + "lock_mode=verify to skip when it is absent. Nothing was loaded or merged.");
        }
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("verified", false);
        skipped.put("skipped", true);
        if (lockPath != null) {
            skipped.put("path", lockPath.toString());
        }
        skipped.put("lockfile_source", source);
        skipped.put("note", "No lockfile was found (" + location + "), so nothing was verified; "
                + "lock_mode=verify skips cleanly. Pass lock_mode=required to make this an error.");
        return skipped;
    }

    /** Whether {@code document} is the policy's resolved {@code interoperability.root_artifact}. */
    private static boolean isProjectRootArtifact(ProjectPolicy policy, Path document) {
        if (document == null) {
            return false;
        }
        List<Path> rootArtifacts = policy.assets().getOrDefault("root_artifact", List.of());
        if (rootArtifacts.size() != 1) {
            return false;
        }
        try {
            return java.nio.file.Files.isSameFile(rootArtifacts.get(0), document);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The local file behind an import-report document IRI, or null when it is not a local file. */
    private static java.io.File localDocumentFile(Object documentIri) {
        if (documentIri == null || String.valueOf(documentIri).isBlank()) {
            return null;
        }
        try {
            return SidecarPaths.toFile(
                    org.semanticweb.owlapi.model.IRI.create(String.valueOf(documentIri)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Fail closed unless the on-disk bytes just hashed are shown to be the loaded closure content
     * the QC snapshot consumed. The lock pins disk bytes only: an unsaved in-memory edit of an
     * imported ontology, or a tamper-then-revert of the file around Protégé's load, leaves every
     * coordinate and on-disk hash intact while reasoning/validation consume different axioms.
     * Rebuild the snapshot's closure fingerprint from the captured live active-ontology fingerprint
     * (the active document may legitimately be dirty) plus a fresh no-network parse of the locked
     * documents sharing one import closure, and require it to equal the closure fingerprint captured
     * in the same model-thread hop as the QC snapshot. Equality attests that the validated closure
     * matches the lock-verified disk content up to the shared artifact-boundary equivalence of the
     * v2 semantic fingerprint — which deliberately ignores unannotated declarations of entities
     * already in a document's signature (the same boundary verified saves use), so a divergence
     * confined to that inert class can still attest; any semantically visible divergence fails
     * closed.
     */
    private static Map<String, Object> attestLoadedContent(Map<String, Object> comparison,
            LockCapture current, ImportTools.ImportReport importReport,
            OntologyFingerprint activeFingerprint, String closureFingerprint) {
        List<String> errors = new ArrayList<>();
        List<String> sessionOnly = new ArrayList<>();
        boolean verified = false;
        if (activeFingerprint == null || closureFingerprint == null) {
            errors.add("the gate did not capture the loaded closure fingerprint, so the lock-matching "
                    + "disk content cannot be attested as the content QC actually validated");
        } else {
            String rootKey = closureMemberKey(new org.semanticweb.owlapi.model.OWLOntologyID(
                    memberIri(importReport.root.get("ontology_iri")),
                    memberIri(importReport.root.get("version_iri"))));
            List<String> members = new ArrayList<>();
            members.add(closureMemberLine(rootKey, activeFingerprint.semanticFingerprint()));
            try {
                List<ParsedLockedDocument> parsed = parseLockedClosure(current.entries,
                        importReport.resolvedImports);
                for (int i = 0; i < parsed.size(); i++) {
                    ParsedLockedDocument member = parsed.get(i);
                    // A cycle can make the active document itself a locked import; the live capture
                    // above already attests that single closure member, dirty or not.
                    if (member.memberKey().equals(rootKey)) continue;
                    members.add(member.memberLine());
                    if (member.sessionOnly()) sessionOnly.add(current.entries.get(i).document);
                }
            } catch (IOException e) {
                errors.add(e.getMessage() == null ? "could not attest the locked import closure: "
                        + e.getClass().getSimpleName() : e.getMessage());
            }
            if (errors.isEmpty()) {
                members.sort(Comparator.naturalOrder());
                if (RevisionTools.sha256(String.join("\n", members)
                        .getBytes(StandardCharsets.UTF_8)).equals(closureFingerprint)) {
                    verified = true;
                } else {
                    errors.add("the loaded import closure content does not provably match the "
                            + "lock-verified on-disk documents: an unsaved in-memory edit of an "
                            + "imported ontology (or an on-disk change since Protégé loaded it) keeps "
                            + "every coordinate and disk hash intact while QC validates different "
                            + "axioms. Reload or save the imported ontologies so disk and workspace "
                            + "agree, then rerun.");
                    if (!sessionOnly.isEmpty()) {
                        errors.add("note: locked document(s) " + sessionOnly + " contain anonymous "
                                + "individuals whose OWLAPI ids are session-local, so their content "
                                + "equality cannot be proven across parses; the gate fails closed for "
                                + "them even when the content is unchanged");
                    }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>(comparison);
        result.put("loaded_content_verified", verified);
        if (!verified) {
            result.put("valid", false);
            List<String> combined = new ArrayList<>();
            Object base = comparison.get("errors");
            if (base instanceof List<?>) ((List<?>) base).forEach(e -> combined.add(String.valueOf(e)));
            combined.addAll(errors);
            result.put("errors", combined);
        }
        return result;
    }

    private static org.semanticweb.owlapi.model.IRI memberIri(Object value) {
        String iri = blankToNull(string(value));
        return iri == null ? null : org.semanticweb.owlapi.model.IRI.create(iri);
    }

    /**
     * MUST byte-match the (private) member-key encoding inside IsolatedValidationSnapshot's closure
     * fingerprint — ontology IRI, version IRI and OWLOntologyID rendering NUL-joined — so a rebuilt
     * closure digest can equal a captured one. Pinned by the locked-gate pass-path tests: any drift
     * makes every clean locked run fail closed as unattested, never a false pass.
     */
    private static String closureMemberKey(org.semanticweb.owlapi.model.OWLOntologyID id) {
        return (id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : "") + "\u0000"
                + (id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : "") + "\u0000"
                + id;
    }

    private static String closureMemberLine(String key, String semanticFingerprint) {
        return key.getBytes(StandardCharsets.UTF_8).length + ":" + key + "\u0000" + semanticFingerprint;
    }

    /** Fresh-parse attestation for one locked document: closure-member line plus stability caveat. */
    record ParsedLockedDocument(String memberKey, String memberLine, boolean sessionOnly) { }

    /** (closure bytes + live edges digest) -> parsed members; an unchanged closure parses once. */
    private static final int PARSE_CACHE_LIMIT = 16;
    private static final Map<String, List<ParsedLockedDocument>> PARSE_CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, List<ParsedLockedDocument>> eldest) {
                    return size() > PARSE_CACHE_LIMIT;
                }
            };

    /**
     * Parse every locked on-disk document in ONE private manager with the same no-network discipline
     * as the verified-save reload, leaf-first along the live resolved-import edges, so each document
     * is parsed WITH the same import closure Protégé had available when it loaded it. A modular
     * ontology routinely uses a property that only an imported document declares; parsing members in
     * isolation types those axioms differently from the loaded closure and would fail a clean,
     * untampered project forever. An import that is not an already-parsed lock-verified member (an
     * unresolved import, or a cyclic edge whose target cannot be parsed first) resolves to one empty
     * placeholder and any resulting typing drift fails closed as divergence, never as a false pass.
     *
     * <p>Each document is read from disk exactly once by {@link #verifiedSource}, so the fingerprint
     * always derives from the same bytes that hash check verified. Members are fingerprinted only
     * after every member has loaded: a later member's load may legally rewrite an earlier one
     * (OWLAPI's illegal-punning repair spans the loaded closure), exactly as it did in Protégé.
     */
    static List<ParsedLockedDocument> parseLockedClosure(List<LockEntry> entries,
            List<Map<String, Object>> resolvedImports) throws IOException {
        String cacheKey = closureContextKey(entries, resolvedImports);
        synchronized (PARSE_CACHE) {
            List<ParsedLockedDocument> cached = PARSE_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        Map<String, LockEntry> byKey = new LinkedHashMap<>();
        entries.forEach(entry -> byKey.putIfAbsent(entry.key(), entry));
        Map<String, org.semanticweb.owlapi.io.StreamDocumentSource> sources = new LinkedHashMap<>();
        for (LockEntry entry : entries) {
            sources.put(entry.key(), verifiedSource(entry));
        }
        // Live graph context, restricted to locked members: which member each import declaration
        // resolved to, and therefore which members must be parsed before which.
        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        Map<String, String> declarationTargets = new LinkedHashMap<>();
        for (Map<String, Object> row : resolvedImports) {
            String source = entryKey(row.get("source_ontology_iri"), row.get("source_version_iri"));
            String target = entryKey(row.get("target_ontology_iri"), row.get("target_version_iri"));
            if (!byKey.containsKey(target)) continue;
            declarationTargets.putIfAbsent(string(row.get("import_iri")), target);
            if (byKey.containsKey(source) && !source.equals(target)) {
                dependsOn.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (LockEntry entry : entries) {
                String key = entry.key();
                if (placed.contains(key)
                        || !placed.containsAll(dependsOn.getOrDefault(key, Set.of()))) {
                    continue;
                }
                order.add(key);
                placed.add(key);
                progress = true;
            }
        }
        // Cyclic leftovers keep the deterministic entry order; their unmet imports resolve to the
        // placeholder, so drift fails closed.
        for (LockEntry entry : entries) {
            if (!placed.contains(entry.key())) order.add(entry.key());
        }

        Path placeholder = Files.createTempFile("protege-mcp-lock-gate-imports-", ".ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            org.semanticweb.owlapi.model.OWLOntologyManager manager = OwlManagers.create();
            org.semanticweb.owlapi.model.IRI placeholderIri =
                    org.semanticweb.owlapi.model.IRI.create(placeholder.toUri());
            Map<String, org.semanticweb.owlapi.model.IRI> loadedDocuments = new LinkedHashMap<>();
            manager.getIRIMappers().add(importIri -> {
                String target = declarationTargets.get(importIri.toString());
                org.semanticweb.owlapi.model.IRI document =
                        target == null ? null : loadedDocuments.get(target);
                return document == null ? placeholderIri : document;
            });
            org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration config =
                    new org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(
                                    org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT)
                            .setFollowRedirects(false);
            Map<String, org.semanticweb.owlapi.model.OWLOntology> parsedByKey = new LinkedHashMap<>();
            for (String key : order) {
                try {
                    parsedByKey.put(key,
                            manager.loadOntologyFromOntologyDocument(sources.get(key), config));
                    loadedDocuments.put(key, sources.get(key).getDocumentIRI());
                } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException
                        | RuntimeException e) {
                    throw new IOException("could not attest locked import document "
                            + byKey.get(key).absolute + ": " + (e.getMessage() == null
                                    ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
            List<ParsedLockedDocument> results = new ArrayList<>();
            try {
                for (LockEntry entry : entries) {
                    org.semanticweb.owlapi.model.OWLOntology parsed = parsedByKey.get(entry.key());
                    OntologyFingerprint fingerprint = OntologyFingerprints.compute(parsed);
                    String key = closureMemberKey(parsed.getOntologyID());
                    results.add(new ParsedLockedDocument(key,
                            closureMemberLine(key, fingerprint.semanticFingerprint()),
                            !fingerprint.releaseStable()));
                }
            } catch (RuntimeException e) {
                throw new IOException("could not attest the locked import closure: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            List<ParsedLockedDocument> immutable = List.copyOf(results);
            synchronized (PARSE_CACHE) {
                PARSE_CACHE.put(cacheKey, immutable);
            }
            return immutable;
        } finally {
            Files.deleteIfExists(placeholder);
        }
    }

    /**
     * Read one locked document from disk EXACTLY once: the bytes are SHA-256-hashed while they
     * stream into the in-memory document source the parser will consume, and the digest must equal
     * the hash the base lock comparison just verified — the parsed bytes are therefore the
     * lock-verified bytes, and a document swapped between separate reads can never attest content
     * nobody hashed. The document IRI stays the on-disk location so relative IRIs resolve exactly as
     * they did when Protégé loaded the file.
     */
    private static org.semanticweb.owlapi.io.StreamDocumentSource verifiedSource(LockEntry entry)
            throws IOException {
        if (!Files.isRegularFile(entry.absolute)) {
            throw new IOException("could not attest locked import document " + entry.absolute
                    + ": not a regular file");
        }
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        final org.semanticweb.owlapi.io.StreamDocumentSource source;
        try (java.io.InputStream in = new java.security.DigestInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(entry.absolute)), digest)) {
            // The source constructor consumes the stream to EOF into its private buffer, wrapping
            // any read failure unchecked.
            source = new org.semanticweb.owlapi.io.StreamDocumentSource(in,
                    org.semanticweb.owlapi.model.IRI.create(entry.absolute.toUri()));
        } catch (RuntimeException e) {
            throw new IOException("could not attest locked import document " + entry.absolute + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        if (!hex.toString().equals(entry.sha256)) {
            throw new IOException("locked import document " + entry.absolute
                    + " changed while the gate was attesting it");
        }
        return source;
    }

    /** The lock-entry key an import-report row's coordinates map to; matches {@link LockEntry#key}. */
    private static String entryKey(Object ontologyIri, Object versionIri) {
        String ontology = string(ontologyIri);
        String version = string(versionIri);
        return version.isBlank() ? ontology : ontology + " @ " + version;
    }

    /**
     * Injective digest of everything the closure parse depends on: each member's document path,
     * exact content hash and identity, plus every live resolved-import edge (a member's parse result
     * depends on its imports' content and on which member each declaration resolved to). Any changed
     * byte, membership, or edge reparses instead of serving a stale member.
     */
    private static String closureContextKey(List<LockEntry> entries,
            List<Map<String, Object>> resolvedImports) {
        List<String> lines = new ArrayList<>();
        for (LockEntry entry : entries) {
            lines.add(contextLine("entry", entry.absolute.toString(), entry.sha256, entry.key()));
        }
        for (Map<String, Object> row : resolvedImports) {
            lines.add(contextLine("edge",
                    entryKey(row.get("source_ontology_iri"), row.get("source_version_iri")),
                    entryKey(row.get("target_ontology_iri"), row.get("target_version_iri")),
                    string(row.get("import_iri"))));
        }
        lines.sort(Comparator.naturalOrder());
        return RevisionTools.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** Length-prefixed field encoding so no field content can imitate another line's boundaries. */
    private static String contextLine(String... fields) {
        StringBuilder line = new StringBuilder();
        for (String field : fields) {
            if (line.length() > 0) line.append(' ');
            line.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
        }
        return line.toString();
    }

    private static Map<String, Object> compareCapture(LockCapture current, List<String> errors) {
        Map<String, LockEntry> expected = new LinkedHashMap<>();
        String lockDigest = null;
        try {
            ImportLockFile.Document parsed = ImportLockFile.read(current.path);
            lockDigest = parsed.sha256();
            for (ImportLockFile.Entry value : parsed.entries()) {
                LockEntry entry = lockEntry(value);
                expected.put(entry.key(), entry);
            }
        } catch (ImportLockFile.InvalidLockException invalid) {
            lockDigest = invalid.sha256();
            errors.add("could not read/parse lock: " + (invalid.getMessage() == null
                    ? invalid.getClass().getSimpleName() : invalid.getMessage()));
        } catch (Exception e) {
            errors.add("could not read/parse lock: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
        }
        Map<String, LockEntry> actual = current.entriesByKey();
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, LockEntry> entry : actual.entrySet()) {
            LockEntry locked = expected.get(entry.getKey());
            if (locked == null) missing.add(entry.getKey());
            else if (!locked.sha256.equals(entry.getValue().sha256)
                    || !locked.document.equals(entry.getValue().document)
                    || locked.direct != entry.getValue().direct) mismatched.add(entry.getKey());
        }
        expected.keySet().stream().filter(key -> !actual.containsKey(key)).forEach(extra::add);
        boolean valid = errors.isEmpty() && missing.isEmpty() && extra.isEmpty() && mismatched.isEmpty();
        return Tools.json().put("valid", valid).put("path", current.path.toString())
                .putIfNotNull("sha256", lockDigest).put("entry_count", expected.size())
                .put("errors", errors).put("missing_entries", missing).put("extra_entries", extra)
                .put("mismatched_entries", mismatched).map();
    }

    static CallToolResult validateCatalog(ToolContext ctx, Map<String, Object> arguments) {
        return validateCatalog(ctx, arguments, DirectAccessPolicy.resolve(ctx, null));
    }

    private static CallToolResult validateCatalog(ToolContext ctx, Map<String, Object> arguments,
            DirectAccessPolicy.Rules rules) {
        String configured = Tools.optString(arguments, "path");
        // Authorize the caller's path from the RAW request string: pre-absolutizing here would anchor
        // a relative path at the process working directory, while the rules root it at the policy's
        // canonical project_root. Only the derived beside-active default is absolute by construction.
        Path explicit = configured == null ? null : rules.readPath(configured);
        CatalogContext context = ctx.access().compute(mm -> {
            java.io.File active = SidecarPaths.toFile(mm.getOWLOntologyManager()
                    .getOntologyDocumentIRI(mm.getActiveOntology()));
            Path path = explicit != null ? explicit
                    : active == null || active.getParentFile() == null ? null
                            // Derived beside-active default, not caller-selected: authorize as an
                            // implicit path so policy_required mode does not misname it caller-selected.
                            : rules.implicitPath(active.getParentFile().toPath()
                                    .resolve("catalog-v001.xml"), false);
            Set<String> imports = new LinkedHashSet<>();
            mm.getActiveOntology().getImportsDeclarations().forEach(d -> imports.add(d.getIRI().toString()));
            return new CatalogContext(path, imports);
        });
        if (context.path == null) return Tools.error("Active ontology has no local catalog folder; pass path.");
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<String> nextCatalogs = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        try {
            OfflineCatalog.Document catalog = OfflineCatalog.read(context.path);
            errors.addAll(catalog.errors());
            for (OfflineCatalog.Entry entry : catalog.entries()) {
                String name = entry.name();
                String uri = entry.reference();
                names.add(name);
                String status;
                java.net.URI resolved = entry.resolved();
                if (resolved == null) {
                    status = "invalid_uri";
                } else {
                    try {
                        if (!"file".equalsIgnoreCase(resolved.getScheme())) {
                            status = "remote_forbidden";
                            errors.add("remote catalog document is not allowed in no-network validation: "
                                    + resolved);
                        } else {
                            Path local;
                            try {
                                local = rules.readPath(Path.of(resolved).toString());
                            } catch (ToolArgException refusal) {
                                // Report a refused target per entry without probing or leaking it.
                                local = null;
                            }
                            if (local == null) {
                                status = "policy_refused";
                                errors.add("catalog document is not readable under the project policy: "
                                        + uri);
                            } else {
                                status = Files.isRegularFile(local) ? "local_ok" : "local_missing";
                                if ("local_missing".equals(status)) {
                                    errors.add("missing catalog document: " + local);
                                }
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        status = "invalid_uri";
                        errors.add("catalog uri entry does not resolve to a valid URI: " + uri);
                    }
                }
                entries.add(Map.of("name", name, "uri", uri, "status", status));
            }
            catalog.nextCatalogs().forEach(next -> nextCatalogs.add(next.toString()));
        } catch (Exception e) {
            errors.add("could not parse catalog: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
        }
        List<String> unmapped = new ArrayList<>();
        List<String> delegated = new ArrayList<>();
        if (Tools.optBool(arguments, "compare_imports", false)) {
            for (String iri : context.imports) {
                if (names.contains(iri)) continue;
                // With a nextCatalog present the import may be mapped further down the chain. This
                // no-network validator never follows the chain, so it cannot assert a missing mapping —
                // report the import as delegated (unverified) instead of failing the catalog.
                (nextCatalogs.isEmpty() ? unmapped : delegated).add(iri);
            }
        }
        boolean valid = errors.isEmpty() && unmapped.isEmpty();
        return Tools.json().put("valid", valid).put("catalog", context.path.toString())
                .put("entries", entries).put("errors", errors).put("next_catalogs", nextCatalogs)
                .put("unmapped_imports", unmapped).put("delegated_imports", delegated).result();
    }

    /**
     * Gather import coordinates on the Protégé model thread. Pure in-memory metadata only — no filesystem
     * access (no {@code isFile} stat, no reads, no hashing): on a large closure that disk I/O would freeze
     * the EDT. Every file operation happens off the model thread in {@link #capture(Coordinates)}.
     */
    private static Coordinates gather(org.protege.editor.owl.model.OWLModelManager mm, Path lockPath) {
        return gather(ImportTools.analyze(mm.getActiveOntology()), lockPath);
    }

    private static Coordinates gather(ImportTools.ImportReport report, Path lockPath) {
        List<String> errors = new ArrayList<>();
        if (!report.missingImports.isEmpty()) errors.add("one or more imports are unresolved");
        if (!report.conflicts.isEmpty()) errors.add("loaded import identity/version/document conflicts exist");
        List<RawImport> raw = new ArrayList<>();
        for (Map<String, Object> row : report.resolvedImports) {
            raw.add(new RawImport(string(row.get("target_document_iri")),
                    string(row.get("target_ontology_iri")), string(row.get("target_version_iri")),
                    Boolean.TRUE.equals(row.get("direct"))));
        }
        return new Coordinates(lockPath.toAbsolutePath().normalize(),
                lockPath.toAbsolutePath().normalize().getParent(), raw, errors);
    }

    /**
     * Resolve, validate, read and hash each import document OFF the model thread, then assemble the
     * deterministic capture. All filesystem access (stat, containment, read) lives here.
     */
    private static LockCapture capture(Coordinates coordinates) {
        List<String> errors = new ArrayList<>(coordinates.errors);
        Path lockDir = coordinates.lockDir;
        Map<String, LockEntry> entries = new LinkedHashMap<>();
        for (RawImport r : coordinates.raw) {
            java.io.File file;
            try {
                file = SidecarPaths.toFile(org.semanticweb.owlapi.model.IRI.create(r.documentIri));
            } catch (RuntimeException e) {
                file = null;
            }
            if (file == null || !file.isFile()) {
                errors.add("import is not backed by a local file: " + r.documentIri);
                continue;
            }
            // verify() only accepts an absolute, non-blank ontology IRI as an entry key; an anonymous
            // resolved import would collapse to an empty key that collides and that verify rejects, so
            // the lock we write would never re-verify. Fail closed here instead of emitting that lock.
            if (r.ontologyIri.isBlank() || !isAbsoluteIri(r.ontologyIri)) {
                errors.add("import has no absolute ontology IRI to lock: " + r.documentIri);
                continue;
            }
            // Containment AND the relative path use ONE canonical namespace so a symlinked checkout is
            // handled consistently by both write and verify. When the lock directory exists, canonicalize
            // both it and the import document (real paths); a symlink that lexically sits under the dir
            // but physically resolves outside is then correctly rejected, and a symlink-alias document in
            // the same real directory is correctly accepted. If the dir does not exist yet (first write),
            // no symlink is possible, so fall back to the lexical absolute path.
            Path base;
            Path resolved;
            try {
                if (Files.isDirectory(lockDir)) {
                    base = lockDir.toRealPath();
                    resolved = file.toPath().toRealPath();
                } else {
                    base = lockDir == null ? null : lockDir;
                    resolved = file.toPath().toAbsolutePath().normalize();
                }
            } catch (IOException e) {
                errors.add("could not resolve import document real path: " + file);
                continue;
            }
            // verify() rejects any lock document that is absolute or escapes the lock directory. Only
            // emit an entry we could read back: an import stored under the lock directory. Anything else
            // (shared library beside the project, sibling folder, cross-root) fails closed here.
            if (base == null || !resolved.startsWith(base)) {
                errors.add("import document is outside the lock directory and cannot be locked "
                        + "relatively: " + resolved);
                continue;
            }
            String relative = base.relativize(resolved).toString()
                    .replace(java.io.File.separatorChar, '/');
            Path absolute = resolved;
            try {
                LockEntry entry = new LockEntry(r.ontologyIri, r.versionIri, relative,
                        RevisionTools.sha256File(absolute).substring("sha256:".length()),
                        r.direct, absolute);
                LockEntry prior = entries.get(entry.key());
                entries.put(entry.key(), prior == null ? entry
                        : new LockEntry(entry.ontologyIri, entry.versionIri, entry.document, entry.sha256,
                                prior.direct || entry.direct, entry.absolute));
            } catch (IOException e) {
                errors.add("could not hash import document " + absolute + ": " + e.getMessage());
            }
        }
        List<LockEntry> ordered = entries.values().stream().sorted(Comparator.comparing(LockEntry::key)).toList();
        return new LockCapture(coordinates.path, ordered, errors);
    }

    /**
     * The DEFAULT lock path — an explicit {@code path} never reaches here; {@link LockAccess}
     * authorizes it against the direct-access rules first (rooting a relative path at the canonical
     * project_root, not the process working directory). Without one the policy decides, and it must
     * decide cleanly: a policy reference that failed to resolve, a loaded-but-invalid policy, or a
     * declared imports.lockfile that did not resolve to a file all refuse — silently retargeting the
     * default beside the active document would make write and verify operate on a different file than
     * the one the policy declares (verify could even report valid=true for that other file). Only a
     * policy that declares no lockfile at all, or no policy, uses the beside-active-document default.
     */
    private static Path resolveLockPath(org.protege.editor.owl.model.OWLModelManager mm,
            RevisionTools.PolicyState state) {
        if (state.error() != null) {
            throw new ToolArgException("Cannot resolve the default lock path: " + state.error()
                    + " Fix the policy reference or pass an explicit path.");
        }
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy = state.policy();
        List<Path> policyPaths = policy.assets().getOrDefault("import_lock", List.of());
        String declared = declaredLockfile(policy);
        // The actionable bootstrap message applies when the lockfile itself is what failed to resolve
        // (or the policy is otherwise fine); a policy broken at the YAML/schema level gets the generic
        // invalid refusal below instead — its raw lockfile value never went through asset resolution.
        boolean lockfileIssue = policy.issues().stream().anyMatch(issue ->
                "error".equals(issue.severity()) && "imports.lockfile".equals(issue.path()));
        if (declared != null && policyPaths.size() != 1 && (lockfileIssue || policy.valid())) {
            throw new ToolArgException("The project policy declares imports.lockfile '" + declared
                    + "' but it did not resolve to a file (for example it does not exist yet). Pass "
                    + "path pointing at the declared location, or create an empty placeholder file "
                    + "there; the lock is never read or written anywhere else.");
        }
        if (policy.loaded() && !policy.valid()) {
            throw new ToolArgException("Cannot resolve the default lock path: the project policy is "
                    + "invalid (" + policyErrorCodes(policy) + " — see validate_project_policy). Fix "
                    + "the policy or pass an explicit path.");
        }
        if (policyPaths.size() == 1) return policyPaths.get(0);
        java.io.File active = SidecarPaths.toFile(mm.getOWLOntologyManager()
                .getOntologyDocumentIRI(mm.getActiveOntology()));
        if (active == null || active.getParentFile() == null) {
            throw new ToolArgException("Active ontology has no local folder; pass lock path.");
        }
        return active.getParentFile().toPath().resolve("imports.lock.json");
    }

    /** The policy-declared {@code imports.lockfile} string, or null when the policy declares none. */
    private static String declaredLockfile(io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy) {
        Object imports = policy.effective().get("imports");
        Object lockfile = imports instanceof Map<?, ?> ? ((Map<?, ?>) imports).get("lockfile") : null;
        return lockfile instanceof String && !((String) lockfile).isBlank() ? (String) lockfile : null;
    }

    /** Deterministic comma-joined error-issue codes naming why a policy refused defaulting. */
    private static String policyErrorCodes(io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy) {
        return policy.issues().stream().filter(issue -> "error".equals(issue.severity()))
                .map(issue -> issue.code()).distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Map<String, Object> lockJson(LockCapture capture) {
        List<Map<String, Object>> imports = new ArrayList<>();
        for (LockEntry entry : capture.entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ontology_iri", blankToNull(entry.ontologyIri));
            row.put("version_iri", blankToNull(entry.versionIri));
            row.put("document", entry.document);
            row.put("sha256", entry.sha256);
            row.put("direct", entry.direct);
            imports.add(row);
        }
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("version", 1);
        lock.put("imports", imports);
        return lock;
    }

    static LockEntry parseEntry(Object value, Path base) throws IOException {
        return lockEntry(ImportLockFile.parseEntry(value, base));
    }

    private static LockEntry lockEntry(ImportLockFile.Entry entry) {
        return new LockEntry(entry.ontologyIri(), entry.versionIri(), entry.document(),
                entry.sha256(), entry.direct(), entry.absolute());
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temp = Files.createTempFile(target.getParent(), ".imports.lock.", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new IOException("atomic replacement is not supported", e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static boolean isAbsoluteIri(String value) {
        try {
            return java.net.URI.create(value).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    record LockEntry(String ontologyIri, String versionIri, String document, String sha256,
            boolean direct, Path absolute) {
        String key() { return versionIri == null || versionIri.isBlank()
                ? ontologyIri : ontologyIri + " @ " + versionIri; }
    }

    /** One resolved import as pure metadata from the model thread — no filesystem access yet. */
    private record RawImport(String documentIri, String ontologyIri, String versionIri, boolean direct) { }

    /** Initial model-thread coordinates pinned across the off-thread file-hashing phase. */
    private record WriteCoordinates(Coordinates coordinates, ModelRevision revision,
            String policyPath, String policyDigest, String reportedPath) { }

    /** Coordinates and revision pinned around verify's off-thread file hashing. */
    private record VerifyCoordinates(Coordinates coordinates, ModelRevision revision,
            String policyDigest) { }

    /** Model-thread capture: the lock path, its directory, raw import rows, and fail-closed errors. */
    private record Coordinates(Path path, Path lockDir, List<RawImport> raw, List<String> errors) { }

    private record LockCapture(Path path, List<LockEntry> entries, List<String> errors) {
        Map<String, LockEntry> entriesByKey() {
            Map<String, LockEntry> result = new LinkedHashMap<>();
            entries.forEach(entry -> result.put(entry.key(), entry));
            return result;
        }
    }
    private record CatalogContext(Path path, Set<String> imports) { }
}
