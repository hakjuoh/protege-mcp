package io.github.hakjuoh.protege_mcp.core.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ConcurrentHashMap;

/** JVM- and process-wide serialization for one project's append/rotate/export boundary. */
final class AuditFileMutex {

    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface IoOperation<T> {
        T run() throws IOException;
    }

    private AuditFileMutex() {
    }

    static <T> T withLock(Path auditRoot, String projectHash, IoOperation<T> operation) {
        if (projectHash == null || !projectHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("invalid audit project hash");
        }
        Path lockDirectory = auditRoot.resolve(".locks").toAbsolutePath().normalize();
        try {
            if (Files.exists(lockDirectory, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(lockDirectory)) {
                throw new IllegalStateException("audit lock directory must not be a symbolic link");
            }
            Files.createDirectories(lockDirectory);
            AuditLog.restrictDirectory(lockDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("could not prepare the audit lock directory", e);
        }
        // Exactly 256 possible shard files bound both disk use and in-process lock identities.
        Path lock = lockDirectory.resolve(projectHash.substring(0, 2) + ".lock");
        Object monitor = JVM_LOCKS.computeIfAbsent(lock, ignored -> new Object());
        synchronized (monitor) {
            try {
                if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(lock)) {
                    throw new IllegalStateException("audit project lock must not be a symbolic link");
                }
                if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        try {
                            Files.createFile(lock, PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
                        } catch (FileAlreadyExistsException raced) {
                            // Another process created the same project lock after our existence check.
                        }
                    } catch (UnsupportedOperationException nonPosix) {
                        try {
                            Files.createFile(lock);
                        } catch (FileAlreadyExistsException raced) {
                            // Same cross-process first-use race on a non-POSIX filesystem.
                        }
                    }
                }
                AuditLog.restrictFile(lock);
                try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS); var ignored = channel.lock()) {
                    return operation.run();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not acquire the audit project lock", e);
            }
        }
    }
}
