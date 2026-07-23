package io.github.hakjuoh.protege_mcp.jobs;

/** Best-effort interruption handle; the monotonic cancellation tombstone remains authoritative. */
@FunctionalInterface
public interface JobTaskHandle {
    boolean cancel(boolean mayInterrupt);
}
