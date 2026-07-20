package io.github.hakjuoh.protege_mcp.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditExportServiceTest {

    @TempDir
    Path temp;

    @Test
    void mergesByTimestampAndEventIdAndWritesOneConfinedArtifact() throws Exception {
        Path project = project();
        Path root = temp.resolve("audit");
        AuditLog later = new AuditLog(root, project, "workspace-b", AuditSettings.defaults(),
                Clock.fixed(Instant.parse("2026-07-19T12:01:00Z"), ZoneOffset.UTC), () -> "event-b");
        AuditLog earlier = new AuditLog(root, project, "workspace-a", AuditSettings.defaults(),
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC), () -> "event-a");
        later.append(event("later"));
        earlier.append(event("earlier"));

        AuditExportService.Result exported = AuditExportService.export(
                earlier.projectAuditDirectory(), project, ".protege-mcp/audit-export-review.jsonl");

        assertEquals(".protege-mcp/audit-export-review.jsonl", exported.path());
        assertEquals(2, exported.eventCount());
        assertEquals(2, exported.sourceCount());
        assertTrue(exported.sha256().matches("sha256:[0-9a-f]{64}"));
        List<String> lines = Files.readAllLines(project.resolve(exported.path()), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"event_id\":\"event-a\""));
        assertTrue(lines.get(1).contains("\"event_id\":\"event-b\""));
    }

    @Test
    void reRedactsValidOwnerFileAndRejectsCorruptionEscapeAndSymlinks() throws Exception {
        Path project = project();
        Path root = temp.resolve("audit");
        AuditLog log = new AuditLog(root, project, "workspace-safe", AuditSettings.defaults(),
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC), () -> "event-safe");
        log.append(event("safe"));
        String original = Files.readString(log.streamPath()).trim();
        String tampered = original.substring(0, original.length() - 1)
                + ",\"access_token\":\"owner-mistake\"}\n";
        Files.writeString(log.streamPath(), tampered, StandardCharsets.UTF_8);

        AuditExportService.Result exported = AuditExportService.export(
                log.projectAuditDirectory(), project, null);
        String content = Files.readString(project.resolve(exported.path()));
        assertFalse(content.contains("owner-mistake"));
        assertTrue(content.contains(AuditRedactor.REDACTED));
        assertThrows(IllegalArgumentException.class, () -> AuditExportService.export(
                log.projectAuditDirectory(), project, "../escape.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> AuditExportService.previewOutput(
                project, project.resolve(".protege-mcp/audit-export.jsonl").toString()));
        assertThrows(IllegalArgumentException.class, () -> AuditExportService.previewOutput(
                project, ".protege-mcp/project.yaml"));

        Files.writeString(log.streamPath(), "{broken\n", StandardCharsets.UTF_8);
        assertThrows(RuntimeException.class, () -> AuditExportService.export(
                log.projectAuditDirectory(), project, ".protege-mcp/audit-export-broken.jsonl"));

        Path linked = log.projectAuditDirectory().resolve("workspace-link.jsonl");
        try {
            Files.createSymbolicLink(linked, temp.resolve("elsewhere"));
        } catch (UnsupportedOperationException | IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        assertThrows(IllegalArgumentException.class, () -> AuditExportService.export(
                log.projectAuditDirectory(), project, ".protege-mcp/audit-export-link.jsonl"));
    }

    @Test
    void sameTimestampUsesWorkspaceSequenceBeforeLexicalEventId() throws Exception {
        Path project = project();
        AtomicInteger ids = new AtomicInteger();
        AuditLog log = new AuditLog(temp.resolve("audit-order"), project, "workspace-order",
                AuditSettings.defaults(),
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC),
                () -> ids.incrementAndGet() == 1 ? "event-10" : "event-2");
        log.append(event("first"));
        log.append(event("second"));

        AuditExportService.Result exported = AuditExportService.export(
                log.projectAuditDirectory(), project, ".protege-mcp/audit-export-ordered.jsonl");
        List<String> lines = Files.readAllLines(project.resolve(exported.path()));
        assertTrue(lines.get(0).contains("\"operation\":\"first\""));
        assertTrue(lines.get(1).contains("\"operation\":\"second\""));
        assertTrue(lines.get(0).contains("\"sequence\":1"));
        assertTrue(lines.get(1).contains("\"sequence\":2"));
    }

    private Path project() throws IOException {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        return project;
    }

    private static AuditEvent event(String operation) {
        return new AuditEvent(operation, AuditEvent.Outcome.SUCCEEDED,
                new AuditEvent.Actor("client", "Client", "oauth", Set.of("ontology:read")),
                "https://example.org/ontology", null, "not_applicable", false,
                Map.of("count", 1), List.of(), null);
    }
}
