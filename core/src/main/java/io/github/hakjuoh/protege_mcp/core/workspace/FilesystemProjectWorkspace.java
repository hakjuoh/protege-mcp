package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.policy.PolicyIssue;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/**
 * Offline, project-confined filesystem workspace. Every ontology is parsed from captured bytes with
 * its original document IRI; no caller can inject an OWLAPI manager or mapper.
 */
public final class FilesystemProjectWorkspace implements ProjectWorkspace {

    private static final int MAX_CATALOGS = 64;
    private static final int MAX_DIRECTORY_ENTRIES = 10_000;
    private static final int MAX_TEMP_FILES = 20_000;
    private static final long MAX_CAPTURE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TEMP_BYTES = 2L * 1024 * 1024 * 1024;

    private final Path policyPath;
    private final Path stateRoot;
    private final CaptureHook beforeFinalVerification;
    private final String workspaceId = UUID.randomUUID().toString();
    private final AtomicLong revision = new AtomicLong();

    public FilesystemProjectWorkspace(Path policyPath) {
        this(policyPath, defaultStateRoot(), () -> { });
    }

    FilesystemProjectWorkspace(Path policyPath, CaptureHook beforeFinalVerification) {
        this(policyPath, defaultStateRoot(), beforeFinalVerification);
    }

    FilesystemProjectWorkspace(Path policyPath, Path stateRoot,
            CaptureHook beforeFinalVerification) {
        if (policyPath == null) {
            throw new IllegalArgumentException("policyPath must not be null");
        }
        this.policyPath = policyPath.toAbsolutePath().normalize();
        this.stateRoot = java.util.Objects.requireNonNull(stateRoot, "stateRoot")
                .toAbsolutePath().normalize();
        this.beforeFinalVerification = java.util.Objects.requireNonNull(
                beforeFinalVerification, "beforeFinalVerification");
    }

    @Override
    public String workspaceId() {
        return workspaceId;
    }

    @Override
    public synchronized WorkspaceSnapshot capture() throws IOException {
        return capture(false);
    }

    /** Capture for deterministic import-lock generation without trusting the lock being replaced. */
    public synchronized WorkspaceSnapshot captureForImportLock() throws IOException {
        return capture(true);
    }

