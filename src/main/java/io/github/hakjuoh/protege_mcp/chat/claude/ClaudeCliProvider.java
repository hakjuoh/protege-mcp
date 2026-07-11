package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.config.McpConfig;

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
                        listener.onError(failureMessage(exit, stderr, request.showReasoning()));
                    }
                    listener.onComplete(exit);
                });
    }

    /**
     * The transcript error for a failed run. When the failure is the reasoning opt-in flag itself —
     * a claude CLI too old to know {@code --thinking-display} rejects the whole invocation — the raw
     * "unknown option" alone gives the user no way to connect it to the checkbox, and the persisted
     * preference would fail every following turn too; name the way out. Package-private for testing.
     */
    static String failureMessage(int exit, String stderr, boolean showReasoning) {
        String msg = CliSupport.describeFailure("claude", exit, stderr);
        if (showReasoning && stderr != null && stderr.contains("--thinking-display")) {
            msg += " This claude CLI does not support the reasoning opt-in — untick 'Show reasoning' "
                    + "and resend, or update the CLI.";
        }
        return msg;
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
     * conversation. When the user opted into reasoning display, {@code --thinking-display summarized}
     * asks the CLI to stream real thinking text. {@code mcpConfigPath} is the path to the owner-only
     * MCP-config file written by {@link #startTurn}, passed by PATH so the bearer token it carries
     * never reaches the argv. Package-private for unit testing.
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
        if (req.showReasoning()) {
            // Without this, current CLIs put an EMPTY thinking block (encrypted signature only) in
            // stream-json — Claude 5-era models default their thinking display to "omitted" — so the
            // Show-reasoning toggle would have nothing to render. "summarized" restores real
            // thinking_delta text. The flag is accepted but undocumented on current CLIs and only
            // passed when the user opted in; a CLI too old to know it fails the turn with a clear
            // "unknown option" error rather than silently showing nothing.
            cmd.add("--thinking-display");
            cmd.add("summarized");
        }
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
