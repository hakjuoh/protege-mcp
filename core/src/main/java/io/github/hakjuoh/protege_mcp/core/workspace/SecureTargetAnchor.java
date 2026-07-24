package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;

/**
 * Directory-handle-relative file operations anchored beneath one canonical project root.
 *
 * <p>When the provider lacks {@link SecureDirectoryStream}, the fallback is enabled only for a
 * POSIX path whose complete ancestor chain has stable non-null file keys and is not writable by
 * group or other principals. That makes pathname operations exclusive to an equivalent local OS
 * authority. A sticky shared ancestor such as {@code /tmp} is allowed only above an already
 * non-shared-writable project directory. Direct targets in a shared directory and unsupported
 * providers fail closed.
 */
final class SecureTargetAnchor implements AutoCloseable {
    private static final int MAX_RETAINED_TRANSACTION_DIRECTORIES = 32;
    private static final Path TRANSACTION_MANIFEST = Path.of("transaction-manifest-v1");
    private static final Path PUBLICATION_UNCERTAIN = Path.of("publication-uncertain");
    private static final Path PUBLICATION_COMPLETED = Path.of("publication-completed");
    private static final Path RECOVERY_UNCERTAIN = Path.of("recovery-uncertain");
    private static final Path RECOVERY_COMPLETED = Path.of("recovery-completed");
    private static final Path COMPLETED_RECOVERY = Path.of("completed-recovery");
    private final Path projectRoot;
    private final SecureDirectoryStream<Path> rootDirectory;
    private final SecureDirectoryStream<Path> directory;
    private final Path displayDirectory;
    private final Path relativeParent;
    private final Object parentFileKey;
    private final Path targetName;
    private final List<DirectoryIdentity> lexicalDirectories;

    private SecureTargetAnchor(Path projectRoot, SecureDirectoryStream<Path> rootDirectory,
            SecureDirectoryStream<Path> directory, Path displayDirectory,
            Path relativeParent, Object parentFileKey, Path targetName,
            List<DirectoryIdentity> lexicalDirectories) {
        this.projectRoot = projectRoot;
        this.rootDirectory = rootDirectory;
        this.directory = directory;
        this.displayDirectory = displayDirectory;
        this.relativeParent = relativeParent;
        this.parentFileKey = parentFileKey;
        this.targetName = targetName;
        this.lexicalDirectories = List.copyOf(lexicalDirectories);
    }

    static SecureTargetAnchor open(Path projectRoot, Path requested) throws IOException {
        return open(projectRoot, requested, true);
    }

    static SecureTargetAnchor openFallbackForTest(Path projectRoot, Path requested)
            throws IOException {
        return open(projectRoot, requested, false);
    }

    private static SecureTargetAnchor open(Path projectRoot, Path requested,
            boolean secureHandles) throws IOException {
        if (projectRoot == null || requested == null) {
            throw new IllegalArgumentException("project root and target are required");
        }
        Path root = projectRoot.toRealPath();
        Path requestedPath = requested.toAbsolutePath().normalize();
        Path requestedParent = requestedPath.getParent();
        Path parent = requestedParent == null ? null : requestedParent.toRealPath();
        Path normalized = parent == null ? requestedPath
                : parent.resolve(requestedPath.getFileName());
        if (parent == null || !normalized.startsWith(root)) {
            throw new IOException("transaction target is outside the project: " + normalized);
        }
        Path relativeParent = root.relativize(parent);
        List<DirectoryIdentity> lexicalDirectories = captureDirectoryChain(root, parent);
        SecureDirectoryStream<Path> rootHandle = secureHandles ? secureDirectory(root) : null;
        if (rootHandle == null) {
            requireExclusiveFallbackPath(parent);
            Object parentKey = lexicalDirectories.get(
                    lexicalDirectories.size() - 1).fileKey();
            return new SecureTargetAnchor(root, null, null, parent,
                    relativeParent, parentKey, normalized.getFileName(), lexicalDirectories);
        }
        SecureDirectoryStream<Path> current = rootHandle;
        boolean complete = false;
        try {
            if (!relativeParent.toString().isEmpty()) {
                for (Path component : relativeParent) {
                    SecureDirectoryStream<Path> next = current.newDirectoryStream(
                            Path.of(component.toString()), LinkOption.NOFOLLOW_LINKS);
                    if (current != rootHandle) current.close();
                    current = next;
                }
            }
            Object parentFileKey = attributes(current).fileKey();
            complete = true;
            return new SecureTargetAnchor(root, rootHandle, current, parent,
                    relativeParent, parentFileKey, normalized.getFileName(),
                    lexicalDirectories);
        } finally {
            if (!complete) {
                if (current != rootHandle) current.close();
                rootHandle.close();
            }
        }
    }

    Path target() {
        return displayDirectory.resolve(targetName);
    }

    Path targetName() {
        return targetName;
    }

