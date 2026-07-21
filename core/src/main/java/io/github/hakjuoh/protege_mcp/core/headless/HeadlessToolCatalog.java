package io.github.hakjuoh.protege_mcp.core.headless;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.hakjuoh.protege_mcp.core.auth.ToolCapabilityCatalog;
import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.contracts.Legacy072ToolContracts;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;
import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;
import io.github.hakjuoh.protege_mcp.contracts.SssomToolSchemas;

/** Public MCP metadata for the deliberately small, project-confined headless subset. */
public final class HeadlessToolCatalog {

    public static final String SURFACE_TOOL = "get_headless_capabilities";
    private static final List<Definition> DEFINITIONS = buildDefinitions();
    private static final Map<String, Definition> BY_NAME = byName();

    private HeadlessToolCatalog() {
    }

    public record Definition(String name, String description, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, Map<String, Object> errorSchema,
            Set<String> requiredCapabilities) {
        public Definition(String name, String description, Map<String, Object> inputSchema,
                Set<String> requiredCapabilities) {
            this(requireLegacyName(name), description, inputSchema,
                    ToolContractSchemas.legacySuccessSchema(),
                    ToolContractSchemas.errorSchema(), requiredCapabilities);
        }

        public Definition {
            if (name == null || name.isBlank() || description == null || description.isBlank()
                    || inputSchema == null || outputSchema == null || errorSchema == null
                    || requiredCapabilities == null
                    || requiredCapabilities.isEmpty() && !SURFACE_TOOL.equals(name)) {
                throw new IllegalArgumentException("headless tool metadata must be complete");
            }
            inputSchema = immutableMap(inputSchema);
            outputSchema = immutableMap(outputSchema);
            errorSchema = immutableMap(errorSchema);
            ToolSchemaValidator.validateInput(inputSchema, "headless tool '" + name + "' input");
            ToolSchemaValidator.validateOutput(outputSchema, "headless tool '" + name + "' output");
            ToolSchemaValidator.validateOutput(errorSchema, "headless tool '" + name + "' error");
            if (!ToolContractSchemas.errorSchema().equals(errorSchema)) {
                throw new IllegalArgumentException("headless tool '" + name
                        + "' must use the shared error schema");
            }
            if (!Legacy072ToolContracts.headlessToolNames().contains(name)) {
                requireTypedOutput(name, outputSchema);
            }
            requiredCapabilities = Collections.unmodifiableSet(
                    new LinkedHashSet<>(requiredCapabilities));
        }
    }

    private static String requireLegacyName(String name) {
        if (!Legacy072ToolContracts.headlessToolNames().contains(name)) {
            throw new IllegalArgumentException("New headless tool '" + name
                    + "' must declare an explicit typed output schema");
        }
        return name;
    }

