package io.github.hakjuoh.protege_mcp.jobs;

/** Internal monotonic winner between cancellation and output publication. */
enum JobCommitFence {
    OPEN,
    CANCELLED,
    COMMIT_STARTED,
    PUBLICATION_STARTED
}
