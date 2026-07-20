package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.StageResult;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseBundleService;
import io.github.hakjuoh.protege_mcp.core.release.ReleaseManifest;

class WorkspaceBundleTransactionTest {

    private static final String ROOT_IRI = "https://example.org/bundle-workspace";

    @TempDir
    Path temp;

    @Test
    void publishesANewCompleteDirectoryAndCanRemoveItOnRecovery() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = temp.resolve("dist");
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = workspace.beginBundleTransaction(
                        snapshot, output, null)) {
            ReleaseBundleService.Bundle bundle = bundle(snapshot);

            WorkspaceBundleTransaction.Stage stage = transaction.stage(bundle);
            WorkspaceBundleTransaction.Commit commit = transaction.commit();

            assertEquals(bundle.artifacts().size(), stage.artifacts());
            assertFalse(commit.previousExisted());
            assertNull(commit.backupPath());
            assertTrue(Files.isRegularFile(output.resolve("manifest.json")));
            WorkspaceBundleTransaction.Recovery recovery = transaction.recover();
            assertFalse(recovery.restored());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void replacesAndRecoversAnExistingDirectoryFromAVerifiedBackup() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = Files.createDirectories(temp.resolve("dist"));
        Files.writeString(output.resolve("previous.txt"), "previous release");
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = workspace.beginBundleTransaction(
                        snapshot, output, null)) {
            transaction.stage(bundle(snapshot));

            WorkspaceBundleTransaction.Commit commit = transaction.commit();

            assertTrue(commit.previousExisted());
            assertNotNull(commit.backupPath());
            assertEquals("previous release",
                    Files.readString(commit.backupPath().resolve("previous.txt")));
            assertTrue(Files.exists(output.resolve("manifest.json")));
            WorkspaceBundleTransaction.Recovery recovery = transaction.recover();
            assertTrue(recovery.restored());
            assertEquals("previous release", Files.readString(output.resolve("previous.txt")));
            assertFalse(Files.exists(output.resolve("manifest.json")));
            assertFalse(Files.exists(commit.backupPath()));
        }
    }

    @Test
    void externalGuardDriftRefusesCommitWithoutTouchingTheOutput() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = Files.createDirectories(temp.resolve("dist"));
        Files.writeString(output.resolve("previous.txt"), "previous release");
        AtomicBoolean valid = new AtomicBoolean(true);
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = workspace.beginBundleTransaction(
                        snapshot, output, () -> {
                            if (!valid.get()) throw new IOException("baseline changed");
                        })) {
            transaction.stage(bundle(snapshot));
            valid.set(false);

            IOException refusal = assertThrows(IOException.class, transaction::commit);

            assertTrue(refusal.getMessage().contains("baseline changed"), refusal::getMessage);
            assertEquals("previous release", Files.readString(output.resolve("previous.txt")));
            assertFalse(Files.exists(output.resolve("manifest.json")));
        }
    }

    @Test
    void stagedTamperingIsDetectedBeforePublication() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = temp.resolve("dist");
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = workspace.beginBundleTransaction(
                        snapshot, output, null)) {
            transaction.stage(bundle(snapshot));
            Files.writeString(transaction.stagedPath().resolve("manifest.json"), "tampered");

            assertThrows(IOException.class, transaction::commit);

            assertFalse(Files.exists(output));
        }
    }

    @Test
    void failedSecondRenameAutomaticallyRestoresThePreviousDirectory() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = Files.createDirectories(temp.resolve("dist"));
        Files.writeString(output.resolve("previous.txt"), "previous release");
        AtomicInteger moves = new AtomicInteger();
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = new WorkspaceBundleTransaction(
                        workspace, snapshot, output, null, () -> { }, (source, target) -> {
                            if (moves.incrementAndGet() == 2) throw new IOException("injected move failure");
                            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                        })) {
            transaction.stage(bundle(snapshot));

            IOException failure = assertThrows(IOException.class, transaction::commit);

            assertTrue(failure.getMessage().contains("injected move failure"), failure::getMessage);
            assertEquals("previous release", Files.readString(output.resolve("previous.txt")));
            assertFalse(Files.exists(output.resolve("manifest.json")));
        }
    }

    @Test
    void sharesTheProjectAdvisoryLockWithSingleFileTransactions() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = workspace.beginBundleTransaction(
                        snapshot, temp.resolve("dist"), null)) {
            transaction.stage(bundle(snapshot));
            Path root = snapshot.policy().projectRoot().toRealPath();
            try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                    workspace.stateRoot(), root)) {
                IOException refusal = assertThrows(IOException.class, transaction::commit);
                assertTrue(refusal.getMessage().contains("lock is already held"), refusal::getMessage);
            }
        }
    }

    @Test
    void sourceDriftImmediatelyBeforeReplacementPreservesThePreviousOutput() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = Files.createDirectories(temp.resolve("dist"));
        Files.writeString(output.resolve("previous.txt"), "previous release");
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = new WorkspaceBundleTransaction(
                        workspace, snapshot, output, null,
                        () -> Files.writeString(temp.resolve("root.ttl"), "changed"),
                        (source, target) -> Files.move(
                                source, target, StandardCopyOption.ATOMIC_MOVE))) {
            transaction.stage(bundle(snapshot));

            assertThrows(IOException.class, transaction::commit);

            assertEquals("previous release", Files.readString(output.resolve("previous.txt")));
            assertFalse(Files.exists(output.resolve("manifest.json")));
        }
    }

    @Test
    void refusesAnOutputDirectorySymlink() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path real = Files.createDirectories(temp.resolve("real-output"));
        Path link = temp.resolve("dist");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + unsupported);
        }
        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            assertThrows(IOException.class,
                    () -> workspace.beginBundleTransaction(snapshot, link, null));
        }
        assertFalse(Files.exists(real.resolve("manifest.json")));
    }

    @Test
    void ambiguousFirstRenameIsDetectedAndTheBaselineIsRestored() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = Files.createDirectories(temp.resolve("dist"));
        Files.writeString(output.resolve("previous.txt"), "previous release");
        AtomicInteger moves = new AtomicInteger();
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = new WorkspaceBundleTransaction(
                        workspace, snapshot, output, null, () -> { }, (source, target) -> {
                            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                            if (moves.incrementAndGet() == 1) {
                                throw new IOException("injected exception after first move");
                            }
                        })) {
            transaction.stage(bundle(snapshot));

            IOException failure = assertThrows(IOException.class, transaction::commit);

            assertTrue(failure.getMessage().contains("after first move"), failure::getMessage);
            assertEquals("previous release", Files.readString(output.resolve("previous.txt")));
            assertFalse(Files.exists(output.resolve("manifest.json")));
        }
    }

    @Test
    void failedRecoveryRollbackPreservesTheInstalledTreeForManualRecovery() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        Path output = Files.createDirectories(temp.resolve("dist"));
        Files.writeString(output.resolve("previous.txt"), "previous release");
        AtomicInteger moves = new AtomicInteger();
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = new WorkspaceBundleTransaction(
                        workspace, snapshot, output, null, () -> { }, (source, target) -> {
                            int move = moves.incrementAndGet();
                            if (move == 4 || move == 5) {
                                throw new IOException("injected recovery move failure " + move);
                            }
                            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                        })) {
            transaction.stage(bundle(snapshot));
            WorkspaceBundleTransaction.Commit commit = transaction.commit();

            IOException failure = assertThrows(IOException.class, transaction::recover);

            assertTrue(failure.getMessage().contains("manual recovery"), failure::getMessage);
            assertFalse(Files.exists(output));
            assertTrue(Files.exists(commit.backupPath().resolve("previous.txt")));
            try (var siblings = Files.list(temp)) {
                Path trash = siblings.filter(path -> path.getFileName().toString()
                                .contains(".dist.protege-mcp-recovery-"))
                        .findFirst().orElseThrow();
                assertTrue(Files.exists(trash.resolve("manifest.json")), trash::toString);
            }
        }
    }

    @Test
    void rejectsAHandBuiltManifestThatDoesNotIndexItsArtifacts() throws Exception {
        FilesystemProjectWorkspace workspace = workspace();
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceBundleTransaction transaction = workspace.beginBundleTransaction(
                        snapshot, temp.resolve("dist"), null)) {
            ReleaseBundleService.Bundle valid = bundle(snapshot);
            Map<String, Object> forgedManifest = new LinkedHashMap<>(valid.manifest());
            forgedManifest.put("artifacts", List.of());
            byte[] forgedBytes = ReleaseManifest.toJson(forgedManifest)
                    .getBytes(StandardCharsets.UTF_8);
            List<ReleaseBundleService.Artifact> artifacts = new ArrayList<>(
                    valid.artifacts().subList(0, valid.artifacts().size() - 1));
            artifacts.add(new ReleaseBundleService.Artifact("manifest.json", "application/json",
                    forgedBytes, io.github.hakjuoh.protege_mcp.core.release.ArtifactStore
                            .sha256(forgedBytes), forgedBytes.length));
            ReleaseBundleService.Bundle forged = new ReleaseBundleService.Bundle(
                    artifacts, forgedManifest);

            assertThrows(IOException.class, () -> transaction.stage(forged));
            assertFalse(Files.exists(temp.resolve("dist")));
        }
    }

    private FilesystemProjectWorkspace workspace() throws IOException {
        writeFixture();
        return new FilesystemProjectWorkspace(temp.resolve("project.yaml"),
                temp.resolve("state"), () -> { });
    }

    private static ReleaseBundleService.Bundle bundle(WorkspaceSnapshot snapshot) {
        String fingerprint = snapshot.fingerprint().semanticFingerprint();
        StageResult stage = new StageResult("release", StageStatus.PASS, null, List.of(), Map.of());
        GateResult gate = GateResult.aggregate(1, fingerprint, List.of("release"), List.of(stage),
                FindingSeverity.ERROR);
        return ReleaseBundleService.build(new ReleaseBundleService.Request("bundle-test", ROOT_IRI,
                ROOT_IRI + "/1.0.0", "2026-07-19T12:00:00Z",
                "https://www.apache.org/licenses/LICENSE-2.0", snapshot.policy().digest(), null,
                fingerprint, true, gate, "ontology".getBytes(), "ontology.ttl", "text/turtle",
                null, null, 50));
    }

    private void writeFixture() throws IOException {
        Files.writeString(temp.resolve("root.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + ROOT_IRI + "> a owl:Ontology .\n");
        writeCrate();
        Files.writeString(temp.resolve("project.yaml"), "version: 1\n"
                + "project_id: bundle-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: root.ttl\n"
                + "  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.1}\n"
                + "  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}\n"
                + "validation:\n"
                + "  required_stages: [interoperability]\n");
    }

    private void writeCrate() throws IOException {
        String profile = "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
        List<Object> graph = new ArrayList<>();
        graph.add(entity("ro-crate-metadata.json", "CreativeWork", "about", ref("./"),
                "conformsTo", ref("https://w3id.org/ro/crate/1.1")));
        graph.add(entity("./", "Dataset", "name", "Bundle test", "description",
                "Bundle transaction test", "datePublished", "2026-07-19", "license",
                "https://www.apache.org/licenses/LICENSE-2.0", "identifier", "bundle-test",
                "conformsTo", List.of(ref(profile)), "mainEntity", ref("root.ttl"),
                "hasPart", ref("root.ttl")));
        graph.add(entity(profile, "CreativeWork", "name", "Project profile"));
        graph.add(entity("root.ttl", "File", "encodingFormat", "text/turtle",
                "about", ref(ROOT_IRI)));
        graph.add(entity(ROOT_IRI, "Dataset", "conformsTo",
                ref("https://www.w3.org/TR/owl2-overview/")));
        new ObjectMapper().writeValue(temp.resolve("ro-crate-metadata.json").toFile(),
                Map.of("@context", "https://w3id.org/ro/crate/1.1/context", "@graph", graph));
    }

    private static Map<String, Object> entity(String id, Object type, Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("@id", id);
        result.put("@type", type);
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static Map<String, String> ref(String id) {
        return Map.of("@id", id);
    }
}
