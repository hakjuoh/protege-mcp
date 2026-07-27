package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;
import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void changeSetSteeringIsAppendedToTheSystemPromptOnEveryTurn() {
        // Fresh turn: the steering rides --append-system-prompt, not the user prompt.
        List<String> fresh = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "hello world", null, ENDPOINT), CONFIG_PATH);
        assertAdjacent(fresh, "--append-system-prompt",
                io.github.hakjuoh.protege_mcp.chat.AssistantSteering.SYSTEM_PROMPT);
        assertEquals("hello world", fresh.get(fresh.size() - 1),
                "steering must not contaminate the user prompt positional");

        // Resumed turn: --resume does not restore per-invocation system-prompt flags, so a resumed
        // session that dropped the flag would silently lose the write-workflow contract.
        List<String> resumed = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "again", "sess-9", ENDPOINT), CONFIG_PATH);
        assertAdjacent(resumed, "--append-system-prompt",
                io.github.hakjuoh.protege_mcp.chat.AssistantSteering.SYSTEM_PROMPT);
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
    void selectedReasoningEffortUsesClaudeEffortFlag() {
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "hi", null, ENDPOINT, List.of(), false, "", "xhigh"), CONFIG_PATH);
        assertAdjacent(cmd, "--effort", "xhigh");
    }

    @Test
    void blankReasoningEffortLeavesClaudeDefaultUntouched() {
        List<String> cmd = ClaudeCliProvider.buildCommand("claude",
                new ChatRequest("", "hi", null, ENDPOINT, List.of(), false, "", ""), CONFIG_PATH);
        assertFalse(cmd.contains("--effort"));
    }

    @Test
    void reasoningFlagRejectionNamesTheCheckbox() {
        // A CLI predating the flag fails every opted-in turn; the raw "unknown option" alone gives
        // no way to connect the failure to the persisted checkbox.
        String msg = ClaudeCliProvider.failureMessage(1,
                "error: unknown option '--thinking-display'", true, false);
        assertTrue(msg.contains("claude exited with code 1"));
        assertTrue(msg.contains("Show reasoning"));
    }

    @Test
    void exitLineSuppressedWhenStreamAlreadyReportedTheError() {
        // An API/policy refusal arrives as an is_error result the parser already showed; the generic
        // "claude exited with code 1" line after it would only repeat the failure.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(1, "", true, false, false, false, l);
        assertTrue(l.errors.isEmpty(), "the stream's own error already told the user");
        assertEquals(1, l.exit, "onComplete still reports the exit code");
    }

    @Test
    void exitLineStillReportedWhenTheCliDiedWithoutAStreamError() {
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(1, "boom on stderr", false, false, false, false, l);
        assertEquals(1, l.errors.size(), "with no stream error, the exit line is the only diagnostic");
        assertTrue(l.errors.get(0).contains("claude exited with code 1"));
        assertTrue(l.errors.get(0).contains("boom on stderr"), "stderr tail must survive");
        assertEquals(1, l.exit);
    }

    @Test
    void reasoningHintSurvivesTheSuppressionGate() {
        // The unknown-option rejection happens at argv parse, before any stream-json exists — so
        // streamErrorSeen is false on that path and the checkbox hint still reaches the transcript.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(1, "error: unknown option '--thinking-display'", false, false, true, false, l);
        assertEquals(1, l.errors.size());
        assertTrue(l.errors.get(0).contains("Show reasoning"));
    }

    @Test
    void effortFlagRejectionNamesTheEffortPicker() {
        String msg = ClaudeCliProvider.failureMessage(1,
                "error: unknown option '--effort'", true, true);
        assertTrue(msg.contains("reset the effort picker"));
    }

    @Test
    void effortFlagRejectionIsExplainedEvenWhenReasoningDisplayIsOff() {
        String msg = ClaudeCliProvider.failureMessage(1,
                "error: unknown option '--effort'", false, true);
        assertTrue(msg.contains("reset the effort picker"));
        assertFalse(msg.contains("Show reasoning"), "the checkbox was not the failing option");
    }

    @Test
    void cleanExitReportsNoErrorLine() {
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "", false, true, false, false, l);
        assertTrue(l.errors.isEmpty());
        assertTrue(l.notices.isEmpty());
        assertEquals(0, l.exit);
    }

    @Test
    void aTurnThatSaidNothingAndExplainedNothingIsStillAccountedFor() {
        // Exit 0, no reply, nothing reported in the stream, no warning to quote. Every other shape here
        // has some account of the turn and this one had none, so the user was left with a blank exchange
        // and no reason for it. An error rather than a note, because a turn recorded as failed is also
        // kept out of the conversation the next turn resumes - which an empty reply must not join.
        RecordingChatListener l = new RecordingChatListener();

        ClaudeCliProvider.finishTurn(0, "", false, false, false, false, l);

        assertEquals(List.of(CliSupport.describeSilentTurn("claude")), l.errors,
                "the turn nothing else accounts for is described as exactly that");
        assertTrue(l.notices.isEmpty(), "there is no warning and no setting to point at");
        assertEquals(0, l.exit, "and the exit code is still reported");
    }

    @Test
    void aSilentTurnThatWasAlreadyExplainedIsNotExplainedTwice() {
        // The other silent shapes have their own account already - the stream's is_error result, the
        // non-zero exit line, or the warning note. Adding the generic one would tell the user twice.
        RecordingChatListener stream = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "", true, false, false, false, stream);
        assertTrue(stream.errors.isEmpty(), "the stream already reported this turn");
        assertTrue(stream.notices.isEmpty());

        RecordingChatListener died = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(9, "boom", false, false, false, false, died);
        assertEquals(1, died.errors.size(), "a turn that died reports how, not that it was silent");
        assertTrue(died.errors.get(0).contains("9"), died.errors.get(0));
    }

    // ---- a reasoning option the CLI accepted and then ignored --------------------------------------

    /** Verbatim from claude 2.1.220, which answers at its own effort and exits 0. */
    private static final String REAL_EFFORT_WARNING = "Warning: Unknown --effort value 'bogus' "
            + "\u2014 ignoring it and using the default effort. Valid values: low, medium, high, "
            + "xhigh, max.";

    @Test
    void anEffortTheCliWarnedAboutIsReportedEvenThoughTheTurnSucceeded() {
        // What a current claude CLI does with an --effort value it does not know: warn, then answer
        // at its own effort with exit 0. Nothing in the stream says the picked value was dropped.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, REAL_EFFORT_WARNING + "\n", false, true, false, true, l);

        assertEquals(1, l.notices.size(), "a silently dropped option has to be said out loud");
        assertTrue(l.notices.get(0).contains(REAL_EFFORT_WARNING),
                "the CLI's own words, since their wording is not a contract: " + l.notices.get(0));
        assertTrue(l.notices.get(0).contains("reset the effort picker to (default)"));
        assertTrue(l.errors.isEmpty(), "the turn itself did not fail");
        assertEquals(0, l.exit);
    }

    @Test
    void aTurnThatSaidNothingIsNotToldItRanAtTheCliEffort() {
        // The same warning and the same clean exit, with no reply. This turn ran on nothing the user can
        // see, and no error line or stream event says otherwise, so the note is the only account of it:
        // saying it ran at the CLI's own effort would credit an answer the transcript does not have.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, REAL_EFFORT_WARNING + "\n", false, false, false, true, l);

        assertEquals(1, l.notices.size(), "the warning is still the only evidence there is");
        assertTrue(l.notices.get(0).contains(REAL_EFFORT_WARNING));
        assertTrue(l.notices.get(0).contains("produced no reply"),
                "the note says what the turn did: " + l.notices.get(0));
        assertFalse(l.notices.get(0).contains("ran on the CLI's own"),
                "nothing ran at any effort: " + l.notices.get(0));
        assertTrue(l.notices.get(0).contains("reset the effort picker to (default)"));

        RecordingChatListener display = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "Warning: --thinking-display is not supported here\n",
                false, false, true, false, display);
        assertEquals(1, display.notices.size());
        assertTrue(display.notices.get(0).contains("produced no reply"),
                "and the same for the reasoning display: " + display.notices.get(0));
    }

    @Test
    void aWarningIsIgnoredWhenThisTurnAskedForNoReasoningOption() {
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "Warning: config file is deprecated\n", false, true, false, false, l);
        assertTrue(l.notices.isEmpty(),
                "an unrelated CLI warning must not point at controls this turn never used");
    }

    @Test
    void aWarningAboutSomethingElseIsNotBlamedOnTheReasoningControls() {
        // Warnings on a CLI's stderr are routine - an update notice, a deprecated setting, the login
        // shell's own rc output. Show reasoning is ticked, so the old gate ("did this turn pass ANY
        // reasoning option?") let every one of those accuse a picker that worked fine.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "Warning: a new version of Claude Code is available\n"
                + "Warning: ~/.zshrc: command not found: rbenv\n", false, true, true, true, l);
        assertTrue(l.notices.isEmpty(),
                "no option of ours was named, so nothing of ours was dropped: " + l.notices);
    }

    @Test
    void aDroppedReasoningDisplayIsReportedWithoutTalkingAboutTheEffortPicker() {
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "Warning: --thinking-display is not supported here\n",
                false, true, true, false, l);
        assertEquals(1, l.notices.size());
        assertFalse(l.notices.get(0).contains("effort picker"),
                "the effort picker had nothing to do with this: " + l.notices.get(0));
        assertTrue(l.notices.get(0).contains("not the one this turn asked for"));
    }

    @Test
    void aFailingRunReportsTheOptionOnceNotTwice() {
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(1, "Warning: unknown --effort value 'max', ignoring\n"
                + "error: unknown option '--effort'", false, false, false, true, l);
        assertEquals(1, l.errors.size());
        assertTrue(l.errors.get(0).contains("reset the effort picker"));
        assertTrue(l.notices.isEmpty(), "the error line already carries the hint");
    }

    @Test
    void aFailedTurnIsNeverToldItRanOnTheCliDefault() {
        // exit != 0 with the stream having already reported the failure: no error line is added here,
        // but the turn still failed, so a note claiming it ran anyway would contradict the transcript.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(1, "Warning: unknown --effort value 'max', ignoring\n",
                true, false, false, true, l);
        assertTrue(l.errors.isEmpty(), "the stream's own error already told the user");
        assertTrue(l.notices.isEmpty(), "a failed turn did not run on anything: " + l.notices);
        assertEquals(1, l.exit);
    }

    @Test
    void aTurnTheStreamFailedAndNeverAnsweredIsNotToldItRanOnTheCliDefault() {
        // claude can report the failure inside the event stream and still exit 0. Nothing replied, so
        // this turn ran at no effort at all, and a note saying it ran on the CLI default would
        // contradict the error the stream already printed.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "Warning: unknown --effort value 'max', ignoring\n",
                true, false, false, true, l);
        assertTrue(l.notices.isEmpty(), "a turn that never answered ran on nothing: " + l.notices);
        assertTrue(l.errors.isEmpty(), "the stream's own error already told the user");
        assertEquals(0, l.exit);
    }

    @Test
    void aStreamErrorTheTurnAnsweredThroughStillReportsTheDroppedOption() {
        // The other side of it: an error the run recovered from, followed by a reply. The turn did run,
        // at an effort the CLI chose instead of the one picked, so staying quiet here would leave the
        // picker looking like it took effect.
        RecordingChatListener l = new RecordingChatListener();
        ClaudeCliProvider.finishTurn(0, "Warning: unknown --effort value 'max', ignoring\n",
                true, true, false, true, l);
        assertEquals(1, l.notices.size(), "the answer came at some other effort than the one picked");
        assertTrue(l.notices.get(0).contains("reset the effort picker to (default)"));
        assertEquals(0, l.exit);
    }

    @Test
    void onlyWarningLinesAreQuotedAndTheQuoteIsBounded() {
        assertNull(ClaudeCliProvider.ignoredOptionWarning("just some progress chatter\n", false, true,
                true));
        assertNull(ClaudeCliProvider.ignoredOptionWarning(null, false, true, true));

        String notice = ClaudeCliProvider.ignoredOptionWarning(
                "loading\nWarning: --effort first thing\nnoise\n  Warning: --effort second thing\n",
                false, true, true);
        assertTrue(notice.contains("Warning: --effort first thing Warning: --effort second thing"));
        assertFalse(notice.contains("noise"), "only the warnings are the user's business");

        String flood = ClaudeCliProvider.ignoredOptionWarning(
                "Warning: --effort " + "x".repeat(900) + "\n", false, true, true);
        assertTrue(flood.contains("\u2026"), "a runaway warning is elided, not pasted whole");
        assertTrue(flood.length() < 700, "notice length stayed bounded: " + flood.length());
    }

    @Test
    void ordinaryFailuresCarryNoReasoningHint() {
        assertFalse(ClaudeCliProvider.failureMessage(1, "some other error", true, true)
                .contains("Show reasoning"));
        // Same stderr without the opt-in: the flag cannot have been passed, so no hint.
        assertFalse(ClaudeCliProvider.failureMessage(1,
                "error: unknown option '--thinking-display'", false, false).contains("Show reasoning"));
        // Nor when a diagnostic merely mentions --effort on a turn that never passed one.
        assertFalse(ClaudeCliProvider.failureMessage(1,
                "backend note: --effort is ignored for this account", false, false)
                .contains("effort picker"));
    }

    @Test
    void anOptionThatMerelyStartsTheSameWayIsADifferentOption() {
        // A CLI option ends where its name does, so --effortless is not --effort. Matching the name as a
        // bare substring told the user to reset the effort picker over a warning about some other flag,
        // and claimed this claude cannot take an option it took without complaint.
        assertNull(ClaudeCliProvider.ignoredOptionWarning(
                        "Warning: the --effortless preset was ignored\n", false, true, true),
                "a warning about another flag is not this turn's effort option");
        assertFalse(ClaudeCliProvider.failureMessage(1,
                        "error: unknown option '--effortlessly'", false, true)
                        .contains("effort picker"),
                "nor is a failure about one a reason to reset the picker");
        assertNull(ClaudeCliProvider.ignoredOptionWarning(
                        "Warning: --thinking-displays are off\n", true, false, true),
                "the same boundary holds for the other reasoning flag");

        // The option itself, however it is written on the line, still reads as itself.
        for (String warning : List.of("Warning: --effort was ignored\n",
                "Warning: --effort=max is not available\n", "Warning: ignoring '--effort'\n",
                "Warning: --effort,--verbose ignored\n")) {
            assertNotNull(ClaudeCliProvider.ignoredOptionWarning(warning, false, true, true), warning);
        }
        assertTrue(ClaudeCliProvider.failureMessage(1,
                        "error: unknown option '--effort'", false, true).contains("effort picker"),
                "the CLI that cannot take the option must still say so");
        // A line carrying both spellings is still about the one this turn passed.
        assertNotNull(ClaudeCliProvider.ignoredOptionWarning(
                        "Warning: --effortless replaced --effort\n", false, true, true),
                "the real option is found past the longer one that shares its start");
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
