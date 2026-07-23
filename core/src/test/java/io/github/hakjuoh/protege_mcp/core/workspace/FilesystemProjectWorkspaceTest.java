package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationInputDigests;

class FilesystemProjectWorkspaceTest {

    private static final String ROOT_IRI = "https://example.org/root";
    private static final String IMPORT_IRI = "https://example.org/import";

    @TempDir
    Path temp;

    @Test
    void capturesModuleMappedClosureWithOriginalDocumentSemanticsAndDetectsDrift() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        Path imported = writeImport("Imported");
        Path importedReal = imported.toRealPath();
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);
        Path capturedPath;

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            assertEquals(2, snapshot.closure().size());
            assertEquals(1, snapshot.revision().sessionRevision());
            assertTrue(snapshot.revision().semanticFingerprint().startsWith("sha256:"));
            assertTrue(snapshot.closureFingerprint().startsWith("sha256:"));
            assertTrue(workspace.isCurrent(snapshot));
            assertTrue(snapshot.root().containsClassInSignature(
                    IRI.create(root.toRealPath().toUri() + "#Local")),
                    () -> "relative entity IRIs must resolve against the original document IRI, not temp: "
                            + snapshot.root().getClassesInSignature());
            capturedPath = snapshot.sources().stream()
                    .filter(source -> source.original().equals(importedReal))
                    .findFirst().orElseThrow().captured();
            assertTrue(Files.isRegularFile(capturedPath));
            assertTrue(snapshot.capturedAssets().containsKey("interoperability_manifest"));

            Files.writeString(imported, importTurtle("Changed"));
            assertFalse(workspace.isCurrent(snapshot));
            assertEquals(2, snapshot.closure().size(), "captured OWL state remains immutable");
        }
        assertFalse(Files.exists(capturedPath), "closing a snapshot removes its private temp tree");
    }

    @Test
    void versionTwoCaptureDoesNotTreatAnAbsentMappingOutputAsAnInputAsset() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Files.writeString(policy, Files.readString(policy).replace("version: 1", "version: 2"));
        assertFalse(Files.exists(temp.resolve(".protege-mcp/mappings.sssom.tsv")));

        try (WorkspaceSnapshot snapshot = new FilesystemProjectWorkspace(policy).capture()) {
            assertEquals(2, snapshot.closure().size());
            assertFalse(snapshot.capturedAssets().containsKey("mapping_store"));
        }
    }

    @Test
    void versionTwoCaptureKeepsPresentMappingStoreOutOfGenericOntologySnapshot()
            throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Files.writeString(policy, Files.readString(policy).replace("version: 1", "version: 2"));
        Path mappings = policy.getParent().resolve(".protege-mcp/mappings.sssom.tsv");
        Files.createDirectories(mappings.getParent());
        Files.writeString(mappings, "subject_id\tpredicate_id\tobject_id\n");

        try (WorkspaceSnapshot snapshot = new FilesystemProjectWorkspace(policy).capture()) {
            assertEquals(List.of(mappings.toRealPath()),
                    snapshot.policy().assets().get("mapping_store"));
            assertEquals(ArtifactStore.sha256(mappings),
                    MaterializationInputDigests.mappingRevision(
                            snapshot.policy(), 1_048_576));
            assertFalse(snapshot.capturedAssets().containsKey("mapping_store"),
                    "SSSOM state is guarded by mapping_revision only when a mapping-aware flow uses it");
        }
    }

    @Test
    void resolvesNestedLocalCatalogButNeverFallsThroughToNetwork() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Files.createDirectories(temp.resolve("catalogs"));
        Files.writeString(temp.resolve("catalog-v001.xml"), catalog(
                "<nextCatalog catalog=\"catalogs/nested.xml\"/>"));
        Files.writeString(temp.resolve("catalogs/nested.xml"), catalog(
                "<uri name=\"" + IMPORT_IRI + "\" uri=\"../import.ttl\"/>"));
        Path policy = writePolicy("");

        try (WorkspaceSnapshot snapshot = new FilesystemProjectWorkspace(policy).capture()) {
            assertEquals(2, snapshot.closure().size());
            assertEquals(2, snapshot.sources().stream()
                    .filter(source -> "catalog".equals(source.kind())).count());
        }

        writeRoot("http://127.0.0.1:9/must-not-connect");
        IOException refusal = assertThrows(IOException.class,
                () -> new FilesystemProjectWorkspace(policy).capture());
        assertTrue(refusal.getMessage().contains("no local mapping"), refusal::getMessage);
    }

    @Test
    void verifiesLockedClosureChecksumMembershipAndDirectness() throws Exception {
        writeRoot(IMPORT_IRI);
        Path imported = writeImport("Imported");
        writeLock(imported, true);
        Path policy = writePolicy("imports:\n"
                + "  mode: locked\n"
                + "  lockfile: imports.lock.json\n");

        try (WorkspaceSnapshot snapshot = new FilesystemProjectWorkspace(policy).capture()) {
            assertEquals(2, snapshot.closure().size());
            assertTrue(snapshot.revision().documentFingerprint().startsWith("sha256:"));
        }

        Files.writeString(imported, importTurtle("Tampered"));
        IOException mismatch = assertThrows(IOException.class,
                () -> new FilesystemProjectWorkspace(policy).capture());
        assertTrue(mismatch.getMessage().contains("checksum mismatch"), mismatch::getMessage);

        writeImport("Imported");
        writeLock(imported, false);
        IOException direct = assertThrows(IOException.class,
                () -> new FilesystemProjectWorkspace(policy).capture());
        assertTrue(direct.getMessage().contains("lock mismatch"), direct::getMessage);
    }

    @Test
    void rejectsCatalogSymlinkEscapesBeforeReadingTheTarget() throws Exception {
        writeRoot(IMPORT_IRI);
        Path outside = Files.createTempFile("protege-mcp-outside-", ".ttl");
        try {
            Files.writeString(outside, importTurtle("Outside"));
            Files.createSymbolicLink(temp.resolve("escape.ttl"), outside);
            Files.writeString(temp.resolve("catalog-v001.xml"), catalog(
                    "<uri name=\"" + IMPORT_IRI + "\" uri=\"escape.ttl\"/>"));
            Path policy = writePolicy("");

            IOException refusal = assertThrows(IOException.class,
                    () -> new FilesystemProjectWorkspace(policy).capture());
            assertTrue(refusal.getMessage().contains("escapes project root"), refusal::getMessage);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsAConcurrentSourceSwapBeforePublishingTheSnapshot() throws Exception {
        writeRoot(IMPORT_IRI);
        Path imported = writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy,
                () -> Files.writeString(imported, importTurtle("Raced")));

        IOException changed = assertThrows(IOException.class, workspace::capture);

        assertTrue(changed.getMessage().contains("changed during capture"), changed::getMessage);
    }

    @Test
    void detectsCapturedMemoryAndDirectoryMembershipTampering() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path cqs = Files.createDirectories(temp.resolve("cqs"));
        Files.writeString(cqs.resolve("ask.rq"), "ASK { ?s ?p ?o }\n");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n"
                + "validation:\n"
                + "  required_stages: [structural]\n"
                + "  competency_questions:\n"
                + "    convention: robot-sparql-dir\n"
                + "    path: cqs\n");
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            WorkspaceSource capturedRoot = snapshot.sources().stream()
                    .filter(source -> "root_ontology".equals(source.kind()))
                    .findFirst().orElseThrow();
            Files.writeString(capturedRoot.captured(), "tampered\n");
            assertFalse(workspace.isCurrent(snapshot),
                    "private captured bytes are part of snapshot currency");
        }

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            var dataFactory = snapshot.root().getOWLOntologyManager().getOWLDataFactory();
            snapshot.root().getOWLOntologyManager().addAxiom(snapshot.root(),
                    dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(
                            IRI.create(ROOT_IRI + "#Injected"))));
            assertFalse(workspace.isCurrent(snapshot),
                    "in-memory changes must invalidate the pinned model revision");
        }

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            assertTrue(workspace.isCurrent(snapshot));
            Files.writeString(cqs.resolve("added.rq"), "ASK { FILTER(false) }\n");
            assertFalse(workspace.isCurrent(snapshot),
                    "adding an asset after capture must change its directory identity");
            assertEquals(3, snapshot.revision().sessionRevision());
        }
    }

    @Test
    void compatibilityLoaderRejectsPreviouslyCapturedBytesAfterSourceReplacement()
            throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        byte[] captured = Files.readAllBytes(policy);
        Files.writeString(policy, "not: [the, captured, policy]\n");

        ProjectPolicy loaded = ProjectPolicyLoader.loadCaptured(
                policy.toRealPath(), captured, null, null, true);

        assertFalse(loaded.valid());
        assertTrue(loaded.issues().stream()
                .anyMatch(issue -> "policy_changed_during_read".equals(issue.code())));
    }

    @Test
    void workspaceRejectsFinalAndEscapingDirectoryPolicySymlinks() throws Exception {
        Path realPolicy = writePolicy("");
        Path finalLink = temp.resolve("linked-policy.yaml");
        try {
            Files.createSymbolicLink(finalLink, realPolicy);
        } catch (UnsupportedOperationException | IOException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "symbolic links are unavailable: " + unsupported);
        }
        IOException finalRefusal = assertThrows(IOException.class,
                () -> new FilesystemProjectWorkspace(finalLink).capture());
        assertTrue(finalRefusal.getMessage().contains("secure path validation"),
                finalRefusal::getMessage);

        Path outside = Files.createTempDirectory("workspace-policy-outside-");
        try {
            Files.writeString(outside.resolve("project.yaml"), "version: 1\n");
            Path linkedDirectory = temp.resolve(".protege-mcp");
            Files.createSymbolicLink(linkedDirectory, outside);
            IOException directoryRefusal = assertThrows(IOException.class,
                    () -> new FilesystemProjectWorkspace(
                            linkedDirectory.resolve("project.yaml")).capture());
            assertTrue(directoryRefusal.getMessage().contains("secure path validation"),
                    directoryRefusal::getMessage);
        } finally {
            Files.deleteIfExists(outside.resolve("project.yaml"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void workspaceRejectsPolicyDirectorySwapAfterSecureDiscovery() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path originalPolicy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Path policyDirectory = temp.resolve("project");
        Files.createDirectory(policyDirectory);
        for (String file : List.of("project.yaml", "root.ttl", "import.ttl",
                "ro-crate-metadata.json")) {
            Files.move(temp.resolve(file), policyDirectory.resolve(file));
        }
        Path policy = policyDirectory.resolve(originalPolicy.getFileName());
        Path savedDirectory = temp.resolve("project-before-swap");
        Path outside = Files.createTempDirectory("workspace-policy-race-");
        Files.writeString(outside.resolve("project.yaml"), Files.readString(policy));

        try {
            FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy, () -> {
                Files.move(policyDirectory, savedDirectory);
                Files.createSymbolicLink(policyDirectory, outside);
            }, () -> { });

            IOException refusal = assertThrows(IOException.class, workspace::capture);
            assertTrue(refusal.getMessage().contains("changed after secure discovery"),
                    refusal::getMessage);
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "symbolic links are unavailable: " + unsupported);
        } finally {
            if (Files.isSymbolicLink(policyDirectory)) Files.deleteIfExists(policyDirectory);
            if (Files.exists(savedDirectory)) Files.move(savedDirectory, policyDirectory);
            Files.deleteIfExists(outside.resolve("project.yaml"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void workspaceRejectsSamePathOrdinaryProjectDirectoryReplacement() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path originalPolicy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Path project = temp.resolve("project");
        Files.createDirectory(project);
        for (String file : List.of("project.yaml", "root.ttl", "import.ttl",
                "ro-crate-metadata.json")) {
            Files.move(temp.resolve(file), project.resolve(file));
        }
        Path policy = project.resolve(originalPolicy.getFileName());
        Path saved = temp.resolve("project-before-replacement");

        try {
            FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy, () -> {
                Files.move(project, saved);
                Files.createDirectory(project);
                Files.createLink(policy, saved.resolve("project.yaml"));
            }, () -> { });

            IOException refusal = assertThrows(IOException.class, workspace::capture);
            assertTrue(refusal.getMessage().contains("identity changed after secure discovery"),
                    refusal::getMessage);
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "hard links are unavailable: " + unsupported);
        }
    }

    @Test
    void workspaceRejectsSamePathPolicyFileReplacement() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("");
        Path savedPolicy = policy.resolveSibling("project-before-replacement.yaml");
        var originalTime = Files.getLastModifiedTime(policy);
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy, () -> {
            Files.move(policy, savedPolicy);
            Files.copy(savedPolicy, policy);
            Files.setLastModifiedTime(policy, originalTime);
        }, () -> { });

        IOException refusal = assertThrows(IOException.class, workspace::capture);
        assertTrue(refusal.getMessage().contains("identity changed after secure discovery"),
                refusal::getMessage);
    }

    @Test
    void workspaceFinalPinRejectsHardlinkedReplacementTreeAfterAllAssetReads()
            throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path originalPolicy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Path project = temp.resolve("project");
        Files.createDirectory(project);
        for (String file : List.of("project.yaml", "root.ttl", "import.ttl",
                "ro-crate-metadata.json")) {
            Files.move(temp.resolve(file), project.resolve(file));
        }
        Path policy = project.resolve(originalPolicy.getFileName());
        Path saved = temp.resolve("project-before-final-replacement");

        try {
            FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(
                    policy, () -> { }, () -> {
                        Files.move(project, saved);
                        mirrorWithHardLinks(saved, project);
                    });

            IOException refusal = assertThrows(IOException.class, workspace::capture);
            assertTrue(refusal.getMessage().contains("before snapshot publication"),
                    refusal::getMessage);
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "hard links are unavailable: " + unsupported);
        }
    }

    @Test
    void publishedSnapshotRetainsProjectAnchorIdentity() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path originalPolicy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Path project = temp.resolve("project");
        Files.createDirectory(project);
        for (String file : List.of("project.yaml", "root.ttl", "import.ttl",
                "ro-crate-metadata.json")) {
            Files.move(temp.resolve(file), project.resolve(file));
        }
        Path policy = project.resolve(originalPolicy.getFileName());
        Path saved = temp.resolve("project-before-published-replacement");
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            Files.move(project, saved);
            mirrorWithHardLinks(saved, project);
            assertFalse(workspace.isCurrent(snapshot),
                    "content-identical hardlinks cannot replace the pinned project anchor");
            IOException transactionRefusal = assertThrows(IOException.class,
                    () -> workspace.beginTransaction(snapshot, project.resolve("root.ttl"), false));
            assertTrue(transactionRefusal.getMessage().contains(
                    "sources changed before transaction creation"), transactionRefusal::getMessage);
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "hard links are unavailable: " + unsupported);
        }
    }

    @Test
    void recoveryRefusesHardlinkedProjectReplacementAfterCommit() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path originalPolicy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Path project = temp.resolve("project");
        Files.createDirectory(project);
        for (String file : List.of("project.yaml", "root.ttl", "import.ttl",
                "ro-crate-metadata.json")) {
            Files.move(temp.resolve(file), project.resolve(file));
        }
        Path policy = project.resolve(originalPolicy.getFileName());
        Path root = project.resolve("root.ttl");
        Path saved = temp.resolve("project-before-recovery-replacement");
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(
                policy, temp.resolve("state"), () -> { });
        byte[] replacement = (Files.readString(root)
                + "<" + ROOT_IRI + "#Committed> a <http://www.w3.org/2002/07/owl#Class> .\n")
                .getBytes(StandardCharsets.UTF_8);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(
                        snapshot, root, true)) {
            transaction.stageBytes(replacement);
            transaction.commit();
            Files.move(project, saved);
            mirrorWithHardLinks(saved, project);

            IOException refusal = assertThrows(IOException.class, transaction::recover);
            assertTrue(refusal.getMessage().contains("project policy identity changed"),
                    refusal::getMessage);
            assertEquals(WorkspaceTransaction.State.COMMITTED, transaction.state());
            assertEquals(ArtifactStore.sha256(replacement), ArtifactStore.sha256(root));
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "hard links are unavailable: " + unsupported);
        }
    }

    @Test
    void transactionCreationRefusesProjectRootReplacementBeforeAnchorOpen() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path originalPolicy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Path project = temp.resolve("project");
        Files.createDirectory(project);
        for (String file : List.of("project.yaml", "root.ttl", "import.ttl",
                "ro-crate-metadata.json")) {
            Files.move(temp.resolve(file), project.resolve(file));
        }
        Path policy = project.resolve(originalPolicy.getFileName());
        Path root = project.resolve("root.ttl");
        Path saved = temp.resolve("project-before-anchor-open");
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(
                policy, temp.resolve("state"), () -> { });

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            IOException refusal = assertThrows(IOException.class,
                    () -> new WorkspaceTransaction(workspace, snapshot, root, true,
                            WorkspaceTransaction.MAX_STAGED_BYTES, () -> {
                                Files.move(project, saved);
                                mirrorWithHardLinks(saved, project);
                            }, () -> { }, WorkspaceTransactionTestMoves::atomic));

            assertTrue(refusal.getMessage().contains(
                    "sources changed while transaction was pinned"), refusal::getMessage);
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "hard links are unavailable: " + unsupported);
        }
    }

    @Test
    void recoveryCleanupDoesNotFollowAnAnchorReplacedInsideTheFinalMove()
            throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path originalPolicy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        Path project = temp.resolve("project");
        Files.createDirectory(project);
        for (String file : List.of("project.yaml", "root.ttl", "import.ttl",
                "ro-crate-metadata.json")) {
            Files.move(temp.resolve(file), project.resolve(file));
        }
        Path policy = project.resolve(originalPolicy.getFileName());
        Path root = project.resolve("root.ttl");
        Path saved = temp.resolve("project-replaced-inside-recovery");
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(
                policy, temp.resolve("state"), () -> { });
        byte[] replacement = (Files.readString(root)
                + "<" + ROOT_IRI + "#Committed> a <http://www.w3.org/2002/07/owl#Class> .\n")
                .getBytes(StandardCharsets.UTF_8);
        AtomicInteger moves = new AtomicInteger();

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(
                        workspace, snapshot, root, true, () -> { }, (source, target) -> {
                            if (moves.incrementAndGet() == 3) {
                                Files.move(project, saved);
                                mirrorWithHardLinks(saved, project);
                                throw new IOException("injected replacement inside recovery move");
                            }
                            Files.move(source, target,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        })) {
            transaction.stageBytes(replacement);
            transaction.commit();

            IOException refusal = assertThrows(IOException.class, transaction::recover);

            assertTrue(refusal.getMessage().contains("injected replacement"),
                    refusal::getMessage);
            assertEquals(WorkspaceTransaction.State.COMMITTED, transaction.state());
            assertEquals(ArtifactStore.sha256(replacement), ArtifactStore.sha256(root));
            try (var entries = Files.list(project)) {
                assertTrue(entries.anyMatch(path -> path.getFileName().toString()
                        .startsWith(".root.ttl.protege-mcp-recovery-")),
                        "identity loss must leave the recovery temp for manual handling");
            }
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "hard links are unavailable: " + unsupported);
        }
    }

    @Test
    void rejectsSymlinksInsideCapturedAssetDirectories() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path cqs = Files.createDirectories(temp.resolve("cqs"));
        Path queries = Files.createDirectories(cqs.resolve("queries"));
        Files.writeString(queries.resolve("ask.rq"), "ASK { ?s ?p ?o }\n");
        Files.createSymbolicLink(cqs.resolve("alias"), Path.of("queries"));
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n"
                + "validation:\n"
                + "  required_stages: [structural]\n"
                + "  competency_questions:\n"
                + "    convention: robot-sparql-dir\n"
                + "    path: cqs\n");

        IOException refusal = assertThrows(IOException.class,
                () -> new FilesystemProjectWorkspace(policy).capture());

        assertTrue(refusal.getMessage().contains("contains a symbolic link"), refusal::getMessage);
    }

    @Test
    void atomicallyCommitsBacksUpAndRecoversTheExactBaseline() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        Set<PosixFilePermission> originalPermissions =
                PosixFilePermissions.fromString("rw-r-----");
        try {
            Files.setPosixFilePermissions(root, originalPermissions);
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "POSIX permissions are unavailable: " + unsupported);
        }
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        byte[] original = Files.readAllBytes(root);
        byte[] replacement = (Files.readString(root)
                + "<" + ROOT_IRI + "#Committed> a <http://www.w3.org/2002/07/owl#Class> .\n")
                .getBytes(StandardCharsets.UTF_8);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(snapshot, root, true)) {
            WorkspaceTransaction.Stage stage = transaction.stageBytes(replacement);
            assertEquals(ArtifactStore.sha256(replacement), stage.sha256());

            WorkspaceTransaction.Commit commit = transaction.commit();

            assertEquals(WorkspaceTransaction.State.COMMITTED, transaction.state());
            assertEquals(ArtifactStore.sha256(original), commit.previousSha256());
            assertEquals(ArtifactStore.sha256(replacement), commit.installedSha256());
            assertEquals(replacement.length, commit.installedBytes());
            assertEquals(new String(replacement, StandardCharsets.UTF_8), Files.readString(root));
            assertEquals(originalPermissions, Files.getPosixFilePermissions(root));
            assertEquals(new String(original, StandardCharsets.UTF_8),
                    Files.readString(commit.backupPath()));
            assertTrue(commit.backupPath().getFileName().toString()
                    .startsWith(".protege-mcp-backup-"));
            assertFalse(workspace.isCurrent(snapshot));

            WorkspaceTransaction.Recovery recovery = transaction.recover();

            assertTrue(recovery.restored());
            assertEquals(ArtifactStore.sha256(original), recovery.restoredSha256());
            assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(root));
            assertEquals(originalPermissions, Files.getPosixFilePermissions(root));
            assertTrue(workspace.isCurrent(snapshot));
        }
    }

    @Test
    void refusesSourceAndStagedByteDriftBeforeReplacement() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        Path imported = writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        byte[] original = Files.readAllBytes(root);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(workspace, snapshot, root,
                        true, () -> Files.writeString(imported, importTurtle("Raced")),
                        WorkspaceTransactionTestMoves::atomic)) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));

            IOException changed = assertThrows(IOException.class, transaction::commit);

            assertTrue(changed.getMessage().contains("source checksum changed"), changed::getMessage);
            assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(root));
            assertFalse(Files.exists(root.resolveSibling(root.getFileName() + ".bak")));
        }

        writeImport("Imported");
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(snapshot, root, false)) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));
            Files.writeString(transaction.stagedPath(), "tampered\n");

            IOException changed = assertThrows(IOException.class, transaction::commit);

            assertTrue(changed.getMessage().contains("staged artifact changed"), changed::getMessage);
            assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(root));
        }
    }

    @Test
    void refusesConcurrentChangesToTrackedAndNewTargetsOutsideTheSnapshotSources() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path existing = temp.resolve("notes.txt");
        Files.writeString(existing, "baseline\n");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(workspace, snapshot,
                        existing, false, () -> Files.writeString(existing, "raced\n"),
                        WorkspaceTransactionTestMoves::atomic)) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));

            IOException changed = assertThrows(IOException.class, transaction::commit);

            assertTrue(changed.getMessage().contains("target checksum changed"), changed::getMessage);
            assertEquals("raced\n", Files.readString(existing));
        }

        Path created = temp.resolve("concurrent.txt");
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(workspace, snapshot,
                        created, false, () -> Files.writeString(created, "other writer\n"),
                        WorkspaceTransactionTestMoves::atomic)) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));

            IOException changed = assertThrows(IOException.class, transaction::commit);

            assertTrue(changed.getMessage().contains("created concurrently"), changed::getMessage);
            assertEquals("other writer\n", Files.readString(created));
        }
    }

    @Test
    void capturedTargetBaselineGuardsReadOnlyNoopDecisions() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path target = temp.resolve("materialized.ofn");
        Files.writeString(target, "baseline\n");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(
                        snapshot, target, true)) {
            assertTrue(transaction.targetExisted());
            assertEquals(ArtifactStore.sha256(Files.readAllBytes(target)),
                    transaction.baselineSha256());
            Files.writeString(target, "swapped\n");

            IOException changed = assertThrows(IOException.class,
                    transaction::verifyBaseline);

            assertTrue(changed.getMessage().contains("target checksum changed"),
                    changed::getMessage);
            assertEquals("swapped\n", Files.readString(target));
        }
    }

    @Test
    void secureTargetSnapshotRejectsAPostOpenSymlinkSwap() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path target = temp.resolve("materialized.ofn");
        Files.writeString(target, "baseline\n");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        String sourceDigest = ArtifactStore.sha256(Files.readAllBytes(root));

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(
                        snapshot, target, true)) {
            Files.delete(target);
            try {
                Files.createSymbolicLink(target, root.getFileName());
            } catch (UnsupportedOperationException | IOException unsupported) {
                org.junit.jupiter.api.Assumptions.abort(
                        "symbolic links are unavailable: " + unsupported);
            }

            IOException changed = assertThrows(IOException.class,
                    () -> transaction.snapshotTarget(1_024));

            assertTrue(changed.getMessage().contains("regular file")
                    || changed.getMessage().contains("changed"), changed::getMessage);
            assertEquals(sourceDigest, ArtifactStore.sha256(Files.readAllBytes(root)));
        }
    }

    @Test
    void anchoredIdentityRejectsASymlinkSwapImmediatelyBeforeOpen() throws Exception {
        Path target = temp.resolve("mappings.tsv");
        Path outside = temp.resolve("outside.tsv");
        Files.writeString(target, "safe\n");
        Files.writeString(outside, "outside\n");
        String outsideDigest = ArtifactStore.sha256(outside);

        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            IOException refusal = assertThrows(IOException.class,
                    () -> anchor.identity(anchor.targetName(), 1_024, () -> {
                        Files.delete(target);
                        Files.createSymbolicLink(target, outside.getFileName());
                    }));

            assertTrue(refusal.getMessage().contains("symbolic link")
                    || refusal.getMessage().contains("changed"), refusal::getMessage);
            assertEquals(outsideDigest, ArtifactStore.sha256(outside));
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "symbolic links are unavailable: " + unsupported);
        }
    }

    @Test
    void secureAnchorRejectsAReplacedTargetDirectory() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path artifacts = Files.createDirectory(temp.resolve("artifacts"));
        Path target = artifacts.resolve("materialized.ofn");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(
                        snapshot, target, true)) {
            Path moved = temp.resolve("artifacts-moved");
            Files.move(artifacts, moved);
            Files.createDirectory(artifacts);

            IOException changed = assertThrows(IOException.class,
                    () -> transaction.stageBytes("candidate\n"
                            .getBytes(StandardCharsets.UTF_8)));

            assertTrue(changed.getMessage().contains("directory changed")
                    || changed.getMessage().contains("directory chain changed"),
                    changed::getMessage);
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(moved.resolve("materialized.ofn")));
        }
    }

    @Test
    void secureAnchorRejectsAReplacedWholeProjectRoot() throws Exception {
        Path target = temp.resolve("materialized.ofn");
        Path moved = temp.resolveSibling(temp.getFileName() + "-moved");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            Files.move(temp, moved);
            Files.createDirectory(temp);

            IOException changed = assertThrows(IOException.class,
                    () -> anchor.createSibling("materialized.ofn", "stage"));

            assertTrue(changed.getMessage().contains("directory chain changed"),
                    changed::getMessage);
            try (var entries = Files.list(moved)) {
                assertTrue(entries.findAny().isEmpty());
            }
        } finally {
            if (Files.exists(moved)) {
                if (Files.exists(temp)) Files.delete(temp);
                Files.move(moved, temp);
            }
        }
    }

    @Test
    void pathFallbackRejectsASharedWritableDirectory() throws Exception {
        Path shared = Files.createDirectory(temp.resolve("shared"));
        try {
            Files.setPosixFilePermissions(shared,
                    PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "POSIX permissions are unavailable: " + unsupported);
        }

        IOException refused = assertThrows(IOException.class,
                () -> SecureTargetAnchor.openFallbackForTest(
                        temp, shared.resolve("target.ofn")));

        assertTrue(refused.getMessage().contains("shared-writable"), refused::getMessage);
    }

    @Test
    void pathFallbackCompletesAnchoredCreateHashMoveAndDelete() throws Exception {
        Path fallbackRoot = privateFallbackRoot();
        Path directory = Files.createDirectory(fallbackRoot.resolve("private-fallback"));
        try {
            Files.setPosixFilePermissions(directory,
                    PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "POSIX permissions are unavailable: " + unsupported);
        }
        Path target = directory.resolve("target.ofn");
        byte[] content = "fallback candidate\n".getBytes(StandardCharsets.UTF_8);

        try (SecureTargetAnchor anchor = SecureTargetAnchor.openFallbackForTest(
                fallbackRoot, target)) {
            Path stagedName;
            try (SecureTargetAnchor.CreatedFile created =
                    anchor.createSibling("target.ofn", "stage")) {
                created.channel().write(ByteBuffer.wrap(content));
                created.force();
                assertEquals(ArtifactStore.sha256(content),
                        anchor.identity(created.name(), 1_024).sha256());
                stagedName = created.name();
            }
            anchor.move(stagedName, anchor.targetName());
            assertEquals(ArtifactStore.sha256(content),
                    anchor.identity(anchor.targetName(), 1_024).sha256());
            anchor.deleteIfExists(anchor.targetName());
            assertFalse(anchor.exists(anchor.targetName()));
        } finally {
            Files.deleteIfExists(directory);
            Files.deleteIfExists(fallbackRoot);
        }
    }

    @Test
    void pathFallbackRefusesAParentReplacementAfterAuthorization() throws Exception {
        Path fallbackRoot = privateFallbackRoot();
        Path directory = Files.createDirectory(fallbackRoot.resolve("fallback-parent"));
        Path moved = fallbackRoot.resolve("fallback-parent-moved");
        try {
            Files.setPosixFilePermissions(directory,
                    PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort(
                    "POSIX permissions are unavailable: " + unsupported);
        }

        try (SecureTargetAnchor anchor = SecureTargetAnchor.openFallbackForTest(
                fallbackRoot, directory.resolve("target.ofn"))) {
            Files.move(directory, moved);
            Files.createDirectory(directory);
            IOException refusal = assertThrows(IOException.class,
                    () -> anchor.createSibling("target.ofn", "stage"));
            assertTrue(refusal.getMessage().contains("directory chain changed"),
                    refusal::getMessage);
            try (var entries = Files.list(moved)) {
                assertTrue(entries.findAny().isEmpty());
            }
        } finally {
            Files.deleteIfExists(directory);
            Files.deleteIfExists(moved);
            Files.deleteIfExists(fallbackRoot);
        }
    }

    @Test
    void anchoredDeleteReportsAppliedMutationWhenTheRootDetachesAfterDelete()
            throws Exception {
        Path target = temp.resolve("delete-me.ofn");
        Path moved = temp.resolveSibling(temp.getFileName() + "-delete-moved");
        Files.writeString(target, "delete\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            SecureTargetAnchor.MutationAppliedException applied = assertThrows(
                    SecureTargetAnchor.MutationAppliedException.class,
                    () -> anchor.deleteIfExists(anchor.targetName(), () -> {
                        Files.move(temp, moved);
                        Files.createDirectory(temp);
                    }));
            assertTrue(applied.getMessage().contains("mutation completed"));
            assertFalse(Files.exists(moved.resolve("delete-me.ofn")));
        } finally {
            if (Files.exists(moved)) {
                if (Files.exists(temp)) Files.delete(temp);
                Files.move(moved, temp);
            }
        }
    }

    @Test
    void recoveryQuarantineNeverDeletesAReplacementRacingTheIdentityCheck()
            throws Exception {
        Path target = temp.resolve("generated.json");
        Files.writeString(target, "installed\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            FilesystemProjectWorkspace.FileIdentity installed =
                    anchor.identity(anchor.targetName(), 1_024);

            IOException refused = assertThrows(IOException.class,
                    () -> anchor.quarantineIfIdentity(anchor.targetName(), installed, 1_024,
                            () -> Files.writeString(target, "racing replacement\n"),
                            () -> { }));

            assertTrue(refused.getMessage().contains("target changed"), refused::getMessage);
            assertEquals("racing replacement\n", Files.readString(target));
        }
    }

    @Test
    void recoveryQuarantineReportsAReplacementCreatedAfterTheAtomicMove()
            throws Exception {
        Path target = temp.resolve("generated-after.json");
        Files.writeString(target, "installed\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            FilesystemProjectWorkspace.FileIdentity installed =
                    anchor.identity(anchor.targetName(), 1_024);

            SecureTargetAnchor.GuardedReplaceException applied = assertThrows(
                    SecureTargetAnchor.GuardedReplaceException.class,
                    () -> anchor.quarantineIfIdentity(anchor.targetName(), installed, 1_024,
                            () -> { },
                            () -> Files.writeString(target, "racing replacement\n")));

            assertEquals("racing replacement\n", Files.readString(target));
            assertTrue(Files.isRegularFile(applied.receipt().displacedPath()));
            assertEquals(installed.sha256(), applied.receipt().displacedSha256());
            assertTrue(applied.receipt().targetStateKnown());
            assertTrue(applied.receipt().targetPresent());
            assertFalse(applied.receipt().publicationApplied());
        }
    }

    @Test
    void lockedRecoveryRestoresAnOrphanDisplacedByASimulatedProcessCrash()
            throws Exception {
        Path target = temp.resolve("crash-recovery.ofn");
        byte[] baseline = "baseline\n".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, baseline);
        Path stagedName;
        FilesystemProjectWorkspace.FileIdentity stagedIdentity;
        FilesystemProjectWorkspace.FileIdentity baselineIdentity;

        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            baselineIdentity = anchor.identity(anchor.targetName(), 1_024);
            try (SecureTargetAnchor.CreatedFile staged =
                    anchor.createSibling("crash-recovery.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap(replacement));
                staged.force();
                stagedName = staged.name();
            }
            stagedIdentity = anchor.identity(stagedName, 1_024);

            assertThrows(SimulatedProcessCrash.class,
                    () -> anchor.replaceGuarded(stagedName, stagedIdentity,
                            anchor.targetName(), baselineIdentity, 1_024,
                            () -> { }, () -> {
                                throw new SimulatedProcessCrash();
                            }));
        }
        assertFalse(Files.exists(target));

        try (SecureTargetAnchor ignored = SecureTargetAnchor.open(temp, target)) {
            assertFalse(Files.exists(target),
                    "read-only anchor opening must not perform orphan recovery");
        }

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    temp.resolve("recovery-state"), temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            SecureTargetAnchor.RecoverySweepReceipt receipt =
                    anchor.recoverOrphanedTransactionsUnderLock(lock, lock.lockPath());
            assertTrue(receipt.mutationApplied());
            assertTrue(receipt.targetRestored());
            assertEquals(baselineIdentity.sha256(), ArtifactStore.sha256(target));
        }
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".crash-recovery.ofn.protege-mcp-transaction-")));
        }
    }

    @Test
    void recoveryRequiresTheMatchingProjectLock() throws Exception {
        Path target = temp.resolve("locked-recovery.ofn");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            IOException refusal = assertThrows(IOException.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(
                            null, temp.resolve("missing.lock")));
            assertTrue(refusal.getMessage().contains("matching project lock"),
                    refusal::getMessage);
        }
    }

    @Test
    void transactionBeginReportsRecoveryBeforeApplyingTheCallerSizeBound()
            throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        Path target = temp.resolve("recovered-large.ofn");
        byte[] baselineBytes = "b".repeat(2_048).getBytes(StandardCharsets.UTF_8);
        Files.write(target, baselineBytes);

        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            FilesystemProjectWorkspace.FileIdentity baseline = anchor.identity(
                    anchor.targetName(), 4_096);
            Path stagedName;
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    "recovered-large.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap(
                        "replacement\n".getBytes(StandardCharsets.UTF_8)));
                staged.force();
                stagedName = staged.name();
            }
            FilesystemProjectWorkspace.FileIdentity stagedIdentity = anchor.identity(
                    stagedName, 4_096);
            assertThrows(SimulatedProcessCrash.class,
                    () -> anchor.replaceGuarded(stagedName, stagedIdentity,
                            anchor.targetName(), baseline, 4_096,
                            () -> { }, () -> { throw new SimulatedProcessCrash(); }));
        }
        assertFalse(Files.exists(target));

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            WorkspaceTransaction.OrphanRecoveryAppliedException applied = assertThrows(
                    WorkspaceTransaction.OrphanRecoveryAppliedException.class,
                    () -> workspace.beginTransaction(snapshot, target, true, 16));
            assertTrue(applied.receipt().targetRestored());
            assertTrue(applied.receipt().targetStateKnown());
            assertEquals(ArtifactStore.sha256(baselineBytes),
                    applied.receipt().targetSha256());
            assertArrayEquals(baselineBytes, Files.readAllBytes(target));

            assertThrows(WorkspaceTransaction.ExistingTargetSizeException.class,
                    () -> workspace.beginTransaction(snapshot, target, true, 16));
        }
    }

    @Test
    void directStickySharedTargetDirectoryIsRejected() throws Exception {
        Path shared = Path.of("/tmp");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(shared));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(shared).supportsFileAttributeView("posix"));
        Path target = shared.resolve("protege-mcp-sticky-" + UUID.randomUUID());

        IOException refusal = assertThrows(IOException.class,
                () -> SecureTargetAnchor.openFallbackForTest(shared, target));

        assertTrue(refusal.getMessage().contains("shared-writable"), refusal::getMessage);
    }

    @Test
    void authoritativeLockRejectsASharedWritableProjectRoot() throws Exception {
        Path shared = Path.of("/tmp").toRealPath();
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(shared));
        Path reserved = shared.resolve(WorkspaceProjectLock.LOCK_DIRECTORY);
        org.junit.jupiter.api.Assumptions.assumeFalse(
                Files.exists(reserved, LinkOption.NOFOLLOW_LINKS));
        IOException refusal = assertThrows(IOException.class,
                () -> WorkspaceProjectLock.acquire(
                        temp.resolve("secure-sticky-state"), shared));
        assertTrue(refusal.getMessage().contains("shared-writable"), refusal::getMessage);
        assertFalse(Files.exists(reserved, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void differentStateRootsUseOneAuthoritativeProjectLock() throws Exception {
        Path firstState = temp.resolve("state-a");
        Path secondState = temp.resolve("state-b");
        assertEquals(WorkspaceProjectLock.path(firstState, temp),
                WorkspaceProjectLock.path(secondState, temp));
        try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                firstState, temp)) {
            assertThrows(ProjectFileLock.UnavailableException.class,
                    () -> WorkspaceProjectLock.acquire(secondState, temp));
        }
    }

    @Test
    void unknownReservedCoordinateIsRejectedWithoutModification() throws Exception {
        Path collision = temp.resolve(WorkspaceProjectLock.LOCK_DIRECTORY);
        byte[] original = "user content\n".getBytes(StandardCharsets.UTF_8);
        Files.write(collision, original);
        Files.setPosixFilePermissions(collision,
                PosixFilePermissions.fromString("rw-r-----"));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(collision);

        assertThrows(IOException.class,
                () -> WorkspaceProjectLock.acquire(temp.resolve("state"), temp));

        assertArrayEquals(original, Files.readAllBytes(collision));
        assertEquals(permissions, Files.getPosixFilePermissions(collision));
    }

    @Test
    void nestedReservedNameRemainsPartOfAssetIdentity() throws Exception {
        Path assets = temp.resolve("assets");
        Path nested = assets.resolve("nested").resolve(WorkspaceProjectLock.LOCK_DIRECTORY);
        Files.createDirectories(nested);
        Path governed = nested.resolve("user-data.txt");
        Files.writeString(governed, "first\n");
        FilesystemProjectWorkspace.FileIdentity before =
                FilesystemProjectWorkspace.directoryIdentity(assets, temp);

        Files.writeString(governed, "second\n");
        FilesystemProjectWorkspace.FileIdentity after =
                FilesystemProjectWorkspace.directoryIdentity(assets, temp);

        assertFalse(before.equals(after));
    }

    @Test
    void assetScanWhileLockedDoesNotReleaseTheOsLock() throws Exception {
        Files.writeString(temp.resolve("asset.txt"), "content\n");
        FilesystemProjectWorkspace.FileIdentity before =
                FilesystemProjectWorkspace.directoryIdentity(temp, temp);
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                temp.resolve("parent-state"), temp)) {
            FilesystemProjectWorkspace.FileIdentity during =
                    FilesystemProjectWorkspace.directoryIdentity(temp, temp);
            assertEquals(before, during);
            Process process = new ProcessBuilder(java.toString(), "-cp",
                    System.getProperty("java.class.path"),
                    WorkspaceRecoveryProcess.class.getName(), "try-lock",
                    temp.resolve("child-state").toString(), temp.toString())
                    .redirectErrorStream(true)
                    .start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(75, process.exitValue(), output);
        }
    }

    @Test
    void recoveryRejectsAReplacedLockInode() throws Exception {
        Path target = temp.resolve("replaced-lock.ofn");
        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    temp.resolve("lock-state"), temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            Files.delete(lock.lockPath());
            Files.writeString(lock.lockPath(), "replacement\n");
            IOException refusal = assertThrows(IOException.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(
                            lock, lock.lockPath()));
            assertTrue(refusal.getMessage().contains("matching project lock"),
                    refusal::getMessage);
        }
    }

    @Test
    void separateJvmCannotAcquireTheAuthoritativeProjectLock() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"),
                WorkspaceRecoveryProcess.class.getName(), "hold-lock",
                temp.resolve("child-state").toString(), temp.toString())
                .redirectErrorStream(true)
                .start();
        try {
            String ready;
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                ready = reader.readLine();
            }
            assertEquals("LOCKED", ready);
            assertThrows(ProjectFileLock.UnavailableException.class,
                    () -> WorkspaceProjectLock.acquire(
                            temp.resolve("parent-state"), temp));
        } finally {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    @Test
    void forgedRetainedDirectoriesCannotRecoverContentOrConsumeQuota() throws Exception {
        Path target = temp.resolve("forged-target.ofn");
        String prefix = ".forged-target.ofn.protege-mcp-transaction-";
        for (int index = 0; index < 40; index++) {
            Path forged = temp.resolve(prefix + UUID.randomUUID());
            Files.createDirectory(forged);
            Files.setPosixFilePermissions(forged,
                    PosixFilePermissions.fromString("rwxrwxrwx"));
            Files.writeString(forged.resolve("transaction-manifest-v1"),
                    "protege-mcp-transaction-v1\n" + forged.getFileName()
                            + "\nforged-target.ofn\n");
            Files.writeString(forged.resolve("displaced"), "forged\n");
        }
        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    temp.resolve("forged-state"), temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            anchor.recoverOrphanedTransactionsUnderLock(lock, lock.lockPath());
            assertFalse(Files.exists(target));
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    "forged-target.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap(
                        "trusted\n".getBytes(StandardCharsets.UTF_8)));
                staged.force();
                FilesystemProjectWorkspace.FileIdentity identity = anchor.identity(
                        staged.name(), 1_024);
                anchor.replaceGuarded(staged.name(), identity, anchor.targetName(),
                        null, 1_024);
            }
        }
        assertEquals("trusted\n", Files.readString(target));
    }

    @Test
    void multipleRecoverableOrphansFailClosedWithoutSelectingAStaleBaseline()
            throws Exception {
        Path target = temp.resolve("ambiguous-orphans.ofn");
        String prefix = ".ambiguous-orphans.ofn.protege-mcp-transaction-";
        for (String content : List.of("older\n", "newer\n")) {
            Path retained = temp.resolve(prefix + UUID.randomUUID());
            Files.createDirectory(retained,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            Files.writeString(retained.resolve("transaction-manifest-v1"),
                    "protege-mcp-transaction-v1\n" + retained.getFileName()
                            + "\nambiguous-orphans.ofn\n");
            Files.writeString(retained.resolve("displaced"), content);
        }

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    temp.resolve("ambiguous-state"), temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            IOException refusal = assertThrows(IOException.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(lock, lock.lockPath()));
            assertTrue(refusal.getMessage().contains("multiple recoverable"),
                    refusal::getMessage);
        }
        assertFalse(Files.exists(target));
    }

    @Test
    void ambiguousAndRecoverableOrphansCannotBeMixed() throws Exception {
        Path target = temp.resolve("mixed-orphans.ofn");
        String prefix = ".mixed-orphans.ofn.protege-mcp-transaction-";
        for (boolean uncertain : List.of(false, true)) {
            Path retained = temp.resolve(prefix + UUID.randomUUID());
            Files.createDirectory(retained,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            Files.writeString(retained.resolve("transaction-manifest-v1"),
                    "protege-mcp-transaction-v1\n" + retained.getFileName()
                            + "\nmixed-orphans.ofn\n");
            Files.writeString(retained.resolve("displaced"),
                    uncertain ? "ambiguous\n" : "recoverable\n");
            if (uncertain) {
                Files.createFile(retained.resolve("publication-uncertain"));
                Files.createFile(retained.resolve("completed-recovery"));
            }
        }

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    temp.resolve("mixed-state"), temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            IOException refusal = assertThrows(IOException.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(
                            lock, lock.lockPath()));
            assertTrue(refusal.getMessage().contains("ambiguous transaction evidence"),
                    refusal::getMessage);
        }
        assertFalse(Files.exists(target));
    }

    @Test
    void preMutationFailuresDoNotLeaveQuotaConsumingDirectories() throws Exception {
        Path target = temp.resolve("no-litter.ofn");
        FilesystemProjectWorkspace.FileIdentity missing =
                new FilesystemProjectWorkspace.FileIdentity("sha256:" + "0".repeat(64), 0);
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            for (int attempt = 0; attempt < 40; attempt++) {
                assertThrows(IOException.class,
                        () -> anchor.replaceGuarded(Path.of("missing-stage"), missing,
                                anchor.targetName(), null, 1_024));
            }
            assertThrows(IOException.class,
                    () -> anchor.quarantineIfIdentity(Path.of("missing-target"),
                            missing, 1_024));
            assertThrows(IOException.class,
                    () -> anchor.quarantineIfIdentity(anchor.targetName(),
                            missing, 1_024,
                            () -> { throw new IOException("before move failure"); },
                            () -> { }));
        }
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".no-litter.ofn.protege-mcp-transaction-")));
        }
    }

    @Test
    void lockedRecoveryCleansAStageOnlyCrashAndRequiresRetry() throws Exception {
        Path target = temp.resolve("stage-only-crash.ofn");
        Files.writeString(target, "baseline\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            FilesystemProjectWorkspace.FileIdentity baseline = anchor.identity(
                    anchor.targetName(), 1_024);
            Path stagedName;
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    "stage-only-crash.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap(
                        "candidate\n".getBytes(StandardCharsets.UTF_8)));
                staged.force();
                stagedName = staged.name();
            }
            FilesystemProjectWorkspace.FileIdentity stagedIdentity = anchor.identity(
                    stagedName, 1_024);
            assertThrows(SimulatedProcessCrash.class,
                    () -> anchor.replaceGuarded(stagedName, stagedIdentity,
                            anchor.targetName(), baseline, 1_024,
                            () -> { }, () -> { },
                            () -> { throw new SimulatedProcessCrash(); },
                            () -> { }, () -> { }));
        }

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    temp.resolve("stage-only-state"), temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            SecureTargetAnchor.RecoverySweepReceipt receipt =
                    anchor.recoverOrphanedTransactionsUnderLock(lock, lock.lockPath());
            assertTrue(receipt.mutationApplied());
            assertTrue(receipt.recoveryStateKnown());
            assertEquals(1, receipt.directoriesCleaned());
        }
        assertEquals("baseline\n", Files.readString(target));
        try (var entries = Files.list(temp)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".stage-only-crash.ofn.protege-mcp-transaction-")));
        }
    }

    @Test
    void simultaneousJvmsPublishOneCompleteLockCoordinate() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process first = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"),
                WorkspaceRecoveryProcess.class.getName(), "hold-lock",
                temp.resolve("first-state").toString(), temp.toString())
                .redirectErrorStream(true).start();
        Process second = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"),
                WorkspaceRecoveryProcess.class.getName(), "hold-lock",
                temp.resolve("second-state").toString(), temp.toString())
                .redirectErrorStream(true).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (first.isAlive() && second.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(first.isAlive() && second.isAlive(),
                    "one racing JVM must fail to acquire the same lock");
            Process winner = first.isAlive() ? first : second;
            Process loser = first.isAlive() ? second : first;
            assertTrue(winner.isAlive(), "exactly one racing JVM must hold the lock");
            assertTrue(loser.waitFor(10, TimeUnit.SECONDS));
            String winnerOutput = winner.inputReader(StandardCharsets.UTF_8).readLine();
            String loserOutput = new String(loser.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals("LOCKED", winnerOutput);
            assertTrue(loser.exitValue() != 0, loserOutput);
        } finally {
            first.getOutputStream().close();
            second.getOutputStream().close();
            if (!first.waitFor(10, TimeUnit.SECONDS)) first.destroyForcibly();
            if (!second.waitFor(10, TimeUnit.SECONDS)) second.destroyForcibly();
        }
        try (WorkspaceProjectLock.Handle ignored = WorkspaceProjectLock.acquire(
                temp.resolve("verification-state"), temp)) {
            assertTrue(Files.isRegularFile(ignored.lockPath()));
        }
    }

    @Test
    void ambiguousPostPublicationCrashNeverRestoresTheOldTarget() throws Exception {
        Path target = temp.resolve("post-publication.ofn");
        Files.writeString(target, "baseline\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            FilesystemProjectWorkspace.FileIdentity baseline = anchor.identity(
                    anchor.targetName(), 1_024);
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    "post-publication.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap(
                        "replacement\n".getBytes(StandardCharsets.UTF_8)));
                staged.force();
                FilesystemProjectWorkspace.FileIdentity stagedIdentity = anchor.identity(
                        staged.name(), 1_024);
                assertThrows(SimulatedProcessCrash.class,
                        () -> anchor.replaceGuarded(staged.name(), stagedIdentity,
                                anchor.targetName(), baseline, 1_024,
                                () -> { }, () -> { }, () -> { }, () -> { },
                                () -> { throw new SimulatedProcessCrash(); }));
            }
        }
        assertEquals("replacement\n", Files.readString(target));
        Files.delete(target);

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(
                    temp.resolve("post-publication-state"), temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            assertThrows(IOException.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(
                            lock, lock.lockPath()));
        }

        assertFalse(Files.exists(target),
                "an ambiguous publication must never resurrect the displaced baseline");
        try (var entries = Files.list(temp)) {
            assertTrue(entries.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".post-publication.ofn.protege-mcp-transaction-")));
        }
    }

    @Test
    void crashAfterRecoveryLinkNeverRestoresTheSameBaselineAgain() throws Exception {
        Path target = temp.resolve("recovery-link-crash.ofn");
        Files.writeString(target, "baseline\n");
        FilesystemProjectWorkspace.FileIdentity baseline;
        Path stagedName;
        FilesystemProjectWorkspace.FileIdentity stagedIdentity;
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            baseline = anchor.identity(anchor.targetName(), 1_024);
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    "recovery-link-crash.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap(
                        "replacement\n".getBytes(StandardCharsets.UTF_8)));
                staged.force();
                stagedName = staged.name();
            }
            stagedIdentity = anchor.identity(stagedName, 1_024);
            assertThrows(SimulatedProcessCrash.class,
                    () -> anchor.replaceGuarded(stagedName, stagedIdentity,
                            anchor.targetName(), baseline, 1_024,
                            () -> { }, () -> { throw new SimulatedProcessCrash(); }));
        }
        assertFalse(Files.exists(target));

        Path state = temp.resolve("recovery-link-state");
        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(state, temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            assertThrows(SimulatedProcessCrash.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(
                            lock, lock.lockPath(),
                            () -> { throw new SimulatedProcessCrash(); }));
        }
        assertEquals("baseline\n", Files.readString(target));
        Files.delete(target);

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(state, temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            assertThrows(IOException.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(
                            lock, lock.lockPath()));
        }
        assertFalse(Files.exists(target),
                "recovery uncertainty must prevent repeated baseline resurrection");
    }

    @Test
    void killedRecoveryProcessReleasesTheLockWithoutEnablingStaleRestoration()
            throws Exception {
        Path target = temp.resolve("killed-recovery.ofn");
        createPrePublicationOrphan(target);

        Path state = temp.resolve("killed-recovery-state");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"),
                WorkspaceRecoveryProcess.class.getName(), "recover-and-halt",
                state.toString(), temp.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS),
                    "recovery subprocess did not terminate");
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(73, process.exitValue(), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertEquals("baseline\n", Files.readString(target));
        Files.delete(target);

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(state, temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            assertThrows(IOException.class,
                    () -> anchor.recoverOrphanedTransactionsUnderLock(
                            lock, lock.lockPath()));
        }
        assertFalse(Files.exists(target));
    }

    @Test
    void killedCleanupAfterDisplacedDeletionCannotEnableStaleRestoration()
            throws Exception {
        Path target = temp.resolve("killed-cleanup.ofn");
        createPrePublicationOrphan(target);
        Path state = temp.resolve("killed-cleanup-state");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp",
                System.getProperty("java.class.path"),
                WorkspaceRecoveryProcess.class.getName(), "recover-cleanup-halt",
                state.toString(), temp.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS),
                    "recovery cleanup subprocess did not terminate");
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(74, process.exitValue(), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertEquals("baseline\n", Files.readString(target));
        Files.delete(target);

        try (WorkspaceProjectLock.Handle lock = WorkspaceProjectLock.acquire(state, temp);
                SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            SecureTargetAnchor.RecoverySweepReceipt receipt =
                    anchor.recoverOrphanedTransactionsUnderLock(lock, lock.lockPath());
            assertTrue(receipt.mutationApplied());
            assertTrue(receipt.recoveryStateKnown());
        }
        assertFalse(Files.exists(target));
    }

    @Test
    void appliedPrivateMovesProducePhaseAccurateReceipts() throws Exception {
        Path target = temp.resolve("phase-receipt.ofn");
        Files.writeString(target, "baseline\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            FilesystemProjectWorkspace.FileIdentity baseline = anchor.identity(
                    anchor.targetName(), 1_024);
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    "phase-receipt.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap("candidate\n".getBytes(
                        StandardCharsets.UTF_8)));
                staged.force();
                FilesystemProjectWorkspace.FileIdentity stagedIdentity = anchor.identity(
                        staged.name(), 1_024);
                SecureTargetAnchor.GuardedReplaceException stageFailure = assertThrows(
                        SecureTargetAnchor.GuardedReplaceException.class,
                        () -> anchor.replaceGuarded(staged.name(), stagedIdentity,
                                anchor.targetName(), baseline, 1_024,
                                () -> { }, () -> { },
                                () -> { throw new IOException("stage attachment failure"); },
                                () -> { }, () -> { }));
                assertTrue(stageFailure.receipt().sourceMoved());
                assertFalse(stageFailure.receipt().originalMoved());
                assertTrue(stageFailure.receipt().stagedStateKnown());
                assertEquals("baseline\n", Files.readString(target));
            }
        }

        Path displacedTarget = temp.resolve("phase-displaced.ofn");
        Files.writeString(displacedTarget, "baseline two\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, displacedTarget)) {
            FilesystemProjectWorkspace.FileIdentity baseline = anchor.identity(
                    anchor.targetName(), 1_024);
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    "phase-displaced.ofn", "stage")) {
                staged.channel().write(ByteBuffer.wrap("candidate two\n".getBytes(
                        StandardCharsets.UTF_8)));
                staged.force();
                FilesystemProjectWorkspace.FileIdentity stagedIdentity = anchor.identity(
                        staged.name(), 1_024);
                SecureTargetAnchor.GuardedReplaceException displacementFailure = assertThrows(
                        SecureTargetAnchor.GuardedReplaceException.class,
                        () -> anchor.replaceGuarded(staged.name(), stagedIdentity,
                                anchor.targetName(), baseline, 1_024,
                                () -> { }, () -> { }, () -> { },
                                () -> { throw new IOException("displacement attachment failure"); },
                                () -> { }));
                assertTrue(displacementFailure.receipt().sourceMoved());
                assertTrue(displacementFailure.receipt().originalMoved());
                assertTrue(displacementFailure.receipt().displacedStateKnown());
                assertEquals(baseline.sha256(),
                        displacementFailure.receipt().displacedSha256());
                assertFalse(Files.exists(displacedTarget));
            }
        }
    }

    @Test
    void transactionRejectsAnExistingTargetBeforeHashingPastTheCallerBound()
            throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path target = temp.resolve("large.ofn");
        Files.writeString(target, "x".repeat(2_048));
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            IOException refused = assertThrows(IOException.class,
                    () -> workspace.beginTransaction(snapshot, target, true, 1_024));
            assertTrue(refused.getMessage().contains("exceeds 1024 bytes"),
                    refused::getMessage);
        }
    }

    @Test
    void serializesProjectCommitsWithAnAdvisoryProcessLock() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        byte[] original = Files.readAllBytes(root);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(snapshot, root, false)) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));
            Path lockPath = transaction.lockPath();
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.lock()) {
                IOException locked = assertThrows(IOException.class, transaction::commit);
                assertTrue(locked.getMessage().contains("lock is already held"), locked::getMessage);
            }
            assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(root));
        }
    }

    @Test
    void failsClosedWhenAtomicReplacementIsUnavailable() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        byte[] original = Files.readAllBytes(root);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(workspace, snapshot, root,
                        false, () -> { }, (source, destination) -> {
                            throw new AtomicMoveNotSupportedException(
                                    source.toString(), destination.toString(), "test seam");
                        })) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));

            IOException unsupported = assertThrows(IOException.class, transaction::commit);

            assertTrue(unsupported.getMessage().contains("does not support atomic replacement"),
                    unsupported::getMessage);
            assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(root));
        }
    }

    @Test
    void reportsPublishedBackupWhenTargetReplacementIsPrevented() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        byte[] original = Files.readAllBytes(root);
        AtomicInteger moves = new AtomicInteger();

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(
                        workspace, snapshot, root, true, () -> { }, (source, destination) -> {
                            if (moves.incrementAndGet() == 2) {
                                throw new IOException("injected target replacement failure");
                            }
                            WorkspaceTransactionTestMoves.atomic(source, destination);
                        })) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));

            WorkspaceTransaction.BackupAppliedException applied = assertThrows(
                    WorkspaceTransaction.BackupAppliedException.class, transaction::commit);

            assertEquals(WorkspaceTransaction.State.STAGED, transaction.state());
            assertEquals(ArtifactStore.sha256(original), applied.backupSha256());
            assertEquals(ArtifactStore.sha256(original), ArtifactStore.sha256(root));
            assertTrue(Files.isRegularFile(applied.backupPath()));
            assertEquals(ArtifactStore.sha256(original),
                    ArtifactStore.sha256(applied.backupPath()));
        }
    }

    @Test
    void backupReceiptDoesNotClaimVerificationAfterPostPublicationTampering()
            throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        byte[] original = Files.readAllBytes(root);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(
                        workspace, snapshot, root, true, () -> { }, (source, destination) -> {
                            WorkspaceTransactionTestMoves.atomic(source, destination);
                            if (destination.getFileName().toString()
                                    .startsWith(".protege-mcp-backup-")) {
                                Files.writeString(destination, "tampered backup\n");
                            }
                        })) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));

            WorkspaceTransaction.BackupAppliedException applied = assertThrows(
                    WorkspaceTransaction.BackupAppliedException.class, transaction::commit);

            WorkspaceTransaction.BackupSideEffect receipt = applied.receipt();
            assertTrue(receipt.locationCurrent());
            assertTrue(receipt.backupStateKnown());
            assertFalse(receipt.backupVerified());
            assertEquals(ArtifactStore.sha256("tampered backup\n"
                    .getBytes(StandardCharsets.UTF_8)), receipt.backupSha256());
            assertTrue(receipt.targetStateKnown());
            assertTrue(receipt.targetPreserved());
            assertEquals(ArtifactStore.sha256(original), ArtifactStore.sha256(root));
        }
    }

    @Test
    void backupReceiptReportsTargetDriftInsteadOfClaimingPreservation() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(
                        workspace, snapshot, root, true, () -> { }, (source, destination) -> {
                            WorkspaceTransactionTestMoves.atomic(source, destination);
                            if (destination.getFileName().toString()
                                    .startsWith(".protege-mcp-backup-")) {
                                Files.writeString(root, "racing target writer\n");
                            }
                        })) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));

            WorkspaceTransaction.BackupAppliedException applied = assertThrows(
                    WorkspaceTransaction.BackupAppliedException.class, transaction::commit);

            WorkspaceTransaction.BackupSideEffect receipt = applied.receipt();
            assertTrue(receipt.backupVerified());
            assertTrue(receipt.targetStateKnown());
            assertFalse(receipt.targetPreserved());
            assertEquals("racing target writer\n", Files.readString(root));
        }
    }

    @Test
    void reportsACommitReceiptWhenPostInstallVerificationLosesARace() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(workspace, snapshot, root,
                        true, () -> { }, (source, destination) -> {
                            WorkspaceTransactionTestMoves.atomic(source, destination);
                            if (destination.equals(root.toRealPath())) {
                                Files.writeString(destination, "racing writer\n");
                            }
                        })) {
            byte[] replacement = "replacement\n".getBytes(StandardCharsets.UTF_8);
            transaction.stageBytes(replacement);

            WorkspaceTransaction.CommitAppliedException applied = assertThrows(
                    WorkspaceTransaction.CommitAppliedException.class, transaction::commit);

            assertEquals(WorkspaceTransaction.State.COMMITTED, transaction.state());
            assertEquals(ArtifactStore.sha256(replacement), applied.commit().installedSha256());
            assertTrue(Files.isRegularFile(applied.commit().backupPath()));
            assertEquals("racing writer\n", Files.readString(root));
        }
    }

    @Test
    void recoveryReportsAppliedMutationWhenAttachmentVerificationFails() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        String baseline = ArtifactStore.sha256(root);
        AtomicInteger moves = new AtomicInteger();

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(
                        workspace, snapshot, root, true, () -> { }, (source, destination) -> {
                            WorkspaceTransactionTestMoves.atomic(source, destination);
                            if (moves.incrementAndGet() == 3) {
                                throw new SecureTargetAnchor.MutationAppliedException(
                                        new IOException("injected post-recovery detachment"));
                            }
                        })) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));
            transaction.commit();

            WorkspaceTransaction.RecoveryAppliedException applied = assertThrows(
                    WorkspaceTransaction.RecoveryAppliedException.class, transaction::recover);

            assertEquals(root.toRealPath(), applied.receipt().target());
            assertTrue(applied.receipt().expectedPresent());
            assertEquals(baseline, applied.receipt().expectedSha256());
            assertEquals(WorkspaceTransaction.State.COMMITTED, transaction.state());
        }
    }

    @Test
    void recoversANewTargetByDeletingOnlyItsUnchangedInstalledArtifact() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        Path target = temp.resolve("generated.json");

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(snapshot, target, true)) {
            transaction.stageBytes("{}\n".getBytes(StandardCharsets.UTF_8));
            WorkspaceTransaction.Commit commit = transaction.commit();
            assertFalse(commit.previousExisted());
            assertTrue(Files.isRegularFile(target));

            WorkspaceTransaction.Recovery recovery = transaction.recover();

            assertFalse(recovery.restored());
            assertFalse(Files.exists(target));
            assertTrue(Files.isRegularFile(recovery.retainedArtifact()));
            assertEquals(commit.installedSha256(),
                    ArtifactStore.sha256(recovery.retainedArtifact()));
        }
        try (SecureTargetAnchor ignored = SecureTargetAnchor.open(temp, target)) {
            assertFalse(Files.exists(target),
                    "completed recovery evidence must not be mistaken for a crash orphan");
        }
    }

    @Test
    void guardedCommitNeverOverwritesANewTargetCreatedAfterFinalVerification()
            throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        Path target = temp.resolve("racing-new-target.json");

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(
                        workspace, snapshot, target, true,
                        WorkspaceTransaction.MAX_STAGED_BYTES,
                        () -> { }, () -> { },
                        () -> Files.writeString(target, "racing target\n"),
                        () -> { }, null)) {
            transaction.stageBytes("intended\n".getBytes(StandardCharsets.UTF_8));

            WorkspaceTransaction.GuardedReplacementException guarded = assertThrows(
                    WorkspaceTransaction.GuardedReplacementException.class,
                    transaction::commit);

            assertEquals("racing target\n", Files.readString(target));
            assertEquals(WorkspaceTransaction.State.PARTIAL, transaction.state());
            assertTrue(guarded.receipt().stagedRetained());
            assertFalse(guarded.receipt().originalMoved());
            assertTrue(guarded.receipt().targetStateKnown());
            assertTrue(guarded.receipt().targetPresent());
            assertEquals(ArtifactStore.sha256("racing target\n".getBytes(
                            StandardCharsets.UTF_8)), guarded.receipt().targetSha256());
            assertFalse(guarded.receipt().publicationApplied());
            assertTrue(Files.isRegularFile(guarded.receipt().retainedStagePath()));
        }
    }

    @Test
    void guardedCommitRetainsTheBaselineWhenTargetIsRecreatedAfterDisplacement()
            throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        String baseline = ArtifactStore.sha256(root);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = new WorkspaceTransaction(
                        workspace, snapshot, root, true,
                        WorkspaceTransaction.MAX_STAGED_BYTES,
                        () -> { }, () -> { }, () -> { },
                        () -> Files.writeString(root, "racing target\n"), null)) {
            transaction.stageBytes("intended\n".getBytes(StandardCharsets.UTF_8));

            WorkspaceTransaction.GuardedReplacementException guarded = assertThrows(
                    WorkspaceTransaction.GuardedReplacementException.class,
                    transaction::commit);

            assertEquals("racing target\n", Files.readString(root));
            assertEquals(WorkspaceTransaction.State.PARTIAL, transaction.state());
            assertTrue(guarded.receipt().originalMoved());
            assertTrue(guarded.receipt().displacedMatched());
            assertEquals(baseline, guarded.receipt().displacedSha256());
            assertTrue(Files.isRegularFile(guarded.receipt().displacedPath()));
            assertTrue(guarded.receipt().targetPresent());
            assertFalse(guarded.receipt().publicationApplied());
            assertTrue(guarded.receipt().backupPublished());
            assertTrue(guarded.receipt().backupStateKnown());
            assertTrue(guarded.receipt().backupVerified());
            assertEquals(baseline, guarded.receipt().backupSha256());
            assertTrue(Files.isRegularFile(guarded.receipt().backupPath()));
        }
    }

    @Test
    void refusesRecoveryAfterTheTargetOrBackupChanges() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);

        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(snapshot, root, true)) {
            transaction.stageBytes("replacement\n".getBytes(StandardCharsets.UTF_8));
            WorkspaceTransaction.Commit commit = transaction.commit();
            Files.writeString(commit.backupPath(), "tampered backup\n");

            IOException refused = assertThrows(IOException.class, transaction::recover);

            assertTrue(refused.getMessage().contains("backup changed"), refused::getMessage);
            assertEquals("replacement\n", Files.readString(root));
            Files.delete(commit.backupPath());
        }

        writeRoot(IMPORT_IRI);
        try (WorkspaceSnapshot snapshot = workspace.capture();
                WorkspaceTransaction transaction = workspace.beginTransaction(snapshot, root, true)) {
            transaction.stageBytes("replacement two\n".getBytes(StandardCharsets.UTF_8));
            transaction.commit();
            Files.writeString(root, "post-commit edit\n");

            IOException refused = assertThrows(IOException.class, transaction::recover);

            assertTrue(refused.getMessage().contains("target changed"), refused::getMessage);
            assertEquals("post-commit edit\n", Files.readString(root));
        }
    }

    @Test
    void confinesTransactionTargetsAndRejectsSymlinkDestinations() throws Exception {
        Path root = writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        FilesystemProjectWorkspace workspace = transactionWorkspace(policy);
        Path outside = Files.createTempFile("protege-mcp-transaction-outside-", ".txt");
        try {
            try (WorkspaceSnapshot snapshot = workspace.capture()) {
                IOException escaped = assertThrows(IOException.class,
                        () -> workspace.beginTransaction(snapshot, outside, false));
                assertTrue(escaped.getMessage().contains("outside the project"), escaped::getMessage);

                Path alias = temp.resolve("alias.ttl");
                Files.createSymbolicLink(alias, root.getFileName());
                IOException symlink = assertThrows(IOException.class,
                        () -> workspace.beginTransaction(snapshot, alias, false));
                assertTrue(symlink.getMessage().contains("must not be a symbolic link"),
                        symlink::getMessage);
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private FilesystemProjectWorkspace transactionWorkspace(Path policy) {
        return new FilesystemProjectWorkspace(policy, temp.resolve("state"), () -> { });
    }

    private void createPrePublicationOrphan(Path target) throws Exception {
        Files.writeString(target, "baseline\n");
        try (SecureTargetAnchor anchor = SecureTargetAnchor.open(temp, target)) {
            FilesystemProjectWorkspace.FileIdentity baseline = anchor.identity(
                    anchor.targetName(), 1_024);
            Path stagedName;
            try (SecureTargetAnchor.CreatedFile staged = anchor.createSibling(
                    target.getFileName().toString(), "stage")) {
                staged.channel().write(ByteBuffer.wrap(
                        "replacement\n".getBytes(StandardCharsets.UTF_8)));
                staged.force();
                stagedName = staged.name();
            }
            FilesystemProjectWorkspace.FileIdentity stagedIdentity = anchor.identity(
                    stagedName, 1_024);
            assertThrows(SimulatedProcessCrash.class,
                    () -> anchor.replaceGuarded(stagedName, stagedIdentity,
                            anchor.targetName(), baseline, 1_024,
                            () -> { }, () -> { throw new SimulatedProcessCrash(); }));
        }
        assertFalse(Files.exists(target));
    }

    private static final class WorkspaceTransactionTestMoves {
        private WorkspaceTransactionTestMoves() {
        }

        static void atomic(Path source, Path destination) throws IOException {
            Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path writeRoot(String importIri) throws IOException {
        Path root = temp.resolve("root.ttl");
        Files.writeString(root, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + ROOT_IRI + "> a owl:Ontology ; owl:imports <" + importIri + "> .\n"
                + "<#Local> a owl:Class .\n");
        return root;
    }

    private Path writeImport(String local) throws IOException {
        Path imported = temp.resolve("import.ttl");
        Files.writeString(imported, importTurtle(local));
        return imported;
    }

    private static String importTurtle(String local) {
        return "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<" + IMPORT_IRI + "> a owl:Ontology .\n"
                + "<" + IMPORT_IRI + "#" + local + "> a owl:Class .\n";
    }

    private void writeLock(Path imported, boolean direct) throws IOException {
        String sha = ArtifactStore.sha256(imported).substring("sha256:".length());
        Files.writeString(temp.resolve("imports.lock.json"), "{\n"
                + "  \"version\": 1,\n"
                + "  \"imports\": [{\n"
                + "    \"ontology_iri\": \"" + IMPORT_IRI + "\",\n"
                + "    \"version_iri\": null,\n"
                + "    \"document\": \"import.ttl\",\n"
                + "    \"sha256\": \"" + sha + "\",\n"
                + "    \"direct\": " + direct + "\n"
                + "  }]\n"
                + "}\n");
    }

    private Path writePolicy(String extra) throws IOException {
        Path policy = temp.resolve("project.yaml");
        String validation = extra.contains("validation:") ? "" : "validation:\n"
                + "  required_stages: [structural]\n";
        Files.writeString(policy, "version: 1\n"
                + "project_id: workspace-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: root.ttl\n"
                + "  metadata:\n"
                + "    path: ro-crate-metadata.json\n"
                + "    format: ro-crate-1.1\n"
                + "  canonicalization:\n"
                + "    algorithm: RDFC-1.0\n"
                + "    hash: SHA-256\n"
                + "    scope: root-ontology\n"
                + "    timeout_ms: 120000\n"
                + validation
                + extra);
        writeCrate();
        return policy;
    }

    private void writeCrate() throws IOException {
        String profile = "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
        List<Object> graph = new ArrayList<>();
        graph.add(entity("ro-crate-metadata.json", "CreativeWork", "about", ref("./"),
                "conformsTo", ref("https://w3id.org/ro/crate/1.1")));
        graph.add(entity("./", "Dataset", "name", "Workspace test", "description", "Test project",
                "datePublished", "2026-07-19", "license",
                "https://www.apache.org/licenses/LICENSE-2.0", "identifier", "workspace-test",
                "conformsTo", List.of(ref(profile)), "mainEntity", ref("root.ttl"),
                "hasPart", ref("root.ttl")));
        graph.add(entity(profile, "CreativeWork", "name", "Project profile"));
        graph.add(entity("root.ttl", "File", "encodingFormat", "text/turtle",
                "about", ref(ROOT_IRI)));
        graph.add(entity(ROOT_IRI, "Dataset", "conformsTo",
                ref("https://www.w3.org/TR/owl2-overview/")));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                temp.resolve("ro-crate-metadata.json").toFile(),
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

    private static String catalog(String content) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                + content + "\n</catalog>\n";
    }

    private static Path privateFallbackRoot() throws IOException {
        Path home = Path.of(System.getProperty("user.home")).toRealPath();
        Path root = Files.createTempDirectory(home, ".protege-mcp-fallback-test-");
        try {
            Files.setPosixFilePermissions(root,
                    PosixFilePermissions.fromString("rwx------"));
            return root;
        } catch (UnsupportedOperationException unsupported) {
            Files.deleteIfExists(root);
            org.junit.jupiter.api.Assumptions.abort(
                    "POSIX permissions are unavailable: " + unsupported);
            throw new AssertionError("unreachable");
        }
    }

    private static final class SimulatedProcessCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static void mirrorWithHardLinks(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.createLink(destination, path);
            }
        }
    }
}
