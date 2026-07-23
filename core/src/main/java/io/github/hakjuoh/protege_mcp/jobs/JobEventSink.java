package io.github.hakjuoh.protege_mcp.jobs;

/**
 * Non-blocking destination for ordered lifecycle events.
 *
 * <p>A runtime exception reports an audit delivery failure to the job runtime. In particular,
 * failure to deliver a required pre-commit publication intent prevents the commit.</p>
 */
@FunctionalInterface
public interface JobEventSink {
    void onEvent(JobEvent event);

    static JobEventSink noOp() {
        return event -> { };
    }
}
