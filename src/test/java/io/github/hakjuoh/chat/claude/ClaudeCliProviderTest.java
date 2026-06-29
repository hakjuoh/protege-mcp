package io.github.hakjuoh.chat.claude;

import io.github.hakjuoh.chat.ChatAttachment;
import io.github.hakjuoh.chat.ChatRequest;
import io.github.hakjuoh.chat.McpEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void attachmentDirectoriesAreAllowedForClaudeReads(@TempDir Path dir) throws Exception {
        Path image = Files.writeString(dir.resolve("screen.png"), "fake");
        ChatRequest req = new ChatRequest("", "look at [Image #1]", null, ENDPOINT,
                List.of(ChatAttachment.image("Image #1", image.toFile(), "image/png")));

        List<String> cmd = ClaudeCliProvider.buildCommand("claude", req);

        assertAdjacent(cmd, "--add-dir", dir.toFile().getAbsolutePath());
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1));
    }

    @Test
    void fileAttachmentDirectoriesAreAllowedForClaudeReads(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("notes.txt"), "x");
        ChatRequest req = new ChatRequest("", "see [File #1: notes.txt]", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", doc.toFile(), null)));

        List<String> cmd = ClaudeCliProvider.buildCommand("claude", req);

        assertAdjacent(cmd, "--add-dir", dir.toFile().getAbsolutePath());
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1));
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
