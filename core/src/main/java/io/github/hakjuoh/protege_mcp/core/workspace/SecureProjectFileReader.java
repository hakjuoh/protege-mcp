package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

/**
 * Stable, no-follow capture of one project-confined regular file.
 *
 * <p>The secure directory anchor is held while the target identity is checked and copied into an
 * owner-only sibling snapshot. Callers parse the returned bytes and never reopen the mutable path.</p>
 */
public final class SecureProjectFileReader {
    private SecureProjectFileReader() {
    }

    public static Captured capture(
            Path projectRoot, Path target, long maximumBytes) throws IOException {
        if (projectRoot == null || target == null
                || maximumBytes < 1
                || maximumBytes > WorkspaceTransaction.MAX_STAGED_BYTES) {
            throw new IllegalArgumentException("secure project read arguments are invalid");
        }
        try (SecureTargetAnchor anchor =
                SecureTargetAnchor.open(projectRoot, target)) {
            Path name = anchor.targetName();
            if (!anchor.exists(name)) {
                throw new IOException("project file does not exist");
            }
            FilesystemProjectWorkspace.FileIdentity identity;
            try {
                identity = anchor.identity(name, maximumBytes);
            } catch (SecureTargetAnchor.SizeLimitExceededException exceeded) {
                throw new IOException("project file exceeds its byte bound", exceeded);
            }
            Path snapshot = anchor.snapshot(name, identity, maximumBytes);
            try {
                byte[] bytes = Files.readAllBytes(snapshot);
                if (bytes.length != identity.bytes()
                        || !ArtifactStore.sha256(bytes).equals(identity.sha256())) {
                    throw new IOException(
                            "private project-file snapshot failed digest verification");
                }
                return new Captured(bytes, identity.sha256(), identity.bytes());
            } finally {
                Files.deleteIfExists(snapshot);
            }
        }
    }

    public record Captured(byte[] bytes, String sha256, long size) {
        public Captured {
            bytes = bytes == null ? null : bytes.clone();
            if (bytes == null || sha256 == null || size != bytes.length) {
                throw new IllegalArgumentException("captured project file is invalid");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
