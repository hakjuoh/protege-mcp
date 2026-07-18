package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void resourceBackedToolResolvesMetadataByName() {
        ToolRegistry registry = new ToolRegistry();
        assertSame(registry, registry.tool("list_ontologies",
                (exchange, request) -> Tools.text("ok")));

        var specification = registry.build().get(0);
        assertEquals("list_ontologies", specification.tool().name());
        assertTrue(specification.tool().description().startsWith("List EVERY ontology"));
        assertEquals("object", specification.tool().inputSchema().get("type"));
    }

    @Test
    void nullHandlerFailsAtRegistrationNotAtTheFirstCall() {
        ToolRegistry registry = new ToolRegistry();
        for (org.junit.jupiter.api.function.Executable registration : new org.junit.jupiter.api.function.Executable[] {
                () -> registry.tool("list_ontologies", null),
                () -> registry.tool("t", "d", Map.of("type", "object"), null)}) {
            IllegalArgumentException rejected = org.junit.jupiter.api.Assertions
                    .assertThrows(IllegalArgumentException.class, registration);
            assertTrue(rejected.getMessage().contains("without a handler"), rejected.getMessage());
        }
        assertTrue(registry.build().isEmpty(), "a rejected registration must not add a spec");
    }

    @Test
    void registeredHandlerConvertsExpectedExceptionsAtTheRegistryBoundary() {
        ToolRegistry registry = new ToolRegistry();
        registry.tool("list_ontologies", (exchange, request) -> {
            throw new ToolArgException("invalid request");
        });

        var result = registry.build().get(0).callHandler().apply(null, null);

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(Map.of("error", "invalid request"), result.structuredContent());
    }
}
