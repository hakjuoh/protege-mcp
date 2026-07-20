package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

class ImportLockServiceTest {

    private static final String ROOT_IRI = "https://example.org/root";
    private static final String IMPORT_IRI = "https://example.org/import";
    private static final String CHILD_IRI = "https://example.org/child";

    @TempDir
    Path temp;

    @Test
    void dryRunRendersDeterministicCapturedImportWithoutWriting() throws Exception {
        Path imported = fixture();
        Path policy = policy(false);
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);

        ImportLockService.Result result = ImportLockService.generate(workspace, null, true);

        assertFalse(result.written());
        assertTrue(result.dryRun());
        assertEquals("imports.lock.json", result.path());
        assertFalse(Files.exists(temp.resolve("imports.lock.json")));
        ImportLockFile.Document parsed = ImportLockFile.parse(
                result.content(), temp.resolve("imports.lock.json"));
        assertEquals(1, parsed.entries().size());
        assertEquals(IMPORT_IRI, parsed.entries().get(0).ontologyIri());
        assertEquals("import.ttl", parsed.entries().get(0).document());
        assertEquals(ArtifactStore.sha256(imported).substring("sha256:".length()),
                parsed.entries().get(0).sha256());
        assertTrue(parsed.entries().get(0).direct());
    }

    @Test
    void atomicallyCreatesAndThenBacksUpAReplacement() throws Exception {
        fixture();
        Path policy = policy(false);
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);

        ImportLockService.Result created = ImportLockService.generate(workspace, null, false);
        byte[] first = Files.readAllBytes(temp.resolve("imports.lock.json"));
        ImportLockService.Result replaced = ImportLockService.generate(workspace, null, false);

        assertTrue(created.written());
        assertTrue(replaced.written());
        assertEquals(created.sha256(), replaced.sha256());
        assertEquals(first.length, replaced.bytes());
        assertTrue(replaced.backupPath().startsWith(".protege-mcp-backup-"));
        assertArrayEquals(first, Files.readAllBytes(temp.resolve(replaced.backupPath())));
    }

    @Test
    void bootstrapsOnlyAMissingPolicyDeclaredLock() throws Exception {
        fixture();
        Path policy = policy(true);

        ImportLockService.Result result = ImportLockService.generate(
                new FilesystemProjectWorkspace(policy), null, true);

        assertEquals("imports.lock.json", result.path());
        assertEquals(1, result.entryCount());
    }

    @Test
    void regeneratesFromExistingLockMappingWithoutTrustingItsOldChecksum() throws Exception {
        Path imported = fixture();
        Path lock = temp.resolve("imports.lock.json");
        Files.write(lock, ImportLockFile.render(List.of(new ImportLockFile.Entry(
                IMPORT_IRI, null, "import.ttl", "0".repeat(64), true, imported))));
        Path policy = policy(true, false);

        ImportLockService.Result result = ImportLockService.generate(
                new FilesystemProjectWorkspace(policy), null, true);

        ImportLockFile.Document candidate = ImportLockFile.parse(result.content(), lock);
        assertEquals(ArtifactStore.sha256(imported).substring("sha256:".length()),
                candidate.entries().get(0).sha256());
        assertFalse(candidate.entries().get(0).sha256().equals("0".repeat(64)));
    }

    @Test
    void replacesAMalformedOldLockWhenPolicyMappingsStillResolveTheClosure() throws Exception {
        fixture();
        Files.writeString(temp.resolve("imports.lock.json"), "{not valid json");
        Path policy = policy(true);

        ImportLockService.Result result = ImportLockService.generate(
                new FilesystemProjectWorkspace(policy), null, true);

        assertEquals(1, result.entryCount());
        assertEquals(1, ImportLockFile.parse(result.content(), temp.resolve("imports.lock.json"))
                .entries().size());
    }

    @Test
    void rejectsSourceDriftDuringSnapshotCapture() throws Exception {
        Path imported = fixture();
        Path policy = policy(false);
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy,
                () -> Files.writeString(imported, Files.readString(imported) + "# changed\n"));

        IOException refusal = assertThrows(IOException.class,
                () -> ImportLockService.generate(workspace, null, false));

        assertTrue(refusal.getMessage().contains("changed"), refusal::getMessage);
        assertFalse(Files.exists(temp.resolve("imports.lock.json")));
    }

    @Test
    void refusesOutputOutsideTheProject() throws Exception {
        fixture();
        Path policy = policy(false);
        Path outside = temp.getParent().resolve("outside-imports.lock.json");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> ImportLockService.generate(
                new FilesystemProjectWorkspace(policy), outside, true));

        assertTrue(refusal.getMessage().contains("inside the project root"), refusal::getMessage);
        assertFalse(Files.exists(outside));
    }

    @Test
    void ignoresADeletedStaleHintWhenPolicyMappingsResolveTheCurrentClosure() throws Exception {
        Path imported = fixture();
        Path lock = temp.resolve("imports.lock.json");
        Files.write(lock, ImportLockFile.render(List.of(
                new ImportLockFile.Entry(IMPORT_IRI, null, "import.ttl", "0".repeat(64),
                        true, imported),
                new ImportLockFile.Entry("https://example.org/deleted", null, "deleted.ttl",
                        "0".repeat(64), false, temp.resolve("deleted.ttl")))));

        ImportLockService.Result result = ImportLockService.generate(
                new FilesystemProjectWorkspace(policy(true)), null, true);

        ImportLockFile.Document candidate = ImportLockFile.parse(result.content(), lock);
        assertEquals(1, candidate.entries().size());
        assertEquals(IMPORT_IRI, candidate.entries().get(0).ontologyIri());
    }

    @Test
    void marksTransitiveImportsAsIndirect() throws Exception {
        fixture();
        addChildImport(false);

        ImportLockFile.Document candidate = generatedLock();
        Map<String, ImportLockFile.Entry> entries = byOntology(candidate);

        assertTrue(entries.get(IMPORT_IRI).direct());
        assertFalse(entries.get(CHILD_IRI).direct());
    }

    @Test
    void promotesAnIdentitySeenBothTransitivelyAndDirectly() throws Exception {
        fixture();
        addChildImport(true);

        ImportLockFile.Document candidate = generatedLock();

        assertTrue(byOntology(candidate).get(CHILD_IRI).direct());
    }

    private Path fixture() throws IOException {
        Files.writeString(temp.resolve("root.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + ROOT_IRI + "> a owl:Ontology ; owl:imports <"
                        + IMPORT_IRI + "> .\n");
        Path imported = temp.resolve("import.ttl");
        Files.writeString(imported,
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORT_IRI + "> a owl:Ontology .\n"
                        + "<" + IMPORT_IRI + "#Term> a owl:Class .\n");
        writeCrate();
        return imported;
    }

    private void addChildImport(boolean alsoDirect) throws IOException {
        Files.writeString(temp.resolve("root.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + ROOT_IRI + "> a owl:Ontology ; owl:imports <"
                        + IMPORT_IRI + ">"
                        + (alsoDirect ? ", <" + CHILD_IRI + ">" : "") + " .\n");
        Files.writeString(temp.resolve("import.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORT_IRI + "> a owl:Ontology ; owl:imports <"
                        + CHILD_IRI + "> .\n");
        Files.writeString(temp.resolve("child.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + CHILD_IRI + "> a owl:Ontology .\n");
        Files.writeString(temp.resolve("catalog-v001.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n"
                        + "  <uri name=\"" + CHILD_IRI + "\" uri=\"child.ttl\"/>\n"
                        + "</catalog>\n");
    }

    private ImportLockFile.Document generatedLock() throws Exception {
        ImportLockService.Result result = ImportLockService.generate(
                new FilesystemProjectWorkspace(policy(false)), null, true);
        return ImportLockFile.parse(result.content(), temp.resolve("imports.lock.json"));
    }

    private static Map<String, ImportLockFile.Entry> byOntology(ImportLockFile.Document document) {
        Map<String, ImportLockFile.Entry> result = new LinkedHashMap<>();
        document.entries().forEach(entry -> result.put(entry.ontologyIri(), entry));
        return result;
    }

    private Path policy(boolean locked) throws IOException {
        return policy(locked, true);
    }

    private Path policy(boolean locked, boolean includeModule) throws IOException {
        Path path = temp.resolve("project.yaml");
        Files.writeString(path, "version: 1\n"
                + "project_id: import-lock-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: root.ttl\n"
                + "  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.1}\n"
                + "  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}\n"
                + (includeModule ? "modules:\n"
                        + "  - ontology_iri: " + IMPORT_IRI + "\n"
                        + "    path: import.ttl\n" : "")
                + "imports:\n"
                + "  mode: " + (locked ? "locked" : "unlocked") + "\n"
                + (locked ? "  lockfile: imports.lock.json\n" : "")
                + "validation:\n"
                + "  required_stages: [interoperability]\n");
        return path;
    }

    private void writeCrate() throws IOException {
        String profile = "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
        List<Object> graph = new ArrayList<>();
        graph.add(entity("ro-crate-metadata.json", "CreativeWork", "about", ref("./"),
                "conformsTo", ref("https://w3id.org/ro/crate/1.1")));
        graph.add(entity("./", "Dataset", "name", "Import lock test",
                "description", "Import lock test project", "datePublished", "2026-07-19",
                "license", "https://www.apache.org/licenses/LICENSE-2.0", "identifier",
                "import-lock-test", "conformsTo", List.of(ref(profile)),
                "mainEntity", ref("root.ttl"), "hasPart", ref("root.ttl")));
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
