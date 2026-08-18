package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.core.workspace.ImportLockFile;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies import locks, attests loaded closure content and resolves release-gate lock policy. */
final class ImportLockVerifier {

    private ImportLockVerifier() {}

    static CallToolResult verify(ToolContext ctx, Map<String, Object> arguments) {
        return verify(ctx, arguments, () -> {}, null);
    }

    /** Test seam for a concurrent model/import change after off-thread hashing. */
    static CallToolResult verify(
            ToolContext ctx, Map<String, Object> arguments, Runnable afterCapture) {
        return verify(ctx, arguments, afterCapture, null);
    }

    static CallToolResult verify(
            ToolContext ctx,
            Map<String, Object> arguments,
            Runnable afterCapture,
            McpSyncServerExchange exchange) {
        ImportLockAccess access = ImportLockAccess.open(ctx, exchange, arguments, false);
        String configuredPolicy = Tools.optString(access.arguments, "policy_path");
        ImportLockCaptureCodec.VerifyCoordinates initial =
                ctx.access()
                        .compute(
                                mm -> verifyCoordinates(ctx, mm, access, configuredPolicy),
                                ImportLockModel.WRITE_HOP_TIMEOUT_MS);
        ImportLockCaptureCodec.LockCapture current =
                ImportLockCaptureCodec.capture(
                        initial.coordinates()); // hashes off the model thread
        afterCapture.run();
        ImportLockCaptureCodec.VerifyCoordinates finalState =
                ctx.access()
                        .compute(
                                mm -> verifyCoordinates(ctx, mm, access, configuredPolicy),
                                ImportLockModel.WRITE_HOP_TIMEOUT_MS);
        List<String> errors = new ArrayList<>(current.errors());
        if (!initial.revision().equals(finalState.revision())
                || !initial.coordinates().equals(finalState.coordinates())
                || !java.util.Objects.equals(initial.policyDigest(), finalState.policyDigest())
                || !java.util.Objects.equals(
                        initial.policySourceDigest(), finalState.policySourceDigest())) {
            errors.add(
                    "ontology/import state changed while the lock was being verified; retry against"
                            + " a stable workspace revision");
        }
        return Tools.ok(compareCapture(current, errors));
    }

    private static ImportLockCaptureCodec.VerifyCoordinates verifyCoordinates(
            ToolContext ctx,
            org.protege.editor.owl.model.OWLModelManager mm,
            ImportLockAccess access,
            String configuredPolicy) {
        RevisionTools.PolicyState policy =
                ImportLockCaptureCodec.resolvePolicyOnModelThread(mm, configuredPolicy);
        Path target =
                access.explicitTarget != null
                        ? access.explicitTarget
                        : access.rules.implicitPath(
                                ImportLockCaptureCodec.resolveLockPath(mm, policy), false);
        ImportLockCaptureCodec.Coordinates coordinates = ImportLockCaptureCodec.gather(mm, target);
        ModelRevision revision =
                ctx.revisions().current(mm, null, policy.policy().digest()).revision();
        return new ImportLockCaptureCodec.VerifyCoordinates(
                coordinates,
                revision,
                policy.policy().digest(),
                ImportLockCaptureCodec.policySourceDigest(policy.policy()));
    }

    /** Compare the lock against the exact already-loaded graph captured by project QC/preflight. */
    static Map<String, Object> verifyForGate(
            ProjectPolicy policy,
            ImportTools.ImportReport importReport,
            OntologyFingerprint activeFingerprint,
            String closureFingerprint) {
        // Locked policies keep resolving ONLY the policy asset (ADR 0005 decision 4): the
        // externally-resolved variant below must never widen this resolution rule.
        List<Path> paths = policy.assets().getOrDefault("import_lock", List.of());
        if (paths.size() != 1) {
            return Map.of(
                    "valid",
                    false,
                    "errors",
                    List.of("locked policy did not resolve exactly one imports.lockfile"),
                    "missing_entries",
                    List.of(),
                    "extra_entries",
                    List.of(),
                    "mismatched_entries",
                    List.of(),
                    "entry_count",
                    0);
        }
        return verifyForGate(paths.get(0), importReport, activeFingerprint, closureFingerprint);
    }

