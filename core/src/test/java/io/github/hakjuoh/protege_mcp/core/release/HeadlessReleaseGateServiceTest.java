package io.github.hakjuoh.protege_mcp.core.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.core.workspace.FilesystemProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.ProjectWorkspace;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceSnapshot;

class HeadlessReleaseGateServiceTest {

    private static final String ROOT_IRI = "https://example.org/release";
    private static final String VERSION_IRI = ROOT_IRI + "/1.0.0";
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 19);

    @TempDir
    Path temp;

    @Test
    void preparesACompleteBundleWithoutWritingTheProject() throws Exception {
        Path policy = fixture(true);
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);

        HeadlessReleaseGateService.Result result;
        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            result = HeadlessReleaseGateService.evaluate(workspace, snapshot,
                    new StructuralReasonerFactory(), request(null), TODAY);
        }

        assertEquals(GateStatus.PASS, result.gate().gate(), result.details()::toString);
        assertNotNull(result.bundle());
        assertEquals(7, result.bundle().artifacts().size());
        assertEquals(VERSION_IRI, result.details().get("version_iri"));
        assertEquals("deny", result.details().get("network"));
        assertEquals(Boolean.TRUE, result.details().get("snapshot_consistent"));
        assertFalse(result.details().toString().contains(temp.toString()),
                "release details must not leak local project paths");
        assertFalse(Files.exists(temp.resolve("dist")), "read-only evaluation must not publish");
    }

    @Test
    void missingRequiredVersionIriFailsClosedWithoutArtifacts() throws Exception {
        Path policy = fixture(false);
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);

        HeadlessReleaseGateService.Result result;
        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            result = HeadlessReleaseGateService.evaluate(workspace, snapshot,
                    new StructuralReasonerFactory(), request(null), TODAY);
        }

        assertEquals(GateStatus.ERROR, result.gate().gate());
        assertNull(result.bundle());
        assertTrue(result.gate().findings().stream()
                .anyMatch(finding -> "release.version_iri_missing".equals(finding.id())));
    }

    @Test
    void verifiesEveryBaselineArtifactBeforeComparingThePrimary() throws Exception {
        Path policy = fixture(true);
        HeadlessReleaseGateService.Result first = evaluate(policy, request(null));
        Path baseline = Files.createDirectories(temp.resolve("baseline"));
        ArtifactStore store = new ArtifactStore(baseline);
        first.bundle().artifacts().forEach(artifact ->
                store.writeBytes(artifact.path(), artifact.content()));

        HeadlessReleaseGateService.Result compared = evaluate(policy,
                request(baseline.resolve("manifest.json")));

        assertEquals(GateStatus.PASS, compared.gate().gate(), compared.details()::toString);
        assertEquals(8, compared.bundle().artifacts().size());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) compared.details().get("baseline");
        assertEquals("compared", summary.get("status"));
        assertEquals(Boolean.TRUE, summary.get("identical"));
        assertFalse(compared.baselineSources().isEmpty());
        assertTrue(compared.baselineSources().stream()
                .allMatch(HeadlessReleaseGateService.SourcePin::current));
        Files.writeString(baseline.resolve("reports/qc.json"), "changed after evaluation");
        assertFalse(compared.baselineSources().stream()
                .allMatch(HeadlessReleaseGateService.SourcePin::current));
    }

    @Test
    void rejectsATamperedNonPrimaryBaselineArtifact() throws Exception {
        Path policy = fixture(true);
        HeadlessReleaseGateService.Result first = evaluate(policy, request(null));
        Path baseline = Files.createDirectories(temp.resolve("tampered-baseline"));
        ArtifactStore store = new ArtifactStore(baseline);
        first.bundle().artifacts().forEach(artifact ->
                store.writeBytes(artifact.path(), artifact.content()));
        Files.writeString(baseline.resolve("reports/qc.md"), "tampered");

        HeadlessReleaseGateService.Result result = evaluate(policy,
                request(baseline.resolve("manifest.json")));

        assertEquals(GateStatus.ERROR, result.gate().gate());
        assertNull(result.bundle());
        assertTrue(result.gate().findings().stream()
                .anyMatch(finding -> "release.baseline_invalid".equals(finding.id())));
    }

    @Test
    void refusesABaselineArtifactThatEscapesItsManifestDirectory() throws Exception {
        Path policy = fixture(true);
        HeadlessReleaseGateService.Result first = evaluate(policy, request(null));
        Path baseline = Files.createDirectories(temp.resolve("escaping-baseline"));
        ArtifactStore store = new ArtifactStore(baseline);
        first.bundle().artifacts().forEach(artifact ->
                store.writeBytes(artifact.path(), artifact.content()));
        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = mapper.readValue(
                baseline.resolve("manifest.json").toFile(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> primary = ((List<Map<String, Object>>) manifest.get("artifacts")).get(0);
        byte[] root = Files.readAllBytes(temp.resolve("root.ttl"));
        primary.put("path", "../root.ttl");
        primary.put("sha256", ArtifactStore.sha256(root));
        primary.put("bytes", root.length);
        mapper.writeValue(baseline.resolve("manifest.json").toFile(), manifest);

        HeadlessReleaseGateService.Result result = evaluate(policy,
                request(baseline.resolve("manifest.json")));

        assertEquals(GateStatus.ERROR, result.gate().gate());
        assertNull(result.bundle());
        assertTrue(result.gate().findings().stream()
                .anyMatch(finding -> "release.baseline_invalid".equals(finding.id())));
    }

    @Test
    void driftAfterQcButBeforeBundleAssemblyFailsClosed() throws Exception {
        Path policy = fixture(true);
        FilesystemProjectWorkspace delegate = new FilesystemProjectWorkspace(policy);
        AtomicInteger checks = new AtomicInteger();
        ProjectWorkspace drifting = new ProjectWorkspace() {
            @Override public String workspaceId() { return delegate.workspaceId(); }
            @Override public WorkspaceSnapshot capture() throws IOException { return delegate.capture(); }
            @Override public boolean isCurrent(WorkspaceSnapshot snapshot) {
                return checks.incrementAndGet() == 1 && delegate.isCurrent(snapshot);
            }
        };

        HeadlessReleaseGateService.Result result;
        try (WorkspaceSnapshot snapshot = drifting.capture()) {
            result = HeadlessReleaseGateService.evaluate(drifting, snapshot,
                    new StructuralReasonerFactory(), request(null), TODAY);
        }

        assertEquals(GateStatus.ERROR, result.gate().gate());
        assertNull(result.bundle());
        assertEquals(Boolean.FALSE, result.details().get("snapshot_consistent"));
        assertTrue(result.gate().findings().stream()
                .anyMatch(finding -> "release.snapshot_changed".equals(finding.id())));
    }

    @Test
    void rejectsNonUtcReproducibilityTimestamps() {
        assertThrows(IllegalArgumentException.class,
                () -> new HeadlessReleaseGateService.Request(null, "2026-07-19T12:00:00-04:00", 50));
    }

    @Test
    void optionalCleanRoundTripDoesNotTurnSerializationLossIntoAnErrorFinding() throws Exception {
        Path policy = fixture(true);
        Files.writeString(temp.resolve("root.ttl"), Files.readString(temp.resolve("root.ttl"))
                + "<" + ROOT_IRI + "#Term> <http://www.w3.org/2000/01/rdf-schema#label> "
                + "\"Second label\"@en .\n");
        Files.writeString(policy, Files.readString(policy)
                .replace("format: turtle", "format: obo")
                .replace("require_clean_round_trip: true", "require_clean_round_trip: false"));

        HeadlessReleaseGateService.Result result = evaluate(policy, request(null));

        assertEquals(GateStatus.PASS, result.gate().gate(), result.details()::toString);
        assertNull(result.bundle(), "an unverified serialization cannot become a release bundle");
        assertFalse(result.gate().findings().stream()
                .anyMatch(finding -> "release.round_trip_failed".equals(finding.id())));
        assertTrue(result.gate().findings().stream()
                .anyMatch(finding -> "release.lossy_format".equals(finding.id())
                        && "warning".equals(finding.severity().json())));
    }

    private HeadlessReleaseGateService.Result evaluate(Path policy,
            HeadlessReleaseGateService.Request request) throws Exception {
        FilesystemProjectWorkspace workspace = new FilesystemProjectWorkspace(policy);
        try (WorkspaceSnapshot snapshot = workspace.capture()) {
            return HeadlessReleaseGateService.evaluate(workspace, snapshot,
                    new StructuralReasonerFactory(), request, TODAY);
        }
    }

    private static HeadlessReleaseGateService.Request request(Path baseline) {
        return new HeadlessReleaseGateService.Request(
                baseline, "2026-07-19T12:00:00Z", 50);
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
                + "project_id: headless-release-test\n"
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
        graph.add(entity("./", "Dataset", "name", "Headless release test",
                "description", "Headless release project", "datePublished", "2026-07-19",
                "license", "https://www.apache.org/licenses/LICENSE-2.0", "identifier",
                "headless-release-test", "conformsTo", List.of(ref(profile)),
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
