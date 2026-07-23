package io.github.hakjuoh.protege_mcp.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.contracts.Legacy072ToolContracts;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;
import io.github.hakjuoh.protege_mcp.contracts.SssomToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ExternalTermToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ReasonerToolSchemas;
import io.github.hakjuoh.protege_mcp.contracts.JobToolSchemas;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;

/**
 * The resource-backed source of truth for MCP tool and prompt metadata.
 *
 * <p>Handlers and prompt renderers register by name. This catalog supplies their descriptions,
 * input schemas and prompt arguments from one versioned JSON resource, and rejects malformed or
 * ambiguous metadata at startup.
 */
public final class McpCatalog {

    static final String RESOURCE = "/io/github/hakjuoh/protege_mcp/catalog/mcp-catalog.json";

    private static final int FORMAT_VERSION = 1;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern INTERNAL_REFERENCE = Pattern.compile(
            "(?i)(?:\\bADR(?:\\s+|-)0*\\d+\\b|PLAN(?:\\.md)?\\s*\u00A7|"
                    + "\u00A7\\s*\\d+|\\bM\\d+[A-Z]?\\s+milestone\\b|"
                    + "\\bmilestone\\s+M\\d+[A-Z]?\\b)");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final Map<String, ToolDefinition> tools;
    private final Map<String, PromptDefinition> prompts;

    private McpCatalog(Map<String, ToolDefinition> tools,
            Map<String, PromptDefinition> prompts) {
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.prompts = Collections.unmodifiableMap(new LinkedHashMap<>(prompts));
    }

    /** Return the shared, validated catalog loaded from the packaged resource. */
    public static McpCatalog get() {
        return Holder.INSTANCE;
    }

