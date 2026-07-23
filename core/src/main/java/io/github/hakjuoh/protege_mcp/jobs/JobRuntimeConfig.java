package io.github.hakjuoh.protege_mcp.jobs;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Policy-tightenable admission and retention bounds for one in-memory job runtime. */
public record JobRuntimeConfig(Set<JobType> allowedTypes, int queueCapacity,
        int activePerPrincipal, int retainedPerPrincipal, int retainedPerBackend,
        Duration retention) {
    public JobRuntimeConfig {
        Set<JobType> types = new LinkedHashSet<>(
                allowedTypes == null ? Set.of() : allowedTypes);
        if (types.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("allowed job types are invalid");
        }
        allowedTypes = JobHashes.immutableSortedSet(types);
        if (queueCapacity < 1 || queueCapacity > 32
                || activePerPrincipal < 1 || activePerPrincipal > 8
                || retainedPerPrincipal < activePerPrincipal || retainedPerPrincipal > 32
                || retainedPerBackend < retainedPerPrincipal || retainedPerBackend > 128
                || retention == null || retention.compareTo(Duration.ofSeconds(60)) < 0
                || retention.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("job runtime configuration is outside hard bounds");
        }
    }

    public static JobRuntimeConfig defaults() {
        return new JobRuntimeConfig(Set.of(JobType.values()), 32, 8, 32, 128,
                Duration.ofHours(1));
    }
}