    private static void requireTypedOutput(String name, Map<String, Object> schema) {
        ToolSchemaValidator.validateTypedOutput(schema, "headless tool '" + name + "' output");
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Definition definition(String name) {
        Definition definition = BY_NAME.get(name);
        if (definition == null) throw new IllegalArgumentException("Unknown headless tool: " + name);
        return definition;
    }

    public static Set<String> supportedNames() {
        return BY_NAME.keySet();
    }

    /** Live plugin tools intentionally absent from stdio, in stable plugin-catalog order. */
    public static List<String> unavailableLiveToolNames() {
        Set<String> supportedPluginNames = new LinkedHashSet<>(supportedNames());
        supportedPluginNames.remove(SURFACE_TOOL);
        return ToolCapabilityCatalog.names().stream()
                .filter(name -> !supportedPluginNames.contains(name)).toList();
    }

    private static List<Definition> buildDefinitions() {
        List<Definition> definitions = new ArrayList<>();
        definitions.add(new Definition(SURFACE_TOOL,
                "Describe the project-confined headless stdio surface, effective capabilities, "
                        + "message bounds, supported tools, and live Protégé tools that are unavailable.",
                objectSchema(Map.of(), List.of()), Set.of()));
        definitions.add(definition("validate_project_policy",
                "Validate the fixed project policy supplied when this stdio server started. Returns "
                        + "structured schema, semantic, and project-containment findings and writes nothing.",
                objectSchema(Map.of(), List.of())));
        definitions.add(mappingDefinition("list_mappings",
                "List the canonical project's SSSOM mappings with revision-bound cursor pagination."));
        definitions.add(mappingDefinition("add_mapping",
                "Add one SSSOM mapping using an expected mapping revision and explicit confirmation."));
        definitions.add(mappingDefinition("remove_mapping",
                "Remove one SSSOM mapping by stable id using an expected revision and confirmation."));
        definitions.add(mappingDefinition("import_sssom",
                "Validate and atomically replace or merge a project-confined SSSOM 1.0 TSV file."));
        definitions.add(mappingDefinition("export_sssom",
                "Atomically export the canonical mapping store with no-clobber and target-digest checks."));
        definitions.add(mappingDefinition("validate_mappings",
                "Validate the canonical mapping store and page through deterministic findings."));
        definitions.add(definition("run_project_qc",
                "Run the complete offline project QC gate against one captured, checksum-verified "
                        + "workspace snapshot using the bundled headless reasoner.",
                objectSchema(Map.of("limit", integer("Maximum findings per stage (default 25).")),
                        List.of())));
        definitions.add(definition("verify_import_lock",
                "Verify the policy-declared locked import closure offline against captured local files "
                        + "and checksums. Non-locked policies report verified=false and write nothing.",
                objectSchema(Map.of(), List.of())));
        definitions.add(definition("write_import_lock",
                "Generate the deterministic project import lock from captured local imports. Dry-run is "
                        + "the default; a write uses checksum-guarded atomic replacement with backup.",
                objectSchema(Map.of(
                        "dry_run", bool("When true (default), return the candidate without writing."),
                        "output", path("Optional project-relative output file.")), List.of())));
        definitions.add(definition("run_release_gate",
                "Run the complete offline release gate and build a bounded preview without writing. "
                        + "Any baseline manifest must resolve inside the fixed project root.",
                objectSchema(Map.of(
                        "baseline_manifest", path("Optional project-confined baseline manifest."),
                        "limit", integer("Maximum findings and report samples (default 50).")),
                        List.of())));
        definitions.add(definition("prepare_release",
                "Build the verified release bundle. Dry-run is the default; a write publishes the whole "
                        + "project-confined directory failure-atomically after the release gate passes.",
                objectSchema(Map.of(
                        "baseline_manifest", path("Optional project-confined baseline manifest."),
                        "created_at", Map.of("type", "string", "maxLength", 128,
                                "description", "Optional ISO-8601 UTC release timestamp."),
                        "dry_run", bool("When true (default), compute the bundle without writing."),
                        "limit", integer("Maximum findings and report samples (default 50)."),
                        "output_dir", path("Optional project-relative release directory.")),
                        List.of())));
        definitions.add(definition("export_audit_log",
                "Explicitly merge the redacted owner-only project audit streams into one deterministic, "
                        + "bounded JSON Lines artifact. Dry-run is the default.",
                objectSchema(Map.of(
                        "dry_run", bool("When true (default), report the target without writing."),
                        "output", path("Optional project-relative output file.")), List.of())));
        return Collections.unmodifiableList(definitions);
    }

    private static Definition definition(String name, String description,
            Map<String, Object> schema) {
        return new Definition(name, description, schema, ToolCapabilityCatalog.required(name));
    }

    private static Definition mappingDefinition(String name, String description) {
        return new Definition(name, description, SssomToolSchemas.input(name, false),
                SssomToolSchemas.output(name), ToolContractSchemas.errorSchema(),
                ToolCapabilityCatalog.required(name));
    }

    private static Map<String, Definition> byName() {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) {
            if (definitions.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalStateException("duplicate headless tool " + definition.name());
            }
        }
        return Collections.unmodifiableMap(definitions);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties,
            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "minimum", 0, "maximum", 1000,
                "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> path(String description) {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096,
                "description", description);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return ImmutableJson.map(source);
    }
}
