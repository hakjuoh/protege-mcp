package io.github.hakjuoh.protege_mcp.jobs;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Complete immutable request captured before scheduler submission. */
public record JobSubmission(JobOwner owner, JobType type, String idempotencyKey,
        JobInputIdentity inputIdentity, Set<String> requiredCapabilities,
        boolean reasonerCancellationProven, JobPreCommitGuard publicationGuard, JobTask task) {
    public JobSubmission {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(inputIdentity, "inputIdentity");
        Objects.requireNonNull(publicationGuard, "publicationGuard");
        Objects.requireNonNull(task, "task");
        if (!owner.workspaceId().equals(inputIdentity.modelRevision().workspaceId())) {
            throw new IllegalArgumentException("job owner and input workspace differ");
        }
        idempotencyKey = JobValidators.requireIdempotencyKey(idempotencyKey);
        Set<String> capabilities = new LinkedHashSet<>(requiredCapabilities == null
                ? Set.of() : requiredCapabilities);
        requiredCapabilities = JobValidators.requireCapabilities(capabilities);
    }
}
