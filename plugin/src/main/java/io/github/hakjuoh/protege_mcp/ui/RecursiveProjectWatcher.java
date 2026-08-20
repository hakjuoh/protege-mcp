package io.github.hakjuoh.protege_mcp.ui;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recursive, debounced filesystem watcher for one canonical project root. */
final class RecursiveProjectWatcher implements AutoCloseable {

    private static final long DEBOUNCE_MILLIS = 200;
    private static final int MAX_WATCHED_DIRECTORIES = 10_000;

    private final Path root;
    private final Runnable callback;
    private final int maxWatchedDirectories;
    private final WatchService watchService;
    private final Map<WatchKey, Path> directories = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;
    private final AtomicBoolean registrationTruncated = new AtomicBoolean();
    private volatile boolean closed;

    RecursiveProjectWatcher(Path root, Runnable callback) throws IOException {
        this(root, callback, MAX_WATCHED_DIRECTORIES);
    }

    RecursiveProjectWatcher(Path root, Runnable callback, int maxWatchedDirectories)
            throws IOException {
        if (maxWatchedDirectories < 1) {
            throw new IllegalArgumentException("maxWatchedDirectories must be positive");
        }
        this.root = root.toRealPath();
        this.callback = callback;
        this.maxWatchedDirectories = maxWatchedDirectories;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "protege-project-explorer-watcher");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(() -> {
            try {
                registerTree(this.root);
            } catch (IOException | RuntimeException ignored) {
                if (!closed) registrationTruncated.set(true);
            }
            scheduleCallback();
            watchLoop();
        });
    }

    Path root() { return root; }
    boolean registrationTruncated() { return registrationTruncated.get(); }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                if (Files.isSymbolicLink(directory)) return FileVisitResult.SKIP_SUBTREE;
                if (directories.size() >= maxWatchedDirectories) {
                    registrationTruncated.set(true);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                register(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path directory) throws IOException {
        WatchKey key = directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
        directories.put(key, directory);
    }

    private void watchLoop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException closedService) {
                return;
            }
            Path directory = directories.get(key);
            if (directory != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        recoverFromOverflow();
                        continue;
                    }
                    Object context = event.context();
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && context instanceof Path relative) {
                        Path created = directory.resolve(relative);
                        if (Files.isDirectory(created) && !Files.isSymbolicLink(created)) {
                            registerCreatedDirectory(created);
                        }
                    }
                    scheduleCallback();
                }
            }
            if (!key.reset()) directories.remove(key);
        }
    }

    void recoverFromOverflow() {
        try {
            registerTree(root);
        } catch (IOException | RuntimeException ignored) {
            if (!closed) registrationTruncated.set(true);
        }
        scheduleCallback();
    }

    private void registerCreatedDirectory(Path created) {
        try {
            registerTree(created);
        } catch (IOException | RuntimeException ignored) {
            // A rename is harmless; a persistent directory means live coverage is incomplete.
            if (!closed && Files.isDirectory(created)) registrationTruncated.set(true);
        }
    }

    private synchronized void scheduleCallback() {
        if (closed) return;
        if (pending != null) pending.cancel(false);
        pending = executor.schedule(callback, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pending != null) pending.cancel(false);
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        executor.shutdownNow();
        directories.clear();
    }
}
