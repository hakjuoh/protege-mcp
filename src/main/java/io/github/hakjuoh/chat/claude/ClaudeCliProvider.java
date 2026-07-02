package io.github.hakjuoh.chat.claude;

import io.github.hakjuoh.chat.ChatListener;
import io.github.hakjuoh.chat.ChatProcess;
import io.github.hakjuoh.chat.ChatProvider;
import io.github.hakjuoh.chat.ChatRequest;
import io.github.hakjuoh.chat.CliSupport;
import io.github.hakjuoh.chat.McpEndpoint;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.config.McpConfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Drives the Claude Code CLI ({@code claude}) headlessly: a streaming, non-interactive run that
 * attaches Protégé's own MCP server over HTTP and pre-approves its tools, so the model reads/edits the
 * live ontology through the existing tool layer. Reuses the user's existing Claude login (keychain /
 * subscription) — the plugin stores no API key.
 */
public final class ClaudeCliProvider implements ChatProvider {

    public static final String EXECUTABLE = "claude";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "claude";
    }

    @Override
    public String displayName() {
        return "Claude";
    }

    @Override
    public boolean isAvailable() {
        return resolveExecutable() != null;
    }

    @Override
    public List<String> listModels() {
        // Claude Code has no "list models" command; these are the documented aliases. "" = CLI default.
        return List.of("", "opus", "sonnet", "haiku", "fable");
    }

    @Override
    public String defaultModel() {
        return "";
    }

    @Override
    public ChatProcess startTurn(ChatRequest request, ChatListener listener) throws IOException {
        String exe = resolveExecutable();
        if (exe == null) {
            throw new IOException("The 'claude' CLI was not found. Install Claude Code, or set its path "
                    + "in Preferences ▸ Ontology Assistant.");
        }
        // Write the MCP config (which carries the bearer token) to an owner-only temp FILE and pass its
        // PATH on the command line, rather than embedding the token JSON as an argv value where any local
        // user could read it via `ps`. Deleted once the process exits.
        final File mcpConfig;
        try {
            mcpConfig = CliSupport.writeOwnerOnlyTempFile("protege-mcp-", ".json",
                    mcpConfigJson(request.endpoint()));
        } catch (IOException e) {
            throw new IOException("Could not write the MCP config for the claude CLI: " + e.getMessage(), e);
        }
        mcpConfig.deleteOnExit();
        List<String> command = buildCommand(exe, request, mcpConfig.getAbsolutePath());
        return CliSupport.spawn(command, Collections.emptyMap(), CliSupport.neutralWorkingDir(),
                new ClaudeEventParser(listener),
                (exit, stderr) -> {
                    try {
                        Files.deleteIfExists(mcpConfig.toPath());
                    } catch (IOException ignored) {
                        // best-effort cleanup; deleteOnExit is the backstop
                    }
                    if (exit != 0) {
                        listener.onError(CliSupport.describeFailure("claude", exit, stderr));
                    }
                    listener.onComplete(exit);
                });
    }

    private String resolveExecutable() {
        String override = McpConfig.prefs().getString(McpConfig.KEY_CHAT_CLAUDE_PATH, "");
        return CliSupport.resolveExecutable(EXECUTABLE, override);
    }

    /**
     * Build the headless streaming invocation. {@code --strict-mcp-config} + a {@code --mcp-config} file
     * means the run sees exactly Protégé's server and nothing else; {@code --allowedTools mcp__protege}
     * pre-approves the whole server so the non-interactive run never blocks on a permission prompt
     * (server-side read-only / confirm-write gates still apply). A non-blank session id resumes the
     * conversation. {@code mcpConfigPath} is the path to the owner-only MCP-config file written by
     * {@link #startTurn}, passed by PATH so the bearer token it carries never reaches the argv.
     * Package-private for unit testing.
     */
    static List<String> buildCommand(String exe, ChatRequest req, String mcpConfigPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--include-partial-messages");
        cmd.add("--verbose");
        cmd.add("--strict-mcp-config");
        cmd.add("--mcp-config");
        cmd.add(mcpConfigPath);
        cmd.add("--allowedTools");
        cmd.add("mcp__" + McpEndpoint.SERVER_NAME);
        List<java.io.File> attachmentDirs = req.attachmentDirectories();
        if (!attachmentDirs.isEmpty()) {
            cmd.add("--add-dir");
            for (java.io.File dir : attachmentDirs) {
                cmd.add(dir.getAbsolutePath());
            }
        }
        if (req.model() != null && !req.model().isBlank()) {
            cmd.add("--model");
            cmd.add(req.model().trim());
        }
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            cmd.add("--resume");
            cmd.add(req.sessionId().trim());
        }
        cmd.add("--");
        cmd.add(req.providerPrompt());
        return cmd;
    }

    /** The {@code --mcp-config} value: one HTTP MCP server with a bearer auth header. */
    static String mcpConfigJson(McpEndpoint endpoint) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        ObjectNode server = servers.putObject(McpEndpoint.SERVER_NAME);
        server.put("type", "http");
        server.put("url", endpoint.url());
        ObjectNode headers = server.putObject("headers");
        headers.put("Authorization", "Bearer " + endpoint.token());
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // ObjectNode is always serializable; this is unreachable in practice.
            throw new IllegalStateException("Failed to render MCP config JSON", e);
        }
    }

    /** Exposed only so a test can assert the spawn env carries nothing secret. */
    static Map<String, String> environment() {
        return Collections.emptyMap();
    }
}
