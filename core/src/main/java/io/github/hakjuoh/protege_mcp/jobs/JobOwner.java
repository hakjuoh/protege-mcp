package io.github.hakjuoh.protege_mcp.jobs;

/** Opaque exact owner/grant identity for one workspace-scoped job. */
public record JobOwner(String workspaceId, String principalFingerprint,
        String clientFingerprint, String grantFingerprint) {

    public JobOwner {
        workspaceId = JobValidators.requireUuid(workspaceId, "workspace id");
        principalFingerprint = JobHashes.requireDigest(
                principalFingerprint, "principal fingerprint");
        clientFingerprint = JobHashes.requireDigest(clientFingerprint, "client fingerprint");
        grantFingerprint = JobHashes.requireDigest(grantFingerprint, "grant fingerprint");
    }

    public String ownerFingerprint() {
        return JobHashes.digest(workspaceId, principalFingerprint,
                clientFingerprint, grantFingerprint);
    }
}
