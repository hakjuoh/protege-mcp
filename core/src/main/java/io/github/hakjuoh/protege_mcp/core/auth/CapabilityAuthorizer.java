package io.github.hakjuoh.protege_mcp.core.auth;

import java.util.List;
import java.util.Set;

/** One capability implication rule shared by the plugin and headless adapters. */
public final class CapabilityAuthorizer {

    public static final String LOCAL_ADMIN = "local:admin";

    private CapabilityAuthorizer() {
    }

    public static boolean allows(Set<String> granted, String required) {
        return granted != null && (granted.contains(required) || granted.contains(LOCAL_ADMIN));
    }

    public static List<String> missing(Set<String> granted, Set<String> required) {
        if (required == null) throw new IllegalArgumentException("required capabilities must not be null");
        return required.stream().filter(capability -> !allows(granted, capability))
                .sorted().toList();
    }
}
