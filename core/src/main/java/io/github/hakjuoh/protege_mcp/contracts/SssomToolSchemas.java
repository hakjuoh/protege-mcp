package io.github.hakjuoh.protege_mcp.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict input/output JSON Schemas shared by the six 0.8 SSSOM tools. */
public final class SssomToolSchemas {

    public static final Set<String> NAMES = Set.of("list_mappings", "add_mapping",
            "remove_mapping", "import_sssom", "export_sssom", "validate_mappings");

    private SssomToolSchemas() {
    }

    public static Map<String, Object> input(String name, boolean policyPath) {
        requireName(name);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        properties.put("path", path("Explicit canonical store path for policy v1/no-policy projects."));
        if (policyPath) properties.put("policy_path", path("Optional explicit project policy path."));
        switch (name) {
            case "list_mappings", "validate_mappings" -> {
                properties.put("limit", integer(1, 200));
                properties.put("cursor", string(1, 512));
            }
            case "add_mapping" -> {
                properties.put("expected_mapping_revision", digest());
                properties.put("mapping", stringMap(128));
                properties.put("mapping_set_id", string(1, 4096));
                properties.put("license", string(1, 4096));
                properties.put("prefix_map", stringMap(128));
                properties.put("confirm", Map.of("type", "boolean", "const", true));
                required.addAll(List.of("expected_mapping_revision", "mapping", "confirm"));
            }
            case "remove_mapping" -> {
                properties.put("expected_mapping_revision", digest());
                properties.put("mapping_id", string(1, 65_536));
                properties.put("confirm", Map.of("type", "boolean", "const", true));
                required.addAll(List.of("expected_mapping_revision", "mapping_id", "confirm"));
            }
            case "import_sssom" -> {
                properties.put("expected_mapping_revision", digest());
                properties.put("source", path("Project-confined SSSOM 1.0 TSV source."));
                properties.put("mode", Map.of("type", "string",
                        "enum", List.of("replace", "merge")));
                properties.put("confirm", Map.of("type", "boolean", "const", true));
                required.addAll(List.of("expected_mapping_revision", "source", "mode", "confirm"));
            }
            case "export_sssom" -> {
                properties.put("expected_mapping_revision", digest());
                properties.put("destination", path("Existing project-confined destination directory/file."));
                properties.put("overwrite", Map.of("type", "boolean"));
                properties.put("expected_target_digest", digest());
                properties.put("spreadsheet_safe", Map.of("type", "boolean"));
                properties.put("confirm", Map.of("type", "boolean", "const", true));
                required.addAll(List.of("expected_mapping_revision", "destination", "confirm"));
            }
            default -> throw new IllegalArgumentException("unknown mapping tool " + name);
        }
        return object(properties, required);
    }

    public static Map<String, Object> output(String name) {
        requireName(name);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if ("list_mappings".equals(name) || "validate_mappings".equals(name)) {
            snapshotProperties(properties, required);
            properties.put("returned", integer(0, 200));
            properties.put("next_cursor", string(1, 512));
            required.add("returned");
            if ("list_mappings".equals(name)) {
                properties.put("items", array(recordSchema(), 200));
                required.add("items");
            } else {
                properties.put("findings", array(findingSchema(), 200));
                required.add("findings");
            }
            return object(properties, required);
        }
        if ("export_sssom".equals(name)) {
            properties.put("committed", Map.of("type", "boolean"));
            properties.put("path", path("Exported file."));
            properties.put("mapping_revision", digest());
            properties.put("sha256", digest());
            properties.put("bytes", integer(0, 67_108_864));
            properties.put("backup_path", path("Verified previous target backup."));
            properties.put("spreadsheet_safe", Map.of("type", "boolean"));
            properties.put("lossless", Map.of("type", "boolean"));
            required.addAll(List.of("committed", "path", "mapping_revision", "sha256", "bytes",
                    "spreadsheet_safe", "lossless"));
            return object(properties, required);
        }
        mutationProperties(properties, required);
        if ("import_sssom".equals(name)) {
            properties.put("mode", Map.of("type", "string", "enum", List.of("replace", "merge")));
            properties.put("source_records", integer(0, 100_000));
            required.addAll(List.of("mode", "source_records"));
        }
        return object(properties, required);
    }

    private static void snapshotProperties(Map<String, Object> properties, List<String> required) {
        properties.put("path", path("Canonical store."));
        properties.put("exists", Map.of("type", "boolean"));
        properties.put("mapping_revision", digest());
        properties.put("canonical_bytes", integer(0, 67_108_864));
        properties.put("record_count", integer(0, 100_000));
        validationProperties(properties, required);
        required.addAll(0, List.of("path", "exists", "mapping_revision",
                "canonical_bytes", "record_count"));
    }

    private static void mutationProperties(Map<String, Object> properties, List<String> required) {
        properties.put("committed", Map.of("type", "boolean"));
        properties.put("path", path("Canonical store."));
        properties.put("previous_mapping_revision", digest());
        properties.put("mapping_revision", digest());
        properties.put("record_count", integer(0, 100_000));
        properties.put("bytes", integer(0, 67_108_864));
        properties.put("backup_path", path("Verified previous target backup."));
        validationProperties(properties, required);
        required.addAll(0, List.of("committed", "path", "previous_mapping_revision",
                "mapping_revision", "record_count", "bytes"));
    }

    private static void validationProperties(Map<String, Object> properties, List<String> required) {
        properties.put("valid", Map.of("type", "boolean"));
        properties.put("error_count", integer(0, 2_000));
        properties.put("warning_count", integer(0, 2_000));
        properties.put("findings_truncated", Map.of("type", "boolean"));
        required.addAll(List.of("valid", "error_count", "warning_count", "findings_truncated"));
    }

    private static Map<String, Object> recordSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mapping_id", string(1, 65_536));
        properties.put("subject_id", string(0, 65_536));
        properties.put("predicate_id", string(0, 65_536));
        properties.put("object_id", string(0, 65_536));
        properties.put("cells", stringMap(128));
        return object(properties, List.of("mapping_id", "subject_id", "predicate_id",
                "object_id", "cells"));
    }

    private static Map<String, Object> findingSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("severity", Map.of("type", "string", "enum", List.of("error", "warning")));
        properties.put("code", Map.of("type", "string", "pattern", "^[a-z][a-z0-9_]{0,63}$"));
        properties.put("mapping_id", string(1, 65_536));
        properties.put("column", string(1, 128));
        properties.put("message", string(1, 1024));
        return object(properties, List.of("severity", "code", "message"));
    }

    private static Map<String, Object> array(Map<String, Object> items, int maximum) {
        return Map.of("type", "array", "items", items, "maxItems", maximum);
    }

    private static Map<String, Object> stringMap(int maximum) {
        return Map.of("type", "object", "maxProperties", maximum,
                "additionalProperties", string(0, 65_536));
    }

    private static Map<String, Object> path(String description) {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096,
                "description", description);
    }

    private static Map<String, Object> digest() {
        return Map.of("type", "string", "pattern", "^sha256:[0-9a-f]{64}$");
    }

    private static Map<String, Object> string(int minimum, int maximum) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum);
    }

    private static Map<String, Object> integer(long minimum, long maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return ImmutableJson.map(schema);
    }

    private static void requireName(String name) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("unknown mapping tool " + name);
    }
}