    private WorkspaceSnapshot capture(boolean importLockBootstrap) throws IOException {
        Path temporaryRoot = createPrivateTempDirectory();
        CaptureContext context = new CaptureContext(temporaryRoot);
        OWLOntologyManager manager = null;
        boolean success = false;
        try {
            Path canonicalPolicy = requireRegularFile(policyPath, null, "project policy");
            CapturedFile policyCapture = context.capture(canonicalPolicy, "policy",
                    ProjectPolicyLoader.MAX_POLICY_BYTES);
            ProjectPolicy policy = ProjectPolicyLoader.loadCaptured(canonicalPolicy,
                    Files.readAllBytes(policyCapture.captured), null, null, true);
            if (importLockBootstrap) requireImportLockBootstrapPolicy(policy);
            else requireValidPolicy(policy);
            if (!canonicalPolicy.equals(policy.path())) {
                throw new IOException("project policy path changed during discovery");
            }
            context.verify(policyCapture);

            Path projectRoot = policy.projectRoot().toRealPath();
            List<Path> roots = policy.assets().getOrDefault("root_artifact", List.of());
            if (roots.size() != 1) {
                throw new IOException("valid project policy did not resolve exactly one root artifact");
            }
            CapturedFile rootCapture = context.capture(
                    requireRegularFile(roots.get(0), projectRoot, "root artifact"),
                    "root_ontology", MAX_CAPTURE_BYTES);

            Map<String, Mapping> mappings = new LinkedHashMap<>();
            putMapping(mappings, string(policy.effective().get("root_ontology")),
                    rootCapture.original.toUri(), "project root artifact");
            installModuleMappings(policy, projectRoot, context, mappings);
            installCatalogMappings(rootCapture.original, projectRoot, context, mappings);
            ImportLockFile.Document lock;
            if (importLockBootstrap) {
                installBootstrapLockMappings(policy, projectRoot, context, mappings);
                lock = null;
            } else {
                lock = installLockMappings(policy, projectRoot, context, mappings);
            }
            Map<String, List<Path>> capturedAssets = capturePolicyAssets(policy, projectRoot, context);

            manager = OWLManager.createOWLOntologyManager();
            Path missing = temporaryRoot.resolve("missing-import-do-not-create.ofn");
            IRI missingIri = IRI.create(missing.toUri());
            manager.getIRIMappers().add(ignored -> missingIri);
            OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                    .setFollowRedirects(false);

            Map<Path, OWLOntology> loadedByDocument = new LinkedHashMap<>();
            OWLOntology root = load(manager, rootCapture, configuration);
            loadedByDocument.put(rootCapture.original, root);
            resolveClosure(manager, root, mappings, projectRoot, context, loadedByDocument,
                    configuration);
            requireRootIdentity(root, string(policy.effective().get("root_ontology")));
            requireResolvedImports(root);
            if (lock != null) {
                verifyLockClosure(lock, root, context);
            }

            String lockDigest = lock == null ? null : lock.sha256();
            OntologyFingerprint fingerprint = OntologyFingerprints.compute(root, lockDigest);
            String closureFingerprint = WorkspaceFingerprints.closure(root.getImportsClosure());
            beforeFinalVerification.run();
            context.verifyAll();

            ModelRevision modelRevision = new ModelRevision(workspaceId,
                    revision.incrementAndGet(), fingerprint.semanticFingerprint(),
                    fingerprint.documentFingerprint());
            WorkspaceSnapshot snapshot = new WorkspaceSnapshot(policy, manager, root,
                    context.sources(), capturedAssets, modelRevision, closureFingerprint,
                    lockDigest, temporaryRoot);
            success = true;
            return snapshot;
        } finally {
            if (!success) {
                if (manager != null) {
                    for (OWLOntology ontology : new ArrayList<>(manager.getOntologies())) {
                        manager.removeOntology(ontology);
                    }
                }
                deleteTree(temporaryRoot);
            }
        }
    }

    @Override
    public boolean isCurrent(WorkspaceSnapshot snapshot) {
        if (!ownsOpenSnapshot(snapshot)) {
            return false;
        }
        try {
            OntologyFingerprint current = OntologyFingerprints.compute(snapshot.root(),
                    snapshot.importLockDigest());
            if (!current.semanticFingerprint().equals(snapshot.revision().semanticFingerprint())
                    || !current.documentFingerprint().equals(
                            snapshot.revision().documentFingerprint())
                    || !WorkspaceFingerprints.closure(snapshot.closure()).equals(
                            snapshot.closureFingerprint())) {
                return false;
            }
            return sourcesCurrent(snapshot);
        } catch (RuntimeException changed) {
            return false;
        }
    }

    /** Begin a checksum-guarded single-file transaction within this snapshot's project. */
    public WorkspaceTransaction beginTransaction(WorkspaceSnapshot snapshot, Path target,
            boolean backup) throws IOException {
        return new WorkspaceTransaction(this, snapshot, target, backup);
    }

    /** Begin a guarded whole-directory release publication transaction. */
    public WorkspaceBundleTransaction beginBundleTransaction(WorkspaceSnapshot snapshot, Path target,
            WorkspaceBundleTransaction.Guard guard) throws IOException {
        return new WorkspaceBundleTransaction(this, snapshot, target, guard);
    }

    /** Resolve and authorize a release directory without creating or changing it. */
    public Path resolveBundleTarget(WorkspaceSnapshot snapshot, Path requested) throws IOException {
        if (!ownsOpenSnapshot(snapshot) || !isCurrent(snapshot)) {
            throw new IOException("workspace sources changed before release output authorization");
        }
        Path projectRoot = snapshot.policy().projectRoot().toRealPath();
        Path candidate = requested.isAbsolute() ? requested : projectRoot.resolve(requested);
        return WorkspaceBundleTransaction.resolveTarget(candidate, projectRoot);
    }

