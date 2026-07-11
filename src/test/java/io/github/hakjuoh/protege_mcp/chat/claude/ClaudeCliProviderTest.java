package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;

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

    /** A stand-in for the owner-only MCP-config file path startTurn writes; passed by path, not inline. */
    private static final String CONFIG_PATH = "/tmp/protege-mcp-abc123.json";

    @Test
    void buildsStreamingArgvWithMcpAndSessionResume() {
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("opus", "hello world", "sess-9", ENDPOINT), CONFIG_PATH);

        assertEquals("claude", cmd.get(0));
        assertTrue(cmd.contains("-p"));
        assertAdjacent(cmd, "--output-format", "stream-json");
        assertTrue(cmd.contains("--strict-mcp-config"));
        // --mcp-config carries the FILE PATH, never the token JSON.
        assertAdjacent(cmd, "--mcp-config", CONFIG_PATH);
        assertAdjacent(cmd, "--allowedTools", "mcp__protege");
        assertAdjacent(cmd, "--model", "opus");
        assertAdjacent(cmd, "--resume", "sess-9");
        // The prompt is the final positional, protected by a "--" separator.
        assertEquals("--", cmd.get(cmd.size() - 2));
        assertEquals("hello world", cmd.get(cmd.size() - 1));
    }

    @Test
    void bearerTokenNeverAppearsOnTheCommandLine() {
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("opus", "hello", "sess-9", ENDPOINT), CONFIG_PATH);
        // The token is written to the 0600 config file, so it must not leak onto any argv element
        // (where `ps` / other local users could read it). Guards the security fix.
        assertTrue(cmd.stream().noneMatch(arg -> arg.contains("TOK123")),
                "bearer token must not appear on the command line: " + cmd);
    }

    @Test
    void omitsModelAndResumeWhenBlank() {
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "hi", null, ENDPOINT), CONFIG_PATH);
        assertFalse(cmd.contains("--model"));
        assertFalse(cmd.contains("--resume"));
    }

    @Test
    void showReasoningAsksForSummarizedThinking() {
        // Claude 5-era models default thinking display to "omitted" (empty thinking text in
        // stream-json), so the opt-in must translate into the CLI-side flag.
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "hi", null, ENDPOINT, List.of(), true), CONFIG_PATH);
        assertAdjacent(cmd, "--thinking-display", "summarized");
    }

    @Test
    void noThinkingDisplayFlagWhenReasoningOff() {
        // The flag is undocumented on current CLIs; a run that didn't opt in must not risk an
        // "unknown option" failure on an older CLI.
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "hi", null, ENDPOINT), CONFIG_PATH);
        assertFalse(cmd.contains("--thinking-display"));
    }

    @Test
    void reasoningFlagRejectionNamesTheCheckbox() {
        // A CLI predating the flag fails every opted-in turn; the raw "unknown option" alone gives
        // no way to connect the failure to the persisted checkbox.
        String msg = ClaudeCliProvider.failureMessage(1,
                "error: unknown option '--thinking-display'", true);
        assertTrue(msg.contains("claude exited with code 1"));
        assertTrue(msg.contains("Show reasoning"));
    }

    @Test
    void ordinaryFailuresCarryNoReasoningHint() {
        assertFalse(ClaudeCliProvider.failureMessage(1, "some other error", true)
                .contains("Show reasoning"));
        // Same stderr without the opt-in: the flag cannot have been passed, so no hint.
        assertFalse(ClaudeCliProvider.failureMessage(1,
                "error: unknown option '--thinking-display'", false).contains("Show reasoning"));
    }

    @Test
    void attachmentDirectoriesAreAllowedForClaudeReads(@TempDir Path dir) throws Exception {
        Path image = Files.writeString(dir.resolve("screen.png"), "fake");
        ChatRequest req = new ChatRequest("", "look at [Image #1]", null, ENDPOINT,
                List.of(ChatAttachment.image("Image #1", image.toFile(), "image/png")));

        List<String> cmd = ClaudeCliProvider.buildCommand("claude", req, CONFIG_PATH);

        assertAdjacent(cmd, "--add-dir", dir.toFile().getAbsolutePath());
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1));
    }

    @Test
    void fileAttachmentDirectoriesAreAllowedForClaudeReads(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("notes.txt"), "x");
        ChatRequest req = new ChatRequest("", "see [File #1: notes.txt]", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", doc.toFile(), null)));

        List<String> cmd = ClaudeCliProvider.buildCommand("claude", req, CONFIG_PATH);

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
