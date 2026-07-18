package io.github.hakjuoh.protege_mcp.tools;

import java.util.List;

/** Project-policy inputs for the interoperability QC stage. */
final class QcInteroperabilityConfig {
    final String profile;
    final List<String> additionalProfiles;
    final String roCrateFormat;
    final String manifestPath;
    final String rootArtifact;
    final String canonicalizationAlgorithm;
    final String hashAlgorithm;
    final String scope;
    final int timeoutMs;

    QcInteroperabilityConfig(String profile, List<String> additionalProfiles,
            String roCrateFormat, String manifestPath, String rootArtifact,
            String canonicalizationAlgorithm, String hashAlgorithm, String scope, int timeoutMs) {
        this.profile = profile;
        this.additionalProfiles = List.copyOf(additionalProfiles);
        this.roCrateFormat = roCrateFormat;
        this.manifestPath = manifestPath;
        this.rootArtifact = rootArtifact;
        this.canonicalizationAlgorithm = canonicalizationAlgorithm;
        this.hashAlgorithm = hashAlgorithm;
        this.scope = scope;
        this.timeoutMs = timeoutMs;
    }
}
