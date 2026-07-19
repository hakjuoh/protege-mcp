package io.github.hakjuoh.protege_mcp.core.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;
import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.contracts.StageResult;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

/** The dependency-clean core release package: manifest, reports (JSON/MD/JUnit/SARIF), crate, store. */
class ReleaseCoreTest {

    private static final String FP = "sha256:" + "a".repeat(64);

    @Test
    void manifestIsByteIdenticalForTheSameInputsAndTimestamp() {
        Map<String, Object> a = manifest("2026-07-18T00:00:00Z");
        Map<String, Object> b = manifest("2026-07-18T00:00:00Z");
        assertEquals(ReleaseManifest.toJson(a), ReleaseManifest.toJson(b));
        assertEquals(1, a.get("manifest_version"));
        assertEquals("https://example.org/ont/1.0.0", a.get("version_iri"));
    }

    @Test
    void manifestRefusesASessionOnlyFingerprint() {
        assertThrows(ReleaseManifest.UnstableFingerprintException.class,
                () -> ReleaseManifest.build("p", "https://example.org/ont", null,
                        "2026-07-18T00:00:00Z", FP, null, FP, false,
                        List.of(new ReleaseManifest.Artifact("ontology.ttl", FP, 10)), "pass",
                        "reports/qc.json", null));
    }

    @Test
    void junitMapsFindingsErrorsAndSkipsToTheRightElements() {
        GateResult gate = failingGate();
        String xml = ReleaseReports.junit(gate, "2026-07-18T00:00:00");
        assertTrue(xml.startsWith("<?xml"), xml);
        assertTrue(xml.contains("<testsuites"), xml);
        assertTrue(xml.contains("<failure message=\"a class is unsatisfiable\""), xml);
        assertTrue(xml.contains("<skipped message="), xml);
        // A finding at/over the threshold is a failure; an errored stage is an <error>.
        assertTrue(xml.contains("<error message="), xml);
    }

    @Test
    void junitDoesNotDropFindingsFromAnErroredStage() {
        Finding finding = new Finding("release.snapshot_changed", "release",
                FindingSeverity.ERROR, "snapshot changed", null, null, null, null, null, Map.of());
        StageResult stage = new StageResult("release", StageStatus.ERROR, "release failed",
                List.of(finding), Map.of());
        GateResult gate = GateResult.aggregate(1, FP, List.of("release"), List.of(stage),
                FindingSeverity.ERROR);

        String xml = ReleaseReports.junit(gate, "2026-07-18T00:00:00Z");

        assertTrue(xml.contains("<error message=\"release failed\""), xml);
        assertTrue(xml.contains("type=\"release.snapshot_changed\""), xml);
    }

    @Test
    void sarifIsMinimallyValidAndFingerprintsByRuleAndFocus() throws Exception {
        GateResult gate = failingGate();
        String json = ReleaseReports.sarifJson(gate, "policy.yaml");
        JsonNode root = ContractJson.mapper().readTree(json);
        assertEquals("2.1.0", root.get("version").asText());
        assertEquals("https://json.schemastore.org/sarif-2.1.0.json", root.get("$schema").asText());
        JsonNode run = root.get("runs").get(0);
        assertEquals("protege-mcp", run.get("tool").get("driver").get("name").asText());
        assertTrue(run.get("tool").get("driver").get("rules").size() >= 1, json);
        JsonNode result = run.get("results").get(0);
        assertEquals("error", result.get("level").asText());
        assertNotNull(result.get("message").get("text"));
        JsonNode region = result.get("locations").get(0).get("physicalLocation").get("region");
        assertEquals(1, region.get("startLine").asInt());
        assertNotNull(result.get("partialFingerprints").get("primaryLocationLineHash"));
    }

    @Test
    void markdownContainsTheStageTableAndVerdict() {
        String md = ReleaseReports.markdown(failingGate(), "https://example.org/ont/1", 50);
        assertTrue(md.contains("# Release gate: fail"), md);
        assertTrue(md.contains("| Stage | Status | Findings |"), md);
        assertTrue(md.contains("`reasoner`"), md);
    }

    @Test
    void reportsAreDeterministic() {
        GateResult gate = failingGate();
        assertEquals(ReleaseReports.junit(gate, "T"), ReleaseReports.junit(gate, "T"));
        assertEquals(ReleaseReports.sarifJson(gate, "p"), ReleaseReports.sarifJson(gate, "p"));
        assertEquals(ReleaseReports.markdown(gate, "v", 50), ReleaseReports.markdown(gate, "v", 50));
    }

    @Test
    void crateDeclaresTheChecksumTermConformsToAndProvenance() throws Exception {
        Map<String, Object> crate = ReleaseCrate.build("proj", "desc",
                "https://example.org/ont/1.0.0", "2026-07-18T00:00:00Z",
                Map.of("@id", "https://spdx.org/licenses/BSD-2-Clause.html"), "ontology.ttl",
                List.of(new ReleaseCrate.CrateFile("ontology.ttl", "text/turtle", FP, 12),
                        new ReleaseCrate.CrateFile("manifest.json", "application/json", FP, 34)));
        JsonNode root = ContractJson.mapper().readTree(ReleaseCrate.toJson(crate));
        JsonNode context = root.get("@context");
        assertEquals(ReleaseCrate.RO_CRATE_CONTEXT, context.get(0).asText());
        assertEquals(ReleaseCrate.SHA256_TERM, context.get(1).get("sha256").asText());
        boolean conformsToProfile = false;
        boolean hasCreateAction = false;
        boolean fileHasSha = false;
        boolean licensePreserved = false;
        for (JsonNode node : root.get("@graph")) {
            if ("ro-crate-metadata.json".equals(node.path("@id").asText())) {
                for (JsonNode c : node.get("conformsTo")) {
                    if (ReleaseCrate.RELEASE_PROFILE.equals(c.path("@id").asText())) {
                        conformsToProfile = true;
                    }
                }
            }
            if ("CreateAction".equals(node.path("@type").asText())) {
                hasCreateAction = true;
                assertEquals("2026-07-18T00:00:00Z", node.get("endTime").asText());
            }
            if ("./".equals(node.path("@id").asText())) {
                licensePreserved = "https://spdx.org/licenses/BSD-2-Clause.html"
                        .equals(node.path("license").path("@id").asText());
            }
            if ("ontology.ttl".equals(node.path("@id").asText())) {
                assertEquals("12", node.get("contentSize").asText());
                assertEquals("a".repeat(64), node.get("sha256").asText());
                fileHasSha = true;
            }
        }
        assertTrue(conformsToProfile && hasCreateAction && fileHasSha && licensePreserved,
                ReleaseCrate.toJson(crate));
    }

