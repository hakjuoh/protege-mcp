package io.github.hakjuoh.protege_mcp.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict JSON Schemas for the public external-term tools. */
public final class ExternalTermToolSchemas {

    public static final Set<String> NAMES = Set.of(
            "search_external_terms", "inspect_external_term", "propose_term_reuse",
            "accept_reuse_proposal");

    private ExternalTermToolSchemas() {
    }

    public static Map<String, Object> input(String name) {
        requireName(name);
        Map<String, Object> properties = new LinkedHashMap<>();
        if ("accept_reuse_proposal".equals(name)) {
            properties.put("proposal_id", proposalId());
            properties.put("proposal_fingerprint", digest());
            properties.put("confirm", Map.of("type", "boolean", "const", true));
            properties.put("policy_path", string(1, 4096));
            properties.put("mapping_set_id", absoluteIri());
            properties.put("license", absoluteIri());
            return object(properties,
                    List.of("proposal_id", "proposal_fingerprint", "confirm"));
        }
        properties.put("provider_id", identifier());
        properties.put("policy_path", string(1, 4096));
        properties.put("network", Map.of("type", "string",
                "enum", List.of("deny", "allow")));
        if ("search_external_terms".equals(name)) {
            properties.put("query", string(1, 512));
            properties.put("ontologies", array(identifier(), 16));
            properties.put("language", language());
            properties.put("limit", integer(1, 100));
            properties.put("cursor", string(1, 512));
            Map<String, Object> schema = new LinkedHashMap<>(object(properties, List.of()));
            schema.put("oneOf", List.of(
                    mode(properties, List.of("provider_id", "query"), "cursor"),
                    mode(properties, List.of("cursor"), "provider_id", "query", "ontologies",
                            "language", "limit")));
            return ImmutableJson.map(schema);
        }
        properties.put("ontology", identifier());
        properties.put("iri", absoluteIri());
        properties.put("language", language());
        if ("inspect_external_term".equals(name)) {
            properties.put("fresh", Map.of("type", "boolean"));
            return object(properties, List.of("provider_id", "ontology", "iri"));
        }
        properties.put("term_fingerprint", Map.of(
                "type", "string", "pattern", "^sha256:[0-9a-f]{64}$",
                "description", "Stable term-content fingerprint from direct inspection."));
        properties.put("action", Map.of("type", "string", "enum",
                List.of("reuse_iri", "add_mapping", "mint_local_with_mapping")));
        properties.put("mapping", mapping());
        properties.put("local_entity", localEntity());
        List<String> base = List.of("provider_id", "ontology", "iri",
                "term_fingerprint", "action");
        Map<String, Object> schema = new LinkedHashMap<>(object(properties, base));
        schema.put("oneOf", List.of(
                proposalMode(properties, "reuse_iri", List.of(), "mapping", "local_entity"),
                proposalMode(properties, "add_mapping", List.of("mapping"), "local_entity"),
                proposalMode(properties, "mint_local_with_mapping",
                        List.of("mapping", "local_entity"))));
        return ImmutableJson.map(schema);
    }

    public static Map<String, Object> output(String name) {
        requireName(name);
        Map<String, Object> properties = new LinkedHashMap<>();
        if ("search_external_terms".equals(name)) {
            properties.put("provider_id", identifier());
            properties.put("profile", identifier());
            properties.put("items", array(providerResult(), 100));
            properties.put("total", integer(0, Integer.MAX_VALUE));
            properties.put("returned", integer(0, 100));
            properties.put("fetched_at", timestamp());
            properties.put("retries", integer(0, 2));
            properties.put("cache_hit", Map.of("type", "boolean"));
            properties.put("next_cursor", string(1, 512));
            properties.put("cursor_expires_in_seconds", integer(1, 300));
            Map<String, Object> schema = new LinkedHashMap<>(object(properties,
                    List.of("provider_id", "profile", "items", "total", "returned",
                            "fetched_at", "retries", "cache_hit")));
            schema.put("oneOf", List.of(
                    mode(properties, List.of("next_cursor", "cursor_expires_in_seconds")),
                    mode(properties, List.of(), "next_cursor", "cursor_expires_in_seconds")));
            return ImmutableJson.map(schema);
        }
        if ("inspect_external_term".equals(name)) {
            properties.put("result", providerResult());
            properties.put("cache_hit", Map.of("type", "boolean"));
            return object(properties, List.of("result", "cache_hit"));
        }
        if ("accept_reuse_proposal".equals(name)) return acceptanceOutput();
        properties.put("proposal_id", proposalId());
        properties.put("expires_in_seconds", Map.of("type", "integer", "const", 900));
        properties.put("proposal", proposal());
        return object(properties, List.of("proposal_id", "expires_in_seconds", "proposal"));
    }

