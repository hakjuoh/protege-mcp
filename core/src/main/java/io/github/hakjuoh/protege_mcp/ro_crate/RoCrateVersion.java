package io.github.hakjuoh.protege_mcp.ro_crate;

import java.util.Arrays;

/** Supported RO-Crate Recommendation versions and their normative attached-crate identifiers. */
public enum RoCrateVersion {
    V1_0("1.0", "ro-crate-metadata.jsonld", false),
    V1_1("1.1", "ro-crate-metadata.json", false),
    V1_2("1.2", "ro-crate-metadata.json", true),
    V1_3("1.3", "ro-crate-metadata.json", true);

    private final String version;
    private final String format;
    private final String context;
    private final String specification;
    private final String metadataFile;
    private final boolean formalProfiles;

    RoCrateVersion(String version, String metadataFile, boolean formalProfiles) {
        this.version = version;
        this.format = "ro-crate-" + version;
        this.context = "https://w3id.org/ro/crate/" + version + "/context";
        this.specification = "https://w3id.org/ro/crate/" + version;
        this.metadataFile = metadataFile;
        this.formalProfiles = formalProfiles;
    }

    public String version() { return version; }
    public String format() { return format; }
    public String context() { return context; }
    public String specification() { return specification; }
    public String metadataFile() { return metadataFile; }
    public boolean formalProfiles() { return formalProfiles; }

    public static RoCrateVersion fromFormat(String format) {
        return Arrays.stream(values()).filter(value -> value.format.equals(format)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported RO-Crate format: " + format));
    }
}
