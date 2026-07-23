package io.github.hakjuoh.protege_mcp.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

class JobContractsTest {
    @Test
    void inputIdentityIsOrderIndependentAndDefensivelyCopied() {
        List<JobInputIdentity.SecondaryInput> inputs = new ArrayList<>();
        inputs.add(secondary("right", "right"));
        inputs.add(secondary("left", "left"));
        JobInputIdentity first = identity(inputs);
        inputs.clear();
        JobInputIdentity second = identity(List.of(
                secondary("left", "left"), secondary("right", "right")));

        assertEquals(first.identityDigest(), second.identityDigest());
        assertEquals(List.of("left", "right"),
                first.secondaryInputs().stream().map(
                        JobInputIdentity.SecondaryInput::name).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> first.secondaryInputs().clear());
    }

    @Test
    void inputIdentityRejectsDigestMismatchAndDuplicateSecondaryNames() {
        JobInputIdentity valid = identity(List.of());
        assertThrows(IllegalArgumentException.class, () -> new JobInputIdentity(
                valid.modelRevision(), valid.closureFingerprint(), valid.importLockDigest(),
                valid.mappingRevision(), valid.policyDigest(), valid.preflightAssetDigest(),
                valid.reasonerDigest(), valid.normalizedRequestDigest(), List.of(), digest("wrong")));
        assertThrows(IllegalArgumentException.class, () -> identity(List.of(
                secondary("same", "one"), secondary("same", "two"))));
    }

    @Test
    void ownerFingerprintBindsWorkspacePrincipalClientAndGrant() {
        JobOwner owner = owner("00000000-0000-4000-8000-000000000001", "grant-a");
        assertNotEquals(owner.ownerFingerprint(),
                owner("00000000-0000-4000-8000-000000000002", "grant-a")
                        .ownerFingerprint());
        assertNotEquals(owner.ownerFingerprint(),
                owner("00000000-0000-4000-8000-000000000001", "grant-b")
                        .ownerFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> new JobOwner("workspace", digest("p"), digest("c"), digest("g")));
    }

    @Test
    void artifactBytesAreDefensiveAndReferenceContainsNoPath() {
        byte[] bytes = new byte[] {1, 2, 3};
        JobArtifact artifact = new JobArtifact(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000002",
                "application/json", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), bytes);
        bytes[0] = 9;
        byte[] copy = artifact.copyBytes();
        copy[1] = 9;

        assertEquals(List.of(1, 2, 3), artifactBytes(artifact));
        assertEquals(3, artifact.reference().bytes());
        assertEquals("application/json", artifact.reference().mediaType());
    }

    @Test
    void errorAndResultMapsAreDeeplyImmutable() {
        Map<String, Object> nested = new java.util.LinkedHashMap<>();
        List<String> items = new ArrayList<>(List.of("one"));
        nested.put("items", items);
        JobError error = new JobError("job_failed", "Failed safely.", false, nested);
        JobResult result = new JobResult(JobResultType.CLASSIFICATION, nested, List.of(), false);
        items.add("two");

        assertEquals(List.of("one"), error.details().get("items"));
        assertEquals(List.of("one"), result.structured().get("items"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) result.structured().get("items")).clear());
        assertThrows(IllegalArgumentException.class, () -> new JobError(
                "job_failed", "Failed safely.", false,
                Map.of("oversized", "x".repeat(65_537))));
    }

    private static List<Integer> artifactBytes(JobArtifact artifact) {
        List<Integer> result = new ArrayList<>();
        for (byte value : artifact.copyBytes()) result.add((int) value);
        return result;
    }

    private static JobInputIdentity identity(List<JobInputIdentity.SecondaryInput> inputs) {
        return new JobInputIdentity(new ModelRevision(
                "00000000-0000-4000-8000-000000000001", 7,
                digest("semantic"), digest("document")), digest("closure"),
                digest("lock"), digest("mapping"), digest("policy"), digest("assets"),
                digest("reasoner"), digest("request"), inputs);
    }

    private static JobInputIdentity.SecondaryInput secondary(String name, String seed) {
        return new JobInputIdentity.SecondaryInput(name, digest(seed + "-bytes"),
                digest(seed + "-provenance"));
    }

    private static JobOwner owner(String workspace, String grant) {
        return new JobOwner(workspace, digest("principal"), digest("client"), digest(grant));
    }

    private static String digest(String value) {
        return JobHashes.digest(value);
    }
}
