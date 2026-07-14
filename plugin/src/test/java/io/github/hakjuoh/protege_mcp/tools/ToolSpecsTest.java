package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Method-level tests for the {@link ToolSpecs} factory. {@code ToolSpecs.of} is a thin, pure
 * wrapper over the MCP SDK's {@code Tool.builder(name, inputSchema)} and
 * {@code SyncToolSpecification.builder()}, so these tests assert the wiring (name/description/schema
 * flow through, the exact handler reference is preserved) plus the validation branches that the
 * builders enforce — which, per the SDK bytecode, are {@code IllegalArgumentException}s (blank name,
 * null handler), NOT the NPEs the pre-harness plan speculated about, and a *warn-and-default* rather
 * than throw for a null input schema.
 */
class ToolSpecsTest {

    /** A no-op handler; identity is what matters for the wiring assertions. */
    private static BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> noopHandler() {
        return (exchange, request) -> CallToolResult.builder().addTextContent("ok").build();
    }

    private static Map<String, Object> schema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("iri", Map.of("type", "string"));
        s.put("properties", props);
        return s;
    }

    // ---------------------------------------------------------------- happy path / wiring

    @Test
    void ofReturnsNonNullSpecification() {
        SyncToolSpecification spec = ToolSpecs.of("t", "d", schema(), noopHandler());
        assertNotNull(spec, "of() must return a specification for valid inputs");
        assertNotNull(spec.tool(), "the built specification must carry a Tool");
    }

    @Test
    void ofCopiesNameOntoTool() {
        SyncToolSpecification spec = ToolSpecs.of("create_class", "d", schema(), noopHandler());
        assertEquals("create_class", spec.tool().name(), "tool name must equal the given name");
    }

    @Test
    void ofCopiesDescriptionOntoTool() {
        SyncToolSpecification spec = ToolSpecs.of("t", "Creates an OWL class.", schema(), noopHandler());
        assertEquals("Creates an OWL class.", spec.tool().description(),
                "tool description must equal the given description");
    }

    @Test
    void ofCopiesInputSchemaOntoTool() {
        Map<String, Object> in = schema();
        SyncToolSpecification spec = ToolSpecs.of("t", "d", in, noopHandler());
        assertEquals(in, spec.tool().inputSchema(),
                "tool inputSchema must equal the given schema map");
    }

    @Test
    void ofPreservesExactHandlerReference() {
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = noopHandler();
        SyncToolSpecification spec = ToolSpecs.of("t", "d", schema(), handler);
        assertSame(handler, spec.callHandler(),
                "the callHandler must be the exact same reference that was passed in");
    }

    @Test
    void ofDoesNotInvokeTheHandlerAtBuildTime() {
        boolean[] called = { false };
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = (e, r) -> {
            called[0] = true;
            return CallToolResult.builder().addTextContent("x").build();
        };
        ToolSpecs.of("t", "d", schema(), handler);
        assertTrue(!called[0], "building the spec must not eagerly invoke the handler");
    }

    @Test
    void ofBuildsDistinctSpecificationsOnEachCall() {
        SyncToolSpecification a = ToolSpecs.of("t", "d", schema(), noopHandler());
        SyncToolSpecification b = ToolSpecs.of("t", "d", schema(), noopHandler());
        assertNotSame(a, b, "each of() call must produce a fresh specification instance");
    }

    // ---------------------------------------------------------------- description edge cases

    @Test
    void ofAcceptsEmptyDescription() {
        SyncToolSpecification spec = ToolSpecs.of("t", "", schema(), noopHandler());
        assertEquals("", spec.tool().description(), "an empty description must pass through unchanged");
    }

    @Test
    void ofAcceptsNullDescription() {
        // The Tool builder stores description verbatim and never validates it.
        SyncToolSpecification spec = ToolSpecs.of("t", null, schema(), noopHandler());
        assertEquals(null, spec.tool().description(), "a null description must pass through as null");
    }

    // ---------------------------------------------------------------- input-schema edge cases

    @Test
    void ofAcceptsEmptyInputSchema() {
        Map<String, Object> empty = new LinkedHashMap<>();
        SyncToolSpecification spec = ToolSpecs.of("t", "d", empty, noopHandler());
        assertEquals(empty, spec.tool().inputSchema(),
                "an explicitly empty schema map must be used as-is");
    }

    @Test
    void ofRejectsNullInputSchema() {
        // ToolSpecs.of routes through Tool.builder(name, inputSchema), whose two-arg constructor
        // asserts the schema is non-null (Assert.notNull -> IllegalArgumentException) before build().
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ToolSpecs.of("t", "d", null, noopHandler()),
                "a null input schema must be rejected by Tool.builder(name, inputSchema)");
        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("schema"),
                "the exception message should reference the missing input schema");
    }

    // ---------------------------------------------------------------- name validation branches

    @Test
    void ofRejectsNullName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ToolSpecs.of(null, "d", schema(), noopHandler()),
                "a null name must be rejected by the Tool builder");
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("name"),
                "the exception message should mention the offending name field");
    }

    @Test
    void ofRejectsEmptyName() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolSpecs.of("", "d", schema(), noopHandler()),
                "an empty name must be rejected (Assert.hasText requires non-blank text)");
    }

    @Test
    void ofRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolSpecs.of("   ", "d", schema(), noopHandler()),
                "a whitespace-only name must be rejected (Assert.hasText treats it as blank)");
    }

    // ---------------------------------------------------------------- handler validation branch

    @Test
    void ofRejectsNullHandler() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ToolSpecs.of("t", "d", schema(), null),
                "a null handler must be rejected by the SyncToolSpecification builder");
        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("function"),
                "the exception message should reference the missing call-tool function");
    }
}
