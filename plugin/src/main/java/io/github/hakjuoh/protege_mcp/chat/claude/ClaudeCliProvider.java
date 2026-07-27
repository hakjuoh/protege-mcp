package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.AssistantSteering;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
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
    /** How the CLI prefixes a diagnostic it is going to carry on past. */
    private static final String WARNING_PREFIX = "Warning:";
    /** The reasoning options this provider can put on the command line, as the CLI spells them. */
    private static final String EFFORT_OPTION = "--effort";
    private static final String THINKING_OPTION = "--thinking-display";
    /** A warning is one line in practice; the cap only stops a pathological stderr reaching the view. */
    private static final int MAX_WARNING_CHARS = 400;

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
        return ChatModelCatalog.pickerModels(McpConfig.prefs(), id());
    }

    @Override
    public List<String> reasoningEfforts() {
        return ChatModelCatalog.claudeReasoningEfforts();
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
        ClaudeEventParser parser = new ClaudeEventParser(listener);
        return CliSupport.spawn(command, Collections.emptyMap(), CliSupport.neutralWorkingDir(),
                parser,
                (exit, stderr) -> {
                    try {
                        Files.deleteIfExists(mcpConfig.toPath());
                    } catch (IOException ignored) {
                        // best-effort cleanup; deleteOnExit is the backstop
                    }
                    finishTurn(exit, stderr, parser.errorReported(), parser.answered(),
                            request.showReasoning(), !request.reasoningEffort().isBlank(), listener);
                });
    }

    /**
     * Report the process exit to the listener. A non-zero exit adds the generic failure line only
     * when the stream did not already surface its own error (an {@code is_error} result — e.g. an
     * API or policy refusal): repeating it would only add noise. For a CLI that died without one
     * (unknown option, not logged in), the exit line is the only diagnostic — including the
     * Show-reasoning hint, whose unknown-option rejection happens at argv parse, before any
     * stream-json exists.
     *
     * <p>A turn that exits cleanly, reports nothing anywhere and produces no reply is reported too. It is
     * the one turn nothing else in the transcript accounts for: no reply to read, no error from the stream,
     * no warning to quote — and left silent it is a blank exchange the user is given no reason for.
     * Package-private for unit testing.
     */
    static void finishTurn(int exit, String stderr, boolean streamErrorSeen, boolean answered,
            boolean showReasoning, boolean effortRequested, ChatListener listener) {
        if (exit != 0) {
            if (!streamErrorSeen) {
                listener.onError(failureMessage(exit, stderr, showReasoning, effortRequested));
            }
        } else if (answered || !streamErrorSeen) {
            // Only a run that SUCCEEDED can have silently dropped an option (see
            // ignoredOptionWarning). A failing turn already has its own diagnostic - either the error
            // line above or the one the stream surfaced - and a note saying the turn ran on the CLI's
            // own setting would contradict it. A clean exit is not proof on its own: the stream can
            // report the failure itself and exit 0, and then only a reply tells the two apart. A clean
            // exit that reported nothing and still said nothing is reported here, because nothing else
            // reports it at all - in the wording such a turn earns, which is not "it ran anyway".
            String notice = ignoredOptionWarning(stderr, showReasoning, effortRequested, answered);
            if (notice != null) {
                listener.onNotice(notice);
            } else if (!answered) {
                // Nothing else has anything to say about this turn: it exited 0, the stream reported no
                // failure, there is no warning to quote, and no reply arrived. Said as an error rather
                // than a note, because that is what it is: a question that went unanswered with nothing
                // else in the transcript to say so.
                listener.onError(CliSupport.describeSilentTurn("claude"));
            }
        }
        listener.onComplete(exit);
    }

    /**
     * The warning a successful run printed about a reasoning option <em>this turn passed</em>, as a
     * transcript notice, or {@code null} when there is none to report. A current claude CLI handles an
     * {@code --effort} value it does not know by warning on stderr and running at its own effort
     * anyway: exit 0, a complete reply, and nothing in the stream to say the value the user picked was
     * dropped — so the reply looks like it came from the requested effort when it did not. The warning
     * is the only evidence, and its wording is not part of any contract, so it is surfaced verbatim
     * rather than matched.
     *
     * <p>Only a warning that names the option this turn actually passed is reported. Warnings on a
     * CLI's stderr are routine — an available update, a deprecated setting, a login shell's own rc
     * output — and blaming the reasoning controls for one of those would accuse the user's picker of
     * something it did not do, on a turn that was fine.
     *
     * <p>What the note says the turn did depends on whether it answered. A clean exit with nothing in
     * the stream and no reply is a turn that produced nothing, and describing that as having run at the
     * CLI's own effort would credit an answer the transcript does not have — on a turn that has no other
     * diagnostic at all. The warning itself is still the user's only evidence, so it is still reported.
     * Package-private for testing.
     */
    static String ignoredOptionWarning(String stderr, boolean showReasoning, boolean effortRequested,
            boolean answered) {
        if (stderr == null || (!showReasoning && !effortRequested)) {
            return null;
        }
        StringBuilder warnings = new StringBuilder();
        boolean aboutEffort = false;
        for (String line : stderr.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(WARNING_PREFIX) || !namesPassedOption(trimmed,
                    showReasoning, effortRequested)) {
                continue;
            }
            aboutEffort |= effortRequested && namesOption(trimmed, EFFORT_OPTION);
            if (warnings.length() > 0) {
                warnings.append(' ');
            }
            warnings.append(trimmed);
            if (warnings.length() >= MAX_WARNING_CHARS) {
                break;
            }
        }
        if (warnings.length() == 0) {
            return null;
        }
        String text = warnings.length() > MAX_WARNING_CHARS
                ? warnings.substring(0, MAX_WARNING_CHARS) + "…"
                : warnings.toString();
        if (!answered) {
            return "[note] claude reported: " + text + (aboutEffort
                    ? " The turn produced no reply, so pick one of the values it lists or reset the "
                            + "effort picker to (default)."
                    : " The turn produced no reply.");
        }
        return "[note] claude reported: " + text + (aboutEffort
                ? " The turn ran on the CLI's own effort, so pick one of the values it lists or reset "
                        + "the effort picker to (default)."
                : " The turn ran on the CLI's own reasoning setting, not the one this turn asked for.");
    }

    /** Whether a warning line is about one of the reasoning options this turn put on the command. */
    private static boolean namesPassedOption(String warning, boolean showReasoning,
            boolean effortRequested) {
        return (effortRequested && namesOption(warning, EFFORT_OPTION))
                || (showReasoning && namesOption(warning, THINKING_OPTION));
    }

    /**
     * Whether a diagnostic names this option and not a longer one that begins with it. A CLI option ends
     * where its name does, so {@code --effortless} is a different setting from {@code --effort}, and
     * matching it as a substring would tell the user to reset an effort picker a warning about some other
     * flag never mentioned - or claim the CLI cannot take the option it took. A trailing {@code =}, quote,
     * comma or space all end the name, which is how every diagnostic that does mean this option writes it.
     */
    private static boolean namesOption(String text, String option) {
        int from = 0;
        while (true) {
            int at = text.indexOf(option, from);
            if (at < 0) {
                return false;
            }
            int after = at + option.length();
            if (after >= text.length() || !isOptionNameChar(text.charAt(after))) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isOptionNameChar(char c) {
        return c == '-' || c == '_' || Character.isLetterOrDigit(c);
    }

    /**
     * The transcript error for a failed run. When the failure is a reasoning opt-in flag itself — a
     * claude CLI too old to know {@code --thinking-display} or {@code --effort} rejects the whole
     * invocation — the raw "unknown option" alone gives the user no way to connect it to the control
     * that set it, and the persisted preference would fail every following turn too; name the way
     * out. Each hint is gated on that flag actually having been passed, so a CLI that merely prints
     * the flag name in some other diagnostic does not send the user chasing a control they never
     * touched. Package-private for testing.
     */
    static String failureMessage(int exit, String stderr, boolean showReasoning,
            boolean effortRequested) {
        String msg = CliSupport.describeFailure("claude", exit, stderr);
        boolean thinkingRejected = showReasoning && stderr != null
                && namesOption(stderr, THINKING_OPTION);
        boolean effortRejected = effortRequested && stderr != null
                && namesOption(stderr, EFFORT_OPTION);
        if (thinkingRejected || effortRejected) {
            msg += " This claude CLI does not support the reasoning option — ";
            if (thinkingRejected && effortRejected) {
                msg += "untick 'Show reasoning' and reset the effort picker to (default)";
            } else if (thinkingRejected) {
                msg += "untick 'Show reasoning'";
            } else {
                msg += "reset the effort picker to (default)";
            }
            msg += " and resend, or update the CLI.";
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
        // Every invocation, resumed or not: the system prompt is per-invocation state that --resume
        // does not restore. Long-documented flag, unlike --thinking-display, so no version gate.
        cmd.add("--append-system-prompt");
        cmd.add(AssistantSteering.SYSTEM_PROMPT);
        if (req.showReasoning()) {
            // Without this, current CLIs put an EMPTY thinking block (encrypted signature only) in
            // stream-json — Claude 5-era models default their thinking display to "omitted" — so the
            // Show-reasoning toggle would have nothing to render. "summarized" restores real
            // thinking_delta text. The flag is accepted but undocumented on current CLIs and only
            // passed when the user opted in; a CLI too old to know it fails the turn with a clear
            // "unknown option" error rather than silently showing nothing.
            cmd.add(THINKING_OPTION);
            cmd.add("summarized");
        }
        if (req.reasoningEffort() != null && !req.reasoningEffort().isBlank()) {
            cmd.add(EFFORT_OPTION);
            cmd.add(req.reasoningEffort().trim());
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
