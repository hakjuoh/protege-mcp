package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

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
    void parsesPreviouslyCapturedPolicyBytesWithoutRereadingTheSource() throws Exception {
        writeRoot(IMPORT_IRI);
        writeImport("Imported");
        Path policy = writePolicy("modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n");
        byte[] captured = Files.readAllBytes(policy);
        Files.writeString(policy, "not: [the, captured, policy]\n");

        ProjectPolicy loaded = ProjectPolicyLoader.loadCaptured(
                policy.toRealPath(), captured, null, null, true);

        assertTrue(loaded.valid(), () -> loaded.issues().toString());
        assertEquals(ROOT_IRI, loaded.effective().get("root_ontology"));
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
            assertEquals(new String(original, StandardCharsets.UTF_8),
                    Files.readString(commit.backupPath()));
            assertTrue(commit.backupPath().getFileName().toString()
                    .startsWith(".protege-mcp-backup-"));
            assertFalse(workspace.isCurrent(snapshot));

            WorkspaceTransaction.Recovery recovery = transaction.recover();

            assertTrue(recovery.restored());
            assertEquals(ArtifactStore.sha256(original), recovery.restoredSha256());
            assertEquals(new String(original, StandardCharsets.UTF_8), Files.readString(root));
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
}
