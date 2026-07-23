package io.github.hakjuoh.protege_mcp.reasoner;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owner-local bounded artifact store shared by synchronous previews and later job execution. */
public final class MaterializationArtifactStore {
    private static final int MAXIMUM_BACKEND_ARTIFACTS = 128;
    private static final long MAXIMUM_BACKEND_BYTES = 512L * 1024 * 1024;
    private final Clock clock;
    private final int maximumArtifacts;
    private final long maximumBytes;
    private final Map<String, Entry> artifacts = new LinkedHashMap<>();
    private long retainedBytes;

    public MaterializationArtifactStore(Clock clock, int maximumArtifacts, long maximumBytes) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumArtifacts < 1 || maximumArtifacts > 128
                || maximumBytes < 1024 || maximumBytes > 512L * 1024 * 1024) {
            throw new IllegalArgumentException("artifact store quota is outside hard bounds");
        }
        this.maximumArtifacts = maximumArtifacts;
        this.maximumBytes = maximumBytes;
    }

    public synchronized void put(String owner, MaterializationArtifact artifact) {
        owner = owner(owner);
        Objects.requireNonNull(artifact, "artifact");
        cleanup();
        if (artifact.canonicalBytes() > maximumBytes) {
            throw quota("artifact bytes exceed the owner quota");
        }
        String key = key(owner, artifact.artifactId());
        Entry previous = artifacts.remove(key);
        if (previous != null) retainedBytes -= previous.artifact().canonicalBytes();
        while (ownerCount(owner) >= maximumArtifacts
                || ownerBytes(owner) > maximumBytes - artifact.canonicalBytes()) {
            if (!evictOldest(owner)) break;
        }
        if (ownerCount(owner) >= maximumArtifacts
                || ownerBytes(owner) > maximumBytes - artifact.canonicalBytes()) {
            throw quota("artifact owner quota is exhausted");
        }
        while (!artifacts.isEmpty() && (artifacts.size() >= MAXIMUM_BACKEND_ARTIFACTS
                || retainedBytes > MAXIMUM_BACKEND_BYTES - artifact.canonicalBytes())) {
            evictOldestGlobal();
        }
        if (artifacts.size() >= MAXIMUM_BACKEND_ARTIFACTS
                || retainedBytes > MAXIMUM_BACKEND_BYTES - artifact.canonicalBytes()) {
            throw quota("artifact backend quota is exhausted");
        }
        artifacts.put(key, new Entry(owner, artifact));
        retainedBytes += artifact.canonicalBytes();
    }

    public synchronized MaterializationArtifact require(String owner, String artifactId,
            String artifactFingerprint) {
        owner = owner(owner);
        cleanup();
        Entry entry = artifacts.get(key(owner, artifactId));
        MaterializationArtifact artifact = entry == null ? null : entry.artifact();
        if (artifact == null) {
            throw new MaterializationException("materialization_artifact_not_found",
                    "The materialization artifact is missing or expired.",
                    Map.of("effects_prevented", true), false);
        }
        if (!artifact.artifactFingerprint().equals(artifactFingerprint)) {
            throw new MaterializationException("materialization_artifact_mismatch",
                    "The artifact fingerprint does not match the owner-local preview.",
                    Map.of("effects_prevented", true), false);
        }
        return artifact;
    }

    public synchronized void clear() {
        artifacts.clear();
        retainedBytes = 0;
    }

    private void cleanup() {
        Iterator<Entry> iterator = artifacts.values().iterator();
        while (iterator.hasNext()) {
            MaterializationArtifact artifact = iterator.next().artifact();
            if (!clock.instant().isBefore(artifact.expiresAt())) {
                retainedBytes -= artifact.canonicalBytes();
                iterator.remove();
            }
        }
    }

    private int ownerCount(String owner) {
        return (int) artifacts.values().stream()
                .filter(entry -> entry.owner().equals(owner)).count();
    }

    private long ownerBytes(String owner) {
        return artifacts.values().stream().filter(entry -> entry.owner().equals(owner))
                .mapToLong(entry -> entry.artifact().canonicalBytes()).sum();
    }

    private boolean evictOldest(String owner) {
        Iterator<Map.Entry<String, Entry>> iterator = artifacts.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.owner().equals(owner)) {
                retainedBytes -= entry.artifact().canonicalBytes();
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void evictOldestGlobal() {
        Iterator<Entry> iterator = artifacts.values().iterator();
        if (!iterator.hasNext()) return;
        Entry entry = iterator.next();
        retainedBytes -= entry.artifact().canonicalBytes();
        iterator.remove();
    }

    private static String owner(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifact owner must be a bounded opaque digest");
        }
        return value;
    }

    private static String key(String owner, String artifactId) {
        return owner + "\u0000" + artifactId;
    }

    private static MaterializationException quota(String message) {
        return new MaterializationException("materialization_artifact_quota_exceeded", message,
                Map.of("effects_prevented", true), true);
    }

    private record Entry(String owner, MaterializationArtifact artifact) { }
}
