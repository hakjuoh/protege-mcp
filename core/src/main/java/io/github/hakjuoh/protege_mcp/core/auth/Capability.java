package io.github.hakjuoh.protege_mcp.core.auth;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Stable public authorization capabilities shared by every delivery adapter. */
public enum Capability {
    ONTOLOGY_READ("ontology:read"),
    ONTOLOGY_CURATE("ontology:curate"),
    ONTOLOGY_ADMIN("ontology:admin"),
    ONTOLOGY_RELEASE("ontology:release"),
    SERVER_ADMIN("server:admin"),
    FILESYSTEM_PROJECT_READ("filesystem:project:read"),
    FILESYSTEM_PROJECT_WRITE("filesystem:project:write"),
    FILESYSTEM_EXTERNAL("filesystem:external"),
    NETWORK_ACCESS("network:access");

    private final String value;

    Capability(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Capability fromValue(String value) {
        return Arrays.stream(values()).filter(capability -> capability.value.equals(value))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("unknown capability scope: " + value));
    }

    public static Set<String> valuesSet() {
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(Capability.values()).map(Capability::value).forEach(values::add);
        return Collections.unmodifiableSet(values);
    }
}
