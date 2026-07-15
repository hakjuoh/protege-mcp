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
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
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
                "Write deterministic imports.lock.json for every currently resolved local import in "
                        + "the active ontology's closure. Each sorted entry records ontology/version IRI, "
                        + "project-relative document, SHA-256 and whether it is direct. Missing or remote "
                        + "imports fail closed. The file is replaced atomically and is not undoable.",
                Tools.schema()
                        .str("path", "Explicit lock path. Default: the policy-declared imports.lockfile; "
                                + "a policy that fails to load/validate, or whose declared lockfile does "
                                + "not resolve, refuses instead of falling back. Only a policy without a "
                                + "lockfile (or no policy) defaults beside the active document.")
                        .str("policy_path", "Optional explicit project policy. A path that fails to load "
                                + "refuses lock-path defaulting rather than falling back.")
                        .build(),
                (ex, req) -> Tools.guard(() -> write(ctx, Tools.args(req))));

        tools.tool("verify_import_lock",
                "Verify the effective imports.lock.json without network access. Recomputes every local "
                        + "artifact SHA-256 and compares deterministic loaded import coordinates, reporting "
                        + "missing/extra entries, changed documents and checksum mismatches. Invalid JSON, "
                        + "duplicate entries, unresolved imports or non-local documents return valid=false.",
                Tools.schema()
                        .str("path", "Explicit lock path. Default: the policy-declared imports.lockfile; "
                                + "a policy that fails to load/validate, or whose declared lockfile does "
                                + "not resolve, refuses instead of verifying a fallback file. Only a policy "
                                + "without a lockfile (or no policy) defaults beside the active document.")
                        .str("policy_path", "Optional explicit project policy. A path that fails to load "
                                + "refuses lock-path defaulting rather than falling back.")
                        .build(),
                (ex, req) -> Tools.guard(() -> verify(ctx, Tools.args(req))));

        tools.tool("validate_catalog",
                "Parse an OASIS catalog-v001.xml with hardened no-network XML settings and report malformed, "
                        + "duplicate, remote, or missing local URI mappings. uri targets are resolved "
                        + "honoring xml:base, matching Protege's own catalog loading; nextCatalog "
                        + "delegations are reported in next_catalogs but never followed. Optionally compare "
                        + "catalog names with the active import declarations — an import only reachable "
                        + "through a delegation is reported as delegated_imports (unverified), not as a "
                        + "missing mapping. Read-only.",
                Tools.schema()
                        .str("path", "Catalog file (default catalog-v001.xml beside active ontology).")
                        .bool("compare_imports", "Require every active import declaration to have a mapping.")
                        .build(),
                (ex, req) -> Tools.guard(() -> validateCatalog(ctx, Tools.args(req))));
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
        return write(ctx, arguments, () -> { });
    }

    /** Test seam: run one action after off-thread hashing but before the final install guard. */
    static CallToolResult write(ToolContext ctx, Map<String, Object> arguments,
            Runnable afterCapture) {
        CallToolResult denied = WriteTools.checkWriteAllowed(ctx, "write deterministic import lock");
        if (denied != null) return denied;
        String configuredPolicy = Tools.optString(arguments, "policy_path");
        String configuredPath = Tools.optString(arguments, "path");
        RevisionTools.PolicyState policy = RevisionTools.resolvePolicy(ctx,
                configuredPolicy);
        WriteCoordinates coordinates = ctx.access().compute(mm -> {
            Path target = resolveLockPath(mm, policy, configuredPath);
            Coordinates gathered = gather(mm, target);
            // The import lock being replaced must not participate in its own pre-write revision token.
            // The listener-backed session coordinate plus the active semantic/document coordinates cover
            // active/import edits, ontology switches, and prefix/document changes during hashing.
            ModelRevision revision = ctx.revisions().current(mm, null, null).revision();
            return new WriteCoordinates(gathered, revision, policyPath(policy.policy()),
                    policy.policy().digest());
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
                currentTarget = resolveLockPath(mm, currentPolicy, null);
            } catch (RuntimeException changedPolicy) {
                return policyConflict(capture.path, changedPolicy.getMessage());
            }
            if (!capture.path.toAbsolutePath().normalize()
                    .equals(currentTarget.toAbsolutePath().normalize())
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
        return Tools.json().put("written", true).put("path", capture.path.toString())
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
        return verify(ctx, arguments, () -> { });
    }

    /** Test seam for a concurrent model/import change after off-thread hashing. */
    static CallToolResult verify(ToolContext ctx, Map<String, Object> arguments, Runnable afterCapture) {
        RevisionTools.PolicyState policy = RevisionTools.resolvePolicy(ctx,
                Tools.optString(arguments, "policy_path"));
        VerifyCoordinates initial = ctx.access().compute(mm -> {
            Coordinates coordinates = gather(mm,
                    resolveLockPath(mm, policy, Tools.optString(arguments, "path")));
            ModelRevision revision = ctx.revisions().current(mm, null, policy.policy().digest()).revision();
            return new VerifyCoordinates(coordinates, revision);
        }, WRITE_HOP_TIMEOUT_MS);
        LockCapture current = capture(initial.coordinates); // hashes off the model thread
        afterCapture.run();
        VerifyCoordinates finalState = ctx.access().compute(mm -> new VerifyCoordinates(
                gather(mm, initial.coordinates.path),
                ctx.revisions().current(mm, null, policy.policy().digest()).revision()),
                WRITE_HOP_TIMEOUT_MS);
        List<String> errors = new ArrayList<>(current.errors);
        if (!initial.revision.equals(finalState.revision)
                || !initial.coordinates.equals(finalState.coordinates)) {
            errors.add("ontology/import state changed while the lock was being verified; retry against "
                    + "a stable workspace revision");
        }
        Map<String, LockEntry> expected = new LinkedHashMap<>();
        String lockDigest = null;
        try {
            if (!Files.isRegularFile(current.path)
                    || Files.size(current.path) > RevisionTools.MAX_BUFFERED_BYTES) {
                throw new IOException("lock file is missing or exceeds " + RevisionTools.MAX_BUFFERED_BYTES
                        + " bytes");
            }
            byte[] bytes = Files.readAllBytes(current.path);
            lockDigest = RevisionTools.sha256(bytes);
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(bytes, LinkedHashMap.class);
            Set<String> unknownFields = new LinkedHashSet<>(parsed.keySet());
            unknownFields.removeAll(Set.of("version", "imports"));
            if (!unknownFields.isEmpty()) {
                errors.add("unknown top-level lock field(s): " + unknownFields);
            }
            if (!Integer.valueOf(1).equals(parsed.get("version"))) {
                errors.add("lock version must be integer 1");
            }
            Object entries = parsed.get("imports");
            if (!(entries instanceof List<?>)) {
                errors.add("imports must be an array");
            } else {
                for (Object value : (List<?>) entries) {
                    LockEntry entry = parseEntry(value, current.path.getParent());
                    if (expected.putIfAbsent(entry.key(), entry) != null) {
                        errors.add("duplicate lock entry: " + entry.key());
                    }
                }
            }
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
                .put("mismatched_entries", mismatched).result();
    }

    static CallToolResult validateCatalog(ToolContext ctx, Map<String, Object> arguments) {
        String configured = Tools.optString(arguments, "path");
        CatalogContext context = ctx.access().compute(mm -> {
            java.io.File active = SidecarPaths.toFile(mm.getOWLOntologyManager()
                    .getOntologyDocumentIRI(mm.getActiveOntology()));
            Path path = configured == null
                    ? (active == null || active.getParentFile() == null ? null
                            : active.getParentFile().toPath().resolve("catalog-v001.xml"))
                    : Path.of(configured).toAbsolutePath().normalize();
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
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(context.path.toFile());
            java.net.URI catalogUri = context.path.toUri();
            org.w3c.dom.NodeList nodes = document.getElementsByTagNameNS("*", "uri");
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Element element = (org.w3c.dom.Element) nodes.item(i);
                String name = element.getAttribute("name");
                String uri = element.getAttribute("uri");
                if (name.isBlank() || uri.isBlank()) {
                    errors.add("catalog uri entry " + i + " is missing name/uri");
                    continue;
                }
                if (!names.add(name)) errors.add("duplicate catalog name: " + name);
                String status;
                try {
                    java.net.URI resolved = effectiveXmlBase(element, catalogUri).resolve(uri);
                    if ("file".equalsIgnoreCase(resolved.getScheme())) {
                        Path local = Path.of(resolved);
                        status = Files.isRegularFile(local) ? "local_ok" : "local_missing";
                        if ("local_missing".equals(status)) errors.add("missing catalog document: " + local);
                    } else {
                        status = "remote_forbidden";
                        errors.add("remote catalog document is not allowed in no-network validation: "
                                + resolved);
                    }
                } catch (IllegalArgumentException e) {
                    status = "invalid_uri";
                    errors.add("catalog uri entry does not resolve to a valid URI: " + uri);
                }
                entries.add(Map.of("name", name, "uri", uri, "status", status));
            }
            org.w3c.dom.NodeList nexts = document.getElementsByTagNameNS("*", "nextCatalog");
            for (int i = 0; i < nexts.getLength(); i++) {
                org.w3c.dom.Element element = (org.w3c.dom.Element) nexts.item(i);
                String catalog = element.getAttribute("catalog");
                if (catalog.isBlank()) {
                    errors.add("catalog nextCatalog entry " + i + " is missing catalog");
                    continue;
                }
                try {
                    nextCatalogs.add(effectiveXmlBase(element, catalogUri).resolve(catalog).toString());
                } catch (IllegalArgumentException e) {
                    errors.add("nextCatalog does not resolve to a valid URI: " + catalog);
                }
            }
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
     * The OASIS effective base for a catalog entry: the nearest xml:base on the element or an ancestor
     * (uri, then group, then the catalog root), where each relative xml:base is itself resolved against
     * the next outer base, ending at the catalog file's own URI. Matches org.protege.xmlcatalog's
     * resolution — what Protégé's XMLCatalogIRIMapper uses to actually load imports — so a catalog this
     * validator accepts is one Protégé resolves the same way.
     */
    private static java.net.URI effectiveXmlBase(org.w3c.dom.Element element, java.net.URI catalogUri) {
        List<String> bases = new ArrayList<>();
        for (org.w3c.dom.Node node = element; node instanceof org.w3c.dom.Element;
                node = node.getParentNode()) {
            String base = ((org.w3c.dom.Element) node).getAttributeNS(
                    javax.xml.XMLConstants.XML_NS_URI, "base");
            if (!base.isBlank()) bases.add(base);
        }
        java.net.URI effective = catalogUri;
        for (int i = bases.size() - 1; i >= 0; i--) {
            effective = effective.resolve(bases.get(i));
        }
        return effective;
    }

    /**
     * Gather import coordinates on the Protégé model thread. Pure in-memory metadata only — no filesystem
     * access (no {@code isFile} stat, no reads, no hashing): on a large closure that disk I/O would freeze
     * the EDT. Every file operation happens off the model thread in {@link #capture(Coordinates)}.
     */
    private static Coordinates gather(org.protege.editor.owl.model.OWLModelManager mm, Path lockPath) {
        ImportTools.ImportReport report = ImportTools.analyze(mm.getActiveOntology());
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
     * The effective lock path. An explicit {@code path} always wins. Without one the policy decides,
     * and it must decide cleanly: a policy reference that failed to resolve, a loaded-but-invalid
     * policy, or a declared imports.lockfile that did not resolve to a file all refuse — silently
     * retargeting the default beside the active document would make write and verify operate on a
     * different file than the one the policy declares (verify could even report valid=true for that
     * other file). Only a policy that declares no lockfile at all, or no policy, uses the
     * beside-active-document default.
     */
    private static Path resolveLockPath(org.protege.editor.owl.model.OWLModelManager mm,
            RevisionTools.PolicyState state, String configured) {
        if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
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

    @SuppressWarnings("unchecked")
    static LockEntry parseEntry(Object value, Path base) throws IOException {
        if (!(value instanceof Map<?, ?>)) throw new IOException("lock entry must be an object");
        Map<String, Object> row = (Map<String, Object>) value;
        Set<String> allowed = Set.of("ontology_iri", "version_iri", "document", "sha256", "direct");
        if (!allowed.containsAll(row.keySet())) throw new IOException("unknown lock entry field");
        String document = string(row.get("document"));
        if (document.isBlank() || Path.of(document).isAbsolute()) {
            throw new IOException("lock document must be a non-empty relative path");
        }
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path absolute = normalizedBase.resolve(document).normalize();
        if (!absolute.startsWith(normalizedBase)) throw new IOException("lock document escapes lock directory");
        String ontologyIri = string(row.get("ontology_iri"));
        if (ontologyIri.isBlank() || !isAbsoluteIri(ontologyIri)) {
            throw new IOException("ontology_iri must be an absolute IRI");
        }
        String versionIri = string(row.get("version_iri"));
        if (!versionIri.isBlank() && !isAbsoluteIri(versionIri)) {
            throw new IOException("version_iri must be null or an absolute IRI");
        }
        String lockedSha = string(row.get("sha256"));
        if (!lockedSha.matches("[0-9a-f]{64}")) throw new IOException("sha256 must be 64 lowercase hex digits");
        if (!(row.get("direct") instanceof Boolean)) throw new IOException("direct must be boolean");
        return new LockEntry(ontologyIri, versionIri, document,
                lockedSha, Boolean.TRUE.equals(row.get("direct")), absolute);
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
            String policyPath, String policyDigest) { }

    /** Coordinates and revision pinned around verify's off-thread file hashing. */
    private record VerifyCoordinates(Coordinates coordinates, ModelRevision revision) { }

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
