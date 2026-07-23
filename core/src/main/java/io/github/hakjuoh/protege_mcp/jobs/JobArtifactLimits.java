package io.github.hakjuoh.protege_mcp.jobs;

/** Test-tightenable artifact/result/progress bounds capped by product maxima. */
record JobArtifactLimits(int artifactsPerJob, long artifactBytes, long jobArtifactBytes,
        long backendArtifactBytes, int resultBytes, int progressBytes) {
    JobArtifactLimits {
        if (artifactsPerJob < 1 || artifactsPerJob > JobService.MAX_ARTIFACTS_PER_JOB
                || artifactBytes < 1 || artifactBytes > JobService.MAX_ARTIFACT_BYTES
                || jobArtifactBytes < artifactBytes
                || jobArtifactBytes > JobService.MAX_JOB_ARTIFACT_BYTES
                || backendArtifactBytes < jobArtifactBytes
                || backendArtifactBytes > JobService.MAX_BACKEND_ARTIFACT_BYTES
                || resultBytes < 1 || resultBytes > JobService.MAX_RESULT_BYTES
                || progressBytes < 1 || progressBytes > JobService.MAX_PROGRESS_BYTES) {
            throw new IllegalArgumentException("job artifact limits are outside hard bounds");
        }
    }

    static JobArtifactLimits defaults() {
        return new JobArtifactLimits(JobService.MAX_ARTIFACTS_PER_JOB,
                JobService.MAX_ARTIFACT_BYTES, JobService.MAX_JOB_ARTIFACT_BYTES,
                JobService.MAX_BACKEND_ARTIFACT_BYTES, JobService.MAX_RESULT_BYTES,
                JobService.MAX_PROGRESS_BYTES);
    }
}
