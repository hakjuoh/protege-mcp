package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Supplementary coverage for {@link ClaudeCliProvider}: the identity/model accessors, the empty spawn
 * environment, plus the {@code buildCommand}/{@code mcpConfigJson} branches the existing
 * {@code ClaudeCliProviderTest} leaves uncovered (trimming, whitespace-blank handling, multi-dir
 * attachments, exact flag sequence, and full JSON structure). Purely static/pure-record helpers only —
 * no Protégé runtime, no process spawning, no Swing.
 */
class ClaudeCliProviderCoverageTest {

    private static final McpEndpoint ENDPOINT = new McpEndpoint("http://127.0.0.1:8123/mcp", "TOK123");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Stand-in for the owner-only MCP-config file path startTurn writes and passes by path. */
    private static final String CONFIG_PATH = "/tmp/protege-mcp-cov.json";

    /** buildCommand with the config-file path fixed, so the argv-shape tests stay focused. */
    private static List<String> bc(String exe, ChatRequest req) {
        return ClaudeCliProvider.buildCommand(exe, req, CONFIG_PATH);
    }

    private final ClaudeCliProvider provider = new ClaudeCliProvider();

    // ---- identity / model accessors ----

    @Test
    void idIsTheStableProviderKey() {
        assertEquals("claude", provider.id(), "id() must be the stable 'claude' key");
    }

    @Test
    void displayNameIsHumanReadable() {
        assertEquals("Claude", provider.displayName(), "displayName() must be 'Claude'");
    }

    @Test
    void defaultModelIsBlankMeaningCliDefault() {
        assertEquals("", provider.defaultModel(), "defaultModel() must be blank (CLI's own default)");
    }

    @Test
    void listModelsHasDocumentedAliasesInOrder() {
        assertEquals(List.of("", "opus", "sonnet", "haiku", "fable"), provider.listModels(),
                "listModels() must be the documented aliases in order, blank first");
    }

    @Test
    void listModelsIsImmutable() {
        List<String> models = provider.listModels();
        assertThrows(UnsupportedOperationException.class, () -> models.add("gpt"),
                "listModels() must be an immutable list");
    }

    // ---- environment() ----

    @Test
    void environmentIsEmptySoNoSecretsLeak() {
        assertTrue(ClaudeCliProvider.environment().isEmpty(),
                "environment() must be empty so no secret keys enter the subprocess");
    }

    @Test
    void environmentIsImmutable() {
        Map<String, String> env = ClaudeCliProvider.environment();
        assertThrows(UnsupportedOperationException.class, () -> env.put("ANTHROPIC_API_KEY", "x"),
                "environment() must be immutable");
    }

    // ---- buildCommand: exact fixed flag sequence ----

    @Test
    void buildCommandEmitsFixedHeadlessStreamingFlagsInOrder() {
        List<String> cmd = bc("claude",
                new ChatRequest("", "hi", null, ENDPOINT));
        // The leading fixed prefix must be exactly this argv, in order.
        assertEquals(List.of(
                "claude", "-p",
                "--output-format", "stream-json",
                "--include-partial-messages",
                "--verbose",
                "--strict-mcp-config",
                "--mcp-config", CONFIG_PATH,
                "--allowedTools", "mcp__protege",
                "--append-system-prompt",
                io.github.hakjuoh.protege_mcp.chat.AssistantSteering.SYSTEM_PROMPT),
                cmd.subList(0, 13),
                "leading fixed argv must match the headless streaming invocation");
    }

    @Test
    void buildCommandUsesGivenExecutableAsArgvZero() {
        List<String> cmd = bc("/opt/bin/claude",
                new ChatRequest("", "hi", null, ENDPOINT));
        assertEquals("/opt/bin/claude", cmd.get(0), "argv[0] must be the resolved executable path");
    }

    @Test
    void buildCommandMcpConfigValueIsTheConfigFilePathNotTheTokenJson() {
        List<String> cmd = bc("claude",
                new ChatRequest("", "hi", null, ENDPOINT));
        int i = cmd.indexOf("--mcp-config");
        assertTrue(i >= 0 && i + 1 < cmd.size(), "--mcp-config flag must be present");
        // The value is the PATH to the owner-only config file, not the inline token JSON.
        assertEquals(CONFIG_PATH, cmd.get(i + 1), "--mcp-config value must be the config file path");
        assertTrue(cmd.stream().noneMatch(arg -> arg.contains("TOK123")),
                "the bearer token must not appear anywhere on the command line");
    }

