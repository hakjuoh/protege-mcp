package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;

import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessExecutionException;

class HeadlessToolServiceTest {

    @TempDir
    Path temp;

    private final Path policy = Path.of("src/smoke/policy.yaml").toAbsolutePath();
    private final HeadlessToolService service = new HeadlessToolService(policy,
            new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());

    @Test
    void fixedProjectSupportsPolicyQcLockAndReleasePreviews() throws Exception {
        Map<String, Object> validated = execute("validate_project_policy", Map.of());
        assertEquals(true, validated.get("valid"));
        assertEquals("deny", validated.get("network"));

        Map<String, Object> qc = execute("run_project_qc", Map.of("limit", 5));
        assertEquals("pass", qc.get("gate"), qc::toString);

        Map<String, Object> lock = execute("verify_import_lock", Map.of());
        assertEquals(true, lock.get("valid"));
        assertEquals(false, lock.get("verified"));

        Map<String, Object> candidate = execute("write_import_lock", Map.of());
        assertEquals(false, candidate.get("written"));
        assertEquals(true, candidate.get("dry_run"));
        assertFalse(java.nio.file.Files.exists(policy.getParent().resolve("imports.lock.json")),
                "the default MCP lock operation must remain a preview");

        Map<String, Object> release = execute("run_release_gate", Map.of("limit", 5));
        assertEquals(true, release.get("dry_run"));
        assertEquals(false, release.get("prepared"));
        assertFalse(release.containsKey("publication"));

        Map<String, Object> audit = execute("export_audit_log", Map.of());
        assertEquals(false, audit.get("exported"));
        assertEquals(true, audit.get("dry_run"));
        assertEquals(".protege-mcp/audit-export.jsonl", audit.get("path"));
    }

    @Test
    void headlessReasonerCapabilitiesAndRuleValidationUseTypedOfflineContracts()
            throws Exception {
        Map<String, Object> capabilities = execute("get_reasoner_capabilities", Map.of());
        assertEquals("reviewed", capabilities.get("profile_status"), capabilities::toString);
        assertEquals(true, capabilities.get("exact_profile_match"));
        assertTrue(capabilities.toString().contains("1.3.8.431"));

        Map<String, Object> validation = execute("validate_rules", Map.of("limit", 10));
        assertEquals(false, validation.get("executed_rules"));
        assertEquals(true, validation.get("parsed_every_atom"));
        assertEquals(0, validation.get("total_rules"));
        assertEquals(true, validation.get("compatible"));
    }

    @Test
    void directHeadlessCallsEnforceTheSharedClosedInputContractAndSnapshotCodes() {
        for (Map<String, Object> invalid : java.util.List.of(
                Map.<String, Object>of("extra", true),
                Map.<String, Object>of("limit", 11),
                Map.<String, Object>of("limit", "10"),
                Map.<String, Object>of("snapshot_fingerprint", "bad"))) {
            HeadlessExecutionException failure = assertThrows(HeadlessExecutionException.class,
                    () -> execute("validate_rules", invalid));
            assertEquals("invalid_request", failure.code(), invalid::toString);
            assertEquals(true, failure.details().get("effects_prevented"));
        }

        HeadlessExecutionException required = assertThrows(HeadlessExecutionException.class,
                () -> execute("validate_rules", Map.of("offset", 1)));
        assertEquals("rule_validation_snapshot_required", required.code());
        HeadlessExecutionException changed = assertThrows(HeadlessExecutionException.class,
                () -> execute("validate_rules", Map.of("offset", 1,
                        "snapshot_fingerprint", "sha256:" + "0".repeat(64))));
        assertEquals("rule_validation_snapshot_changed", changed.code());
        assertEquals(true, changed.retryable());
    }

