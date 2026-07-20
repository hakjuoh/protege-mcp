package io.github.hakjuoh.protege_mcp.core.workspace;

import java.nio.file.Path;

/** One exact local input captured into an owner-only temporary workspace. */
public record WorkspaceSource(String kind, Path original, Path captured, String sha256, long bytes) {
    public WorkspaceSource {
        if (kind == null || kind.isBlank() || original == null || captured == null
                || sha256 == null || !sha256.matches("sha256:[0-9a-f]{64}") || bytes < 0) {
            throw new IllegalArgumentException("invalid workspace source");
        }
        original = original.toAbsolutePath().normalize();
        captured = captured.toAbsolutePath().normalize();
    }
}
