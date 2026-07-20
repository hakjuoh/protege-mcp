package io.github.hakjuoh.protege_mcp.core.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.core.workspace.FilesystemProjectWorkspace;

class HeadlessReleaseServiceTest {

    private static final String ROOT_IRI = "https://example.org/release-service";
    private static final String VERSION_IRI = ROOT_IRI + "/1.0.0";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-19T13:14:15Z"), ZoneOffset.UTC);

    @TempDir
    Path temp;

    @Test
    void dryRunBuildsEveryArtifactWithoutWriting() throws Exception {
        Path policy = fixture(true);

        HeadlessReleaseService.Result result = execute(policy,
                new HeadlessReleaseService.Request(null, null, true, null, 50));

        assertEquals(GateStatus.PASS, result.gate().gate(), result.details()::toString);
        assertFalse(result.prepared());
        assertTrue(result.dryRun());
        assertEquals("dist", result.outputDirectory());
        assertEquals("PREVIEW", result.details().get("created_at"));
        assertEquals(7, result.bundle().artifacts().size());
        assertNull(result.publication());
        assertFalse(Files.exists(temp.resolve("dist")));
    }

    @Test
    void commitsOneVerifiedDirectoryUsingOneClockReading() throws Exception {
        Path policy = fixture(true);
        AtomicInteger reads = new AtomicInteger();
        Clock countingClock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                reads.incrementAndGet();
                return CLOCK.instant();
            }
        };

        HeadlessReleaseService.Result result = HeadlessReleaseService.execute(
                new FilesystemProjectWorkspace(policy), new StructuralReasonerFactory(),
                new HeadlessReleaseService.Request(null, null, false, null, 50), countingClock);

        assertTrue(result.prepared());
        assertEquals(1, reads.get(), "a release must derive its timestamp and UTC date atomically");
        assertEquals("2026-07-19T13:14:15Z", result.details().get("created_at"));
        assertEquals("dist", result.publication().outputDirectory());
        assertFalse(result.publication().previousExisted());
        assertNull(result.publication().backupPath());
        assertTrue(Files.isRegularFile(temp.resolve("dist/manifest.json")));
        assertTrue(Files.isRegularFile(temp.resolve("dist/reports/qc.sarif")));
        assertArrayEquals(ReleaseManifest.toJson(result.bundle().manifest())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(temp.resolve("dist/manifest.json")));
    }

    @Test
    void baselineInsideReplacedOutputIsPinnedAndPreservedInBackup() throws Exception {
        Path policy = fixture(true);
        HeadlessReleaseService.Result first = execute(policy,
                new HeadlessReleaseService.Request(null, null, false,
                        "2026-07-19T10:00:00Z", 50));
        byte[] oldManifest = Files.readAllBytes(temp.resolve("dist/manifest.json"));

        HeadlessReleaseService.Result second = execute(policy,
                new HeadlessReleaseService.Request(null,
                        Path.of("dist/manifest.json"), false,
                        "2026-07-19T11:00:00Z", 50));

        assertTrue(first.prepared());
        assertTrue(second.prepared(), second.details()::toString);
        assertTrue(second.publication().previousExisted());
        assertNotNull(second.publication().backupPath());
        Path backup = temp.resolve(second.publication().backupPath());
        assertEquals(List.of("reports/diff.json"), second.bundle().artifacts().stream()
                .map(ReleaseBundleService.Artifact::path)
                .filter("reports/diff.json"::equals).toList());
        assertTrue(Files.isRegularFile(backup.resolve("manifest.json")));
        assertTrue(java.util.Arrays.equals(oldManifest,
                Files.readAllBytes(backup.resolve("manifest.json"))));
    }

    @Test
    void aFailedReleaseGateNeverCreatesTheOutput() throws Exception {
        Path policy = fixture(false);

        HeadlessReleaseService.Result result = execute(policy,
                new HeadlessReleaseService.Request(null, null, false, null, 50));

        assertEquals(GateStatus.ERROR, result.gate().gate());
        assertFalse(result.prepared());
        assertNull(result.bundle());
        assertFalse(Files.exists(temp.resolve("dist")));
    }

    @Test
    void outputTraversalIsRejectedBeforeGateExecutionAndWritesNothing() throws Exception {
        Path policy = fixture(true);
        Path outside = temp.getParent().resolve("escaped-release");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> execute(policy, new HeadlessReleaseService.Request(
                        outside, null, false, null, 50)));

        assertTrue(error.getMessage().contains("invalid release output"), error::getMessage);
        assertFalse(Files.exists(outside));
    }

    @Test
    void concurrentOutputChangeDuringGateIsNeverOverwritten() throws Exception {
        Path policy = fixture(true);
        Path output = Files.createDirectory(temp.resolve("dist"));
        Path marker = output.resolve("owner.txt");
        Files.writeString(marker, "original");
        StructuralReasonerFactory mutatingReasoner = new StructuralReasonerFactory() {
            @Override
            public OWLReasoner createReasoner(OWLOntology ontology,
                    OWLReasonerConfiguration configuration) {
                try {
                    Files.writeString(marker, "concurrent update");
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                return super.createReasoner(ontology, configuration);
            }
        };

        IOException error = assertThrows(IOException.class,
                () -> HeadlessReleaseService.execute(new FilesystemProjectWorkspace(policy),
                        mutatingReasoner, new HeadlessReleaseService.Request(
                                null, null, false, null, 50), CLOCK));

        assertTrue(error.getMessage().contains("release output changed"), error::getMessage);
        assertEquals("concurrent update", Files.readString(marker));
        assertFalse(Files.exists(output.resolve("manifest.json")));
    }

    private HeadlessReleaseService.Result execute(Path policy,
            HeadlessReleaseService.Request request) throws IOException {
        return HeadlessReleaseService.execute(new FilesystemProjectWorkspace(policy),
                new StructuralReasonerFactory(), request, CLOCK);
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
                + "project_id: headless-release-service-test\n"
                + "root_ontology: " + ROOT_IRI + "\n"
                + "interoperability:\n"
                + "  profile: https://hakjuoh.github.io/protege-mcp/profiles/project-v1/\n"
                + "  additional_profiles: []\n"
                + "  root_artifact: root.ttl\n"
                + "  metadata: {path: ro-crate-metadata.json, format: ro-crate-1.1}\n"
                + "  canonicalization: {algorithm: RDFC-1.0, hash: SHA-256, scope: root-ontology}\n"
                + "reasoning:\n"
                + "  reasoner: Structural Reasoner\n"
                + "validation:\n"
                + "  required_stages: [interoperability, reasoner, profile]\n"
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
        graph.add(entity("./", "Dataset", "name", "Headless release service test",
                "description", "Headless release service project",
                "datePublished", "2026-07-19",
                "license", "https://www.apache.org/licenses/LICENSE-2.0", "identifier",
                "headless-release-service-test", "conformsTo", List.of(ref(profile)),
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
