package io.github.hakjuoh.protege_mcp.jobs;

/** The single irreversible adapter action protected by the job commit fence. */
@FunctionalInterface
public interface JobCommitAction {
    JobTaskOutput commit() throws Exception;
}
