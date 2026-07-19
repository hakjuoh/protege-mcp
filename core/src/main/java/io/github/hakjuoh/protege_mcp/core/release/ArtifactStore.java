package io.github.hakjuoh.protege_mcp.core.release;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-relative atomic writer for release and report artifacts (PLAN §4). Every write lands via a
 * sibling temp file and an atomic move, so a reader never observes a half-written artifact, and each
 * write returns the artifact's byte length and {@code sha256:}-prefixed digest for the manifest.
 *
 * <p>Confinement is the caller's contract: {@code root} is the canonical release output directory and
 * every requested relative path must resolve strictly beneath it. A path escaping the root — via
 * {@code ..} or an absolute component — is refused before any write. This class does no policy
 * resolution; the caller supplies an already-authorized {@code root}.
 */
public final class ArtifactStore {

    private final Path root;
    private final List<Written> written = new ArrayList<>();

    public ArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** One artifact's committed identity, for the manifest and result envelope. */
    public record Written(String path, String sha256, long bytes) { }

    /** SHA-256 over raw bytes, {@code sha256:}-prefixed lowercase hex — never truncated. */
    public static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Streaming SHA-256 of an existing file, without holding it all in memory. */
    public static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        } catch (IOException e) {
            throw new UncheckedIOException("could not hash " + file, e);
        }
    }

    /** Write UTF-8 text; returns the committed artifact identity (relative path, digest, bytes). */
    public Written writeText(String relativePath, String content) {
        return writeBytes(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Write raw bytes atomically beneath the root; refuses any escaping path. */
    public Written writeBytes(String relativePath, byte[] content) {
        Path target = resolveContained(relativePath);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            // Lexical containment is not enough: a symlinked child directory planted inside the
            // authorized root (e.g. an existing `reports` symlink) would redirect the write outside
            // it. Re-check the REAL path of the created parent before writing.
            Path realParent = target.getParent().toRealPath();
            if (!realParent.startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("artifact path '" + relativePath
                        + "' resolves through a symlink escaping the release output directory");
            }
            temp = Files.createTempFile(target.getParent(), ".release-", ".tmp");
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                // Some filesystems reject ATOMIC_MOVE across the same directory only rarely; fall
                // back to a plain replace, still via the temp file so no partial write is visible.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("could not write artifact " + relativePath, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort: never mask the actual write failure with temp cleanup.
                }
            }
        }
        Written record = new Written(root.relativize(target).toString().replace('\\', '/'),
                sha256(content), content.length);
        written.add(record);
        return record;
    }

    /** Every artifact committed through this store, in write order. */
    public List<Written> written() {
        return List.copyOf(written);
    }

    private Path resolveContained(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException(
                    "artifact path '" + relativePath + "' escapes the release output directory");
        }
        return resolved;
    }
}
