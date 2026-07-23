package io.github.hakjuoh.protege_mcp.jobs;

import java.util.concurrent.RejectedExecutionException;

/** Adapter-owned bounded scheduler; live adapters configure exactly two workers. */
@FunctionalInterface
public interface JobScheduler {
    JobTaskHandle submit(Runnable task) throws RejectedExecutionException;
}
