package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.prefs.Preferences;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.contracts.Finding;
import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.GateStatus;
import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.OntologyAccess;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/** End-to-end tests for run_release_gate and prepare_release over the headless Protégé adapter. */
class ReleaseToolsTest {

    private static final String ONTOLOGY_IRI = "https://example.org/project";
    private static final String VERSION_IRI = ONTOLOGY_IRI + "/1.0.0";

    private Preferences prefs;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void savePreferences() {
        prefs = McpConfig.prefs();
        savedReadOnly = prefs.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restorePreferences() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    // ------------------------------------------------------------------ run_release_gate

    @Test
    void gatePassesEndToEndWithReleaseBlockVersionIriAndLocalImport(@TempDir Path temp)
            throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        ToolContext ctx = ctx(temp, VERSION_IRI, "file://" + temp.resolve("imported.ttl"), null);

        Map<String, Object> result = structured(callGate(ctx,
                Map.of("policy_path", policy.toString())));

        assertEquals("pass", result.get("gate"), () -> result.toString());
        assertEquals(VERSION_IRI, result.get("version_iri"));
        @SuppressWarnings("unchecked")
        Map<String, Object> roundTrip = (Map<String, Object>) result.get("round_trip");
        assertEquals(true, roundTrip.get("clean"));
        assertEquals("turtle", roundTrip.get("format"));
        List<?> imports = (List<?>) result.get("resolved_imports");
        assertEquals(1, imports.size(), () -> imports.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> member = (Map<String, Object>) imports.get(0);
        assertEquals("local_file", member.get("backed_by"), () -> member.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) result.get("manifest_preview");
        assertEquals(1, manifest.get("manifest_version"));
        assertEquals(VERSION_IRI, manifest.get("version_iri"));
        assertTrue(((List<?>) manifest.get("artifacts")).size() >= 6, () -> manifest.toString());
    }

    @Test
    void missingVersionIriIsAReleaseGateError(@TempDir Path temp) throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        ToolContext ctx = ctx(temp, null, null, null);

        Map<String, Object> result = structured(callGate(ctx,
                Map.of("policy_path", policy.toString())));

        assertEquals("error", result.get("gate"), () -> result.toString());
        assertTrue(String.valueOf(result.get("findings")).contains("release.version_iri_missing"),
                () -> result.toString());
        // resolved_imports must be present even when the gate errors.
        assertNotNull(result.get("resolved_imports"));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) result.get("manifest_preview");
        // A version-IRI error still leaves a stable, serializable ontology, so a preview can render;
        // the gate is what refuses. (Manifest availability tracks fingerprint stability, not the gate.)
        assertEquals(1, manifest.get("manifest_version"), () -> manifest.toString());
    }

    @Test
    void qcFailurePropagatesToTheReleaseGate(@TempDir Path temp) throws Exception {
        // A governance warning under fail_on=warn fails the QC governance stage, which the release
        // gate must surface as gate=fail (not error): a policy violation, not an unproducible verdict.
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("test", ONTOLOGY_IRI)
                        + "release:\n  output_dir: out\n  format: turtle\n"
                        + "iri_policy:\n  required_namespaces: [https://example.org/allowed/]\n"
                        + "validation:\n  required_stages: [governance]\n  fail_on: warning\n");
        ToolContext ctx = ctx(temp, VERSION_IRI, null, null);

        Map<String, Object> result = structured(callGate(ctx,
                Map.of("policy_path", policy.toString())));

        assertEquals("fail", result.get("gate"), () -> result.toString());
    }

    @Test
    void remoteBackedClosureMemberUnderDefaultDenyIsAGateError(@TempDir Path temp) throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        ToolContext ctx = ctx(temp, VERSION_IRI, "http://example.org/remote/b.ttl", null);

        Map<String, Object> result = structured(callGate(ctx,
                Map.of("policy_path", policy.toString())));

        assertEquals("error", result.get("gate"), () -> result.toString());
        assertTrue(String.valueOf(result.get("findings")).contains("imports.remote_backed"),
                () -> result.toString());
        List<?> imports = (List<?>) result.get("resolved_imports");
        assertEquals(1, imports.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> member = (Map<String, Object>) imports.get(0);
        assertEquals("remote", member.get("backed_by"), () -> member.toString());
    }

    @Test
    void networkAllowDowngradesRemoteBackedToACaveat(@TempDir Path temp) throws Exception {
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("test", ONTOLOGY_IRI)
                        + "release:\n  output_dir: out\n  format: turtle\n"
                        + "network:\n  default: allow\n"
                        + "validation:\n  required_stages: [structural]\n  fail_on: error\n");
        ToolContext ctx = ctx(temp, VERSION_IRI, "http://example.org/remote/b.ttl", null);

        Map<String, Object> result = structured(callGate(ctx,
                Map.of("policy_path", policy.toString(), "network", "allow")));

        assertFalse(String.valueOf(result.get("findings")).contains("imports.remote_backed"),
                () -> result.toString());
        assertNotNull(result.get("network_caveat"), () -> result.toString());
        assertEquals("pass", result.get("gate"), () -> result.toString());
    }

    @Test
    void roundTripFailureIsAReleaseGateError(@TempDir Path temp) throws Exception {
        // OBO cannot faithfully round-trip a generic https-IRI ontology, so the verified serialization
        // is not exact and require_clean_round_trip (default true) turns that into a gate error.
        Path policy = writeReleasePolicy(temp, "obo", "[structural]", "");
        ToolContext ctx = ctx(temp, VERSION_IRI, null, null);

        Map<String, Object> result = structured(callGate(ctx,
                Map.of("policy_path", policy.toString())));

        @SuppressWarnings("unchecked")
        Map<String, Object> roundTrip = (Map<String, Object>) result.get("round_trip");
        assertEquals(false, roundTrip.get("clean"), () -> roundTrip.toString());
        assertEquals("error", result.get("gate"), () -> result.toString());
        assertTrue(String.valueOf(result.get("findings")).contains("release.round_trip_failed"),
                () -> result.toString());
    }

    @Test
    void resolvedImportsPresentEvenWithNoPolicy(@TempDir Path temp) throws Exception {
        ToolContext ctx = ctx(temp, VERSION_IRI, null, null);

        Map<String, Object> result = structured(callGate(ctx, Map.of()));

        assertEquals("error", result.get("gate"), () -> result.toString());
        assertTrue(String.valueOf(result.get("findings")).contains("policy_not_found"),
                () -> result.toString());
        assertNotNull(result.get("resolved_imports"));
    }

    @Test
    void baselineManifestArtifactPathsCannotEscapeProjectConfinement(@TempDir Path temp)
            throws Exception {
        // A baseline manifest that lives INSIDE the project but names artifacts OUTSIDE it (an absolute
        // path and a ../ escape) must never be read: no presence/sha256 oracle, no content diff. The
        // policy confines the filesystem (allow_external_paths defaults to false).
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        Path secret = Files.createTempFile("release-secret-", ".ttl");
        Files.writeString(secret, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/secret> a owl:Ontology .\n");
        Path escapeSibling = temp.getParent().resolve("secret-sibling.ttl");
        Files.writeString(escapeSibling, Files.readString(secret));
        try {
            Path baseline = temp.resolve("baseline/manifest.json");
            Files.createDirectories(baseline.getParent());
            // manifestDir is temp/baseline, so "../../secret-sibling.ttl" resolves ABOVE project_root
            // (temp); the absolute path escapes outright.
            Files.writeString(baseline, "{\"manifest_version\":1,"
                    + "\"version_iri\":\"https://example.org/project/0.9.0\","
                    + "\"artifacts\":[{\"path\":\"" + secret.toAbsolutePath() + "\",\"sha256\":\"x\","
                    + "\"bytes\":1},{\"path\":\"../../secret-sibling.ttl\",\"sha256\":\"y\","
                    + "\"bytes\":1}]}");
            ToolContext ctx = ctx(temp, VERSION_IRI, null, null);

            Map<String, Object> result = structured(callGate(ctx, Map.of(
                    "policy_path", policy.toString(), "baseline_manifest", baseline.toString())));

            @SuppressWarnings("unchecked")
            Map<String, Object> baselineSummary = (Map<String, Object>) result.get("baseline");
            // Every escaping entry was refused before any disk access — no presence/hash leak.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checks =
                    (List<Map<String, Object>>) baselineSummary.get("artifact_checks");
            for (Map<String, Object> check : checks) {
                assertEquals(false, check.get("authorized"), () -> check.toString());
                assertFalse(check.containsKey("present"), () -> "presence oracle leaked: " + check);
                assertFalse(check.containsKey("sha256_match"), () -> "hash oracle leaked: " + check);
            }
            // The primary artifact was refused, so nothing was diffed and no diff.json is produced.
            assertEquals(false, baselineSummary.get("compared"), () -> baselineSummary.toString());
            assertEquals("primary_artifact_refused", baselineSummary.get("status"),
                    () -> baselineSummary.toString());
            assertFalse(String.valueOf(result).contains("example.org/secret"),
                    "the out-of-project document content must never be exfiltrated");
        } finally {
            Files.deleteIfExists(secret);
            Files.deleteIfExists(escapeSibling);
        }
    }

    @Test
    void sarifReportUsesARepoRootRelativeUriNotTheAbsolutePolicyPath(@TempDir Path temp)
            throws Exception {
        // A governance namespace warning under fail_on=error is a pass-with-warning: the gate passes
        // but the SARIF report carries the warning result, whose artifactLocation.uri must be the
        // repo-root-relative ontology name — never the absolute policy path (which would leak layout).
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("test", ONTOLOGY_IRI)
                        + "release:\n  output_dir: out\n  format: turtle\n"
                        + "iri_policy:\n  required_namespaces: [https://example.org/allowed/]\n"
                        + "validation:\n  required_stages: [governance]\n  fail_on: error\n");
        ToolContext ctx = ctx(temp, VERSION_IRI, null,
                new McpServerController(new OntologyAccess(null)));

        Map<String, Object> result = structured(callPrepare(ctx,
                Map.of("policy_path", policy.toString(), "dry_run", true)));

        assertEquals("pass", result.get("gate"), () -> result.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> reports = (Map<String, Object>) result.get("reports");
        @SuppressWarnings("unchecked")
        Map<String, Object> sarifBounded = (Map<String, Object>) reports.get("reports/qc.sarif");
        JsonNode root = new ObjectMapper().readTree(String.valueOf(sarifBounded.get("content")));
        JsonNode results = root.get("runs").get(0).get("results");
        assertTrue(results.size() >= 1, () -> String.valueOf(sarifBounded));
        for (JsonNode r : results) {
            String uri = r.get("locations").get(0).get("physicalLocation")
                    .get("artifactLocation").get("uri").asText();
            assertEquals("ontology.ttl", uri, () -> "SARIF uri must be repo-root-relative: " + uri);
            assertFalse(uri.startsWith("/"), () -> "SARIF uri must not be absolute: " + uri);
            assertFalse(uri.contains(temp.toString()),
                    () -> "SARIF uri must not leak the absolute policy path: " + uri);
        }
    }

    // ------------------------------------------------------------------ prepare_release

    @Test
    void prepareReleaseDryRunByDefaultWritesNothing(@TempDir Path temp) throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        Files.createDirectories(temp.resolve("out"));
        ToolContext ctx = ctx(temp, VERSION_IRI, null,
                new McpServerController(new OntologyAccess(null)));

        Map<String, Object> result = structured(callPrepare(ctx,
                Map.of("policy_path", policy.toString())));

        assertEquals(true, result.get("dry_run"), () -> result.toString());
        assertEquals("pass", result.get("gate"));
        assertNotNull(result.get("manifest"));
        assertNotNull(result.get("reports"));
        try (var files = Files.list(temp.resolve("out"))) {
            assertEquals(0, files.count(), "dry run must write nothing into the output directory");
        }
    }

    @Test
    void prepareReleaseRefusesAFailingGateWithNoWrites(@TempDir Path temp) throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        Files.createDirectories(temp.resolve("out"));
        ToolContext ctx = ctx(temp, null, null,
                new McpServerController(new OntologyAccess(null)));

        Map<String, Object> result = structured(callPrepare(ctx,
                Map.of("policy_path", policy.toString(), "dry_run", false)));

        assertEquals(false, result.get("prepared"), () -> result.toString());
        assertEquals("error", result.get("gate"));
        assertTrue(String.valueOf(result.get("findings")).contains("release.version_iri_missing"));
        try (var files = Files.list(temp.resolve("out"))) {
            assertEquals(0, files.count(), "a refused release must not write anything");
        }
    }

    @Test
    void prepareReleaseCommitsEveryArtifactWithDigestsMatchingRecomputation(@TempDir Path temp)
            throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        ToolContext ctx = ctx(temp, VERSION_IRI, null,
                new McpServerController(new OntologyAccess(null)));

        Map<String, Object> result = structured(callPrepare(ctx, Map.of(
                "policy_path", policy.toString(), "dry_run", false,
                "created_at", "2026-07-18T00:00:00Z")));

        assertEquals(true, result.get("prepared"), () -> result.toString());
        Path out = temp.resolve("out");
        assertTrue(Files.isRegularFile(out.resolve("ontology.ttl")));
        assertTrue(Files.isRegularFile(out.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(out.resolve("reports/qc.json")));
        assertTrue(Files.isRegularFile(out.resolve("reports/qc.md")));
        assertTrue(Files.isRegularFile(out.resolve("reports/qc.xml")));
        assertTrue(Files.isRegularFile(out.resolve("reports/qc.sarif")));
        assertTrue(Files.isRegularFile(out.resolve("ro-crate-metadata.json")));

        // Every artifact digest recorded in the manifest must match a fresh hash of the written file.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(out.resolve("manifest.json").toFile());
        for (JsonNode artifact : manifest.get("artifacts")) {
            Path file = out.resolve(artifact.get("path").asText());
            assertTrue(Files.isRegularFile(file), () -> "missing artifact " + file);
            assertEquals(ArtifactStore.sha256(file), artifact.get("sha256").asText(),
                    () -> "digest drift for " + file);
        }
        // A second identical run is byte-identical (deterministic given the same created_at).
        String first = Files.readString(out.resolve("manifest.json"));
        structured(callPrepare(ctx, Map.of("policy_path", policy.toString(), "dry_run", false,
                "created_at", "2026-07-18T00:00:00Z")));
        assertEquals(first, Files.readString(out.resolve("manifest.json")));
    }

    @Test
    void prepareReleaseRefusesAnOutputDirectoryOutsideTheProject(@TempDir Path temp) throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        Path escape = Files.createTempDirectory("release-escape-");
        try {
            ToolContext ctx = ctx(temp, VERSION_IRI, null,
                    new McpServerController(new OntologyAccess(null)));

            CallToolResult result = callPrepare(ctx, Map.of(
                    "policy_path", policy.toString(), "dry_run", false,
                    "output_dir", escape.toString()));

            assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
            assertTrue(String.valueOf(structured(result).get("error")).contains("project_root"));
            try (var files = Files.list(escape)) {
                assertEquals(0, files.count(), "nothing may be written outside the project");
            }
        } finally {
            Files.deleteIfExists(escape);
        }
    }

    @Test
    void prepareReleaseIsRefusedInReadOnlyMode(@TempDir Path temp) throws Exception {
        Path policy = writeReleasePolicy(temp, "turtle", "[structural]", "");
        Files.createDirectories(temp.resolve("out"));
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);
        ToolContext ctx = ctx(temp, VERSION_IRI, null,
                new McpServerController(new OntologyAccess(null)));

        CallToolResult result = callPrepare(ctx,
                Map.of("policy_path", policy.toString(), "dry_run", false));

        assertEquals(Boolean.TRUE, result.isError(), () -> String.valueOf(result.structuredContent()));
        assertTrue(String.valueOf(structured(result).get("error")).toLowerCase().contains("read-only"));
        try (var files = Files.list(temp.resolve("out"))) {
            assertEquals(0, files.count(), "read-only mode must write nothing");
        }
    }

    // ------------------------------------------------------------------ unit pins

    @Test
    void qcMapToGateResultAdapterReproducesTheQcVerdict() {
        String fp = "sha256:" + "a".repeat(64);
        // Passing QC → a passing release gate (the added required "release" stage is clean).
        GateResult pass = ReleaseGate.aggregate(qcMap(fp, "pass",
                stage("structural", "pass")), List.of(), "pass");
        assertEquals(GateStatus.PASS, pass.gate());

        // Failing QC stage propagates to gate=fail.
        GateResult fail = ReleaseGate.aggregate(qcMap(fp, "fail",
                stage("structural", "fail")), List.of(), "fail");
        assertEquals(GateStatus.FAIL, fail.gate());

        // A release-specific ERROR finding forces gate=error even over a passing QC map.
        GateResult error = ReleaseGate.aggregate(qcMap(fp, "pass", stage("structural", "pass")),
                List.of(new Finding("release.version_iri_missing", "release", FindingSeverity.ERROR,
                        "no version IRI", null, null, null, "release.version_iri_missing", null,
                        Map.of())),
                "pass");
        assertEquals(GateStatus.ERROR, error.gate());
    }

    @Test
    void provenanceClassifierLabelsLocalRemoteAndMemoryMembers() {
        Map<String, Object> root = member("root", "root", "local", ONTOLOGY_IRI);
        List<Map<String, Object>> ontologies = new ArrayList<>();
        ontologies.add(root);
        ontologies.add(member("direct", "local", "local", "https://example.org/local"));
        ontologies.add(member("direct", "remote", "remote", "https://example.org/remote"));
        ontologies.add(member("transitive", "memory", "memory", "https://example.org/memory"));
        ImportTools.ImportReport report = new ImportTools.ImportReport(root, ontologies, List.of(),
                List.of(), List.of(), List.of(), List.of(), Map.of());

        List<Map<String, Object>> members = ReleaseGate.provenance(report);

        assertEquals(3, members.size(), "the root ontology is excluded from the closure members");
        assertEquals("local_file", members.get(0).get("backed_by"));
        assertEquals("remote", members.get(1).get("backed_by"));
        assertEquals("memory", members.get(2).get("backed_by"));
    }

    // ------------------------------------------------------------------ fixtures / helpers

    private static Map<String, Object> member(String role, String ref, String sourceType,
            String iri) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("ontology_iri", iri);
        row.put("version_iri", null);
        row.put("document_iri", sourceType.equals("local") ? "file:///tmp/" + ref + ".ttl"
                : "http://example.org/" + ref);
        row.put("source_type", sourceType);
        return row;
    }

    private static Map<String, Object> stage(String name, String status) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage", name);
        stage.put("status", status);
        List<Map<String, Object>> findings = new ArrayList<>();
        if (!"pass".equals(status)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", name + ".finding");
            f.put("source", name);
            f.put("severity", "error");
            f.put("message", name + " reported " + status);
            f.put("rule_id", name + ".finding");
            f.put("details", Map.of());
            findings.add(f);
        }
        stage.put("findings", findings);
        stage.put("details", Map.of());
        return stage;
    }

    @SafeVarargs
    private static Map<String, Object> qcMap(String fingerprint, String gate,
            Map<String, Object>... stages) {
        Map<String, Object> qc = new LinkedHashMap<>();
        qc.put("gate", gate);
        qc.put("policy_version", 1);
        qc.put("semantic_fingerprint", fingerprint);
        List<String> required = new ArrayList<>();
        List<Map<String, Object>> stageList = new ArrayList<>();
        for (Map<String, Object> stage : stages) {
            required.add(String.valueOf(stage.get("stage")));
            stageList.add(stage);
        }
        qc.put("required_stages", required);
        qc.put("stages", stageList);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> stage : stages) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sf = (List<Map<String, Object>>) stage.get("findings");
            findings.addAll(sf);
        }
        qc.put("findings", findings);
        qc.put("details", Map.of("fail_on", "error"));
        return qc;
    }

    /** minimalPolicy + a release block, with the given format and required stages. */
    private static Path writeReleasePolicy(Path temp, String format, String stages, String extra)
            throws Exception {
        Path policy = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(policy,
                ProjectPolicyFixtures.minimalPolicy("test", ONTOLOGY_IRI)
                        + "release:\n  output_dir: out\n  format: " + format + "\n"
                        + "validation:\n  required_stages: " + stages + "\n  fail_on: error\n"
                        + extra);
        return policy;
    }

    private ToolContext ctx(Path temp, String versionIri, String importDocumentIri,
            McpServerController controller) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntologyID id = versionIri == null
                ? new OWLOntologyID(IRI.create(ONTOLOGY_IRI))
                : new OWLOntologyID(IRI.create(ONTOLOGY_IRI), IRI.create(versionIri));
        OWLOntology active = manager.createOntology(id);
        manager.setOntologyFormat(active, new TurtleDocumentFormat());
        manager.setOntologyDocumentIRI(active, IRI.create(temp.resolve("ontology.ttl").toUri()));
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(active, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create(ONTOLOGY_IRI + "#Thing"))));
        manager.addAxiom(active, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                IRI.create(ONTOLOGY_IRI + "#Thing"), df.getOWLLiteral("Thing")));
        if (importDocumentIri != null) {
            String importIri = "https://example.org/imported";
            OWLOntology imported = manager.createOntology(IRI.create(importIri));
            manager.setOntologyDocumentIRI(imported, IRI.create(importDocumentIri));
            manager.applyChange(new AddImport(active,
                    df.getOWLImportsDeclaration(IRI.create(importIri))));
        }
        return new ToolContext(HeadlessAccess.over(FakeModelManager.over(active)), controller);
    }

    private static CallToolResult callGate(ToolContext ctx, Map<String, Object> args) {
        return call(ctx, "run_release_gate", args);
    }

    private static CallToolResult callPrepare(ToolContext ctx, Map<String, Object> args) {
        return call(ctx, "prepare_release", args);
    }

    private static CallToolResult call(ToolContext ctx, String name, Map<String, Object> args) {
        ToolRegistry registry = new ToolRegistry();
        ReleaseTools.register(registry, ctx);
        for (SyncToolSpecification spec : registry.build()) {
            if (spec.tool().name().equals(name)) {
                return spec.callHandler().apply(null, new CallToolRequest(name, args));
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }
}
