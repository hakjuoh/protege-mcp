package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The Claude headless argv + the inline MCP-config JSON it hands the CLI. */
class ClaudeCliProviderTest {

    private static final McpEndpoint ENDPOINT = new McpEndpoint("http://127.0.0.1:8123/mcp", "TOK123");

    @Test
    void buildsStreamingArgvWithMcpAndSessionResume() {
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("opus", "hello world", "sess-9", ENDPOINT));

        assertEquals("claude", cmd.get(0));
        assertTrue(cmd.contains("-p"));
        assertAdjacent(cmd, "--output-format", "stream-json");
        assertTrue(cmd.contains("--strict-mcp-config"));
        assertAdjacent(cmd, "--allowedTools", "mcp__protege");
        assertAdjacent(cmd, "--model", "opus");
        assertAdjacent(cmd, "--resume", "sess-9");
        // The prompt is the final positional, protected by a "--" separator.
        assertEquals("--", cmd.get(cmd.size() - 2));
        assertEquals("hello world", cmd.get(cmd.size() - 1));
    }

    @Test
    void omitsModelAndResumeWhenBlank() {
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "hi", null, ENDPOINT));
        assertFalse(cmd.contains("--model"));
        assertFalse(cmd.contains("--resume"));
    }

    @Test
    void mcpConfigJsonDescribesAnHttpServerWithBearer() throws Exception {
        String json = ClaudeCliProvider.mcpConfigJson(ENDPOINT);
        JsonNode server = new ObjectMapper().readTree(json).path("mcpServers").path("protege");
        assertEquals("http", server.path("type").asText());
        assertEquals("http://127.0.0.1:8123/mcp", server.path("url").asText());
        assertEquals("Bearer TOK123", server.path("headers").path("Authorization").asText());
    }

    private static void assertAdjacent(List<String> cmd, String flag, String value) {
        int i = cmd.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < cmd.size(), "missing flag " + flag);
        assertEquals(value, cmd.get(i + 1), "value after " + flag);
    }
}
