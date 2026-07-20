package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseBundleService;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseManifest;

/** Failure-atomic, checksum-guarded publication of one complete release directory. */
public final class WorkspaceBundleTransaction implements AutoCloseable {

    public static final int MAX_ARTIFACTS = 32;
    public static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    private final FilesystemProjectWorkspace workspace;
    private final WorkspaceSnapshot snapshot;
    private final Path projectRoot;
    private final Path target;
    private final boolean targetExisted;
    private final FilesystemProjectWorkspace.FileIdentity baseline;
    private final Guard guard;
    private final TransactionHook beforeReplace;
    private final AtomicMover mover;
    private Path staged;
    private FilesystemProjectWorkspace.FileIdentity stagedIdentity;
    private Path backup;
    private Commit commit;
    private State state = State.OPEN;

    WorkspaceBundleTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, Guard guard) throws IOException {
        this(workspace, snapshot, target, guard, () -> { }, WorkspaceBundleTransaction::atomicMove);
    }

    WorkspaceBundleTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, Guard guard, TransactionHook beforeReplace, AtomicMover mover)
            throws IOException {
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        this.guard = guard == null ? () -> { } : guard;
        this.beforeReplace = java.util.Objects.requireNonNull(beforeReplace, "beforeReplace");
        this.mover = java.util.Objects.requireNonNull(mover, "mover");
        if (!workspace.isCurrent(snapshot)) {
            throw new IOException("workspace sources changed before bundle transaction creation");
        }
        this.projectRoot = snapshot.policy().projectRoot().toRealPath();
        this.target = resolveTarget(target, projectRoot);
        this.targetExisted = Files.exists(this.target, LinkOption.NOFOLLOW_LINKS);
        this.baseline = targetExisted
                ? FilesystemProjectWorkspace.pinnedIdentity(this.target, true) : null;
        this.guard.verify();
    }

    public synchronized Stage stage(ReleaseBundleService.Bundle bundle) throws IOException {
        requireState(State.OPEN);
        if (bundle == null || bundle.artifacts().isEmpty()
                || bundle.artifacts().size() > MAX_ARTIFACTS) {
            throw new IllegalArgumentException("release bundle artifact count is invalid");
        }
        Path candidate = createSiblingDirectory("stage");
        try {
            Set<String> paths = new LinkedHashSet<>();
            for (ReleaseBundleService.Artifact artifact : bundle.artifacts()) {
                if (!paths.add(artifact.path())) {
                    throw new IOException("duplicate release artifact path: " + artifact.path());
                }
            }
            ReleaseBundleService.Artifact manifest = bundle.artifacts()
                    .get(bundle.artifacts().size() - 1);
            byte[] expectedManifest = ReleaseManifest.toJson(bundle.manifest())
                    .getBytes(StandardCharsets.UTF_8);
            if (!"manifest.json".equals(manifest.path())
                    || !Arrays.equals(expectedManifest, manifest.content())) {
                throw new IOException("release bundle must end with its matching manifest.json");
            }
            verifyManifestIndex(bundle);
            ArtifactStore store = new ArtifactStore(candidate);
            long total = 0;
            for (ReleaseBundleService.Artifact artifact : bundle.artifacts()) {
                total += artifact.bytes();
                if (total < 0 || total > MAX_TOTAL_BYTES) {
                    throw new IOException("release bundle exceeds " + MAX_TOTAL_BYTES + " bytes");
                }
                ArtifactStore.Written written = store.writeBytes(
                        artifact.path(), artifact.content());
                if (!written.sha256().equals(artifact.sha256())
                        || written.bytes() != artifact.bytes()) {
                    throw new IOException("staged release artifact identity mismatch: "
                            + artifact.path());
                }
                force(candidate.resolve(written.path()));
            }
            for (ReleaseBundleService.Artifact artifact : bundle.artifacts()) {
                Path file = candidate.resolve(artifact.path());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) != artifact.bytes()
                        || !ArtifactStore.sha256(file).equals(artifact.sha256())) {
                    throw new IOException("staged release artifact changed: " + artifact.path());
                }
            }
            forceTreeDirectories(candidate);
            staged = candidate.toRealPath();
            stagedIdentity = FilesystemProjectWorkspace.pinnedIdentity(staged, true);
            state = State.STAGED;
            return new Stage(target, bundle.artifacts().size(), total,
                    stagedIdentity.sha256());
        } catch (IOException | RuntimeException error) {
            deleteTree(candidate);
            throw error;
        }
    }

    public synchronized Commit commit() throws IOException {
        requireState(State.STAGED);
        boolean baselineMoved = false;
        try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                workspace.stateRoot(), projectRoot)) {
            verifyBeforeMove();
            beforeReplace.run();
            verifyBeforeMove();
            preserveTargetPermissions(staged);
            if (targetExisted) {
                backup = backupPath();
                try {
                    mover.move(target, backup);
                    baselineMoved = true;
                } catch (IOException | RuntimeException firstMoveFailure) {
                    if (baselineAtBackupAndTargetAbsent()) {
                        baselineMoved = true;
                        restoreBaselineAfterFailedCommit(firstMoveFailure);
                    }
                    throw firstMoveFailure;
                }
            }
            try {
                if (baselineMoved
                        && !baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(backup, true))) {
                    throw new IOException("published release backup does not match the baseline");
                }
                verifyAfterBaselineMove(baselineMoved);
                mover.move(staged, target);
                staged = null;
            } catch (IOException | RuntimeException failure) {
                if (installedStageExists()) {
                    staged = null;
                    commit = new Commit(target, targetExisted,
                            baseline == null ? null : baseline.sha256(), stagedIdentity.sha256(),
                            stagedIdentity.bytes(), backup);
                    state = State.COMMITTED;
                    throw new CommitAppliedException(commit, failure);
                }
                if (baselineMoved) restoreBaselineAfterFailedCommit(failure);
                throw failure;
            }
            commit = new Commit(target, targetExisted,
                    baseline == null ? null : baseline.sha256(), stagedIdentity.sha256(),
                    stagedIdentity.bytes(), backup);
            state = State.COMMITTED;
            try {
                if (!stagedIdentity.equals(
                        FilesystemProjectWorkspace.pinnedIdentity(target, true))) {
                    throw new IOException("installed release directory does not match the stage");
                }
            } catch (IOException | RuntimeException verificationFailure) {
                throw new CommitAppliedException(commit, verificationFailure);
            }
            forceDirectory(target.getParent());
            return commit;
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("filesystem does not support atomic release publication for "
                    + target, unsupported);
        } catch (IOException failure) {
            if (state == State.COMMITTED && commit != null
                    && !(failure instanceof CommitAppliedException)) {
                throw new CommitAppliedException(commit, failure);
            }
            throw failure;
        }
    }

    /** Restore the exact prior release, or remove a newly-created release, after identity checks. */
    public synchronized Recovery recover() throws IOException {
        requireState(State.COMMITTED);
        Path trash = createSiblingPath("recovery");
        try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                workspace.stateRoot(), projectRoot)) {
            if (!stagedIdentity.equals(FilesystemProjectWorkspace.pinnedIdentity(target, true))) {
                throw new IOException("published release changed after commit; recovery refused");
            }
            if (targetExisted
                    && !baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(backup, true))) {
                throw new IOException("release backup changed after commit; recovery refused");
            }
            try {
                mover.move(target, trash);
            } catch (IOException | RuntimeException firstMoveFailure) {
                if (!installedAtTrashAndTargetAbsent(trash)) throw firstMoveFailure;
            }
            try {
                if (targetExisted) mover.move(backup, target);
            } catch (IOException | RuntimeException failure) {
                try {
                    mover.move(trash, target);
                } catch (IOException | RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    throw new IOException("release recovery failed and the installed release was "
                            + "preserved for manual recovery at " + trash, failure);
                }
                throw failure;
            }
            if (targetExisted
                    && !baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(target, true))) {
                throw new IOException("restored release does not match the baseline; the prior "
                        + "installed release remains at " + trash);
            }
            deleteTree(trash);
            state = State.RECOVERED;
            forceDirectory(target.getParent());
            return new Recovery(target, targetExisted,
                    baseline == null ? null : baseline.sha256());
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("filesystem does not support atomic release recovery for "
                    + target, unsupported);
        }
    }

    public synchronized State state() {
        return state;
    }

    public Path target() {
        return target;
    }

    public synchronized Path stagedPath() {
        if (state != State.STAGED || staged == null) {
            throw new IllegalStateException("bundle transaction has no staged directory");
        }
        return staged;
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) return;
        if (staged != null) {
            deleteTree(staged);
            staged = null;
        }
        state = State.CLOSED;
    }

    private void verifyBeforeMove() throws IOException {
        verifySourcesAndStage();
        if (targetExisted) {
            if (!baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(target, true))) {
                throw new IOException("release output changed; commit refused");
            }
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("release output was created concurrently; commit refused");
        }
    }

    private void verifyAfterBaselineMove(boolean baselineMoved) throws IOException {
        verifySourcesAndStage();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("release output appeared during commit; commit refused");
        }
        if (baselineMoved
                && !baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(backup, true))) {
            throw new IOException("release backup changed during commit");
        }
    }

    private void verifySourcesAndStage() throws IOException {
        if (!workspace.isCurrent(snapshot)) {
            throw new IOException("workspace source checksum changed; release commit refused");
        }
        guard.verify();
        if (!stagedIdentity.equals(FilesystemProjectWorkspace.pinnedIdentity(staged, true))) {
            throw new IOException("staged release bundle changed; commit refused");
        }
    }

    private boolean installedStageExists() {
        try {
            return Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && stagedIdentity.equals(FilesystemProjectWorkspace.pinnedIdentity(target, true));
        } catch (IOException | RuntimeException absentOrDifferent) {
            return false;
        }
    }

    private boolean baselineAtBackupAndTargetAbsent() {
        try {
            return !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(backup, LinkOption.NOFOLLOW_LINKS)
                    && baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(backup, true));
        } catch (IOException | RuntimeException absentOrDifferent) {
            return false;
        }
    }

    private boolean installedAtTrashAndTargetAbsent(Path trash) {
        try {
            return !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)
                    && stagedIdentity.equals(FilesystemProjectWorkspace.pinnedIdentity(trash, true));
        } catch (IOException | RuntimeException absentOrDifferent) {
            return false;
        }
    }

    private static void verifyManifestIndex(ReleaseBundleService.Bundle bundle) throws IOException {
        Object raw = bundle.manifest().get("artifacts");
        if (!(raw instanceof java.util.List<?> rows)
                || rows.size() != bundle.artifacts().size() - 1) {
            throw new IOException("release manifest artifact index is incomplete");
        }
        for (int index = 0; index < rows.size(); index++) {
            if (!(rows.get(index) instanceof java.util.Map<?, ?> row)) {
                throw new IOException("release manifest artifact entry must be an object");
            }
            ReleaseBundleService.Artifact artifact = bundle.artifacts().get(index);
            Object bytes = row.get("bytes");
            if (!artifact.path().equals(row.get("path"))
                    || !artifact.sha256().equals(row.get("sha256"))
                    || !(bytes instanceof Number number)
                    || number.longValue() != artifact.bytes()) {
                throw new IOException("release manifest does not match staged artifact: "
                        + artifact.path());
            }
        }
    }

    private void restoreBaselineAfterFailedCommit(Throwable original) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            original.addSuppressed(new IOException(
                    "release output unexpectedly exists; automatic baseline restoration refused; "
                            + "the verified backup remains at " + backup));
            return;
        }
        Path recoveryBackup = backup;
        try {
            mover.move(recoveryBackup, target);
        } catch (IOException | RuntimeException restoreFailure) {
            if (baselineAtTargetAndBackupAbsent(recoveryBackup)) {
                backup = null;
                return;
            }
            original.addSuppressed(restoreFailure);
            throw new IOException("release commit failed and baseline restoration also failed; "
                    + "the prior release backup may remain at " + recoveryBackup, original);
        }
        backup = null;
        if (!baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(target, true))) {
            throw new IOException("automatic baseline restoration moved content back to " + target
                    + " but its identity did not match; inspect that target before retrying", original);
        }
    }

    private boolean baselineAtTargetAndBackupAbsent(Path recoveryBackup) {
        try {
            return !Files.exists(recoveryBackup, LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                    && baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(target, true));
        } catch (IOException | RuntimeException absentOrDifferent) {
            return false;
        }
    }

    private Path createSiblingDirectory(String purpose) throws IOException {
        Path created = Files.createTempDirectory(target.getParent(),
                "." + target.getFileName() + ".protege-mcp-" + purpose + "-");
        setOwnerOnly(created, true);
        return created.toRealPath();
    }

    private Path createSiblingPath(String purpose) {
        return target.getParent().resolve("." + target.getFileName() + ".protege-mcp-"
                + purpose + "-" + UUID.randomUUID());
    }

    private Path backupPath() {
        return target.getParent().resolve(".protege-mcp-backup-"
                + baseline.sha256().substring("sha256:".length()) + "-" + UUID.randomUUID());
    }

    static Path resolveTarget(Path requested, Path projectRoot) throws IOException {
        if (requested == null) throw new IllegalArgumentException("target must not be null");
        Path normalized = requested.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)) {
                throw new IOException("release output must not be a symbolic link");
            }
            Path real = normalized.toRealPath();
            if (real.equals(projectRoot) || !real.startsWith(projectRoot)
                    || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("release output is outside the project or not a directory");
            }
            FilesystemProjectWorkspace.directoryIdentity(real);
            return real;
        }
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("release output has no parent directory");
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(projectRoot)) {
            throw new IOException("release output escapes the project");
        }
        Path target = realParent.resolve(normalized.getFileName());
        if (target.equals(projectRoot)) throw new IOException("release output cannot be project root");
        return target;
    }

    private void preserveTargetPermissions(Path source) {
        if (!targetExisted) return;
        try {
            Files.setPosixFilePermissions(source, Files.getPosixFilePermissions(target));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Keep the restrictive staging-directory permissions.
        }
    }

    private static void setOwnerOnly(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions
                    .fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // The platform does not expose POSIX permissions.
        }
    }

    private static void forceTreeDirectories(Path root) {
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isDirectory).sorted(Comparator.reverseOrder())
                    .forEach(WorkspaceBundleTransaction::forceDirectory);
        } catch (IOException ignored) {
            // File fsyncs remain authoritative when directory fsync is unsupported.
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not portable; atomic rename is still the visibility boundary.
        }
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort for a transaction-owned tree.
                }
            });
        } catch (IOException ignored) {
            // Already absent or best-effort cleanup failed.
        }
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("bundle transaction state is " + state
                    + ", expected " + expected);
        }
    }

    public enum State { OPEN, STAGED, COMMITTED, RECOVERED, CLOSED }

    public record Stage(Path target, int artifacts, long bytes, String treeSha256) { }

    public record Commit(Path target, boolean previousExisted, String previousTreeSha256,
            String installedTreeSha256, long installedBytes, Path backupPath) { }

    public record Recovery(Path target, boolean restored, String restoredTreeSha256) { }

    public static final class CommitAppliedException extends IOException {
        private final Commit commit;

        CommitAppliedException(Commit commit, Throwable cause) {
            super("release publication completed but post-install verification failed: "
                    + (cause.getMessage() == null ? cause.getClass().getSimpleName()
                            : cause.getMessage()), cause);
            this.commit = commit;
        }

        public Commit commit() { return commit; }
    }

    @FunctionalInterface
    public interface Guard { void verify() throws IOException; }

    @FunctionalInterface
    interface TransactionHook { void run() throws IOException; }

    @FunctionalInterface
    interface AtomicMover { void move(Path source, Path destination) throws IOException; }
}