    /**
     * The same gate comparison-plus-loaded-content attestation against an externally resolved lock
     * path — the request-triggered ({@code lock_mode=verify|required}) gate entry for unlocked or
     * absent policies. Resolution stays the caller's responsibility (see {@link
     * #resolveRequestGateLock}); this method never consults {@code imports.lockfile}.
     */
    static Map<String, Object> verifyForGate(
            Path lockPath,
            ImportTools.ImportReport importReport,
            OntologyFingerprint activeFingerprint,
            String closureFingerprint) {
        ImportLockCaptureCodec.LockCapture current =
                ImportLockCaptureCodec.capture(
                        ImportLockCaptureCodec.gather(importReport, lockPath));
        Map<String, Object> comparison = compareCapture(current, new ArrayList<>(current.errors()));
        if (!Boolean.TRUE.equals(comparison.get("valid"))) {
            return comparison;
        }
        return attestLoadedContent(
                comparison, current, importReport, activeFingerprint, closureFingerprint);
    }

    /**
     * Resolve the lockfile for a request-triggered gate verification with the released {@code
     * verify_import_lock} TOOL rules (ADR 0005 decision 4): the policy-declared {@code
     * imports.lockfile} asset when exactly one resolves; the beside-active-document {@code
     * imports.lock.json} default only when no lockfile is declared or no policy is loaded. The
     * released refusal states — a declared-but-unresolved lockfile, an invalid policy, no local
     * document folder — abort the request as {@link ToolArgException} rather than emitting a
     * finding. The beside-document default derives from the SAME-hop import report the gate
     * captured, so the verification and the QC snapshot see one document — and is authorized
     * through the request's {@link DirectAccessPolicy.Rules} (canonical, symlink-resolved project
     * containment) BEFORE any existence check or read, exactly like the released {@code
     * verify_import_lock} tool.
     */
    static ImportLockModel.GateLock resolveRequestGateLock(
            ProjectPolicy policy,
            ImportTools.ImportReport importReport,
            DirectAccessPolicy.Rules rules) {
        if (policy.loaded()) {
            List<Path> policyPaths = policy.assets().getOrDefault("import_lock", List.of());
            String declared = ImportLockCaptureCodec.declaredLockfile(policy);
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
                                + "' but it did not resolve to a file (for example it does not"
                                + " exist yet), so lock_mode cannot verify it. Create the declared"
                                + " lockfile (write_import_lock) first; the lock is never read"
                                + " anywhere else.");
            }
            if (!policy.valid()) {
                throw new ToolArgException(
                        "Cannot resolve the lockfile for lock_mode: the project "
                                + "policy is invalid ("
                                + ImportLockCaptureCodec.policyErrorCodes(policy)
                                + " — see validate_project_policy).");
            }
            if (policyPaths.size() == 1) {
                return new ImportLockModel.GateLock(
                        policyPaths.get(0), ImportLockModel.SOURCE_POLICY_DECLARED);
            }
        }
        java.io.File active =
                importReport == null
                        ? null
                        : localDocumentFile(importReport.root.get("document_iri"));
        if (active == null || active.getParentFile() == null) {
            throw new ToolArgException(
                    "The active ontology has no local document folder, so no "
                            + "beside-document imports.lock.json can be resolved for lock_mode.");
        }
        return new ImportLockModel.GateLock(
                authorizeBesideDocumentLock(
                        rules, active.getParentFile().toPath().resolve("imports.lock.json")),
                ImportLockModel.SOURCE_BESIDE_DOCUMENT);
    }

    /**
     * Authorize READING a beside-document lockfile against the request's filesystem rules before
     * any existence probe or byte is read. {@code implicitPath} pins the canonical,
     * symlink-resolved path and refuses an out-of-project escape under a confining policy — a
     * symlinked {@code imports.lock.json} pointing outside {@code project_root} must never leak
     * out-of-project content into a caller-visible comparison. Fails closed when no request-scoped
     * rules were threaded (a caller that cannot authorize the read must not perform it).
     */
    private static Path authorizeBesideDocumentLock(DirectAccessPolicy.Rules rules, Path lockPath) {
        if (rules == null) {
            throw new ToolArgException(
                    "lock_mode cannot authorize the beside-document lockfile read on this surface:"
                            + " no request-scoped filesystem rules are available.");
        }
        return rules.implicitPath(lockPath, false);
    }

    /**
     * Verify a TO-BE-LOADED closure's coordinates and SHA-256 hashes against the lockfile resolved
     * for the loaded document — the {@code lock_mode} entry for {@code load_ontology} and {@code
     * merge_ontology_document} (ADR 0005 decision 4). The lockfile resolves beside the loaded
     * document by default; the policy-declared lockfile applies only when the loaded document is
     * the project's resolved root artifact. A mismatch, or {@code required} with no lockfile,
     * refuses the load with a structured {@link ToolArgException} while the closure still lives in
     * the throwaway parse manager — nothing has been applied to the workspace. The loaded-content
     * attestation is deliberately NOT run here: it requires the closure fingerprint captured in the
     * same model-thread hop as a QC snapshot, which loading operations do not have (gate-only). The
     * beside-document lockfile read is authorized through the request's rules (canonical,
     * symlink-resolved project containment) BEFORE any existence check or read. Returns the
     * verification map for the tool's structured result.
     */
    static Map<String, Object> verifyClosureBeforeLoad(
            DirectAccessPolicy.Rules rules,
            ImportTools.ImportReport report,
            java.io.File loadedDocument,
            LockMode mode) {
        ProjectPolicy policy = rules == null ? null : rules.policy();
        if (policy != null && policy.loaded() && !policy.valid()) {
            // Tool handlers cannot reach this because source authorization already fails closed on
            // an invalid policy, but never trust an invalid policy to resolve a lockfile.
            throw new ToolArgException(
                    "Cannot resolve the lockfile for lock_mode: the project "
                            + "policy is invalid ("
                            + ImportLockCaptureCodec.policyErrorCodes(policy)
                            + " — see validate_project_policy).");
        }
        Path document =
                loadedDocument == null
                        ? null
                        : loadedDocument.getAbsoluteFile().toPath().normalize();
        Path lockPath = null;
        String source = ImportLockModel.SOURCE_BESIDE_DOCUMENT;
        if (policy != null && policy.loaded() && isProjectRootArtifact(policy, document)) {
            List<Path> policyPaths = policy.assets().getOrDefault("import_lock", List.of());
            if (policyPaths.size() == 1) {
                lockPath = policyPaths.get(0);
                source = ImportLockModel.SOURCE_POLICY_DECLARED;
            } else if (ImportLockCaptureCodec.declaredLockfile(policy) != null) {
                // The project's root artifact is verified against the DECLARED lockfile only; a
                // declared-but-unresolved lockfile is the file-absent state for this load.
                return absentLockResult(
                        policy.projectRoot() == null
                                ? null
                                : policy.projectRoot()
                                        .resolve(ImportLockCaptureCodec.declaredLockfile(policy)),
                        ImportLockModel.SOURCE_POLICY_DECLARED,
                        mode);
            }
        }
        if (lockPath == null && source.equals(ImportLockModel.SOURCE_BESIDE_DOCUMENT)) {
            if (document == null || document.getParent() == null) {
                return absentLockResult(null, ImportLockModel.SOURCE_BESIDE_DOCUMENT, mode);
            }
            lockPath =
                    authorizeBesideDocumentLock(
                            rules, document.getParent().resolve("imports.lock.json"));
        }
        if (!Files.isRegularFile(lockPath)) {
            return absentLockResult(lockPath, source, mode);
        }
        ImportLockCaptureCodec.LockCapture current =
                ImportLockCaptureCodec.capture(ImportLockCaptureCodec.gather(report, lockPath));
        Map<String, Object> verification =
                new LinkedHashMap<>(compareCapture(current, new ArrayList<>(current.errors())));
        verification.put("lockfile_source", source);
        if (ImportLockModel.SOURCE_BESIDE_DOCUMENT.equals(source)) {
            verification.put("lockfile_note", ImportLockModel.BESIDE_DOCUMENT_TRUST_NOTE);
        }
        if (!Boolean.TRUE.equals(verification.get("valid"))) {
            throw new ToolArgException(
                    "Import lock verification (lock_mode="
                            + mode.value()
                            + ") refused the load before any workspace change: the document's"
                            + " import closure does not match the lockfile "
                            + lockPath
                            + " (errors: "
                            + verification.get("errors")
                            + "; missing_entries: "
                            + verification.get("missing_entries")
                            + "; extra_entries: "
                            + verification.get("extra_entries")
                            + "; mismatched_entries: "
                            + verification.get("mismatched_entries")
                            + "). Nothing was loaded or merged.");
        }
        return verification;
    }

    /**
     * The clean skip (verify) or structured refusal (required) for a lockfile that does not exist.
     */
    private static Map<String, Object> absentLockResult(
            Path lockPath, String source, LockMode mode) {
        String location =
                lockPath == null
                        ? "the loaded document is not a local file, so no beside-document lockfile"
                                + " can exist"
                        : "no lockfile exists at " + lockPath;
        if (mode == LockMode.REQUIRED) {
            throw new ToolArgException(
                    "lock_mode=required refused the load before any workspace "
                            + "change: "
                            + location
                            + ". Create the lock (write_import_lock) or load with lock_mode=verify"
                            + " to skip when it is absent. Nothing was loaded or merged.");
        }
        Map<String, Object> skipped = new LinkedHashMap<>();
        skipped.put("verified", false);
        skipped.put("skipped", true);
        if (lockPath != null) {
            skipped.put("path", lockPath.toString());
        }
        skipped.put("lockfile_source", source);
        skipped.put(
                "note",
                "No lockfile was found ("
                        + location
                        + "), so nothing was verified; lock_mode=verify skips cleanly. Pass"
                        + " lock_mode=required to make this an error.");
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
     * documents sharing one import closure, and require it to equal the closure fingerprint
     * captured in the same model-thread hop as the QC snapshot. Equality attests that the validated
     * closure matches the lock-verified disk content up to the shared artifact-boundary equivalence
     * of the v2 semantic fingerprint — which deliberately ignores unannotated declarations of
     * entities already in a document's signature (the same boundary verified saves use), so a
     * divergence confined to that inert class can still attest; any semantically visible divergence
     * fails closed.
     */
    private static Map<String, Object> attestLoadedContent(
            Map<String, Object> comparison,
            ImportLockCaptureCodec.LockCapture current,
            ImportTools.ImportReport importReport,
            OntologyFingerprint activeFingerprint,
            String closureFingerprint) {
        List<String> errors = new ArrayList<>();
        List<String> sessionOnly = new ArrayList<>();
        boolean verified = false;
        if (activeFingerprint == null || closureFingerprint == null) {
            errors.add(
                    "the gate did not capture the loaded closure fingerprint, so the lock-matching"
                        + " disk content cannot be attested as the content QC actually validated");
        } else {
            String rootKey =
                    closureMemberKey(
                            new org.semanticweb.owlapi.model.OWLOntologyID(
                                    memberIri(importReport.root.get("ontology_iri")),
                                    memberIri(importReport.root.get("version_iri"))));
            List<String> members = new ArrayList<>();
            members.add(closureMemberLine(rootKey, activeFingerprint.semanticFingerprint()));
            try {
                List<ImportLockModel.ParsedLockedDocument> parsed =
                        parseLockedClosure(current.entries(), importReport.resolvedImports);
                for (int i = 0; i < parsed.size(); i++) {
                    ImportLockModel.ParsedLockedDocument member = parsed.get(i);
                    // A cycle can make the active document itself a locked import; the live capture
                    // above already attests that single closure member, dirty or not.
                    if (member.memberKey().equals(rootKey)) continue;
                    members.add(member.memberLine());
                    if (member.sessionOnly()) sessionOnly.add(current.entries().get(i).document());
                }
            } catch (IOException e) {
                errors.add(
                        e.getMessage() == null
                                ? "could not attest the locked import closure: "
                                        + e.getClass().getSimpleName()
                                : e.getMessage());
            }
            if (errors.isEmpty()) {
                members.sort(Comparator.naturalOrder());
                if (RevisionTools.sha256(
                                String.join("\n", members).getBytes(StandardCharsets.UTF_8))
                        .equals(closureFingerprint)) {
                    verified = true;
                } else {
                    errors.add(
                            "the loaded import closure content does not provably match the"
                                + " lock-verified on-disk documents: an unsaved in-memory edit of"
                                + " an imported ontology (or an on-disk change since Protégé loaded"
                                + " it) keeps every coordinate and disk hash intact while QC"
                                + " validates different axioms. Reload or save the imported"
                                + " ontologies so disk and workspace agree, then rerun.");
                    if (!sessionOnly.isEmpty()) {
                        errors.add(
                                "note: locked document(s) "
                                        + sessionOnly
                                        + " contain anonymous individuals whose OWLAPI ids are"
                                        + " session-local, so their content equality cannot be"
                                        + " proven across parses; the gate fails closed for them"
                                        + " even when the content is unchanged");
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
            if (base instanceof List<?>)
                ((List<?>) base).forEach(e -> combined.add(String.valueOf(e)));
            combined.addAll(errors);
            result.put("errors", combined);
        }
        return result;
    }

    private static org.semanticweb.owlapi.model.IRI memberIri(Object value) {
        String iri = ImportLockCaptureCodec.blankToNull(ImportLockCaptureCodec.string(value));
        return iri == null ? null : org.semanticweb.owlapi.model.IRI.create(iri);
    }

    /**
     * MUST byte-match the (private) member-key encoding inside IsolatedValidationSnapshot's closure
     * fingerprint — ontology IRI, version IRI and OWLOntologyID rendering NUL-joined — so a rebuilt
     * closure digest can equal a captured one. Pinned by the locked-gate pass-path tests: any drift
     * makes every clean locked run fail closed as unattested, never a false pass.
     */
    private static String closureMemberKey(org.semanticweb.owlapi.model.OWLOntologyID id) {
        return (id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : "")
                + "\u0000"
                + (id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : "")
                + "\u0000"
                + id;
    }

    private static String closureMemberLine(String key, String semanticFingerprint) {
        return key.getBytes(StandardCharsets.UTF_8).length
                + ":"
                + key
                + "\u0000"
                + semanticFingerprint;
    }

    /** (closure bytes + live edges digest) -> parsed members; an unchanged closure parses once. */
    private static final int PARSE_CACHE_LIMIT = 16;

    private static final Map<String, List<ImportLockModel.ParsedLockedDocument>> PARSE_CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, List<ImportLockModel.ParsedLockedDocument>> eldest) {
                    return size() > PARSE_CACHE_LIMIT;
                }
            };

    /**
     * Parse every locked on-disk document in ONE private manager with the same no-network
     * discipline as the verified-save reload, leaf-first along the live resolved-import edges, so
     * each document is parsed WITH the same import closure Protégé had available when it loaded it.
     * A modular ontology routinely uses a property that only an imported document declares; parsing
     * members in isolation types those axioms differently from the loaded closure and would fail a
     * clean, untampered project forever. An import that is not an already-parsed lock-verified
     * member (an unresolved import, or a cyclic edge whose target cannot be parsed first) resolves
     * to one empty placeholder and any resulting typing drift fails closed as divergence, never as
     * a false pass.
     *
     * <p>Each document is read from disk exactly once by {@link #verifiedSource}, so the
     * fingerprint always derives from the same bytes that hash check verified. Members are
     * fingerprinted only after every member has loaded: a later member's load may legally rewrite
     * an earlier one (OWLAPI's illegal-punning repair spans the loaded closure), exactly as it did
     * in Protégé.
     */
    static List<ImportLockModel.ParsedLockedDocument> parseLockedClosure(
            List<ImportLockModel.LockEntry> entries, List<Map<String, Object>> resolvedImports)
            throws IOException {
        String cacheKey = closureContextKey(entries, resolvedImports);
        synchronized (PARSE_CACHE) {
            List<ImportLockModel.ParsedLockedDocument> cached = PARSE_CACHE.get(cacheKey);
            if (cached != null) return cached;
        }
        Map<String, ImportLockModel.LockEntry> byKey = new LinkedHashMap<>();
        entries.forEach(entry -> byKey.putIfAbsent(entry.key(), entry));
        Map<String, org.semanticweb.owlapi.io.StreamDocumentSource> sources = new LinkedHashMap<>();
        for (ImportLockModel.LockEntry entry : entries) {
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
            declarationTargets.putIfAbsent(
                    ImportLockCaptureCodec.string(row.get("import_iri")), target);
            if (byKey.containsKey(source) && !source.equals(target)) {
                dependsOn.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
            }
        }
        List<String> order = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (ImportLockModel.LockEntry entry : entries) {
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
        for (ImportLockModel.LockEntry entry : entries) {
            if (!placed.contains(entry.key())) order.add(entry.key());
        }

        Path placeholder = Files.createTempFile("protege-mcp-lock-gate-imports-", ".ofn");
        try {
            Files.writeString(placeholder, "Ontology()\n", StandardCharsets.UTF_8);
            org.semanticweb.owlapi.model.OWLOntologyManager manager = OwlManagers.create();
            org.semanticweb.owlapi.model.IRI placeholderIri =
                    org.semanticweb.owlapi.model.IRI.create(placeholder.toUri());
            Map<String, org.semanticweb.owlapi.model.IRI> loadedDocuments = new LinkedHashMap<>();
            manager.getIRIMappers()
                    .add(
                            importIri -> {
                                String target = declarationTargets.get(importIri.toString());
                                org.semanticweb.owlapi.model.IRI document =
                                        target == null ? null : loadedDocuments.get(target);
                                return document == null ? placeholderIri : document;
                            });
            org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration config =
                    new org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(
                                    org.semanticweb.owlapi.model.MissingImportHandlingStrategy
                                            .SILENT)
                            .setFollowRedirects(false);
            Map<String, org.semanticweb.owlapi.model.OWLOntology> parsedByKey =
                    new LinkedHashMap<>();
            for (String key : order) {
                try {
                    parsedByKey.put(
                            key,
                            manager.loadOntologyFromOntologyDocument(sources.get(key), config));
                    loadedDocuments.put(key, sources.get(key).getDocumentIRI());
                } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException
                        | RuntimeException e) {
                    throw new IOException(
                            "could not attest locked import document "
                                    + byKey.get(key).absolute()
                                    + ": "
                                    + (e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage()));
                }
            }
            List<ImportLockModel.ParsedLockedDocument> results = new ArrayList<>();
            try {
                for (ImportLockModel.LockEntry entry : entries) {
                    org.semanticweb.owlapi.model.OWLOntology parsed = parsedByKey.get(entry.key());
                    OntologyFingerprint fingerprint = OntologyFingerprints.compute(parsed);
                    String key = closureMemberKey(parsed.getOntologyID());
                    results.add(
                            new ImportLockModel.ParsedLockedDocument(
                                    key,
                                    closureMemberLine(key, fingerprint.semanticFingerprint()),
                                    !fingerprint.releaseStable()));
                }
            } catch (RuntimeException e) {
                throw new IOException(
                        "could not attest the locked import closure: "
                                + (e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage()));
            }
            List<ImportLockModel.ParsedLockedDocument> immutable = List.copyOf(results);
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
     * nobody hashed. The document IRI stays the on-disk location so relative IRIs resolve exactly
     * as they did when Protégé loaded the file.
     */
    private static org.semanticweb.owlapi.io.StreamDocumentSource verifiedSource(
            ImportLockModel.LockEntry entry) throws IOException {
        if (!Files.isRegularFile(entry.absolute())) {
            throw new IOException(
                    "could not attest locked import document "
                            + entry.absolute()
                            + ": not a regular file");
        }
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        final org.semanticweb.owlapi.io.StreamDocumentSource source;
        try (java.io.InputStream in =
                new java.security.DigestInputStream(
                        new java.io.BufferedInputStream(Files.newInputStream(entry.absolute())),
                        digest)) {
            // The source constructor consumes the stream to EOF into its private buffer, wrapping
            // any read failure unchecked.
            source =
                    new org.semanticweb.owlapi.io.StreamDocumentSource(
                            in, org.semanticweb.owlapi.model.IRI.create(entry.absolute().toUri()));
        } catch (RuntimeException e) {
            throw new IOException(
                    "could not attest locked import document "
                            + entry.absolute()
                            + ": "
                            + (e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage()));
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        if (!hex.toString().equals(entry.sha256())) {
            throw new IOException(
                    "locked import document "
                            + entry.absolute()
                            + " changed while the gate was attesting it");
        }
        return source;
    }

    /**
     * The lock-entry key an import-report row's coordinates map to; matches {@link
     * ImportLockModel.LockEntry#key}.
     */
    private static String entryKey(Object ontologyIri, Object versionIri) {
        String ontology = ImportLockCaptureCodec.string(ontologyIri);
        String version = ImportLockCaptureCodec.string(versionIri);
        return version.isBlank() ? ontology : ontology + " @ " + version;
    }

    /**
     * Injective digest of everything the closure parse depends on: each member's document path,
     * exact content hash and identity, plus every live resolved-import edge (a member's parse
     * result depends on its imports' content and on which member each declaration resolved to). Any
     * changed byte, membership, or edge reparses instead of serving a stale member.
     */
    private static String closureContextKey(
            List<ImportLockModel.LockEntry> entries, List<Map<String, Object>> resolvedImports) {
        List<String> lines = new ArrayList<>();
        for (ImportLockModel.LockEntry entry : entries) {
            lines.add(
                    contextLine("entry", entry.absolute().toString(), entry.sha256(), entry.key()));
        }
        for (Map<String, Object> row : resolvedImports) {
            lines.add(
                    contextLine(
                            "edge",
                            entryKey(row.get("source_ontology_iri"), row.get("source_version_iri")),
                            entryKey(row.get("target_ontology_iri"), row.get("target_version_iri")),
                            ImportLockCaptureCodec.string(row.get("import_iri"))));
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

    private static Map<String, Object> compareCapture(
            ImportLockCaptureCodec.LockCapture current, List<String> errors) {
        Map<String, ImportLockModel.LockEntry> expected = new LinkedHashMap<>();
        String lockDigest = null;
        try {
            ImportLockFile.Document parsed = ImportLockFile.read(current.path());
            lockDigest = parsed.sha256();
            for (ImportLockFile.Entry value : parsed.entries()) {
                ImportLockModel.LockEntry entry = ImportLockCaptureCodec.lockEntry(value);
                expected.put(entry.key(), entry);
            }
        } catch (ImportLockFile.InvalidLockException invalid) {
            lockDigest = invalid.sha256();
            errors.add(
                    "could not read/parse lock: "
                            + (invalid.getMessage() == null
                                    ? invalid.getClass().getSimpleName()
                                    : invalid.getMessage()));
        } catch (Exception e) {
            errors.add(
                    "could not read/parse lock: "
                            + (e.getMessage() == null
                                    ? e.getClass().getSimpleName()
                                    : e.getMessage()));
        }
        Map<String, ImportLockModel.LockEntry> actual = current.entriesByKey();
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, ImportLockModel.LockEntry> entry : actual.entrySet()) {
            ImportLockModel.LockEntry locked = expected.get(entry.getKey());
            if (locked == null) missing.add(entry.getKey());
            else if (!locked.sha256().equals(entry.getValue().sha256())
                    || !locked.document().equals(entry.getValue().document())
                    || locked.direct() != entry.getValue().direct()) mismatched.add(entry.getKey());
        }
        expected.keySet().stream().filter(key -> !actual.containsKey(key)).forEach(extra::add);
        boolean valid =
                errors.isEmpty() && missing.isEmpty() && extra.isEmpty() && mismatched.isEmpty();
        return Tools.json()
                .put("valid", valid)
                .put("path", current.path().toString())
                .putIfNotNull("sha256", lockDigest)
                .put("entry_count", expected.size())
                .put("errors", errors)
                .put("missing_entries", missing)
                .put("extra_entries", extra)
                .put("mismatched_entries", mismatched)
                .map();
    }
}
