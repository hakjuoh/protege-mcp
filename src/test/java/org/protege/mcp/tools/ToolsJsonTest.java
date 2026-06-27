package org.protege.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * The JSON result contract: every tool result carries the payload both as MCP {@code structuredContent}
 * and as a parseable JSON text block, and errors set {@code isError} with an {@code error} field.
 */
class ToolsJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode textJson(CallToolResult r) throws Exception {
        assertFalse(r.content().isEmpty(), "a text content block is always present");
        assertInstanceOf(TextContent.class, r.content().get(0));
        String text = ((TextContent) r.content().get(0)).text();
        return MAPPER.readTree(text);
    }

    @Test
    void okResultCarriesStructuredAndTextJson() throws Exception {
        CallToolResult r = Tools.json().put("a", 1).put("b", "x").putIfNotNull("skip", null).result();
        assertFalse(r.isError());
        assertInstanceOf(java.util.Map.class, r.structuredContent());

        JsonNode node = textJson(r);
        assertEquals(1, node.get("a").asInt());
        assertEquals("x", node.get("b").asText());
        assertFalse(node.has("skip"), "putIfNotNull(null) is omitted");
    }

    @Test
    void errorResultIsFlaggedAndStructured() throws Exception {
        CallToolResult r = Tools.error("boom");
        assertTrue(r.isError());
        assertEquals("boom", textJson(r).get("error").asText());
    }

    @Test
    void textHelperWrapsAsMessageObject() throws Exception {
        CallToolResult r = Tools.text("hello");
        assertFalse(r.isError());
        assertEquals("hello", textJson(r).get("message").asText());
    }
}