    boolean sourcesCurrent(WorkspaceSnapshot snapshot) {
        if (!ownsOpenSnapshot(snapshot)) {
            return false;
        }
        try {
            for (WorkspaceSource source : snapshot.sources()) {
                boolean directory = source.kind().startsWith("asset_directory:");
                FileIdentity original = pinnedIdentity(source.original(), directory);
                FileIdentity captured = pinnedIdentity(source.captured(), directory);
                if (original.bytes != source.bytes() || !original.sha256.equals(source.sha256())
                        || captured.bytes != source.bytes()
                        || !captured.sha256.equals(source.sha256())) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException changed) {
            return false;
        }
    }

    Path stateRoot() {
        return stateRoot;
    }

    private boolean ownsOpenSnapshot(WorkspaceSnapshot snapshot) {
        return snapshot != null && !snapshot.closed()
                && workspaceId.equals(snapshot.revision().workspaceId());
    }

    private static void installModuleMappings(ProjectPolicy policy, Path projectRoot,
            CaptureContext context, Map<String, Mapping> mappings) throws IOException {
        List<Map<String, Object>> modules = objects(policy.effective().get("modules"));
        List<Path> paths = policy.assets().getOrDefault("modules", List.of());
        if (modules.size() != paths.size()) {
            throw new IOException("valid project policy module coordinates are incomplete");
        }
        for (int index = 0; index < modules.size(); index++) {
            Path module = requireRegularFile(paths.get(index), projectRoot, "module");
            CapturedFile captured = context.capture(module, "module", MAX_CAPTURE_BYTES);
            putMapping(mappings, string(modules.get(index).get("ontology_iri")),
                    captured.original.toUri(), "policy module");
        }
    }

    private static void installCatalogMappings(Path rootDocument, Path projectRoot,
            CaptureContext context, Map<String, Mapping> mappings) throws IOException {
        Path initial = rootDocument.getParent().resolve("catalog-v001.xml");
        if (!Files.exists(initial)) {
            return;
        }
        Deque<Path> pending = new ArrayDeque<>();
        pending.add(initial);
        Set<Path> visited = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            if (visited.size() >= MAX_CATALOGS) {
                throw new IOException("catalog chain exceeds " + MAX_CATALOGS + " local catalogs");
            }
            Path catalogPath = requireRegularFile(pending.removeFirst(), projectRoot, "catalog");
            if (!visited.add(catalogPath)) {
                continue;
            }
            CapturedFile captured = context.capture(catalogPath, "catalog", OfflineCatalog.MAX_BYTES);
            OfflineCatalog.Document catalog = OfflineCatalog.parse(
                    Files.readAllBytes(captured.captured), catalogPath);
            if (!catalog.errors().isEmpty()) {
                throw new IOException("invalid catalog " + catalogPath + ": "
                        + String.join("; ", catalog.errors()));
            }
            for (OfflineCatalog.Entry entry : catalog.entries()) {
                URI resolved = entry.resolved();
                if (resolved == null) {
                    throw new IOException("catalog entry has no resolved target: " + entry.name());
                }
                URI authorized = resolved;
                if ("file".equalsIgnoreCase(resolved.getScheme())) {
                    authorized = catalogFile(resolved, projectRoot,
                            "catalog mapping target").toUri();
                }
                putMapping(mappings, entry.name(), authorized, "catalog " + catalogPath);
            }
            for (URI next : catalog.nextCatalogs()) {
                if (!"file".equalsIgnoreCase(next.getScheme())) {
                    throw new IOException("offline catalog chain cannot delegate to " + next);
                }
                pending.add(catalogFile(next, projectRoot, "nextCatalog"));
            }
        }
    }

