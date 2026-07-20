package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.core.qc.ImportGraphAnalysisService;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Deterministic, no-network import-lock generation with checksum-guarded installation. */
public final class ImportLockService {

    private ImportLockService() {
    }

    public record Result(boolean written, boolean dryRun, String path, String sha256,
            int entryCount, long bytes, String backupPath, byte[] content) {
        public Result {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public static Result generate(FilesystemProjectWorkspace workspace, Path output,
            boolean dryRun) throws IOException {
        if (workspace == null) throw new IllegalArgumentException("workspace must not be null");
        try (WorkspaceSnapshot snapshot = workspace.captureForImportLock()) {
            ProjectPolicy policy = snapshot.policy();
            Path target = resolveTarget(policy, output);
            List<ImportLockFile.Entry> entries = entries(snapshot, target);
            byte[] content = ImportLockFile.render(entries);
            // Parse our own candidate before it can become externally visible.
            ImportLockFile.parse(content, target);
            if (!workspace.isCurrent(snapshot)) {
                throw new IOException("workspace sources changed while generating the import lock");
            }
            String digest = ArtifactStore.sha256(content);
            if (dryRun) {
                return new Result(false, true, portable(policy, target), digest,
                        entries.size(), content.length, null, content);
            }
            try (WorkspaceTransaction transaction = workspace.beginTransaction(
                    snapshot, target, true)) {
                transaction.stageBytes(content);
                WorkspaceTransaction.Commit commit = transaction.commit();
                return new Result(true, false, portable(policy, target), digest,
                        entries.size(), content.length,
                        commit.backupPath() == null ? null
                                : portable(policy, commit.backupPath()), content);
            }
        }
    }

    private static Path resolveTarget(ProjectPolicy policy, Path output) throws IOException {
        Path root = policy.projectRoot().toRealPath();
        Path target = output;
        if (target == null) {
            Map<String, Object> imports = object(policy.effective().get("imports"));
            String configured = string(imports.get("lockfile"));
            target = configured == null ? root.resolve("imports.lock.json")
                    : root.resolve(configured);
        } else if (!target.isAbsolute()) {
            target = root.resolve(target);
        }
        target = target.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException(
                    "import lock output must stay inside the project root");
        }
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException(
                    "import lock output directory does not exist: " + parent);
        }
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(root)) {
            throw new IllegalArgumentException(
                    "import lock output directory escapes the project root");
        }
        return realParent.resolve(target.getFileName());
    }

    private static List<ImportLockFile.Entry> entries(WorkspaceSnapshot snapshot, Path target)
            throws IOException {
        ImportGraphAnalysisService.Report report = ImportGraphAnalysisService.analyze(snapshot.root());
        List<String> errors = new ArrayList<>();
        if (!report.missingImports().isEmpty()) errors.add("one or more imports are unresolved");
        if (!report.conflicts().isEmpty()) {
            errors.add("loaded import identity/version/document conflicts exist");
        }
        Path base = target.getParent().toRealPath();
        Map<Path, WorkspaceSource> sources = new LinkedHashMap<>();
        for (WorkspaceSource source : snapshot.sources()) {
            sources.put(source.original(), source);
        }
        Map<String, ImportLockFile.Entry> byKey = new LinkedHashMap<>();
        for (Map<String, Object> row : report.resolvedImports()) {
            String ontologyIri = string(row.get("target_ontology_iri"));
            String versionIri = string(row.get("target_version_iri"));
            String documentIri = string(row.get("target_document_iri"));
            Path document = localFile(documentIri);
            if (document == null) {
                errors.add("import is not backed by a local file: " + documentIri);
                continue;
            }
            Path real = document.toRealPath();
            WorkspaceSource source = sources.get(real);
            if (source == null) {
                errors.add("import document was not captured by the workspace: " + real);
                continue;
            }
            if (ontologyIri == null || !URI.create(ontologyIri).isAbsolute()) {
                errors.add("import has no absolute ontology IRI to lock: " + documentIri);
                continue;
            }
            if (!real.startsWith(base)) {
                errors.add("import document is outside the lock directory and cannot be locked "
                        + "relatively: " + real);
                continue;
            }
            String relative = base.relativize(real).toString().replace('\\', '/');
            ImportLockFile.Entry entry = new ImportLockFile.Entry(ontologyIri, versionIri,
                    relative, source.sha256().substring("sha256:".length()),
                    Boolean.TRUE.equals(row.get("direct")), real);
            ImportLockFile.Entry prior = byKey.get(entry.key());
            if (prior == null) byKey.put(entry.key(), entry);
            else if (!prior.document().equals(entry.document())
                    || !prior.sha256().equals(entry.sha256())) {
                errors.add("import identity resolves to multiple documents: " + entry.key());
            } else if (!prior.direct() && entry.direct()) {
                byKey.put(entry.key(), new ImportLockFile.Entry(entry.ontologyIri(),
                        entry.versionIri(), entry.document(), entry.sha256(), true, real));
            }
        }
        if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
        return byKey.values().stream().sorted(Comparator.comparing(ImportLockFile.Entry::key)).toList();
    }

    private static Path localFile(String documentIri) {
        if (documentIri == null) return null;
        try {
            URI uri = URI.create(documentIri);
            return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String portable(ProjectPolicy policy, Path path) {
        Path root = policy.projectRoot().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        return root.relativize(absolute).toString().replace('\\', '/');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String string(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}
