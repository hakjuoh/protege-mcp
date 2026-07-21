package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Symlink-resistant owner-only directories and durable atomic files for local provider state. */
final class OwnerOnlyFiles {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> NON_OWNER_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final long LOCK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

    private OwnerOnlyFiles() { }

    static Path prepareDirectory(Path configured) throws ProviderFailure {
        if (configured == null) throw failure("provider_store_invalid", "Provider store path is missing");
        Path path = configured.toAbsolutePath().normalize();
        try {
            Path parent = path.getParent();
            if (parent == null || Files.isSymbolicLink(parent)) {
                throw new IOException("store parent is invalid");
            }
            Path realParent = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
            path = realParent.resolve(path.getFileName());
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectory(path);
            } else {
                try {
                    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
                            DIRECTORY_PERMISSIONS));
                } catch (UnsupportedOperationException unsupported) {
                    Files.createDirectory(path);
                }
                applyOwnerOnly(path, true);
            }
            requireDirectory(path);
            return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException invalid) {
            throw failure("provider_store_invalid", "Provider store is not owner-only and regular");
        }
    }

    static byte[] read(Path root, String name, int maximum) throws ProviderFailure {
        Path directory = prepareDirectory(root);
        Path file = child(directory, name);
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.NoSuchFileException(file.toString());
            }
            return readFile(file, maximum);
        } catch (java.nio.file.NoSuchFileException missing) {
            throw failure("provider_store_missing", "Provider store file is missing");
        } catch (IOException | UnsupportedOperationException invalid) {
            throw failure("provider_store_invalid", "Provider store file is invalid");
        }
    }

    static boolean exists(Path root, String name) throws ProviderFailure {
        Path directory = prepareDirectory(root);
        Path file = child(directory, name);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            requireFile(file);
            return true;
        } catch (IOException | UnsupportedOperationException invalid) {
            throw failure("provider_store_invalid", "Provider store file is invalid");
        }
    }

    static void write(Path root, String name, byte[] value) throws ProviderFailure {
        write(root, name, value, MutationObserver.NONE);
    }

    static void write(Path root, String name, byte[] value, MutationObserver observer)
            throws ProviderFailure {
        if (value == null) throw failure("provider_store_write_failed", "Provider store value is missing");
        Path directory = prepareDirectory(root);
        Path target = child(directory, name);
        byte[] snapshot = value.clone();
        Path temporary = null;
        boolean committed = false;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireFile(target);
            temporary = Files.createTempFile(directory, ".provider-", ".tmp");
            applyOwnerOnly(temporary, false);
            try (OpenedFile opened = openChannel(directory, temporary.getFileName().toString(),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                FileChannel channel = opened.channel();
                ByteBuffer buffer = ByteBuffer.wrap(snapshot);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            requireFile(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic replacement is unavailable", unsupported);
            }
            temporary = null;
            committed = true;
            observer.afterCommit("write", target);
            forceDirectory(directory);
            requireFile(target);
            byte[] installed = readFile(target, snapshot.length);
            try {
                if (!Arrays.equals(snapshot, installed)) throw new IOException("commit mismatch");
            } finally {
                Arrays.fill(installed, (byte) 0);
            }
        } catch (IOException | UnsupportedOperationException invalid) {
            if (committed) {
                throw failure("provider_store_outcome_unknown",
                        "Provider store update committed but durability verification failed");
            }
            throw failure("provider_store_write_failed", "Provider store update failed");
        } finally {
            Arrays.fill(snapshot, (byte) 0);
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    static void delete(Path root, String name) throws ProviderFailure {
        delete(root, name, MutationObserver.NONE);
    }

    static void delete(Path root, String name, MutationObserver observer) throws ProviderFailure {
        Path directory = prepareDirectory(root);
        Path target = child(directory, name);
        Path tombstone = directory.resolve("." + name + ".deleted-" + UUID.randomUUID());
        boolean committed = false;
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
            requireFile(target);
            Files.move(target, tombstone, StandardCopyOption.ATOMIC_MOVE);
            committed = true;
            observer.afterCommit("delete", target);
            forceDirectory(directory);
            Files.delete(tombstone);
            forceDirectory(directory);
        } catch (IOException | UnsupportedOperationException invalid) {
            if (committed) {
                throw failure("provider_store_outcome_unknown",
                        "Provider store delete committed but durability verification failed");
            }
            throw failure("provider_store_write_failed", "Provider store delete failed");
        }
    }

    static <T> T withLock(Path root, String name, LockedOperation<T> operation)
            throws ProviderFailure {
        Path directory = prepareDirectory(root);
        Path lock = child(directory, name);
        ReentrantLock mutex = JVM_LOCKS.computeIfAbsent(lock, ignored -> new ReentrantLock());
        long deadline = System.nanoTime() + LOCK_TIMEOUT_NANOS;
        boolean entered = false;
        try {
            try {
                entered = mutex.tryLock(LOCK_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure("provider_store_write_failed", "Provider store lock was interrupted");
            }
            if (!entered) throw failure("provider_store_write_failed", "Provider store lock timed out");
            try {
                if (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) createLockFile(lock);
                requireFile(lock);
                try (OpenedFile opened = openChannel(directory, lock.getFileName().toString(),
                        StandardOpenOption.WRITE)) {
                    FileChannel channel = opened.channel();
                    requireFile(lock);
                    FileLock acquired = acquire(channel, deadline);
                    try (acquired) {
                        requireFile(lock);
                        cleanupRemnants(directory);
                        return operation.run();
                    }
                }
            } catch (ProviderFailure typed) {
                throw typed;
            } catch (IOException | UnsupportedOperationException invalid) {
                throw failure("provider_store_write_failed", "Provider store lock failed");
            }
        } finally {
            if (entered) mutex.unlock();
        }
    }

    private static byte[] readFile(Path file, int maximum) throws IOException {
        requireFile(file);
        try (OpenedFile opened = openChannel(file.getParent(), file.getFileName().toString(),
                StandardOpenOption.READ)) {
            FileChannel channel = opened.channel();
            requireFile(file);
            long size = channel.size();
            if (size < 0 || size > maximum || size > Integer.MAX_VALUE) {
                throw new IOException("file exceeds bound");
            }
            byte[] result = new byte[(int) size];
            ByteBuffer buffer = ByteBuffer.wrap(result);
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer);
                if (count < 0) throw new IOException("file was truncated");
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("file grew");
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private static OpenedFile openChannel(Path directory, String name, StandardOpenOption... modes)
            throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        if (stream instanceof SecureDirectoryStream<?>) {
            SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) stream;
            Set<OpenOption> options = new HashSet<>();
            options.addAll(List.of(modes));
            options.add(LinkOption.NOFOLLOW_LINKS);
            SeekableByteChannel channel = null;
            try {
                channel = secure.newByteChannel(Path.of(name), options);
                if (!(channel instanceof FileChannel fileChannel)) {
                    throw new IOException("secure channel does not support file locking");
                }
                return new OpenedFile(fileChannel, stream);
            } catch (IOException | RuntimeException invalid) {
                if (channel != null) try { channel.close(); } catch (IOException ignored) { }
                try { stream.close(); } catch (IOException ignored) { }
                throw invalid;
            }
        }
        stream.close();
        Path file = directory.resolve(name);
        Set<OpenOption> options = new HashSet<>();
        options.addAll(List.of(modes));
        options.add(LinkOption.NOFOLLOW_LINKS);
        return new OpenedFile(openNoFollow(file, options), null);
    }

    static FileChannel openNoFollow(Path file, Set<? extends OpenOption> options)
            throws IOException {
        Set<OpenOption> safe = new HashSet<>(options);
        safe.add(LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel channel = null;
        try {
            channel = Files.newByteChannel(file, safe);
            if (!(channel instanceof FileChannel fileChannel)) {
                throw new IOException("platform channel cannot provide file locking");
            }
            return fileChannel;
        } catch (IOException | RuntimeException invalid) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            throw invalid;
        }
    }

    private record OpenedFile(FileChannel channel, DirectoryStream<Path> directory)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try { channel.close(); } catch (IOException invalid) { failure = invalid; }
            if (directory != null) {
                try { directory.close(); } catch (IOException invalid) {
                    if (failure == null) failure = invalid;
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static void createLockFile(Path lock) throws IOException {
        try {
            try {
                Files.createFile(lock, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
            } catch (UnsupportedOperationException unsupported) {
                Files.createFile(lock);
            }
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            // Another process created the stable lock inode; verify it before opening.
        }
        applyOwnerOnly(lock, false);
    }

    private static FileLock acquire(FileChannel channel, long deadline) throws IOException {
        do {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException busy) {
                // Another store instance in this JVM or a test seam owns the OS lock.
            }
            if (System.nanoTime() >= deadline) throw new IOException("store lock timed out");
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("store lock interrupted", interrupted);
            }
        } while (true);
    }

    private static void cleanupRemnants(Path directory) throws IOException {
        int visited = 0;
        boolean changed = false;
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (++visited > 1_024) throw new IOException("provider store has too many entries");
                String name = entry.getFileName().toString();
                boolean temporary = name.startsWith(".provider-") && name.endsWith(".tmp");
                boolean tombstone = name.startsWith(".") && name.contains(".deleted-");
                if (!temporary && !tombstone) continue;
                requireFile(entry);
                Files.delete(entry);
                changed = true;
            }
        }
        if (changed) forceDirectory(directory);
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static Path child(Path directory, String name) throws ProviderFailure {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw failure("provider_store_invalid", "Provider store key is invalid");
        }
        Path child = directory.resolve(name).normalize();
        if (!child.getParent().equals(directory)) {
            throw failure("provider_store_invalid", "Provider store key escapes its root");
        }
        return child;
    }

    private static void requireDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular directory");
        }
        verifyOwnerOnly(path, true);
    }

    private static void requireFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file");
        }
        verifyOwnerOnly(path, false);
    }

    private static void applyOwnerOnly(Path path, boolean directory) throws IOException {
        boolean enforced = false;
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path,
                    directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS);
            enforced = true;
        }
        AclFileAttributeView view = Files.getFileAttributeView(path,
                AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            view.setAcl(List.of(entry));
            enforced = true;
        } else if (enforced && isMacOs()) {
            removeMacAcl(path);
        }
        if (!enforced) throw new IOException("no enforceable permission model");
    }

    private static void verifyOwnerOnly(Path path, boolean directory) throws IOException {
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (!owner.equals(processOwner())) throw new IOException("path is owned by another principal");
        boolean enforced = false;
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(NON_OWNER_PERMISSIONS::contains)
                    || !permissions.contains(PosixFilePermission.OWNER_READ)
                    || !permissions.contains(PosixFilePermission.OWNER_WRITE)
                    || directory && !permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                throw new IOException("permissions are not owner-only");
            }
            enforced = true;
        }
        AclFileAttributeView view = Files.getFileAttributeView(path,
                AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            for (AclEntry entry : view.getAcl()) {
                if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)
                        && !entry.permissions().isEmpty()) {
                    throw new IOException("ACL grants a non-owner access");
                }
            }
            enforced = true;
        } else if (enforced && isMacOs()) {
            verifyMacAclAbsent(path);
        }
        if (!enforced) throw new IOException("no enforceable permission model");
    }

    private static ProviderFailure failure(String code, String message) {
        return new ProviderFailure(code, message, false);
    }

    private static volatile UserPrincipal processOwner;

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
    }

    private static void removeMacAcl(Path path) throws IOException {
        runMacCommand(List.of("/bin/chmod", "-N", path.toString()), false);
    }

    private static void verifyMacAclAbsent(Path path) throws IOException {
        String output = runMacCommand(List.of("/bin/ls", "-lde", "--", path.toString()), true);
        String normalized = output.stripTrailing();
        int separator = normalized.indexOf(' ');
        String permissions = separator < 0 ? normalized : normalized.substring(0, separator);
        if (normalized.contains("\n") || !permissions.matches("[-d][rwx-]{9}@?")) {
            throw new IOException("extended ACL state is not owner-only");
        }
    }

    private static String runMacCommand(List<String> command, boolean capture) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("permission helper timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("permission helper interrupted", interrupted);
        }
        byte[] output = process.getInputStream().readNBytes(4_096);
        if (process.exitValue() != 0 || process.getInputStream().read() != -1) {
            throw new IOException("permission helper failed");
        }
        return capture ? new String(output, StandardCharsets.UTF_8) : "";
    }

    private static UserPrincipal processOwner() throws IOException {
        UserPrincipal cached = processOwner;
        if (cached != null) return cached;
        synchronized (OwnerOnlyFiles.class) {
            if (processOwner != null) return processOwner;
            Path probe = Files.createTempFile("protege-mcp-owner-", ".tmp");
            try {
                processOwner = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
                return processOwner;
            } finally {
                Files.deleteIfExists(probe);
            }
        }
    }

    @FunctionalInterface
    interface LockedOperation<T> {
        T run() throws ProviderFailure;
    }

    @FunctionalInterface
    interface MutationObserver {
        MutationObserver NONE = (operation, target) -> { };

        void afterCommit(String operation, Path target) throws IOException;
    }
}
