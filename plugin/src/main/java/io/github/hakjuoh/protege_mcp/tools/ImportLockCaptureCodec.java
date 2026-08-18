package io.github.hakjuoh.protege_mcp.tools;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.core.workspace.ImportLockFile;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures deterministic import closures and encodes/installs import lock files. */
final class ImportLockCaptureCodec {

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ImportLockCaptureCodec() {}

    static CallToolResult write(ToolContext ctx, Map<String, Object> arguments) {
        return write(ctx, arguments, () -> {}, null);
    }

    /** Test seam: run one action after off-thread hashing but before the final install guard. */
    static CallToolResult write(
            ToolContext ctx, Map<String, Object> arguments, Runnable afterCapture) {
        return write(ctx, arguments, afterCapture, null);
    }

    static CallToolResult write(
            ToolContext ctx,
            Map<String, Object> arguments,
            Runnable afterCapture,
            McpSyncServerExchange exchange) {
        ImportLockAccess access = ImportLockAccess.open(ctx, exchange, arguments, true);
        CallToolResult denied =
                WriteTools.checkWriteAllowed(ctx, "write deterministic import lock");
        if (denied != null) return denied;
        String configuredPolicy = Tools.optString(access.arguments, "policy_path");
        String configuredPath = Tools.optString(access.arguments, "path");
        WriteCoordinates coordinates =
                ctx.access()
                        .compute(
                                mm -> {
                                    RevisionTools.PolicyState policy =
                                            resolvePolicyOnModelThread(mm, configuredPolicy);
                                    final Path target;
                                    final String reported;
                                    if (access.explicitTarget != null) {
                                        target = access.explicitTarget;
                                        reported = access.reportedPath;
                                    } else {
                                        // The beside-active lockfile is DERIVED from the open
                                        // document, not caller-selected, so it stays authorized
                                        // under the no-policy compatibility opt-out
                                        // (policy_required mode), matching save_ontology and
                                        // write_catalog.
                                        Path requestedTarget = resolveLockPath(mm, policy);
                                        target = access.rules.implicitPath(requestedTarget, true);
                                        reported = requestedTarget.toString();
                                    }
                                    Coordinates gathered = gather(mm, target);
                                    // The replaced import lock must not participate in its own
                                    // pre-write revision token. The listener-backed session
                                    // coordinate plus active semantic/document coordinates cover
                                    // active/import edits, ontology switches, and prefix/document
                                    // changes during hashing.
                                    ModelRevision revision =
                                            ctx.revisions().current(mm, null, null).revision();
                                    return new WriteCoordinates(
                                            gathered,
                                            revision,
                                            policyPath(policy.policy()),
                                            policy.policy().digest(),
                                            policySourceDigest(policy.policy()),
                                            reported);
                                },
                                ImportLockModel.WRITE_HOP_TIMEOUT_MS);
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
                return ctx.access()
                        .compute(
                                mm ->
                                        installCapturedLock(
                                                ctx,
                                                mm,
                                                coordinates,
                                                capture,
                                                lock,
                                                bytes,
                                                configuredPolicy,
                                                configuredPath),
                                ImportLockModel.WRITE_HOP_TIMEOUT_MS);
            } catch (McpAccessException e) {
                // The bounded EDT wait can expire (or be interrupted) after the body starts, and
                // OntologyAccess cannot cancel a running body. The lock may still be installed even
                // though this call reports a failure, so never claim nothing happened.
                throw new McpAccessException(
                        e.getMessage()
                                + " The install body may still complete on the Protégé UI thread:"
                                + " call verify_import_lock (or read the lock file) to see whether"
                                + " this capture landed before retrying.",
                        e);
            }
        } finally {
            ctx.writeLock().unlock();
        }
    }

    private static CallToolResult installCapturedLock(
            ToolContext ctx,
            org.protege.editor.owl.model.OWLModelManager mm,
            WriteCoordinates coordinates,
            LockCapture capture,
            Map<String, Object> lock,
            byte[] bytes,
            String configuredPolicy,
            String configuredPath) {
        if (ctx.controller().isReadOnly()) {
            return WriteTools.readOnlyDenied();
        }
        ModelRevision current = ctx.revisions().current(mm, null, null).revision();
        if (!coordinates.revision.equals(current)) {
            return Tools.json()
                    .put("written", false)
                    .put("valid", false)
                    .put("error_code", "revision_conflict")
                    .put("base_revision", RevisionTools.revisionJson(coordinates.revision))
                    .put("current_revision", RevisionTools.revisionJson(current))
                    .put("path", capture.path.toString())
                    .result();
        }

        // With a policy-selected target, repeat discovery at install time. A newly created nearer
        // project.yaml, an edited policy, or a different resolved lockfile must not let an old
        // capture overwrite a path that is no longer effective. Explicit paths intentionally remain
        // independent of policy validity, matching resolveLockPath's bootstrap behavior.
        if (configuredPath == null) {
            RevisionTools.PolicyState currentPolicy =
                    resolvePolicyOnModelThread(mm, configuredPolicy);
            final Path currentTarget;
            try {
                currentTarget = resolveLockPath(mm, currentPolicy);
            } catch (RuntimeException changedPolicy) {
                return policyConflict(capture.path, changedPolicy.getMessage());
            }
            if (!capture.path
                            .toAbsolutePath()
                            .normalize()
                            .equals(
                                    DirectAccessPolicy.canonicalCandidate(
                                            currentTarget.toAbsolutePath().normalize()))
                    || !java.util.Objects.equals(
                            coordinates.policyPath, policyPath(currentPolicy.policy()))
                    || !java.util.Objects.equals(
                            coordinates.policyDigest, currentPolicy.policy().digest())
                    || !java.util.Objects.equals(
                            coordinates.policySourceDigest,
                            policySourceDigest(currentPolicy.policy()))) {
                return policyConflict(
                        capture.path,
                        "The effective project policy or import-lock target changed during"
                                + " capture.");
            }
        }

        try {
            atomicWrite(capture.path, bytes);
        } catch (IOException e) {
            throw new ToolArgException("Could not write import lock: " + e.getMessage());
        }
        return Tools.json()
                .put("written", true)
                .put("path", coordinates.reportedPath)
                .put("entry_count", capture.entries.size())
                .put("sha256", RevisionTools.sha256(bytes))
                .put("lock", lock)
                .result();
    }

    private static CallToolResult policyConflict(Path path, String message) {
        return Tools.json()
                .put("written", false)
                .put("valid", false)
                .put("error_code", "policy_conflict")
                .put("path", path.toString())
                .putIfNotNull("message", message)
                .result();
    }

    private static String policyPath(ProjectPolicy policy) {
        return policy.path() == null ? null : policy.path().toString();
    }

    /**
     * In-model-thread policy reload used only by the final install guard (nested EDT hops are
     * invalid).
     */
    static RevisionTools.PolicyState resolvePolicyOnModelThread(
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
        ProjectPolicy policy =
                error == null
                        ? io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                                explicit,
                                live.documentPath(),
                                live.activeOntologyIri(),
                                live.installedReasoners())
                        : ProjectPolicy.notFound();
        return new RevisionTools.PolicyState(policy, live, error);
    }

    static Coordinates gather(org.protege.editor.owl.model.OWLModelManager mm, Path lockPath) {
        return gather(ImportTools.analyze(mm.getActiveOntology()), lockPath);
    }

    static Coordinates gather(ImportTools.ImportReport report, Path lockPath) {
        List<String> errors = new ArrayList<>();
        if (!report.missingImports.isEmpty()) errors.add("one or more imports are unresolved");
        if (!report.conflicts.isEmpty())
            errors.add("loaded import identity/version/document conflicts exist");
        List<RawImport> raw = new ArrayList<>();
        for (Map<String, Object> row : report.resolvedImports) {
            raw.add(
                    new RawImport(
                            string(row.get("target_document_iri")),
                            string(row.get("target_ontology_iri")),
                            string(row.get("target_version_iri")),
                            Boolean.TRUE.equals(row.get("direct"))));
        }
        return new Coordinates(
                lockPath.toAbsolutePath().normalize(),
                lockPath.toAbsolutePath().normalize().getParent(),
                raw,
                errors);
    }

    /**
     * Resolve, validate, read and hash each import document OFF the model thread, then assemble the
     * deterministic capture. All filesystem access (stat, containment, read) lives here.
     */
    static LockCapture capture(Coordinates coordinates) {
        List<String> errors = new ArrayList<>(coordinates.errors);
        Path lockDir = coordinates.lockDir;
        Map<String, ImportLockModel.LockEntry> entries = new LinkedHashMap<>();
        for (RawImport r : coordinates.raw) {
            java.io.File file;
            try {
                file =
                        SidecarPaths.toFile(
                                org.semanticweb.owlapi.model.IRI.create(r.documentIri()));
            } catch (RuntimeException e) {
                file = null;
            }
            if (file == null || !file.isFile()) {
                errors.add("import is not backed by a local file: " + r.documentIri());
                continue;
            }
            // verify() accepts only an absolute, non-blank ontology IRI as an entry key. An
            // anonymous resolved import would collapse to a colliding empty key, so its lock could
            // never re-verify. Fail closed here instead of emitting it.
            if (r.ontologyIri().isBlank() || !isAbsoluteIri(r.ontologyIri())) {
                errors.add("import has no absolute ontology IRI to lock: " + r.documentIri());
                continue;
            }
            // Containment AND the relative path use ONE canonical namespace so write and verify
            // handle a symlinked checkout consistently. When the lock directory exists, canonicalize
            // it and the import document. This rejects a symlink that sits lexically under the
            // directory but resolves outside, while accepting an alias in the same real directory.
            // Before the directory exists, no symlink is possible, so use the lexical absolute path.
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
            // verify() rejects a lock document that is absolute or escapes the lock directory. Emit
            // only an entry we can read back: an import stored under the lock directory. A shared
            // library, sibling folder, or cross-root path fails closed here.
            if (base == null || !resolved.startsWith(base)) {
                errors.add(
                        "import document is outside the lock directory and cannot be locked "
                                + "relatively: "
                                + resolved);
                continue;
            }
            String relative =
                    base.relativize(resolved).toString().replace(java.io.File.separatorChar, '/');
            Path absolute = resolved;
            try {
                ImportLockModel.LockEntry entry =
                        new ImportLockModel.LockEntry(
                                r.ontologyIri(),
                                r.versionIri(),
                                relative,
                                RevisionTools.sha256File(absolute).substring("sha256:".length()),
                                r.direct(),
                                absolute);
                ImportLockModel.LockEntry prior = entries.get(entry.key());
                entries.put(
                        entry.key(),
                        prior == null
                                ? entry
                                : new ImportLockModel.LockEntry(
                                        entry.ontologyIri(),
                                        entry.versionIri(),
                                        entry.document(),
                                        entry.sha256(),
                                        prior.direct() || entry.direct(),
                                        entry.absolute()));
            } catch (IOException e) {
                errors.add("could not hash import document " + absolute + ": " + e.getMessage());
            }
        }
        List<ImportLockModel.LockEntry> ordered =
                entries.values().stream()
                        .sorted(Comparator.comparing(ImportLockModel.LockEntry::key))
                        .toList();
        return new LockCapture(coordinates.path, ordered, errors);
    }

    /**
     * The DEFAULT lock path — an explicit {@code path} never reaches here; {@link ImportLockAccess}
     * authorizes it against the direct-access rules first (rooting a relative path at the canonical
     * project_root, not the process working directory). Without one the policy decides, and it must
     * decide cleanly: a policy reference that failed to resolve, a loaded-but-invalid policy, or a
     * declared imports.lockfile that did not resolve to a file all refuse — silently retargeting
     * the default beside the active document would make write and verify operate on a different
     * file than the one the policy declares (verify could even report valid=true for that other
     * file). Only a policy that declares no lockfile at all, or no policy, uses the
     * beside-active-document default.
     */
    static Path resolveLockPath(
            org.protege.editor.owl.model.OWLModelManager mm, RevisionTools.PolicyState state) {
        if (state.error() != null) {
            throw new ToolArgException(
                    "Cannot resolve the default lock path: "
                            + state.error()
                            + " Fix the policy reference or pass an explicit path.");
        }
        io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy = state.policy();
        List<Path> policyPaths = policy.assets().getOrDefault("import_lock", List.of());
        String declared = declaredLockfile(policy);
        // The actionable bootstrap message applies when the lockfile itself failed to resolve (or
        // the policy is otherwise fine). A policy broken at the YAML/schema level gets the generic
        // invalid refusal below because its raw lockfile value never reached asset resolution.
        boolean lockfileIssue =
                policy.issues().stream()
                        .anyMatch(
                                issue ->
                                        "error".equals(issue.severity())
                                                && "imports.lockfile".equals(issue.path()));
        if (declared != null && policyPaths.size() != 1 && (lockfileIssue || policy.valid())) {
            throw new ToolArgException(
                    "The project policy declares imports.lockfile '"
                            + declared
                            + "' but it did not resolve to a file (for example it does not exist"
                            + " yet). Pass path pointing at the declared location, or create an"
                            + " empty placeholder file there; the lock is never read or written"
                            + " anywhere else.");
        }
        if (policy.loaded() && !policy.valid()) {
            throw new ToolArgException(
                    "Cannot resolve the default lock path: the project policy is "
                            + "invalid ("
                            + policyErrorCodes(policy)
                            + " — see validate_project_policy). Fix "
                            + "the policy or pass an explicit path.");
        }
        if (policyPaths.size() == 1) return policyPaths.get(0);
        java.io.File active =
                SidecarPaths.toFile(
                        mm.getOWLOntologyManager().getOntologyDocumentIRI(mm.getActiveOntology()));
        if (active == null || active.getParentFile() == null) {
            throw new ToolArgException("Active ontology has no local folder; pass lock path.");
        }
        return active.getParentFile().toPath().resolve("imports.lock.json");
    }

    /**
     * The policy-declared {@code imports.lockfile} string, or null when the policy declares none.
     */
    static String declaredLockfile(io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy) {
        Object imports = policy.effective().get("imports");
        Object lockfile =
                imports instanceof Map<?, ?> ? ((Map<?, ?>) imports).get("lockfile") : null;
        return lockfile instanceof String && !((String) lockfile).isBlank()
                ? (String) lockfile
                : null;
    }

    /** Deterministic comma-joined error-issue codes naming why a policy refused defaulting. */
    static String policyErrorCodes(io.github.hakjuoh.protege_mcp.policy.ProjectPolicy policy) {
        return policy.issues().stream()
                .filter(issue -> "error".equals(issue.severity()))
                .map(issue -> issue.code())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Map<String, Object> lockJson(LockCapture capture) {
        List<Map<String, Object>> imports = new ArrayList<>();
        for (ImportLockModel.LockEntry entry : capture.entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ontology_iri", blankToNull(entry.ontologyIri()));
            row.put("version_iri", blankToNull(entry.versionIri()));
            row.put("document", entry.document());
            row.put("sha256", entry.sha256());
            row.put("direct", entry.direct());
            imports.add(row);
        }
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("version", 1);
        lock.put("imports", imports);
        return lock;
    }

    static ImportLockModel.LockEntry parseEntry(Object value, Path base) throws IOException {
        return lockEntry(ImportLockFile.parseEntry(value, base));
    }

    static ImportLockModel.LockEntry lockEntry(ImportLockFile.Entry entry) {
        return new ImportLockModel.LockEntry(
                entry.ontologyIri(),
                entry.versionIri(),
                entry.document(),
                entry.sha256(),
                entry.direct(),
                entry.absolute());
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temp = Files.createTempFile(target.getParent(), ".imports.lock.", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                throw new IOException("atomic replacement is not supported", e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isAbsoluteIri(String value) {
        try {
            return java.net.URI.create(value).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Hash the policy source bytes so semantically neutral in-place edits still invalidate a
     * capture.
     */
    static String policySourceDigest(ProjectPolicy policy) {
        if (policy.path() == null) {
            return null;
        }
        try {
            return RevisionTools.sha256File(policy.path());
        } catch (IOException e) {
            return "unreadable:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    private record RawImport(
            String documentIri, String ontologyIri, String versionIri, boolean direct) {}

    /** Initial model-thread coordinates pinned across the off-thread file-hashing phase. */
    private record WriteCoordinates(
            Coordinates coordinates,
            ModelRevision revision,
            String policyPath,
            String policyDigest,
            String policySourceDigest,
            String reportedPath) {}

    /** Coordinates and revision pinned around verify's off-thread file hashing. */
    record VerifyCoordinates(
            Coordinates coordinates,
            ModelRevision revision,
            String policyDigest,
            String policySourceDigest) {}

    /**
     * Model-thread capture: the lock path, its directory, raw import rows, and fail-closed errors.
     */
    record Coordinates(Path path, Path lockDir, List<RawImport> raw, List<String> errors) {}

    record LockCapture(Path path, List<ImportLockModel.LockEntry> entries, List<String> errors) {
        Map<String, ImportLockModel.LockEntry> entriesByKey() {
            Map<String, ImportLockModel.LockEntry> result = new LinkedHashMap<>();
            entries.forEach(entry -> result.put(entry.key(), entry));
            return result;
        }
    }
}
