package io.github.hakjuoh.protege_mcp.core.release;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.FindingSeverity;
import io.github.hakjuoh.protege_mcp.contracts.GateResult;
import io.github.hakjuoh.protege_mcp.contracts.StageResult;
import io.github.hakjuoh.protege_mcp.contracts.StageStatus;

class ReleaseBundleServiceTest {

    private static final String FP = "sha256:" + "a".repeat(64);
    private static final String CREATED = "2026-07-19T12:00:00Z";

    @Test
    void buildsAnAcyclicChecksummedBundleWithBaselineEvidence() {
        ReleaseBundleService.Bundle bundle = ReleaseBundleService.build(request(
                Map.of("identical", false), "https://example.org/ontology/0.9.0"));

        assertEquals(List.of("ontology.ttl", "reports/qc.json", "reports/qc.md",
                "reports/qc.xml", "reports/qc.sarif", "reports/diff.json",
                "ro-crate-metadata.json", "manifest.json"),
                bundle.artifacts().stream().map(ReleaseBundleService.Artifact::path).toList());
        Map<String, ReleaseBundleService.Artifact> byPath = bundle.artifacts().stream()
                .collect(Collectors.toMap(ReleaseBundleService.Artifact::path, value -> value,
                        (left, right) -> left, LinkedHashMap::new));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> manifestArtifacts =
                (List<Map<String, Object>>) bundle.manifest().get("artifacts");
        assertEquals(bundle.artifacts().size() - 1, manifestArtifacts.size());
        assertFalse(manifestArtifacts.stream()
                .anyMatch(row -> "manifest.json".equals(row.get("path"))));
        for (Map<String, Object> row : manifestArtifacts) {
            ReleaseBundleService.Artifact actual = byPath.get(row.get("path"));
            assertEquals(actual.sha256(), row.get("sha256"));
            assertEquals(actual.bytes(), ((Number) row.get("bytes")).longValue());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> baseline = (Map<String, Object>) bundle.manifest().get("baseline");
        assertEquals("https://example.org/ontology/0.9.0", baseline.get("version_iri"));
        assertEquals("reports/diff.json", baseline.get("report"));
    }

    @Test
    void omitsDiffAndBaselineTogether() {
        ReleaseBundleService.Bundle bundle = ReleaseBundleService.build(request(null, null));

        assertFalse(bundle.artifacts().stream()
                .anyMatch(artifact -> "reports/diff.json".equals(artifact.path())));
        assertFalse(bundle.manifest().containsKey("baseline"));
        assertEquals(7, bundle.artifacts().size());
    }

    @Test
    void isDeterministicForTheSameClockInput() {
        ReleaseBundleService.Bundle first = ReleaseBundleService.build(request(null, null));
        ReleaseBundleService.Bundle second = ReleaseBundleService.build(request(null, null));

        assertEquals(first.manifest(), second.manifest());
        for (int index = 0; index < first.artifacts().size(); index++) {
            assertEquals(first.artifacts().get(index).path(), second.artifacts().get(index).path());
            assertArrayEquals(first.artifacts().get(index).content(),
                    second.artifacts().get(index).content());
        }
    }

    @Test
    void artifactBytesAreDefensive() {
        ReleaseBundleService.Artifact ontology =
                ReleaseBundleService.build(request(null, null)).artifacts().get(0);
        byte[] expected = ontology.content();
        byte[] mutation = ontology.content();
        mutation[0] ^= 0x7f;

        assertArrayEquals(expected, ontology.content());
        assertEquals(ArtifactStore.sha256(ontology.content()), ontology.sha256());
    }

    @Test
    void refusesEscapingNestedAndReservedOntologyArtifactPaths() {
        for (String path : List.of("../ontology.ttl", "nested/ontology.ttl", "manifest.json",
                "MANIFEST.JSON", "ro-crate-metadata.json", "Reports", "C:\\ontology.ttl",
                "NUL.ttl", "COM1", "ontology.ttl.", "ontology.ttl ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> request(null, null, path), path);
        }
    }

    private static ReleaseBundleService.Request request(Map<String, Object> baseline,
            String baselineVersion) {
        return request(baseline, baselineVersion, "ontology.ttl");
    }

    private static ReleaseBundleService.Request request(Map<String, Object> baseline,
            String baselineVersion, String artifactPath) {
        byte[] ontology = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                .getBytes(StandardCharsets.UTF_8);
        return new ReleaseBundleService.Request("project", "https://example.org/ontology",
                "https://example.org/ontology/1.0.0", CREATED,
                "https://www.apache.org/licenses/LICENSE-2.0", FP, null, FP, true,
                gate(), ontology, artifactPath, "text/turtle", baseline,
                baselineVersion, 50);
    }

    private static GateResult gate() {
        StageResult stage = new StageResult("release", StageStatus.PASS, null,
                List.of(), Map.of());
        return GateResult.aggregate(1, FP, List.of("release"), List.of(stage),
                FindingSeverity.ERROR);
    }
}
