package io.github.hakjuoh.protege_mcp.contracts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared immutable JSON Schemas advertised by every MCP adapter. */
public final class ToolContractSchemas {

    public static final String ERROR_SCHEMA_META_KEY =
            "io.github.hakjuoh.protege-mcp/errorSchema";
    public static final String SUCCESS_SCHEMA_META_KEY =
            "io.github.hakjuoh.protege-mcp/successSchema";

    private static final Map<String, Object> LEGACY_SUCCESS = legacySuccess();
    private static final Map<String, Object> ERROR = error();
    private static final Map<String, Object> ERROR_META = Map.of(ERROR_SCHEMA_META_KEY, ERROR);

    private ToolContractSchemas() {
    }

    /** 0.7.2-compatible result boundary until a tool publishes a narrower typed schema. */
    public static Map<String, Object> legacySuccessSchema() {
        return LEGACY_SUCCESS;
    }

    public static Map<String, Object> errorSchema() {
        return ERROR;
    }

    public static Map<String, Object> errorSchemaMeta() {
        return ERROR_META;
    }

    /** MCP wire schema covering normal and {@code isError=true} structured content. */
    public static Map<String, Object> wireOutputSchema(Map<String, Object> successSchema) {
        if (successSchema == null) throw new IllegalArgumentException("success schema is required");
        Map<String, Object> success = ImmutableJson.map(successSchema);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("anyOf", List.of(success, ERROR));
        schema.put("additionalProperties", true);
        return Collections.unmodifiableMap(schema);
    }

    public static Map<String, Object> outputSchemaMeta(Map<String, Object> successSchema) {
        return Map.of(SUCCESS_SCHEMA_META_KEY, ImmutableJson.map(successSchema),
                ERROR_SCHEMA_META_KEY, ERROR);
    }

    private static Map<String, Object> legacySuccess() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Legacy 0.7.2 structured result; individual 0.8 contracts may narrow this schema.");
        schema.put("additionalProperties", true);
        return Collections.unmodifiableMap(schema);
    }

    private static Map<String, Object> error() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("error", string(1, ToolError.MAX_MESSAGE_LENGTH,
                "Backward-compatible alias of message."));
        properties.put("code", Map.of("type", "string", "pattern", "^[a-z][a-z0-9_]{0,63}$"));
        properties.put("message", string(1, ToolError.MAX_MESSAGE_LENGTH,
                "Bounded sanitized human-readable failure."));
        properties.put("details", Map.of("type", "object", "maxProperties", ToolError.MAX_DETAILS,
                "additionalProperties", true));
        properties.put("retryable", Map.of("type", "boolean"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.of("error", "code", "message", "retryable"));
        schema.put("additionalProperties", false);
        return Collections.unmodifiableMap(schema);
    }

    private static Map<String, Object> string(int minimum, int maximum, String description) {
        return Map.of("type", "string", "minLength", minimum, "maxLength", maximum,
                "description", description);
    }
}