    @Test
    void missingContinuationSnapshotIsRejectedBeforeWorkspaceCapture() {
        HeadlessToolService missingWorkspace = new HeadlessToolService(
                temp.resolve("missing-policy.yaml"),
                new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
        HeadlessExecutionException required = assertThrows(HeadlessExecutionException.class,
                () -> missingWorkspace.execute("validate_rules", Map.of("offset", 1),
                        HeadlessToolService.DEFAULT_CAPABILITIES,
                        HeadlessStdioServer.MAX_INBOUND_MESSAGE_BYTES,
                        HeadlessStdioServer.MAX_OUTBOUND_MESSAGE_BYTES));
        assertEquals("rule_validation_snapshot_required", required.code());
    }

    @Test
    void headlessValidationParsesAndReportsARealRule() throws Exception {
        java.nio.file.Files.copy(Path.of("src/smoke/policy.yaml"), temp.resolve("policy.yaml"));
        java.nio.file.Files.copy(Path.of("src/smoke/ro-crate-metadata.json"),
                temp.resolve("ro-crate-metadata.json"));
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/cli-smoke"));
        var data = manager.getOWLDataFactory();
        var a = data.getOWLClass(IRI.create("https://example.org/A"));
        var b = data.getOWLClass(IRI.create("https://example.org/B"));
        var individual = data.getOWLNamedIndividual(IRI.create("https://example.org/individual"));
        var x = data.getSWRLVariable(IRI.create("https://example.org/var/x"));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(a, individual));
        manager.addAxiom(ontology, data.getSWRLRule(Set.of(data.getSWRLClassAtom(a, x)),
                Set.of(data.getSWRLClassAtom(b, x))));
        manager.saveOntology(ontology, new TurtleDocumentFormat(),
                IRI.create(temp.resolve("ontology.ttl").toUri()));

        HeadlessToolService local = new HeadlessToolService(temp.resolve("policy.yaml"),
                new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
        Map<String, Object> validation = local.execute("validate_rules", Map.of(),
                HeadlessToolService.DEFAULT_CAPABILITIES,
                HeadlessStdioServer.MAX_INBOUND_MESSAGE_BYTES,
                HeadlessStdioServer.MAX_OUTBOUND_MESSAGE_BYTES);
        assertEquals(1, validation.get("total_rules"));
        assertEquals(2, validation.get("parsed_atom_count"));
        assertEquals(1, validation.get("supported_rules"));
        assertEquals(true, validation.get("compatible"));
    }

    @Test
    void callerPathsRemainProjectConfinedEvenWithTheFullHeadlessProfile() {
        HeadlessExecutionException lockEscape = assertThrows(HeadlessExecutionException.class,
                () -> execute("write_import_lock",
                        Map.of("dry_run", false, "output", "../escaped-lock.json")));
        assertUnknownMutation(lockEscape, "project root");

        HeadlessExecutionException releaseEscape = assertThrows(HeadlessExecutionException.class,
                () -> execute("prepare_release",
                        Map.of("output_dir", "../escaped-release")));
        assertUnknownMutation(releaseEscape, "release output");

        HeadlessExecutionException auditClobber = assertThrows(HeadlessExecutionException.class,
                () -> execute("export_audit_log",
                        Map.of("output", ".protege-mcp/project.yaml")));
        assertUnknownMutation(auditClobber, "audit-export");
    }

    private Map<String, Object> execute(String tool, Map<String, Object> arguments) throws Exception {
        return service.execute(tool, arguments, HeadlessToolService.DEFAULT_CAPABILITIES,
                HeadlessStdioServer.MAX_INBOUND_MESSAGE_BYTES,
                HeadlessStdioServer.MAX_OUTBOUND_MESSAGE_BYTES);
    }

    private static void assertUnknownMutation(HeadlessExecutionException failure,
            String internalCauseText) {
        assertEquals("mutation_outcome_unknown", failure.code());
        assertEquals(true, failure.details().get("outcome_unknown"));
        assertEquals(true, failure.details().get("retry_requires_state_check"));
        assertFalse(failure.getMessage().contains(internalCauseText));
        assertTrue(failure.getCause().getMessage().contains(internalCauseText));
    }
}
