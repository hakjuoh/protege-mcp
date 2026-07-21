package io.github.hakjuoh.protege_mcp.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ToolSchemaValidatorTest {

    @Test
    void validatesNestedClosedSchemasAndRuntimeInstances() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("items", Map.of(
                        "type", "array", "minItems", 1, "uniqueItems", true,
                        "items", Map.of("type", "string", "minLength", 1))),
                "required", List.of("items"),
                "additionalProperties", false);
        assertDoesNotThrow(() -> ToolSchemaValidator.validateOutput(schema, "result"));
        ToolSchemaValidator.Compiled compiled = ToolSchemaValidator.compile(schema);
        assertTrue(compiled.violations(Map.of("items", List.of("a", "b"))).isEmpty());
        assertFalse(compiled.violations(Map.of("items", List.of())).isEmpty());
        assertFalse(compiled.violations(Map.of("items", List.of("a", "a"))).isEmpty());
        assertFalse(compiled.violations(Map.of("items", List.of("a"), "extra", true)).isEmpty());
        assertTrue(compiled.violations(Map.of("items", List.of())).stream()
                .noneMatch(message -> message.contains("[PATH]")));
    }

    @Test
    void rejectsInvalidKeywordTypesAndContradictoryBounds() {
        for (Map<String, Object> invalid : List.of(
                output(Map.of("type", "array", "uniqueItems", "yes")),
                output(Map.of("type", "number", "const", Double.NaN)),
                output(Map.of("type", "string", "minLength", 3, "maxLength", 2)),
                output(Map.of("type", "number", "minimum", 3, "maximum", 2)),
                output(Map.of("type", "object", "additionalProperties", "yes")),
                output(Map.of("type", "string", "enum", List.of("a", "a"))),
                output(Map.of("type", "string", "enum", List.of("a"), "const", "b")),
                output(Map.of("type", "string", "minLength", 2, "enum", List.of("a"))),
                output(Map.of("type", "string", "pattern", "^a$", "const", "b")),
                output(Map.of("type", "integer", "const", "1")),
                output(Map.of("type", "number", "minimum", 2, "exclusiveMaximum", 2)),
                output(Map.of("type", "number", "exclusiveMinimum", 2, "maximum", 2)),
                output(Map.of("type", "string", "pattern", "[")),
                output(Map.of("type", "string", "unknown", true)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ToolSchemaValidator.validateOutput(invalid, "result"), invalid.toString());
        }
        assertThrows(IllegalArgumentException.class, () ->
                ToolSchemaValidator.validateTypedOutput(output(Map.of()), "result"));
        assertThrows(IllegalArgumentException.class, () ->
                ToolSchemaValidator.validateTypedOutput(output(Map.of(
                        "type", "object", "properties",
                        Map.of("name", Map.of("type", "string")))), "result"));
        assertThrows(IllegalArgumentException.class, () ->
                ToolSchemaValidator.validateTypedOutput(output(Map.of("type", "array")),
                        "result"));
        assertThrows(IllegalArgumentException.class, () ->
                ToolSchemaValidator.validateTypedOutput(output(Map.of(
                        "type", "object", "properties",
                        Map.of("name", Map.of("type", "string")),
                        "additionalProperties", true)), "result"));
        assertDoesNotThrow(() -> ToolSchemaValidator.validateTypedOutput(output(Map.of(
                "type", "object", "additionalProperties", Map.of("type", "string"))),
                "result"));
    }

    @Test
    void recursivelyRejectsInvalidNotAndAdditionalPropertySchemas() {
        assertThrows(IllegalArgumentException.class, () -> ToolSchemaValidator.validateOutput(
                output(Map.of("type", "string", "not", Map.of("bogus", true))), "result"));
        assertThrows(IllegalArgumentException.class, () -> ToolSchemaValidator.validateOutput(
                Map.of("type", "object", "additionalProperties",
                        Map.of("type", "array", "items", "bad")), "result"));
        assertThrows(IllegalArgumentException.class, () -> ToolSchemaValidator.validateInput(
                Map.of("type", "object", "additionalProperties", true), "input"));
    }

    private static Map<String, Object> output(Map<String, Object> property) {
        return Map.of("type", "object", "properties", Map.of("value", property),
                "additionalProperties", false);
    }
}
