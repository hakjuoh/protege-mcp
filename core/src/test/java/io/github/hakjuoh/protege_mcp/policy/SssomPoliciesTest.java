package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.sssom.SssomPolicies;
import io.github.hakjuoh.protege_mcp.sssom.SssomValidationPolicy;

class SssomPoliciesTest {

    @Test
    void convertsEveryValidatedV2MappingControl() {
        Map<String, Object> mappings = Map.of(
                "allowed_predicates", List.of("ex:mapsTo"),
                "allowed_sources", List.of("registry"),
                "allowed_licenses", List.of("https://example.org/license"),
                "require_license", true,
                "required_findings", List.of("missing_source"),
                "directional_cycle_policy", Map.of("skos:broadMatch", "warning"),
                "many_to_one_rules", List.of(Map.of(
                        "predicate", "skos:closeMatch",
                        "subject_ontologies", List.of("ex:Source"),
                        "subject_providers", List.of("ex:Provider"),
                        "target_ontologies", List.of("ex:Target"))));
        ProjectPolicy policy = new ProjectPolicy(true, "explicit", Path.of("policy.yaml"),
                Path.of("."), "digest", Map.of("version", 2, "mappings", mappings,
                        "prefixes", Map.of("ex", "https://example.org/")),
                Map.of(), List.of(), null);

        SssomValidationPolicy converted = SssomPolicies.from(policy);

        assertEquals(Set.of("ex:mapsTo"), converted.allowedPredicates());
        assertEquals(Set.of("registry"), converted.allowedSources());
        assertEquals(Set.of("https://example.org/license"), converted.allowedLicenses());
        assertTrue(converted.requireLicense());
        assertEquals(Set.of("missing_source"), converted.requiredFindings());
        assertEquals("warning", converted.directionalCyclePolicy().get("skos:broadMatch"));
        assertEquals(Set.of("ex:Provider"), converted.manyToOneRules().get(0).subjectProviders());
        assertEquals(Map.of("ex", "https://example.org/"), converted.approvedPrefixes());
    }

    @Test
    void v1AndMissingPoliciesStayStructuralOnly() {
        assertFalse(SssomPolicies.from(ProjectPolicy.notFound()).requireLicense());
        ProjectPolicy v1 = new ProjectPolicy(true, "explicit", Path.of("policy.yaml"), Path.of("."),
                "digest", Map.of("version", 1), Map.of(), List.of(), null);
        assertTrue(SssomPolicies.from(v1).allowedPredicates().isEmpty());
    }

    @Test
    void malformedManuallyConstructedV2ControlsFailAsOneCleanPolicyError() {
        for (Map<String, Object> mappings : List.of(
                Map.<String, Object>of("many_to_one_rules", List.of(Map.of(
                        "predicate", "skos:closeMatch",
                        "subject_ontologies", List.of(" ")))),
                Map.<String, Object>of("directional_cycle_policy",
                        Map.of("skos:broadMatch", "fatal")))) {
            ProjectPolicy malformed = new ProjectPolicy(true, "explicit", Path.of("policy.yaml"),
                    Path.of("."), "digest", Map.of("version", 2, "mappings", mappings),
                    Map.of(), List.of(), null);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SssomPolicies.from(malformed));
            assertEquals("Project policy v2 mapping controls are malformed", error.getMessage());
        }
    }

    @Test
    void loadedInvalidPoliciesNeverDegradeToStructuralValidation() {
        PolicyIssue issue = new PolicyIssue("error", "invalid_test", "version",
                "synthetic invalid policy");
        for (int version : List.of(1, 2)) {
            ProjectPolicy invalid = new ProjectPolicy(true, "explicit", Path.of("policy.yaml"),
                    Path.of("."), "digest", Map.of("version", version), Map.of(),
                    List.of(issue), null);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SssomPolicies.from(invalid));
            assertEquals("Invalid project policy cannot authorize SSSOM validation",
                    error.getMessage());
        }
    }
}
