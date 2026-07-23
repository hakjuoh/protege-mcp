package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/**
 * Checksum-guarded, project-confined single-file transaction. Candidate bytes are staged beside the
 * target, installed through guarded same-filesystem hard-link publication, and may be restored from a
 * verified backup. Existing targets are displaced into an owner-only private directory before the
 * no-overwrite publication step.
 */
public final class WorkspaceTransaction implements AutoCloseable {

    public static final long MAX_STAGED_BYTES = 512L * 1024 * 1024;

    private final FilesystemProjectWorkspace workspace;
    private final WorkspaceSnapshot snapshot;
    private final Path projectRoot;
    private final Path target;
    private final Path targetName;
    private final SecureTargetAnchor anchor;
    private final boolean backupRequested;
    private final boolean targetExisted;
    private final FilesystemProjectWorkspace.FileIdentity baseline;
    private final long baselineMaximumBytes;
    private final Set<PosixFilePermission> baselinePermissions;
    private final TransactionHook beforeReplace;
    private final TransactionHook beforeGuardedDisplace;
    private final TransactionHook afterGuardedDisplace;
    private final AtomicMover mover;
    private final boolean secureMoves;
    private Path staged;
    private Path stagedName;
    private final List<Path> targetSnapshots = new ArrayList<>();
    private FilesystemProjectWorkspace.FileIdentity stagedIdentity;
    private Commit commit;
    private State state = State.OPEN;

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup) throws IOException {
        this(workspace, snapshot, target, backup, MAX_STAGED_BYTES,
                () -> { }, () -> { }, null);
    }

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup, long baselineMaximumBytes) throws IOException {
        this(workspace, snapshot, target, backup, baselineMaximumBytes,
                () -> { }, () -> { }, null);
    }

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup, TransactionHook beforeReplace, AtomicMover mover)
            throws IOException {
        this(workspace, snapshot, target, backup, MAX_STAGED_BYTES,
                () -> { }, beforeReplace, mover);
    }

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup, long baselineMaximumBytes,
            TransactionHook beforeReplace, AtomicMover mover) throws IOException {
        this(workspace, snapshot, target, backup, baselineMaximumBytes,
                () -> { }, beforeReplace, mover);
    }

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup, long baselineMaximumBytes,
            TransactionHook beforeAnchorOpen, TransactionHook beforeReplace,
            AtomicMover mover) throws IOException {
        this(workspace, snapshot, target, backup, baselineMaximumBytes,
                beforeAnchorOpen, beforeReplace, () -> { }, () -> { }, mover);
    }

    WorkspaceTransaction(FilesystemProjectWorkspace workspace, WorkspaceSnapshot snapshot,
            Path target, boolean backup, long baselineMaximumBytes,
            TransactionHook beforeAnchorOpen, TransactionHook beforeReplace,
            TransactionHook beforeGuardedDisplace, TransactionHook afterGuardedDisplace,
            AtomicMover mover) throws IOException {
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        java.util.Objects.requireNonNull(beforeAnchorOpen, "beforeAnchorOpen");
        this.beforeReplace = java.util.Objects.requireNonNull(beforeReplace, "beforeReplace");
        this.beforeGuardedDisplace = java.util.Objects.requireNonNull(
                beforeGuardedDisplace, "beforeGuardedDisplace");
        this.afterGuardedDisplace = java.util.Objects.requireNonNull(
                afterGuardedDisplace, "afterGuardedDisplace");
        this.mover = mover;
        this.secureMoves = mover == null;
        if (baselineMaximumBytes < 1 || baselineMaximumBytes > MAX_STAGED_BYTES) {
            throw new IllegalArgumentException("baselineMaximumBytes is outside transaction bounds");
        }
        this.baselineMaximumBytes = baselineMaximumBytes;
        if (!workspace.sourcesCurrent(snapshot) || !snapshot.projectRootCurrent()) {
            throw new IOException("workspace sources changed before transaction creation");
        }
        this.projectRoot = snapshot.projectRootPath();
        beforeAnchorOpen.run();
        SecureTargetAnchor opened = SecureTargetAnchor.open(projectRoot, target);
        boolean exists;
        FilesystemProjectWorkspace.FileIdentity identity;
        Set<PosixFilePermission> permissions;
        try {
            exists = opened.exists(opened.targetName());
            identity = exists ? opened.identity(
                    opened.targetName(), baselineMaximumBytes) : null;
            permissions = exists ? opened.permissions(opened.targetName()) : null;
            if (!workspace.sourcesCurrent(snapshot) || !snapshot.projectRootCurrent()) {
                throw new IOException("workspace sources changed while transaction was pinned");
            }
        } catch (SecureTargetAnchor.SizeLimitExceededException exceeded) {
            opened.close();
            throw new ExistingTargetSizeException(exceeded.maximumBytes(), exceeded);
        } catch (IOException | RuntimeException failure) {
            opened.close();
            throw failure;
        }
        this.anchor = opened;
        this.target = opened.target();
        this.targetName = opened.targetName();
        this.targetExisted = exists;
        this.baseline = identity;
        this.baselinePermissions = permissions;
        this.backupRequested = backup;
    }

    public Path target() {
        return target;
    }

    /** Immutable target-existence observation captured after symlink/confinement checks. */
    public boolean targetExisted() {
        return targetExisted;
    }

    /** SHA-256 of the securely pinned target, or null when the target did not exist. */
    public String baselineSha256() {
        return baseline == null ? null : baseline.sha256();
    }

    public synchronized State state() {
        return state;
    }

    /** Copy the pinned target through the anchored directory handle for safe parsing. */
    public synchronized Path snapshotTarget(long maximumBytes) throws IOException {
        requireState(State.OPEN);
        if (!targetExisted) throw new IOException("transaction target does not exist");
        Path copied = anchor.snapshot(targetName, baseline, maximumBytes);
        targetSnapshots.add(copied);
        return copied;
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
        SecureTargetAnchor.CreatedFile candidate =
                anchor.createSibling(targetName.toString(), "stage");
        Path candidateName = candidate.name();
        try {
            try (candidate;
                    OutputStream raw = java.nio.channels.Channels.newOutputStream(
                            candidate.channel());
                    BoundedOutputStream bounded = new BoundedOutputStream(
                            new BufferedOutputStream(raw), MAX_STAGED_BYTES)) {
                writer.write(bounded);
                bounded.flush();
                candidate.force();
            }
            FilesystemProjectWorkspace.FileIdentity identity =
                    anchor.identity(candidateName, MAX_STAGED_BYTES);
            staged = candidate.displayPath();
            stagedName = candidateName;
            stagedIdentity = identity;
            state = State.STAGED;
            return new Stage(target, identity.sha256(), identity.bytes());
        } catch (IOException | RuntimeException error) {
            deleteOwnedTemp(candidateName, error);
            throw error;
        }
    }

    /** Recheck the captured source and target baseline without staging or mutating a file. */
    public synchronized void verifyBaseline() throws IOException {
        requireState(State.OPEN);
        try (WorkspaceProjectLock.Handle ignored = acquireLock()) {
            verifySourceAndTarget();
        }
    }

    /** Install the staged file after rechecking the complete source snapshot and target baseline. */
    public synchronized Commit commit() throws IOException {
        requireState(State.STAGED);
        Path backupTempName = null;
        Path backupPath = null;
        Path backupName = null;
        FilesystemProjectWorkspace.FileIdentity backupIdentity = null;
        boolean backupApplied = false;
        try (WorkspaceProjectLock.Handle lock = acquireLock()) {
            SecureTargetAnchor.RecoverySweepReceipt recovery;
            try {
                recovery = anchor.recoverOrphanedTransactionsUnderLock(lock, lockPath());
            } catch (SecureTargetAnchor.AmbiguousRecoveryException ambiguous) {
                throw ambiguousRecovery(ambiguous);
            } catch (SecureTargetAnchor.OrphanRecoveryAppliedException applied) {
                throw orphanRecoveryApplied(applied.receipt(), applied);
            }
            if (recovery.mutationApplied()) {
                throw orphanRecoveryApplied(recovery, null);
            }
            verifyPreconditions();
            if (backupRequested && targetExisted) {
                backupPath = backupPath();
                try (SecureTargetAnchor.CreatedFile created =
                        anchor.createSibling(targetName.toString(), "backup")) {
                    backupTempName = created.name();
                    anchor.copy(targetName, created, baselineMaximumBytes);
                }
                backupIdentity = anchor.identity(backupTempName, baselineMaximumBytes);
                if (!baseline.equals(backupIdentity)) {
                    throw new IOException("backup copy does not match the transaction baseline");
                }
            }

            beforeReplace.run();
            verifyPreconditions();
            if (backupTempName != null) {
                backupName = backupPath.getFileName();
                if (anchor.exists(backupName)) {
                    backupIdentity = anchor.identity(backupName, baselineMaximumBytes);
                    if (!baseline.equals(backupIdentity)) {
                        throw new IOException(
                                "existing immutable backup does not match the transaction baseline");
                    }
                    anchor.deleteIfExists(backupTempName);
                    backupTempName = null;
                } else {
                    try {
                        if (secureMoves) {
                            anchor.publishOwnedIfAbsent(backupTempName, backupName,
                                    backupIdentity, baselineMaximumBytes);
                        } else {
                            move(backupTempName, backupName,
                                    target.getParent().resolve(backupTempName), backupPath);
                        }
                        backupApplied = true;
                        backupTempName = null;
                    } catch (SecureTargetAnchor.MutationAppliedException applied) {
                        backupApplied = true;
                        backupTempName = null;
                        throw applied;
                    }
                    backupIdentity = anchor.identity(backupName, baselineMaximumBytes);
                }
                if (!baseline.equals(backupIdentity)) {
                    throw new IOException("published backup does not match the transaction baseline");
                }
                verifyPreconditions();
            }

            anchor.setPermissions(stagedName, baselinePermissions);
            Commit proposed = new Commit(target, targetExisted,
                    baseline == null ? null : baseline.sha256(), stagedIdentity.sha256(),
                    stagedIdentity.bytes(), backupPath,
                    backupIdentity == null ? null : backupIdentity.sha256());
            try {
                if (secureMoves) {
                    anchor.replaceGuarded(stagedName, stagedIdentity, targetName,
                            baseline, MAX_STAGED_BYTES,
                            beforeGuardedDisplace::run, afterGuardedDisplace::run);
                } else {
                    move(stagedName, targetName, staged, target);
                }
            } catch (SecureTargetAnchor.GuardedReplaceException guarded) {
                if (guarded.receipt().sourceMoved()) {
                    staged = null;
                    stagedName = null;
                }
                if (guarded.receipt().publicationVerified()) {
                    commit = proposed;
                    state = State.COMMITTED;
                    throw new CommitAppliedException(commit, guarded);
                }
                state = State.PARTIAL;
                throw guardedReplacement(guarded, backupApplied, backupPath);
            } catch (SecureTargetAnchor.MutationAppliedException applied) {
                staged = null;
                stagedName = null;
                commit = proposed;
                state = State.COMMITTED;
                throw new CommitAppliedException(commit, applied);
            }
            staged = null;
            stagedName = null;
            commit = proposed;
            state = State.COMMITTED;
            try {
                FilesystemProjectWorkspace.FileIdentity installed =
                        anchor.identity(targetName, MAX_STAGED_BYTES);
                if (!stagedIdentity.equals(installed)) {
                    throw new IOException("installed target does not match the staged artifact");
                }
            } catch (IOException | RuntimeException verificationFailure) {
                throw new CommitAppliedException(commit, verificationFailure);
            }
            return commit;
        } catch (AtomicMoveNotSupportedException unsupported) {
            IOException failure = new IOException(
                    "filesystem does not support atomic replacement for " + target
                            + "; the previous target was preserved", unsupported);
            if (backupApplied && state != State.COMMITTED) {
                throw backupApplied(backupPath, backupName, failure);
            }
            throw failure;
        } catch (IOException failure) {
            if (state == State.COMMITTED && commit != null
                    && !(failure instanceof CommitAppliedException)) {
                throw new CommitAppliedException(commit, failure);
            }
            if (backupApplied && !(failure instanceof BackupAppliedException)
                    && !(failure instanceof CommitAppliedException)
                    && !(failure instanceof GuardedReplacementException)) {
                throw backupApplied(backupPath, backupName, failure);
            }
            throw failure;
        } finally {
            if (backupTempName != null) deleteOwnedTemp(backupTempName, null);
        }
    }

    /**
     * Restore this transaction's verified baseline. Recovery refuses to overwrite any post-commit edit
     * and, for a newly-created target, atomically moves only the exact installed artifact to a private
     * retained sibling. Retention avoids the pathname replacement race of a non-portable unlink.
     */
    public synchronized Recovery recover() throws IOException {
        requireState(State.COMMITTED);
        if (!backupRequested) {
            throw new IllegalStateException("transaction did not request a recovery backup");
        }
        requirePolicySourceCurrent("before recovery");
        Path recoveryTempName = null;
        try (WorkspaceProjectLock.Handle ignored = acquireLock()) {
            requirePolicySourceCurrent("after acquiring the recovery lock");
            FilesystemProjectWorkspace.FileIdentity current =
                    anchor.identity(targetName, baselineMaximumBytes);
            if (!commit.installedSha256().equals(current.sha256())) {
                throw new IOException("target changed after commit; recovery refused");
            }
            if (!targetExisted) {
                requirePolicySourceCurrent("before quarantining the recovered target");
                Path retainedArtifact;
                try {
                    retainedArtifact = anchor.quarantineIfIdentity(
                            targetName, stagedIdentity, baselineMaximumBytes);
                } catch (SecureTargetAnchor.GuardedReplaceException applied) {
                    throw new RecoveryAppliedException(recoverySideEffect(
                            target, false, null, applied.receipt()), applied);
                } catch (SecureTargetAnchor.MutationAppliedException applied) {
                    throw new RecoveryAppliedException(
                            new RecoverySideEffect(target, false, null), applied);
                }
                state = State.RECOVERED;
                return new Recovery(target, false, null, retainedArtifact);
            }

            Path backupPath = commit.backupPath();
            Path backupName = backupPath.getFileName();
            FilesystemProjectWorkspace.FileIdentity backup =
                    anchor.identity(backupName, baselineMaximumBytes);
            if (!baseline.equals(backup)
                    || !commit.backupSha256().equals(backup.sha256())) {
                throw new IOException("backup changed after commit; recovery refused");
            }
            try (SecureTargetAnchor.CreatedFile created =
                    anchor.createSibling(targetName.toString(), "recovery")) {
                recoveryTempName = created.name();
                anchor.copy(backupName, created, baselineMaximumBytes);
            }
            if (!baseline.equals(anchor.identity(recoveryTempName, baselineMaximumBytes))) {
                throw new IOException("recovery copy does not match the verified backup");
            }
            current = anchor.identity(targetName, baselineMaximumBytes);
            backup = anchor.identity(backupName, baselineMaximumBytes);
            if (!commit.installedSha256().equals(current.sha256()) || !baseline.equals(backup)) {
                throw new IOException("target or backup changed during recovery");
            }
            requirePolicySourceCurrent("before restoring the recovery backup");
            anchor.setPermissions(recoveryTempName, baselinePermissions);
            boolean recoveryApplied = false;
            try {
                if (secureMoves) {
                    anchor.replaceGuarded(recoveryTempName, baseline, targetName,
                            current, baselineMaximumBytes);
                } else {
                    move(recoveryTempName, targetName,
                            target.getParent().resolve(recoveryTempName), target);
                }
                recoveryApplied = true;
                recoveryTempName = null;
                FilesystemProjectWorkspace.FileIdentity restored =
                        anchor.identity(targetName, baselineMaximumBytes);
                if (!baseline.equals(restored)) {
                    throw new IOException(
                            "restored target does not match the transaction baseline");
                }
                state = State.RECOVERED;
                return new Recovery(target, true, restored.sha256(), null);
            } catch (SecureTargetAnchor.GuardedReplaceException applied) {
                if (applied.receipt().sourceMoved()) recoveryTempName = null;
                throw new RecoveryAppliedException(recoverySideEffect(
                        target, true, baseline.sha256(), applied.receipt()), applied);
            } catch (SecureTargetAnchor.MutationAppliedException applied) {
                recoveryTempName = null;
                throw new RecoveryAppliedException(new RecoverySideEffect(
                        target, true, baseline.sha256()), applied);
            } catch (IOException verificationFailure) {
                if (recoveryApplied) {
                    throw new RecoveryAppliedException(new RecoverySideEffect(
                            target, true, baseline.sha256()), verificationFailure);
                }
                throw verificationFailure;
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("filesystem does not support atomic recovery for " + target,
                    unsupported);
        } finally {
            if (recoveryTempName != null) deleteOwnedTemp(recoveryTempName, null);
        }
    }

    private void requirePolicySourceCurrent(String phase) throws IOException {
        if (!snapshot.policySourceCurrent()) {
            throw new IOException("project policy identity changed " + phase + "; recovery refused");
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
                anchor.deleteIfExists(stagedName);
            } catch (IOException ignored) {
                // Best effort for an owner-only uncommitted staging file.
            }
            staged = null;
            stagedName = null;
        }
        for (Path copied : targetSnapshots) {
            try {
                Files.deleteIfExists(copied);
            } catch (IOException ignored) {
                // Best effort for private read snapshots.
            }
        }
        targetSnapshots.clear();
        try {
            anchor.close();
        } catch (IOException ignored) {
            // The transaction state is already closed.
        }
        state = State.CLOSED;
    }

    private void verifyPreconditions() throws IOException {
        verifySourceAndTarget();
        if (!stagedIdentity.equals(anchor.identity(stagedName, MAX_STAGED_BYTES))) {
            throw new IOException("staged artifact changed; commit refused");
        }
    }

    private void verifySourceAndTarget() throws IOException {
        if (!workspace.sourcesCurrent(snapshot)) {
            throw new IOException("workspace source checksum changed; commit refused");
        }
        if (targetExisted) {
            if (!baseline.equals(anchor.identity(targetName, baselineMaximumBytes))) {
                throw new IOException("target checksum changed; commit refused");
            }
        } else if (anchor.exists(targetName)) {
            throw new IOException("target was created concurrently; commit refused");
        }
    }

    private WorkspaceProjectLock.Handle acquireLock() throws IOException {
        return WorkspaceProjectLock.acquire(workspace.stateRoot(), projectRoot);
    }

    Path lockPath() throws IOException {
        return WorkspaceProjectLock.path(workspace.stateRoot(), projectRoot);
    }

    private Path backupPath() {
        String coordinate = target + "\u0000" + baseline.sha256();
        String key = ArtifactStore.sha256(coordinate.getBytes(StandardCharsets.UTF_8))
                .substring("sha256:".length());
        return target.getParent().resolve(".protege-mcp-backup-" + key + ".bak");
    }

    private void move(Path sourceName, Path destinationName,
            Path sourceDisplay, Path destinationDisplay) throws IOException {
        if (secureMoves) anchor.move(sourceName, destinationName);
        else mover.move(sourceDisplay, destinationDisplay);
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("transaction state is " + state + ", expected " + expected);
        }
    }

    private void deleteOwnedTemp(Path name, Throwable primary) {
        try {
            anchor.deleteIfExists(name);
        } catch (IOException cleanupFailure) {
            if (primary != null) primary.addSuppressed(cleanupFailure);
        }
    }

    private BackupAppliedException backupApplied(Path backupPath, Path backupName,
            Throwable cause) {
        boolean locationCurrent = false;
        boolean backupStateKnown = false;
        boolean backupVerified = false;
        String backupSha256 = null;
        boolean targetStateKnown = false;
        boolean targetPreserved = false;
        try {
            anchor.verifyAttached();
            locationCurrent = true;
            try {
                FilesystemProjectWorkspace.FileIdentity observed =
                        anchor.identity(backupName, baselineMaximumBytes);
                backupStateKnown = true;
                backupSha256 = observed.sha256();
                backupVerified = baseline.equals(observed);
            } catch (SecureTargetAnchor.SizeLimitExceededException exceeded) {
                backupStateKnown = true;
            } catch (IOException unavailable) {
                cause.addSuppressed(unavailable);
            }
            try {
                if (targetExisted) {
                    targetPreserved = baseline.equals(
                            anchor.identity(targetName, baselineMaximumBytes));
                } else {
                    targetPreserved = !anchor.exists(targetName);
                }
                targetStateKnown = true;
            } catch (SecureTargetAnchor.SizeLimitExceededException changed) {
                targetStateKnown = true;
            } catch (IOException unavailable) {
                cause.addSuppressed(unavailable);
            }
        } catch (IOException detached) {
            cause.addSuppressed(detached);
        }
        return new BackupAppliedException(new BackupSideEffect(
                backupPath, locationCurrent, backupStateKnown, backupVerified,
                backupSha256, targetStateKnown, targetPreserved), cause);
    }

    private GuardedReplacementException guardedReplacement(
            SecureTargetAnchor.GuardedReplaceException failure, boolean backupPublished,
            Path backupPath) {
        SecureTargetAnchor.GuardedReplaceReceipt receipt = failure.receipt();
        boolean backupStateKnown = false;
        boolean backupVerified = false;
        String backupSha256 = null;
        if (backupPath != null && receipt.locationCurrent()) {
            try {
                anchor.verifyAttached();
                FilesystemProjectWorkspace.FileIdentity observed = anchor.identity(
                        backupPath.getFileName(), baselineMaximumBytes);
                backupStateKnown = true;
                backupVerified = baseline.equals(observed);
                backupSha256 = observed.sha256();
            } catch (SecureTargetAnchor.SizeLimitExceededException changed) {
                backupStateKnown = true;
            } catch (IOException unavailable) {
                failure.addSuppressed(unavailable);
            }
        }
        return new GuardedReplacementException(new GuardedReplacementSideEffect(
                target, receipt.locationCurrent(), receipt.sourceMoved(),
                receipt.stagedStateKnown(), receipt.stagedRetained(), receipt.stagedSha256(),
                receipt.originalMoved(), receipt.displacedPath(),
                receipt.displacedStateKnown(), receipt.displacedSha256(),
                receipt.displacedMatched(),
                receipt.targetStateKnown(), receipt.targetPresent(),
                receipt.targetSha256(), receipt.publicationApplied(),
                receipt.publicationVerified(), receipt.retainedStagePath(),
                receipt.intendedSha256(), backupPath, backupPublished,
                backupStateKnown, backupVerified, backupSha256), failure);
    }

    static OrphanRecoveryAppliedException orphanRecoveryApplied(
            SecureTargetAnchor.RecoverySweepReceipt receipt, Throwable cause) {
        return new OrphanRecoveryAppliedException(new OrphanRecoverySideEffect(
                receipt.recoveryStateKnown(), receipt.locationCurrent(),
                receipt.targetStateKnown(),
                receipt.targetPresent(), receipt.targetSha256(),
                receipt.targetRestored(), receipt.restoredSha256(),
                receipt.directoriesCleaned()), cause);
    }

    static AmbiguousRecoveryException ambiguousRecovery(
            SecureTargetAnchor.AmbiguousRecoveryException failure) {
        return new AmbiguousRecoveryException(new AmbiguousRecoverySideEffect(
                failure.evidencePaths().size(), failure.evidencePaths()), failure);
    }

    private static RecoverySideEffect recoverySideEffect(Path target,
            boolean expectedPresent, String expectedSha256,
            SecureTargetAnchor.GuardedReplaceReceipt receipt) {
        return new RecoverySideEffect(target, expectedPresent, expectedSha256,
                receipt.targetStateKnown(), receipt.targetPresent(),
                receipt.targetSha256(), receipt.displacedPath(),
                receipt.displacedSha256(), receipt.publicationApplied(),
                receipt.publicationVerified());
    }

    public enum State {
        OPEN, STAGED, PARTIAL, COMMITTED, RECOVERED, CLOSED
    }

    public record Stage(Path target, String sha256, long bytes) {
    }

    public record Commit(Path target, boolean previousExisted, String previousSha256,
            String installedSha256, long installedBytes, Path backupPath, String backupSha256) {
    }

    /** Recovery result; retainedArtifact is the exact new target moved aside instead of unlinked. */
    public record Recovery(Path target, boolean restored, String restoredSha256,
            Path retainedArtifact) {
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

    /** Backup publication completed; the receipt states which post-mutation facts were verified. */
    public static final class BackupAppliedException extends IOException {
        private final BackupSideEffect receipt;

        public BackupAppliedException(BackupSideEffect receipt, Throwable cause) {
            super("backup publication completed before target replacement failed: "
                    + (cause.getMessage() == null
                            ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
            this.receipt = receipt;
        }

        public BackupSideEffect receipt() {
            return receipt;
        }

        public Path backupPath() {
            return receipt.backupPath();
        }

        public String backupSha256() {
            return receipt.backupSha256();
        }
    }

    public record BackupSideEffect(Path backupPath, boolean locationCurrent,
            boolean backupStateKnown, boolean backupVerified, String backupSha256,
            boolean targetStateKnown, boolean targetPreserved) {
    }

    /** Locked orphan recovery changed public or private transaction state; retry after inspection. */
    public static final class OrphanRecoveryAppliedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final OrphanRecoverySideEffect receipt;

        public OrphanRecoveryAppliedException(
                OrphanRecoverySideEffect receipt, Throwable cause) {
            super("locked orphan recovery changed transaction state; retry is required", cause);
            this.receipt = receipt;
        }

        public OrphanRecoverySideEffect receipt() {
            return receipt;
        }
    }

    public record OrphanRecoverySideEffect(boolean recoveryStateKnown,
            boolean locationCurrent,
            boolean targetStateKnown, boolean targetPresent, String targetSha256,
            boolean targetRestored, String restoredSha256, int directoriesCleaned) { }

    /** Recovery evidence is ambiguous and requires an offline operator decision. */
    public static final class AmbiguousRecoveryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final AmbiguousRecoverySideEffect receipt;

        public AmbiguousRecoveryException(
                AmbiguousRecoverySideEffect receipt, Throwable cause) {
            super("workspace recovery evidence requires manual intervention", cause);
            this.receipt = receipt;
        }

        public AmbiguousRecoverySideEffect receipt() {
            return receipt;
        }
    }

    public record AmbiguousRecoverySideEffect(int evidenceCount,
            List<Path> evidencePaths) {
        public AmbiguousRecoverySideEffect {
            evidencePaths = List.copyOf(evidencePaths);
        }
    }

    /** Guarded target publication failed after private transaction state may have changed. */
    public static final class GuardedReplacementException extends IOException {
        private final GuardedReplacementSideEffect receipt;

        public GuardedReplacementException(
                GuardedReplacementSideEffect receipt, Throwable cause) {
            super("guarded target publication did not complete", cause);
            this.receipt = receipt;
        }

        public GuardedReplacementSideEffect receipt() {
            return receipt;
        }
    }

    public record GuardedReplacementSideEffect(Path target, boolean locationCurrent,
            boolean sourceMoved, boolean stagedStateKnown, boolean stagedRetained,
            String stagedSha256, boolean originalMoved, Path displacedPath,
            boolean displacedStateKnown, String displacedSha256, boolean displacedMatched,
            boolean targetStateKnown, boolean targetPresent, String targetSha256,
            boolean publicationApplied, boolean publicationVerified,
            Path retainedStagePath, String intendedSha256,
            Path backupPath, boolean backupPublished, boolean backupStateKnown,
            boolean backupVerified, String backupSha256) { }

    /** Recovery changed the anchored target, but its final pathname state could not be proved. */
    public static final class RecoveryAppliedException extends IOException {
        private final RecoverySideEffect receipt;

        RecoveryAppliedException(RecoverySideEffect receipt, Throwable cause) {
            super("recovery mutation completed but final target verification failed: "
                    + (cause.getMessage() == null
                            ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
            this.receipt = receipt;
        }

        public RecoverySideEffect receipt() {
            return receipt;
        }
    }

    public record RecoverySideEffect(Path target, boolean expectedPresent,
            String expectedSha256, boolean targetStateKnown, boolean targetPresent,
            String targetSha256, Path retainedPath, String retainedSha256,
            boolean publicationApplied, boolean publicationVerified) {
        RecoverySideEffect(Path target, boolean expectedPresent, String expectedSha256) {
            this(target, expectedPresent, expectedSha256,
                    false, false, null, null, null, true, false);
        }
    }

    /** The pre-existing destination exceeded the request-specific read/hash bound. */
    public static final class ExistingTargetSizeException extends IOException {
        private final long maximumBytes;

        ExistingTargetSizeException(long maximumBytes, Throwable cause) {
            super("existing transaction target exceeds " + maximumBytes + " bytes", cause);
            this.maximumBytes = maximumBytes;
        }

        public long maximumBytes() {
            return maximumBytes;
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
