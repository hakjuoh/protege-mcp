package io.github.hakjuoh.protege_mcp.tools;

import java.nio.file.Path;
import java.util.List;

import io.github.hakjuoh.protege_mcp.core.qc.ValidationAssetLoader;

/** Source-compatible plugin adapter for the shared bounded invariant loader. */
final class InvariantFiles {

    private InvariantFiles() {
    }

    static List<Invariants.Invariant> load(List<Path> paths) {
        try {
            return ValidationAssetLoader.loadInvariants(paths).stream()
                    .map(InvariantFiles::plugin).toList();
        } catch (ValidationAssetLoader.AssetException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    static Invariants.Invariant read(Path path) {
        try {
            return plugin(ValidationAssetLoader.readInvariant(path));
        } catch (ValidationAssetLoader.AssetException error) {
            throw new ToolArgException(error.getMessage());
        }
    }

    private static Invariants.Invariant plugin(
            io.github.hakjuoh.protege_mcp.core.qc.InvariantQcService.Invariant invariant) {
        return new Invariants.Invariant(invariant.id(), invariant.message(), invariant.severity(),
                invariant.sparql(), invariant.includeInferred());
    }
}
