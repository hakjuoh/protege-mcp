package io.github.hakjuoh.protege_mcp.policy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;

/** Immutable result of policy discovery, parsing, defaulting, and validation. */
public final class ProjectPolicy {

    private final boolean loaded;
    private final String discovery;
    private final Path path;
    private final Path projectRoot;
    private final String digest;
    private final Map<String, Object> effective;
    private final Map<String, List<Path>> assets;
    private final List<PolicyIssue> issues;
    private final PolicyMigrationRecommendation migration;

    ProjectPolicy(boolean loaded, String discovery, Path path, Path projectRoot, String digest,
            Map<String, Object> effective, Map<String, List<Path>> assets, List<PolicyIssue> issues,
            PolicyMigrationRecommendation migration) {
        this.loaded = loaded;
        this.discovery = discovery;
        this.path = path;
        this.projectRoot = projectRoot;
        this.digest = digest;
        this.effective = ImmutableJson.resultMap(new LinkedHashMap<>(effective));
        Map<String, List<Path>> assetCopy = new LinkedHashMap<>();
        assets.forEach((key, value) -> assetCopy.put(key,
                Collections.unmodifiableList(new ArrayList<>(value))));
        this.assets = Collections.unmodifiableMap(assetCopy);
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        this.migration = migration;
    }

    public static ProjectPolicy notFound() {
        return new ProjectPolicy(false, "none", null, null, null,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), null);
    }

    public boolean loaded() {
        return loaded;
    }

    public boolean valid() {
        return loaded && issues.stream().noneMatch(i -> "error".equals(i.severity()));
    }

    public String discovery() {
        return discovery;
    }

    public Path path() {
        return path;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public String digest() {
        return digest;
    }

    public Map<String, Object> effective() {
        return effective;
    }

    public Map<String, List<Path>> assets() {
        return assets;
    }

    public List<PolicyIssue> issues() {
        return issues;
    }

    public int version() {
        Object version = effective.get("version");
        if (!(version instanceof Number number)) return 0;
        int integral = number.intValue();
        return number.doubleValue() == integral ? integral : 0;
    }

    public PolicyMigrationRecommendation migration() {
        return migration;
    }

}
