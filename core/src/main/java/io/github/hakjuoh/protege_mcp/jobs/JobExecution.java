package io.github.hakjuoh.protege_mcp.jobs;

/**
 * Worker-confined execution API. Progress and private artifacts belong to compute/staging and are
 * rejected after read-only publication or a commit protocol begins.
 */
public interface JobExecution {
    void progress(String phase, String message);

    boolean cancellationRequested();

    void checkCancelled() throws InterruptedException;

    JobArtifact.Reference stageArtifact(String mediaType, byte[] bytes);

    JobTaskOutput withCommitPermit(JobTaskOutput preparedResult, JobPreCommitGuard guard,
            JobCommitAction action) throws Exception;
}
