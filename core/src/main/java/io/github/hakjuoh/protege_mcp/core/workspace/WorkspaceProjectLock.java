package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/** One authoritative advisory process lock shared by every mutation of a project root. */
final class WorkspaceProjectLock {

    static final String LOCK_DIRECTORY = ".protege-mcp-workspace-lock";
    private static final String IDENTITY_FILENAME = "identity-v1";
    private static final String LOCK_FILENAME = "lock";
    private static final Set<Path> PROCESS_LOCKS = new HashSet<>();

    private WorkspaceProjectLock() {
    }

    static Handle acquire(Path compatibilityStateRoot, Path projectRoot) throws IOException {
        Path root = protectedProjectRoot(compatibilityStateRoot, projectRoot);
        Path path = root.resolve(LOCK_DIRECTORY).resolve(LOCK_FILENAME);
        synchronized (PROCESS_LOCKS) {
            if (!PROCESS_LOCKS.add(path)) {
                throw new ProjectFileLock.UnavailableException(
                        "workspace transaction lock is already held");
            }
        }
        FileChannel channel = null;
        boolean complete = false;
        try {
            ensureMetadata(root);
            BasicFileAttributes before = validateMetadata(root);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            channel = FileChannel.open(path, options);
            BasicFileAttributes opened = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.fileKey().equals(opened.fileKey())) {
                throw new IOException("workspace lock pathname detached during acquisition");
            }
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException held) {
                lock = null;
            }
            if (lock == null) {
                throw new ProjectFileLock.UnavailableException(
                        "workspace transaction lock is already held");
            }
            BasicFileAttributes locked = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.fileKey().equals(locked.fileKey())) {
                lock.release();
                throw new IOException("workspace lock pathname detached during acquisition");
            }
            Handle handle = new Handle(channel, lock, root, path, before.fileKey());
            complete = true;
            return handle;
        } catch (IOException | RuntimeException failure) {
            if (channel != null && channel.isOpen()) channel.close();
            throw failure;
        } finally {
            if (!complete) releaseProcessReservation(path);
        }
    }

    static Path path(Path compatibilityStateRoot, Path projectRoot) throws IOException {
        Path root = protectedProjectRoot(compatibilityStateRoot, projectRoot);
        return root.resolve(LOCK_DIRECTORY).resolve(LOCK_FILENAME);
    }

    static boolean isReservedMetadataDirectory(Path candidate) throws IOException {
        if (candidate.getFileName() == null
                || !LOCK_DIRECTORY.equals(candidate.getFileName().toString())) {
            return false;
        }
        try {
            Path root = candidate.getParent().toRealPath();
            PosixFileAttributes rootAttributes = validateDirectoryIdentity(root);
            Path lockPath = candidate.resolve(LOCK_FILENAME);
            if (!processReserved(lockPath)) {
                validateFile(lockPath, rootAttributes, lockBytes());
            }
            return true;
        } catch (IOException invalidOrIncomplete) {
            return false;
        }
    }

    private static Path protectedProjectRoot(Path compatibilityStateRoot, Path projectRoot)
            throws IOException {
        java.util.Objects.requireNonNull(compatibilityStateRoot, "compatibilityStateRoot");
        Path root = projectRoot.toRealPath();
        PosixFileAttributes attributes;
        try {
            attributes = Files.readAttributes(root, PosixFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("workspace locking requires protected POSIX project roots",
                    unsupported);
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        if (!attributes.isDirectory() || attributes.isSymbolicLink()
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("workspace project root is shared-writable: " + root);
        }
        return root;
    }

    private static Path ensureMetadata(Path root) throws IOException {
        Path directory = root.resolve(LOCK_DIRECTORY);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            validateMetadata(root);
            return directory.resolve(LOCK_FILENAME);
        }
        Path temporary = root.resolve("." + LOCK_DIRECTORY + "-" + UUID.randomUUID());
        boolean created = false;
        try {
            Files.createDirectory(temporary, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            created = true;
            writeNew(temporary.resolve(IDENTITY_FILENAME), identityBytes(root));
            writeNew(temporary.resolve(LOCK_FILENAME), lockBytes());
            forceDirectory(temporary);
            try {
                Files.move(temporary, directory, StandardCopyOption.ATOMIC_MOVE);
                created = false;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw unsupported;
            } catch (IOException collision) {
                try {
                    validateMetadata(root);
                } catch (IOException invalidWinner) {
                    collision.addSuppressed(invalidWinner);
                    throw collision;
                }
                // Another process published complete, authenticated metadata first.
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("workspace lock metadata requires atomic directory publication",
                    unsupported);
        } finally {
            if (created) cleanupTemporary(temporary);
        }
        validateMetadata(root);
        forceDirectory(root);
        return directory.resolve(LOCK_FILENAME);
    }

    private static BasicFileAttributes validateMetadata(Path root) throws IOException {
        PosixFileAttributes rootAttributes = validateDirectoryIdentity(root);
        return validateFile(root.resolve(LOCK_DIRECTORY).resolve(LOCK_FILENAME),
                rootAttributes, lockBytes());
    }

    private static PosixFileAttributes validateDirectoryIdentity(Path root)
            throws IOException {
        PosixFileAttributes rootAttributes = Files.readAttributes(root,
                PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path directory = root.resolve(LOCK_DIRECTORY);
        PosixFileAttributes directoryAttributes = Files.readAttributes(directory,
                PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!directoryAttributes.isDirectory() || directoryAttributes.isSymbolicLink()
                || !directoryAttributes.owner().equals(rootAttributes.owner())
                || !directoryAttributes.permissions().equals(
                        PosixFilePermissions.fromString("rwx------"))) {
            throw new IOException("workspace lock metadata directory is not trusted");
        }
        validateFile(directory.resolve(IDENTITY_FILENAME), rootAttributes,
                identityBytes(root));
        validateLockInode(directory.resolve(LOCK_FILENAME), rootAttributes);
        return rootAttributes;
    }

    private static void validateLockInode(Path path,
            PosixFileAttributes rootAttributes) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(path,
                PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.fileKey() == null
                || !attributes.owner().equals(rootAttributes.owner())
                || !attributes.permissions().equals(
                        PosixFilePermissions.fromString("rw-------"))
                || attributes.size() != lockBytes().length) {
            throw new IOException("workspace lock inode is not trusted: " + path);
        }
        Object links;
        try {
            links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("workspace lock hard-link evidence is unavailable", unsupported);
        }
        if (!(links instanceof Number count) || count.longValue() != 1L) {
            throw new IOException("workspace lock inode has unexpected hard links: " + path);
        }
    }

    private static BasicFileAttributes validateFile(Path path,
            PosixFileAttributes rootAttributes, byte[] expected) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(path,
                PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.fileKey() == null
                || !attributes.owner().equals(rootAttributes.owner())
                || !attributes.permissions().equals(
                        PosixFilePermissions.fromString("rw-------"))
                || attributes.size() != expected.length) {
            throw new IOException("workspace lock metadata file is not trusted: " + path);
        }
        Object links;
        try {
            links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("workspace lock hard-link evidence is unavailable", unsupported);
        }
        if (!(links instanceof Number count) || count.longValue() != 1L
                || !java.util.Arrays.equals(expected, Files.readAllBytes(path))) {
            throw new IOException("workspace lock metadata content is invalid: " + path);
        }
        return attributes;
    }

    private static byte[] identityBytes(Path root) {
        String digest = ArtifactStore.sha256(
                root.toString().getBytes(StandardCharsets.UTF_8));
        return ("protege-mcp-workspace-lock-v1\n" + digest + "\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] lockBytes() {
        return "protege-mcp-lock-inode-v1\n".getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeNew(Path path, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString("rw-------"));
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void cleanupTemporary(Path temporary) {
        for (String name : new String[] {IDENTITY_FILENAME, LOCK_FILENAME}) {
            try {
                Files.deleteIfExists(temporary.resolve(name));
            } catch (IOException ignored) {
                // A failed initialization remains outside the authenticated coordinate.
            }
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The bounded UUID temporary is inert and never accepted as lock metadata.
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory forcing is provider-specific; metadata validation still fails closed.
        }
    }

    private static void releaseProcessReservation(Path path) {
        synchronized (PROCESS_LOCKS) {
            PROCESS_LOCKS.remove(path);
        }
    }

    private static boolean processReserved(Path path) {
        synchronized (PROCESS_LOCKS) {
            return PROCESS_LOCKS.contains(path);
        }
    }

    record Handle(FileChannel channel, FileLock lock, Path projectRoot, Path lockPath,
            Object lockFileKey) implements AutoCloseable {
        boolean covers(Path requestedProjectRoot, Path expectedLockPath) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(lockPath,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return lock.isValid()
                    && projectRoot.equals(requestedProjectRoot.toRealPath())
                    && lockPath.equals(expectedLockPath.toRealPath())
                    && attributes.isRegularFile() && !attributes.isSymbolicLink()
                    && lockFileKey.equals(attributes.fileKey());
        }

        @Override
        public void close() throws IOException {
            try {
                try {
                    lock.release();
                } finally {
                    channel.close();
                }
            } finally {
                releaseProcessReservation(lockPath);
            }
        }
    }
}