    // ---- buildCommand: model handling ----

    @Test
    void buildCommandTrimsModelWhitespace() {
        List<String> cmd = bc("claude",
                new ChatRequest("  sonnet  ", "hi", null, ENDPOINT));
        assertAdjacent(cmd, "--model", "sonnet");
    }

    @Test
    void buildCommandOmitsModelWhenWhitespaceOnly() {
        List<String> cmd = bc("claude",
                new ChatRequest("   ", "hi", null, ENDPOINT));
        assertFalse(cmd.contains("--model"), "whitespace-only model must be treated as blank");
    }

    @Test
    void buildCommandOmitsModelWhenNull() {
        List<String> cmd = bc("claude",
                new ChatRequest(null, "hi", null, ENDPOINT));
        assertFalse(cmd.contains("--model"), "null model must omit the --model flag");
    }

    // ---- buildCommand: session handling ----

    @Test
    void buildCommandTrimsSessionIdWhitespace() {
        List<String> cmd = bc("claude",
                new ChatRequest("", "hi", "  sess-42  ", ENDPOINT));
        assertAdjacent(cmd, "--resume", "sess-42");
    }

    @Test
    void buildCommandOmitsResumeWhenSessionWhitespaceOnly() {
        List<String> cmd = bc("claude",
                new ChatRequest("", "hi", "   ", ENDPOINT));
        assertFalse(cmd.contains("--resume"), "whitespace-only sessionId must be treated as blank");
    }

    @Test
    void buildCommandIncludesModelButOmitsResumeIndependently() {
        List<String> cmd = bc("claude",
                new ChatRequest("opus", "hi", null, ENDPOINT));
        assertAdjacent(cmd, "--model", "opus");
        assertFalse(cmd.contains("--resume"), "model set with null session must still omit --resume");
    }

    // ---- buildCommand: prompt terminator ----

