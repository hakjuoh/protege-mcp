package io.github.hakjuoh.protege_mcp.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PolicyIssueTest {

    @Test
    void everyDirectIssueConstructionRedactsAndBoundsItsMessage() {
        String canary = "direct-policy-secret-5J8";
        PolicyIssue issue = new PolicyIssue("error", "direct_test", "field",
                "endpoint=https://user:" + canary + "@example.org/resource api_key=" + canary
                        + " " + "x".repeat(4_000));

        assertFalse(issue.message().contains(canary));
        assertTrue(issue.message().contains("[REDACTED]"));
        assertTrue(issue.message().length() <= 2_048);
    }
}
