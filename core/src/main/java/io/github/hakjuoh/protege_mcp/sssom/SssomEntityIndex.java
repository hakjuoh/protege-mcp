package io.github.hakjuoh.protege_mcp.sssom;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exact local entity presence/deprecation snapshot used by shared mapping validation. */
public record SssomEntityIndex(Set<String> present, Set<String> deprecated, boolean available) {
    public SssomEntityIndex(Set<String> present, Set<String> deprecated) {
        this(present, deprecated, true);
    }

    public SssomEntityIndex {
        present = present == null || present.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(present));
        deprecated = deprecated == null || deprecated.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(deprecated));
    }

    public static SssomEntityIndex unavailable() {
        return new SssomEntityIndex(Set.of(), Set.of(), false);
    }
}
