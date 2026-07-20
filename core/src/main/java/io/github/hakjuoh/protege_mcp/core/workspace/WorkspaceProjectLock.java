package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/** One advisory process lock shared by every mutating transaction for a project root. */
final class WorkspaceProjectLock {

    private WorkspaceProjectLock() {
    }

    static Handle acquire(Path stateRoot, Path projectRoot) throws IOException {
        Path path = path(stateRoot, projectRoot);
        FileChannel channel = null;
        try {
            Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            channel = FileChannel.open(path, options);
            setOwnerOnly(path, false);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException held) {
                lock = null;
            }
            if (lock == null) {
                channel.close();
                throw new IOException("workspace transaction lock is already held");
            }
            return new Handle(channel, lock);
        } catch (IOException | RuntimeException error) {
            if (channel != null && channel.isOpen()) channel.close();
            throw error;
        }
    }

    static Path path(Path stateRoot, Path projectRoot) throws IOException {
        Path root = secureStateRoot(stateRoot);
        String projectKey = ArtifactStore.sha256(
                projectRoot.toString().getBytes(StandardCharsets.UTF_8)).substring("sha256:".length());
        return root.resolve(projectKey + ".lock");
    }

    private static Path secureStateRoot(Path requested) throws IOException {
        if (Files.exists(requested, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(requested)) {
            throw new IOException("workspace state root must not be a symbolic link: " + requested);
        }
        Files.createDirectories(requested);
        Path real = requested.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("workspace state root is not a directory: " + requested);
        }
        setOwnerOnly(real, true);
        return real;
    }

    private static void setOwnerOnly(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions
                    .fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // The platform does not expose POSIX permissions.
        }
    }

    record Handle(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
