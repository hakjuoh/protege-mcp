package io.github.hakjuoh.protege_mcp.contracts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** Closed JSON Schema subset used consistently by live and headless MCP contracts. */
public final class ToolSchemaValidator {

    private static final ObjectMapper JSON = ContractJson.mapper();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12);
    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean", "null");
    private static final Set<String> FIELDS = Set.of(
            "type", "title", "description", "properties", "required",
            "additionalProperties", "propertyNames", "items", "enum", "const", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern",
            "format", "minItems", "maxItems", "uniqueItems", "minProperties",
            "maxProperties", "allOf", "anyOf", "oneOf", "not", "default", "examples");

    private ToolSchemaValidator() {
    }

    public static void validateInput(Map<String, Object> schema, String path) {
        JsonNode node = JSON.valueToTree(ImmutableJson.map(schema));
        validateInput(node, path);
    }

    public static void validateInput(JsonNode schema, String path) {
        validateRoot(schema, path, true);
    }

    public static void validateOutput(Map<String, Object> schema, String path) {
        JsonNode node = JSON.valueToTree(ImmutableJson.map(schema));
        validateOutput(node, path);
    }

    public static void validateOutput(JsonNode schema, String path) {
        validateRoot(schema, path, false);
    }

    /** Validate a post-0.7.2 success schema whose result fields may not be unconstrained. */
    public static void validateTypedOutput(Map<String, Object> schema, String path) {
        JsonNode node = JSON.valueToTree(ImmutableJson.map(schema));
        validateTypedOutput(node, path);
    }

    public static void validateTypedOutput(JsonNode schema, String path) {
        validateOutput(schema, path);
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            throw invalid(path + ".properties must declare at least one typed result field");
        }
        if (!schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").booleanValue()) {
            throw invalid(path + ".additionalProperties must be false for a post-0.7.2 tool");
        }
        properties.properties().forEach(entry -> requireTypedNode(
                entry.getValue(), path + ".properties." + entry.getKey()));
    }

    public static Compiled compile(Map<String, Object> schema) {
        JsonNode node = JSON.valueToTree(ImmutableJson.map(schema));
        return new Compiled(SCHEMAS.getSchema(node));
    }

    private static void validateRoot(JsonNode schema, String path, boolean input) {
        requireObject(schema, path);
        validateNode(schema, path);
        if (!"object".equals(text(schema.get("type")))) {
            throw invalid(path + ".type must be 'object'");
        }
        JsonNode additional = schema.get("additionalProperties");
        if (input) {
            if (additional == null || !additional.isBoolean() || additional.booleanValue()) {
                throw invalid(path + ".additionalProperties must be false");
            }
        } else if (additional == null || !(additional.isBoolean() || additional.isObject())) {
            throw invalid(path + ".additionalProperties must be a boolean or schema");
        }
    }

    private static void validateNode(JsonNode schema, String path) {
        requireObject(schema, path);
        Set<String> unknown = new LinkedHashSet<>();
        schema.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) unknown.add(field);
        });
        if (!unknown.isEmpty()) throw invalid(path + " has unsupported JSON Schema fields " + unknown);

        JsonNode typeNode = schema.get("type");
        String type = null;
        if (typeNode != null) {
            if (!typeNode.isTextual() || !TYPES.contains(typeNode.textValue())) {
                throw invalid(path + ".type is not a supported JSON Schema type");
            }
            type = typeNode.textValue();
        }
        for (String field : List.of("title", "description", "format")) {
            JsonNode value = schema.get(field);
            if (value != null && !value.isTextual()) {
                throw invalid(path + "." + field + " must be a string");
            }
        }

        JsonNode properties = schema.get("properties");
        if (properties != null) {
            requireType(type, "object", path, "properties");
            requireObject(properties, path + ".properties");
            properties.properties().forEach(entry ->
                    validateNode(entry.getValue(), path + ".properties." + entry.getKey()));
        }
        validateRequired(schema, properties, path, type);

        JsonNode additional = schema.get("additionalProperties");
        if (additional != null) {
            requireType(type, "object", path, "additionalProperties");
            if (additional.isObject()) {
                validateNode(additional, path + ".additionalProperties");
            } else if (!additional.isBoolean()) {
                throw invalid(path + ".additionalProperties must be a boolean or schema");
            }
        }

        JsonNode propertyNames = schema.get("propertyNames");
        if (propertyNames != null) {
            requireType(type, "object", path, "propertyNames");
            validateNode(propertyNames, path + ".propertyNames");
        }

        JsonNode items = schema.get("items");
        if (items != null) {
            requireType(type, "array", path, "items");
            validateNode(items, path + ".items");
        }

        validateEnum(schema, path, type);
        validateConstrainedValue(schema, schema.get("const"), path + ".const", type);
        validateConstrainedValue(schema, schema.get("default"), path + ".default", type);
        JsonNode examples = schema.get("examples");
        if (examples != null) {
            if (!examples.isArray()) throw invalid(path + ".examples must be an array");
            for (int index = 0; index < examples.size(); index++) {
                validateConstrainedValue(schema, examples.get(index),
                        path + ".examples[" + index + "]", type);
            }
        }

        validateNumericBounds(schema, path, type);
        validateIntegerBounds(schema, path, type, "minLength", "maxLength", "string");
        validateIntegerBounds(schema, path, type, "minItems", "maxItems", "array");
        validateIntegerBounds(schema, path, type, "minProperties", "maxProperties", "object");

        JsonNode pattern = schema.get("pattern");
        if (pattern != null) {
            requireType(type, "string", path, "pattern");
            if (!pattern.isTextual()) throw invalid(path + ".pattern must be a string");
            try {
                Pattern.compile(pattern.textValue());
            } catch (PatternSyntaxException malformed) {
                throw invalid(path + ".pattern is not a valid regular expression");
            }
        }
        if (schema.has("format")) requireType(type, "string", path, "format");
        JsonNode uniqueItems = schema.get("uniqueItems");
        if (uniqueItems != null) {
            requireType(type, "array", path, "uniqueItems");
            if (!uniqueItems.isBoolean()) throw invalid(path + ".uniqueItems must be a boolean");
        }

        for (String combination : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode choices = schema.get(combination);
            if (choices == null) continue;
            if (!choices.isArray() || choices.isEmpty()) {
                throw invalid(path + "." + combination + " must be a non-empty array");
            }
            for (int index = 0; index < choices.size(); index++) {
                validateNode(choices.get(index), path + "." + combination + "[" + index + "]");
            }
        }
        JsonNode negated = schema.get("not");
        if (negated != null) validateNode(negated, path + ".not");
    }

    private static void validateRequired(JsonNode schema, JsonNode properties,
            String path, String type) {
        JsonNode required = schema.get("required");
        if (required == null) return;
        requireType(type, "object", path, "required");
        if (!required.isArray()) throw invalid(path + ".required must be an array");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode field : required) {
            if (!field.isTextual() || field.textValue().isBlank()) {
                throw invalid(path + ".required entries must be non-blank strings");
            }
            if (!seen.add(field.textValue())) {
                throw invalid(path + ".required contains duplicate '" + field.textValue() + "'");
            }
            if (properties == null || !properties.isObject() || !properties.has(field.textValue())) {
                throw invalid(path + ".required names missing property '" + field.textValue() + "'");
            }
        }
    }

    private static void validateEnum(JsonNode schema, String path, String type) {
        JsonNode values = schema.get("enum");
        if (values == null) return;
        if (!values.isArray() || values.isEmpty()) {
            throw invalid(path + ".enum must be a non-empty array");
        }
        Set<JsonNode> seen = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (!seen.add(value)) throw invalid(path + ".enum must not contain duplicates");
            validateConstrainedValue(schema, value, path + ".enum[" + index + "]", type);
        }
        JsonNode constant = schema.get("const");
        if (constant != null && !seen.contains(constant)) {
            throw invalid(path + ".const must be one of the declared enum values");
        }
    }

    private static void validateConstrainedValue(JsonNode schema, JsonNode value,
            String path, String type) {
        if (value == null) return;
        validateTypedValue(value, path, type);
        ObjectNode constraints = ((ObjectNode) schema).deepCopy();
        constraints.remove(List.of("enum", "const", "default", "examples", "title", "description"));
        List<Error> violations = SCHEMAS.getSchema(constraints).validate(value);
        if (!violations.isEmpty()) {
            throw invalid(path + " contradicts sibling constraint '"
                    + violations.get(0).getKeyword() + "'");
        }
    }

    private static void validateTypedValue(JsonNode value, String path, String type) {
        if (value == null || type == null) return;
        boolean matches = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
        if (!matches) throw invalid(path + " does not match declared type '" + type + "'");
    }

    private static void validateNumericBounds(JsonNode schema, String path, String type) {
        BigDecimal minimum = decimal(schema.get("minimum"), path + ".minimum", type);
        BigDecimal maximum = decimal(schema.get("maximum"), path + ".maximum", type);
        BigDecimal exclusiveMinimum = decimal(schema.get("exclusiveMinimum"),
                path + ".exclusiveMinimum", type);
        BigDecimal exclusiveMaximum = decimal(schema.get("exclusiveMaximum"),
                path + ".exclusiveMaximum", type);
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw invalid(path + ".minimum must not exceed maximum");
        }
        if (minimum != null && exclusiveMaximum != null
                && minimum.compareTo(exclusiveMaximum) >= 0) {
            throw invalid(path + ".minimum must be less than exclusiveMaximum");
        }
        if (exclusiveMinimum != null && maximum != null
                && exclusiveMinimum.compareTo(maximum) >= 0) {
            throw invalid(path + ".exclusiveMinimum must be less than maximum");
        }
        if (exclusiveMinimum != null && exclusiveMaximum != null
                && exclusiveMinimum.compareTo(exclusiveMaximum) >= 0) {
            throw invalid(path + ".exclusiveMinimum must be less than exclusiveMaximum");
        }
    }

    private static BigDecimal decimal(JsonNode value, String path, String type) {
        if (value == null) return null;
        if (!("integer".equals(type) || "number".equals(type))) {
            throw invalid(path + " requires numeric type");
        }
        if (!value.isNumber()) throw invalid(path + " must be a number");
        return value.decimalValue();
    }

    private static void validateIntegerBounds(JsonNode schema, String path, String type,
            String minimumName, String maximumName, String requiredType) {
        JsonNode minimum = schema.get(minimumName);
        JsonNode maximum = schema.get(maximumName);
        if (minimum == null && maximum == null) return;
        requireType(type, requiredType, path, minimum != null ? minimumName : maximumName);
        long min = integer(minimum, path + "." + minimumName);
        long max = integer(maximum, path + "." + maximumName);
        if (minimum != null && maximum != null && min > max) {
            throw invalid(path + "." + minimumName + " must not exceed " + maximumName);
        }
    }

    private static long integer(JsonNode value, String path) {
        if (value == null) return -1;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(path + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static void requireType(String actual, String expected, String path, String keyword) {
        if (actual != null && !expected.equals(actual)) {
            throw invalid(path + "." + keyword + " requires type '" + expected + "'");
        }
    }

    private static void requireTypedNode(JsonNode schema, String path) {
        boolean constrained = schema.has("type") || schema.has("enum") || schema.has("const")
                || schema.has("allOf") || schema.has("anyOf") || schema.has("oneOf")
                || schema.has("not");
        if (!constrained) {
            throw invalid(path + " must declare a type or a constraining composition");
        }
        if ("object".equals(text(schema.get("type")))) {
            JsonNode objectTail = schema.get("additionalProperties");
            if (objectTail == null || objectTail.isBoolean() && objectTail.booleanValue()) {
                throw invalid(path + ".additionalProperties must be false or a typed schema");
            }
        }
        if ("array".equals(text(schema.get("type"))) && !schema.has("items")) {
            throw invalid(path + ".items must declare the typed array element schema");
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            properties.properties().forEach(entry -> requireTypedNode(
                    entry.getValue(), path + ".properties." + entry.getKey()));
        }
        JsonNode items = schema.get("items");
        if (items != null) requireTypedNode(items, path + ".items");
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isObject()) {
            requireTypedNode(additional, path + ".additionalProperties");
        }
        JsonNode propertyNames = schema.get("propertyNames");
        if (propertyNames != null) {
            requireTypedNode(propertyNames, path + ".propertyNames");
        }
        for (String combination : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode choices = schema.get(combination);
            if (choices == null) continue;
            for (int index = 0; index < choices.size(); index++) {
                requireTypedNode(choices.get(index), path + "." + combination + "[" + index + "]");
            }
        }
        if (schema.has("not")) requireTypedNode(schema.get("not"), path + ".not");
    }

    private static void requireObject(JsonNode value, String path) {
        if (value == null || !value.isObject()) throw invalid(path + " must be an object");
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /** Precompiled runtime validator; messages expose only keyword and instance path, never values. */
    public static final class Compiled {
        private final Schema schema;

        private Compiled(Schema schema) {
            this.schema = schema;
        }

        public List<String> violations(Object instance) {
            List<Error> errors = schema.validate(JSON.valueToTree(instance));
            if (errors.isEmpty()) return List.of();
            List<String> safe = new ArrayList<>();
            for (Error error : errors.stream().limit(16).toList()) {
                String location = String.valueOf(error.getInstanceLocation());
                safe.add(error.getKeyword() + " at $"
                        + (location.isEmpty() ? "" : location.replace('/', '.')));
            }
            if (errors.size() > 16) safe.add("[TRUNCATED]");
            return List.copyOf(safe);
        }
    }
}
