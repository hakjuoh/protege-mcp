package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;

class ProjectPolicyV2SchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultJsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(JSON);
    private static Map<String, Object> schema;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream input = ProjectPolicyV2SchemaTest.class.getResourceAsStream(
                "/schema/project-policy-v2.schema.json")) {
            assertNotNull(input);
            schema = JSON.readValue(input, new TypeReference<>() { });
        }
    }

    @Test
    void schemaIsStrictValidAndPinsVersionTwo() {
        ValidationResponse response = VALIDATOR.validateSchema(schema);
        assertTrue(response.valid(), response::errorMessage);
        assertTrue(Boolean.FALSE.equals(schema.get("additionalProperties")));
        assertTrue(Integer.valueOf(2).equals(object(properties(schema).get("version")).get("const")));
    }

    @Test
    void acceptsMinimalAndFullyGovernedVersionTwoPolicies() {
        Map<String, Object> minimal = base();
        assertValid(minimal, "minimal v2");

        Map<String, Object> full = copy(minimal);
        full.put("external_terms", Map.of("providers", List.of(Map.of(
                "id", "ols", "profile", "ols4", "enabled", true,
                "origin_alias", "ebi-ols", "ontologies", List.of("efo"),
                "languages", List.of("en"), "credential_id", "ols-read",
                "required_evidence_for", List.of("reuse", "provider_evidence")))));
        full.put("mappings", Map.of(
                "path", ".protege-mcp/mappings.sssom.tsv",
                "allowed_predicates", List.of("skos:exactMatch"),
                "directional_cycle_policy", Map.of("skos:broadMatch", "warning"),
                "many_to_one_rules", List.of(Map.of("predicate", "skos:exactMatch",
                        "subject_providers", List.of("ols")))));
        full.put("jobs", Map.of("workers", 1, "queue_capacity", 8,
                "active_per_principal", 4, "retained_per_principal", 8,
                "retained_per_backend", 32, "retention_seconds", 600));
        full.put("materialization", Map.of(
                "allowed_destinations", List.of("new_ontology", "active_source"),
                "allow_source_write", true, "max_axioms_total", 1000));
        full.put("validation", Map.of(
                "required_stages", List.of("provider_evidence"),
                "provider_evidence", Map.of("providers", List.of("ols"))));
        assertValid(full, "fully governed v2");
    }

    @Test
    void publishedVersionTwoExampleConforms() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        Map<String, Object> example = yaml.readValue(
                Files.readString(Path.of("docs/examples/project-policy/v2.yaml")),
                new TypeReference<>() { });
        assertValid(example, "published v2 example");
    }

    @Test
    void rejectsSecretsEndpointsUnknownFieldsAndBoundsThatExceedProductMaxima() {
        Map<String, Object> endpoint = base();
        endpoint.put("external_terms", Map.of("providers", List.of(Map.of(
                "id", "ols", "profile", "ols4", "enabled", true,
                "origin_alias", "ebi-ols", "endpoint", "https://www.ebi.ac.uk/ols4"))));
        assertInvalid(endpoint, "endpoint must remain owner-local");

        Map<String, Object> secret = base();
        secret.put("external_terms", Map.of("providers", List.of(Map.of(
                "id", "ols", "profile", "ols4", "enabled", true,
                "origin_alias", "ebi-ols", "api_key", "secret"))));
        assertInvalid(secret, "secret must never be policy content");

        Map<String, Object> invalidCredential = base();
        invalidCredential.put("external_terms", Map.of("providers", List.of(Map.of(
                "id", "ols", "profile", "ols4", "enabled", true,
                "origin_alias", "ebi-ols", "credential_id", "4invalid"))));
        assertInvalid(invalidCredential, "credential grammar must match the owner store");

        Map<String, Object> longCredential = base();
        longCredential.put("external_terms", Map.of("providers", List.of(Map.of(
                "id", "ols", "profile", "ols4", "enabled", true,
                "origin_alias", "ebi-ols", "credential_id", "a".repeat(65)))));
        assertInvalid(longCredential, "credential length must match the owner store");

        for (int ttl : List.of(0, 86_400)) {
            Map<String, Object> boundary = base();
            boundary.put("external_terms", Map.of("providers", List.of(Map.of(
                    "id", "ols", "profile", "ols4", "enabled", true,
                    "origin_alias", "ebi-ols", "ttl_seconds", ttl))));
            assertValid(boundary, "provider TTL boundary " + ttl);
        }
        for (int ttl : List.of(-1, 86_401)) {
            Map<String, Object> boundary = base();
            boundary.put("external_terms", Map.of("providers", List.of(Map.of(
                    "id", "ols", "profile", "ols4", "enabled", true,
                    "origin_alias", "ebi-ols", "ttl_seconds", ttl))));
            assertInvalid(boundary, "provider TTL outside boundary " + ttl);
        }

        for (Map<String, Object> jobs : List.<Map<String, Object>>of(
                Map.of("workers", 3), Map.of("queue_capacity", 33),
                Map.of("active_per_principal", 9), Map.of("retention_seconds", 3601))) {
            Map<String, Object> policy = base();
            policy.put("jobs", jobs);
            assertInvalid(policy, "job maximum " + jobs);
        }

        Map<String, Object> materialization = base();
        materialization.put("materialization", Map.of("max_axioms_total", 50001));
        assertInvalid(materialization, "materialization maximum");

        Map<String, Object> source = base();
        source.put("materialization", Map.of(
                "allowed_destinations", List.of("active_source"), "allow_source_write", false));
        assertInvalid(source, "active source must be explicit");

        Map<String, Object> partial = base();
        partial.put("materialization", Map.of("timeout_ms", 1000));
        assertValid(partial, "a partial safe materialization block must not require source writes");

        List<String> tooManyTerms = new ArrayList<>();
        List<String> tooManyPaths = new ArrayList<>();
        for (int i = 0; i < 129; i++) {
            tooManyTerms.add("https://example.org/predicate/" + i);
            tooManyPaths.add("queries/query-" + i + ".rq");
        }
        Map<String, Object> termList = base();
        termList.put("mappings", Map.of("allowed_predicates", tooManyTerms));
        assertInvalid(termList, "term-reference collections are bounded");

        Map<String, Object> pathList = base();
        pathList.put("validation", Map.of(
                "invariants", Map.of("paths", tooManyPaths)));
        assertInvalid(pathList, "path collections are bounded");
    }

    private static Map<String, Object> base() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", 2);
        policy.put("project_id", "v2-test");
        policy.put("root_ontology", "https://example.org/ontology");
        policy.put("interoperability", Map.of(
                "profile", "https://hakjuoh.github.io/protege-mcp/profiles/project-v1/",
                "root_artifact", "ontology.ttl",
                "metadata", Map.of("path", "ro-crate-metadata.json", "format", "ro-crate-1.1"),
                "canonicalization", Map.of("algorithm", "RDFC-1.0", "hash", "SHA-256",
                        "scope", "root-ontology")));
        return policy;
    }

    private static void assertValid(Map<String, Object> policy, String label) {
        ValidationResponse response = VALIDATOR.validate(schema, policy);
        assertTrue(response.valid(), () -> label + ": " + response.errorMessage());
    }

    private static void assertInvalid(Map<String, Object> policy, String label) {
        ValidationResponse response = VALIDATOR.validate(schema, policy);
        assertFalse(response.valid(), () -> label + " unexpectedly passed");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> value) {
        return (Map<String, Object>) value.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> value) {
        return JSON.convertValue(value, new TypeReference<>() { });
    }
}
