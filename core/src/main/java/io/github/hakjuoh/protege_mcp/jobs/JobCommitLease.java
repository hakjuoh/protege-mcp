package io.github.hakjuoh.protege_mcp.jobs;

/**
 * Adapter-held proof that a successful guard check remains valid across publication/commit.
 * Closing releases the adapter's lock, permit, or CAS anchor.
 */
@FunctionalInterface
public interface JobCommitLease extends AutoCloseable {
    /**
     * Release the adapter permit. Implementations must contain and report their own cleanup
     * failures; publication is already terminal when this callback runs.
     */
    @Override
    void close();

    static JobCommitLease noOp() {
        return () -> { };
    }
}
