package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

class ProjectPolicyPublicBoundaryTest {

    @Test
    void adapterErrorsCannotPushThePublicIssueListPastItsBound(@TempDir Path temp)
            throws Exception {
        StringBuilder yaml = new StringBuilder(ProjectPolicyFixtures.minimalPolicy(
                "public-bound", "https://example.org/ontology"));
        yaml.append("modules:\n");
        for (int i = 0; i < 200; i++) {
            yaml.append("  - {ontology_iri: 'https://example.org/module/")
                    .append(i).append("', path: 'missing-").append(i).append(".ttl'}\n");
        }
        Path path = temp.resolve("policy.yaml");
        ProjectPolicyFixtures.writePolicy(path, yaml.toString());
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        assertEquals(ProjectPolicyLoader.MAX_POLICY_ISSUES, policy.issues().size());

        Map<String, Object> result = ProjectPolicyTools.toJson(policy,
                new ProjectPolicyTools.PolicyContext(null, null, List.of(), null), false, true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) result.get("errors");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) result.get("warnings");
        assertEquals(ProjectPolicyLoader.MAX_POLICY_ISSUES, errors.size() + warnings.size());
        assertTrue(errors.stream().anyMatch(error ->
                "active_ontology_anonymous".equals(error.get("code"))));
        assertEquals("policy_issues_truncated", errors.get(errors.size() - 1).get("code"));
        assertEquals(false, result.get("valid"));
    }
}
