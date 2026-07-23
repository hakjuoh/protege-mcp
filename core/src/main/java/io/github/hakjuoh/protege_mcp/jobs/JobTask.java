package io.github.hakjuoh.protege_mcp.jobs;

/** Adapter computation invoked once after a worker claims the job. */
@FunctionalInterface
public interface JobTask {
    JobTaskOutput execute(JobExecution execution) throws Exception;

    /**
     * Deliver an adapter-specific cancellation signal to resources that do not observe thread
     * interruption, such as an OWL reasoner. Implementations must return promptly, must not wait for
     * the computation to stop, and must be safe to call repeatedly.
     */
    default void requestCancellation() {
        // Most core tasks need only the scheduler's thread interruption.
    }

    /**
     * Release immutable captures when admission, idempotent recovery, cancellation, or shutdown
     * prevents this task from ever executing. Implementations must be idempotent, bounded, and
     * non-blocking; any potentially blocking third-party cleanup must be dispatched by the adapter.
     */
    default void discard() {
        // Most core tasks retain no external capture.
    }
}
