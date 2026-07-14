package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.AssistantSteering;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.config.McpConfig;

/**
 * Drives the OpenAI Codex CLI ({@code codex exec --json}) headlessly: a non-interactive run that
 * attaches Protégé's own MCP server over streamable HTTP (with the bearer token supplied via an env
 * var, as {@code codex mcp add --bearer-token-env-var} requires) and auto-approves tool calls while
 * keeping the local filesystem read-only (all editing happens through the MCP server). Reuses the
 * user's existing {@code codex login} — the plugin stores no API key.
 */
public final class CodexCliProvider implements ChatProvider {

    public static final String EXECUTABLE = "codex";
    /** Env var Codex reads for the MCP server's bearer token (referenced by the -c override). */
    static final String TOKEN_ENV_VAR = "PROTEGE_MCP_TOKEN";

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public String displayName() {
        return "Codex";
    }

    @Override
    public boolean isAvailable() {
        return resolveExecutable() != null;
    }

    @Override
    public List<String> listModels() {
        // Codex has no reliable "list models" command; offer common ids ("" = CLI default). The picker
        // is editable, so a user can type any model their account supports.
        return List.of("", "gpt-5.5", "gpt-5.4", "o3");
    }

    @Override
    public String defaultModel() {
        return "";
    }

    @Override
    public ChatProcess startTurn(ChatRequest request, ChatListener listener) throws IOException {
        String exe = resolveExecutable();
        if (exe == null) {
            throw new IOException("The 'codex' CLI was not found. Install Codex, or set its path in "
                    + "Preferences ▸ Ontology Assistant.");
        }
        List<String> command = buildCommand(exe, request);
        Map<String, String> env = Map.of(TOKEN_ENV_VAR, request.endpoint().token());
        CodexEventParser parser = new CodexEventParser(listener);
        return CliSupport.spawn(command, env, CliSupport.neutralWorkingDir(),
                parser,
                (exit, stderr) -> finishTurn(exit, stderr, parser.errorReported(), listener));
    }

    /**
     * Report the process exit to the listener. A non-zero exit adds the generic failure line only
     * when the stream did not already surface its own error (a turn.failed/error event or an error
     * item): repeating it would only add noise. For a CLI that died without one, the exit line is
     * the only diagnostic. Package-private for unit testing.
     */
    static void finishTurn(int exit, String stderr, boolean streamErrorSeen, ChatListener listener) {
        if (exit != 0 && !streamErrorSeen) {
            listener.onError(CliSupport.describeFailure("codex", exit, stderr));
        }
        listener.onComplete(exit);
    }

    private String resolveExecutable() {
        String override = McpConfig.prefs().getString(McpConfig.KEY_CHAT_CODEX_PATH, "");
        return CliSupport.resolveExecutable(EXECUTABLE, override);
    }

    /**
     * Build the headless JSONL invocation. Sandbox/approval and the MCP server are set via global
     * {@code -c} overrides (so they apply to both a fresh {@code exec} and {@code exec resume}, which
     * has no {@code -s} flag). The token is passed by env var, not on the command line. A non-blank
     * session id resumes the thread. Package-private for unit testing.
     */
    static List<String> buildCommand(String exe, ChatRequest req) {
        String server = McpEndpoint.SERVER_NAME;
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-c");
        cmd.add("approval_policy=" + toml("never"));
        cmd.add("-c");
        cmd.add("sandbox_mode=" + toml("read-only"));
        cmd.add("-c");
        cmd.add("mcp_servers." + server + ".url=" + toml(req.endpoint().url()));
        cmd.add("-c");
        cmd.add("mcp_servers." + server + ".bearer_token_env_var=" + toml(TOKEN_ENV_VAR));
        // MCP tool calls are gated separately from approval_policy (which only governs shell/exec). The
        // per-server default is "required", so a headless run with no one to approve auto-cancels every
        // tool call ("user cancelled MCP tool call"). "approve" auto-approves them (the only other
        // AppToolApproval value). Server-side read-only / confirm-write gates still apply to actual edits.
        cmd.add("-c");
        cmd.add("mcp_servers." + server + ".default_tools_approval_mode=" + toml("approve"));
        if (req.showReasoning()) {
            // Codex spends reasoning tokens but emits no "reasoning" items in exec --json unless a
            // summary mode is set; "detailed" makes it stream reasoning summaries the parser already
            // understands. Only requested when the user opted in.
            cmd.add("-c");
            cmd.add("model_reasoning_summary=" + toml("detailed"));
        }
        cmd.add("exec");
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            cmd.add("resume");
            cmd.add(req.sessionId().trim());
        }
        cmd.add("--json");
        cmd.add("--skip-git-repo-check");
        if (req.model() != null && !req.model().isBlank()) {
            cmd.add("-m");
            cmd.add(req.model().trim());
        }
        for (java.io.File image : req.imageFiles()) {
            cmd.add("--image");
            cmd.add(image.getAbsolutePath());
        }
        cmd.add("--");
        // codex exec has no --append-system-prompt equivalent, so the write-workflow steering rides
        // the FIRST message of a new thread; a resumed thread already carries it in its history, and
        // repeating it there would push the real user message further from the model's attention.
        // Resumable ids only exist in the in-memory ChatHistory of this Protégé run, and swapping in
        // a new plugin jar requires a restart that clears them — so every resumed thread was seeded
        // by this provider and no pre-steering thread can reach the resume branch.
        boolean newSession = req.sessionId() == null || req.sessionId().isBlank();
        cmd.add(newSession
                ? AssistantSteering.SYSTEM_PROMPT + "\n\n" + req.providerPrompt()
                : req.providerPrompt());
        return cmd;
    }

    /** Render a TOML string literal for a {@code -c key=value} override (value parsed as TOML). */
    private static String toml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
