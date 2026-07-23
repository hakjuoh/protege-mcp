package io.github.hakjuoh.protege_mcp.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.reasoner.ReasonerCapabilityReport;
import io.github.hakjuoh.protege_mcp.reasoner.MaterializationCategory;

/** Strict shared contracts for reasoner capability and non-executing rule validation tools. */
public final class ReasonerToolSchemas {

    public static final Set<String> NAMES = Set.of(
            "get_reasoner_capabilities", "validate_rules",
            "materialize_inferences", "commit_materialization");
    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "get_reasoner_capabilities",
            "Report the selected reasoner identity and fail-closed OWL/SWRL capabilities "
                    + "for an exact reviewed runtime-code and semantic configuration tuple.",
            "validate_rules",
            "Validate every captured SWRL atom and built-in without executing rules. Reports "
                    + "body-variable safety separately from reasoner-profile DL-safety evidence "
                    + "with bounded snapshot-bound pagination.",
            "materialize_inferences",
            "Preview exact supported inference categories in an isolated reasoner and publish "
                    + "a private immutable 30-minute artifact without changing live state.",
            "commit_materialization",
            "Commit one owner-local materialization artifact after rechecking its complete input "
                    + "identity, policy, confirmation, destination, and verified digest.");
    private static final List<String> SUPPORT = List.of(
            "supported", "unsupported", "unknown", "untested");

    private ReasonerToolSchemas() {
    }

    public static Map<String, Object> input(String name) {
        requireName(name);
        if ("get_reasoner_capabilities".equals(name)) return object(Map.of(), List.of());
        if ("validate_rules".equals(name)) {
            return object(Map.of(
                    "include_imports", Map.of("type", "boolean"),
                    "offset", integer(0, 2_000),
                    "limit", integer(1, 10),
                    "snapshot_fingerprint", digest()), List.of());
        }
        if ("materialize_inferences".equals(name)) return materializationInput();
        return commitInput();
    }

    public static String description(String name) {
        requireName(name);
        return DESCRIPTIONS.get(name);
    }

    public static Map<String, Object> output(String name) {
        requireName(name);
        return switch (name) {
            case "get_reasoner_capabilities" -> capabilityReport();
            case "validate_rules" -> validationReport();
            case "materialize_inferences" -> materializationReport();
            case "commit_materialization" -> materializationCommit();
            default -> throw new IllegalArgumentException("unknown reasoner tool " + name);
        };
    }

    private static Map<String, Object> materializationInput() {
        List<String> categories = java.util.Arrays.stream(MaterializationCategory.values())
                .map(MaterializationCategory::value).toList();
        Map<String, Object> destination = object(Map.of(
                "kind", enumString(List.of("new_ontology", "project_file", "active_source")),
                "identifier", string(1, 4096)), List.of("kind", "identifier"));
        Map<String, Object> provenance = object(Map.of(
                "generator", string(1, 512),
                "purpose", string(1, 1024)), List.of("generator", "purpose"));
        Map<String, Object> limits = object(Map.of(
                "max_axioms_per_category", integer(1, 50_000),
                "max_axioms_total", integer(1, 50_000),
                "max_bytes", integer(1024, 67_108_864),
                "timeout_ms", integer(1, 3_600_000)), List.of(
                        "max_axioms_per_category", "max_axioms_total",
                        "max_bytes", "timeout_ms"));
        return object(Map.of(
                "categories", array(enumString(categories), 1, categories.size(), true),
                "destination", destination,
                "provenance", provenance,
                "limits", limits,
                "policy_path", string(1, 4096)), List.of(
                        "categories", "destination", "provenance", "limits"));
    }

    private static Map<String, Object> commitInput() {
        return object(Map.of(
                "artifact_id", Map.of("type", "string", "pattern",
                        "^[A-Za-z0-9._-]{1,128}$"),
                "artifact_fingerprint", digest(),
                "confirm", Map.of("type", "boolean", "const", true),
                "allow_source", Map.of("type", "boolean"),
                "collision_mode", enumString(List.of("reject", "merge", "replace")),
                "overwrite", Map.of("type", "boolean"),
                "expected_target_digest", digest(),
                "policy_path", string(1, 4096)), List.of(
                        "artifact_id", "artifact_fingerprint", "confirm"));
    }

    private static Map<String, Object> materializationReport() {
        List<String> categories = java.util.Arrays.stream(MaterializationCategory.values())
                .map(MaterializationCategory::value).toList();
        Map<String, Object> category = object(Map.of(
                "category", enumString(categories),
                "status", enumString(List.of("produced", "empty")),
                "supported", Map.of("type", "boolean", "const", true),
                "enumerated_axioms", integer(0, 50_000),
                "produced_axioms", integer(0, 50_000),
                "asserted_collisions", integer(0, 50_000),
                "canonical_bytes", integer(0, 67_108_864),
                "truncated", Map.of("type", "boolean", "const", false),
                "content_digest", digest(),
                "provenance_iri", string(1, 512)), List.of(
                        "category", "status", "supported", "enumerated_axioms",
                        "produced_axioms", "asserted_collisions", "canonical_bytes",
                        "truncated", "content_digest", "provenance_iri"));
        Map<String, Object> artifact = object(Map.of(
                "artifact_id", Map.of("type", "string", "pattern",
                        "^[A-Za-z0-9._-]{1,128}$"),
                "artifact_fingerprint", digest(),
                "artifact_digest", digest(),
                "materialization_digest", digest(),
                "created_at", string(1, 64),
                "expires_at", string(1, 64),
                "axiom_count", integer(0, 50_000),
                "canonical_bytes", integer(0, 67_108_864)), List.of(
                        "artifact_id", "artifact_fingerprint", "artifact_digest",
                        "materialization_digest", "created_at", "expires_at",
                        "axiom_count", "canonical_bytes"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", Map.of("type", "string", "const", "ready"));
        properties.put("preview_only", Map.of("type", "boolean", "const", true));
        properties.put("complete", Map.of("type", "boolean", "const", true));
        properties.put("requested_categories", array(enumString(categories), 1, 6, true));
        properties.put("supported_categories", array(enumString(categories), 1, 6, true));
        properties.put("produced_categories", array(enumString(categories), 0, 6, true));
        properties.put("skipped_categories", array(enumString(categories), 0, 6, true));
        properties.put("categories", array(category, 1, 6, true));
        properties.put("asserted_collision_count", integer(0, 50_000));
        properties.put("input_identity", materializationIdentity());
        properties.put("provenance", object(Map.of(
                "generator", string(1, 512), "purpose", string(1, 1024)),
                List.of("generator", "purpose")));
        properties.put("destination_plan", object(Map.of(
                "kind", enumString(List.of("new_ontology", "project_file", "active_source")),
                "identifier", string(1, 4096)), List.of("kind", "identifier")));
        properties.put("limits", object(Map.of(
                "max_axioms_per_category", integer(1, 50_000),
                "max_axioms_total", integer(1, 50_000),
                "max_bytes", integer(1024, 67_108_864),
                "timeout_ms", integer(1, 3_600_000)), List.of(
                        "max_axioms_per_category", "max_axioms_total",
                        "max_bytes", "timeout_ms")));
        properties.put("artifact", artifact);
        properties.put("live_state_changed", Map.of("type", "boolean", "const", false));
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> materializationIdentity() {
        Map<String, Object> revision = object(Map.of(
                "workspace_id", Map.of("type", "string", "pattern",
                        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"),
                "session_revision", integer(0, Long.MAX_VALUE),
                "semantic_fingerprint", digest(),
                "document_fingerprint", digest()), List.of(
                        "workspace_id", "session_revision", "semantic_fingerprint",
                        "document_fingerprint"));
        Map<String, Object> optionalDigest = Map.of("anyOf", List.of(
                digest(), Map.of("type", "null")));
        Map<String, Object> optionalPath = Map.of("anyOf", List.of(
                string(1, 4096), Map.of("type", "null")));
        return object(Map.of(
                "model_revision", revision,
                "closure_fingerprint", digest(),
                "import_lock_digest", optionalDigest,
                "mapping_revision", optionalDigest,
                "policy_digest", digest(),
                "policy_asset_digest", digest(),
                "policy_path", optionalPath,
                "reasoner", identity()), List.of(
                        "model_revision", "closure_fingerprint", "import_lock_digest",
                        "mapping_revision", "policy_digest", "policy_asset_digest",
                        "policy_path", "reasoner"));
    }

    private static Map<String, Object> materializationCommit() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", enumString(List.of("committed", "noop")));
        properties.put("committed", Map.of("type", "boolean"));
        properties.put("artifact_id", Map.of("type", "string", "pattern",
                "^[A-Za-z0-9._-]{1,128}$"));
        properties.put("artifact_fingerprint", digest());
        properties.put("artifact_digest", digest());
        properties.put("materialization_digest", digest());
        properties.put("destination", object(Map.of(
                "kind", enumString(List.of("new_ontology", "project_file", "active_source")),
                "identifier", string(1, 4096)), List.of("kind", "identifier")));
        properties.put("added_axioms", integer(0, 50_000));
        properties.put("existing_axioms", integer(0, 50_000));
        properties.put("asserted_collision_count", integer(0, 50_000));
        properties.put("single_undo", Map.of("type", "boolean"));
        properties.put("target_digest", digest());
        properties.put("new_revision", materializationIdentity().get("properties")
                instanceof Map<?, ?> all ? ((Map<?, ?>) all).get("model_revision") : Map.of());
        return object(properties, List.of(
                "status", "committed", "artifact_id", "artifact_fingerprint",
                "artifact_digest", "materialization_digest", "destination",
                "added_axioms", "existing_axioms", "asserted_collision_count",
                "single_undo"));
    }

    private static Map<String, Object> capabilityReport() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("vocabulary_version", Map.of("type", "string", "const",
                ReasonerCapabilityReport.VOCABULARY_VERSION));
        properties.put("profile_status", enumString(List.of("reviewed", "unknown")));
        properties.put("exact_profile_match", Map.of("type", "boolean"));
        properties.put("capability_digest", digest());
        properties.put("identity", identity());
        properties.put("owl_capabilities", closedCapabilities(
                ReasonerCapabilityReport.OWL_CAPABILITY_IDS));
        properties.put("rule_capabilities", closedCapabilities(
                ReasonerCapabilityReport.RULE_CAPABILITY_IDS));
        properties.put("swrl_atom_capabilities", closedCapabilities(
                ReasonerCapabilityReport.ATOM_CAPABILITY_IDS));
        properties.put("swrl_builtin_capabilities", closedBuiltins());
        properties.put("known_incompatibilities", array(string(1, 1024), 64));
        properties.put("absence_means_supported", Map.of("type", "boolean", "const", false));
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> validationReport() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("vocabulary_version", Map.of("type", "string", "const",
                ReasonerCapabilityReport.VOCABULARY_VERSION));
        properties.put("profile_status", enumString(List.of("reviewed", "unknown")));
        properties.put("reasoner_identity", identity());
        properties.put("snapshot_fingerprint", digest());
        properties.put("fingerprint_stability", enumString(List.of(
                "cross_restart", "session_only")));
        properties.put("fingerprint_warnings", array(string(1, 512), 4));
        properties.put("executed_rules", Map.of("type", "boolean", "const", false));
        properties.put("parsed_every_atom", Map.of("type", "boolean", "const", true));
        properties.put("compatible", Map.of("type", "boolean"));
        properties.put("coverage_complete", Map.of("type", "boolean"));
        properties.put("total_rules", integer(0, 2_000));
        properties.put("supported_rules", integer(0, 2_000));
        properties.put("unsupported_rules", integer(0, 2_000));
        properties.put("unknown_rules", integer(0, 2_000));
        properties.put("untested_rules", integer(0, 2_000));
        properties.put("incompatible_rule_count", integer(0, 2_000));
        properties.put("incompatible_rule_summaries", array(incompatibleSummary(), 2_000));
        properties.put("source_ontology_count", integer(0, 128));
        properties.put("rule_occurrence_count", integer(0, 2_000));
        properties.put("parsed_atom_count", integer(0, 20_000));
        properties.put("parsed_argument_count", integer(0, 100_000));
        properties.put("canonical_utf8_bytes", integer(0, 2_000_000));
        properties.put("capture_limits", captureLimits());
        properties.put("offset", integer(0, 2_000));
        properties.put("returned", integer(0, 10));
        properties.put("next_offset", integer(1, 2_000));
        properties.put("rules", array(rule(), 10));
        List<String> required = new ArrayList<>(properties.keySet());
        required.remove("next_offset");
        return object(properties, required);
    }

    private static Map<String, Object> identity() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("factory_id", string(1, 4096));
        properties.put("factory_class", string(1, 4096));
        properties.put("factory_binary_digest", Map.of("anyOf", List.of(
                digest(), Map.of("type", "string", "const", "unknown"))));
        properties.put("reviewed_code_digest", Map.of("anyOf", List.of(
                digest(), Map.of("type", "string", "const", "unknown"))));
        properties.put("reviewed_code_scopes", array(string(1, 4096), 0, 16, true));
        properties.put("reviewed_code_class_count", integer(0, 6_000));
        properties.put("reasoner_name", string(1, 4096));
        properties.put("implementation_version", string(1, 128));
        properties.put("configuration_class", string(1, 4096));
        properties.put("configuration_binary_digest", Map.of("anyOf", List.of(
                digest(), Map.of("type", "string", "const", "unknown"))));
        properties.put("configuration_profile", enumString(List.of(
                "owlapi_standard", "factory_default", "custom", "unrecognized")));
        properties.put("configuration_digest", digest());
        properties.put("semantic_configuration_digest", digest());
        properties.put("timeout_ms", integer(-2, Long.MAX_VALUE));
        properties.put("progress_monitor_class", string(1, 4096));
        properties.put("fresh_entity_policy", string(1, 128));
        properties.put("individual_node_set_policy", string(1, 128));
        properties.put("buffering_mode", enumString(List.of("buffering", "non_buffering")));
        properties.put("configuration_source", string(1, 128));
        properties.put("profile_key", digest());
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> capability(List<String> identifiers) {
        return object(Map.of(
                "id", enumString(identifiers),
                "status", enumString(SUPPORT),
                "evidence", string(1, 1024)), List.of("id", "status", "evidence"));
    }

    private static Map<String, Object> closedCapabilities(List<String> identifiers) {
        return exactIdentifierArray(capability(identifiers), identifiers, "id");
    }

    private static Map<String, Object> closedBuiltins() {
        Map<String, Object> item = object(Map.of(
                "iri", enumString(ReasonerCapabilityReport.PURE_BUILTIN_IRIS),
                "status", enumString(SUPPORT),
                "evidence", string(1, 1024)), List.of("iri", "status", "evidence"));
        return exactIdentifierArray(item, ReasonerCapabilityReport.PURE_BUILTIN_IRIS, "iri");
    }

    private static Map<String, Object> exactIdentifierArray(Map<String, Object> item,
            List<String> identifiers, String field) {
        Map<String, Object> schema = new LinkedHashMap<>(array(
                item, identifiers.size(), identifiers.size(), true));
        List<Map<String, Object>> requirements = identifiers.stream()
                .map(identifier -> Map.<String, Object>of("contains", Map.of(
                        "type", "object",
                        "properties", Map.of(field,
                                Map.of("type", "string", "const", identifier)),
                        "required", List.of(field),
                        "additionalProperties", Map.of("type", "string"))))
                .toList();
        schema.put("allOf", requirements);
        return ImmutableJson.map(schema);
    }

    private static Map<String, Object> rule() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rule_id", digest());
        properties.put("fingerprint_stability", enumString(List.of(
                "cross_restart", "session_only")));
        properties.put("source_ontology_count", integer(0, 128));
        properties.put("sources_truncated", Map.of("type", "boolean"));
        properties.put("source_ontologies", array(string(1, 512), 32));
        properties.put("status", enumString(SUPPORT));
        properties.put("dl_safety_status", enumString(SUPPORT));
        properties.put("dl_safety_basis", enumString(List.of(
                "reasoner_profile_engine_semantics")));
        properties.put("dl_safety_note", string(1, 512));
        properties.put("body_variable_safe", Map.of("type", "boolean"));
        properties.put("body_variable_criterion", string(1, 256));
        properties.put("missing_body_variable_count", integer(0, 100_000));
        properties.put("missing_body_variables_truncated", Map.of("type", "boolean"));
        properties.put("missing_body_variables", array(string(1, 512), 8));
        properties.put("variable_count", integer(0, 100_000));
        properties.put("atom_count", integer(0, 512));
        properties.put("atoms_truncated", Map.of("type", "boolean"));
        properties.put("finding_count", integer(0, 515));
        properties.put("findings_truncated", Map.of("type", "boolean"));
        properties.put("atoms", array(atom(), 32));
        properties.put("findings", array(finding(), 64));
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> atom() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("position", enumString(List.of("body", "head")));
        properties.put("index", integer(0, 511));
        properties.put("type", enumString(List.of("class", "object_property", "data_property",
                "data_range", "same_individual", "different_individuals", "built_in",
                "unknown")));
        properties.put("predicate", string(1, 512));
        properties.put("status", enumString(SUPPORT));
        properties.put("argument_count", integer(0, 100_000));
        properties.put("arguments_truncated", Map.of("type", "boolean"));
        properties.put("arguments", array(string(1, 512), 8));
        properties.put("variable_count", integer(0, 100_000));
        properties.put("variables_truncated", Map.of("type", "boolean"));
        properties.put("variables", array(string(1, 512), 8));
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> finding() {
        return object(Map.of(
                "severity", enumString(List.of("error", "warning")),
                "code", Map.of("type", "string", "pattern", "^[a-z][a-z0-9_]{0,63}$"),
                "message", string(1, 512)), List.of("severity", "code", "message"));
    }

    private static Map<String, Object> incompatibleSummary() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rule_id", digest());
        properties.put("status", enumString(SUPPORT));
        properties.put("source_ontology_count", integer(0, 128));
        properties.put("finding_count", integer(0, 515));
        properties.put("finding_codes", array(Map.of("type", "string",
                "pattern", "^[a-z][a-z0-9_]{0,63}$"), 16));
        properties.put("incompatible_predicates", array(string(1, 128), 2));
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> captureLimits() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source_ontologies", integer(128, 128));
        properties.put("source_identifier_characters", integer(4_096, 4_096));
        properties.put("rule_occurrences", integer(2_000, 2_000));
        properties.put("unique_rules", integer(2_000, 2_000));
        properties.put("atoms_per_rule", integer(512, 512));
        properties.put("total_atoms", integer(20_000, 20_000));
        properties.put("total_arguments", integer(100_000, 100_000));
        properties.put("rule_annotations", integer(256, 256));
        properties.put("canonical_object_characters", integer(262_144, 262_144));
        properties.put("canonical_object_nodes", integer(4_096, 4_096));
        properties.put("canonical_object_depth", integer(128, 128));
        properties.put("canonical_utf8_bytes", integer(2_000_000, 2_000_000));
        properties.put("capture_millis", integer(10_000, 10_000));
        return object(properties, new ArrayList<>(properties.keySet()));
    }

    private static Map<String, Object> digest() {
        return Map.of("type", "string", "pattern", "^sha256:[0-9a-f]{64}$");
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

    private static Map<String, Object> array(Map<String, Object> items, int maximum) {
        return Map.of("type", "array", "items", items, "maxItems", maximum);
    }

    private static Map<String, Object> array(Map<String, Object> items, int minimum,
            int maximum, boolean unique) {
        return Map.of("type", "array", "items", items, "minItems", minimum,
                "maxItems", maximum, "uniqueItems", unique);
    }

    private static Map<String, Object> object(Map<String, Object> properties,
            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return ImmutableJson.map(schema);
    }

    private static void requireName(String name) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("unknown reasoner tool " + name);
    }
}
