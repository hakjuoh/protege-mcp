package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.jobs.JobType;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

class WindowJobRuntimeTest {

    @Test
    void normalizedVersionTwoPolicyTightensEveryRuntimeBound(@TempDir Path temp)
            throws Exception {
        ProjectPolicy policy = policy(temp, """
                jobs:
                  allowed_types: [semantic_diff, project_qc]
                  workers: 1
                  queue_capacity: 7
                  active_per_principal: 2
                  retained_per_principal: 5
                  retained_per_backend: 11
                  retention_seconds: 600
                """);
        assertTrue(policy.valid(), () -> policy.issues().toString());

        WindowJobRuntime.Settings settings =
                WindowJobRuntime.Settings.from(policy);

        assertEquals(1, settings.workers());
        assertEquals(Set.of(JobType.SEMANTIC_DIFF, JobType.PROJECT_QC),
                settings.config().allowedTypes());
        assertEquals(7, settings.config().queueCapacity());
        assertEquals(2, settings.config().activePerPrincipal());
        assertEquals(5, settings.config().retainedPerPrincipal());
        assertEquals(11, settings.config().retainedPerBackend());
        assertEquals(600, settings.config().retention().toSeconds());
    }

    @Test
    void versionOnePolicyUsesProductDefaults(@TempDir Path temp)
            throws Exception {
        Path document = temp.resolve("ontology.ttl");
        Files.writeString(document, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        Path path = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(path,
                ProjectPolicyFixtures.minimalPolicy(
                        "job-v1", "https://example.org/job-v1")
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(path, document,
                "https://example.org/job-v1", List.of());
        assertTrue(policy.valid(), () -> policy.issues().toString());

        WindowJobRuntime.Settings settings =
                WindowJobRuntime.Settings.from(policy);

        assertEquals(2, settings.workers());
        assertEquals(32, settings.config().queueCapacity());
        assertEquals(Set.of(JobType.values()), settings.config().allowedTypes());
    }

    @Test
    void inconsistentCrossFieldBoundsFailClosed(@TempDir Path temp)
            throws Exception {
        ProjectPolicy policy = policy(temp, """
                jobs:
                  allowed_types: [classification]
                  workers: 2
                  queue_capacity: 32
                  active_per_principal: 8
                  retained_per_principal: 1
                  retained_per_backend: 1
                  retention_seconds: 3600
                """);
        assertEquals(false, policy.valid());
        assertTrue(policy.issues().stream().anyMatch(issue ->
                "job_retention_below_active".equals(issue.code())));

        ToolArgException failure = assertThrows(ToolArgException.class,
                () -> WindowJobRuntime.Settings.from(policy));

        assertEquals("job_policy_invalid", failure.code());
        assertEquals(true, failure.details().get("effects_prevented"));
    }

    private static ProjectPolicy policy(Path temp, String jobs)
            throws Exception {
        Path document = temp.resolve("ontology.ttl");
        Files.writeString(document, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        Path path = temp.resolve("policy.yaml");
        String yaml = ProjectPolicyFixtures.minimalPolicy(
                "job-v2", "https://example.org/job-v2")
                .replace("version: 1", "version: 2")
                + "validation:\n  required_stages: [structural]\n" + jobs;
        ProjectPolicyFixtures.writePolicy(path, yaml);
        ProjectPolicy policy = ProjectPolicyLoader.load(path, document,
                "https://example.org/job-v2", List.of());
        return policy;
    }
}
