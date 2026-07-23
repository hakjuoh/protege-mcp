package io.github.hakjuoh.protege_mcp.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

class JobValidationTest {
    private static final String WORKSPACE = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER_WORKSPACE = "00000000-0000-4000-8000-000000000002";
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void jobTypesParseStrictlyAndDeclareTheirReasonerRequirement() {
        assertEquals(JobType.CLASSIFICATION, JobType.fromId(" CLASSIFICATION "));
        assertEquals(JobType.PROJECT_QC, JobType.fromId("project_qc"));
        assertEquals(JobType.SEMANTIC_DIFF, JobType.fromId("semantic_diff"));
        assertEquals(JobType.INFERENCE_MATERIALIZATION,
                JobType.fromId("inference_materialization"));
        assertTrue(JobType.CLASSIFICATION.requiresReasoner());
        assertTrue(JobType.INFERENCE_MATERIALIZATION.requiresReasoner());
        assertFalse(JobType.PROJECT_QC.requiresReasoner());
        assertFalse(JobType.SEMANTIC_DIFF.requiresReasoner());
        assertThrows(IllegalArgumentException.class, () -> JobType.fromId(null));
        assertThrows(IllegalArgumentException.class, () -> JobType.fromId("explanation"));
    }

    @Test
    void runtimeConfigurationRejectsEveryOutOfBoundsDimension() {
        Set<JobType> types = Set.of(JobType.values());
        assertEquals(Set.of(), new JobRuntimeConfig(
                null, 1, 1, 1, 1, Duration.ofSeconds(60)).allowedTypes());
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 0, 1, 1, 1, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 33, 1, 1, 1, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 0, 1, 1, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 9, 9, 9, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 2, 1, 2, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 1, 33, 33, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 1, 2, 1, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 1, 1, 129, Duration.ofSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 1, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 1, 1, 1, Duration.ofSeconds(59)));
        assertThrows(IllegalArgumentException.class, () -> new JobRuntimeConfig(
                types, 1, 1, 1, 1, Duration.ofHours(1).plusSeconds(1)));
    }

    @Test
    void artifactLimitsRejectEveryOutOfBoundsDimension() {
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(5, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(1, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(
                        1, JobService.MAX_ARTIFACT_BYTES + 1,
                        JobService.MAX_ARTIFACT_BYTES + 1,
                        JobService.MAX_ARTIFACT_BYTES + 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(1, 2, 1, 2, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(
                        1, 1, JobService.MAX_JOB_ARTIFACT_BYTES + 1,
                        JobService.MAX_JOB_ARTIFACT_BYTES + 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(1, 1, 2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(
                        1, 1, 1, JobService.MAX_BACKEND_ARTIFACT_BYTES + 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(1, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(
                        1, 1, 1, 1, JobService.MAX_RESULT_BYTES + 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(1, 1, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new JobArtifactLimits(
                        1, 1, 1, 1, 1, JobService.MAX_PROGRESS_BYTES + 1));
    }

    @Test
    void submissionRejectsIncompleteOrInconsistentContracts() {
        JobOwner owner = owner(WORKSPACE);
        JobInputIdentity identity = identity(WORKSPACE);
        JobTask task = execution -> output();
        JobPreCommitGuard guard = JobPreCommitGuard.noOp();
        Set<String> capabilities = Set.of("ontology:read");

        assertThrows(NullPointerException.class, () -> new JobSubmission(
                null, JobType.PROJECT_QC, "key", identity, capabilities,
                false, guard, task));
        assertThrows(NullPointerException.class, () -> new JobSubmission(
                owner, null, "key", identity, capabilities,
                false, guard, task));
        assertThrows(NullPointerException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", null, capabilities,
                false, guard, task));
        assertThrows(NullPointerException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", identity, capabilities,
                false, null, task));
        assertThrows(NullPointerException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", identity, capabilities,
                false, guard, null));
        assertThrows(IllegalArgumentException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", identity(OTHER_WORKSPACE), capabilities,
                false, guard, task));
        assertThrows(IllegalArgumentException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, null, identity, capabilities,
                false, guard, task));
        assertThrows(IllegalArgumentException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "-invalid", identity, capabilities,
                false, guard, task));
        assertThrows(IllegalArgumentException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", identity, null,
                false, guard, task));
        assertThrows(IllegalArgumentException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", identity, capabilities(17),
                false, guard, task));
        Set<String> withNull = new HashSet<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", identity, withNull,
                false, guard, task));
        assertThrows(IllegalArgumentException.class, () -> new JobSubmission(
                owner, JobType.PROJECT_QC, "key", identity, Set.of("ontology"),
                false, guard, task));
    }

    @Test
    void valueContractsRejectMalformedAndOversizedContent() {
        assertThrows(IllegalArgumentException.class,
                () -> new JobError(null, "message", false, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new JobError("job_failed", " ", false, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new JobError("job_failed", "x".repeat(2_049), false, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new JobError("job_failed", "€".repeat(683), false, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new JobError("job_failed", "message", false, entries(33)));
        assertThrows(IllegalArgumentException.class,
                () -> new JobResult(null, Map.of(), List.of(), false));
        assertEquals(Map.of(), new JobResult(
                JobResultType.CLASSIFICATION, null, null, false).structured());
        List<JobArtifact.Reference> tooMany = new ArrayList<>();
        for (int index = 0; index <= JobService.MAX_ARTIFACTS_PER_JOB; index++) {
            tooMany.add(artifact().reference());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new JobResult(JobResultType.CLASSIFICATION, Map.of(), tooMany, false));
        assertThrows(IllegalArgumentException.class,
                () -> new JobResult(JobResultType.CLASSIFICATION, Map.of(),
                        java.util.Arrays.asList((JobArtifact.Reference) null), false));

        assertThrows(IllegalArgumentException.class, () -> new JobArtifact(
                "bad", OTHER_WORKSPACE, "application/json", NOW, NOW.plusSeconds(1),
                new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new JobArtifact(
                WORKSPACE, "bad", "application/json", NOW, NOW.plusSeconds(1), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new JobArtifact(
                WORKSPACE, OTHER_WORKSPACE, "json", NOW, NOW.plusSeconds(1), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new JobArtifact(
                WORKSPACE, OTHER_WORKSPACE, "application/json", null,
                NOW.plusSeconds(1), new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new JobArtifact(
                WORKSPACE, OTHER_WORKSPACE, "application/json", NOW, NOW, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new JobArtifact(
                WORKSPACE, OTHER_WORKSPACE, "application/json", NOW,
                NOW.plusSeconds(1), null));
    }

    @Test
    void descriptorsRejectImpossibleTerminalCombinations() {
        JobResult result = new JobResult(JobResultType.CLASSIFICATION, Map.of(), List.of(), false);
        JobError error = new JobError("job_failed", "Failed.", false, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> descriptor(JobState.SUCCEEDED, null, result, null));
        assertThrows(IllegalArgumentException.class,
                () -> descriptor(JobState.RUNNING, NOW.toString(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> descriptor(JobState.SUCCEEDED, NOW.toString(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> descriptor(JobState.RUNNING, null, result, null));
        assertThrows(IllegalArgumentException.class,
                () -> descriptor(JobState.FAILED, NOW.toString(), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> descriptor(JobState.RUNNING, null, null, error));
    }

    @Test
    void eventsRejectInvalidSequenceProgressAndErrorCodes() {
        assertThrows(IllegalArgumentException.class,
                () -> event(0, JobEventKind.ACCEPTED, "queued", 0, null));
        assertThrows(NullPointerException.class,
                () -> event(1, null, "queued", 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, JobEventKind.ACCEPTED, "Queued", 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, JobEventKind.ACCEPTED, "queued", -1, null));
        assertThrows(IllegalArgumentException.class,
                () -> event(1, JobEventKind.ACCEPTED, "queued", 0, "Bad-Code"));
    }

    @Test
    void inputIdentityAndSecondaryInputsRejectMalformedValues() {
        JobInputIdentity valid = identity(WORKSPACE);
        assertThrows(IllegalArgumentException.class, () -> new JobInputIdentity(
                null, valid.closureFingerprint(), null, null, valid.policyDigest(), null,
                null, valid.normalizedRequestDigest(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new JobInputIdentity(
                valid.modelRevision(), "bad", null, null, valid.policyDigest(), null,
                null, valid.normalizedRequestDigest(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new JobInputIdentity(
                valid.modelRevision(), valid.closureFingerprint(), "bad", null,
                valid.policyDigest(), null, null, valid.normalizedRequestDigest(), List.of()));
        List<JobInputIdentity.SecondaryInput> tooMany = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            tooMany.add(new JobInputIdentity.SecondaryInput(
                    "input" + index, digest("bytes" + index), digest("source" + index)));
        }
        assertThrows(IllegalArgumentException.class, () -> new JobInputIdentity(
                valid.modelRevision(), valid.closureFingerprint(), null, null,
                valid.policyDigest(), null, null, valid.normalizedRequestDigest(), tooMany));
        assertThrows(IllegalArgumentException.class, () -> new JobInputIdentity(
                valid.modelRevision(), valid.closureFingerprint(), null, null,
                valid.policyDigest(), null, null, valid.normalizedRequestDigest(),
                java.util.Arrays.asList((JobInputIdentity.SecondaryInput) null)));
        assertThrows(IllegalArgumentException.class,
                () -> new JobInputIdentity.SecondaryInput(
                        "1bad", digest("bytes"), digest("source")));
        assertThrows(IllegalArgumentException.class,
                () -> new JobInputIdentity.SecondaryInput(
                        "input", "bad", digest("source")));
    }

    @Test
    void taskOutputAndStateMachineRejectInvalidValues() {
        JobTaskOutput output = new JobTaskOutput(
                JobResultType.CLASSIFICATION, null, false);
        assertEquals(Map.of(), output.structured());
        assertTrue(output.withAuditIncomplete().auditIncomplete());
        assertEquals(output.withAuditIncomplete(), output.withAuditIncomplete()
                .withAuditIncomplete());
        assertThrows(IllegalArgumentException.class,
                () -> new JobTaskOutput(null, Map.of(), false));
        assertFalse(JobState.QUEUED.canTransitionTo(null));
        assertFalse(JobState.QUEUED.canTransitionTo(JobState.QUEUED));
    }

    private static JobDescriptor descriptor(JobState state, String completedAt,
            JobResult result, JobError error) {
        JobOwner owner = owner(WORKSPACE);
        JobInputIdentity identity = identity(WORKSPACE);
        return new JobDescriptor(WORKSPACE, WORKSPACE, owner.ownerFingerprint(),
                owner.principalFingerprint(), owner.clientFingerprint(),
                owner.grantFingerprint(), JobType.CLASSIFICATION, state, NOW.toString(),
                state == JobState.QUEUED ? null : NOW.toString(), completedAt,
                identity.modelRevision(), identity.policyDigest(), state.id(), "Progress.",
                0, false, false, false, "key", Set.of("ontology:read"), identity,
                JobResultType.CLASSIFICATION, result, error);
    }

    private static JobEvent event(long sequence, JobEventKind kind, String phase,
            long progressSequence, String errorCode) {
        JobOwner owner = owner(WORKSPACE);
        return new JobEvent(sequence, kind, WORKSPACE, NOW.toString(), WORKSPACE,
                owner.ownerFingerprint(), owner.principalFingerprint(),
                owner.clientFingerprint(), owner.grantFingerprint(),
                JobType.CLASSIFICATION, JobState.QUEUED, identity(WORKSPACE).identityDigest(),
                Set.of("ontology:read"), phase, progressSequence,
                0, 0, 0, 0, 0, 0,
                false, false, false, errorCode);
    }

    private static JobTaskOutput output() {
        return new JobTaskOutput(JobResultType.PROJECT_QC, Map.of(), false);
    }

    private static JobArtifact artifact() {
        return new JobArtifact(WORKSPACE, OTHER_WORKSPACE, "application/json",
                NOW, NOW.plusSeconds(60), new byte[] {1});
    }

    private static Map<String, Object> entries(int count) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            values.put("key" + index, index);
        }
        return values;
    }

    private static Set<String> capabilities(int count) {
        Set<String> result = new HashSet<>();
        for (int index = 0; index < count; index++) {
            result.add("scope" + index + ":action");
        }
        return result;
    }

    private static JobOwner owner(String workspace) {
        return new JobOwner(workspace, digest("principal"), digest("client"), digest("grant"));
    }

    private static JobInputIdentity identity(String workspace) {
        return new JobInputIdentity(new ModelRevision(
                workspace, 1, digest("semantic"), digest("document")),
                digest("closure"), null, null, digest("policy"), null,
                digest("reasoner"), digest("request"), List.of());
    }

    private static String digest(String value) {
        return JobHashes.digest(value);
    }
}
