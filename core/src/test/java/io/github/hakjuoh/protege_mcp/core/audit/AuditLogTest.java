package io.github.hakjuoh.protege_mcp.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class AuditLogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void writesDeterministicOwnerOnlyRedactedEnvelope() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog log = log(project, "workspace-a", AuditSettings.defaults(), clock);

        AuditLog.Receipt receipt = log.append(event("commit_change_set", Map.of(
                "normalized_changes", 3,
                "authorization", "Bearer should-never-land",
                "note", "safe Bearer abc.def and https://example.test/?access_token=oops")));

        assertEquals("event-1", receipt.eventId());
        assertEquals("2026-07-19T12:00:00Z", receipt.timestamp());
        assertEquals("workspace-a", receipt.workspaceId());
        List<String> lines = Files.readAllLines(log.streamPath(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        Map<String, Object> row = JSON.readValue(lines.get(0), new TypeReference<>() { });
        assertEquals(1, row.get("schema_version"));
        assertEquals("commit_change_set", row.get("operation"));
        assertEquals("workspace-a", row.get("workspace_id"));
        assertEquals(1, row.get("sequence"));
        assertFalse(lines.get(0).contains("should-never-land"));
        assertFalse(lines.get(0).contains("abc.def"));
        assertFalse(lines.get(0).contains("access_token=oops"));
        assertTrue(lines.get(0).contains(AuditRedactor.REDACTED));

        if (Files.getFileStore(log.streamPath()).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(log.streamPath()));
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(log.projectAuditDirectory()));
        }
    }

    @Test
    void rotatesWithinCountAndCleansExpiredStreamBeforeAppend() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog log = log(project, "workspace-rotate", new AuditSettings(1, 1024, 3), clock);
        String payload = "x".repeat(900);
        for (int i = 0; i < 5; i++) log.append(event("operation-" + i, Map.of("note", payload)));

        assertTrue(Files.exists(log.streamPath()));
        assertTrue(Files.exists(log.projectAuditDirectory().resolve("workspace-rotate.jsonl.1")));
        assertTrue(Files.exists(log.projectAuditDirectory().resolve("workspace-rotate.jsonl.2")));
        assertFalse(Files.exists(log.projectAuditDirectory().resolve("workspace-rotate.jsonl.3")));

        Files.setLastModifiedTime(log.streamPath(),
                FileTime.from(Instant.parse("2026-07-16T12:00:00Z")));
        clock.set(Instant.parse("2026-07-20T12:00:00Z"));
        log.append(event("after-retention", Map.of()));
        String current = Files.readString(log.streamPath());
        assertTrue(current.contains("after-retention"));
        assertFalse(current.contains("operation-4"));
    }

    @Test
    void reducingMaxFilesPrunesOnlyTheOwnersExcessRotationsBeforeAppend() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog original = log(project, "workspace-shrink",
                new AuditSettings(3650, 1024, 5), clock);
        String payload = "x".repeat(900);
        for (int i = 0; i < 5; i++) {
            original.append(event("operation-" + i, Map.of("note", payload)));
        }
        Path siblingRotation = original.projectAuditDirectory()
                .resolve("workspace-shrink-extra.jsonl.4");
        Path dotExtendingSiblingStream = original.projectAuditDirectory()
                .resolve("workspace-shrink.jsonl.4x.jsonl");
        Path oversizedRotation = original.projectAuditDirectory()
                .resolve("workspace-shrink.jsonl.999999999999999999999999");
        Path staleRotation = original.projectAuditDirectory()
                .resolve("workspace-shrink.jsonl.15");
        Files.writeString(siblingRotation, "sibling");
        Files.writeString(dotExtendingSiblingStream, "dot-sibling");
        Files.writeString(oversizedRotation, "stale");
        Files.writeString(staleRotation, "stale");

        AuditLog fallback = log(project, "workspace-shrink", AuditSettings.defaults(), clock);
        fallback.append(event("fallback-session", Map.of()));
        assertTrue(Files.exists(staleRotation),
                "fallback defaults (invalid policy) must never prune rotations an authored "
                        + "max_files retains");

        AuditLog reduced = log(project, "workspace-shrink",
                new AuditSettings(3650, 4096, 3), clock);
        reduced.append(event("after-shrink", Map.of()));

        assertTrue(Files.exists(reduced.streamPath()));
        Path kept1 = reduced.projectAuditDirectory().resolve("workspace-shrink.jsonl.1");
        Path kept2 = reduced.projectAuditDirectory().resolve("workspace-shrink.jsonl.2");
        assertTrue(Files.readString(kept1).contains("operation-3"),
                "an in-policy rotation must survive the prune itself, not be recreated by a "
                        + "coincidental rotation");
        assertTrue(Files.readString(kept2).contains("operation-2"),
                "the prune must keep every rotation below the reduced max_files");
        for (String index : List.of("3", "4", "15")) {
            assertFalse(Files.exists(reduced.projectAuditDirectory()
                    .resolve("workspace-shrink.jsonl." + index)));
        }
        assertFalse(Files.exists(oversizedRotation),
                "a huge numeric suffix must not bypass the reduced rotation limit");
        assertEquals("sibling", Files.readString(siblingRotation),
                "rotation cleanup must use the exact workspace identity");
        assertEquals("dot-sibling", Files.readString(dotExtendingSiblingStream),
                "a sibling workspace id extending this one with '.jsonl.' must not be adopted "
                        + "by the prefix match");
        List<String> current = Files.readAllLines(reduced.streamPath());
        assertEquals(3, current.size(),
                "neither the fallback nor the shrink append should have rotated");
        Map<String, Object> row = JSON.readValue(current.get(current.size() - 1),
                new TypeReference<>() { });
        assertEquals(7, row.get("sequence"),
                "pruning old rotations must not reset the stream sequence");
    }

    @Test
    void fallbackDefaultsDoNotExpireTheSameWorkspacesAuthoredHistory() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog authored = log(project, "workspace-policy-flip",
                new AuditSettings(3650, 1024, 100), clock);
        String payload = "x".repeat(900);
        for (int i = 0; i < 12; i++) {
            authored.append(event("authored-" + i, Map.of("note", payload)));
        }
        Path retainedRotation = authored.projectAuditDirectory()
                .resolve("workspace-policy-flip.jsonl.10");
        Path rotationBoundary = authored.projectAuditDirectory()
                .resolve("workspace-policy-flip.jsonl.9");
        assertTrue(Files.exists(retainedRotation));
        String boundaryContents = Files.readString(rotationBoundary);
        byte[] currentLine = Files.readAllBytes(authored.streamPath());
        try (var output = Files.newOutputStream(authored.streamPath(),
                java.nio.file.StandardOpenOption.APPEND)) {
            long copies = AuditSettings.DEFAULT_MAX_FILE_BYTES / currentLine.length + 1;
            for (long i = 0; i < copies; i++) output.write(currentLine);
        }
        assertTrue(Files.size(authored.streamPath()) > AuditSettings.DEFAULT_MAX_FILE_BYTES,
                "the fallback append must cross its ordinary 10 MiB rotation threshold");
        Files.setLastModifiedTime(retainedRotation,
                FileTime.from(Instant.parse("2025-07-19T12:00:00Z")));

        clock.set(Instant.parse("2026-07-20T12:00:00Z"));
        AuditLog fallback = log(project, "workspace-policy-flip",
                AuditSettings.defaults(), clock);
        fallback.append(event("policy-temporarily-invalid", Map.of()));

        assertTrue(Files.exists(retainedRotation),
                "fallback retention must not erase this same runtime workspace's history when "
                        + "the previously valid policy retains it for 3650 days");
        assertEquals(boundaryContents, Files.readString(rotationBoundary),
                "fallback rotation must not overwrite a file the authored max_files retains");
    }

    @Test
    void separateWorkspacesNeverShareAnAppendTarget() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog first = log(project, "workspace-one", AuditSettings.defaults(), clock);
        AuditLog second = log(project, "workspace-two", AuditSettings.defaults(), clock);
        first.append(event("one", Map.of()));
        second.append(event("two", Map.of()));

        assertFalse(first.streamPath().equals(second.streamPath()));
        assertTrue(Files.readString(first.streamPath()).contains("\"operation\":\"one\""));
        assertTrue(Files.readString(second.streamPath()).contains("\"operation\":\"two\""));
    }

    @Test
    void projectLockSerializesTwoObjectsThatShareAWorkspaceId() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AtomicInteger firstIds = new AtomicInteger();
        AtomicInteger secondIds = new AtomicInteger();
        AuditLog first = new AuditLog(temp.resolve("audit"), project, "workspace-shared",
                AuditSettings.defaults(), clock, () -> "first-" + firstIds.incrementAndGet());
        AuditLog second = new AuditLog(temp.resolve("audit"), project, "workspace-shared",
                AuditSettings.defaults(), clock, () -> "second-" + secondIds.incrementAndGet());
        var executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> {
                for (int i = 0; i < 20; i++) first.append(event("first", Map.of("index", i)));
            });
            executor.submit(() -> {
                for (int i = 0; i < 20; i++) second.append(event("second", Map.of("index", i)));
            });
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        List<String> lines = Files.readAllLines(first.streamPath());
        assertEquals(40, lines.size());
        Set<Long> sequences = new java.util.HashSet<>();
        for (String line : lines) {
            Map<String, Object> row = JSON.readValue(line, new TypeReference<>() { });
            sequences.add(((Number) row.get("sequence")).longValue());
        }
        assertEquals(40, sequences.size());
        assertTrue(sequences.contains(1L));
        assertTrue(sequences.contains(40L));
    }

    @Test
    void anAppendSweepsExpiredSiblingWorkspaceStreamsInTheSameProject() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog finished = log(project, "workspace-finished", new AuditSettings(1, 2048, 3), clock);
        finished.append(event("finished-session", Map.of()));
        Files.setLastModifiedTime(finished.streamPath(),
                FileTime.from(Instant.parse("2026-07-16T12:00:00Z")));
        AuditLog fresh = log(project, "workspace-fresh", new AuditSettings(1, 2048, 3), clock);
        fresh.append(event("fresh-session", Map.of()));

        clock.set(Instant.parse("2026-07-20T12:00:00Z"));
        AuditLog current = log(project, "workspace-current", new AuditSettings(1, 2048, 3), clock);
        current.append(event("current-session", Map.of()));

        assertFalse(Files.exists(finished.streamPath()),
                "a finished session's expired stream must not accumulate until export caps break");
        assertTrue(Files.exists(fresh.streamPath()),
                "unexpired sibling streams must survive the retention sweep");
        assertTrue(Files.exists(current.streamPath()));
    }

    @Test
    void fallbackDefaultSettingsNeverSweepSiblingStreams() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog sibling = log(project, "workspace-authored", new AuditSettings(3650, 2048, 3),
                clock);
        sibling.append(event("long-retention-history", Map.of()));
        Files.setLastModifiedTime(sibling.streamPath(),
                FileTime.from(Instant.parse("2020-07-19T12:00:00Z")));

        AuditLog fallback = log(project, "workspace-fallback", AuditSettings.defaults(), clock);
        fallback.append(event("invalid-policy-session", Map.of()));

        assertTrue(Files.exists(sibling.streamPath()),
                "a session on fallback defaults (invalid policy) must not delete history the "
                        + "authored policy retains for longer");
        Files.setLastModifiedTime(fallback.streamPath(),
                FileTime.from(Instant.parse("2020-07-19T12:00:00Z")));
        clock.set(Instant.parse("2026-07-20T12:00:00Z"));
        AuditLog fallbackAgain = log(project, "workspace-fallback", AuditSettings.defaults(), clock);
        fallbackAgain.append(event("still-invalid", Map.of()));
        assertTrue(Files.exists(sibling.streamPath()));
    }

    @Test
    void aWhollyTornNewestFileFallsBackToRotatedFilesForTheNextSequence() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog log = log(project, "workspace-torn", new AuditSettings(1, 1024, 3), clock);
        String payload = "x".repeat(900);
        for (int i = 0; i < 5; i++) log.append(event("operation-" + i, Map.of("note", payload)));
        List<String> rotatedLines = Files.readAllLines(
                log.projectAuditDirectory().resolve("workspace-torn.jsonl.1"));
        long rotatedSequence = ((Number) JSON.readValue(rotatedLines.get(rotatedLines.size() - 1),
                new TypeReference<Map<String, Object>>() { }).get("sequence")).longValue();
        Files.write(log.streamPath(), "{\"torn\":".getBytes(StandardCharsets.UTF_8));

        log.append(event("after-torn", Map.of()));

        List<String> lines = Files.readAllLines(log.streamPath());
        assertEquals(1, lines.size());
        Map<String, Object> recovered = JSON.readValue(lines.get(0), new TypeReference<>() { });
        assertEquals(rotatedSequence + 1, ((Number) recovered.get("sequence")).longValue(),
                "sequences already committed to rotated files must never be reissued");
        @SuppressWarnings("unchecked")
        Map<String, Object> recovery = (Map<String, Object>) recovered.get("stream_recovery");
        assertTrue(((Number) recovery.get("discarded_torn_bytes")).longValue() > 0);
    }

    @Test
    void anAppendSweepsEntirelyExpiredSiblingProjectStreams() throws Exception {
        Path auditRoot = temp.resolve("audit-sweep");
        Path oldProject = temp.resolve("old-project");
        Path currentProject = temp.resolve("current-project");
        Files.createDirectories(oldProject);
        Files.createDirectories(currentProject);
        MutableClock oldClock = new MutableClock(Instant.parse("2010-07-10T12:00:00Z"));
        AuditLog old = new AuditLog(auditRoot, oldProject, "workspace-old",
                new AuditSettings(1, 2048, 2), oldClock, () -> "old-event");
        old.append(event("old", Map.of()));
        Files.setLastModifiedTime(old.streamPath(),
                FileTime.from(Instant.parse("2010-07-10T12:00:00Z")));
        Files.setLastModifiedTime(old.projectAuditDirectory(),
                FileTime.from(Instant.parse("2010-07-10T12:00:00Z")));

        MutableClock currentClock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog current = new AuditLog(auditRoot, currentProject, "workspace-current",
                new AuditSettings(1, 2048, 2), currentClock, () -> "current-event");
        current.append(event("current", Map.of()));

        assertFalse(Files.exists(old.projectAuditDirectory()),
                "an expired project hash must not remain orphaned forever");
        assertTrue(Files.exists(current.streamPath()));
    }

    @Test
    void redactorNormalizesSensitiveKeysAndPreservesCollidingSanitizedKeys() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("api.key", "dot-secret");
        source.put("apiKey", "camel-secret");
        source.put("authorizationHeader", "header-secret");
        source.put("prompt.template", "prompt-secret");
        source.put("same\n", "one");
        source.put("same\t", "two");

        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) AuditRedactor.redact(source);
        assertFalse(redacted.toString().contains("secret"));
        assertEquals(AuditRedactor.REDACTED, redacted.get("api.key"));
        assertEquals(AuditRedactor.REDACTED, redacted.get("apiKey"));
        assertEquals(AuditRedactor.REDACTED, redacted.get("authorizationHeader"));
        assertEquals(AuditRedactor.REDACTED, redacted.get("prompt.template"));
        assertEquals(6, redacted.size(), "sanitized key collisions must receive stable suffixes");
    }

    @Test
    void recoversATornTailAndRecordsTheDiscardedBytes() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog log = log(project, "workspace-recover", AuditSettings.defaults(), clock);
        log.append(event("before-crash", Map.of()));
        Files.writeString(log.streamPath(), "{\"sequence\":2,\"torn\":",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        log.append(event("after-crash", Map.of()));

        List<String> lines = Files.readAllLines(log.streamPath());
        assertEquals(2, lines.size());
        Map<String, Object> recovered = JSON.readValue(lines.get(1), new TypeReference<>() { });
        assertEquals(2, recovered.get("sequence"));
        assertEquals("after-crash", recovered.get("operation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> recovery = (Map<String, Object>) recovered.get("stream_recovery");
        assertTrue(((Number) recovery.get("discarded_torn_bytes")).longValue() > 0);
        assertEquals(false, recovery.get("added_missing_terminator"));
    }

    @Test
    void repairsACompleteFinalEventMissingOnlyItsLineTerminator() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        AuditLog log = log(project, "workspace-terminator", AuditSettings.defaults(), clock);
        log.append(event("first", Map.of()));
        byte[] complete = Files.readAllBytes(log.streamPath());
        Files.write(log.streamPath(), java.util.Arrays.copyOf(complete, complete.length - 1));

        log.append(event("second", Map.of()));

        List<String> lines = Files.readAllLines(log.streamPath());
        assertEquals(2, lines.size());
        Map<String, Object> second = JSON.readValue(lines.get(1), new TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> recovery = (Map<String, Object>) second.get("stream_recovery");
        assertEquals(true, recovery.get("added_missing_terminator"));
        assertEquals(0, recovery.get("discarded_torn_bytes"));
    }

    @Test
    void refusesUnsafeIdsAndStreamSymlinkReplacement() throws Exception {
        Path project = project();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T12:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> new AuditLog(temp.resolve("audit"),
                project, "../escape", AuditSettings.defaults(), clock, () -> "event-1"));

        AuditLog log = new AuditLog(temp.resolve("audit-safe"), project, "workspace-safe",
                AuditSettings.defaults(), clock, () -> "../event");
        assertThrows(IllegalArgumentException.class, () -> log.append(event("tool", Map.of())));

        AuditLog linked = log(project, "workspace-linked", AuditSettings.defaults(), clock);
        Path outside = temp.resolve("outside.jsonl");
        Files.writeString(outside, "outside");
        try {
            Files.createSymbolicLink(linked.streamPath(), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        assertThrows(UncheckedIOException.class, () -> linked.append(event("tool", Map.of())));
        assertEquals("outside", Files.readString(outside));
    }

    private Path project() throws IOException {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        return project;
    }

    private AuditLog log(Path project, String workspace, AuditSettings settings, Clock clock) {
        AtomicInteger ids = new AtomicInteger();
        return new AuditLog(temp.resolve("audit"), project, workspace, settings, clock,
                () -> "event-" + ids.incrementAndGet());
    }

    private static AuditEvent event(String operation, Map<String, Object> summary) {
        return new AuditEvent(operation, AuditEvent.Outcome.SUCCEEDED,
                new AuditEvent.Actor("client-1", "Review client", "oauth",
                        Set.of("ontology:curate")),
                "https://example.org/ontology", null, "pass", true, summary,
                List.of("confirmation-1"), "release/manifest.json");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
