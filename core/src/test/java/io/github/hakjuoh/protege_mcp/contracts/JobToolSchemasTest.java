package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JobToolSchemasTest {

    @Test
    void everyJobToolHasAClosedCompilableTypedContract() {
        for (String name : JobToolSchemas.NAMES) {
            Map<String, Object> input = JobToolSchemas.input(name);
            Map<String, Object> output = JobToolSchemas.output(name);
            ToolSchemaValidator.validateInput(input, name + " input");
            ToolSchemaValidator.validateOutput(output, name + " output");
            ToolSchemaValidator.validateTypedOutput(output, name + " output");
            assertEquals(false, input.get("additionalProperties"), name);
            assertEquals(false, output.get("additionalProperties"), name);
        }
    }

    @Test
    void startContractAcceptsEachSupportedRequestShapeAndRejectsOpenObjects() {
        ToolSchemaValidator.Compiled schema =
                ToolSchemaValidator.compile(JobToolSchemas.input("start_job"));
        assertTrue(schema.violations(Map.of(
                "type", "classification",
                "idempotency_key", "classify-1",
                "request", Map.of("limit", 25))).isEmpty());
        assertTrue(schema.violations(Map.of(
                "type", "project_qc",
                "idempotency_key", "qc-1",
                "request", Map.of("lock_mode", "required"))).isEmpty());
        assertTrue(schema.violations(Map.of(
                "type", "semantic_diff",
                "idempotency_key", "diff-1",
                "request", Map.of("right_document", "right.owl",
                        "include_imports", false, "network", "deny"))).isEmpty());
        assertTrue(schema.violations(Map.of(
                "type", "inference_materialization",
                "idempotency_key", "mat-1",
                "request", Map.of(
                        "categories", List.of("subclass_axioms"),
                        "destination", Map.of(
                                "kind", "new_ontology", "identifier", "urn:test:inferred"),
                        "provenance", Map.of(
                                "generator", "protege-mcp", "purpose", "test"),
                        "limits", Map.of(
                                "max_axioms_per_category", 10,
                                "max_axioms_total", 10,
                                "max_bytes", 4096,
                                "timeout_ms", 1000)))).isEmpty());
        assertFalse(schema.violations(Map.of(
                "type", "classification",
                "idempotency_key", "classify-1",
                "request", Map.of("unexpected", true))).isEmpty());
        assertFalse(schema.violations(Map.of(
                "type", "classification",
                "idempotency_key", "classify-1",
                "request", Map.of("lock_mode", "required"))).isEmpty());
        assertFalse(schema.violations(Map.of(
                "type", "semantic_diff",
                "idempotency_key", "diff-1",
                "request", Map.of("network", "deny"))).isEmpty());
        assertFalse(schema.violations(Map.of(
                "type", "semantic_diff",
                "idempotency_key", "diff-1",
                "request", Map.of("right_document", "right.owl",
                        "network", "allow"))).isEmpty());
        assertFalse(schema.violations(Map.of(
                "type", "inference_materialization",
                "idempotency_key", "mat-1",
                "request", Map.of("categories",
                        List.of("subclass_axioms")))).isEmpty());
        assertFalse(schema.violations(Map.of(
                "type", "classification",
                "idempotency_key", " spaces are invalid ",
                "request", Map.of())).isEmpty());
        assertFalse(schema.violations(Map.of(
                "type", "unsupported",
                "idempotency_key", "job-1",
                "request", Map.of())).isEmpty());
    }

    @Test
    void terminalDescriptorContractCouplesResultAndErrorShapes() {
        ToolSchemaValidator.Compiled get =
                ToolSchemaValidator.compile(JobToolSchemas.output("get_job"));
        Map<String, Object> success = new LinkedHashMap<>();
        success.put("job", descriptor("succeeded"));
        assertTrue(get.violations(success).isEmpty(), get.violations(success).toString());

        Map<String, Object> failed = new LinkedHashMap<>();
        Map<String, Object> failedDescriptor = descriptor("failed");
        failedDescriptor.put("result", null);
        failedDescriptor.put("error", Map.of(
                "code", "job_execution_failed",
                "message", "Execution failed.",
                "retryable", false,
                "details", Map.of(
                        "effects_prevented", true,
                        "category_status", Map.of(
                                "subclass_axioms", "unsupported"),
                        "collisions", List.of(Map.of(
                                "kind", "asserted", "count", 2)))));
        failed.put("job", failedDescriptor);
        assertTrue(get.violations(failed).isEmpty(), get.violations(failed).toString());

        failedDescriptor.put("unknown", true);
        assertFalse(get.violations(failed).isEmpty());
    }

    @Test
    void descriptorRejectsImpossibleStateAndResultCombinations() {
        ToolSchemaValidator.Compiled get =
                ToolSchemaValidator.compile(JobToolSchemas.output("get_job"));

        Map<String, Object> succeededWithError = descriptor("succeeded");
        succeededWithError.put("error", Map.of(
                "code", "impossible",
                "message", "A successful job cannot have an error.",
                "retryable", false,
                "details", Map.of()));
        assertFalse(get.violations(
                Map.of("job", succeededWithError)).isEmpty());

        Map<String, Object> failedWithoutError = descriptor("failed");
        failedWithoutError.put("result", null);
        failedWithoutError.put("error", null);
        assertFalse(get.violations(
                Map.of("job", failedWithoutError)).isEmpty());

        Map<String, Object> queuedWithTerminalEvidence = descriptor("queued");
        assertFalse(get.violations(
                Map.of("job", queuedWithTerminalEvidence)).isEmpty());

        Map<String, Object> mismatchedType = descriptor("succeeded");
        mismatchedType.put("type", "project_qc");
        assertFalse(get.violations(
                Map.of("job", mismatchedType)).isEmpty());

        Map<String, Object> mismatchedKind = descriptor("succeeded");
        Map<String, Object> result = new LinkedHashMap<>(
                map(mismatchedKind.get("result")));
        Map<String, Object> structured = new LinkedHashMap<>(
                map(result.get("structured")));
        structured.put("kind", "project_qc");
        result.put("structured", structured);
        mismatchedKind.put("result", result);
        assertFalse(get.violations(
                Map.of("job", mismatchedKind)).isEmpty());
    }

    @Test
    void exportRequiresExplicitConfirmationAndBoundedDigestMetadata() {
        ToolSchemaValidator.Compiled input =
                ToolSchemaValidator.compile(JobToolSchemas.input("export_job_artifact"));
        Map<String, Object> valid = Map.of(
                "job_id", uuid("1"),
                "artifact_id", uuid("2"),
                "destination", "reports/job.json",
                "confirm", true);
        assertTrue(input.violations(valid).isEmpty());
        Map<String, Object> denied = new LinkedHashMap<>(valid);
        denied.put("confirm", false);
        assertFalse(input.violations(denied).isEmpty());
        denied.put("confirm", true);
        denied.put("expected_target_digest", "not-a-digest");
        assertFalse(input.violations(denied).isEmpty());
    }

    @Test
    void schemasAreImmutableAndUnknownNamesFailClosed() {
        assertThrows(UnsupportedOperationException.class,
                () -> JobToolSchemas.input("get_job").put("open", true));
        assertThrows(IllegalArgumentException.class,
                () -> JobToolSchemas.input("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> JobToolSchemas.output("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> JobToolSchemas.description("missing"));
    }

    private static Map<String, Object> descriptor(String state) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("job_id", uuid("1"));
        value.put("workspace_id", uuid("2"));
        value.put("owner_fingerprint", digest("1"));
        value.put("principal_fingerprint", digest("2"));
        value.put("client_fingerprint", digest("3"));
        value.put("grant_fingerprint", digest("4"));
        value.put("type", "classification");
        value.put("state", state);
        value.put("created_at", "2026-07-23T00:00:00Z");
        value.put("started_at", "2026-07-23T00:00:01Z");
        value.put("completed_at", "2026-07-23T00:00:02Z");
        value.put("base_revision", revision());
        value.put("policy_digest", digest("5"));
        value.put("phase", state);
        value.put("progress_message", "Complete.");
        value.put("progress_sequence", 3);
        value.put("cancellation_requested", false);
        value.put("cancellation_effective", false);
        value.put("commit_started", false);
        value.put("idempotency_key", "classify-1");
        value.put("required_capabilities", List.of("ontology:read"));
        value.put("input_identity", inputIdentity());
        value.put("result_discriminator", "classification");
        value.put("result", Map.of(
                "discriminator", "classification",
                "structured", Map.of(
                        "kind", "classification",
                        "classification_completed", true,
                        "consistency_status", "consistent",
                        "unsatisfiable_status", "complete",
                        "unsatisfiable_count", 0,
                        "capability_limited", false,
                        "reasoner_digest", digest("6"),
                        "report_artifact_id", uuid("3")),
                "artifacts", List.of(),
                "audit_incomplete", false));
        value.put("error", null);
        return value;
    }

    private static Map<String, Object> inputIdentity() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("model_revision", revision());
        value.put("closure_fingerprint", digest("7"));
        value.put("import_lock_digest", null);
        value.put("mapping_revision", null);
        value.put("policy_digest", digest("5"));
        value.put("preflight_asset_digest", null);
        value.put("reasoner_digest", digest("6"));
        value.put("normalized_request_digest", digest("8"));
        value.put("secondary_inputs", List.of());
        value.put("identity_digest", digest("9"));
        return value;
    }

    private static Map<String, Object> revision() {
        return Map.of(
                "workspace_id", uuid("2"),
                "session_revision", 4,
                "semantic_fingerprint", digest("a"),
                "document_fingerprint", digest("b"));
    }

    private static String digest(String character) {
        return "sha256:" + character.repeat(64);
    }

    private static String uuid(String character) {
        return "00000000-0000-4000-8000-00000000000" + character;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
