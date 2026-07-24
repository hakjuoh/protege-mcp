package io.github.hakjuoh.protege_mcp.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.jobs.JobService;
import io.github.hakjuoh.protege_mcp.jobs.JobType;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationCategory;

/** Closed input and output schemas for the five live-only asynchronous job tools. */
public final class JobToolSchemas {
    public static final Set<String> NAMES = Set.of(
            "start_job", "get_job", "cancel_job", "list_jobs", "export_job_artifact");

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "start_job",
            "Capture immutable inputs and start one owner-scoped asynchronous classification, "
                    + "project-QC, asserted semantic-diff, or inference-materialization job.",
            "get_job",
            "Return one immutable owner-scoped job descriptor or unknown_job.",
            "cancel_job",
            "Request monotonic cancellation without waiting for blocking computation.",
            "list_jobs",
            "List the exact owner's jobs newest-first with an opaque stable cursor.",
            "export_job_artifact",
            "Explicitly copy one verified private job artifact to an authorized project path "
                    + "using confirmation, digest checks, no-clobber, and atomic publication.");

    private JobToolSchemas() {
    }

    public static String description(String name) {
        requireName(name);
        return DESCRIPTIONS.get(name);
    }

    public static Map<String, Object> input(String name) {
        requireName(name);
        return switch (name) {
            case "start_job" -> startInput();
            case "get_job", "cancel_job" -> object(Map.of("job_id", uuid()),
                    List.of("job_id"));
            case "list_jobs" -> object(Map.of(
                    "limit", integer(1, 100),
                    "cursor", string(1, 512)), List.of());
            case "export_job_artifact" -> object(Map.of(
                    "job_id", uuid(),
                    "artifact_id", uuid(),
                    "destination", string(1, 4096),
                    "confirm", Map.of("type", "boolean", "const", true),
                    "overwrite", Map.of("type", "boolean"),
                    "expected_target_digest", digest(),
                    "policy_path", string(1, 4096)), List.of(
                            "job_id", "artifact_id", "destination", "confirm"));
            default -> throw new IllegalArgumentException("unknown job tool " + name);
        };
    }

    public static Map<String, Object> output(String name) {
        requireName(name);
        return switch (name) {
            case "start_job" -> object(Map.of(
                    "job", descriptor(),
                    "reused", Map.of("type", "boolean")), List.of("job", "reused"));
            case "get_job" -> object(Map.of("job", descriptor()), List.of("job"));
            case "cancel_job" -> object(Map.of(
                    "job", descriptor(),
                    "outcome", enumString(List.of(
                            "cancelled", "cancel_requested", "already_terminal",
                            "commit_in_progress"))), List.of("job", "outcome"));
            case "list_jobs" -> object(Map.of(
                    "jobs", array(descriptor(), 0, 100, false),
                    "next_cursor", string(1, 512)), List.of("jobs"));
            case "export_job_artifact" -> object(Map.of(
                    "exported", Map.of("type", "boolean", "const", true),
                    "job_id", uuid(),
                    "artifact_id", uuid(),
                    "path", string(1, 4096),
                    "sha256", digest(),
                    "bytes", integer(0, JobService.MAX_ARTIFACT_BYTES),
                    "overwritten", Map.of("type", "boolean"),
                    "backup_path", nullable(string(1, 4096)),
                    "interactive_write_confirmation", Map.of("type", "boolean")),
                    List.of("exported", "job_id", "artifact_id", "path", "sha256",
                            "bytes", "overwritten", "backup_path",
                            "interactive_write_confirmation"));
            default -> throw new IllegalArgumentException("unknown job tool " + name);
        };
    }

    private static Map<String, Object> startInput() {
        Map<String, Object> classification = object(Map.of(
                "limit", integer(0, 10_000),
                "policy_path", string(1, 4096)), List.of());
        Map<String, Object> projectQc = object(Map.of(
                "limit", integer(0, 10_000),
                "policy_path", string(1, 4096),
                "lock_mode", enumString(List.of("ignore", "verify", "required"))), List.of());
        Map<String, Object> semanticDiff = object(Map.of(
                "right_document", string(1, 4096),
                "limit", integer(0, 10_000),
                "policy_path", string(1, 4096),
                "include_imports", Map.of("type", "boolean", "const", false),
                "network", Map.of("type", "string", "const", "deny")),
                List.of("right_document"));
        Map<String, Object> materialization = object(Map.of(
                "categories", array(enumString(java.util.Arrays.stream(
                        MaterializationCategory.values())
                        .map(MaterializationCategory::value).toList()), 1, 6, true),
                "destination", object(Map.of(
                        "kind", enumString(List.of(
                                "new_ontology", "active_source")),
                        "identifier", string(1, 4096)), List.of("kind", "identifier")),
                "provenance", object(Map.of(
                        "generator", string(1, 512),
                        "purpose", string(1, 1024)), List.of("generator", "purpose")),
                "limits", object(Map.of(
                        "max_axioms_per_category", integer(1, 500),
                        "max_axioms_total", integer(1, 500),
                        "max_bytes", integer(1024, 67_108_864),
                        "timeout_ms", integer(1, 3_600_000)), List.of(
                                "max_axioms_per_category", "max_axioms_total",
                                "max_bytes", "timeout_ms")),
                "policy_path", string(1, 4096)),
                List.of("categories", "destination", "provenance", "limits"));
        Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
        requests.put("classification", classification);
        requests.put("project_qc", projectQc);
        requests.put("semantic_diff", semanticDiff);
        requests.put("inference_materialization", materialization);
        Map<String, Object> schema = object(Map.of(
                "type", enumString(java.util.Arrays.stream(JobType.values())
                        .map(JobType::id).toList()),
                "idempotency_key", Map.of("type", "string",
                        "pattern", "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"),
                "request", Map.of("anyOf", List.copyOf(requests.values()))),
                List.of("type", "idempotency_key", "request"));
        schema = new LinkedHashMap<>(schema);
        schema.put("oneOf", requests.entrySet().stream()
                .map(entry -> constraint(Map.of(
                        "type", Map.of("type", "string", "const", entry.getKey()),
                        "request", entry.getValue()), List.of("type", "request")))
                .toList());
        return schema;
    }

    private static Map<String, Object> descriptor() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("job_id", uuid());
        properties.put("workspace_id", uuid());
        properties.put("owner_fingerprint", digest());
        properties.put("principal_fingerprint", digest());
        properties.put("client_fingerprint", digest());
        properties.put("grant_fingerprint", digest());
        properties.put("type", jobType());
        properties.put("state", enumString(List.of(
                "queued", "running", "cancel_pending", "succeeded", "failed", "cancelled")));
        properties.put("created_at", timestamp());
        properties.put("started_at", nullable(timestamp()));
        properties.put("completed_at", nullable(timestamp()));
        properties.put("base_revision", revision());
        properties.put("policy_digest", digest());
        properties.put("phase", Map.of("type", "string",
                "pattern", "^[a-z][a-z0-9_]{0,63}$"));
        properties.put("progress_message", string(1, JobService.MAX_PROGRESS_BYTES));
        properties.put("progress_sequence", integer(0, Long.MAX_VALUE));
        properties.put("cancellation_requested", Map.of("type", "boolean"));
        properties.put("cancellation_effective", Map.of("type", "boolean"));
        properties.put("commit_started", Map.of("type", "boolean"));
        properties.put("idempotency_key", string(1, 128));
        properties.put("required_capabilities", array(
                Map.of("type", "string", "pattern",
                        "^[a-z][a-z0-9_.-]{0,63}:[a-z][a-z0-9_.:-]{0,127}$"),
                1, 16, true));
        properties.put("input_identity", inputIdentity());
        properties.put("result_discriminator", jobType());
        properties.put("result", nullable(result()));
        properties.put("error", nullable(error()));
        Map<String, Object> schema = object(
                properties, new ArrayList<>(properties.keySet()));
        schema = new LinkedHashMap<>(schema);
        List<Map<String, Object>> legal = new ArrayList<>();
        legal.addAll(typeVariants());
        schema.put("allOf", List.of(
                Map.of("oneOf", stateVariants()),
                Map.of("oneOf", legal)));
        return schema;
    }

    private static Map<String, Object> inputIdentity() {
        Map<String, Object> secondary = object(Map.of(
                "name", Map.of("type", "string",
                        "pattern", "^[A-Za-z][A-Za-z0-9_.-]{0,63}$"),
                "byte_digest", digest(),
                "provenance_digest", digest()),
                List.of("name", "byte_digest", "provenance_digest"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("model_revision", revision());
        properties.put("closure_fingerprint", digest());
        properties.put("import_lock_digest", nullable(digest()));
        properties.put("mapping_revision", nullable(digest()));
        properties.put("policy_digest", digest());
        properties.put("preflight_asset_digest", nullable(digest()));
        properties.put("reasoner_digest", nullable(digest()));
        properties.put("normalized_request_digest", digest());
        properties.put("secondary_inputs", array(secondary, 0, 32, true));
        properties.put("identity_digest", digest());
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> result() {
        return Map.of("oneOf", List.of(
                typedResult("classification", classificationResult()),
                typedResult("project_qc", projectQcResult()),
                typedResult("semantic_diff", semanticDiffResult()),
                typedResult("inference_materialization", materializationResult())));
    }

    private static Map<String, Object> typedResult(
            String discriminator, Map<String, Object> structured) {
        return object(Map.of(
                "discriminator", Map.of(
                        "type", "string", "const", discriminator),
                "structured", structured,
                "artifacts", array(
                        artifact(), 0, JobService.MAX_ARTIFACTS_PER_JOB, true),
                "audit_incomplete", Map.of("type", "boolean")),
                List.of("discriminator", "structured", "artifacts",
                        "audit_incomplete"));
    }

    private static List<Map<String, Object>> typeVariants() {
        return List.of(
                typeVariant("classification", classificationResult()),
                typeVariant("project_qc", projectQcResult()),
                typeVariant("semantic_diff", semanticDiffResult()),
                typeVariant("inference_materialization", materializationResult()));
    }

    private static Map<String, Object> typeVariant(
            String type, Map<String, Object> structured) {
        Map<String, Object> typed = typedResult(type, structured);
        return constraint(Map.of(
                "type", Map.of("type", "string", "const", type),
                "result_discriminator", Map.of(
                        "type", "string", "const", type),
                "result", nullable(typed)),
                List.of("type", "result_discriminator", "result"));
    }

    private static List<Map<String, Object>> stateVariants() {
        Map<String, Object> nullValue = Map.of("type", "null");
        Map<String, Object> falseValue = Map.of("type", "boolean", "const", false);
        Map<String, Object> trueValue = Map.of("type", "boolean", "const", true);
        return List.of(
                stateVariant("queued", nullValue, nullValue, nullValue, nullValue,
                        falseValue, falseValue, falseValue),
                stateVariant("running", timestamp(), nullValue, nullValue, nullValue,
                        falseValue, falseValue, Map.of("type", "boolean")),
                stateVariant("cancel_pending", timestamp(), nullValue, nullValue, nullValue,
                        trueValue, falseValue, falseValue),
                stateVariant("succeeded", timestamp(), timestamp(), result(), nullValue,
                        falseValue, falseValue, Map.of("type", "boolean")),
                stateVariant("failed", nullable(timestamp()), timestamp(), nullValue, error(),
                        falseValue, falseValue, Map.of("type", "boolean")),
                stateVariant("cancelled", nullable(timestamp()), timestamp(), nullValue, nullValue,
                        trueValue, trueValue, falseValue));
    }

    private static Map<String, Object> stateVariant(String state,
            Map<String, Object> started, Map<String, Object> completed,
            Map<String, Object> result, Map<String, Object> error,
            Map<String, Object> cancellationRequested,
            Map<String, Object> cancellationEffective,
            Map<String, Object> commitStarted) {
        return constraint(Map.of(
                "state", Map.of("type", "string", "const", state),
                "started_at", started,
                "completed_at", completed,
                "result", result,
                "error", error,
                "cancellation_requested", cancellationRequested,
                "cancellation_effective", cancellationEffective,
                "commit_started", commitStarted),
                List.of("state", "started_at", "completed_at", "result", "error",
                        "cancellation_requested", "cancellation_effective",
                        "commit_started"));
    }

    private static Map<String, Object> classificationResult() {
        return object(Map.of(
                "kind", Map.of("type", "string", "const", "classification"),
                "classification_completed", Map.of("type", "boolean", "const", true),
                "consistency_status", enumString(List.of(
                        "consistent", "inconsistent", "unsupported", "unknown", "untested")),
                "unsatisfiable_status", enumString(List.of(
                        "complete", "not_applicable", "unsupported", "unknown", "untested")),
                "unsatisfiable_count", nullable(integer(0, 1_000_000)),
                "capability_limited", Map.of("type", "boolean"),
                "reasoner_digest", digest(),
                "report_artifact_id", uuid()),
                List.of("kind", "classification_completed", "consistency_status",
                        "unsatisfiable_status", "unsatisfiable_count", "capability_limited",
                        "reasoner_digest", "report_artifact_id"));
    }

    private static Map<String, Object> projectQcResult() {
        return object(Map.of(
                "kind", Map.of("type", "string", "const", "project_qc"),
                "gate", enumString(List.of("pass", "fail", "error")),
                "stages_ran", integer(0, 64),
                "stages_skipped", integer(0, 64),
                "finding_count", integer(0, 1_000_000),
                "semantic_fingerprint", digest(),
                "report_artifact_id", uuid()),
                List.of("kind", "gate", "stages_ran", "stages_skipped",
                        "finding_count", "semantic_fingerprint", "report_artifact_id"));
    }

    private static Map<String, Object> semanticDiffResult() {
        return object(Map.of(
                "kind", Map.of("type", "string", "const", "semantic_diff"),
                "identical", Map.of("type", "boolean"),
                "compatibility", enumString(List.of(
                        "metadata_only", "non_breaking", "potentially_breaking")),
                "added_axioms", integer(0, Long.MAX_VALUE),
                "removed_axioms", integer(0, Long.MAX_VALUE),
                "report_artifact_id", uuid()),
                List.of("kind", "identical", "compatibility", "added_axioms",
                        "removed_axioms", "report_artifact_id"));
    }

    private static Map<String, Object> materializationResult() {
        return object(Map.of(
                "kind", Map.of("type", "string", "const", "inference_materialization"),
                "materialization_artifact_id", Map.of("type", "string",
                        "pattern", "^[A-Za-z0-9._-]{1,128}$"),
                "materialization_artifact_fingerprint", digest(),
                "materialization_digest", digest(),
                "axiom_count", integer(0, 50_000),
                "report_artifact_id", uuid()),
                List.of("kind", "materialization_artifact_id",
                        "materialization_artifact_fingerprint", "materialization_digest",
                        "axiom_count", "report_artifact_id"));
    }

    private static Map<String, Object> artifact() {
        return object(Map.of(
                "artifact_id", uuid(),
                "media_type", Map.of("type", "string",
                        "pattern", "^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/"
                                + "[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}$"),
                "sha256", digest(),
                "bytes", integer(0, JobService.MAX_ARTIFACT_BYTES),
                "created_at", timestamp(),
                "expires_at", timestamp()), List.of(
                        "artifact_id", "media_type", "sha256", "bytes",
                        "created_at", "expires_at"));
    }

    private static Map<String, Object> error() {
        return object(Map.of(
                "code", Map.of("type", "string",
                        "pattern", "^[a-z][a-z0-9_]{0,63}$"),
                "message", string(1, 2048),
                "retryable", Map.of("type", "boolean"),
                "details", Map.of("type", "object",
                        "maxProperties", 32,
                        "additionalProperties", detailValue(4))),
                List.of("code", "message", "retryable", "details"));
    }

    private static Map<String, Object> detailValue(int remainingDepth) {
        List<Map<String, Object>> alternatives = new ArrayList<>(List.of(
                string(0, 4096), Map.of("type", "integer"),
                Map.of("type", "number"), Map.of("type", "boolean"),
                Map.of("type", "null")));
        if (remainingDepth > 0) {
            Map<String, Object> nested = detailValue(remainingDepth - 1);
            alternatives.add(Map.of("type", "array", "items", nested,
                    "minItems", 0, "maxItems", 128));
            alternatives.add(Map.of("type", "object", "maxProperties", 32,
                    "additionalProperties", nested));
        }
        return Map.of("anyOf", alternatives);
    }

    private static Map<String, Object> revision() {
        return object(Map.of(
                "workspace_id", uuid(),
                "session_revision", integer(0, Long.MAX_VALUE),
                "semantic_fingerprint", digest(),
                "document_fingerprint", digest()), List.of(
                        "workspace_id", "session_revision",
                        "semantic_fingerprint", "document_fingerprint"));
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> jobType() {
        return enumString(java.util.Arrays.stream(JobType.values()).map(JobType::id).toList());
    }

    private static Map<String, Object> uuid() {
        return Map.of("type", "string", "pattern",
                "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                        + "[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    }

    private static Map<String, Object> digest() {
        return Map.of("type", "string", "pattern", "^sha256:[0-9a-f]{64}$");
    }

    private static Map<String, Object> timestamp() {
        return string(1, 64);
    }

    private static Map<String, Object> enumString(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    private static Map<String, Object> string(int minimum, int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }

    private static Map<String, Object> integer(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> array(
            Map<String, Object> items, int minimum, int maximum, boolean unique) {
        return Map.of("type", "array", "items", items, "minItems", minimum,
                "maxItems", maximum, "uniqueItems", unique);
    }

    private static Map<String, Object> object(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return ImmutableJson.map(schema);
    }

    /** Partial object constraint used under composition; the enclosing schema owns closure. */
    private static Map<String, Object> constraint(
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        // Typed-output validation requires a bounded schema here, while the enclosing object
        // owns closure. This value union covers every already-bounded enclosing property.
        schema.put("additionalProperties", detailValue(4));
        return ImmutableJson.map(schema);
    }

    private static void requireName(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown job tool " + name);
        }
    }
}
