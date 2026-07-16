package io.github.hakjuoh.protege_mcp.policy;

import io.github.hakjuoh.protege_mcp.ro_crate.RoCrateVersion;

/** Stable identifiers pinned by project-policy v1's cross-application profile. */
public final class ProjectInteroperability {

    public static final String PROFILE_IRI =
            "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
    public static final String DEFAULT_RO_CRATE_FORMAT = "ro-crate-1.1";
    public static final String OWL2_SPEC = "https://www.w3.org/TR/owl2-overview/";

    private ProjectInteroperability() {
    }

    public static RoCrateVersion roCrateVersion(String format) {
        return RoCrateVersion.fromFormat(format);
    }
}
