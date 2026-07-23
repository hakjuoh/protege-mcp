package io.github.hakjuoh.protege_mcp.jobs;

import java.time.Instant;
import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Private content-addressed artifact whose bytes are never exposed by a descriptor. */
public final class JobArtifact {
    private final String artifactId;
    private final String jobId;
    private final String mediaType;
    private final String sha256;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final byte[] bytes;

    JobArtifact(String artifactId, String jobId, String mediaType, Instant createdAt,
            Instant expiresAt, byte[] bytes) {
        artifactId = JobValidators.requireUuid(artifactId, "artifact id");
        jobId = JobValidators.requireUuid(jobId, "job id");
        if (mediaType == null || !mediaType.matches(
                "[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}")) {
            throw new IllegalArgumentException("artifact media type is invalid");
        }
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("artifact retention interval is invalid");
        }
        if (bytes == null) throw new IllegalArgumentException("artifact bytes are required");
        this.artifactId = artifactId;
        this.jobId = jobId;
        this.mediaType = mediaType;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.bytes = bytes.clone();
        this.sha256 = JobHashes.digest(this.bytes);
    }

    public String artifactId() { return artifactId; }
    public String jobId() { return jobId; }
    public String mediaType() { return mediaType; }
    public String sha256() { return sha256; }
    public long bytes() { return bytes.length; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }

    public byte[] copyBytes() {
        return bytes.clone();
    }

    JobArtifact detachedCopy() {
        return new JobArtifact(artifactId, jobId, mediaType, createdAt, expiresAt, bytes);
    }

    JobArtifact withExpiry(Instant expiry) {
        return new JobArtifact(artifactId, jobId, mediaType, createdAt, expiry, bytes);
    }

    void erase() {
        Arrays.fill(bytes, (byte) 0);
    }

    public Reference reference() {
        return new Reference(artifactId, mediaType, sha256, bytes.length,
                createdAt.toString(), expiresAt.toString());
    }

    /** Public path-free artifact metadata. */
    public record Reference(@JsonProperty("artifact_id") String artifactId,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("bytes") long bytes,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("expires_at") String expiresAt) {
        public Reference {
            artifactId = JobValidators.requireUuid(artifactId, "artifact id");
            if (mediaType == null || !mediaType.matches(
                    "[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/"
                            + "[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}")) {
                throw new IllegalArgumentException("artifact media type is invalid");
            }
            sha256 = JobHashes.requireDigest(sha256, "artifact digest");
            if (bytes < 0 || bytes > JobService.MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("artifact byte size is invalid");
            }
            Instant created = JobValidators.requireInstant(createdAt, "artifact creation time");
            Instant expiry = JobValidators.requireInstant(expiresAt, "artifact expiry time");
            if (!expiry.isAfter(created)) {
                throw new IllegalArgumentException("artifact retention interval is invalid");
            }
        }
    }
}
