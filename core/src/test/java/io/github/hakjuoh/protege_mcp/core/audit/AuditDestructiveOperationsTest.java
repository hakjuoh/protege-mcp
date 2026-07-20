package io.github.hakjuoh.protege_mcp.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Decision table for every destructive audit-store operation.
 *
 * <p>Multiple 0.7.1 review rounds found the same defect class: a destructive mechanism (time-based
 * retention, rotation-count enforcement, size-based rotation) applied without checking whether the
 * session's settings actually speak for the project (policy-derived) and whose files it may touch
 * (own vs sibling workspace). Each fix gated one mechanism and missed the next. This table pins the
 * whole matrix at once: one append per settings-provenance case against an identical seeded
 * directory, asserting exactly which files survive. The seeded current stream exceeds the authored
 * size threshold but stays below the fallback schema maximum, so the provenance cases take opposite
 * size-rotation branches.
 *
 * <p>If a new destructive mechanism is added to {@link AuditLog}, extend this table — a fallback
 * append must continue to delete nothing that any valid policy could retain.
 */
class AuditDestructiveOperationsTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    /** Older than the authored 1-day window, still inside the 3650-day schema maximum. */
    private static final Instant EXPIRED = Instant.parse("2020-07-19T12:00:00Z");
    /** Older than even the 3650-day schema maximum. */
    private static final Instant ANCIENT = Instant.parse("2010-07-19T12:00:00Z");

    @TempDir
    Path temp;

    @Test
    void authoredSettingsApplyTheirBoundsAndFallbackSettingsHonorTheSchemaMaxima() throws Exception {
        Map<String, Instant> seeded = new LinkedHashMap<>();
        seeded.put("owner.jsonl", NOW);             // current stream -> authored size rotation
        seeded.put("owner.jsonl.1", EXPIRED);           // own rotation -> authored time retention
        seeded.put("owner.jsonl.2", NOW);               // own rotation, fresh, within max_files
        seeded.put("owner.jsonl.3", ANCIENT);           // own rotation beyond the schema maximum
        seeded.put("owner.jsonl.15", NOW);              // own rotation, fresh, EXCESS count
        seeded.put("sibling.jsonl", EXPIRED);           // sibling stream, expired for the policy
        seeded.put("sibling-ancient.jsonl", ANCIENT);   // sibling stream beyond the schema maximum
        seeded.put("sibling-fresh.jsonl", NOW);         // sibling stream, fresh
        seeded.put("owner.jsonl.4x.jsonl", NOW);        // dot-extending sibling stream, fresh

        record Case(String name, AuditSettings settings, Set<String> expectedSurvivors) { }
        List<Case> cases = List.of(
                new Case("authored policy applies its own bounds",
                        new AuditSettings(1, 4096, 3),
                        Set.of("owner.jsonl",             // fresh append target
                                "owner.jsonl.1",          // oversized current rotated here
                                "sibling-fresh.jsonl",
                                "owner.jsonl.4x.jsonl")),
                // Fallback settings (invalid/unreadable policy) may destroy nothing an authored
                // policy could retain: only OWN files beyond the schema-maximum window may go.
                new Case("fallback defaults honor the schema maxima",
                        AuditSettings.defaults(),
                        Set.of("owner.jsonl", "owner.jsonl.1", "owner.jsonl.2",
                                "owner.jsonl.15", "sibling.jsonl", "sibling-ancient.jsonl",
                                "sibling-fresh.jsonl", "owner.jsonl.4x.jsonl")));

        for (Case current : cases) {
            Path project = Files.createDirectories(
                    temp.resolve("project-" + Math.abs(current.name().hashCode())));
            AtomicInteger ids = new AtomicInteger();
            AuditLog log = new AuditLog(temp.resolve("audit-" + Math.abs(current.name().hashCode())),
                    project, "owner", current.settings(), Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> "evt-" + ids.incrementAndGet());
            for (Map.Entry<String, Instant> file : seeded.entrySet()) {
                Path seededFile = log.projectAuditDirectory().resolve(file.getKey());
                String line = "{\"sequence\":1}\n";
                Files.writeString(seededFile, "owner.jsonl".equals(file.getKey())
                        ? line.repeat(300) : line);
                Files.setLastModifiedTime(seededFile, FileTime.from(file.getValue()));
            }
            assertTrue(Files.size(log.streamPath()) > 4096);

            log.append(new AuditEvent("table-probe", AuditEvent.Outcome.SUCCEEDED,
                    new AuditEvent.Actor("client-1", "Table probe", "oauth",
                            Set.of("ontology:read")),
                    "https://example.org/ontology", null, "pass", null, Map.of(),
                    List.of(), null));

            Set<String> survivors = survivors(log.projectAuditDirectory());
            assertEquals(new TreeSet<>(current.expectedSurvivors()), survivors,
                    "case '" + current.name() + "': exactly the table's files may be deleted");
            assertTrue(Files.exists(log.streamPath()), current.name());
        }
    }

    @Test
    void aFallbackAppendScansDeepAuthoredRotationsForTheNextSequence() throws Exception {
        // A crash can leave the newest committed sequence ONLY in a rotation beyond the fallback
        // default of 10 files (an authored max_files up to 100 created it). The sequence scan must
        // use the schema-maximum depth under fallback or committed sequence numbers get reissued.
        // (The sibling depth use in rotateIfNeeded is unreachable under fallback below the 1 GiB
        // schema-maximum stream size, so this scan is the observable pin for the shared bound.)
        Path project = Files.createDirectories(temp.resolve("deep-scan-project"));
        AtomicInteger ids = new AtomicInteger();
        AuditLog log = new AuditLog(temp.resolve("deep-scan-audit"), project, "owner",
                AuditSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "evt-" + ids.incrementAndGet());
        Path deepRotation = log.projectAuditDirectory().resolve("owner.jsonl.10");
        Files.writeString(deepRotation, "{\"sequence\":41}\n");

        log.append(new AuditEvent("deep-scan-probe", AuditEvent.Outcome.SUCCEEDED,
                new AuditEvent.Actor("client-1", "Deep scan probe", "oauth",
                        Set.of("ontology:read")),
                "https://example.org/ontology", null, "pass", null, Map.of(),
                List.of(), null));

        List<String> lines = Files.readAllLines(log.streamPath());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"sequence\":42"),
                "a fallback append must consult rotations beyond its own default max_files "
                        + "instead of reissuing committed sequence numbers: " + lines.get(0));
    }

    private static Set<String> survivors(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