    /** Look up tool metadata by the stable name used by its handler registration. */
    public ToolDefinition tool(String name) {
        ToolDefinition definition = tools.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("No tool metadata named '" + name + "' in " + RESOURCE);
        }
        return definition;
    }

    /** Look up prompt metadata by the stable name used by its renderer registration. */
    public PromptDefinition prompt(String name) {
        PromptDefinition definition = prompts.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("No prompt metadata named '" + name + "' in " + RESOURCE);
        }
        return definition;
    }

    /** Tool names in resource declaration order. */
    public Set<String> toolNames() {
        return tools.keySet();
    }

    /** Prompt names in resource declaration order. */
    public Set<String> promptNames() {
        return prompts.keySet();
    }

    static McpCatalog parse(InputStream input) {
        if (input == null) {
            throw new IllegalStateException("MCP catalog resource is missing: " + RESOURCE);
        }
        try (input) {
            JsonNode root = JSON.readTree(input);
            requireObject(root, "catalog");
            rejectInternalReferences(root, "catalog");
            requireFields(root, "catalog", Set.of("version", "tools", "prompts"));
            int version = requireInteger(root, "version", "catalog");
            if (version != FORMAT_VERSION) {
                throw invalid("catalog.version must be " + FORMAT_VERSION + ", got " + version);
            }

            JsonNode toolNodes = requireArray(root, "tools", "catalog");
            JsonNode promptNodes = requireArray(root, "prompts", "catalog");
            Map<String, ToolDefinition> tools = parseTools(toolNodes);
            Map<String, PromptDefinition> prompts = parsePrompts(promptNodes);
            if (tools.isEmpty()) {
                throw invalid("catalog.tools must not be empty");
            }
            if (prompts.isEmpty()) {
                throw invalid("catalog.prompts must not be empty");
            }
            return new McpCatalog(tools, prompts);
        }
        catch (IOException e) {
            throw new IllegalStateException("Cannot read MCP catalog resource " + RESOURCE, e);
        }
    }

    private static Map<String, ToolDefinition> parseTools(JsonNode nodes) {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String path = "catalog.tools[" + index + "]";
            requireObject(node, path);
            requireFields(node, path, Set.of("name", "description", "input_schema"),
                    Set.of("output_schema"));
            String name = requireName(node, path);
            String description = requireText(node, "description", path);
            rejectInternalReference(description, path + ".description");
            if (ReasonerToolSchemas.NAMES.contains(name)) {
                description = ReasonerToolSchemas.description(name);
            } else if (JobToolSchemas.NAMES.contains(name)) {
                description = JobToolSchemas.description(name);
            }
            JsonNode schemaNode = required(node, "input_schema", path);
            requireObject(schemaNode, path + ".input_schema");
            validateInputSchema(schemaNode, path + ".input_schema");
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) immutableValue(schemaNode);
            if (SssomToolSchemas.NAMES.contains(name)) {
                // One cross-surface schema source prevents the live JSON catalog and headless
                // registry from drifting on bounds or required fields.
                schema = SssomToolSchemas.input(name, true);
            } else if (ExternalTermToolSchemas.NAMES.contains(name)) {
                schema = ExternalTermToolSchemas.input(name);
            } else if (ReasonerToolSchemas.NAMES.contains(name)) {
                schema = ReasonerToolSchemas.input(name);
            } else if (JobToolSchemas.NAMES.contains(name)) {
                schema = JobToolSchemas.input(name);
            }
            Map<String, Object> outputSchema = ToolContractSchemas.legacySuccessSchema();
            JsonNode outputNode = node.get("output_schema");
            if (outputNode != null && !outputNode.isNull()) {
                requireObject(outputNode, path + ".output_schema");
                validateOutputSchema(outputNode, path + ".output_schema");
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = (Map<String, Object>) immutableValue(outputNode);
                outputSchema = parsed;
                if (!Legacy072ToolContracts.liveToolNames().contains(name)) {
                    requireTypedOutput(outputNode, path + ".output_schema");
                }
            } else if (SssomToolSchemas.NAMES.contains(name)) {
                outputSchema = SssomToolSchemas.output(name);
                ToolSchemaValidator.validateTypedOutput(outputSchema,
                        path + ".generated_output_schema");
            } else if (ExternalTermToolSchemas.NAMES.contains(name)) {
                outputSchema = ExternalTermToolSchemas.output(name);
                ToolSchemaValidator.validateTypedOutput(outputSchema,
                        path + ".generated_output_schema");
            } else if (ReasonerToolSchemas.NAMES.contains(name)) {
                outputSchema = ReasonerToolSchemas.output(name);
                ToolSchemaValidator.validateTypedOutput(outputSchema,
                        path + ".generated_output_schema");
            } else if (JobToolSchemas.NAMES.contains(name)) {
                outputSchema = JobToolSchemas.output(name);
                ToolSchemaValidator.validateTypedOutput(outputSchema,
                        path + ".generated_output_schema");
            } else if (!Legacy072ToolContracts.liveToolNames().contains(name)) {
                throw invalid(path + ".output_schema is required for every post-0.7.2 tool");
            }
            ToolDefinition previous = definitions.putIfAbsent(
                    name, new ToolDefinition(name, description, schema, outputSchema,
                            ToolContractSchemas.errorSchema()));
            if (previous != null) {
                throw invalid("duplicate tool name '" + name + "'");
            }
        }
        return definitions;
    }

    private static Map<String, PromptDefinition> parsePrompts(JsonNode nodes) {
        Map<String, PromptDefinition> definitions = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String path = "catalog.prompts[" + index + "]";
            requireObject(node, path);
            requireFields(node, path, Set.of("name", "description", "arguments"));
            String name = requireName(node, path);
            String description = requireText(node, "description", path);
            rejectInternalReference(description, path + ".description");
            JsonNode argumentNodes = requireArray(node, "arguments", path);
            List<PromptArgument> arguments = parseArguments(argumentNodes, path);
            PromptDefinition previous = definitions.putIfAbsent(
                    name, new PromptDefinition(name, description, arguments));
            if (previous != null) {
                throw invalid("duplicate prompt name '" + name + "'");
            }
        }
        return definitions;
    }

    private static List<PromptArgument> parseArguments(JsonNode nodes, String promptPath) {
        List<PromptArgument> arguments = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String path = promptPath + ".arguments[" + index + "]";
            requireObject(node, path);
            requireFields(node, path, Set.of("name", "description", "required"));
            String name = requireName(node, path);
            if (!names.add(name)) {
                throw invalid("duplicate prompt argument '" + name + "' at " + promptPath);
            }
            String description = requireText(node, "description", path);
            rejectInternalReference(description, path + ".description");
            JsonNode required = required(node, "required", path);
            if (!required.isBoolean()) {
                throw invalid(path + ".required must be a boolean");
            }
            arguments.add(new PromptArgument(name, description, required.booleanValue()));
        }
        return Collections.unmodifiableList(arguments);
    }

    private static void validateInputSchema(JsonNode schema, String path) {
        try {
            ToolSchemaValidator.validateInput(schema, path);
        } catch (IllegalArgumentException malformed) {
            throw invalid(malformed.getMessage());
        }
    }

    private static void validateOutputSchema(JsonNode schema, String path) {
        try {
            ToolSchemaValidator.validateOutput(schema, path);
        } catch (IllegalArgumentException malformed) {
            throw invalid(malformed.getMessage());
        }
    }

    private static void requireTypedOutput(JsonNode schema, String path) {
        try {
            ToolSchemaValidator.validateTypedOutput(schema, path);
        } catch (IllegalArgumentException malformed) {
            throw invalid(malformed.getMessage());
        }
    }

    private static void rejectInternalReference(String value, String path) {
        if (INTERNAL_REFERENCE.matcher(value).find()) {
            throw invalid(path + " must describe public behavior without internal roadmap identifiers");
        }
    }

    private static void rejectInternalReferences(JsonNode node, String path) {
        if (node.isTextual()) {
            rejectInternalReference(node.textValue(), path);
        } else if (node.isObject()) {
            node.properties().forEach(entry -> rejectInternalReferences(
                    entry.getValue(), path + "." + entry.getKey()));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectInternalReferences(node.get(index), path + "[" + index + "]");
            }
        }
    }

    private static Object immutableValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.properties().forEach(entry ->
                    values.put(entry.getKey(), immutableValue(entry.getValue())));
            return Collections.unmodifiableMap(values);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(immutableValue(value)));
            return Collections.unmodifiableList(values);
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToInt() ? node.intValue() : node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isNull()) {
            return null;
        }
        throw invalid("unsupported JSON value: " + node.getNodeType());
    }

    private static String requireName(JsonNode node, String path) {
        String name = requireText(node, "name", path);
        if (!NAME.matcher(name).matches()) {
            throw invalid(path + ".name is not a stable MCP name: '" + name + "'");
        }
        return name;
    }

    private static String requireText(JsonNode node, String field, String path) {
        JsonNode value = required(node, field, path);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(path + "." + field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static int requireInteger(JsonNode node, String field, String path) {
        JsonNode value = required(node, field, path);
        if (!value.isInt()) {
            throw invalid(path + "." + field + " must be an integer");
        }
        return value.intValue();
    }

    private static JsonNode requireArray(JsonNode node, String field, String path) {
        JsonNode value = required(node, field, path);
        if (!value.isArray()) {
            throw invalid(path + "." + field + " must be an array");
        }
        return value;
    }

    private static JsonNode required(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw invalid(path + " is missing required field '" + field + "'");
        }
        return value;
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw invalid(path + " must be an object");
        }
    }

    private static void requireFields(JsonNode node, String path, Set<String> expected) {
        requireFields(node, path, expected, Set.of());
    }

    private static void requireFields(JsonNode node, String path, Set<String> required,
            Set<String> optional) {
        Set<String> actual = new LinkedHashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        Set<String> allowed = new LinkedHashSet<>(required);
        allowed.addAll(optional);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(actual);
            Set<String> unknown = new LinkedHashSet<>(actual);
            unknown.removeAll(allowed);
            throw invalid(path + " fields are invalid; missing=" + missing + ", unknown=" + unknown);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid MCP catalog: " + message);
    }

    /** Immutable metadata used to construct one MCP tool specification. */
    public record ToolDefinition(String name, String description,
            Map<String, Object> inputSchema, Map<String, Object> outputSchema,
            Map<String, Object> errorSchema) {
    }

    /** Immutable metadata used to construct one MCP prompt specification. */
    public record PromptDefinition(String name, String description,
            List<PromptArgument> arguments) {
    }

    private static final class Holder {
        private static final McpCatalog INSTANCE = load();

        private static McpCatalog load() {
            return parse(McpCatalog.class.getResourceAsStream(RESOURCE));
        }
    }
}