    private static ImportLockFile.Document installLockMappings(ProjectPolicy policy,
            Path projectRoot, CaptureContext context, Map<String, Mapping> mappings) throws IOException {
        Map<String, Object> imports = object(policy.effective().get("imports"));
        if (!"locked".equals(string(imports.get("mode")))) {
            return null;
        }
        List<Path> locks = policy.assets().getOrDefault("import_lock", List.of());
        if (locks.size() != 1) {
            throw new IOException("locked project did not resolve exactly one import lock");
        }
        Path path = requireRegularFile(locks.get(0), projectRoot, "import lock");
        CapturedFile lockCapture = context.capture(path, "import_lock", ImportLockFile.MAX_BYTES);
        ImportLockFile.Document lock = ImportLockFile.parse(
                Files.readAllBytes(lockCapture.captured), path);
        for (ImportLockFile.Entry entry : lock.entries()) {
            Path document = requireRegularFile(entry.absolute(), projectRoot, "locked import");
            CapturedFile captured = context.capture(document, "locked_import", MAX_CAPTURE_BYTES);
            if (!captured.sha256.substring("sha256:".length()).equals(entry.sha256())) {
                throw new IOException("locked import checksum mismatch: " + entry.key());
            }
            putMapping(mappings, entry.ontologyIri(), captured.original.toUri(), "import lock");
            if (entry.versionIri() != null) {
                putMapping(mappings, entry.versionIri(), captured.original.toUri(), "import lock");
            }
        }
        return new ImportLockFile.Document(path, lockCapture.sha256, lock.entries());
    }

    /**
     * Use an existing lock only as a local discovery hint while replacing it. Its stored checksums
     * are deliberately not accepted as evidence: the generated candidate hashes captured bytes.
     * Policy modules and catalogs take precedence, and an invalid old lock can still be replaced
     * when those authoritative mappings are sufficient.
     */
    private static void installBootstrapLockMappings(ProjectPolicy policy, Path projectRoot,
            CaptureContext context, Map<String, Mapping> mappings) throws IOException {
        Map<String, Object> imports = object(policy.effective().get("imports"));
        if (!"locked".equals(string(imports.get("mode")))) {
            return;
        }
        List<Path> locks = policy.assets().getOrDefault("import_lock", List.of());
        if (locks.size() != 1 || !Files.isRegularFile(locks.get(0))) {
            return;
        }
        Path path = requireRegularFile(locks.get(0), projectRoot, "import lock");
        CapturedFile lockCapture = context.capture(path, "import_lock", ImportLockFile.MAX_BYTES);
        final ImportLockFile.Document lock;
        try {
            lock = ImportLockFile.parse(Files.readAllBytes(lockCapture.captured), path);
        } catch (IOException invalidOldLock) {
            return;
        }
        for (ImportLockFile.Entry entry : lock.entries()) {
            Mapping preferred = mappings.get(entry.ontologyIri());
            if (preferred == null && entry.versionIri() != null) {
                preferred = mappings.get(entry.versionIri());
            }
            URI document;
            if (preferred != null) {
                document = preferred.document;
            } else {
                try {
                    Path local = requireRegularFile(entry.absolute(), projectRoot,
                            "import-lock bootstrap hint");
                    document = context.capture(local, "import_lock_hint", MAX_CAPTURE_BYTES)
                            .original.toUri();
                } catch (IOException staleHint) {
                    // A removed import may leave a stale entry in the lock being regenerated. If the
                    // ontology still needs this coordinate, closure resolution below fails closed.
                    continue;
                }
            }
            putMapping(mappings, entry.ontologyIri(), document, "import-lock bootstrap hint");
            if (entry.versionIri() != null) {
                putMapping(mappings, entry.versionIri(), document, "import-lock bootstrap hint");
            }
        }
    }

