package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * File-system contract shared by the broker process and the Protégé instances that discover it.
 *
 * <p>Everything lives under one owner-only directory (default {@code ~/.protege-mcp}, {@code 0700}):
 * {@code secret} (the same-user trust anchor for the {@code /internal} API and never sent to MCP
 * clients), {@code broker.json} (the live broker's pid/port/version, written atomically), {@code
 * broker.lock} (the singleton {@link java.nio.channels.FileLock} a broker holds while it serves —
 * see {@link BrokerMain}), {@code oauth.json} (the broker's persisted OAuth clients + tokens),
 * {@code broker.log} (the spawned process's stdout/stderr) and {@code jars/} (content-named
 * classpath copies the broker runs from — see {@link BrokerSpawner}). Same-user trust comes from the
 * file permissions: only a process that can read {@code secret} may register instances or ask the
 * broker to shut down.
 */
public final class BrokerHome {

    public static final String DIR_NAME = ".protege-mcp";

    private static final Set<PosixFilePermission> DIR_PERMS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path dir;

    public BrokerHome(Path dir) {
        this.dir = dir;
    }

    public static BrokerHome defaultHome() {
        return new BrokerHome(Path.of(System.getProperty("user.home"), DIR_NAME));
    }

    public Path dir() {
        return dir;
    }

    public Path stateFile() {
        return dir.resolve("broker.json");
    }

    public Path secretFile() {
        return dir.resolve("secret");
    }

    public Path oauthFile() {
        return dir.resolve("oauth.json");
    }

    public Path logFile() {
        return dir.resolve("broker.log");
    }

    /**
     * The singleton lock file: a booting broker {@code tryLock}s it (retrying while a dying holder
     * finishes exiting) and keeps the lock until it stops serving, releasing it explicitly on a
     * graceful stop (the OS releases it even on a crash — no stale-lock handling needed). The file
     * itself is never deleted; only the lock matters.
     */
    public Path lockFile() {
        return dir.resolve("broker.lock");
    }

    /** Directory of staged classpath copies the broker process launches from. */
    public Path jarsDir() {
        return dir.resolve("jars");
    }

    /** Create the home directory owner-only if missing (permissions are best-effort on non-POSIX). */
    public void ensureDir() throws IOException {
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_PERMS));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(dir);
            }
        }
    }

    /**
     * Read the directory secret, minting and persisting a fresh one atomically when absent. Both the
     * broker and the instances call this; whoever runs first creates it, and a concurrent loser of
     * the atomic create simply reads the winner's value.
     */
    public String ensureDirSecret() throws IOException {
        ensureDir();
        Path file = secretFile();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (Files.exists(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String minted = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                writeOwnerOnly(file, minted, false);
                return minted;
            } catch (FileAlreadyExistsException raced) {
                // another process minted first — loop re-reads it
            }
        }
        String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (existing.isEmpty()) {
            throw new IOException("could not establish the broker directory secret at " + file);
        }
        return existing;
    }

    /** Atomically replace the state file (temp file + move) so readers never see a torn write. */
    public void writeState(BrokerState state) throws IOException {
        ensureDir();
        Path tmp = dir.resolve("broker.json.tmp-" + ProcessHandle.current().pid());
        writeOwnerOnly(tmp, state.toJson(), true);
        try {
            Files.move(tmp, stateFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException nonAtomicFs) {
            Files.move(tmp, stateFile(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The last written broker state, or empty when absent/unreadable (treat as "no broker"). */
    public Optional<BrokerState> readState() {
        try {
            Path file = stateFile();
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            return BrokerState.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Atomically replace {@code oauth.json}, owner-only (same discipline as the state file). */
    public void writeOauthState(String json) throws IOException {
        ensureDir();
        Path tmp = dir.resolve("oauth.json.tmp-" + ProcessHandle.current().pid());
        writeOwnerOnly(tmp, json, true);
        try {
            Files.move(tmp, oauthFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException nonAtomicFs) {
            Files.move(tmp, oauthFile(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Remove the state file, but only if it still names {@code pid} (don't erase a successor's). */
    public void deleteStateIfOwnedBy(long pid) {
        try {
            Optional<BrokerState> state = readState();
            if (state.isPresent() && state.get().pid == pid) {
                Files.deleteIfExists(stateFile());
            }
        } catch (IOException ignored) {
            // best-effort cleanup; a stale file is handled by the liveness probe on next discovery
        }
    }

    private static void writeOwnerOnly(Path file, String content, boolean replace) throws IOException {
        if (replace) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } else {
            // CREATE_NEW gives the atomic "first writer wins" used by ensureDirSecret()
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        }
        try {
            Files.setPosixFilePermissions(file, FILE_PERMS);
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX file system — the directory ACL is the only guard there
        }
    }
}