    boolean exists(Path name) throws IOException {
        requireAttached();
        try {
            attributes(name);
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    FilesystemProjectWorkspace.FileIdentity identity(Path name, long maximumBytes)
            throws IOException {
        return identity(name, maximumBytes, () -> { });
    }

    FilesystemProjectWorkspace.FileIdentity identity(Path name, long maximumBytes,
            ReadHook beforeOpen) throws IOException {
        requireAttached();
        if (maximumBytes < 0) throw new IllegalArgumentException("maximumBytes is negative");
        java.util.Objects.requireNonNull(beforeOpen, "beforeOpen");
        BasicFileAttributes before = attributes(name);
        if (before.isSymbolicLink()) {
            throw new IOException("transaction target must not be a symbolic link: " + name);
        }
        if (!before.isRegularFile() || before.fileKey() == null) {
            throw new IOException("transaction entry is not a regular file: " + name);
        }
        if (before.size() > maximumBytes) {
            throw new SizeLimitExceededException(maximumBytes, name);
        }
        beforeOpen.run();
        MessageDigest digest = digest();
        long total = 0;
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        try (SeekableByteChannel channel = openRead(name)) {
            for (int read; (read = channel.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > maximumBytes) {
                    throw new SizeLimitExceededException(maximumBytes, name);
                }
                digest.update(buffer.array(), 0, read);
                buffer.clear();
            }
        }
        requireAttached();
        BasicFileAttributes after = attributes(name);
        if (!after.isRegularFile() || after.isSymbolicLink()
                || !before.fileKey().equals(after.fileKey()) || after.size() != total) {
            throw new IOException("transaction entry changed during identity capture: " + name);
        }
        return new FilesystemProjectWorkspace.FileIdentity(hex(digest.digest()), total);
    }

    Path snapshot(Path name, FilesystemProjectWorkspace.FileIdentity expected,
            long maximumBytes) throws IOException {
        requireAttached();
        if (expected == null) throw new IllegalArgumentException("expected identity is required");
        BasicFileAttributes currentAttributes = attributes(name);
        if (currentAttributes.isSymbolicLink() || !currentAttributes.isRegularFile()) {
            throw new IOException("transaction target must not be a symbolic link or non-regular file");
        }
        if (expected.bytes() > maximumBytes) {
            throw new IOException("transaction target exceeds " + maximumBytes + " bytes");
        }
        Path snapshot = Files.createTempFile("protege-mcp-target-", ".snapshot").toRealPath();
        setOwnerOnlyPath(snapshot);
        try {
            MessageDigest digest = digest();
            long total = 0;
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            try (SeekableByteChannel input = openRead(name);
                    FileChannel output = FileChannel.open(snapshot,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read == 0) continue;
                    total += read;
                    if (total > maximumBytes) {
                        throw new IOException("transaction target exceeds " + maximumBytes
                                + " bytes");
                    }
                    digest.update(buffer.array(), 0, read);
                    buffer.flip();
                    while (buffer.hasRemaining()) output.write(buffer);
                    buffer.clear();
                }
                output.force(true);
            }
            FilesystemProjectWorkspace.FileIdentity copied =
                    new FilesystemProjectWorkspace.FileIdentity(hex(digest.digest()), total);
            if (!expected.equals(copied)
                    || !expected.equals(identity(name, maximumBytes))) {
                throw new IOException("transaction target changed during secure snapshot");
            }
            return snapshot;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(snapshot);
            throw failure;
        }
    }

    CreatedFile createSibling(String targetLabel, String purpose) throws IOException {
        requireAttached();
        if (directory == null) {
            Path createdPath = Files.createTempFile(displayDirectory,
                    "." + targetLabel + ".protege-mcp-" + purpose + "-", ".tmp");
            Path real = createdPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            requireAttached();
            if (!real.getParent().equals(displayDirectory)) {
                Files.deleteIfExists(real);
                throw new IOException("transaction sibling escaped its authorized directory");
            }
            setOwnerOnlyPath(real);
            CreatedFile created = new CreatedFile(real.getFileName(), real,
                    FileChannel.open(real, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS));
            try {
                requireAttached();
                return created;
            } catch (IOException failure) {
                created.close();
                Files.deleteIfExists(real);
                throw failure;
            }
        }
        for (int attempt = 0; attempt < 32; attempt++) {
            Path name = Path.of("." + targetLabel + ".protege-mcp-" + purpose + "-"
                    + UUID.randomUUID() + ".tmp");
            try {
                SeekableByteChannel channel = directory.newByteChannel(name,
                        writeOptions());
                CreatedFile created = new CreatedFile(
                        name, displayDirectory.resolve(name), channel);
                try {
                    setOwnerOnly(name);
                    requireAttached();
                    return created;
                } catch (IOException | RuntimeException failure) {
                    created.close();
                    try {
                        directory.deleteFile(name);
                    } catch (IOException suppressed) {
                        failure.addSuppressed(suppressed);
                    }
                    throw failure;
                }
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // Retry with a new unguessable sibling name.
            }
        }
        throw new IOException("could not create a private transaction sibling");
    }

    void copy(Path source, CreatedFile destination, long maximumBytes) throws IOException {
        requireAttached();
        try (SeekableByteChannel input = openRead(source)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long total = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("transaction copy exceeds " + maximumBytes + " bytes");
                }
                buffer.flip();
                while (buffer.hasRemaining()) destination.channel().write(buffer);
                buffer.clear();
            }
            destination.force();
            requireAttached();
        }
    }

    void move(Path source, Path destination) throws IOException {
        requireAttached();
        boolean applied = false;
        try {
            if (directory != null) {
                directory.move(source, directory, destination);
            } else {
                Files.move(displayDirectory.resolve(source), displayDirectory.resolve(destination),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            applied = true;
            requireAttached();
            forceDirectory();
        } catch (IOException failure) {
            if (applied) throw new MutationAppliedException(failure);
            throw failure;
        }
    }

    void publishOwnedIfAbsent(Path source, Path destination,
            FilesystemProjectWorkspace.FileIdentity expected, long maximumBytes)
            throws IOException {
        requireAttached();
        requireExclusiveFallbackPath(displayDirectory);
        boolean applied = false;
        try {
            Files.createLink(displayDirectory.resolve(destination),
                    displayDirectory.resolve(source));
            applied = true;
            requireAttached();
            if (!expected.equals(identity(source, maximumBytes))
                    || !expected.equals(identity(destination, maximumBytes))) {
                throw new IOException("published hard link does not match the owned source");
            }
            deleteIfExists(source);
            forceDirectory();
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("filesystem does not support guarded hard-link publication",
                    unsupported);
        } catch (IOException failure) {
            if (applied) throw new MutationAppliedException(failure);
            throw failure;
        }
    }

    /**
     * Replaces a target without ever publishing through replacement rename semantics.
     * Existing content is first moved under an owner-only transaction directory, then the staged
     * inode is published with an atomic hard-link CREATE_NEW operation. Providers that cannot
     * supply this fail-closed primitive are rejected.
     */
    GuardedReplaceReceipt replaceGuarded(Path source,
            FilesystemProjectWorkspace.FileIdentity sourceExpected, Path destination,
            FilesystemProjectWorkspace.FileIdentity destinationExpected, long maximumBytes)
            throws IOException {
        return replaceGuarded(source, sourceExpected, destination, destinationExpected,
                maximumBytes, () -> { }, () -> { });
    }

    GuardedReplaceReceipt replaceGuarded(Path source,
            FilesystemProjectWorkspace.FileIdentity sourceExpected, Path destination,
            FilesystemProjectWorkspace.FileIdentity destinationExpected, long maximumBytes,
            ReadHook beforeDisplace, ReadHook afterDisplace) throws IOException {
        return replaceGuarded(source, sourceExpected, destination, destinationExpected,
                maximumBytes, beforeDisplace, afterDisplace,
                () -> { }, () -> { }, () -> { });
    }

    GuardedReplaceReceipt replaceGuarded(Path source,
            FilesystemProjectWorkspace.FileIdentity sourceExpected, Path destination,
            FilesystemProjectWorkspace.FileIdentity destinationExpected, long maximumBytes,
            ReadHook beforeDisplace, ReadHook afterDisplace,
            ReadHook afterStageMutation, ReadHook afterDisplaceMutation,
            ReadHook afterPublicationMutation) throws IOException {
        return replaceGuarded(source, sourceExpected, destination, destinationExpected,
                maximumBytes, beforeDisplace, afterDisplace,
                afterStageMutation, afterDisplaceMutation, afterPublicationMutation,
                () -> { });
    }

    GuardedReplaceReceipt replaceGuarded(Path source,
            FilesystemProjectWorkspace.FileIdentity sourceExpected, Path destination,
            FilesystemProjectWorkspace.FileIdentity destinationExpected, long maximumBytes,
            ReadHook beforeDisplace, ReadHook afterDisplace,
            ReadHook afterStageMutation, ReadHook afterDisplaceMutation,
            ReadHook afterPublicationMutation, ReadHook afterPrivateDirectoryCreate)
            throws IOException {
        java.util.Objects.requireNonNull(sourceExpected, "sourceExpected");
        java.util.Objects.requireNonNull(beforeDisplace, "beforeDisplace");
        java.util.Objects.requireNonNull(afterDisplace, "afterDisplace");
        java.util.Objects.requireNonNull(afterStageMutation, "afterStageMutation");
        java.util.Objects.requireNonNull(afterDisplaceMutation, "afterDisplaceMutation");
        java.util.Objects.requireNonNull(afterPublicationMutation, "afterPublicationMutation");
        java.util.Objects.requireNonNull(afterPrivateDirectoryCreate,
                "afterPrivateDirectoryCreate");
        requireAttached();
        requireExclusiveFallbackPath(displayDirectory);
        PrivateDirectory retained = createPrivateDirectory(destination.toString());
        boolean sourceMoved = false;
        boolean originalMoved = false;
        boolean displacedMatched = destinationExpected == null;
        boolean publicationApplied = false;
        boolean publicationVerified = false;
        try {
            afterPrivateDirectoryCreate.run();
            try {
                retained.moveFrom(directory, displayDirectory, source, Path.of("staged"),
                        afterStageMutation);
                sourceMoved = true;
            } catch (PrivateMoveAppliedException applied) {
                sourceMoved = true;
                throw guardedFailure(retained, sourceExpected, true, false,
                        displacedMatched, false, false, applied);
            }
            FilesystemProjectWorkspace.FileIdentity staged = retained.identity(
                    Path.of("staged"), maximumBytes);
            if (!sourceExpected.equals(staged)) {
                throw new IOException("staged artifact changed before guarded publication");
            }
            retained.verifyHardLinkSupport(Path.of("staged"), sourceExpected, maximumBytes);
            beforeDisplace.run();
            if (destinationExpected != null) {
                requireExclusiveFile(destination);
                retained.linkFromPublic(destination, Path.of("expected"));
                FilesystemProjectWorkspace.FileIdentity preMove = retained.identity(
                        Path.of("expected"), maximumBytes);
                if (!destinationExpected.equals(preMove)) {
                    retained.cleanup();
                    throw new IOException(
                            "target changed before guarded displacement; replacement refused");
                }
                Object expectedFileKey = retained.fileKey(Path.of("expected"));
                long expectedBytes = retained.size(Path.of("expected"));
                try {
                    retained.moveFrom(directory, displayDirectory,
                            destination, Path.of("displaced"), afterDisplaceMutation);
                    originalMoved = true;
                } catch (PrivateMoveAppliedException applied) {
                    originalMoved = true;
                    throw guardedFailure(retained, sourceExpected, sourceMoved, true,
                            false, false, false, applied);
                }
                afterDisplace.run();
                displacedMatched = java.util.Objects.equals(expectedFileKey,
                        retained.fileKey(Path.of("displaced")))
                        && expectedBytes == retained.size(Path.of("displaced"));
                if (!displacedMatched) {
                    FilesystemProjectWorkspace.FileIdentity displaced = retained.identity(
                            Path.of("displaced"), maximumBytes);
                    try {
                        publishHardLink(retained.displayPath(Path.of("displaced")),
                                destination, displaced, maximumBytes);
                        retained.cleanup();
                        throw new IOException(
                                "target changed before guarded replacement; replacement refused");
                    } catch (java.nio.file.FileAlreadyExistsException collision) {
                        throw guardedFailure(retained, sourceExpected, sourceMoved, originalMoved,
                                false, false, false, collision);
                    }
                }
            } else if (exists(destination)) {
                throw new java.nio.file.FileAlreadyExistsException(target().toString());
            }

            retained.markPublicationUncertain();
            try {
                publishHardLink(retained.displayPath(Path.of("staged")),
                        destination, sourceExpected, maximumBytes);
                publicationApplied = true;
                publicationVerified = true;
                afterPublicationMutation.run();
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                if (originalMoved) {
                    FilesystemProjectWorkspace.FileIdentity displaced = retained.identity(
                            Path.of("displaced"), maximumBytes);
                    try {
                        publishHardLink(retained.displayPath(Path.of("displaced")),
                                destination, displaced, maximumBytes);
                        retained.cleanup();
                    } catch (java.nio.file.FileAlreadyExistsException racingTarget) {
                        collision.addSuppressed(racingTarget);
                        throw guardedFailure(retained, sourceExpected, sourceMoved, true,
                                true, false, false, collision);
                    }
                }
                throw collision;
            } catch (MutationAppliedException unknown) {
                publicationApplied = true;
                throw guardedFailure(retained, sourceExpected, sourceMoved, originalMoved,
                        displacedMatched, true, false, unknown);
            }
            retained.markPublicationCompleted();
            GuardedReplaceReceipt receipt = observeGuarded(retained, sourceExpected,
                    sourceMoved, originalMoved, displacedMatched,
                    publicationApplied, publicationVerified);
            retained.cleanup();
            return receipt;
        } catch (GuardedReplaceException known) {
            throw known;
        } catch (IOException failure) {
            if (sourceMoved || originalMoved || publicationApplied) {
                throw guardedFailure(retained, sourceExpected, sourceMoved, originalMoved,
                        displacedMatched, publicationApplied, publicationVerified, failure);
            }
            try {
                retained.cleanup();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void publishHardLink(Path retainedSource, Path destination,
            FilesystemProjectWorkspace.FileIdentity expected, long maximumBytes)
            throws IOException {
        requireAttached();
        boolean applied = false;
        try {
            Files.createLink(displayDirectory.resolve(destination), retainedSource);
            applied = true;
            requireAttached();
            if (!expected.equals(identity(destination, maximumBytes))) {
                throw new IOException("hard-link publication did not preserve source identity");
            }
            forceDirectory();
        } catch (IOException failure) {
            if (applied) throw new MutationAppliedException(failure);
            throw failure;
        }
    }

    private GuardedReplaceException guardedFailure(PrivateDirectory retained,
            FilesystemProjectWorkspace.FileIdentity sourceExpected, boolean sourceMoved,
            boolean originalMoved, boolean displacedMatched, boolean publicationApplied,
            boolean publicationVerified, IOException cause) {
        GuardedReplaceReceipt receipt = observeGuarded(retained, sourceExpected, sourceMoved,
                originalMoved, displacedMatched, publicationApplied, publicationVerified);
        retained.close();
        return new GuardedReplaceException(receipt, cause);
    }

    private GuardedReplaceReceipt observeGuarded(PrivateDirectory retained,
            FilesystemProjectWorkspace.FileIdentity sourceExpected, boolean sourceMoved,
            boolean originalMoved, boolean displacedMatched, boolean publicationApplied,
            boolean publicationVerified) {
        boolean locationCurrent = false;
        boolean targetStateKnown = false;
        boolean targetPresent = false;
        String targetSha256 = null;
        String displacedSha256 = null;
        boolean displacedStateKnown = !originalMoved;
        boolean stagedStateKnown = false;
        String stagedSha256 = null;
        boolean stagedRetained = false;
        try {
            FilesystemProjectWorkspace.FileIdentity staged = retained.identity(
                    Path.of("staged"), WorkspaceTransaction.MAX_STAGED_BYTES);
            stagedSha256 = staged.sha256();
            stagedRetained = sourceExpected.equals(staged);
            stagedStateKnown = true;
        } catch (NoSuchFileException absent) {
            stagedStateKnown = true;
        } catch (IOException unavailable) {
            // The private staged alias was cleaned or cannot be verified.
        }
        try {
            displacedSha256 = retained.identity(Path.of("displaced"),
                    WorkspaceTransaction.MAX_STAGED_BYTES).sha256();
            displacedStateKnown = true;
        } catch (NoSuchFileException absent) {
            displacedStateKnown = true;
        } catch (IOException unavailable) {
            // No displaced target exists, or its identity is unavailable.
        }
        try {
            requireAttached();
            locationCurrent = true;
            targetPresent = exists(targetName);
            if (targetPresent) {
                targetSha256 = identity(targetName,
                        WorkspaceTransaction.MAX_STAGED_BYTES).sha256();
            }
            targetStateKnown = true;
        } catch (IOException unavailable) {
            // The receipt remains fact-granular when post-mutation observation fails.
        }
        return new GuardedReplaceReceipt(locationCurrent, sourceMoved, stagedStateKnown,
                stagedRetained, stagedSha256, originalMoved,
                originalMoved ? retained.displayPath(Path.of("displaced")) : null,
                displacedStateKnown, displacedSha256, displacedMatched,
                sourceExpected.sha256(), targetStateKnown, targetPresent, targetSha256,
                publicationApplied, publicationVerified,
                stagedStateKnown && stagedSha256 != null
                        ? retained.displayPath(Path.of("staged")) : null);
    }

    private PrivateDirectory createPrivateDirectory(String targetLabel) throws IOException {
        requireAttached();
        requireExclusiveFallbackPath(displayDirectory);
        if (retainedTransactionDirectories().size()
                >= MAX_RETAINED_TRANSACTION_DIRECTORIES) {
            throw new IOException("retained transaction directory quota is exhausted for "
                    + targetName);
        }
        for (int attempt = 0; attempt < 32; attempt++) {
            Path name = Path.of("." + targetLabel + ".protege-mcp-transaction-"
                    + UUID.randomUUID());
            Path path = displayDirectory.resolve(name);
            boolean created = false;
            PrivateDirectory retained = null;
            try {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                created = true;
                requireAttached();
                SecureDirectoryStream<Path> handle = secureDirectory(path);
                retained = new PrivateDirectory(name, path, handle);
                retained.initializeManifest();
                return retained;
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // Retry with a fresh owner-private name.
            } catch (IOException | RuntimeException failure) {
                if (created) {
                    try {
                        if (retained != null) retained.cleanup();
                        else {
                            Files.deleteIfExists(path);
                            forceDirectory();
                        }
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                if (failure instanceof UnsupportedOperationException unsupported) {
                    throw new IOException(
                            "guarded replacement requires POSIX owner-only directories",
                            unsupported);
                }
                throw failure;
            }
        }
        throw new IOException("could not allocate a private transaction directory");
    }

    /** Recover trusted crash evidence. The caller must hold this project's process lock. */
    RecoverySweepReceipt recoverOrphanedTransactionsUnderLock(
            WorkspaceProjectLock.Handle lock, Path expectedLockPath)
            throws IOException {
        return recoverOrphanedTransactionsUnderLock(lock, expectedLockPath, () -> { });
    }

    RecoverySweepReceipt recoverOrphanedTransactionsUnderLock(
            WorkspaceProjectLock.Handle lock, Path expectedLockPath,
            ReadHook afterRecoveryLink) throws IOException {
        return recoverOrphanedTransactionsUnderLock(lock, expectedLockPath,
                afterRecoveryLink, () -> { });
    }

    RecoverySweepReceipt recoverOrphanedTransactionsUnderLock(
            WorkspaceProjectLock.Handle lock, Path expectedLockPath,
            ReadHook afterRecoveryLink, ReadHook afterDisplacedCleanup) throws IOException {
        java.util.Objects.requireNonNull(afterRecoveryLink, "afterRecoveryLink");
        java.util.Objects.requireNonNull(afterDisplacedCleanup, "afterDisplacedCleanup");
        if (lock == null || !lock.covers(projectRoot, expectedLockPath)) {
            throw new IOException("transaction recovery requires the matching project lock");
        }
        requireExclusiveFallbackPath(displayDirectory);
        List<Path> retained = retainedTransactionDirectories();
        if (retained.isEmpty()) return observeRecovery(false, true, false, 0, null);
        boolean targetPresent = exists(targetName);
        List<WorkspaceRecoveryPlan.Evidence> evidence = new ArrayList<>();
        for (Path directoryPath : retained) {
            Path staged = directoryPath.resolve("staged");
            Path displaced = directoryPath.resolve("displaced");
            boolean publicationUncertain = Files.exists(
                    directoryPath.resolve(PUBLICATION_UNCERTAIN), LinkOption.NOFOLLOW_LINKS);
            boolean publicationCompleted = Files.exists(
                    directoryPath.resolve(PUBLICATION_COMPLETED), LinkOption.NOFOLLOW_LINKS);
            boolean recoveryUncertain = Files.exists(
                    directoryPath.resolve(RECOVERY_UNCERTAIN), LinkOption.NOFOLLOW_LINKS);
            boolean recoveryCompleted = Files.exists(
                    directoryPath.resolve(RECOVERY_COMPLETED), LinkOption.NOFOLLOW_LINKS);
            boolean completedQuarantine = Files.exists(
                    directoryPath.resolve(COMPLETED_RECOVERY), LinkOption.NOFOLLOW_LINKS);
            boolean stagedPresent = Files.isRegularFile(
                    staged, LinkOption.NOFOLLOW_LINKS);
            boolean displacedPresent = Files.isRegularFile(
                    displaced, LinkOption.NOFOLLOW_LINKS);
            evidence.add(new WorkspaceRecoveryPlan.Evidence(directoryPath,
                    stagedPresent, displacedPresent,
                    publicationUncertain, publicationCompleted,
                    recoveryUncertain, recoveryCompleted, completedQuarantine,
                    targetPresent && stagedPresent
                            && targetIsHardLinkTo(directoryPath, Path.of("staged")),
                    targetPresent && displacedPresent
                            && targetIsHardLinkTo(directoryPath, Path.of("displaced"))));
        }
        WorkspaceRecoveryPlan.Plan plan = WorkspaceRecoveryPlan.classify(
                evidence, targetPresent);
        if (plan.requiresManualIntervention()) {
            throw new AmbiguousRecoveryException(plan.reason(), retained);
        }
        boolean mutationApplied = false;
        int directoriesCleaned = 0;
        for (Path directoryPath : plan.uncertainToMark()) {
            try (PrivateDirectory transaction = openPrivateDirectory(directoryPath)) {
                transaction.markRecoveryUncertain();
                mutationApplied = true;
            } catch (IOException failure) {
                throw new OrphanRecoveryAppliedException(
                        observeRecovery(true, false, false,
                                directoriesCleaned, null), failure);
            }
        }
        for (Path directoryPath : plan.cleanable()) {
            try {
                cleanupPrivatePath(directoryPath);
                mutationApplied = true;
                directoriesCleaned++;
            } catch (IOException failure) {
                throw new OrphanRecoveryAppliedException(
                        observeRecovery(true, false, false,
                                directoriesCleaned, null), failure);
            }
        }
        for (Path directoryPath : plan.recoverable()) {
            Path displaced = directoryPath.resolve("displaced");
            FilesystemProjectWorkspace.FileIdentity expected =
                    identityPrivatePath(displaced, WorkspaceTransaction.MAX_STAGED_BYTES);
            boolean applied = false;
            try (PrivateDirectory transaction = openPrivateDirectory(directoryPath)) {
                try {
                    transaction.markRecoveryUncertain();
                    mutationApplied = true;
                } catch (IOException failure) {
                    throw new OrphanRecoveryAppliedException(
                            observeRecovery(true, false, false,
                                    directoriesCleaned, null), failure);
                }
                Files.createLink(target(), displaced);
                applied = true;
                afterRecoveryLink.run();
                requireAttached();
                if (!expected.equals(identity(targetName,
                        WorkspaceTransaction.MAX_STAGED_BYTES))) {
                    throw new IOException(
                            "orphan recovery did not restore the displaced target identity");
                }
                forceDirectory();
                transaction.markRecoveryCompleted();
                transaction.cleanup(afterDisplacedCleanup);
                mutationApplied = true;
                directoriesCleaned++;
                return observeRecovery(true, true, true,
                        directoriesCleaned, expected.sha256());
            } catch (OrphanRecoveryAppliedException known) {
                throw known;
            } catch (java.nio.file.FileAlreadyExistsException concurrent) {
                // Preserve the concurrent target and retained orphan evidence.
            } catch (IOException failure) {
                if (applied || mutationApplied) {
                    throw new OrphanRecoveryAppliedException(
                            observeRecovery(true, false, applied, directoriesCleaned,
                                    applied ? expected.sha256() : null),
                            failure);
                }
                throw failure;
            }
        }
        return observeRecovery(mutationApplied, true, false, directoriesCleaned, null);
    }

    private RecoverySweepReceipt observeRecovery(boolean mutationApplied,
            boolean recoveryStateKnown, boolean targetRestored,
            int directoriesCleaned, String restoredSha256) {
        boolean locationCurrent = false;
        boolean targetStateKnown = false;
        boolean targetPresent = false;
        String targetSha256 = null;
        try {
            requireAttached();
            locationCurrent = true;
            targetPresent = exists(targetName);
            if (targetPresent) {
                targetSha256 = identity(targetName,
                        WorkspaceTransaction.MAX_STAGED_BYTES).sha256();
            }
            targetStateKnown = true;
        } catch (IOException unavailable) {
            // A receipt never turns unavailable post-mutation facts into known facts.
        }
        return new RecoverySweepReceipt(mutationApplied, recoveryStateKnown, locationCurrent,
                targetStateKnown, targetPresent, targetSha256, targetRestored,
                restoredSha256, directoriesCleaned);
    }

    private boolean targetIsHardLinkTo(Path privateDirectory, Path privateEntry)
            throws IOException {
        Object targetKey = attributes(targetName).fileKey();
        BasicFileAttributes privateAttributes = Files.readAttributes(
                privateDirectory.resolve(privateEntry), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return targetKey != null && targetKey.equals(privateAttributes.fileKey());
    }

    private List<Path> retainedTransactionDirectories() throws IOException {
        String prefix = "." + targetName + ".protege-mcp-transaction-";
        List<Path> retained = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(
                displayDirectory, entry -> entry.getFileName().toString().startsWith(prefix))) {
            for (Path entry : entries) {
                if (isTrustedTransactionDirectory(entry)) retained.add(entry);
            }
        }
        retained.sort(Path::compareTo);
        return retained;
    }

    private boolean isTrustedTransactionDirectory(Path candidate) throws IOException {
        String prefix = "." + targetName + ".protege-mcp-transaction-";
        String filename = candidate.getFileName().toString();
        if (!filename.startsWith(prefix)) return false;
        try {
            UUID.fromString(filename.substring(prefix.length()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (Files.isSymbolicLink(candidate)) return false;
        final PosixFileAttributes parentAttributes;
        final PosixFileAttributes directoryAttributes;
        try {
            parentAttributes = Files.readAttributes(displayDirectory,
                    PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            directoryAttributes = Files.readAttributes(candidate,
                    PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return false;
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("transaction recovery requires POSIX ownership evidence",
                    unsupported);
        }
        if (!directoryAttributes.isDirectory()
                || !directoryAttributes.owner().equals(parentAttributes.owner())
                || !directoryAttributes.permissions().equals(
                        PosixFilePermissions.fromString("rwx------"))) {
            return false;
        }
        Path manifest = candidate.resolve(TRANSACTION_MANIFEST);
        BasicFileAttributes manifestAttributes;
        try {
            manifestAttributes = Files.readAttributes(manifest, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return false;
        }
        byte[] expected = manifestBytes(candidate.getFileName());
        if (!manifestAttributes.isRegularFile() || manifestAttributes.isSymbolicLink()
                || manifestAttributes.size() != expected.length) {
            return false;
        }
        return java.util.Arrays.equals(expected, Files.readAllBytes(manifest));
    }

    private byte[] manifestBytes(Path privateName) {
        return ("protege-mcp-transaction-v1\n" + privateName + "\n" + targetName + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private FilesystemProjectWorkspace.FileIdentity identityPrivatePath(
            Path path, long maximumBytes) throws IOException {
        try (SecureTargetAnchor nested = SecureTargetAnchor.open(projectRoot, path)) {
            return nested.identity(nested.targetName(), maximumBytes);
        }
    }

    private void cleanupPrivatePath(Path directoryPath) throws IOException {
        try (PrivateDirectory transaction = openPrivateDirectory(directoryPath)) {
            transaction.cleanup();
        }
    }

    private PrivateDirectory openPrivateDirectory(Path directoryPath) throws IOException {
        return new PrivateDirectory(directoryPath.getFileName(), directoryPath,
                secureDirectory(directoryPath));
    }

    /**
     * Atomically moves an entry to a private retained sibling and verifies the moved object.
     * The retained object is deliberately not unlinked: Java has no portable compare-and-delete
     * primitive, so deleting it by pathname would reintroduce a replacement race.
     */
    Path quarantineIfIdentity(Path name, FilesystemProjectWorkspace.FileIdentity expected,
            long maximumBytes) throws IOException {
        return quarantineIfIdentity(name, expected, maximumBytes, () -> { }, () -> { });
    }

    Path quarantineIfIdentity(Path name, FilesystemProjectWorkspace.FileIdentity expected,
            long maximumBytes, ReadHook beforeMove, ReadHook afterMove) throws IOException {
        java.util.Objects.requireNonNull(expected, "expected");
        java.util.Objects.requireNonNull(beforeMove, "beforeMove");
        java.util.Objects.requireNonNull(afterMove, "afterMove");
        requireAttached();
        requireExclusiveFallbackPath(displayDirectory);
        PrivateDirectory retained = createPrivateDirectory(name.toString());
        boolean displacementApplied = false;
        try {
            beforeMove.run();
            try {
                retained.moveFrom(directory, displayDirectory, name, Path.of("displaced"));
                displacementApplied = true;
            } catch (PrivateMoveAppliedException applied) {
                displacementApplied = true;
                throw guardedFailure(retained, expected, false, true,
                        false, false, false, applied);
            }
            afterMove.run();
            FilesystemProjectWorkspace.FileIdentity moved =
                    retained.identity(Path.of("displaced"), maximumBytes);
            if (!expected.equals(moved)) {
                try {
                    publishHardLink(retained.displayPath(Path.of("displaced")),
                            name, moved, maximumBytes);
                    retained.cleanup();
                    throw new IOException(
                            "target changed before recovery quarantine; recovery refused");
                } catch (java.nio.file.FileAlreadyExistsException collision) {
                    throw guardedFailure(retained, expected, false, true,
                            false, false, false, collision);
                }
            }
            if (exists(name)) {
                throw guardedFailure(retained, expected, false, true,
                        true, false, false, new java.nio.file.FileAlreadyExistsException(
                                "target was recreated while the installed artifact was quarantined"));
            }
            retained.markCompletedRecovery();
            retained.close();
            return retained.displayPath(Path.of("displaced"));
        } catch (GuardedReplaceException known) {
            throw known;
        } catch (IOException failure) {
            if (displacementApplied) retained.close();
            else {
                try {
                    retained.cleanup();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        } catch (RuntimeException failure) {
            if (displacementApplied) retained.close();
            else {
                try {
                    retained.cleanup();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    Set<PosixFilePermission> permissions(Path name) throws IOException {
        requireAttached();
        try {
            PosixFileAttributeView view = directory == null
                    ? Files.getFileAttributeView(displayDirectory.resolve(name),
                            PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    : directory.getFileAttributeView(name, PosixFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
            return view == null ? null : Set.copyOf(view.readAttributes().permissions());
        } catch (UnsupportedOperationException unavailable) {
            return null;
        }
    }

    void setPermissions(Path name, Set<PosixFilePermission> permissions) throws IOException {
        if (permissions == null) return;
        requireAttached();
        try {
            PosixFileAttributeView view = directory == null
                    ? Files.getFileAttributeView(displayDirectory.resolve(name),
                            PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    : directory.getFileAttributeView(name, PosixFileAttributeView.class,
                            LinkOption.NOFOLLOW_LINKS);
            if (view != null) view.setPermissions(permissions);
        } catch (UnsupportedOperationException unavailable) {
            // The platform does not expose POSIX permissions.
        }
    }

    void deleteIfExists(Path name) throws IOException {
        deleteIfExists(name, () -> { });
    }

    void deleteIfExists(Path name, ReadHook afterDelete) throws IOException {
        requireAttached();
        java.util.Objects.requireNonNull(afterDelete, "afterDelete");
        boolean applied = false;
        try {
            if (directory == null) {
                applied = Files.deleteIfExists(displayDirectory.resolve(name));
            } else {
                directory.deleteFile(name);
                applied = true;
            }
            if (applied) {
                afterDelete.run();
                requireAttached();
                forceDirectory();
                requireAttached();
            }
        } catch (NoSuchFileException absent) {
            // Already absent.
        } catch (IOException failure) {
            if (applied) throw new MutationAppliedException(failure);
            throw failure;
        }
    }

    void verifyAttached() throws IOException {
        requireAttached();
    }

    private BasicFileAttributes attributes(Path name) throws IOException {
        if (directory == null) {
            return Files.readAttributes(displayDirectory.resolve(name),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        return attributes(directory, name);
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> stream,
            Path name) throws IOException {
        BasicFileAttributeView view = stream.getFileAttributeView(name,
                BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("basic file attributes are unavailable");
        return view.readAttributes();
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> stream)
            throws IOException {
        BasicFileAttributeView view = stream.getFileAttributeView(BasicFileAttributeView.class);
        if (view == null) throw new IOException("basic directory attributes are unavailable");
        return view.readAttributes();
    }

    private void requireAttached() throws IOException {
        for (DirectoryIdentity identity : lexicalDirectories) {
            BasicFileAttributes current = Files.readAttributes(identity.path(),
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isDirectory() || current.isSymbolicLink()
                    || current.fileKey() == null
                    || !current.fileKey().equals(identity.fileKey())
                    || !identity.path().toRealPath().equals(identity.path())) {
                throw new IOException("transaction directory chain changed after authorization: "
                        + projectRoot);
            }
        }
        if (rootDirectory == null) {
            if (Files.isSymbolicLink(displayDirectory)) {
                throw new IOException("transaction target directory changed after authorization");
            }
            BasicFileAttributes current = Files.readAttributes(displayDirectory,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isDirectory() || !displayDirectory.toRealPath().equals(displayDirectory)
                    || !java.util.Objects.equals(parentFileKey, current.fileKey())) {
                throw new IOException("transaction target directory changed after authorization");
            }
            return;
        }
        SecureDirectoryStream<Path> current = rootDirectory;
        try {
            if (!relativeParent.toString().isEmpty()) {
                for (Path component : relativeParent) {
                    SecureDirectoryStream<Path> next = current.newDirectoryStream(
                            Path.of(component.toString()), LinkOption.NOFOLLOW_LINKS);
                    if (current != rootDirectory) current.close();
                    current = next;
                }
            }
            Object currentKey = attributes(current).fileKey();
            if (!java.util.Objects.equals(parentFileKey, currentKey)) {
                throw new IOException("transaction target directory changed after authorization");
            }
        } finally {
            if (current != rootDirectory) current.close();
        }
    }

    private void setOwnerOnly(Path name) throws IOException {
        if (directory == null) {
            setOwnerOnlyPath(displayDirectory.resolve(name));
            return;
        }
        PosixFileAttributeView view = directory.getFileAttributeView(name,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return;
        view.setPermissions(PosixFilePermissions.fromString("rw-------"));
    }

    private void requireExclusiveFile(Path name) throws IOException {
        Set<PosixFilePermission> permissions = permissions(name);
        if (permissions == null
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException(
                    "guarded replacement requires an owner-only writable target: " + name);
        }
    }

    private static void setOwnerOnlyPath(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // The platform does not expose POSIX permissions.
        }
    }

    private static SecureDirectoryStream<Path> secureDirectory(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        DirectoryStream<Path> opened = Files.newDirectoryStream(path);
        if (!(opened instanceof SecureDirectoryStream<?> raw)) {
            opened.close();
            return null;
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) raw;
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isDirectory() || !after.isDirectory()
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
            secure.close();
            throw new IOException("project root changed while opening a secure directory handle");
        }
        return secure;
    }

    private static List<DirectoryIdentity> captureDirectoryChain(Path root, Path parent)
            throws IOException {
        List<Path> paths = new ArrayList<>();
        for (Path current = parent; current != null; current = current.getParent()) {
            paths.add(current);
        }
        Collections.reverse(paths);
        List<DirectoryIdentity> identities = new ArrayList<>();
        for (Path path : paths) {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()
                    || attributes.fileKey() == null
                    || !path.toRealPath().equals(path)) {
                throw new IOException("transaction directory chain is not securely identifiable: "
                        + path);
            }
            identities.add(new DirectoryIdentity(path, attributes.fileKey()));
        }
        if (identities.stream().noneMatch(identity -> identity.path().equals(root))) {
            throw new IOException("project root is not in the transaction directory chain");
        }
        return identities;
    }

    private static void requireExclusiveFallbackPath(Path parent) throws IOException {
        boolean protectedDescendant = false;
        for (Path current = parent; current != null; current = current.getParent()) {
            final PosixFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException unavailable) {
                throw new IOException("secure directory-relative operations are unavailable and "
                        + "the provider has no POSIX fallback", unavailable);
            }
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                Object rawMode;
                try {
                    rawMode = Files.getAttribute(current, "unix:mode",
                            LinkOption.NOFOLLOW_LINKS);
                } catch (UnsupportedOperationException unavailable) {
                    rawMode = null;
                }
                boolean sticky = rawMode instanceof Number number
                        && (number.intValue() & 01000) != 0;
                if (sticky && protectedDescendant) continue;
                throw new IOException("secure directory-relative operations are unavailable and "
                        + "the fallback path is shared-writable: " + current);
            }
            protectedDescendant = true;
        }
    }

    private final class PrivateDirectory implements AutoCloseable {
        private final Path name;
        private final Path path;
        private final SecureDirectoryStream<Path> handle;
        private boolean closed;

        PrivateDirectory(Path name, Path path, SecureDirectoryStream<Path> handle) {
            this.name = name;
            this.path = path;
            this.handle = handle;
        }

        void initializeManifest() throws IOException {
            writeStateFile(TRANSACTION_MANIFEST, manifestBytes(name));
        }

        void moveFrom(SecureDirectoryStream<Path> sourceHandle, Path sourceDirectory,
                Path source, Path destination) throws IOException {
            moveFrom(sourceHandle, sourceDirectory, source, destination, () -> { });
        }

        void moveFrom(SecureDirectoryStream<Path> sourceHandle, Path sourceDirectory,
                Path source, Path destination, ReadHook afterMutation) throws IOException {
            requireAttached();
            boolean applied = false;
            try {
                if (sourceHandle != null && handle != null) {
                    sourceHandle.move(source, handle, destination);
                } else if (sourceHandle == null && handle == null) {
                    Files.move(sourceDirectory.resolve(source), path.resolve(destination),
                            StandardCopyOption.ATOMIC_MOVE);
                } else {
                    throw new IOException(
                            "guarded replacement cannot mix secure and fallback directories");
                }
                applied = true;
                afterMutation.run();
                requireAttached();
                forceDirectory();
                forcePrivateDirectory();
            } catch (IOException failure) {
                if (applied) throw new PrivateMoveAppliedException(failure);
                throw failure;
            }
        }

        FilesystemProjectWorkspace.FileIdentity identity(Path entry, long maximumBytes)
                throws IOException {
            try (SecureTargetAnchor nested = SecureTargetAnchor.open(
                    projectRoot, path.resolve(entry))) {
                return nested.identity(nested.targetName(), maximumBytes);
            }
        }

        void verifyHardLinkSupport(Path source,
                FilesystemProjectWorkspace.FileIdentity expected, long maximumBytes)
                throws IOException {
            Path probe = Path.of("hard-link-probe-" + UUID.randomUUID());
            try {
                Files.createLink(path.resolve(probe), path.resolve(source));
                if (!expected.equals(identity(probe, maximumBytes))) {
                    throw new IOException("hard-link probe changed source identity");
                }
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("filesystem does not support guarded hard-link publication",
                        unsupported);
            } finally {
                deletePrivateFile(probe);
            }
        }

        void linkFromPublic(Path source, Path destination) throws IOException {
            Files.createLink(path.resolve(destination), displayDirectory.resolve(source));
            requireAttached();
        }

        Object fileKey(Path entry) throws IOException {
            BasicFileAttributes attributes = handle == null
                    ? Files.readAttributes(path.resolve(entry), BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS)
                    : SecureTargetAnchor.attributes(handle, entry);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.fileKey() == null) {
                throw new IOException("private transaction entry is not securely identifiable");
            }
            return attributes.fileKey();
        }

        long size(Path entry) throws IOException {
            return handle == null
                    ? Files.readAttributes(path.resolve(entry), BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).size()
                    : SecureTargetAnchor.attributes(handle, entry).size();
        }

        void markCompletedRecovery() throws IOException {
            writeStateFile(COMPLETED_RECOVERY, new byte[0]);
        }

        void markRecoveryUncertain() throws IOException {
            writeStateFile(RECOVERY_UNCERTAIN, new byte[0]);
        }

        void markRecoveryCompleted() throws IOException {
            writeStateFile(RECOVERY_COMPLETED, new byte[0]);
        }

        void markPublicationUncertain() throws IOException {
            writeStateFile(PUBLICATION_UNCERTAIN, new byte[0]);
        }

        void markPublicationCompleted() throws IOException {
            writeStateFile(PUBLICATION_COMPLETED, new byte[0]);
        }

        private void writeStateFile(Path marker, byte[] content) throws IOException {
            if (handle != null) {
                try (SeekableByteChannel channel = handle.newByteChannel(
                        marker, writeOptions())) {
                    writeFully(channel, content);
                    force(channel);
                }
            } else {
                try (FileChannel channel = FileChannel.open(path.resolve(marker),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writeFully(channel, content);
                    channel.force(true);
                }
            }
            forcePrivateDirectory();
        }

        private void forcePrivateDirectory() {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Directory forcing is provider-specific; phase ambiguity still fails closed.
            }
        }

        Path displayPath(Path entry) {
            return path.resolve(entry);
        }

        void cleanup() throws IOException {
            cleanup(() -> { });
        }

        void cleanup(ReadHook afterDisplacedDeletion) throws IOException {
            try {
                deletePrivateFile(Path.of("displaced"));
                afterDisplacedDeletion.run();
            } catch (IOException unsafe) {
                close();
                throw unsafe;
            }
            IOException failure = null;
            for (Path entry : List.of(Path.of("staged"), Path.of("expected"),
                    COMPLETED_RECOVERY, RECOVERY_UNCERTAIN,
                    RECOVERY_COMPLETED, PUBLICATION_UNCERTAIN, PUBLICATION_COMPLETED,
                    TRANSACTION_MANIFEST)) {
                try {
                    deletePrivateFile(entry);
                } catch (IOException unavailable) {
                    if (failure == null) failure = unavailable;
                    else failure.addSuppressed(unavailable);
                }
            }
            try {
                close();
                if (directory != null) directory.deleteDirectory(name);
                else Files.deleteIfExists(path);
                requireAttached();
                forceDirectory();
            } catch (IOException unavailable) {
                if (failure == null) failure = unavailable;
                else failure.addSuppressed(unavailable);
            }
            if (failure != null) throw failure;
        }

        private void deletePrivateFile(Path entry) throws IOException {
            try {
                if (handle != null) handle.deleteFile(entry);
                else Files.deleteIfExists(path.resolve(entry));
            } catch (NoSuchFileException absent) {
                // The private entry was not created or was already cleaned.
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (handle != null) {
                try {
                    handle.close();
                } catch (IOException ignored) {
                    // Retain the directory for manual inspection on close failure.
                }
            }
        }
    }

    private void forceDirectory() {
        try (FileChannel channel = FileChannel.open(
                displayDirectory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory forcing is not portable; ambiguous recovery states still fail closed.
        }
    }

    private static Set<OpenOption> readOptions() {
        return Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private static Set<OpenOption> writeOptions() {
        return Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
    }

    private SeekableByteChannel openRead(Path name) throws IOException {
        return directory == null
                ? Files.newByteChannel(displayDirectory.resolve(name), readOptions())
                : directory.newByteChannel(name, readOptions());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder("sha256:");
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static void force(SeekableByteChannel channel) throws IOException {
        if (!(channel instanceof FileChannel file)) {
            throw new IOException("durable file forcing is unavailable for this provider");
        }
        file.force(true);
    }

    private static void writeFully(SeekableByteChannel channel, byte[] content)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    @Override
    public void close() throws IOException {
        if (rootDirectory == null) return;
        if (directory != rootDirectory) directory.close();
        rootDirectory.close();
    }

    record CreatedFile(Path name, Path displayPath, SeekableByteChannel channel)
            implements AutoCloseable {
        void force() throws IOException {
            SecureTargetAnchor.force(channel);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private record DirectoryIdentity(Path path, Object fileKey) { }

    static final class MutationAppliedException extends IOException {
        private static final long serialVersionUID = 1L;

        MutationAppliedException(IOException cause) {
            super("filesystem mutation completed but directory attachment verification failed",
                    cause);
        }
    }

    static final class PrivateMoveAppliedException extends IOException {
        private static final long serialVersionUID = 1L;

        PrivateMoveAppliedException(IOException cause) {
            super("private move completed but durability or attachment verification failed",
                    cause);
        }
    }

    record RecoverySweepReceipt(boolean mutationApplied, boolean recoveryStateKnown,
            boolean locationCurrent,
            boolean targetStateKnown, boolean targetPresent, String targetSha256,
            boolean targetRestored, String restoredSha256, int directoriesCleaned) { }

    static final class OrphanRecoveryAppliedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final RecoverySweepReceipt receipt;

        OrphanRecoveryAppliedException(RecoverySweepReceipt receipt, IOException cause) {
            super("orphan recovery changed transaction state before failing", cause);
            this.receipt = receipt;
        }

        RecoverySweepReceipt receipt() {
            return receipt;
        }
    }

    static final class AmbiguousRecoveryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final List<Path> evidencePaths;

        AmbiguousRecoveryException(String message, List<Path> evidencePaths) {
            super(message);
            this.evidencePaths = List.copyOf(evidencePaths);
        }

        List<Path> evidencePaths() {
            return evidencePaths;
        }
    }

    record GuardedReplaceReceipt(boolean locationCurrent, boolean sourceMoved,
            boolean stagedStateKnown, boolean stagedRetained, String stagedSha256,
            boolean originalMoved, Path displacedPath, boolean displacedStateKnown,
            String displacedSha256,
            boolean displacedMatched,
            String intendedSha256, boolean targetStateKnown, boolean targetPresent,
            String targetSha256, boolean publicationApplied, boolean publicationVerified,
            Path retainedStagePath) { }

    static final class GuardedReplaceException extends IOException {
        private final GuardedReplaceReceipt receipt;

        GuardedReplaceException(GuardedReplaceReceipt receipt, IOException cause) {
            super("guarded replacement changed private transaction state before failing", cause);
            this.receipt = receipt;
        }

        GuardedReplaceReceipt receipt() {
            return receipt;
        }
    }

    @FunctionalInterface
    interface ReadHook {
        void run() throws IOException;
    }

    static final class SizeLimitExceededException extends IOException {
        private final long maximumBytes;

        SizeLimitExceededException(long maximumBytes, Path name) {
            super("transaction entry exceeds " + maximumBytes + " bytes: " + name);
            this.maximumBytes = maximumBytes;
        }

        long maximumBytes() {
            return maximumBytes;
        }
    }
}
