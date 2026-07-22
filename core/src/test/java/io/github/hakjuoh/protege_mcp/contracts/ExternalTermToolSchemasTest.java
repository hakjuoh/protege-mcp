package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExternalTermToolSchemasTest {

    @Test
    void everySchemaIsStrictTypedAndCompilable() {
        for (String name : ExternalTermToolSchemas.NAMES) {
            Map<String, Object> input = ExternalTermToolSchemas.input(name);
            Map<String, Object> output = ExternalTermToolSchemas.output(name);
            ToolSchemaValidator.validateInput(input, name + " input");
            ToolSchemaValidator.validateOutput(output, name + " output");
            ToolSchemaValidator.validateTypedOutput(output, name + " output");
            assertEquals(false, input.get("additionalProperties"));
            assertEquals(false, output.get("additionalProperties"));
        }
    }

    @Test
    void searchContractSeparatesOpaqueCursorFromProviderContinuation() {
        Map<String, Object> input = ExternalTermToolSchemas.input("search_external_terms");
        Map<String, Object> output = ExternalTermToolSchemas.output("search_external_terms");
        Map<String, Object> inputProperties = properties(input);
        Map<String, Object> outputProperties = properties(output);

        assertTrue(inputProperties.containsKey("cursor"));
        assertTrue(outputProperties.containsKey("next_cursor"));
        assertFalse(inputProperties.containsKey("continuation"));
        assertFalse(outputProperties.containsKey("continuation"));
        assertFalse(String.valueOf(output).contains("authorization"));
        assertFalse(String.valueOf(output).contains("credential"));
    }

    @Test
    void searchInputAcceptsExactlyOneRequestMode() {
        ToolSchemaValidator.Compiled schema = ToolSchemaValidator.compile(
                ExternalTermToolSchemas.input("search_external_terms"));

        assertTrue(schema.violations(Map.of("provider_id", "ols", "query", "heart")).isEmpty());
        assertTrue(schema.violations(Map.of("cursor", "opaque-cursor")).isEmpty());
        assertFalse(schema.violations(Map.of()).isEmpty());
        assertFalse(schema.violations(Map.of("provider_id", "ols")).isEmpty());
        assertFalse(schema.violations(Map.of("query", "heart")).isEmpty());
        assertFalse(schema.violations(Map.of("cursor", "opaque-cursor",
                "provider_id", "ols", "query", "heart")).isEmpty());
        assertFalse(schema.violations(Map.of("cursor", "opaque-cursor", "limit", 10)).isEmpty());
    }

    @Test
    void resultContractRequiresEvidenceFingerprintAndSourceMetadata() {
        Map<String, Object> output = ExternalTermToolSchemas.output("inspect_external_term");
        Map<String, Object> result = properties(output);
        Map<String, Object> evidence = cast(result.get("result"));
        List<String> required = list(evidence.get("required"));

        assertTrue(required.contains("term_fingerprint"));
        assertTrue(required.contains("result_fingerprint"));
        assertTrue(required.contains("provider_timestamp"));
        assertTrue(required.contains("source_url"));
        assertTrue(required.contains("match_explanation"));
    }

    @Test
    void directInspectionCanExplicitlyBypassCache() {
        ToolSchemaValidator.Compiled inspect = ToolSchemaValidator.compile(
                ExternalTermToolSchemas.input("inspect_external_term"));
        Map<String, Object> request = Map.of(
                "provider_id", "ols", "ontology", "efo",
                "iri", "https://example.org/EFO_1", "fresh", true);

        assertTrue(inspect.violations(request).isEmpty());
        assertFalse(ToolSchemaValidator.compile(
                ExternalTermToolSchemas.input("search_external_terms"))
                .violations(Map.of("provider_id", "ols", "query", "heart", "fresh", true))
                .isEmpty());
    }

    @Test
    void searchOutputCouplesCursorMetadataAndRequiresAtLeastOneLabel() {
        ToolSchemaValidator.Compiled schema = ToolSchemaValidator.compile(
                ExternalTermToolSchemas.output("search_external_terms"));
        Map<String, Object> result = providerResult();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("provider_id", "ols");
        base.put("profile", "ols4");
        base.put("items", List.of(result));
        base.put("total", 1);
        base.put("returned", 1);
        base.put("fetched_at", "2026-07-21T00:00:00Z");
        base.put("retries", 0);
        base.put("cache_hit", false);

        assertTrue(schema.violations(base).isEmpty());
        Map<String, Object> cursorOnly = new LinkedHashMap<>(base);
        cursorOnly.put("next_cursor", "opaque");
        assertFalse(schema.violations(cursorOnly).isEmpty());
        Map<String, Object> expiryOnly = new LinkedHashMap<>(base);
        expiryOnly.put("cursor_expires_in_seconds", 300);
        assertFalse(schema.violations(expiryOnly).isEmpty());
        Map<String, Object> cursorPage = new LinkedHashMap<>(cursorOnly);
        cursorPage.put("cursor_expires_in_seconds", 300);
        assertTrue(schema.violations(cursorPage).isEmpty());

        Map<String, Object> noLabels = new LinkedHashMap<>(result);
        noLabels.put("labels", List.of());
        Map<String, Object> invalidPage = new LinkedHashMap<>(base);
        invalidPage.put("items", List.of(noLabels));
        assertFalse(schema.violations(invalidPage).isEmpty());
    }

    @Test
    void proposalInputEnforcesExactlyOneActionShape() {
        ToolSchemaValidator.Compiled schema = ToolSchemaValidator.compile(
                ExternalTermToolSchemas.input("propose_term_reuse"));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("provider_id", "ols");
        base.put("ontology", "efo");
        base.put("iri", "https://example.org/EFO_1");
        base.put("term_fingerprint", "sha256:" + "1".repeat(64));

        Map<String, Object> reuse = new LinkedHashMap<>(base);
        reuse.put("action", "reuse_iri");
        assertTrue(schema.violations(reuse).isEmpty());

        Map<String, Object> mapping = Map.of(
                "subject_id", "https://example.org/local",
                "predicate_id", "skos:exactMatch",
                "object_id", "https://example.org/EFO_1",
                "mapping_justification", "semapv:ManualMappingCuration");
        Map<String, Object> add = new LinkedHashMap<>(base);
        add.put("action", "add_mapping");
        add.put("mapping", mapping);
        assertTrue(schema.violations(add).isEmpty());

        Map<String, Object> mint = new LinkedHashMap<>(add);
        mint.put("action", "mint_local_with_mapping");
        mint.put("local_entity", Map.of(
                "iri", "https://example.org/local",
                "type", "class",
                "labels", List.of(Map.of("value", "Local term", "language", "en"))));
        assertTrue(schema.violations(mint).isEmpty());

        Map<String, Object> reuseWithMapping = new LinkedHashMap<>(reuse);
        reuseWithMapping.put("mapping", mapping);
        assertFalse(schema.violations(reuseWithMapping).isEmpty());
        Map<String, Object> addWithoutMapping = new LinkedHashMap<>(base);
        addWithoutMapping.put("action", "add_mapping");
        assertFalse(schema.violations(addWithoutMapping).isEmpty());
        Map<String, Object> mintWithoutEntity = new LinkedHashMap<>(add);
        mintWithoutEntity.put("action", "mint_local_with_mapping");
        assertFalse(schema.violations(mintWithoutEntity).isEmpty());
        Map<String, Object> invalidColumn = new LinkedHashMap<>(add);
        Map<String, Object> invalidMapping = new LinkedHashMap<>(mapping);
        invalidMapping.put("invalid column", "value");
        invalidColumn.put("mapping", invalidMapping);
        assertFalse(schema.violations(invalidColumn).isEmpty());
    }

    @Test
    void acceptanceContractCouplesEachTerminalAndPartialShape() {
        ToolSchemaValidator.Compiled input = ToolSchemaValidator.compile(
                ExternalTermToolSchemas.input("accept_reuse_proposal"));
        Map<String, Object> acceptedInput = Map.of(
                "proposal_id", "a".repeat(43),
                "proposal_fingerprint", digest("1"), "confirm", true);
        assertTrue(input.violations(acceptedInput).isEmpty());
        assertFalse(input.violations(Map.of(
                "proposal_id", "a".repeat(43),
                "proposal_fingerprint", digest("1"), "confirm", false)).isEmpty());

        ToolSchemaValidator.Compiled output = ToolSchemaValidator.compile(
                ExternalTermToolSchemas.output("accept_reuse_proposal"));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("proposal_id", "a".repeat(43));
        base.put("proposal_fingerprint", digest("1"));
        base.put("status", "accepted");
        base.put("action", "reuse_iri");
        base.put("committed", false);
        base.put("interactive_confirmation", false);
        base.put("receipt", Map.of(
                "provider_id", "ols", "source_ontology", "efo",
                "entity_iri", "https://example.org/EFO_1",
                "term_fingerprint", digest("2"),
                "model_revision", revision(), "mapping_revision", digest("3"),
                "policy_digest", digest("4"), "target_fingerprint", digest("5")));
        assertTrue(output.violations(base).isEmpty());
        Map<String, Object> missingConfirmationEvidence = new LinkedHashMap<>(base);
        missingConfirmationEvidence.remove("interactive_confirmation");
        assertFalse(output.violations(missingConfirmationEvidence).isEmpty());

        Map<String, Object> mappingAccepted = new LinkedHashMap<>(base);
        mappingAccepted.put("action", "add_mapping");
        mappingAccepted.put("committed", true);
        mappingAccepted.remove("receipt");
        mappingAccepted.put("mapping", mappingMutation());
        assertTrue(output.violations(mappingAccepted).isEmpty());

        Map<String, Object> partial = new LinkedHashMap<>(base);
        partial.put("status", "partial");
        partial.put("action", "mint_local_with_mapping");
        partial.put("committed", true);
        partial.remove("receipt");
        partial.put("mint_receipt", mintReceipt());
        partial.put("mapping_error", Map.of("code", "mapping_revision_conflict",
                "message", "Mapping state changed.", "retryable", true,
                "details", Map.of("effects_prevented", true)));
        partial.put("continuation", continuation());
        assertTrue(output.violations(partial).isEmpty());

        Map<String, Object> partialWithMapping = new LinkedHashMap<>(partial);
        partialWithMapping.put("mapping", mappingMutation());
        assertFalse(output.violations(partialWithMapping).isEmpty());
        Map<String, Object> acceptedWithoutMapping = new LinkedHashMap<>(mappingAccepted);
        acceptedWithoutMapping.remove("mapping");
        assertFalse(output.violations(acceptedWithoutMapping).isEmpty());
    }

    @Test
    void unknownToolIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ExternalTermToolSchemas.input("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalTermToolSchemas.output("unknown"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return (List<String>) value;
    }

    private static Map<String, Object> providerResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", "ols");
        result.put("profile", "ols4");
        result.put("source_ontology", "efo");
        result.put("entity_iri", "https://example.org/EFO_1");
        result.put("entity_type", "class");
        result.put("labels", List.of(Map.of("value", "Term", "language", "en")));
        result.put("synonyms", List.of());
        result.put("descriptions", List.of());
        result.put("match_explanation", "exact_label");
        result.put("score", 1.0);
        result.put("provider_timestamp", "2026-07-21T00:00:00Z");
        result.put("source_url", "https://example.org/ols4/term");
        result.put("retries", 0);
        result.put("deprecated", false);
        result.put("term_fingerprint", "sha256:" + "1".repeat(64));
        result.put("result_fingerprint", "sha256:" + "0".repeat(64));
        return result;
    }

    private static String digest(String digit) {
        return "sha256:" + digit.repeat(64);
    }

    private static Map<String, Object> revision() {
        return Map.of("workspace_id", "123e4567-e89b-12d3-a456-426614174000",
                "session_revision", 7, "semantic_fingerprint", digest("a"),
                "document_fingerprint", digest("b"));
    }

    private static Map<String, Object> mappingMutation() {
        return Map.of("committed", true, "path", "/project/mappings.tsv",
                "previous_mapping_revision", digest("3"),
                "mapping_revision", digest("5"), "record_count", 1,
                "bytes", 512, "valid", true, "error_count", 0,
                "warning_count", 0, "findings_truncated", false);
    }

    private static Map<String, Object> mintReceipt() {
        return Map.of("proposal_fingerprint", digest("1"),
                "entity_iri", "https://example.org/local",
                "base_revision", revision(), "minted_revision", Map.of(
                        "workspace_id", "123e4567-e89b-12d3-a456-426614174000",
                        "session_revision", 8, "semantic_fingerprint", digest("c"),
                        "document_fingerprint", digest("d")),
                "receipt_fingerprint", digest("e"));
    }

    private static Map<String, Object> continuation() {
        Map<String, Object> mapping = Map.of(
                "subject_id", "https://example.org/local",
                "predicate_id", "skos:exactMatch",
                "object_id", "https://example.org/EFO_1");
        return Map.of(
                "retry", Map.of("tool", "accept_reuse_proposal", "arguments", Map.of(
                        "proposal_id", "a".repeat(43),
                        "proposal_fingerprint", digest("1"), "confirm", true)),
                "manual_recovery", Map.of("tool", "add_mapping", "arguments", Map.of(
                        "expected_mapping_revision", digest("3"),
                        "mapping", mapping, "confirm", true)));
    }
}
