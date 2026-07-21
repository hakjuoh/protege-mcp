package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shared advisory-lock boundary for project-confined filesystem transactions.
 *
 * <p>Every delivery adapter must use this boundary rather than an adapter-local mutex so a live
 * Protégé process and a headless process serialize mutations to the same project.
 */
public final class ProjectFileLock {

    private ProjectFileLock() {
    }

    public static <T> T withLock(Path projectRoot, IoCallable<T> body) throws IOException {
        return withLock(defaultStateRoot(), projectRoot, body);
    }

    public static <T> T withLock(Path stateRoot, Path projectRoot, IoCallable<T> body)
            throws IOException {
        if (stateRoot == null || projectRoot == null || body == null) {
            throw new IllegalArgumentException("project lock arguments must not be null");
        }
        Path canonicalRoot = projectRoot.toRealPath();
        try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                stateRoot.toAbsolutePath().normalize(), canonicalRoot)) {
            return body.call();
        }
    }

    public static Path defaultStateRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home is unavailable");
        }
        return Path.of(home).resolve(".protege-mcp").resolve("locks");
    }

    @FunctionalInterface
    public interface IoCallable<T> {
        T call() throws IOException;
    }

    /** The shared project lock is currently owned by another in-process or external transaction. */
    public static final class UnavailableException extends IOException {
        UnavailableException(String message) {
            super(message);
        }
    }
}
