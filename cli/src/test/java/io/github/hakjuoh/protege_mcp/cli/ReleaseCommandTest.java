package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

class ReleaseCommandTest {

    private static final String ROOT_IRI = "https://example.org/cli-release";
    private static final String VERSION_IRI = ROOT_IRI + "/1.0.0";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void dryRunReturnsCompletePortableEvidenceAndWritesNothing() throws Exception {
        Path policy = fixture(true);

        Invocation invocation = run("release", "--project", policy.toString(),
                "--dry-run", "--no-network", "--no-external");

        assertEquals(0, invocation.exit(), invocation::err);
        Map<String, Object> result = json(invocation.out());
        assertEquals(false, result.get("prepared"));
        assertEquals(true, result.get("dry_run"));
        assertEquals("pass", result.get("gate"));
        assertEquals("PREVIEW", result.get("created_at"));
        assertEquals("dist", result.get("output_dir"));
        assertEquals(7, list(result.get("artifacts")).size());
        assertTrue(map(result.get("reports")).keySet().containsAll(List.of(
                "reports/qc.json", "reports/qc.md", "reports/qc.xml",
                "reports/qc.sarif")));
        assertTrue(result.containsKey("manifest"));
        assertTrue(result.containsKey("ro_crate"));
        assertTrue(result.containsKey("no_network"));
        assertEquals(true, result.get("no_external_paths"));
        assertFalse(invocation.out().contains(temp.toString()),
                "machine output must contain only portable release paths");
        assertFalse(Files.exists(temp.resolve("dist")));
    }

    @Test
    void writePublishesEveryChecksummedArtifactAsOneDirectory() throws Exception {
        Path policy = fixture(true);

        Invocation invocation = run("release", "--project", policy.toString(),
                "--created-at", "2026-07-19T12:34:56Z");

        assertEquals(0, invocation.exit(), invocation::err);
        Map<String, Object> result = json(invocation.out());
        assertEquals(true, result.get("prepared"));
        assertEquals(false, result.get("dry_run"));
        assertEquals("2026-07-19T12:34:56Z", result.get("created_at"));
        assertEquals(false, map(result.get("publication")).get("previous_existed"));
        for (Object raw : list(result.get("artifacts"))) {
            Map<String, Object> artifact = map(raw);
            Path file = temp.resolve("dist").resolve((String) artifact.get("path"));
            assertTrue(Files.isRegularFile(file), file::toString);
            assertEquals(artifact.get("sha256"), ArtifactStore.sha256(file));
            assertEquals(((Number) artifact.get("bytes")).longValue(), Files.size(file));
        }
        assertFalse(invocation.out().contains(temp.toString()));
    }

    @Test
    void aReleaseGateErrorWritesNoArtifactDirectory() throws Exception {
        Path policy = fixture(false);

        Invocation invocation = run("release", "--project", policy.toString());

        assertEquals(3, invocation.exit(), invocation::err);
        Map<String, Object> result = json(invocation.out());
        assertEquals(false, result.get("prepared"));
        assertEquals("error", result.get("gate"));
        assertTrue(list(result.get("findings")).stream().map(ReleaseCommandTest::map)
                .anyMatch(finding -> "release.version_iri_missing".equals(finding.get("id"))));
        assertEquals(List.of(), result.get("artifacts"));
        assertFalse(Files.exists(temp.resolve("dist")));
    }