    @Test
    void artifactStoreWritesAtomicallyWithCorrectDigestAndConfinement(@TempDir Path temp) {
        ArtifactStore store = new ArtifactStore(temp);
        ArtifactStore.Written written = store.writeText("reports/qc.md", "# hi\n");
        assertEquals("reports/qc.md", written.path());
        assertEquals(5, written.bytes());
        assertEquals(ArtifactStore.sha256("# hi\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                written.sha256());
        assertTrue(Files.exists(temp.resolve("reports/qc.md")));
        assertEquals(1, store.written().size());
        assertThrows(IllegalArgumentException.class, () -> store.writeText("../escape.txt", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> store.writeText("reports/../../escape.txt", "x"));
    }

    @Test
    void artifactStoreRefusesAWriteThroughASymlinkedChildDirectory(@TempDir Path temp)
            throws Exception {
        Path root = Files.createDirectories(temp.resolve("out"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("reports"), outside);
        } catch (java.io.IOException | UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + unsupported);
        }
        ArtifactStore store = new ArtifactStore(root);
        // Lexically reports/qc.json is beneath root, but the symlink redirects it outside.
        assertThrows(IllegalArgumentException.class, () -> store.writeText("reports/qc.json", "x"));
        assertFalse(Files.exists(outside.resolve("qc.json")),
                "nothing may land in the symlink target");
    }

    @Test
    void sarifNeverEmitsAnAbsolutePathAsArtifactUri() throws Exception {
        // A path-less finding with an absolute fallback must degrade to the "ontology" placeholder,
        // never leak the local policy-file path into the shipped report.
        Finding warn = new Finding("release.version_iri_missing", "release", FindingSeverity.WARNING,
                "no version IRI", null, null, null, null, null, Map.of());
        StageResult release = new StageResult("release", StageStatus.PASS, null, List.of(warn),
                Map.of());
        GateResult gate = GateResult.aggregate(1, FP, List.of("release"), List.of(release),
                FindingSeverity.ERROR);
        JsonNode root = ContractJson.mapper().readTree(
                ReleaseReports.sarifJson(gate, "/Users/alice/project/.protege-mcp/project.yaml"));
        String uri = root.get("runs").get(0).get("results").get(0).get("locations").get(0)
                .get("physicalLocation").get("artifactLocation").get("uri").asText();
        assertEquals("ontology", uri, "an absolute fallback must not leak into the SARIF uri");
        // A repo-root-relative fallback is preserved.
        JsonNode rel = ContractJson.mapper().readTree(
                ReleaseReports.sarifJson(gate, "ontology.ttl"));
        assertEquals("ontology.ttl", rel.get("runs").get(0).get("results").get(0).get("locations")
                .get(0).get("physicalLocation").get("artifactLocation").get("uri").asText());

        for (String unsafe : List.of("../secret.ttl", "file:///tmp/secret.ttl",
                "\\\\server\\share\\secret.ttl")) {
            JsonNode sanitized = ContractJson.mapper().readTree(
                    ReleaseReports.sarifJson(gate, unsafe));
            assertEquals("ontology", sanitized.get("runs").get(0).get("results").get(0)
                    .get("locations").get(0).get("physicalLocation").get("artifactLocation")
                    .get("uri").asText(), unsafe);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static Map<String, Object> manifest(String createdAt) {
        return ReleaseManifest.build("example-product-ontology", "https://example.org/ont",
                "https://example.org/ont/1.0.0", createdAt, FP, FP, FP, true,
                List.of(new ReleaseManifest.Artifact("ontology.ttl", FP, 12345)), "pass",
                "reports/qc.json", new ReleaseManifest.Baseline("https://example.org/ont/0.9.0",
                        "reports/diff.json"));
    }

    private static GateResult failingGate() {
        Finding unsat = new Finding("reasoner.finding", "reasoner", FindingSeverity.ERROR,
                "a class is unsatisfiable", "https://example.org/Broken", null, "ontology.ttl",
                null, null, Map.of());
        StageResult reasoner = new StageResult("reasoner", StageStatus.FAIL, null, List.of(unsat),
                Map.of());
        StageResult profile = StageResult.error("profile", "classification timed out");
        StageResult shacl = new StageResult("shacl", StageStatus.SKIPPED, "no shapes configured",
                List.of(), Map.of());
        // reasoner is the only required stage and it FAILs → gate=fail; profile still errors as an
        // optional stage (an <error> in JUnit) and shacl is skipped (a <skipped/>).
        return GateResult.aggregate(1, FP, List.of("reasoner"),
                List.of(reasoner, profile, shacl), FindingSeverity.ERROR);
    }
}
