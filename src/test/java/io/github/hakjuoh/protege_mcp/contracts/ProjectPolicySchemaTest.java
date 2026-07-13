package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;

/** Structural and adversarial tests for the proposed project-policy v1 authoring contract. */
class ProjectPolicySchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final DefaultJsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(JSON);
    private static final Path EXAMPLES = Path.of("docs", "examples", "project-policy");
    private static Map<String, Object> schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream in = ProjectPolicySchemaTest.class.getResourceAsStream(
                "/schema/project-policy-v1.schema.json")) {
            assertNotNull(in, "project policy schema must be packaged as a main resource");
            schema = JSON.readValue(in, new TypeReference<>() { });
        }
    }

    @Test
    void schemaIsValidAndDeclaresV1FailClosedUnknownFieldHandling() {
        ValidationResponse response = VALIDATOR.validateSchema(schema);
        assertTrue(response.valid(), response::errorMessage);
        assertTrue(Boolean.FALSE.equals(schema.get("additionalProperties")));
        @SuppressWarnings("unchecked")
        Map<String, Object> version = (Map<String, Object>) properties(schema).get("version");
        assertTrue(Integer.valueOf(1).equals(version.get("const")));
    }

    @Test
    void minimalGeneralAndOboExamplesAllConform() throws IOException {
        for (String file : List.of("minimal.yaml", "general-owl.yaml", "obo.yaml")) {
            Map<String, Object> policy = readYaml(EXAMPLES.resolve(file));
            ValidationResponse response = VALIDATOR.validate(schema, policy);
            assertTrue(response.valid(), () -> file + ": " + response.errorMessage());
        }
    }

    @Test
    void examplesMakeFilesystemAndNetworkDefaultsExplicit() throws IOException {
        for (String file : List.of("minimal.yaml", "general-owl.yaml", "obo.yaml")) {
            Map<String, Object> policy = readYaml(EXAMPLES.resolve(file));
            Map<String, Object> filesystem = object(policy, "filesystem");
            Map<String, Object> network = object(policy, "network");
            assertTrue(Boolean.FALSE.equals(filesystem.get("allow_external_paths")), file);
            assertTrue("deny".equals(network.get("default")), file);
        }
    }

    @Test
    void rejectsMissingIdentityFutureVersionAndUnknownTopLevelFields() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("minimal.yaml"));

        Map<String, Object> missing = copy(base);
        missing.remove("project_id");
        assertInvalid(missing, "project_id");

        Map<String, Object> future = copy(base);
        future.put("version", 2);
        assertInvalid(future, "version");

        Map<String, Object> unknown = copy(base);
        unknown.put("future_option", true);
        assertInvalid(unknown, "future_option");
    }

    @Test
    void rejectsUnsafeProjectRootsAndMalformedCoreTypes() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("minimal.yaml"));
        for (String path : List.of("../outside", "/tmp/project", "C:\\project", "C:project")) {
            Map<String, Object> invalid = copy(base);
            invalid.put("project_root", path);
            assertInvalid(invalid, "project_root " + path);
        }

        Map<String, Object> invalidIri = copy(base);
        invalidIri.put("root_ontology", "not an absolute IRI");
        assertInvalid(invalidIri, "root_ontology");

        for (String iri : List.of("https://example.org/has space", "https://example.org/has\nnewline")) {
            Map<String, Object> whitespaceIri = copy(base);
            whitespaceIri.put("root_ontology", iri);
            assertInvalid(whitespaceIri, "root_ontology whitespace");
        }

        Map<String, Object> invalidFilesystem = copy(base);
        object(invalidFilesystem, "filesystem").put("allow_external_paths", "false");
        assertInvalid(invalidFilesystem, "allow_external_paths type");
    }

    @Test
    void rejectsUnknownOrDuplicateStagesAndInvalidTimeouts() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("general-owl.yaml"));

        Map<String, Object> unknown = copy(base);
        object(unknown, "validation").put("required_stages", List.of("reasoner", "magic"));
        assertInvalid(unknown, "unknown stage");

        Map<String, Object> duplicate = copy(base);
        object(duplicate, "validation").put("required_stages", List.of("reasoner", "reasoner"));
        assertInvalid(duplicate, "duplicate stage");

        Map<String, Object> timeout = copy(base);
        object(timeout, "reasoning").put("timeout_ms", 0);
        assertInvalid(timeout, "timeout_ms");
    }

    @Test
    void rejectsMalformedPrefixLanguageAndRequiredReasonerConfiguration() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("general-owl.yaml"));

        Map<String, Object> prefix = copy(base);
        object(prefix, "prefixes").put("bad prefix", "https://example.org/");
        assertInvalid(prefix, "invalid prefix name");

        Map<String, Object> language = copy(base);
        object(object(language, "annotations"), "labels").put("required_languages", List.of("not_a_tag"));
        assertInvalid(language, "invalid language tag");

        Map<String, Object> reasoner = copy(base);
        object(reasoner, "reasoning").remove("reasoner");
        assertInvalid(reasoner, "required reasoning without a selected reasoner");
    }

    @Test
    void hostAllowlistSupportsDnsIpv4AndIpv6ButRejectsMalformedEntries() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("minimal.yaml"));
        object(base, "network").put("allowed_hosts", List.of(
                "ontology.example.org", "127.0.0.1", "::1", "2001:db8::1"));
        assertValid(base, "valid host allowlist");

        for (String host : List.of("...", "bad_host", "-example.org", "example..org", "::::")) {
            Map<String, Object> invalid = readYaml(EXAMPLES.resolve("minimal.yaml"));
            object(invalid, "network").put("allowed_hosts", List.of(host));
            assertInvalid(invalid, "invalid allowed host " + host);
        }
    }

    @Test
    void waiverExpiryRequiresAnIsoDateShapeEvenWhenFormatIsAnnotationOnly() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("minimal.yaml"));
        object(base, "validation").put("waivers", List.of(Map.of(
                "rule_id", "annotation.definition.required",
                "reason", "Migration window",
                "owner", "ontology-team",
                "expires", "banana")));
        assertInvalid(base, "malformed waiver expiry");
    }

    @Test
    void lockedImportsAndRequiredAssetStagesFailClosedStructurally() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("general-owl.yaml"));

        Map<String, Object> missingLock = copy(base);
        object(missingLock, "imports").remove("lockfile");
        assertInvalid(missingLock, "locked imports without lockfile");

        Map<String, Object> missingInvariants = copy(base);
        object(missingInvariants, "validation").remove("invariants");
        assertInvalid(missingInvariants, "required invariants without paths");

        Map<String, Object> missingCqs = copy(base);
        object(missingCqs, "validation").remove("competency_questions");
        assertInvalid(missingCqs, "required cqs without configuration");

        Map<String, Object> missingShacl = copy(base);
        object(missingShacl, "validation").remove("shacl");
        assertInvalid(missingShacl, "required shacl without paths");
    }

    @Test
    void rejectsUnknownNestedFieldsInsteadOfSilentlyReinterpretingThem() throws IOException {
        Map<String, Object> base = readYaml(EXAMPLES.resolve("general-owl.yaml"));
        object(base, "release").put("atomic_magic", true);
        assertInvalid(base, "unknown nested field");
    }

    private static void assertInvalid(Map<String, Object> policy, String caseName) {
        ValidationResponse response = VALIDATOR.validate(schema, policy);
        assertFalse(response.valid(), () -> caseName + " unexpectedly passed");
    }

    private static void assertValid(Map<String, Object> policy, String caseName) {
        ValidationResponse response = VALIDATOR.validate(schema, policy);
        assertTrue(response.valid(), () -> caseName + ": " + response.errorMessage());
    }

    private static Map<String, Object> readYaml(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), () -> "missing policy example: " + path);
        return YAML.readValue(Files.readString(path), new TypeReference<>() { });
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return JSON.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> value) {
        return (Map<String, Object>) value.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> value, String key) {
        return (Map<String, Object>) value.get(key);
    }
}