    @Test
    void outputEscapeAndInvalidTimestampAreConfigurationErrors() throws Exception {
        Path policy = fixture(true);
        Path outside = temp.getParent().resolve("escaped-cli-release");

        Invocation escaped = run("release", "--project", policy.toString(),
                "--output", outside.toString());
        Invocation timestamp = run("release", "--project", policy.toString(),
                "--created-at", "2026-07-19T08:00:00-04:00");
        Invocation preview = run("release", "--project", policy.toString(),
                "--created-at", "PREVIEW");
        Invocation negativeLimit = run("release", "--project", policy.toString(),
                "--limit", "-1");
        Invocation excessiveLimit = run("release", "--project", policy.toString(),
                "--limit", "10001");

        assertEquals(2, escaped.exit(), escaped::err);
        assertTrue(escaped.err().contains("invalid release output"), escaped::err);
        assertEquals(2, timestamp.exit(), timestamp::err);
        assertTrue(timestamp.err().contains("UTC"), timestamp::err);
        assertEquals(2, preview.exit(), preview::err);
        assertTrue(preview.err().contains("reserved for dry-run"), preview::err);
        assertEquals(2, negativeLimit.exit(), negativeLimit::err);
        assertEquals(2, excessiveLimit.exit(), excessiveLimit::err);
        assertFalse(Files.exists(outside));
        assertFalse(Files.exists(temp.resolve("dist")));
    }

    @Test
    void aPassingGateWithoutVerifiedSerializationReturnsExecutionErrorAndWritesNothing()
            throws Exception {
        Path policy = fixture(true);
        Files.writeString(temp.resolve("root.ttl"), Files.readString(temp.resolve("root.ttl"))
                + "<" + ROOT_IRI + "#Term> <http://www.w3.org/2000/01/rdf-schema#label> "
                + "\"Second label\"@en .\n");
        Files.writeString(policy, Files.readString(policy)
                .replace("format: turtle", "format: obo")
                .replace("require_clean_round_trip: true",
                        "require_clean_round_trip: false"));

        Invocation invocation = run("release", "--project", policy.toString());

        assertEquals(3, invocation.exit(), invocation::err);
        Map<String, Object> result = json(invocation.out());
        assertEquals("pass", result.get("gate"));
        assertEquals(false, result.get("artifacts_available"));
        assertEquals(List.of(), result.get("artifacts"));
        assertFalse(Files.exists(temp.resolve("dist")));
    }

    @Test
    void boundedPreviewNeverSplitsAUnicodeSurrogatePair() {
        String value = "a".repeat(7_999) + "😀" + "tail";

        String preview = Main.boundedPreview(value.getBytes(StandardCharsets.UTF_8));

        int marker = preview.indexOf("\n...[truncated]");
        assertNotEquals(-1, marker);
        assertFalse(Character.isHighSurrogate(preview.charAt(marker - 1)));
        assertFalse(Character.isLowSurrogate(preview.charAt(marker - 1)));
    }

    private Path fixture(boolean versioned) throws IOException {
        Files.writeString(temp.resolve("root.ttl"),
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                        + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                        + "<" + ROOT_IRI + "> a owl:Ontology"
                        + (versioned ? " ; owl:versionIRI <" + VERSION_IRI + ">" : "") + " .\n"
                        + "<" + ROOT_IRI + "#Term> a owl:Class ; rdfs:label \"Term\"@en .\n");
        writeCrate();
        Path policy = temp.resolve("project.yaml");
        Files.writeString(policy, "version: 1\n"
                + "project_id: cli-release-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: root.ttl\n"
                + "  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.1}\n"
                + "  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}\n"
                + "validation:\n"
                + "  required_stages: [interoperability, profile]\n"
                + "  fail_on: error\n"
                + "release:\n"
                + "  format: turtle\n"
                + "  output_dir: dist\n"
                + "  require_version_iri: true\n"
                + "  require_clean_round_trip: true\n");
        return policy;
    }

    private void writeCrate() throws IOException {
        String profile = "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/";
        List<Object> graph = new ArrayList<>();
        graph.add(entity("ro-crate-metadata.json", "CreativeWork", "about", ref("./"),
                "conformsTo", ref("https://w3id.org/ro/crate/1.1")));
        graph.add(entity("./", "Dataset", "name", "CLI release test",
                "description", "CLI release test project", "datePublished", "2026-07-19",
                "license", "https://www.apache.org/licenses/LICENSE-2.0", "identifier",
                "cli-release-test", "conformsTo", List.of(ref(profile)),
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
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
