package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessExecutionException;

class HeadlessToolServiceTest {

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
