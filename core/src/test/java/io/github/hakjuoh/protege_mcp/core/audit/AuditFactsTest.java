package io.github.hakjuoh.protege_mcp.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AuditFactsTest {

    @Test
    void projectionKeepsAttributionButDropsBodiesAndMessages() {
        Map<String, Object> projected = AuditFacts.summary(Map.of(
                "committed", true,
                "change_set_id", "cs-7",
                "new_revision", Map.of("semantic_fingerprint", "sha256:abc",
                        "axioms", List.of("Secret ontology body")),
                "artifacts", List.of(Map.of("path", "release/manifest.json",
                        "sha256", "sha256:def", "content", "secret")),
                "prompt", "private prompt",
                "error", "raw error with content"));

        assertEquals(true, projected.get("committed"));
        assertEquals("cs-7", projected.get("change_set_id"));
        assertFalse(projected.toString().contains("Secret ontology body"));
        assertFalse(projected.toString().contains("private prompt"));
        assertFalse(projected.toString().contains("raw error"));
        assertFalse(projected.toString().contains("content"));
    }

    @Test
    void mutationAndConfirmationFactsAreConservative() {
        assertEquals(false, AuditFacts.committed(Map.of("prepared", false, "dry_run", true), true));
        assertEquals(true, AuditFacts.committed(Map.of("applied", 2), true));
        assertNull(AuditFacts.committed(Map.of("valid", true), false));
        assertNull(AuditFacts.committed(Map.of("message", "success"), true));
        assertNull(AuditFacts.committed(Map.of("gate", "fail"), false));
        String digest = "sha256:" + "a".repeat(64);
        assertEquals(List.of("change_set:cs-1", "policy_digest:" + digest),
                AuditFacts.confirmationReferences(Map.of(
                        "change_set_id", "cs-1", "confirm_policy_digest", digest,
                        "body", "must never be retained")));
        assertTrue(AuditFacts.confirmationReferences(Map.of(
                "change_set_id", "ontology content with spaces")).isEmpty());
    }

    @Test
    void onlyCommittedReleaseGetsAManifestLinkAndKnownGate() {
        assertEquals("release/manifest.json", AuditFacts.releaseManifest("prepare_release",
                Map.of("prepared", true, "output_dir", "release")));
        assertNull(AuditFacts.releaseManifest("prepare_release",
                Map.of("prepared", false, "output_dir", "release")));
        assertEquals("pass", AuditFacts.gate(Map.of("gate", "pass")));
        assertNull(AuditFacts.gate(Map.of("gate", "blocked")));
        assertTrue(AuditFacts.summary(Map.of("ontology_content", "secret")).isEmpty());
    }
}