    private static Map<String, Object> acceptanceOutput() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("proposal_id", proposalId());
        properties.put("proposal_fingerprint", digest());
        properties.put("status", Map.of("type", "string",
                "enum", List.of("accepted", "partial")));
        properties.put("action", Map.of("type", "string", "enum",
                List.of("reuse_iri", "add_mapping", "mint_local_with_mapping")));
        properties.put("committed", Map.of("type", "boolean"));
        properties.put("interactive_confirmation", Map.of("type", "boolean"));
        properties.put("receipt", reuseReceipt());
        properties.put("mapping", mappingMutation());
        properties.put("mint_receipt", mintReceipt());
        properties.put("continuation", mintContinuation());
        properties.put("mapping_error", mappingError());
        List<String> base = List.of("proposal_id", "proposal_fingerprint",
                "status", "action", "committed", "interactive_confirmation");
        Map<String, Object> schema = new LinkedHashMap<>(object(properties, base));
        schema.put("oneOf", List.of(
                acceptanceMode(properties, "reuse_iri", "accepted", false,
                        List.of("receipt"), "mapping", "mint_receipt", "continuation",
                        "mapping_error"),
                acceptanceMode(properties, "add_mapping", "accepted", null,
                        List.of("mapping"), "receipt", "mint_receipt", "continuation",
                        "mapping_error"),
                acceptanceMode(properties, "mint_local_with_mapping", "accepted", true,
                        List.of("mapping", "mint_receipt"), "receipt", "continuation",
                        "mapping_error"),
                acceptanceMode(properties, "mint_local_with_mapping", "partial", true,
                        List.of("mint_receipt", "continuation", "mapping_error"),
                        "receipt", "mapping")));
        return ImmutableJson.map(schema);
    }

    private static Map<String, Object> acceptanceMode(Map<String, Object> properties,
            String action, String status, Boolean committed, List<String> extras,
            String... forbidden) {
        Map<String, Object> variant = new LinkedHashMap<>(properties);
        variant.put("action", Map.of("type", "string", "const", action));
        variant.put("status", Map.of("type", "string", "const", status));
        if (committed != null) {
            variant.put("committed", Map.of("type", "boolean", "const", committed));
        }
        List<String> required = new ArrayList<>(List.of("proposal_id", "proposal_fingerprint",
                "status", "action", "committed", "interactive_confirmation"));
        required.addAll(extras);
        return mode(variant, required, forbidden);
    }

    private static Map<String, Object> reuseReceipt() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provider_id", identifier());
        properties.put("source_ontology", identifier());
        properties.put("entity_iri", absoluteIri());
        properties.put("term_fingerprint", digest());
        properties.put("model_revision", modelRevision());
        properties.put("mapping_revision", digest());
        properties.put("policy_digest", digest());
        properties.put("target_fingerprint", digest());
        return object(properties, List.copyOf(properties.keySet()));
    }

    private static Map<String, Object> mappingMutation() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("committed", Map.of("type", "boolean"));
        properties.put("path", string(1, 16_384));
        properties.put("previous_mapping_revision", digest());
        properties.put("mapping_revision", digest());
        properties.put("record_count", integer(0, 100_000));
        properties.put("bytes", integer(0, 67_108_864));
        properties.put("backup_path", string(1, 16_384));
        properties.put("valid", Map.of("type", "boolean"));
        properties.put("error_count", integer(0, Integer.MAX_VALUE));
        properties.put("warning_count", integer(0, Integer.MAX_VALUE));
        properties.put("findings_truncated", Map.of("type", "boolean"));
        return object(properties, List.of("committed", "path", "previous_mapping_revision",
                "mapping_revision", "record_count", "bytes", "valid", "error_count",
                "warning_count", "findings_truncated"));
    }

    private static Map<String, Object> mintReceipt() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("proposal_fingerprint", digest());
        properties.put("entity_iri", absoluteIri());
        properties.put("base_revision", modelRevision());
        properties.put("minted_revision", modelRevision());
        properties.put("mapping_set_id", absoluteIri());
        properties.put("mapping_set_license", absoluteIri());
        properties.put("configured_policy_path", string(1, 4096));
        properties.put("receipt_fingerprint", digest());
        return object(properties, List.of("proposal_fingerprint", "entity_iri",
                "base_revision", "minted_revision", "receipt_fingerprint"));
    }

    private static Map<String, Object> mintContinuation() {
        Map<String, Object> retryArguments = new LinkedHashMap<>();
        retryArguments.put("proposal_id", proposalId());
        retryArguments.put("proposal_fingerprint", digest());
        retryArguments.put("confirm", Map.of("type", "boolean", "const", true));
        retryArguments.put("policy_path", string(1, 4096));
        retryArguments.put("mapping_set_id", absoluteIri());
        retryArguments.put("license", absoluteIri());
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("tool", Map.of("type", "string", "const", "accept_reuse_proposal"));
        retry.put("arguments", object(retryArguments,
                List.of("proposal_id", "proposal_fingerprint", "confirm")));

        Map<String, Object> manualArguments = new LinkedHashMap<>();
        manualArguments.put("expected_mapping_revision", digest());
        manualArguments.put("mapping", mapping());
        manualArguments.put("mapping_set_id", absoluteIri());
        manualArguments.put("license", absoluteIri());
        manualArguments.put("confirm", Map.of("type", "boolean", "const", true));
        manualArguments.put("policy_path", string(1, 4096));
        Map<String, Object> manual = new LinkedHashMap<>();
        manual.put("tool", Map.of("type", "string", "const", "add_mapping"));
        manual.put("arguments", object(manualArguments,
                List.of("expected_mapping_revision", "mapping", "confirm")));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("retry", object(retry, List.of("tool", "arguments")));
        properties.put("manual_recovery", object(manual, List.of("tool", "arguments")));
        return object(properties, List.of("retry", "manual_recovery"));
    }

    private static Map<String, Object> mappingError() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", string(1, 128));
        properties.put("message", string(1, 2_048));
        properties.put("retryable", Map.of("type", "boolean"));
        properties.put("details", Map.of(
                "type", "object",
                "propertyNames", Map.of("type", "string",
                        "pattern", "^[a-z][a-z0-9_]{0,127}$"),
                "additionalProperties", Map.of("oneOf", List.of(
                        string(0, 2_048), Map.of("type", "boolean"),
                        integer(Long.MIN_VALUE, Long.MAX_VALUE)))));
        return object(properties, List.copyOf(properties.keySet()));
    }

    private static Map<String, Object> proposal() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provider_result", providerResult());
        properties.put("input_identity", inputIdentity());
        properties.put("action", Map.of("type", "string", "enum",
                List.of("reuse_iri", "add_mapping", "mint_local_with_mapping")));
        properties.put("suggested_operations", suggestedOperations());
        properties.put("proposal_fingerprint", digest());
        Map<String, Object> schema = new LinkedHashMap<>(object(properties,
                List.of("provider_result", "input_identity", "action",
                        "suggested_operations", "proposal_fingerprint")));
        schema.put("oneOf", List.of(
                proposalOutputMode(properties, "reuse_iri",
                        operationObject(Map.of("entity_iri", absoluteIri()),
                                List.of("entity_iri"))),
                proposalOutputMode(properties, "add_mapping",
                        operationObject(Map.of("mapping", mapping()), List.of("mapping"))),
                proposalOutputMode(properties, "mint_local_with_mapping",
                        operationObject(Map.of("entity", localEntity(), "mapping", mapping()),
                                List.of("entity", "mapping")))));
        return ImmutableJson.map(schema);
    }

    private static Map<String, Object> inputIdentity() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provider_id", identifier());
        properties.put("profile", identifier());
        properties.put("source_ontology", identifier());
        properties.put("entity_iri", absoluteIri());
        properties.put("language", language());
        properties.put("term_fingerprint", digest());
        properties.put("result_fingerprint", digest());
        properties.put("model_revision", modelRevision());
        properties.put("mapping_revision", digest());
        properties.put("policy_digest", digest());
        properties.put("target_identity", targetIdentity());
        properties.put("input_fingerprint", digest());
        return object(properties, List.copyOf(properties.keySet()));
    }

    private static Map<String, Object> targetIdentity() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("project_root_fingerprint", digest());
        properties.put("policy_source_fingerprint", digest());
        properties.put("mapping_target_fingerprint", digest());
        properties.put("mapping_exists", Map.of("type", "boolean"));
        properties.put("target_fingerprint", digest());
        return object(properties, List.copyOf(properties.keySet()));
    }

    private static Map<String, Object> modelRevision() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspace_id", string(1, 128));
        properties.put("session_revision", integer(0, Long.MAX_VALUE));
        properties.put("semantic_fingerprint", digest());
        properties.put("document_fingerprint", digest());
        return object(properties, List.copyOf(properties.keySet()));
    }

    private static Map<String, Object> suggestedOperations() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("entity_iri", absoluteIri());
        properties.put("mapping", mapping());
        properties.put("entity", localEntity());
        return object(properties, List.of());
    }

    private static Map<String, Object> operationObject(Map<String, Object> properties,
            List<String> required) {
        return object(properties, required);
    }

    private static Map<String, Object> proposalOutputMode(Map<String, Object> properties,
            String action, Map<String, Object> operation) {
        Map<String, Object> variant = new LinkedHashMap<>(properties);
        variant.put("action", Map.of("type", "string", "const", action));
        variant.put("suggested_operations", operation);
        return object(variant, List.of("provider_result", "input_identity", "action",
                "suggested_operations", "proposal_fingerprint"));
    }

    private static Map<String, Object> localEntity() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("iri", absoluteIri());
        properties.put("type", Map.of("type", "string", "enum", List.of(
                "class", "object_property", "data_property", "annotation_property",
                "named_individual", "datatype")));
        properties.put("labels", array(localizedText(), 1, 16));
        return object(properties, List.of("iri", "type", "labels"));
    }

    private static Map<String, Object> mapping() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subject_id", string(1, 65_536));
        properties.put("predicate_id", string(1, 65_536));
        properties.put("object_id", string(1, 65_536));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("subject_id", "predicate_id", "object_id"));
        schema.put("propertyNames", Map.of("type", "string",
                "pattern", "^[A-Za-z][A-Za-z0-9_.:-]{0,127}$"));
        schema.put("additionalProperties", string(0, 65_536));
        schema.put("minProperties", 3);
        schema.put("maxProperties", 128);
        return ImmutableJson.map(schema);
    }

    private static Map<String, Object> proposalMode(Map<String, Object> properties,
            String action, List<String> operationFields, String... forbidden) {
        Map<String, Object> variantProperties = new LinkedHashMap<>(properties);
        variantProperties.put("action", Map.of("type", "string", "const", action));
        List<String> required = new ArrayList<>(List.of("provider_id", "ontology", "iri",
                "term_fingerprint", "action"));
        required.addAll(operationFields);
        return mode(variantProperties, required, forbidden);
    }

    private static Map<String, Object> providerResult() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provider_id", identifier());
        properties.put("profile", identifier());
        properties.put("source_ontology", identifier());
        properties.put("source_ontology_iri", absoluteIri());
        properties.put("entity_iri", absoluteIri());
        properties.put("entity_type", identifier());
        properties.put("labels", array(localizedText(), 1, 16));
        properties.put("synonyms", array(localizedText(), 512));
        properties.put("descriptions", array(string(1, 8192), 16));
        properties.put("license", string(1, 4096));
        properties.put("provenance", string(1, 4096));
        properties.put("match_explanation", string(1, 1024));
        properties.put("score", Map.of("type", "number", "minimum", 0, "maximum", 1));
        properties.put("provider_version", string(1, 512));
        properties.put("provider_timestamp", timestamp());
        properties.put("source_url", httpsUrl());
        properties.put("retries", integer(0, 4));
        properties.put("deprecated", Map.of("type", "boolean"));
        properties.put("replaced_by", absoluteIri());
        properties.put("term_fingerprint", digest());
        properties.put("result_fingerprint", digest());
        return object(properties, List.of("provider_id", "profile", "source_ontology",
                "entity_iri", "entity_type", "labels", "synonyms", "descriptions",
                "match_explanation", "score", "provider_timestamp", "source_url", "retries",
                "deprecated", "term_fingerprint", "result_fingerprint"));
    }

    private static Map<String, Object> localizedText() {
        return object(Map.of("value", string(1, 4096), "language", language()),
                List.of("value", "language"));
    }

    private static Map<String, Object> identifier() {
        return Map.of("type", "string", "pattern", "^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    }

    private static Map<String, Object> language() {
        return Map.of("type", "string", "maxLength", 64,
                "pattern", "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$");
    }

    private static Map<String, Object> absoluteIri() {
        return Map.of("type", "string", "minLength", 3, "maxLength", 4096,
                "pattern", "^[A-Za-z][A-Za-z0-9+.-]*:.+$");
    }

    private static Map<String, Object> httpsUrl() {
        return Map.of("type", "string", "minLength", 9, "maxLength", 16_384,
                "pattern", "^[Hh][Tt][Tt][Pp][Ss]://.+$");
    }

    private static Map<String, Object> timestamp() {
        return Map.of("type", "string", "minLength", 10, "maxLength", 128);
    }

    private static Map<String, Object> digest() {
        return Map.of("type", "string", "pattern", "^sha256:[0-9a-f]{64}$");
    }

    private static Map<String, Object> proposalId() {
        return Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{43}$");
    }

    private static Map<String, Object> array(Map<String, Object> items, int maximum) {
        return Map.of("type", "array", "items", items, "maxItems", maximum);
    }

    private static Map<String, Object> array(Map<String, Object> items, int minimum, int maximum) {
        return Map.of("type", "array", "items", items,
                "minItems", minimum, "maxItems", maximum);
    }

    private static Map<String, Object> string(int minimum, int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }

    private static Map<String, Object> integer(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> mode(Map<String, Object> properties,
            List<String> required, String... forbidden) {
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("type", "object");
        mode.put("properties", properties);
        mode.put("required", required);
        if (forbidden.length > 0) {
            mode.put("not", Map.of("type", "object", "properties", properties,
                    "anyOf", java.util.Arrays.stream(forbidden)
                    .map(field -> Map.<String, Object>of("type", "object",
                            "properties", properties, "required", List.of(field)))
                    .toList()));
        }
        return mode;
    }

    private static Map<String, Object> object(Map<String, Object> properties,
            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", new ArrayList<>(required));
        schema.put("additionalProperties", false);
        return ImmutableJson.map(schema);
    }

    private static void requireName(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown external term tool " + name);
        }
    }
}
