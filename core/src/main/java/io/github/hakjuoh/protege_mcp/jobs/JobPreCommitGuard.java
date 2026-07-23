package io.github.hakjuoh.protege_mcp.jobs;

/** Acquires a lease that keeps dynamic authority and input guards valid through publication. */
@FunctionalInterface
public interface JobPreCommitGuard {
    JobCommitLease acquire() throws Exception;

    static JobPreCommitGuard noOp() {
        return JobCommitLease::noOp;
    }
}
