package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the SHACL engine core ({@link ShaclTools#validate}). Pure Jena over a serialised data
 * snapshot + an inline shapes graph — no Protégé — so the constraint-validation behaviour is verified
 * without a live workspace.
 */
class ShaclToolsTest {

    private static final String SHAPES = ""
            + "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix ex: <http://ex/> .\n"
            + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
            + "ex:PersonShape a sh:NodeShape ;\n"
            + "  sh:targetClass ex:Person ;\n"
            + "  sh:property [ sh:path ex:age ; sh:minCount 1 ; sh:datatype xsd:integer ] .\n";

    private static byte[] data(String extra) {
        String ttl = "@prefix ex: <http://ex/> .\n"
                + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                + "ex:bob a ex:Person ; ex:age 30 .\n"
                + extra;
        return ttl.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("results");
    }

    @Test
    void reportsAViolationForAMissingRequiredProperty() {
        // alice is a Person with no ex:age → violates sh:minCount 1.
        byte[] data = data("ex:alice a ex:Person .\n");
        Map<String, Object> r = ShaclTools.validate(data, SHAPES, null, 1000);

        assertEquals(false, r.get("conforms"), "a missing required property must not conform");
        assertTrue(((Number) r.get("violations")).intValue() >= 1, "at least one sh:Violation");
        assertEquals("Violation", r.get("worst_severity"));
        boolean aliceFlagged = results(r).stream()
                .anyMatch(row -> "http://ex/alice".equals(row.get("focus_node")));
        assertTrue(aliceFlagged, "the offending focus node is reported");
        // The reported result carries the constraint context.
        Map<String, Object> first = results(r).get(0);
        assertEquals("Violation", first.get("severity"));
        assertTrue(first.containsKey("result_path"), "the failing property path is reported");
    }

    @Test
    void conformsWhenEveryTargetSatisfiesTheShape() {
        // Only bob (who has an age) is a Person → conforms.
        Map<String, Object> r = ShaclTools.validate(data(""), SHAPES, null, 1000);
        assertEquals(true, r.get("conforms"));
        assertEquals(0, ((Number) r.get("violations")).intValue());
        assertTrue(results(r).isEmpty());
    }

    @Test
    void multipleMessagesOnAShapeCountAsOneViolation() {
        // A constraint with two sh:message values (multilingual) sets two sh:resultMessage on the result;
        // the report scrape must fold them, not multiply the violation count/rows (adversarial-review bug).
        String shapes = ""
                + "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <http://ex/> .\n"
                + "ex:PersonShape a sh:NodeShape ;\n"
                + "  sh:targetClass ex:Person ;\n"
                + "  sh:property [ sh:path ex:age ; sh:minCount 1 ;\n"
                + "                sh:message \"age is required\"@en , \"l age est requis\"@fr ] .\n";
        byte[] data = data("ex:alice a ex:Person .\n");
        Map<String, Object> r = ShaclTools.validate(data, shapes, null, 1000);
        assertEquals(1, ((Number) r.get("violations")).intValue(), "one focus node → exactly one violation");
        assertEquals(1, results(r).size(), "multilingual messages must not duplicate the result row");
    }

    @Test
    void findingIdentitiesAreStableAcrossTwoParsesOfTheSameShapesDocument() {
        // SHAPES uses an ANONYMOUS property shape: each validate() call re-parses the document,
        // and Jena mints fresh parse-local blank-node labels every parse. Those labels must never
        // reach the finding identities (identity_digest / the identitiesOut side channel), or
        // identical findings on the two semantic_diff sides — and across the verified-apply
        // baseline re-run — would look different (phantom entered/left rows).
        byte[] data = data("ex:alice a ex:Person .\n");
        List<String> firstIdentities = new java.util.ArrayList<>();
        List<String> secondIdentities = new java.util.ArrayList<>();
        Map<String, Object> first = ShaclTools.validate(data, SHAPES, null, 1000, 0L, firstIdentities);
        Map<String, Object> second = ShaclTools.validate(data, SHAPES, null, 1000, 0L, secondIdentities);

        assertFalse(firstIdentities.isEmpty(), "the violation must produce a gating identity");
        assertEquals(new java.util.TreeSet<>(firstIdentities), new java.util.TreeSet<>(secondIdentities),
                "parse-local blank-node labels must not leak into finding identities");
        assertEquals(first.get("identity_digest"), second.get("identity_digest"),
                "the public digest must be parse-stable too");
        // The volatile label may still appear in the human-facing result row.
        assertTrue(results(first).get(0).containsKey("source_shape"));
    }

    @Test
    void malformedShapesGraphIsAToolArgError() {
        // A shapes document that is not valid Turtle is a caller error, not a crash.
        assertFalse(isValidShapes("this is not turtle @@@"));
    }

    private static boolean isValidShapes(String shapes) {
        try {
            ShaclTools.validate(data(""), shapes, null, 1000);
            return true;
        } catch (ToolArgException e) {
            return false;
        }
    }

    @Test
    void remoteShapesPathIsRejectedForOfflineSafety() {
        ToolArgException e = org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> ShaclTools.validate(data(""), null, "https://example.org/shapes.ttl", 1000));
        assertTrue(e.getMessage().toLowerCase().contains("local"));
    }

    @Test
    void fileUrlSchemeShapesPathIsRejected() {
        // Any URL scheme (not just http/https) is refused — the raw string never reaches Jena's resolver.
        ToolArgException e = org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> ShaclTools.validate(data(""), null, "file:///tmp/shapes.ttl", 1000));
        assertTrue(e.getMessage().toLowerCase().contains("local"));
    }

    @Test
    void nonexistentShapesPathIsRejected() {
        ToolArgException e = org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> ShaclTools.validate(data(""), null, "/no/such/shapes-file-xyz.ttl", 1000));
        assertTrue(e.getMessage().toLowerCase().contains("local file"));
    }

    @Test
    void loadsShapesFromARealLocalFile(@TempDir Path dir) throws Exception {
        Path shapesFile = dir.resolve("shapes.ttl");
        Files.writeString(shapesFile, SHAPES);
        Map<String, Object> r = ShaclTools.validate(data("ex:alice a ex:Person .\n"), null,
                shapesFile.toString(), 1000);
        assertEquals(false, r.get("conforms"));
        assertTrue(((Number) r.get("violations")).intValue() >= 1, "a real local shapes file is loaded + applied");
    }

    @Test
    void boundedValidationMatchesUnbounded() {
        byte[] data = data("ex:alice a ex:Person .\n");
        Map<String, Object> unbounded = ShaclTools.validate(data, SHAPES, null, 1000);          // timeoutMs 0
        Map<String, Object> bounded = ShaclTools.validate(data, SHAPES, null, 1000, 120_000L);  // bounded worker
        assertEquals(unbounded.get("conforms"), bounded.get("conforms"));
        assertEquals(unbounded.get("violations"), bounded.get("violations"));
    }

    @Test
    void policyShapeFilesAreUnionedAcrossFormats(@TempDir Path dir) throws Exception {
        Path age = dir.resolve("age.ttl");
        Files.writeString(age, SHAPES);
        Path name = dir.resolve("name.rdf");
        Files.writeString(name, "<?xml version=\"1.0\"?>\n"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:sh=\"http://www.w3.org/ns/shacl#\" xmlns:ex=\"http://ex/\">\n"
                + "<sh:NodeShape rdf:about=\"http://ex/NameShape\">\n"
                + "<sh:targetClass rdf:resource=\"http://ex/Person\"/>\n"
                + "<sh:property><sh:PropertyShape><sh:path rdf:resource=\"http://ex/name\"/>"
                + "<sh:minCount rdf:datatype=\"http://www.w3.org/2001/XMLSchema#integer\">1</sh:minCount>"
                + "</sh:PropertyShape></sh:property></sh:NodeShape></rdf:RDF>\n");
        Map<String, Object> result = ShaclTools.validate(data("ex:alice a ex:Person .\n"),
                List.of(age, name), 1000, 120_000);
        assertEquals(false, result.get("conforms"));
        assertTrue(((Number) result.get("violations")).intValue() >= 2,
                "constraints from both files must participate");
    }

    @Test
    void malformedFileInPolicyShapeSetFailsTheWholeStage(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.ttl");
        Path bad = dir.resolve("bad.ttl");
        Files.writeString(good, SHAPES);
        Files.writeString(bad, "not turtle @@@");
        assertThrows(ToolArgException.class,
                () -> ShaclTools.validate(data(""), List.of(good, bad), 1000, 120_000));
    }
}