    private static Map<String, List<Path>> capturePolicyAssets(ProjectPolicy policy,
            Path projectRoot, CaptureContext context) throws IOException {
        Map<String, List<Path>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Path>> asset : policy.assets().entrySet()) {
            if ("release_output".equals(asset.getKey())) {
                continue;
            }
            List<Path> captured = new ArrayList<>();
            for (Path path : asset.getValue()) {
                Path real = path.toRealPath();
                if (!real.startsWith(projectRoot)) {
                    throw new IOException("policy asset escapes project root: " + path);
                }
                if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                    captured.add(context.captureDirectory(real, "asset:" + asset.getKey(), projectRoot));
                } else {
                    captured.add(context.capture(real, "asset:" + asset.getKey(),
                            MAX_CAPTURE_BYTES).captured);
                }
            }
            result.put(asset.getKey(), captured);
        }
        return result;
    }

    private static void resolveClosure(OWLOntologyManager manager, OWLOntology root,
            Map<String, Mapping> mappings, Path projectRoot, CaptureContext context,
            Map<Path, OWLOntology> loadedByDocument, OWLOntologyLoaderConfiguration configuration)
            throws IOException {
        Deque<OWLOntology> pending = new ArrayDeque<>();
        Set<OWLOntology> scanned = new HashSet<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            OWLOntology ontology = pending.removeFirst();
            if (!scanned.add(ontology)) {
                continue;
            }
            for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
                String importIri = declaration.getIRI().toString();
                Mapping mapping = mappings.get(importIri);
                if (mapping == null) {
                    throw new IOException("offline import has no local mapping: " + importIri);
                }
                if (!"file".equalsIgnoreCase(mapping.document.getScheme())) {
                    throw new IOException("offline import maps to a non-file document: " + importIri);
                }
                Path document = requireRegularFile(Path.of(mapping.document), projectRoot,
                        "import mapping");
                OWLOntology imported = loadedByDocument.get(document);
                if (imported == null) {
                    CapturedFile captured = context.capture(document, "import", MAX_CAPTURE_BYTES);
                    imported = load(manager, captured, configuration);
                    loadedByDocument.put(document, imported);
                }
                requireImportIdentity(importIri, imported.getOntologyID(), document);
                pending.add(imported);
            }
        }
    }

    private static OWLOntology load(OWLOntologyManager manager, CapturedFile source,
            OWLOntologyLoaderConfiguration configuration) throws IOException {
        try (InputStream input = Files.newInputStream(source.captured)) {
            StreamDocumentSource document = new StreamDocumentSource(input,
                    IRI.create(source.original.toUri()));
            return manager.loadOntologyFromOntologyDocument(document, configuration);
        } catch (OWLOntologyCreationException | RuntimeException error) {
            throw new IOException("could not load ontology snapshot " + source.original + ": "
                    + message(error), error);
        }
    }

    private static void requireRootIdentity(OWLOntology root, String expected) throws IOException {
        String actual = root.getOntologyID().getOntologyIRI().isPresent()
                ? root.getOntologyID().getOntologyIRI().get().toString() : null;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new IOException("root ontology IRI mismatch: expected " + expected + ", found " + actual);
        }
    }

    private static void requireImportIdentity(String requested, OWLOntologyID id, Path document)
            throws IOException {
        String ontology = id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : null;
        String version = id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : null;
        if (!requested.equals(ontology) && !requested.equals(version)) {
            throw new IOException("import " + requested + " resolved to " + document
                    + " whose ontology/version IRI is " + ontology + " / " + version);
        }
    }

    private static void requireResolvedImports(OWLOntology root) throws IOException {
        for (OWLOntology ontology : root.getImportsClosure()) {
            for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
                if (root.getOWLOntologyManager().getImportedOntology(declaration) == null) {
                    throw new IOException("import remained unresolved after offline capture: "
                            + declaration.getIRI());
                }
            }
        }
    }

    private static void verifyLockClosure(ImportLockFile.Document lock, OWLOntology root,
            CaptureContext context) throws IOException {
        Map<String, ImportLockFile.Entry> expected = lock.entriesByKey();
        Set<String> actual = new LinkedHashSet<>();
        Set<OWLOntology> direct = root.getImports();
        for (OWLOntology member : root.getImportsClosure()) {
            if (member.equals(root)) {
                continue;
            }
            OWLOntologyID id = member.getOntologyID();
            String ontology = id.getOntologyIRI().isPresent() ? id.getOntologyIRI().get().toString() : "";
            String version = id.getVersionIRI().isPresent() ? id.getVersionIRI().get().toString() : null;
            String key = version == null || version.isBlank() ? ontology : ontology + " @ " + version;
            actual.add(key);
            ImportLockFile.Entry entry = expected.get(key);
            if (entry == null) {
                continue;
            }
            IRI documentIri = member.getOWLOntologyManager().getOntologyDocumentIRI(member);
            Path document;
            try {
                document = Path.of(documentIri.toURI()).toRealPath();
            } catch (Exception invalid) {
                throw new IOException("loaded import is not backed by a local file: " + key, invalid);
            }
            Path locked = entry.absolute().toRealPath();
            CapturedFile captured = context.byOriginal.get(document);
            if (!document.equals(locked) || captured == null
                    || !captured.sha256.substring("sha256:".length()).equals(entry.sha256())
                    || direct.contains(member) != entry.direct()) {
                throw new IOException("import lock mismatch: " + key);
            }
        }
        Set<String> unlocked = new LinkedHashSet<>(actual);
        unlocked.removeAll(expected.keySet());
        Set<String> unloaded = new LinkedHashSet<>(expected.keySet());
        unloaded.removeAll(actual);
        if (!unlocked.isEmpty() || !unloaded.isEmpty()) {
            throw new IOException("import lock closure mismatch; loaded_without_lock=" + unlocked
                    + ", locked_but_not_loaded=" + unloaded);
        }
    }

    private static Path catalogFile(URI uri, Path projectRoot, String label) throws IOException {
        try {
            return requireRegularFile(Path.of(uri), projectRoot, label);
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException invalid) {
            throw new IOException(label + " is not a valid local file URI: " + uri, invalid);
        }
    }

    private static void putMapping(Map<String, Mapping> mappings, String ontologyIri,
            URI document, String source) throws IOException {
        if (ontologyIri == null || ontologyIri.isBlank()) {
            throw new IOException(source + " has a blank ontology IRI mapping");
        }
        Mapping next = new Mapping(document, source);
        Mapping prior = mappings.putIfAbsent(ontologyIri, next);
        if (prior != null && !prior.document.normalize().equals(document.normalize())) {
            throw new IOException("conflicting local mappings for " + ontologyIri + ": "
                    + prior.document + " and " + document);
        }
    }

    private static Path requireRegularFile(Path path, Path projectRoot, String label)
            throws IOException {
        Path real = path.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular file: " + path);
        }
        if (projectRoot != null && !real.startsWith(projectRoot)) {
            throw new IOException(label + " escapes project root: " + path);
        }
        return real;
    }

    private static void requireValidPolicy(ProjectPolicy policy) throws IOException {
        if (policy.loaded() && policy.valid()) {
            return;
        }
        List<String> errors = policy.issues().stream()
                .filter(issue -> "error".equals(issue.severity()))
                .map(PolicyIssue::code).distinct().toList();
        throw new IOException("project policy is not valid: "
                + (errors.isEmpty() ? "not loaded" : String.join(", ", errors)));
    }

    private static void requireImportLockBootstrapPolicy(ProjectPolicy policy) throws IOException {
        if (isImportLockBootstrapEligible(policy)) return;
        List<PolicyIssue> errors = policy == null ? List.of() : policy.issues().stream()
                .filter(issue -> "error".equals(issue.severity())).toList();
        throw new IOException("project policy is not valid for import-lock generation: "
                + (errors.isEmpty() ? "not evaluable" : errors.stream().map(PolicyIssue::code)
                        .distinct().collect(java.util.stream.Collectors.joining(", "))));
    }

    /** True only for a valid policy or the single missing-lock state this command can repair. */
    public static boolean isImportLockBootstrapEligible(ProjectPolicy policy) {
        if (policy == null || !policy.loaded()) return false;
        if (policy.valid()) return true;
        List<PolicyIssue> errors = policy.issues().stream()
                .filter(issue -> "error".equals(issue.severity())).toList();
        return !errors.isEmpty() && errors.stream().allMatch(issue ->
                "asset_missing".equals(issue.code()) && "imports.lockfile".equals(issue.path()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static FileIdentity identity(Path path) throws IOException {
        MessageDigest digest = digest();
        long bytes = 0;
        try (InputStream input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(path)), digest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes += read;
            }
        }
        return new FileIdentity(hex(digest.digest()), bytes);
    }

    static FileIdentity directoryIdentity(Path directory) throws IOException {
        Path root = directory.toRealPath();
        MessageDigest digest = digest();
        long totalBytes = 0;
        for (Path path : directoryEntries(root)) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("asset directory contains a symbolic link: " + path);
            }
            Path real = path.toRealPath();
            if (!real.startsWith(root)) {
                throw new IOException("directory contains an escaping symlink: " + path);
            }
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                digest.update(("D " + relative + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else if (Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                FileIdentity file = identity(real);
                totalBytes += file.bytes;
                digest.update(("F " + relative + " " + file.bytes + " " + file.sha256 + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                throw new IOException("directory contains a non-regular entry: " + path);
            }
        }
        return new FileIdentity(hex(digest.digest()), totalBytes);
    }

    private static List<Path> directoryEntries(Path root) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                if (entries.size() >= MAX_DIRECTORY_ENTRIES) {
                    throw new IOException("asset directory exceeds " + MAX_DIRECTORY_ENTRIES
                            + " entries: " + root);
                }
                entries.add(iterator.next());
            }
        }
        entries.sort(Comparator.comparing(Path::toString));
        return entries;
    }

    static FileIdentity pinnedIdentity(Path expected, boolean directory) throws IOException {
        Path real = expected.toRealPath();
        if (!real.equals(expected.toAbsolutePath().normalize())) {
            throw new IOException("workspace source path changed: " + expected);
        }
        if (directory ? !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("workspace source type changed: " + expected);
        }
        return directory ? directoryIdentity(real) : identity(real);
    }

    private static Path createPrivateTempDirectory() throws IOException {
        Path directory = Files.createTempDirectory("protege-mcp-workspace-");
        try {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // The platform does not expose POSIX permissions (for example Windows).
        }
        // Keep every captured path pinned to the canonical spelling. On macOS the temporary
        // directory API commonly returns /var while toRealPath() resolves it to /private/var.
        return directory.toRealPath();
    }

    private static Path defaultStateRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home is unavailable");
        }
        return Path.of(home).resolve(".protege-mcp").resolve("locks");
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder value = new StringBuilder("sha256:");
        for (byte item : digest) {
            value.append(Character.forDigit((item >>> 4) & 0xf, 16));
            value.append(Character.forDigit(item & 0xf, 16));
        }
        return value.toString();
    }

    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort for a failed private capture.
                }
            });
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    private record Mapping(URI document, String source) {
    }

    @FunctionalInterface
    interface CaptureHook {
        void run() throws IOException;
    }

    record FileIdentity(String sha256, long bytes) {
    }

    private static final class CapturedFile {
        final Path original;
        final Path captured;
        final String sha256;
        final long bytes;
        final String kind;

        CapturedFile(Path original, Path captured, String sha256, long bytes, String kind) {
            this.original = original;
            this.captured = captured;
            this.sha256 = sha256;
            this.bytes = bytes;
            this.kind = kind;
        }
    }

    private static final class CaptureContext {
        final Path root;
        final Map<Path, CapturedFile> byOriginal = new LinkedHashMap<>();
        final List<WorkspaceSource> directorySources = new ArrayList<>();
        int sequence;
        int storedFiles;
        long storedBytes;

        CaptureContext(Path root) {
            this.root = root;
        }

        CapturedFile capture(Path source, String kind, long maxBytes) throws IOException {
            Path original = source.toRealPath();
            CapturedFile existing = byOriginal.get(original);
            if (existing != null) {
                return existing;
            }
            if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("workspace source is not a regular file: " + source);
            }
            requireCapacity(0);
            String suffix = suffix(original);
            Path target = root.resolve(String.format("source-%05d%s", sequence++, suffix));
            MessageDigest digest = digest();
            long bytes = 0;
            try (InputStream input = new DigestInputStream(
                            new BufferedInputStream(Files.newInputStream(original)), digest);
                    OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes += read;
                    if (bytes > maxBytes) {
                        throw new IOException("workspace source exceeds " + maxBytes + " bytes: "
                                + original);
                    }
                    if (bytes > MAX_TEMP_BYTES - storedBytes) {
                        throw new IOException("workspace capture exceeds " + MAX_TEMP_BYTES
                                + " temporary bytes");
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException error) {
                Files.deleteIfExists(target);
                throw error;
            }
            CapturedFile captured = new CapturedFile(original, target,
                    hex(digest.digest()), bytes, kind);
            storedFiles++;
            storedBytes += bytes;
            byOriginal.put(original, captured);
            verify(captured);
            return captured;
        }

        Path captureDirectory(Path directory, String kind, Path projectRoot) throws IOException {
            Path real = directory.toRealPath();
            if (!real.startsWith(projectRoot)) {
                throw new IOException("asset directory escapes project root: " + directory);
            }
            Path targetRoot = root.resolve(String.format("asset-%05d", sequence++));
            Files.createDirectories(targetRoot);
            for (Path path : directoryEntries(real)) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("asset directory contains a symbolic link: " + path);
                }
                Path canonical = path.toRealPath();
                if (!canonical.startsWith(real)) {
                    throw new IOException("asset directory contains an escaping symlink: " + path);
                }
                Path relative = real.relativize(path);
                if (Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(targetRoot.resolve(relative));
                } else if (Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) {
                    CapturedFile file = capture(canonical, kind, MAX_CAPTURE_BYTES);
                    requireCapacity(file.bytes);
                    Path target = targetRoot.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(file.captured, target);
                    storedFiles++;
                    storedBytes += file.bytes;
                }
            }
            FileIdentity originalIdentity = directoryIdentity(real);
            FileIdentity capturedIdentity = directoryIdentity(targetRoot);
            if (!originalIdentity.equals(capturedIdentity)) {
                throw new IOException("asset directory changed during capture: " + directory);
            }
            directorySources.add(new WorkspaceSource("asset_directory:" + kind, real, targetRoot,
                    originalIdentity.sha256, originalIdentity.bytes));
            return targetRoot;
        }

        void verify(CapturedFile captured) throws IOException {
            Path current = captured.original.toRealPath();
            if (!current.equals(captured.original)
                    || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("workspace source path changed during capture: "
                        + captured.original);
            }
            FileIdentity identity = identity(current);
            if (identity.bytes != captured.bytes || !identity.sha256.equals(captured.sha256)) {
                throw new IOException("workspace source changed during capture: " + captured.original);
            }
            FileIdentity snapshotIdentity = pinnedIdentity(captured.captured, false);
            if (!snapshotIdentity.equals(identity)) {
                throw new IOException("captured workspace bytes changed: " + captured.captured);
            }
        }

        void verifyAll() throws IOException {
            for (CapturedFile captured : byOriginal.values()) {
                verify(captured);
            }
            for (WorkspaceSource directory : directorySources) {
                FileIdentity original = pinnedIdentity(directory.original(), true);
                FileIdentity captured = pinnedIdentity(directory.captured(), true);
                if (original.bytes != directory.bytes() || !original.sha256.equals(directory.sha256())
                        || !original.equals(captured)) {
                    throw new IOException("asset directory changed during capture: "
                            + directory.original());
                }
            }
        }

        List<WorkspaceSource> sources() {
            List<WorkspaceSource> result = new ArrayList<>(byOriginal.values().stream()
                    .map(file -> new WorkspaceSource(file.kind, file.original, file.captured,
                            file.sha256, file.bytes))
                    .toList());
            result.addAll(directorySources);
            return List.copyOf(result);
        }

        private void requireCapacity(long additionalBytes) throws IOException {
            if (storedFiles >= MAX_TEMP_FILES) {
                throw new IOException("workspace capture exceeds " + MAX_TEMP_FILES
                        + " temporary files");
            }
            if (additionalBytes < 0 || additionalBytes > MAX_TEMP_BYTES - storedBytes) {
                throw new IOException("workspace capture exceeds " + MAX_TEMP_BYTES
                        + " temporary bytes");
            }
        }

        private static String suffix(Path path) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return dot < 0 ? ".bin" : name.substring(dot);
        }
    }
}
