package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;

/** Unit and adversarial tests for the surface-neutral M0 contract records. */
class ContractRecordsTest {

    private static final ObjectMapper JSON = ContractJson.mapper();
    private static final DefaultJsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(JSON);
    private static final String WORKSPACE = "8a15d9e8-828f-4d94-9d45-0a31e92d28eb";
    private static final String HASH_A = "sha256:" + "a".repeat(64);
    private static final String HASH_B = "sha256:" + "b".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static Map<String, Object> schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = ContractRecordsTest.class.getResourceAsStream(
                "/schema/ontology-engineering-contracts-v1.schema.json")) {
            schema = JSON.readValue(in, new TypeReference<>() { });
        }
        ValidationResponse response = VALIDATOR.validateSchema(schema);
        assertTrue(response.valid(), response::errorMessage);
    }

    @Test
    void recordsRoundTripAsSnakeCaseAndConformToThePublishedJsonContract() throws Exception {
        ProjectCoordinates project = new ProjectCoordinates("example", 1,
                "https://example.org/ontology", List.of("https://example.org/core"));
        ModelRevision revision = new ModelRevision(WORKSPACE, 42, HASH_A, HASH_B);
        OntologyFingerprint fingerprint = new OntologyFingerprint(1, HASH_A, HASH_B,
                "cross_restart", true, List.of());
        Finding finding = warning();
        ArtifactReference artifact = new ArtifactReference("reports/qc.json", DIGEST_B, 123,
                Map.of("media_type", "application/json"));
        StageResult stage = new StageResult("governance", StageStatus.FAIL, null,
                List.of(finding), Map.of("checked", 3));
        GateResult gate = GateResult.aggregate(1, HASH_A, List.of("governance"),
                List.of(stage), FindingSeverity.WARNING);

        assertRoundTrip(project, ProjectCoordinates.class);
        assertRoundTrip(revision, ModelRevision.class);
        assertRoundTrip(fingerprint, OntologyFingerprint.class);
        assertRoundTrip(finding, Finding.class);
        assertRoundTrip(artifact, ArtifactReference.class);
        assertRoundTrip(stage, StageResult.class);
        assertRoundTrip(gate, GateResult.class);

        JsonNode revisionJson = JSON.valueToTree(revision);
        assertTrue(revisionJson.has("workspace_id"));
        assertTrue(revisionJson.has("semantic_fingerprint"));
        assertFalse(revisionJson.has("workspaceId"));
    }

    @Test
    void warningBelowErrorThresholdPassesButWarningThresholdFails() {
        StageResult governance = StageResult.pass("governance", List.of(warning()));
        GateResult lenient = GateResult.aggregate(1, HASH_A, List.of("governance"),
                List.of(governance), FindingSeverity.ERROR);
        GateResult strict = GateResult.aggregate(1, HASH_A, List.of("governance"),
                List.of(governance), FindingSeverity.WARNING);

        assertEquals(GateStatus.PASS, lenient.gate());
        assertEquals(GateStatus.FAIL, strict.gate());
        assertEquals("warning", strict.details().get("fail_on"));
    }

    @Test
    void explicitStageFailureFailsEvenWhenItsFindingListIsEmpty() {
        GateResult result = GateResult.aggregate(1, HASH_A, List.of("profile"),
                List.of(StageResult.fail("profile", Collections.emptyList())), FindingSeverity.ERROR);
        assertEquals(GateStatus.FAIL, result.gate());
    }

    @Test
    void missingSkippedAndErroredRequiredStagesAreErrorsNeverPassesOrPolicyFailures() {
        GateResult missing = GateResult.aggregate(1, HASH_A, List.of("reasoner"),
                Collections.emptyList(), FindingSeverity.WARNING);
        assertEquals(GateStatus.ERROR, missing.gate());
        assertEquals(0, missing.stagesRan());
        assertTrue(missing.stages().isEmpty());
        assertEquals(List.of("reasoner"), missing.details().get("missing_required_stages"));

        GateResult missingBesideFailure = GateResult.aggregate(1, HASH_A,
                List.of("profile", "reasoner"),
                List.of(StageResult.fail("profile", List.of(warning()))), FindingSeverity.WARNING);
        assertEquals(GateStatus.ERROR, missingBesideFailure.gate(),
                "missing execution data takes precedence over a separate policy failure");

        GateResult skipped = GateResult.aggregate(1, HASH_A, List.of("reasoner"),
                List.of(StageResult.skipped("reasoner", "No reasoner selected.")), FindingSeverity.WARNING);
        assertEquals(GateStatus.ERROR, skipped.gate());
        assertEquals(0, skipped.stagesRan());
        assertEquals(1, skipped.stagesSkipped());

        GateResult errored = GateResult.aggregate(1, HASH_A, List.of("shacl"),
                List.of(StageResult.error("shacl", "Malformed shapes.")), FindingSeverity.WARNING);
        assertEquals(GateStatus.ERROR, errored.gate());
    }

    @Test
    void optionalSkippedStageDoesNotPoisonACompletedRequiredGate() {
        GateResult result = GateResult.aggregate(1, HASH_A, List.of("profile"), List.of(
                StageResult.pass("profile", Collections.emptyList()),
                StageResult.skipped("cqs", "No competency questions configured.")),
                FindingSeverity.WARNING);
        assertEquals(GateStatus.PASS, result.gate());
        assertEquals(1, result.stagesRan());
        assertEquals(1, result.stagesSkipped());
    }

    @Test
    void optionalStageFindingsRemainVisibleButDoNotControlTheRequiredGate() {
        Finding optionalError = new Finding("optional-error", "cqs", FindingSeverity.ERROR,
                "An optional competency question failed.", null, null, null, null, null, Map.of());
        GateResult result = GateResult.aggregate(1, HASH_A, List.of("profile"), List.of(
                StageResult.pass("profile", Collections.emptyList()),
                StageResult.fail("cqs", List.of(optionalError))), FindingSeverity.WARNING);

        assertEquals(GateStatus.PASS, result.gate());
        assertEquals(List.of(optionalError), result.findings());
    }

    @Test
    void executionErrorTakesPrecedenceOverASeparatePolicyFailure() {
        GateResult result = GateResult.aggregate(1, HASH_A, List.of("profile", "shacl"), List.of(
                StageResult.fail("profile", List.of(warning())),
                StageResult.error("shacl", "Timed out.")), FindingSeverity.WARNING);
        assertEquals(GateStatus.ERROR, result.gate());
    }

    @Test
    void rejectsDuplicateOrAmbiguousStageData() {
        assertThrows(IllegalArgumentException.class, () -> GateResult.aggregate(1, HASH_A,
                List.of("profile", "profile"), Collections.emptyList(), FindingSeverity.WARNING));
        assertThrows(IllegalArgumentException.class, () -> GateResult.aggregate(1, HASH_A,
                List.of("profile"), List.of(
                        StageResult.pass("profile", Collections.emptyList()),
                        StageResult.pass("profile", Collections.emptyList())), FindingSeverity.WARNING));
    }

    @Test
    void constructorsRejectMalformedIdentityFingerprintCountsAndMissingErrorMessages() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectCoordinates("", 1, "https://example.org", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRevision("not-a-uuid", 0, HASH_A, HASH_B));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRevision("1-2-3-4-5", 0, HASH_A, HASH_B));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRevision(WORKSPACE, -1, HASH_A, HASH_B));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRevision(WORKSPACE, 0, "sha256:abc", HASH_B));
        assertThrows(IllegalArgumentException.class,
                () -> new ArtifactReference("report.json", HASH_B, 1, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StageResult("shacl", StageStatus.ERROR, null, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new GateResult(GateStatus.PASS, 1, HASH_A, List.of("profile"), 0, 0,
                        List.of(StageResult.pass("profile", List.of())), List.of(), List.of(), Map.of()));
    }

    @Test
    void directAndJacksonConstructionCannotForgeAContradictoryGate() throws Exception {
        StageResult pass = StageResult.pass("profile", List.of());
        StageResult error = StageResult.error("profile", "Reasoner unavailable.");

        assertThrows(IllegalArgumentException.class,
                () -> new GateResult(GateStatus.PASS, 1, HASH_A, List.of("profile"), 1, 0,
                        List.of(error), List.of(), List.of(), Map.of("fail_on", "warning")));
        assertThrows(IllegalArgumentException.class,
                () -> new GateResult(GateStatus.PASS, 1, HASH_A, List.of("reasoner"), 1, 0,
                        List.of(pass), List.of(), List.of(), Map.of("fail_on", "warning")));
        assertThrows(IllegalArgumentException.class,
                () -> new GateResult(GateStatus.PASS, 1, HASH_A, List.of("profile"), 0, 1,
                        List.of(pass), List.of(), List.of(), Map.of("fail_on", "warning")));
        assertThrows(IllegalArgumentException.class,
                () -> new GateResult(GateStatus.PASS, 1, HASH_A, List.of("profile"), 1, 0,
                        List.of(pass), List.of(warning()), List.of(), Map.of("fail_on", "warning")));
        assertThrows(IllegalArgumentException.class,
                () -> new GateResult(GateStatus.PASS, 1, HASH_A, List.of("profile"), 1, 0,
                        List.of(pass), List.of(), List.of(), Map.of()));

        GateResult forged = new GateResult(GateStatus.PASS, 1, HASH_A, List.of("profile"), 1, 0,
                List.of(pass), List.of(), List.of(), Map.of("fail_on", "warning"));
        String json = JSON.writeValueAsString(forged).replace("\"gate\":\"pass\"", "\"gate\":\"error\"");
        assertThrows(JsonProcessingException.class, () -> JSON.readValue(json, GateResult.class));
    }

    @Test
    void packagedSchemaRejectsNonCanonicalIdentityAndMissingGateThreshold() {
        Map<String, Object> revision = JSON.convertValue(
                new ModelRevision(WORKSPACE, 1, HASH_A, HASH_B), new TypeReference<>() { });
        revision.put("workspace_id", "1-2-3-4-5");
        assertSchemaInvalid(revision, "non-canonical workspace UUID");

        GateResult gate = GateResult.aggregate(1, HASH_A, List.of("profile"),
                List.of(StageResult.pass("profile", List.of())), FindingSeverity.WARNING);
        Map<String, Object> gateJson = JSON.convertValue(gate, new TypeReference<>() { });
        object(gateJson, "details").remove("fail_on");
        assertSchemaInvalid(gateJson, "gate without fail_on");
    }

    @Test
    void gateNormalizesThresholdAndProtectsSemanticDetailLists() {
        List<String> declaredMissing = new ArrayList<>(List.of("reasoner"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fail_on", "WARNING");
        details.put("missing_required_stages", declaredMissing);
        GateResult result = new GateResult(GateStatus.ERROR, 1, HASH_A, List.of("reasoner"),
                0, 0, List.of(), List.of(), List.of(), details);

        declaredMissing.clear();
        assertEquals("warning", result.details().get("fail_on"));
        assertEquals(List.of("reasoner"), result.details().get("missing_required_stages"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) result.details().get("missing_required_stages")).clear());
    }

    @Test
    void recordsDefensivelyCopyCallerCollections() {
        List<String> modules = new ArrayList<>(List.of("https://example.org/core"));
        ProjectCoordinates project = new ProjectCoordinates("example", 1,
                "https://example.org/ontology", modules);
        modules.add("https://example.org/later");
        assertEquals(1, project.modules().size());
        assertThrows(UnsupportedOperationException.class,
                () -> project.modules().add("https://example.org/nope"));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", 1);
        Finding finding = new Finding("id", "structural", FindingSeverity.INFO, "message",
                null, null, null, null, null, details);
        details.put("count", 2);
        assertEquals(1, finding.details().get("count"));
        assertThrows(UnsupportedOperationException.class, () -> finding.details().put("x", 1));
    }

    @Test
    void strictMapperRejectsScalarCoercionsNullPrimitivesAndTrailingContent() throws Exception {
        String json = JSON.writeValueAsString(new ModelRevision(WORKSPACE, 7, HASH_A, HASH_B));

        assertThrows(JsonProcessingException.class, () -> JSON.readValue(
                json.replace("\"session_revision\":7", "\"session_revision\":7.9"),
                ModelRevision.class), "a fractional revision must fail, not truncate");
        assertThrows(JsonProcessingException.class, () -> JSON.readValue(
                json.replace("\"session_revision\":7", "\"session_revision\":\"7\""),
                ModelRevision.class), "a quoted revision must fail, not coerce");
        assertThrows(JsonProcessingException.class, () -> JSON.readValue(
                json.replace("\"session_revision\":7", "\"session_revision\":null"),
                ModelRevision.class), "an explicit null revision must fail, not zero");
        assertThrows(JsonProcessingException.class,
                () -> JSON.readValue(json + " {\"junk\":true}", ModelRevision.class),
                "trailing content after the document must fail");
    }

    @Test
    void unknownFutureJsonFieldsAreRejectedRatherThanSilentlyReinterpreted() throws Exception {
        String json = JSON.writeValueAsString(new ModelRevision(WORKSPACE, 1, HASH_A, HASH_B));
        String withUnknown = json.substring(0, json.length() - 1) + ",\"future\":true}";
        assertThrows(JsonProcessingException.class, () -> JSON.readValue(withUnknown, ModelRevision.class));

        String duplicate = json.substring(0, json.length() - 1)
                + ",\"workspace_id\":\"" + WORKSPACE + "\"}";
        assertThrows(JsonProcessingException.class, () -> JSON.readValue(duplicate, ModelRevision.class));
    }

    @Test
    void enumParsingIsCaseInsensitiveButRejectsUnknownVocabulary() {
        assertEquals(FindingSeverity.WARNING, FindingSeverity.fromJson("WARNING"));
        assertEquals(GateStatus.ERROR, GateStatus.fromJson("error"));
        assertEquals(StageStatus.SKIPPED, StageStatus.fromJson("Skipped"));
        assertThrows(IllegalArgumentException.class, () -> FindingSeverity.fromJson("critical"));
        assertThrows(IllegalArgumentException.class, () -> GateStatus.fromJson("skipped"));
    }

    private static Finding warning() {
        return new Finding("missing-definition", "governance", FindingSeverity.WARNING,
                "Class has no project definition annotation.", "https://example.org/Widget",
                null, null, "annotation.definition.required", null, Map.of("validator", "test"));
    }

    private static <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        Map<String, Object> json = JSON.convertValue(value, new TypeReference<>() { });
        ValidationResponse response = VALIDATOR.validate(schema, json);
        assertTrue(response.valid(), () -> type.getSimpleName() + ": " + response.errorMessage());
        assertEquals(value, JSON.readValue(JSON.writeValueAsBytes(value), type));
    }

    private static void assertSchemaInvalid(Map<String, Object> value, String caseName) {
        ValidationResponse response = VALIDATOR.validate(schema, value);
        assertFalse(response.valid(), () -> caseName + " unexpectedly passed");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> value, String key) {
        return (Map<String, Object>) value.get(key);
    }
}
