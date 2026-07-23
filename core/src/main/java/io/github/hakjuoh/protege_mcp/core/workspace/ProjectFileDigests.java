package io.github.hakjuoh.protege_mcp.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Bounded, directory-anchored digests for optional project-confined regular files. */
public final class ProjectFileDigests {
    private ProjectFileDigests() {
    }

    /** Return the SHA-256 digest, or null when the securely resolved file is absent. */
    public static String sha256IfPresent(Path projectRoot, Path file, long maximumBytes)
            throws IOException {
        if (projectRoot == null || file == null) {
            throw new IllegalArgumentException("projectRoot and file are required");
        }
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        Path root = projectRoot.toRealPath();
        Path requested = file.toAbsolutePath().normalize();
        if (!requested.startsWith(root)) {
            throw new IOException("project file is outside the project root");
        }
        Path parent = requested.getParent();
        if (parent == null) throw new IOException("project file has no parent");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(projectRoot, file)) {
            if (!anchor.exists(anchor.targetName())) return null;
            return anchor.identity(anchor.targetName(), maximumBytes).sha256();
        } catch (SecureTargetAnchor.SizeLimitExceededException exceeded) {
            throw new IOException("project file exceeds bound", exceeded);
        }
    }
}
