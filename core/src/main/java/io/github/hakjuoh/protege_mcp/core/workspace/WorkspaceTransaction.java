package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/**
 * Checksum-guarded, project-confined single-file transaction. Candidate bytes are staged beside the
 * target, installed only with a same-filesystem atomic move, and may be restored from a verified backup.
 */
public final class WorkspaceTransaction implements AutoCloseable {

    public static final long MAX_STAGED_BYTES = 512L * 1024 * 1024;

    private final FilesystemProjectWorkspace workspace;
    private final WorkspaceSnapshot snapshot;
    private final Path projectRoot;
    private final Path target;
    private final boolean backupRequested;
    private final boolean targetExisted;
    private final FilesystemProjectWorkspace.FileIdentity baseline;
    private final TransactionHook beforeReplace;
    private final AtomicMover mover;
    private Path staged;
    private FilesystemProjectWorkspace.FileIdentity stagedIdentity;
    private Commit commit;
    private State state = State.OPEN;

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup) throws IOException {
        this(workspace, snapshot, target, backup, () -> { }, WorkspaceTransaction::atomicMove);
    }

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup, TransactionHook beforeReplace, AtomicMover mover)
            throws IOException {
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        this.beforeReplace = java.util.Objects.requireNonNull(beforeReplace, "beforeReplace");
        this.mover = java.util.Objects.requireNonNull(mover, "mover");
        if (!workspace.sourcesCurrent(snapshot)) {
            throw new IOException("workspace sources changed before transaction creation");
        }
        this.projectRoot = snapshot.policy().projectRoot().toRealPath();
        this.target = resolveTarget(target, projectRoot);
        this.targetExisted = Files.exists(this.target, LinkOption.NOFOLLOW_LINKS);
        this.baseline = targetExisted
                ? FilesystemProjectWorkspace.pinnedIdentity(this.target, false) : null;
        this.backupRequested = backup;
    }

    public Path target() {
        return target;
    }

    public synchronized State state() {
        return state;
    }

    /** Path of the private candidate for validators that need to inspect staged bytes. */
    public synchronized Path stagedPath() {
        if (state != State.STAGED || staged == null) {
            throw new IllegalStateException("transaction has no staged artifact");
        }
        return staged;
    }

    /** Stage immutable candidate bytes in a restrictive sibling file. */
    public synchronized Stage stageBytes(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        return stage(output -> output.write(bytes));
    }

    /**
     * Stream one candidate into the bounded sibling staging file. A rejected candidate is discarded by
     * closing this transaction; iterative generation starts a fresh transaction and baseline check.
     */
    public synchronized Stage stage(Stager writer) throws IOException {
        requireState(State.OPEN);
        java.util.Objects.requireNonNull(writer, "writer");
        Path candidate = createSibling("stage");
        try {
            try (FileChannel channel = FileChannel.open(candidate,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    OutputStream raw = java.nio.channels.Channels.newOutputStream(channel);
                    BoundedOutputStream bounded = new BoundedOutputStream(
                            new BufferedOutputStream(raw), MAX_STAGED_BYTES)) {
                writer.write(bounded);
                bounded.flush();
                channel.force(true);
            }
            Path canonical = candidate.toRealPath();
            FilesystemProjectWorkspace.FileIdentity identity =
                    FilesystemProjectWorkspace.pinnedIdentity(canonical, false);
            staged = canonical;
            stagedIdentity = identity;
            state = State.STAGED;
            return new Stage(target, identity.sha256(), identity.bytes());
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(candidate);
            throw error;
        }
    }

    /** Install the staged file after rechecking the complete source snapshot and target baseline. */
    public synchronized Commit commit() throws IOException {
        requireState(State.STAGED);
        Path backupTemp = null;
        Path backupPath = null;
        FilesystemProjectWorkspace.FileIdentity backupIdentity = null;
        try (WorkspaceProjectLock.Handle ignored = acquireLock()) {
            verifyPreconditions();
            if (backupRequested && targetExisted) {
                backupPath = backupPath();
                backupTemp = createSibling("backup");
                Files.copy(target, backupTemp, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                force(backupTemp);
                backupTemp = backupTemp.toRealPath();
                backupIdentity = FilesystemProjectWorkspace.pinnedIdentity(backupTemp, false);
                if (!baseline.equals(backupIdentity)) {
                    throw new IOException("backup copy does not match the transaction baseline");
                }
            }

            beforeReplace.run();
            verifyPreconditions();
            preserveTargetPermissions(staged);

            if (backupTemp != null) {
                mover.move(backupTemp, backupPath);
                backupTemp = null;
                backupIdentity = FilesystemProjectWorkspace.pinnedIdentity(
                        backupPath.toRealPath(), false);
                if (!baseline.equals(backupIdentity)) {
                    throw new IOException("published backup does not match the transaction baseline");
                }
                verifyPreconditions();
            }

            Path installedSource = staged;
            mover.move(installedSource, target);
            staged = null;
            commit = new Commit(target, targetExisted,
                    baseline == null ? null : baseline.sha256(), stagedIdentity.sha256(),
                    stagedIdentity.bytes(), backupPath,
                    backupIdentity == null ? null : backupIdentity.sha256());
            state = State.COMMITTED;
            try {
                FilesystemProjectWorkspace.FileIdentity installed =
                        FilesystemProjectWorkspace.pinnedIdentity(target.toRealPath(), false);
                if (!stagedIdentity.equals(installed)) {
                    throw new IOException("installed target does not match the staged artifact");
                }
            } catch (IOException | RuntimeException verificationFailure) {
                throw new CommitAppliedException(commit, verificationFailure);
            }
            forceDirectory(target.getParent());
            return commit;
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("filesystem does not support atomic replacement for " + target
                    + "; the previous target was preserved", unsupported);
        } catch (IOException failure) {
            if (state == State.COMMITTED && commit != null
                    && !(failure instanceof CommitAppliedException)) {
                throw new CommitAppliedException(commit, failure);
            }
            throw failure;
        } finally {
            if (backupTemp != null) {
                Files.deleteIfExists(backupTemp);
            }
        }
    }

    /**
     * Restore this transaction's verified baseline. Recovery refuses to overwrite any post-commit edit
     * and, for a newly-created target, removes only the exact artifact installed by this transaction.
     */
    public synchronized Recovery recover() throws IOException {
        requireState(State.COMMITTED);
        if (!backupRequested) {
            throw new IllegalStateException("transaction did not request a recovery backup");
        }
        Path recoveryTemp = null;
        try (WorkspaceProjectLock.Handle ignored = acquireLock()) {
            FilesystemProjectWorkspace.FileIdentity current =
                    FilesystemProjectWorkspace.pinnedIdentity(target, false);
            if (!commit.installedSha256().equals(current.sha256())) {
                throw new IOException("target changed after commit; recovery refused");
            }
            if (!targetExisted) {
                Files.delete(target);
                forceDirectory(target.getParent());
                state = State.RECOVERED;
                return new Recovery(target, false, null);
            }

            Path backupPath = commit.backupPath();
            FilesystemProjectWorkspace.FileIdentity backup =
                    FilesystemProjectWorkspace.pinnedIdentity(backupPath, false);
            if (!baseline.equals(backup)
                    || !commit.backupSha256().equals(backup.sha256())) {
                throw new IOException("backup changed after commit; recovery refused");
            }
            recoveryTemp = createSibling("recovery");
            Files.copy(backupPath, recoveryTemp, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            force(recoveryTemp);
            recoveryTemp = recoveryTemp.toRealPath();
            if (!baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(recoveryTemp, false))) {
                throw new IOException("recovery copy does not match the verified backup");
            }
            current = FilesystemProjectWorkspace.pinnedIdentity(target, false);
            backup = FilesystemProjectWorkspace.pinnedIdentity(backupPath, false);
            if (!commit.installedSha256().equals(current.sha256()) || !baseline.equals(backup)) {
                throw new IOException("target or backup changed during recovery");
            }
            mover.move(recoveryTemp, target);
            recoveryTemp = null;
            FilesystemProjectWorkspace.FileIdentity restored =
                    FilesystemProjectWorkspace.pinnedIdentity(target.toRealPath(), false);
            if (!baseline.equals(restored)) {
                throw new IOException("restored target does not match the transaction baseline");
            }
            forceDirectory(target.getParent());
            state = State.RECOVERED;
            return new Recovery(target, true, restored.sha256());
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("filesystem does not support atomic recovery for " + target,
                    unsupported);
        } finally {
            if (recoveryTemp != null) {
                Files.deleteIfExists(recoveryTemp);
            }
        }
    }

    /**
     * Delete an uncommitted stage and end this in-process recovery handle. A committed transaction must
     * call {@link #recover()} before close when immediate rollback is desired; its backup remains on disk.
     */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        if (staged != null) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // Best effort for an owner-only uncommitted staging file.
            }
            staged = null;
        }
        state = State.CLOSED;
    }

    private void verifyPreconditions() throws IOException {
        if (!workspace.sourcesCurrent(snapshot)) {
            throw new IOException("workspace source checksum changed; commit refused");
        }
        if (targetExisted) {
            if (!baseline.equals(FilesystemProjectWorkspace.pinnedIdentity(target, false))) {
                throw new IOException("target checksum changed; commit refused");
            }
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("target was created concurrently; commit refused");
        }
        if (!stagedIdentity.equals(FilesystemProjectWorkspace.pinnedIdentity(staged, false))) {
            throw new IOException("staged artifact changed; commit refused");
        }
    }

    private WorkspaceProjectLock.Handle acquireLock() throws IOException {
        return WorkspaceProjectLock.acquire(workspace.stateRoot(), projectRoot);
    }

    Path lockPath() throws IOException {
        return WorkspaceProjectLock.path(workspace.stateRoot(), projectRoot);
    }

    private Path createSibling(String purpose) throws IOException {
        Path created = Files.createTempFile(target.getParent(),
                "." + target.getFileName() + ".protege-mcp-" + purpose + "-", ".tmp");
        setOwnerOnly(created, false);
        return created.toRealPath();
    }

    private Path backupPath() {
        String coordinate = target + "\u0000" + baseline.sha256();
        String key = ArtifactStore.sha256(coordinate.getBytes(StandardCharsets.UTF_8))
                .substring("sha256:".length());
        return target.getParent().resolve(".protege-mcp-backup-" + key + ".bak");
    }

    private static Path resolveTarget(Path requested, Path projectRoot) throws IOException {
        if (requested == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        Path normalized = requested.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)) {
                throw new IOException("transaction target must not be a symbolic link: " + normalized);
            }
            Path real = normalized.toRealPath();
            if (!real.startsWith(projectRoot)
                    || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("transaction target is outside the project or not a regular file: "
                        + normalized);
            }
            return real;
        }
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("transaction target has no parent directory: " + normalized);
        }
        Path realParent = parent.toRealPath();
        if (!realParent.startsWith(projectRoot)) {
            throw new IOException("transaction target escapes the project: " + normalized);
        }
        return realParent.resolve(normalized.getFileName());
    }

    private void preserveTargetPermissions(Path source) {
        if (!targetExisted) {
            return;
        }
        try {
            Files.setPosixFilePermissions(source, Files.getPosixFilePermissions(target));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Keep the restrictive staging-file default.
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

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not portable; the atomic rename remains the visibility boundary.
        }
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("transaction state is " + state + ", expected " + expected);
        }
    }

    public enum State {
        OPEN, STAGED, COMMITTED, RECOVERED, CLOSED
    }

    public record Stage(Path target, String sha256, long bytes) {
    }

    public record Commit(Path target, boolean previousExisted, String previousSha256,
            String installedSha256, long installedBytes, Path backupPath, String backupSha256) {
    }

    public record Recovery(Path target, boolean restored, String restoredSha256) {
    }

    /** Replacement completed, but a racing writer prevented proof that the installed bytes survived. */
    public static final class CommitAppliedException extends IOException {
        private final Commit commit;

        CommitAppliedException(Commit commit, Throwable cause) {
            super("target replacement completed but post-install verification failed: "
                    + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()),
                    cause);
            this.commit = commit;
        }

        public Commit commit() {
            return commit;
        }
    }

    @FunctionalInterface
    public interface Stager {
        void write(OutputStream output) throws IOException;
    }

    @FunctionalInterface
    interface TransactionHook {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path destination) throws IOException;
    }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        BoundedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            out.write(bytes, offset, length);
            written += length;
        }

        private void requireCapacity(int additional) throws IOException {
            if (additional < 0 || additional > limit - written) {
                throw new IOException("staged artifact exceeds " + limit + " bytes");
            }
        }
    }
}
