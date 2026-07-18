package io.github.hakjuoh.protege_mcp.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.prompts.PromptCatalog;
import io.github.hakjuoh.protege_mcp.tools.ToolCatalog;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;

class McpCatalogTest {

    @Test
    void packagedCatalogMatchesEveryRegisteredToolAndPrompt() {
        McpCatalog catalog = McpCatalog.get();
        Set<String> registeredTools = new LinkedHashSet<>();
        ToolCatalog.buildAll(new ToolContext(null, null)).forEach(
                spec -> registeredTools.add(spec.tool().name()));
        Set<String> registeredPrompts = new LinkedHashSet<>();
        PromptCatalog.buildAll().forEach(spec -> registeredPrompts.add(spec.prompt().name()));

        assertEquals(82, catalog.toolNames().size());
        assertEquals(11, catalog.promptNames().size());
        assertEquals(catalog.toolNames(), registeredTools,
                "every JSON tool definition must have exactly one handler registration");
        assertEquals(catalog.promptNames(), registeredPrompts,
                "every JSON prompt definition must have exactly one renderer registration");
    }

    @Test
    void definitionsAndNestedSchemasAreImmutable() {
        McpCatalog catalog = McpCatalog.get();
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.toolNames().remove("list_ontologies"));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.tool("search_entities").inputSchema().put("extra", true));
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>)
                catalog.tool("search_entities").inputSchema().get("properties");
        assertThrows(UnsupportedOperationException.class,
                () -> properties.put("extra", java.util.Map.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.prompt("audit_ontology").arguments().clear());
    }

    @Test
    void unknownRegistrationNamesFailWithAUsefulMessage() {
        IllegalArgumentException tool = assertThrows(IllegalArgumentException.class,
                () -> McpCatalog.get().tool("missing_tool"));
        IllegalArgumentException prompt = assertThrows(IllegalArgumentException.class,
                () -> McpCatalog.get().prompt("missing_prompt"));
        assertTrue(tool.getMessage().contains("missing_tool"));
        assertTrue(prompt.getMessage().contains("missing_prompt"));
    }

    @Test
    void parserRejectsUnknownFields() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> parse("""
                {"version":1,"tools":[{"name":"a","description":"d",
                 "input_schema":{"type":"object","additionalProperties":false},"typo":true}],
                 "prompts":[{"name":"p","description":"d","arguments":[]}]}
                """));
        assertTrue(error.getMessage().contains("unknown=[typo]"));
    }

    @Test
    void parserRejectsDuplicateNames() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> parse("""
                {"version":1,"tools":[
                 {"name":"a","description":"d","input_schema":{"type":"object","additionalProperties":false}},
                 {"name":"a","description":"d","input_schema":{"type":"object","additionalProperties":false}}],
                 "prompts":[{"name":"p","description":"d","arguments":[]}]}
                """));
        assertTrue(error.getMessage().contains("duplicate tool name 'a'"));
    }

    @Test
    void parserRejectsRequiredSchemaFieldsWithoutProperties() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> parse("""
                {"version":1,"tools":[{"name":"a","description":"d",
                 "input_schema":{"type":"object","required":["missing"],"additionalProperties":false}}],
                 "prompts":[{"name":"p","description":"d","arguments":[]}]}
                """));
        assertTrue(error.getMessage().contains("missing property 'missing'"));
    }

    @Test
    void parserRejectsDuplicateJsonFields() {
        assertThrows(IllegalStateException.class, () -> parse("""
                {"version":1,"version":1,"tools":[],"prompts":[]}
                """));
    }

    private static McpCatalog parse(String json) {
        return McpCatalog.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
