package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.workspace.ImportLockFile;

class ImportLockCommandTest {

    private static final String ROOT_IRI = "https://example.org/cli-lock-root";
    private static final String IMPORT_IRI = "https://example.org/cli-lock-import";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void dryRunAndWriteGenerateTheSamePortableOfflineLock() throws Exception {
        Path policy = fixture();

        Invocation dryRun = run("imports", "lock", "--project", policy.toString(),
                "--dry-run", "--no-network");

        assertEquals(0, dryRun.exit(), dryRun::err);
        Map<String, Object> preview = json(dryRun.out());
        assertEquals(false, preview.get("written"));
        assertEquals(true, preview.get("dry_run"));
        assertEquals("imports.lock.json", preview.get("path"));
        assertEquals(1, preview.get("entry_count"));
        assertTrue(preview.containsKey("no_network"));
        assertFalse(dryRun.out().contains(temp.toString()), "output paths must be portable");
        assertFalse(Files.exists(temp.resolve("imports.lock.json")));

        Invocation write = run("imports", "lock", "--project", policy.toString());

        assertEquals(0, write.exit(), write::err);
        Map<String, Object> installed = json(write.out());
        assertEquals(true, installed.get("written"));
        assertEquals(preview.get("sha256"), installed.get("sha256"));
        ImportLockFile.Document lock = ImportLockFile.read(temp.resolve("imports.lock.json"));
        assertEquals(1, lock.entries().size());
        assertEquals("import.ttl", lock.entries().get(0).document());
        assertEquals(IMPORT_IRI, lock.entries().get(0).ontologyIri());
    }

    @Test
    void outputTraversalIsAConfigurationErrorAndWritesNothing() throws Exception {
        Path policy = fixture();
        Path outside = temp.getParent().resolve("escaped-imports.lock.json");

        Invocation result = run("imports", "lock", "--project", policy.toString(),
                "--output", outside.toString());

        assertEquals(2, result.exit(), result::err);
        assertTrue(result.err().contains("inside the project root"), result::err);
        assertFalse(Files.exists(outside));
    }

    @Test
    void rejectsUnknownImportsSubcommands() {
        Invocation result = run("imports", "verify");

        assertEquals(2, result.exit());
        assertTrue(result.err().contains("'lock' subcommand"), result::err);
    }

    private Path fixture() throws IOException {
        Files.writeString(temp.resolve("root.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + ROOT_IRI + "> a owl:Ontology ; owl:imports <"
                        + IMPORT_IRI + "> .\n");
        Files.writeString(temp.resolve("import.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "<" + IMPORT_IRI + "> a owl:Ontology .\n");
        writeCrate();
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, "version: 1\n"
                + "project_id: cli-import-lock-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: root.ttl\n"
                + "  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.1}\n"
                + "  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}\n"
                + "modules:\n"
                + "  - ontology_iri: " + IMPORT_IRI + "\n"
                + "    path: import.ttl\n"
                + "imports:\n"
                + "  mode: locked\n"
                + "  lockfile: imports.lock.json\n"
                + "validation:\n"
                + "  required_stages: [interoperability]\n");
        return policy;
    }

    private void writeCrate() throws IOException {
        String profile = "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
        List<Object> graph = new ArrayList<>();
        graph.add(entity("ro-crate-metadata.json", "CreativeWork", "about", ref("./"),
                "conformsTo", ref("https://w3id.org/ro/crate/1.1")));
        graph.add(entity("./", "Dataset", "name", "CLI import lock test",
                "description", "CLI import lock test project", "datePublished", "2026-07-19",
                "license", "https://www.apache.org/licenses/LICENSE-2.0", "identifier",
                "cli-import-lock-test", "conformsTo", List.of(ref(profile)),
                "mainEntity", ref("root.ttl"), "hasPart", ref("root.ttl")));
        graph.add(entity(profile, "CreativeWork", "name", "Project profile"));
        graph.add(entity("root.ttl", "File", "encodingFormat", "text/turtle",
                "about", ref(ROOT_IRI)));
        graph.add(entity(ROOT_IRI, "Dataset", "conformsTo",
                ref("https://www.w3.org/TR/owl2-overview/")));
        JSON.writeValue(temp.resolve("ro-crate-metadata.json").toFile(),
                Map.of("@context", "https://w3id.org/ro/crate/1.1/context", "@graph", graph));
    }

    private Invocation run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(out), new PrintStream(err));
        return new Invocation(exit, out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> json(String value) throws IOException {
        return JSON.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
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

    private record Invocation(int exit, String out, String err) {
    }
}
