package io.github.hakjuoh.protege_mcp.core.qc;

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

class ShaclValidationServiceTest {

    private static final String SHAPES = ""
            + "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
            + "@prefix ex: <http://ex/> .\n"
            + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
            + "ex:PersonShape a sh:NodeShape ; sh:targetClass ex:Person ;\n"
            + "  sh:property [ sh:path ex:age ; sh:minCount 1 ; sh:datatype xsd:integer ] .\n";

    @Test
    void reportsViolationsAndConformance() {
        ShaclValidationService.Validation failing = ShaclValidationService.validate(
                data("ex:alice a ex:Person .\n"), SHAPES, null, 1000, 0L);
        ShaclValidationService.Validation passing = ShaclValidationService.validate(
                data(""), SHAPES, null, 1000, 0L);

        assertEquals(false, failing.report().get("conforms"));
        assertEquals("Violation", failing.report().get("worst_severity"));
        assertFalse(failing.gatingIdentities().isEmpty());
        assertTrue(results(failing).stream()
                .anyMatch(row -> "http://ex/alice".equals(row.get("focus_node"))));
        assertEquals(true, passing.report().get("conforms"));
        assertEquals(0, passing.report().get("violations"));
    }

    @Test
    void anonymousShapeIdentitiesAreStableAcrossParses() {
        byte[] data = data("ex:alice a ex:Person .\n");

        ShaclValidationService.Validation first = ShaclValidationService.validate(
                data, SHAPES, null, 1000, 0L);
        ShaclValidationService.Validation second = ShaclValidationService.validate(
                data, SHAPES, null, 1000, 0L);

        assertEquals(first.gatingIdentities(), second.gatingIdentities());
        assertEquals(first.report().get("identity_digest"),
                second.report().get("identity_digest"));
        assertTrue(results(first).get(0).containsKey("source_shape"));
    }

    @Test
    void multilingualMessagesDoNotMultiplyResults() {
        String shapes = ""
                + "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <http://ex/> .\n"
                + "ex:S a sh:NodeShape ; sh:targetClass ex:Person ;\n"
                + " sh:property [ sh:path ex:age ; sh:minCount 1 ;\n"
                + " sh:message \"age required\"@en, \"age requis\"@fr ] .\n";

        ShaclValidationService.Validation validation = ShaclValidationService.validate(
                data("ex:alice a ex:Person .\n"), shapes, null, 1000, 0L);

        assertEquals(1, validation.report().get("violations"));
        assertEquals(1, results(validation).size());
    }

    @Test
    void rejectsRemoteAndFileUrlShapePaths() {
        var remote = assertThrows(ShaclValidationService.ValidationException.class,
                () -> ShaclValidationService.validate(data(""), null,
                        "https://example.org/shapes.ttl", 1000, 0L));
        var fileUrl = assertThrows(ShaclValidationService.ValidationException.class,
                () -> ShaclValidationService.validate(data(""), null,
                        "file:///tmp/shapes.ttl", 1000, 0L));

        assertTrue(remote.getMessage().contains("LOCAL"));
        assertTrue(fileUrl.getMessage().contains("LOCAL"));
    }

    @Test
    void policyShapeFilesAreUnionedAndMalformedMembersFail(@TempDir Path directory)
            throws Exception {
        Path age = directory.resolve("age.ttl");
        Files.writeString(age, SHAPES);
        Path name = directory.resolve("name.ttl");
        Files.writeString(name, ""
                + "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <http://ex/> .\n"
                + "ex:N a sh:NodeShape ; sh:targetClass ex:Person ;\n"
                + " sh:property [ sh:path ex:name ; sh:minCount 1 ] .\n");

        ShaclValidationService.Validation validation = ShaclValidationService.validate(
                data("ex:alice a ex:Person .\n"), List.of(age, name), 1000, 10_000L);
        assertTrue(((Number) validation.report().get("violations")).intValue() >= 2);

        Path malformed = directory.resolve("bad.ttl");
        Files.writeString(malformed, "not turtle @@@");
        assertThrows(ShaclValidationService.ValidationException.class,
                () -> ShaclValidationService.validate(data(""), List.of(age, malformed),
                        1000, 10_000L));
    }

    @Test
    void projectsWarningAndInfoSeveritiesWithoutFailingThem() {
        String warning = severityShapes("sh:Warning");
        String info = severityShapes("sh:Info");

        ShaclValidationService.Result warningResult = ShaclValidationService.evaluate(
                data("ex:alice a ex:Person .\n"), warning, null, 1000, 0L);
        ShaclValidationService.Result infoResult = ShaclValidationService.evaluate(
                data("ex:alice a ex:Person .\n"), info, null, 1000, 0L);

        assertEquals(QcStageVerdict.WARNING, warningResult.execution().verdict());
        assertEquals(QcStageVerdict.INFO, infoResult.execution().verdict());
        assertFalse(warningResult.gatingIdentities().isEmpty());
        assertTrue(infoResult.gatingIdentities().isEmpty());
    }

    @Test
    void distinguishesOptionalAndStrictMissingInputs() {
        ShaclValidationService.Result noShapes = ShaclValidationService.evaluate(
                data(""), null, null, 1000, 0L);
        ShaclValidationService.Result optionalData = ShaclValidationService.evaluate(
                null, SHAPES, null, 1000, 0L);
        ShaclValidationService.Result strictData = ShaclValidationService.evaluatePolicy(
                null, List.of(Path.of("shapes.ttl")), 1000, 0L);

        assertEquals(QcStageVerdict.SKIPPED, noShapes.execution().verdict());
        assertEquals(QcStageVerdict.SKIPPED, optionalData.execution().verdict());
        assertEquals(QcStageVerdict.ERROR, strictData.execution().verdict());
    }

    @Test
    void restoresTheCallingThreadClassLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ClassLoader marker = new ClassLoader() { };
        thread.setContextClassLoader(marker);
        try {
            ShaclValidationService.validate(data(""), SHAPES, null, 1000, 0L);
            assertEquals(marker, thread.getContextClassLoader());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void blankExceptionMessagesStillProduceUsableStageErrors() {
        RuntimeException blank = new RuntimeException("   ");

        assertEquals("RuntimeException", ShaclValidationService.message(blank));
        QcStageExecution execution = QcStageExecution.error("shacl",
                ShaclValidationService.message(blank), Map.of());
        assertEquals(QcStageVerdict.ERROR, execution.verdict());
    }

    private static String severityShapes(String severity) {
        return ""
                + "@prefix sh: <http://www.w3.org/ns/shacl#> .\n"
                + "@prefix ex: <http://ex/> .\n"
                + "ex:S a sh:NodeShape ; sh:targetClass ex:Person ;\n"
                + " sh:property [ sh:path ex:age ; sh:minCount 1 ; sh:severity "
                + severity + " ] .\n";
    }

    private static byte[] data(String extra) {
        return ("@prefix ex: <http://ex/> .\n"
                + "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n"
                + "ex:bob a ex:Person ; ex:age 30 .\n" + extra)
                .getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(
            ShaclValidationService.Validation validation) {
        return (List<Map<String, Object>>) validation.report().get("results");
    }
}
