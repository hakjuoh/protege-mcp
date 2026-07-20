package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.owl.EntityGrounding;

/** Pins the headless half of the shared plugin/CLI conformance fixture. */
class CrossSurfaceConformanceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIXTURE_FILES = List.of(
            "project.yaml", "ontology.ttl", "module.ttl", "catalog-v001.xml",
            "ro-crate-metadata.json");

    @Test
    void headlessSurfaceMatchesTheCommittedMachineEvidence(@TempDir Path temp) throws Exception {
        Path policy = copyFixture(temp);

        Map<String, Object> qc = invoke("validate", "--project", policy.toString(),
                "--format", "json", "--no-network", "--no-external");
        Map<String, Object> release = invoke("release", "--project", policy.toString(),
                "--dry-run", "--no-network", "--no-external");
        Map<String, Object> lock = invoke("imports", "lock", "--project", policy.toString(),
                "--dry-run", "--no-network");

        Map<String, Object> actual = evidence(qc, release, lock);
        actual.put("grounding", Map.of(
                "curie", EntityGrounding.iri("ex:Term",
                        Map.of("ex", "https://example.org/conformance#")).toString(),
                "iri", EntityGrounding.iri(
                        "https://example.org/conformance#Term", Map.of()).toString()));
        assertEquals(expected(), actual, () -> pretty(actual));
    }

    private static Map<String, Object> evidence(Map<String, Object> qc,
            Map<String, Object> release, Map<String, Object> lock) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("qc", qcEvidence(qc));
        result.put("release", releaseEvidence(release));
        result.put("lock", lockEvidence(lock, "candidate"));
        return result;
    }

    private static Map<String, Object> qcEvidence(Map<String, Object> qc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate", qc.get("gate"));
        out.put("semantic_fingerprint", qc.get("semantic_fingerprint"));
        out.put("closure_fingerprint", map(qc.get("validation_snapshot"))
                .get("closure_fingerprint"));
        out.put("reasoner", qc.get("reasoner"));
        List<Map<String, Object>> stages = new ArrayList<>();
        String reasonerFactory = null;
        for (Object value : list(qc.get("stages"))) {
            Map<String, Object> row = map(value);
            stages.add(Map.of("stage", row.get("stage"), "status", row.get("status")));
            if ("reasoner".equals(row.get("stage"))) {
                reasonerFactory = String.valueOf(map(map(row.get("details"))
                        .get("reasoner_configuration")).get("factory_class"));
            }
        }
        out.put("stages", stages);
        out.put("reasoner_factory", reasonerFactory);
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Object value : list(qc.get("findings"))) {
            Map<String, Object> row = map(value);
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("id", row.get("id"));
            finding.put("source", row.get("source"));
            finding.put("severity", row.get("severity"));
            finding.put("focus_iri", row.get("focus_iri"));
            findings.add(finding);
        }
        findings.sort(Comparator.comparing((Map<String, Object> value) ->
                String.valueOf(value.get("id")))
                .thenComparing(value -> String.valueOf(value.get("focus_iri"))));
        out.put("findings", findings);
        return out;
    }

    private static Map<String, Object> releaseEvidence(Map<String, Object> release) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate", release.get("gate"));
        out.put("version_iri", release.get("version_iri"));
        Map<String, Object> artifacts = new LinkedHashMap<>();
        List<String> artifactPaths = new ArrayList<>();
        for (Object value : list(release.get("artifacts"))) {
            Map<String, Object> row = map(value);
            String path = String.valueOf(row.get("path"));
            artifactPaths.add(path);
            if (List.of("ontology.ttl", "reports/qc.md", "reports/qc.xml",
                    "reports/qc.sarif").contains(path)) {
                artifacts.put(path, row.get("sha256"));
            }
        }
        artifactPaths.sort(String::compareTo);
        out.put("artifact_count", artifactPaths.size());
        out.put("artifact_paths", artifactPaths);
        out.put("artifacts", artifacts);
        Map<String, Object> manifest = map(release.get("manifest"));
        out.put("manifest_semantic_fingerprint", manifest.get("semantic_fingerprint"));
        out.put("manifest_version_iri", manifest.get("version_iri"));
        return out;
    }

    private static Map<String, Object> lockEvidence(Map<String, Object> lock, String contentKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sha256", lock.get("sha256"));
        out.put("entry_count", lock.get("entry_count"));
        out.put("content", lock.get(contentKey));
        return out;
    }

    private static Path copyFixture(Path temp) throws IOException {
        ClassLoader loader = CrossSurfaceConformanceTest.class.getClassLoader();
        for (String name : FIXTURE_FILES) {
            try (InputStream input = loader.getResourceAsStream("conformance/v1/" + name)) {
                if (input == null) throw new IOException("missing conformance fixture " + name);
                Files.copy(input, temp.resolve(name));
            }
        }
        return temp.resolve("project.yaml");
    }

    private static Map<String, Object> invoke(String... arguments) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(arguments,
                new PrintStream(out, false, StandardCharsets.UTF_8),
                new PrintStream(err, false, StandardCharsets.UTF_8));
        assertEquals(0, exit, () -> err.toString(StandardCharsets.UTF_8)
                + out.toString(StandardCharsets.UTF_8));
        return JSON.readValue(out.toByteArray(),
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static Map<String, Object> expected() throws IOException {
        try (InputStream input = CrossSurfaceConformanceTest.class.getClassLoader()
                .getResourceAsStream("conformance/v1/expected.json")) {
            if (input == null) throw new IOException("missing conformance expected.json");
            return JSON.readValue(input,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static String pretty(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException impossible) {
            return String.valueOf(value);
        }
    }
}
