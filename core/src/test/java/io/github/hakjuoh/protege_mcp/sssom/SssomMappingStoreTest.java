package io.github.hakjuoh.protege_mcp.sssom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

import io.github.hakjuoh.protege_mcp.core.workspace.ProjectFileLock;

class SssomMappingStoreTest {

    @TempDir
    Path temporary;

    @Test
    void absentAddRemoveAndStaleCasAreDeterministic() throws Exception {
        Path target = temporary.resolve(".protege-mcp/mappings.sssom.tsv");
        Files.createDirectories(target.getParent());
        SssomMappingStore store = store(target);
        SssomMappingStore.Snapshot empty = store.read(structural(), unavailable());
        assertFalse(empty.exists());
        assertEquals(0, empty.recordCount());

        SssomMappingStore.Mutation added = store.add(empty.mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        assertTrue(added.committed());
        assertTrue(Files.isRegularFile(target));
        assertNotEquals(empty.mappingRevision(), added.mappingRevision());
        assertEquals(1, added.recordCount());
        assertEquals(added.mappingRevision(), store.read(structural(), unavailable()).mappingRevision());

        SssomStoreException stale = assertThrows(SssomStoreException.class,
                () -> store.add(empty.mappingRevision(), row("C", "D"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_revision_conflict", stale.code());
        assertTrue(stale.effectsPrevented());

        String id = store.read(structural(), unavailable()).validation()
                .document().records().get(0).mappingId();
        SssomMappingStore.Mutation removed = store.remove(added.mappingRevision(), id,
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        assertTrue(removed.committed());
        assertEquals(0, removed.recordCount());
        assertTrue(store.read(structural(), unavailable()).validation().valid());
        byte[] afterRemove = Files.readAllBytes(target);
        SssomStoreException repeated = assertThrows(SssomStoreException.class,
                () -> store.remove(removed.mappingRevision(), id, structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_not_found", repeated.code());
        assertArrayEquals(afterRemove, Files.readAllBytes(target));
    }

    @Test
    void lockedAddRejectsExistenceDriftEvenWhenCanonicalRevisionIsEqual() throws Exception {
        Path target = temporary.resolve("existence/mappings.sssom.tsv");
        Files.createDirectories(target.getParent());
        SssomMappingStore store = store(target);
        SssomMappingStore.Snapshot absent = store.read(structural(), unavailable());
        Files.write(target, SssomParser.render(SssomDocument.empty()));
        SssomMappingStore.Snapshot present = store.read(structural(), unavailable());
        assertTrue(present.exists());
        assertEquals(absent.mappingRevision(), present.mappingRevision());

        SssomStoreException appeared = assertThrows(SssomStoreException.class,
                () -> store.add(absent.mappingRevision(), row("A", "B"),
                        mappingMetadata(), Map.of(), structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none(), false));
        assertEquals("mapping_existence_conflict", appeared.code());
        assertEquals(0, store.read(structural(), unavailable()).recordCount());

        Files.delete(target);
        SssomStoreException disappeared = assertThrows(SssomStoreException.class,
                () -> store.add(present.mappingRevision(), row("A", "B"),
                        mappingMetadata(), Map.of(), structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none(), true));
        assertEquals("mapping_existence_conflict", disappeared.code());
        assertFalse(Files.exists(target));
    }

    @Test
    void exactDuplicateIsIdempotentAndIdCollisionIsAtomic() throws Exception {
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomMappingStore store = store(target);
        SssomMappingStore.Snapshot empty = store.read(structural(), unavailable());
        MappingRecord row = row("A", "B");
        SssomMappingStore.Mutation first = store.add(empty.mappingRevision(), row,
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        byte[] before = Files.readAllBytes(target);
        MappingRecord canonical = store.read(structural(), unavailable())
                .validation().document().records().get(0);

        SssomMappingStore.Mutation duplicate = store.add(first.mappingRevision(), canonical,
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        assertFalse(duplicate.committed());
        assertArrayEquals(before, Files.readAllBytes(target));

        MappingRecord collision = canonical.withCells(Map.of("object_id", "https://example.org/C"));
        SssomStoreException invalid = assertThrows(SssomStoreException.class,
                () -> store.add(first.mappingRevision(), collision, structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_validation_failed", invalid.code());
        assertTrue(invalid.findings().stream().anyMatch(f -> "mapping_id_conflict".equals(f.code())));
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void extensionHeaderAbsenceAndExplicitEmptyAreTheSameIdempotentRow() throws Exception {
        Path target = temporary.resolve("header-affinity.sssom.tsv");
        SssomMappingStore store = store(target);
        MappingRecord raw = row("A", "B");
        SssomDocument withExtensionHeader = new SssomDocument(mappingMetadata(), Map.of(),
                List.of("mapping_id", "subject_id", "predicate_id", "object_id",
                        "mapping_justification", "x_note"), List.of(raw));
        SssomMappingStore.Mutation imported = store.importDocument(
                store.read(structural(), unavailable()).mappingRevision(), withExtensionHeader,
                SssomMappingStore.ImportMode.REPLACE, structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        byte[] before = Files.readAllBytes(target);

        SssomMappingStore.Mutation duplicate = store.add(imported.mappingRevision(), raw,
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        assertFalse(duplicate.committed());
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void mergePreservesExtensionsAndRejectsMetadataOrPrefixConflicts() throws Exception {
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomMappingStore store = store(target);
        SssomDocument base = document(Map.of("mapping_set_id", "https://example.org/set"),
                Map.of("ex", "https://example.org/"),
                row("A", "B").withCells(Map.of("review_note", "first")));
        SssomMappingStore.Mutation replaced = store.importDocument(
                store.read(structural(), unavailable()).mappingRevision(), base,
                SssomMappingStore.ImportMode.REPLACE, structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());

        SssomDocument incoming = document(Map.of("mapping_set_id", "https://example.org/set",
                        "mapping_set_description", "incoming"),
                Map.of("other", "https://other.example/"),
                row("C", "D").withCells(Map.of("other_extension", "second")));
        SssomMappingStore.Mutation merged = store.importDocument(replaced.mappingRevision(), incoming,
                SssomMappingStore.ImportMode.MERGE, structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        SssomDocument saved = store.read(structural(), unavailable()).validation().document();
        assertEquals(2, merged.recordCount());
        assertTrue(saved.columns().containsAll(List.of("review_note", "other_extension")));
        assertEquals("https://example.org/set", saved.metadata().get("mapping_set_id"));
        assertEquals("incoming", saved.metadata().get("mapping_set_description"));

        byte[] before = Files.readAllBytes(target);
        SssomDocument conflict = document(Map.of("mapping_set_id", "https://example.org/other"),
                Map.of("ex", "https://else.example/"), row("E", "F"));
        SssomStoreException rejected = assertThrows(SssomStoreException.class,
                () -> store.importDocument(merged.mappingRevision(), conflict,
                        SssomMappingStore.ImportMode.MERGE, structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_metadata_conflict", rejected.code());
        assertArrayEquals(before, Files.readAllBytes(target));

        SssomDocument prefixConflict = document(Map.of(
                        "mapping_set_id", "https://example.org/set",
                        "mapping_set_description", "incoming"),
                Map.of("ex", "https://else.example/"), row("E", "F"));
        SssomStoreException prefixRejected = assertThrows(SssomStoreException.class,
                () -> store.importDocument(merged.mappingRevision(), prefixConflict,
                        SssomMappingStore.ImportMode.MERGE, structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_prefix_conflict", prefixRejected.code());
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void replaceFailureAndGuardDriftLeaveOldStoreUntouched() throws Exception {
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomMappingStore initial = store(target);
        SssomMappingStore.Mutation seeded = initial.add(
                initial.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        byte[] before = Files.readAllBytes(target);

        SssomMappingStore failing = new SssomMappingStore(temporary, target, stateRoot(),
                () -> { throw new IOException("injected before replace"); }, () -> { },
                SssomMappingStoreTest::atomicMove);
        SssomStoreException failed = assertThrows(SssomStoreException.class,
                () -> failing.add(seeded.mappingRevision(), row("C", "D"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_write_failed", failed.code());
        assertTrue(failed.effectsPrevented());
        assertArrayEquals(before, Files.readAllBytes(target));
        assertEquals(0, backupArtifacts(temporary));

        AtomicInteger checks = new AtomicInteger();
        SssomStoreException drift = assertThrows(SssomStoreException.class,
                () -> initial.add(seeded.mappingRevision(), row("E", "F"), structural(),
                        unavailable(), () -> {
                            if (checks.incrementAndGet() == 3) throw new IOException("policy drift");
                        }));
        assertEquals("mutation_guard_failed", drift.code());
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void guardDriftImmediatelyAfterReplacementRecoversThePreviousStore() throws Exception {
        Path target = temporary.resolve("post-replace-guard.tsv");
        SssomMappingStore store = store(target);
        SssomMappingStore.Mutation seeded = store.add(
                store.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        byte[] before = Files.readAllBytes(target);
        AtomicInteger checks = new AtomicInteger();

        SssomStoreException drift = assertThrows(SssomStoreException.class,
                () -> store.add(seeded.mappingRevision(), row("C", "D"), structural(),
                        unavailable(), () -> {
                            if (checks.incrementAndGet() == 4) {
                                throw new IOException("late model or authorization drift");
                            }
                        }));

        assertEquals("post_commit_verification_failed_recovered", drift.code());
        assertTrue(drift.effectsPrevented());
        assertFalse(drift.outcomeUnknown());
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void postInstallFailureRestoresVerifiedBackup() throws Exception {
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomMappingStore initial = store(target);
        SssomMappingStore.Mutation seeded = initial.add(
                initial.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        byte[] before = Files.readAllBytes(target);

        AtomicBoolean failOnce = new AtomicBoolean(true);
        SssomMappingStore recovering = new SssomMappingStore(temporary, target, stateRoot(),
                () -> { }, () -> {
                    if (failOnce.getAndSet(false)) throw new IOException("post-install verification");
                }, SssomMappingStoreTest::atomicMove);
        SssomStoreException failure = assertThrows(SssomStoreException.class,
                () -> recovering.add(seeded.mappingRevision(), row("C", "D"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("post_commit_verification_failed_recovered", failure.code());
        assertTrue(failure.effectsPrevented());
        assertFalse(failure.outcomeUnknown());
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void renameThenThrowIsReconciledAndRecovered() throws Exception {
        Path target = temporary.resolve("rename-then-throw.sssom.tsv");
        SssomMappingStore initial = store(target);
        SssomMappingStore.Mutation seeded = initial.add(
                initial.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        byte[] before = Files.readAllBytes(target);
        AtomicBoolean failAfterMove = new AtomicBoolean(true);
        SssomMappingStore ambiguousMover = new SssomMappingStore(temporary, target, stateRoot(),
                () -> { }, () -> { }, (source, destination) -> {
                    atomicMove(source, destination);
                    if (destination.equals(target.toRealPath().getParent()
                            .resolve(target.getFileName())) && failAfterMove.getAndSet(false)) {
                        throw new IOException("rename completed before provider error");
                    }
                });

        SssomStoreException failure = assertThrows(SssomStoreException.class,
                () -> ambiguousMover.add(seeded.mappingRevision(), row("C", "D"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("post_commit_verification_failed_recovered", failure.code());
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void unrecoverablePostInstallTamperIsOutcomeUnknown() throws Exception {
        Path target = temporary.resolve("unknown-outcome.sssom.tsv");
        SssomMappingStore initial = store(target);
        SssomMappingStore.Mutation seeded = initial.add(
                initial.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        SssomMappingStore tampered = new SssomMappingStore(temporary, target, stateRoot(),
                () -> { }, () -> Files.writeString(target, "tampered"),
                SssomMappingStoreTest::atomicMove);

        SssomStoreException failure = assertThrows(SssomStoreException.class,
                () -> tampered.add(seeded.mappingRevision(), row("C", "D"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_write_outcome_unknown", failure.code());
        assertTrue(failure.outcomeUnknown());
        assertFalse(failure.effectsPrevented());
    }

    @Test
    void atomicMoveUnsupportedBeforeReplacementPreservesBytes() throws Exception {
        Path target = temporary.resolve("atomic-unsupported.sssom.tsv");
        SssomMappingStore initial = store(target);
        SssomMappingStore.Mutation seeded = initial.add(
                initial.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        byte[] before = Files.readAllBytes(target);
        SssomMappingStore unsupported = new SssomMappingStore(temporary, target, stateRoot(),
                () -> { }, () -> { }, (source, destination) -> {
                    throw new java.nio.file.AtomicMoveNotSupportedException(
                            source.toString(), destination.toString(), "injected");
                });

        SssomStoreException failure = assertThrows(SssomStoreException.class,
                () -> unsupported.add(seeded.mappingRevision(), row("C", "D"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("atomic_replace_unsupported", failure.code());
        assertTrue(failure.effectsPrevented());
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void parentDirectorySwapIsDetectedBeforeReplacement() throws Exception {
        Path parent = temporary.resolve("mapping-dir");
        Path moved = temporary.resolve("mapping-dir-moved");
        Path outside = temporary.resolve("outside");
        Files.createDirectories(parent);
        Files.createDirectories(outside);
        Path target = parent.resolve("mappings.tsv");
        SssomMappingStore swapping = new SssomMappingStore(temporary, target, stateRoot(), () -> {
            Files.move(parent, moved);
            Files.createSymbolicLink(parent, outside);
        }, () -> { }, SssomMappingStoreTest::atomicMove);
        String revision = swapping.read(structural(), unavailable()).mappingRevision();
        try {
            SssomStoreException failure = assertThrows(SssomStoreException.class,
                    () -> swapping.add(revision, row("A", "B"), mappingMetadata(), Map.of(),
                            structural(), unavailable(), SssomMappingStore.MutationGuard.none()));
            assertEquals("symlink_forbidden", failure.code());
            assertTrue(failure.effectsPrevented());
            assertFalse(Files.exists(outside.resolve("mappings.tsv")));
        } finally {
            Files.deleteIfExists(parent);
            if (Files.exists(moved)) Files.move(moved, parent);
        }
    }

    @Test
    void sourceParentSwapCannotRedirectReadsOutsideProject() throws Exception {
        Path parent = temporary.resolve("source-dir");
        Path moved = temporary.resolve("source-dir-moved");
        Path outside = temporary.resolve("source-outside");
        Files.createDirectories(parent);
        Files.createDirectories(outside);
        Path target = parent.resolve("mappings.tsv");
        SssomMappingStore store = store(target);
        store.add(store.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        Files.write(outside.resolve("mappings.tsv"), SssomParser.render(
                document(Map.of(), Map.of(), row("Outside", "Data"))));
        Files.move(parent, moved);
        try {
            Files.createSymbolicLink(parent, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Files.move(moved, parent);
            return;
        }
        try {
            SssomStoreException refused = assertThrows(SssomStoreException.class,
                    () -> store.read(structural(), unavailable()));
            assertTrue(refused.effectsPrevented());
            assertFalse(refused.code().isBlank());
        } finally {
            Files.deleteIfExists(parent);
            Files.move(moved, parent);
        }
    }

    @Test
    void importRechecksSourceConfinementInsideTheProjectLock() throws Exception {
        Path sourceParent = temporary.resolve("imports");
        Path movedParent = temporary.resolve("imports-reviewed");
        Path outsideParent = temporary.resolveSibling(
                temporary.getFileName() + "-outside-imports");
        Files.createDirectories(sourceParent);
        Files.createDirectories(outsideParent);
        Path source = sourceParent.resolve("incoming.tsv");
        Files.write(source, SssomParser.render(document(Map.of(), Map.of(), row("A", "B"))));
        Files.write(outsideParent.resolve("incoming.tsv"), SssomParser.render(
                document(Map.of(), Map.of(), row("Outside", "Payload"))));
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomMappingStore store = store(target);
        String revision = store.read(structural(), unavailable()).mappingRevision();
        AtomicBoolean swapped = new AtomicBoolean();

        try {
            SssomStoreException refusal = assertThrows(SssomStoreException.class,
                    () -> store.importFile(revision, source,
                            SssomMappingStore.ImportMode.REPLACE, structural(), unavailable(), () -> {
                                if (swapped.compareAndSet(false, true)) {
                                    Files.move(sourceParent, movedParent);
                                    Files.createSymbolicLink(sourceParent, outsideParent);
                                }
                            }));

            assertEquals("path_outside_project", refusal.code());
            assertTrue(refusal.effectsPrevented());
            assertFalse(Files.exists(target));
        } catch (UnsupportedOperationException unsupported) {
            return;
        } finally {
            Files.deleteIfExists(sourceParent);
            if (Files.exists(movedParent)) Files.move(movedParent, sourceParent);
            Files.deleteIfExists(outsideParent.resolve("incoming.tsv"));
            Files.deleteIfExists(outsideParent);
        }
    }

    @Test
    void invalidParseableStoreCanBeRecoveredOnlyByReplace() throws Exception {
        Path target = temporary.resolve("repair.sssom.tsv");
        Files.writeString(target, "mapping_id\tpredicate_id\tmapping_justification\n"
                + "urn:bad\tmissing:predicate\tsemapv:ManualMappingCuration\n");
        SssomMappingStore store = store(target);
        SssomMappingStore.Snapshot invalid = store.read(structural(), unavailable());
        assertFalse(invalid.validation().valid());
        assertThrows(SssomStoreException.class,
                () -> store.add(invalid.mappingRevision(), row("A", "B"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));

        SssomMappingStore.Mutation repaired = store.importDocument(invalid.mappingRevision(),
                document(Map.of(), Map.of(), row("A", "B")),
                SssomMappingStore.ImportMode.REPLACE, structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        assertTrue(repaired.committed());
        assertTrue(store.read(structural(), unavailable()).validation().valid());
    }

    @Test
    void successfulMutationsRetainOnlyOneStableBackupPerTarget() throws Exception {
        Path target = temporary.resolve("bounded-backup.sssom.tsv");
        SssomMappingStore store = store(target);
        SssomMappingStore.Mutation first = store.add(
                store.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        SssomMappingStore.Mutation second = store.add(first.mappingRevision(), row("C", "D"),
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        store.add(second.mappingRevision(), row("E", "F"), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        assertEquals(1, backupArtifacts(temporary));
    }

    @Test
    void backupPublishThenThrowRestoresTargetAndPreviousStableBackup() throws Exception {
        Path target = temporary.resolve("backup-publish-throw.sssom.tsv");
        SssomMappingStore store = store(target);
        SssomMappingStore.Mutation first = store.add(
                store.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        SssomMappingStore.Mutation second = store.add(first.mappingRevision(), row("C", "D"),
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        byte[] targetBefore = Files.readAllBytes(target);
        Path backup = onlyBackup(temporary);
        byte[] backupBefore = Files.readAllBytes(backup);
        AtomicBoolean failAfterBackupPublish = new AtomicBoolean(true);
        SssomMappingStore failing = new SssomMappingStore(temporary, target, stateRoot(),
                () -> { }, () -> { }, (source, destination) -> {
                    atomicMove(source, destination);
                    if (destination.getFileName().toString().startsWith(".protege-mcp-backup-")
                            && failAfterBackupPublish.getAndSet(false)) {
                        throw new IOException("backup rename completed before provider error");
                    }
                });

        SssomStoreException failure = assertThrows(SssomStoreException.class,
                () -> failing.add(second.mappingRevision(), row("E", "F"), structural(),
                        unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("post_commit_verification_failed_recovered", failure.code());
        assertArrayEquals(targetBefore, Files.readAllBytes(target));
        assertArrayEquals(backupBefore, Files.readAllBytes(onlyBackup(temporary)));
    }

    @Test
    void lockAndTwoWriterRaceNeverLoseAnUpdate() throws Exception {
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomMappingStore first = store(target);
        SssomMappingStore second = store(target);
        String revision = first.read(structural(), unavailable()).mappingRevision();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> left = executor.submit(() -> attempt(start, first, revision, row("A", "B")));
            Future<Object> right = executor.submit(() -> attempt(start, second, revision, row("C", "D")));
            start.countDown();
            Object leftResult = left.get();
            Object rightResult = right.get();
            long commits = List.of(leftResult, rightResult).stream()
                    .filter(SssomMappingStore.Mutation.class::isInstance).count();
            assertEquals(1, commits);
            assertTrue(List.of(leftResult, rightResult).stream()
                    .filter(Throwable.class::isInstance).count() == 1);
            Object refused = leftResult instanceof Throwable ? leftResult : rightResult;
            assertTrue(refused instanceof SssomStoreException);
            assertEquals("mapping_revision_conflict", ((SssomStoreException) refused).code());
        } finally {
            executor.shutdownNow();
        }
        SssomMappingStore.Snapshot saved = first.read(structural(), unavailable());
        assertEquals(1, saved.recordCount());
        assertTrue(saved.validation().valid());

        AtomicBoolean entered = new AtomicBoolean();
        ProjectFileLock.withLock(stateRoot(), temporary, () -> {
            entered.set(true);
            assertThrows(IOException.class, () -> first.add(saved.mappingRevision(), row("E", "F"),
                    structural(), unavailable(), SssomMappingStore.MutationGuard.none()));
            return null;
        });
        assertTrue(entered.get());
        assertEquals(1, first.read(structural(), unavailable()).recordCount());
    }

    @Test
    void exportIsNoClobberCasAndSpreadsheetSafeIsExplicitlyLossy() throws Exception {
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomMappingStore store = store(target);
        Path output = temporary.resolve("exports/mappings.tsv");
        Files.createDirectories(output.getParent());
        String absentRevision = store.read(structural(), unavailable()).mappingRevision();
        SssomStoreException absent = assertThrows(SssomStoreException.class,
                () -> store.export(absentRevision, output, false, null, false,
                        structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_store_absent", absent.code());

        MappingRecord formula = row("A", "B").withCells(Map.of("comment", " \t=SUM(A1:A2)"));
        SssomMappingStore.Mutation added = store.add(
                store.read(structural(), unavailable()).mappingRevision(), formula,
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());

        SssomStoreException staleSource = assertThrows(SssomStoreException.class,
                () -> store.export(absentRevision, output, false, null, false,
                        structural(), unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("mapping_revision_conflict", staleSource.code());
        assertFalse(Files.exists(output));

        SssomMappingStore.Export first = store.export(added.mappingRevision(), output,
                false, null, true,
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        assertTrue(first.committed());
        assertTrue(first.spreadsheetSafe());
        assertFalse(first.lossless());
        assertTrue(Files.readString(output).contains("' \t=SUM(A1:A2)"));

        SssomStoreException noClobber = assertThrows(SssomStoreException.class,
                () -> store.export(added.mappingRevision(), output, false, null, false,
                        structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("target_exists", noClobber.code());
        SssomStoreException missingDigest = assertThrows(SssomStoreException.class,
                () -> store.export(added.mappingRevision(), output, true, null, false,
                        structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("expected_target_digest_required", missingDigest.code());

        byte[] beforeConflict = Files.readAllBytes(output);
        SssomStoreException staleDigest = assertThrows(SssomStoreException.class,
                () -> store.export(added.mappingRevision(), output, true,
                        "sha256:" + "0".repeat(64), false,
                        structural(), unavailable(), SssomMappingStore.MutationGuard.none()));
        assertEquals("target_digest_conflict", staleDigest.code());
        assertArrayEquals(beforeConflict, Files.readAllBytes(output));

        SssomMappingStore.Export overwritten = store.export(added.mappingRevision(), output,
                true, first.sha256(), false,
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        assertTrue(overwritten.lossless());
        assertTrue(Files.readString(output).contains(" \t=SUM(A1:A2)"));
        assertFalse(Files.readString(output).contains("' \t=SUM(A1:A2)"));
    }

    @Test
    void symlinkEscapesAndOutputAliasingAreRefused() throws Exception {
        Path outside = temporary.resolveSibling(temporary.getFileName() + "-outside");
        Files.createDirectories(outside);
        Path link = temporary.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            return;
        }
        SssomStoreException escaped = assertThrows(SssomStoreException.class,
                () -> new SssomMappingStore(temporary, link.resolve("mapping.tsv"), stateRoot()));
        assertEquals("symlink_forbidden", escaped.code());

        Path target = temporary.resolve("mapping.tsv");
        SssomMappingStore store = store(target);
        SssomMappingStore.Mutation added = store.add(
                store.read(structural(), unavailable()).mappingRevision(), row("A", "B"),
                mappingMetadata(), Map.of(), structural(), unavailable(),
                SssomMappingStore.MutationGuard.none());
        SssomStoreException alias = assertThrows(SssomStoreException.class,
                () -> store.export(added.mappingRevision(), target, true, null, false,
                        structural(), unavailable(),
                        SssomMappingStore.MutationGuard.none()));
        assertEquals("export_target_is_store", alias.code());
    }

    @Test
    void nonCanonicalInputUsesCanonicalRevisionAndMutationCanonicalizes() throws Exception {
        Path target = temporary.resolve("mappings.sssom.tsv");
        SssomDocument document = document(Map.of(), Map.of(), row("A", "B"));
        byte[] canonical = SssomParser.render(document.canonical());
        byte[] withBomCrLf = ("\ufeff" + new String(canonical, StandardCharsets.UTF_8)
                .replace("\n", "\r\n")).getBytes(StandardCharsets.UTF_8);
        Files.write(target, withBomCrLf);
        SssomMappingStore store = store(target);
        SssomMappingStore.Snapshot snapshot = store.read(structural(), unavailable());
        assertTrue(snapshot.validation().valid());
        MappingRecord existing = snapshot.validation().document().records().get(0);
        SssomMappingStore.Mutation result = store.add(snapshot.mappingRevision(), existing,
                structural(), unavailable(), SssomMappingStore.MutationGuard.none());
        assertTrue(result.committed());
        assertArrayEquals(canonical, Files.readAllBytes(target));
    }

    @Test
    void entityIndexCapturesPresenceAndOwlDeprecated() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology();
        var data = manager.getOWLDataFactory();
        IRI active = IRI.create("https://example.org/Active");
        IRI retired = IRI.create("https://example.org/Retired");
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(data.getOWLClass(active)));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(data.getOWLClass(retired)));
        manager.addAxiom(ontology, data.getOWLAnnotationAssertionAxiom(
                data.getOWLDeprecated(), retired, data.getOWLLiteral(true)));
        SssomEntityIndex index = SssomEntityIndexes.fromOntologies(List.of(ontology));
        assertTrue(index.available());
        assertTrue(index.present().containsAll(List.of(active.toString(), retired.toString())));
        assertEquals(List.of(retired.toString()), index.deprecated().stream().toList());
    }

    @Test
    void directoryFsyncFallbackIsLimitedToKnownWindowsUnsupportedSignals() {
        assertTrue(SssomMappingStore.directoryFsyncUnavailable("Windows 11",
                new java.nio.file.AccessDeniedException("directory")));
        assertTrue(SssomMappingStore.directoryFsyncUnavailable("Windows Server",
                new java.nio.file.FileSystemException("directory", null,
                        "The request is not supported")));
        assertFalse(SssomMappingStore.directoryFsyncUnavailable("Linux",
                new java.nio.file.AccessDeniedException("directory")));
        assertFalse(SssomMappingStore.directoryFsyncUnavailable("Windows 11",
                new IOException("disk failure")));
    }

    private Object attempt(CountDownLatch start, SssomMappingStore store, String revision,
            MappingRecord record) {
        try {
            start.await();
            return store.add(revision, record, mappingMetadata(), Map.of(), structural(), unavailable(),
                    SssomMappingStore.MutationGuard.none());
        } catch (Throwable failure) {
            return failure;
        }
    }

    private SssomMappingStore store(Path target) throws IOException {
        return new SssomMappingStore(temporary, target, stateRoot());
    }

    private Path stateRoot() {
        return temporary.resolve(".locks");
    }

    private static SssomDocument document(Map<String, Object> metadata,
            Map<String, String> prefixes, MappingRecord... records) {
        Map<String, Object> completeMetadata = new LinkedHashMap<>(mappingMetadata());
        completeMetadata.putAll(metadata);
        List<String> columns = new java.util.ArrayList<>(List.of(
                "mapping_id", "subject_id", "predicate_id", "object_id",
                "mapping_justification"));
        for (MappingRecord record : records) {
            for (String column : record.cells().keySet()) if (!columns.contains(column)) columns.add(column);
        }
        return new SssomDocument(completeMetadata, prefixes, columns, List.of(records));
    }

    private static Map<String, Object> mappingMetadata() {
        return Map.of("mapping_set_id", "https://example.org/mappings",
                "license", "https://creativecommons.org/licenses/by/4.0/");
    }

    private static MappingRecord row(String subject, String object) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("mapping_id", "");
        cells.put("subject_id", "https://example.org/" + subject);
        cells.put("predicate_id", "skos:exactMatch");
        cells.put("object_id", "https://example.org/" + object);
        cells.put("mapping_justification", "semapv:ManualMappingCuration");
        return new MappingRecord(cells);
    }

    private static SssomValidationPolicy structural() {
        return SssomValidationPolicy.structural();
    }

    private static SssomEntityIndex unavailable() {
        return SssomEntityIndex.unavailable();
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static long backupArtifacts(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString()
                    .startsWith(".protege-mcp-backup-")).count();
        }
    }

    private static Path onlyBackup(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString()
                    .startsWith(".protege-mcp-backup-")).findFirst().orElseThrow();
        }
    }
}
