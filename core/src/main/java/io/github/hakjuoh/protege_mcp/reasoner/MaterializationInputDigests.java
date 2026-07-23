package io.github.hakjuoh.protege_mcp.reasoner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.hakjuoh.protege_mcp.core.workspace.ProjectFileDigests;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

/** Shared bounded digests for materialization input identities. */
public final class MaterializationInputDigests {
    private MaterializationInputDigests() {
    }

    public static String mappingRevision(ProjectPolicy policy, long maximumBytes)
            throws IOException {
        if (policy == null) return null;
        if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
        List<Path> paths = policy.assets().getOrDefault("mapping_store", List.of());
        if (paths.isEmpty()) return null;
        if (paths.size() != 1) {
            throw new IOException("mapping policy resolved multiple stores");
        }
        Path configured = paths.get(0).toAbsolutePath().normalize();
        if (policy.projectRoot() == null) {
            throw new IOException("mapping store is not a confined regular file");
        }
        try {
            return ProjectFileDigests.sha256IfPresent(
                    policy.projectRoot(), configured, maximumBytes);
        } catch (IOException unsafe) {
            throw new IOException("mapping store is not a stable confined regular file", unsafe);
        }
    }
}