    @Test
    void buildCommandTerminatesWithSeparatorThenProviderPrompt() {
        ChatRequest req = new ChatRequest("", "explain BFO", null, ENDPOINT);
        List<String> cmd = bc("claude", req);
        assertEquals("--", cmd.get(cmd.size() - 2), "prompt must be guarded by a '--' separator");
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1),
                "final positional must be the provider prompt");
        assertEquals("explain BFO", cmd.get(cmd.size() - 1),
                "with no attachments providerPrompt equals the raw prompt");
    }

    @Test
    void buildCommandUsesProviderPromptNotRawPromptWhenAttachmentsPresent(@TempDir Path dir)
            throws Exception {
        Path doc = Files.writeString(dir.resolve("notes.txt"), "content");
        ChatRequest req = new ChatRequest("", "see [File #1]", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1", doc.toFile(), null)));
        List<String> cmd = bc("claude", req);
        String finalArg = cmd.get(cmd.size() - 1);
        assertEquals(req.providerPrompt(), finalArg, "final positional must be the expanded providerPrompt");
        assertTrue(finalArg.contains(doc.toFile().getAbsolutePath()),
                "providerPrompt must inline the attachment's absolute path");
    }

    // ---- buildCommand: attachment directories ----

    @Test
    void buildCommandOmitsAddDirWhenNoAttachments() {
        List<String> cmd = bc("claude",
                new ChatRequest("", "hi", null, ENDPOINT));
        assertFalse(cmd.contains("--add-dir"), "no attachments must omit --add-dir");
    }

    @Test
    void buildCommandEmitsOneAddDirFlagFollowedByEachDistinctDirectory(@TempDir Path root)
            throws Exception {
        Path dirA = Files.createDirectory(root.resolve("a"));
        Path dirB = Files.createDirectory(root.resolve("b"));
        Path fileA = Files.writeString(dirA.resolve("x.txt"), "x");
        Path fileB = Files.writeString(dirB.resolve("y.txt"), "y");
        ChatRequest req = new ChatRequest("", "look", null, ENDPOINT, List.of(
                ChatAttachment.file("File #1", fileA.toFile(), null),
                ChatAttachment.file("File #2", fileB.toFile(), null)));

        List<String> cmd = bc("claude", req);

        int i = cmd.indexOf("--add-dir");
        assertTrue(i >= 0, "--add-dir flag must be present");
        // The implementation emits a single --add-dir flag then all directory paths back-to-back.
        assertEquals(1, cmd.stream().filter("--add-dir"::equals).count(),
                "exactly one --add-dir flag precedes the directory list");
        List<String> dirs = req.attachmentDirectories().stream()
                .map(java.io.File::getAbsolutePath).toList();
        assertEquals(dirs, cmd.subList(i + 1, i + 1 + dirs.size()),
                "each distinct attachment directory follows --add-dir in order");
    }

    @Test
    void buildCommandDeduplicatesDirectoriesFromSameFolder(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        ChatRequest req = new ChatRequest("", "look", null, ENDPOINT, List.of(
                ChatAttachment.file("File #1", a.toFile(), null),
                ChatAttachment.file("File #2", b.toFile(), null)));

        List<String> cmd = bc("claude", req);

        int i = cmd.indexOf("--add-dir");
        assertTrue(i >= 0, "--add-dir must be present");
        // Two files in the same folder collapse to one directory (attachmentDirectories dedups).
        assertEquals(dir.toFile().getAbsolutePath(), cmd.get(i + 1),
                "shared parent folder appears once");
        assertEquals("--", cmd.get(cmd.size() - 2),
                "only the single shared dir is added before the prompt separator");
    }

    // ---- mcpConfigJson: full structure ----

    @Test
    void mcpConfigJsonIsParseableAndFullyStructured() throws Exception {
        JsonNode root = MAPPER.readTree(ClaudeCliProvider.mcpConfigJson(ENDPOINT));
        JsonNode servers = root.path("mcpServers");
        assertTrue(servers.isObject(), "root must carry an mcpServers object");
        JsonNode server = servers.path("protege");
        assertTrue(server.isObject(), "mcpServers must be keyed by the SERVER_NAME 'protege'");
        assertEquals("http", server.path("type").asText(), "server type must be http");
        assertEquals("http://127.0.0.1:8123/mcp", server.path("url").asText(),
                "url must come from endpoint.url()");
        assertEquals("Bearer TOK123", server.path("headers").path("Authorization").asText(),
                "Authorization header must be 'Bearer <token>'");
    }

    @Test
    void mcpConfigJsonUsesServerNameConstantAsKey() throws Exception {
        JsonNode servers = MAPPER.readTree(ClaudeCliProvider.mcpConfigJson(ENDPOINT)).path("mcpServers");
        assertTrue(servers.has(McpEndpoint.SERVER_NAME),
                "server key must be McpEndpoint.SERVER_NAME");
    }

    @Test
    void mcpConfigJsonPrefixesTokenWithBearerLiteralEvenForEmptyToken() throws Exception {
        McpEndpoint blankToken = new McpEndpoint("http://localhost:9/mcp", "");
        JsonNode server = MAPPER.readTree(ClaudeCliProvider.mcpConfigJson(blankToken))
                .path("mcpServers").path("protege");
        assertEquals("Bearer ", server.path("headers").path("Authorization").asText(),
                "an empty token still yields the 'Bearer ' literal prefix");
        assertEquals("http://localhost:9/mcp", server.path("url").asText(),
                "url is passed through verbatim");
    }

    @Test
    void mcpConfigJsonReflectsDistinctEndpointValues() throws Exception {
        McpEndpoint other = new McpEndpoint("https://example.test/x", "AbC-_123");
        JsonNode server = MAPPER.readTree(ClaudeCliProvider.mcpConfigJson(other))
                .path("mcpServers").path("protege");
        assertEquals("https://example.test/x", server.path("url").asText());
        assertEquals("Bearer AbC-_123", server.path("headers").path("Authorization").asText());
    }

    @Test
    void mcpConfigJsonIsNonNullNonEmpty() {
        String json = ClaudeCliProvider.mcpConfigJson(ENDPOINT);
        assertNotNull(json, "mcpConfigJson must not be null");
        assertFalse(json.isBlank(), "mcpConfigJson must not be blank");
    }

    private static void assertAdjacent(List<String> cmd, String flag, String value) {
        int i = cmd.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < cmd.size(), "missing flag " + flag);
        assertEquals(value, cmd.get(i + 1), "value after " + flag);
    }
}
