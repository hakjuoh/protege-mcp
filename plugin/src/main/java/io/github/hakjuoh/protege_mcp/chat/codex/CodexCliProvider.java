package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.AssistantSteering;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    /**
     * The value an "invalid value" diagnostic names, quoted or bare. The bare form must not be part of
     * a dotted identifier, so {@code high.foo} is one value rather than a refusal of {@code high}.
     */
    private static final Pattern INVALID_VALUE = Pattern.compile(
            "invalid value:?\\s*(?:'([^']*)'|\"([^\"]*)\"|([\\w-]+)(?!\\.?[\\w-]))");
    /**
     * What a diagnostic that names reasoning effort says about it to be a refusal of it. Naming the
     * setting - by its override key or in prose - is what says which setting is meant, and a wording of
     * refusal is what says it was refused: a line that only reports what the turn ran with, or echoes the
     * override back as configuration, names the setting without complaining about it. See
     * {@link #namesTheEffortSetting}.
     *
     * <p>Each entry carries the elisions of the wordings it covers, so "is not supported", "does not
     * support" and "doesn't support" all reach one of them, as do both spellings of "unrecognised".
     * Refusing by availability is one of these wordings too: an API that answers "reasoning effort 'max' is
     * not available for this model" has refused the value the picker sent as plainly as one that calls it
     * unsupported, and a turn that failed for it with no notice leaves the user with no way to connect the
     * error to the control that caused it.
     */
    private static final List<String> EFFORT_REFUSAL_WORDS = List.of(
            "invalid", "unsupported", "not support", "n't support", "not accept", "n't accept",
            "must be one of", "expected one of", "supported values", "allowed values", "cannot",
            "can't", "unknown", "unrecogni", "no such", "not a valid", "not allowed", "rejected",
            "out of range", "not available", "n't available", "unavailable", "not enabled",
            "not permitted");
    /** The refusal wordings as one alternation, for the two attribution patterns below. */
    private static final String REFUSAL_WORDS = EFFORT_REFUSAL_WORDS.stream()
            .map(Pattern::quote).collect(Collectors.joining("|"));
    /**
     * A line that says it is the <em>model</em> it refuses: "invalid model 'gpt-bad'", "unknown model".
     * The word boundary is what keeps it off the override key - {@code model_reasoning_effort} is not
     * "model", so a release that refuses the key by name still reads as refusing the effort.
     */
    private static final Pattern REFUSES_THE_MODEL = Pattern.compile(
            "(?:" + REFUSAL_WORDS + ")\\s+(?:the\\s+)?model\\b");
    /**
     * A line that says it is the reasoning effort it refuses, and not merely a line that mentions it: the
     * refusal either runs straight into the setting's name through plain words alone ("unknown config key
     * model_reasoning_effort"), or follows it closely enough to be about it ("reasoning effort 'max' is not
     * available"). An intervening quoted value or punctuation breaks the first form, and a refusal the model
     * has already claimed does not satisfy the second, which is what tells "invalid model 'gpt-bad';
     * model_reasoning_effort=high" - a refused model beside an echoed override, either way round - from a
     * refusal of the effort itself.
     */
    private static final Pattern REFUSES_THE_EFFORT = Pattern.compile(
            "(?:" + REFUSAL_WORDS + ")\\s+(?:[\\w-]+\\s+){0,3}(?:model_)?reasoning[ _]effort"
                    + "|(?:model_)?reasoning[ _]effort\\b.{0,40}?(?:" + REFUSAL_WORDS + ")"
                    + "(?!\\s+(?:the\\s+)?model\\b)");
    /**
     * What to do about a refused reasoning effort. The picker offers every value current Codex
     * releases accept, but which of them a given model supports is the model's business, and the
     * rejection arrives as an API error that names neither this plugin's control nor the way out.
     *
     * <p>Who refused it is left to the diagnostic itself. The same classification catches a Codex
     * release that will not take the override at all ("unknown config key
     * {@code model_reasoning_effort}"), which is the CLI's own parser and not the model - naming the
     * model there would send the user hunting for a supported value that exists. For the same reason the
     * note cannot promise the error lists what to pick instead: that release names no value and accepts
     * none, so the way out it always has is the picker's own (default).
     */
    static final String EFFORT_HINT = "[note] The reasoning effort this turn asked for was refused. "
            + "Pick one of the values the error lists, if it lists any, or reset the effort picker to "
            + "(default) to let Codex choose.";
    /**
     * The same diagnostic on a turn that still answered: Codex complaining about the effort override
     * and then running anyway means the reply came from its own setting, so it must not be described as
     * a refusal - the user has a reply in front of them, and "was refused" would read as if the question
     * had gone unanswered. It happens both ways: a diagnostic on stderr with nothing wrong in the
     * stream, and a stream error the retry loop surfaced before Codex answered anyway.
     *
     * <p>A reply is what this note is about, so it is only used when one arrived. A turn that exited
     * cleanly and said nothing gets the refusal wording instead: "answered anyway" would promise a reply
     * that is not in the transcript, on the one kind of turn where nothing else says what went wrong.
     */
    static final String EFFORT_IGNORED_HINT = "[note] Codex reported a problem with the reasoning "
            + "effort this turn asked for and answered anyway, so the reply came from its own "
            + "setting. Pick one of the values it names, if it names any, or reset the effort picker "
            + "to (default).";
    /**
     * The same refusal when the value it names is the one both pickers are set to. A diagnostic of the
     * "invalid value" shape names no setting, so it is only about the effort because the value it rejected
     * is the one we sent - and when the model picker carries that value too, the match says nothing about
     * which of the two Codex meant. Naming the effort there sends the user to a control they may never
     * have touched; naming neither leaves the one diagnostic that identifies no setting unexplained. So
     * this note says what is actually known - it is one of these two - and how to tell them apart.
     */
    static final String AMBIGUOUS_HINT = "[note] Codex refused a value this turn asked for, and the "
            + "model and reasoning-effort pickers are both set to that value, so the error does not say "
            + "which of them it means. Change one at a time: pick a different model, or reset the effort "
            + "picker to (default).";
    /** The same ambiguity on a turn that answered anyway; see {@link #EFFORT_IGNORED_HINT}. */
    static final String AMBIGUOUS_IGNORED_HINT = "[note] Codex reported a problem with a value this turn "
            + "asked for and answered anyway. The model and reasoning-effort pickers are both set to that "
            + "value, so the error does not say which of them it means. Change one at a time: pick a "
            + "different model, or reset the effort picker to (default).";

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
        return ChatModelCatalog.pickerModels(McpConfig.prefs(), id());
    }

    @Override
    public List<String> reasoningEfforts() {
        return reasoningEfforts("");
    }

    @Override
    public List<String> reasoningEfforts(String model) {
        return ChatModelCatalog.codexReasoningEfforts(model);
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
        boolean effortRequested = request.reasoningEffort() != null
                && !request.reasoningEffort().isBlank();
        return CliSupport.spawn(command, env, CliSupport.neutralWorkingDir(),
                parser,
                (exit, stderr) -> finishTurn(exit, stderr, parser.errorReported(),
                        parser.classifiableErrorText(), parser.answered(),
                        effortRequested ? request.reasoningEffort().trim() : null,
                        request.model(), listener));
    }

    /**
     * Report the process exit to the listener. A non-zero exit adds the generic failure line only
     * when the stream did not already surface its own error (a turn.failed/error event or an error
     * item): repeating it would only add noise. For a CLI that died without one, the exit line is
     * the only diagnostic. When the failure was the reasoning effort being refused, the hint naming
     * the control that set it is added on top, because neither diagnostic mentions it.
     *
     * <p>A turn that exits cleanly, reports nothing anywhere, produces no reply and has no diagnostic to
     * explain it is reported as exactly that. It is the one turn nothing else in the transcript accounts
     * for, and a blank exchange with no reason given is worse than a plain account of it.
     * Package-private for unit testing.
     */
    static void finishTurn(int exit, String stderr, boolean streamErrorSeen, String streamErrorText,
            boolean answered, String requestedEffort, ChatListener listener) {
        finishTurn(exit, stderr, streamErrorSeen, streamErrorText, answered, requestedEffort, null,
                listener);
    }

    /**
     * The same report, told which model the turn asked for. Only the ambiguity note needs it: a
     * diagnostic that names no setting is tied to the effort picker by the refused value alone, and that
     * tie means nothing when the model picker carries the same value. The form without it reads as "no
     * model was named", which is what a turn at the CLI's own default model sends.
     * Package-private for unit testing.
     */
    static void finishTurn(int exit, String stderr, boolean streamErrorSeen, String streamErrorText,
            boolean answered, String requestedEffort, String requestedModel, ChatListener listener) {
        // A turn that answered was not refused, whatever the diagnostic looks like: Codex surfaces the
        // errors of a retry loop as it goes, so a reported failure decides how to describe the turn only
        // while no reply arrived. A non-zero exit is decisive on its own - the reply, if any, is a
        // fragment of a turn that then died. The reply is what tells the two apart in the other
        // direction too: a clean exit that reported nothing in the stream and still said nothing did not
        // answer at its own effort, so it is a refusal, and only the reply can say otherwise.
        boolean refused = exit != 0 || !answered;
        if (exit != 0 && !streamErrorSeen) {
            listener.onError(CliSupport.describeFailure("codex", exit, stderr));
        }
        boolean effortRefused = effortRejection(requestedEffort, streamErrorText, stderr);
        if (exit == 0 && !answered && !streamErrorSeen && !effortRefused) {
            // The one turn nothing else accounts for: a clean exit, no reply, nothing reported in the
            // stream, and no diagnostic naming a setting to explain it. Silence here leaves a blank
            // exchange with no reason given, and an error rather than a note is what such a turn is: the
            // question was not answered, and nothing else in the transcript will say so.
            listener.onError(CliSupport.describeSilentTurn("codex"));
        }
        if (effortRefused) {
            boolean ambiguous = alsoTheRequestedModel(requestedEffort, requestedModel,
                    streamErrorText, stderr);
            if (refused) {
                listener.onNotice(ambiguous ? AMBIGUOUS_HINT : EFFORT_HINT);
            } else {
                listener.onNotice(ambiguous ? AMBIGUOUS_IGNORED_HINT : EFFORT_IGNORED_HINT);
            }
        }
        listener.onComplete(exit);
    }

    /**
     * Whether a diagnostic is the effort this turn asked for being refused, given the value that was
     * requested ({@code null}/blank when the picker was at (default), which can refuse nothing).
     * Three shapes occur: Codex rejecting the {@code model_reasoning_effort} override it was handed,
     * the API rejecting the value itself ("Invalid value: 'x'. Supported values are: …"), and a
     * diagnostic that spells out reasoning effort in prose.
     *
     * <p>The "invalid value" shape names no setting, so it is only ours when the value it rejected is
     * the one we sent — a mistyped model id from the catalog is rejected in exactly the same words,
     * and pointing at the effort picker for that would send the user to a control they never touched.
     * Package-private for testing.
     */
    static boolean effortRejection(String requestedEffort, String... diagnostics) {
        if (requestedEffort == null || requestedEffort.isBlank()) {
            return false;
        }
        String effort = requestedEffort.trim().toLowerCase(Locale.ROOT);
        for (String text : diagnostics) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (namesTheEffortSetting(lower) || refusedValues(lower).contains(effort)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the requested model would answer that diagnostic exactly as well as the requested effort.
     * That is true only when the value the two pickers carry is the same and nothing else in the
     * diagnostic ties it to the effort: a text that names {@code model_reasoning_effort} or spells out a
     * refused reasoning effort in prose has said which setting it means, whatever the model is set to.
     * Package-private for testing.
     */
    static boolean alsoTheRequestedModel(String requestedEffort, String requestedModel,
            String... diagnostics) {
        if (requestedEffort == null || requestedModel == null || requestedModel.isBlank()
                || !requestedModel.trim().equalsIgnoreCase(requestedEffort.trim())) {
            return false;
        }
        for (String text : diagnostics) {
            if (text == null || text.isBlank()) {
                continue;
            }
            if (namesTheEffortSetting(text.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a diagnostic says which setting it is complaining about: reasoning effort, named by its
     * override key or spelled out in prose, next to a word that refuses something. The reading is used
     * twice over - to classify a rejection, and to rule out the ambiguity with the model picker - so it is
     * one test, because a text that identifies the setting for one question identifies it for the other.
     *
     * <p>The override key is held to the same test as the prose. Codex prints it back in diagnostics that
     * refuse nothing - a debug line echoing the configuration it was handed, for one - and reading those as
     * a refusal would tell the user their effort was rejected by a turn that ran at exactly the effort they
     * picked. Every release that does refuse the key says so in words: "unknown config key
     * {@code model_reasoning_effort}" is the wording of the release whose parser does not know it at all.
     */
    private static boolean namesTheEffortSetting(String lower) {
        // Line by line, because "next to" is the whole claim: a diagnostic is a stream's errors and a
        // CLI's stderr joined by newlines, so a debug line echoing the override and an unrelated line
        // refusing a mistyped model id would otherwise read as one sentence refusing the effort - and send
        // the user to a picker that had nothing to do with the failure.
        for (String line : lower.split("\\R")) {
            if (namesTheEffortSettingOnce(line)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesTheEffortSettingOnce(String line) {
        if (!line.contains("model_reasoning_effort")
                && !(line.contains("reasoning") && line.contains("effort"))) {
            return false;
        }
        if (EFFORT_REFUSAL_WORDS.stream().noneMatch(line::contains)) {
            return false;
        }
        // A line that names which setting it refuses, and names the model, has not refused the effort: the
        // override echoed beside "invalid model 'gpt-bad'" is configuration the failure was handed, not the
        // failure, and a notice about the effort picker would send the user to a control that had nothing to
        // do with it. A line that refuses both is still the user's to act on at the effort picker, so the
        // effort's own attribution wins where it is there to be read.
        return !REFUSES_THE_MODEL.matcher(line).find() || REFUSES_THE_EFFORT.matcher(line).find();
    }

    /**
     * The values an "invalid value" diagnostic says were refused, whole. Comparing whole values is the
     * point: a prefix test would read {@code Invalid value: 'high.foo'} as a refusal of {@code high},
     * and a model id is rejected in exactly these words.
     */
    private static Set<String> refusedValues(String lowercasedDiagnostic) {
        Set<String> refused = new LinkedHashSet<>();
        Matcher matcher = INVALID_VALUE.matcher(lowercasedDiagnostic);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String value = matcher.group(group);
                if (value != null) {
                    refused.add(value);
                    break;
                }
            }
        }
        return refused;
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
        if (req.reasoningEffort() != null && !req.reasoningEffort().isBlank()) {
            cmd.add("-c");
            cmd.add("model_reasoning_effort=" + toml(req.reasoningEffort().trim()));
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
