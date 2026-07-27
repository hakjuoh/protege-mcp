package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.AssistantSteering;
import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;
import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Codex {@code exec --json} argv, its {@code -c} overrides, and that the token stays off the CLI. */
class CodexCliProviderTest {

    private static final McpEndpoint ENDPOINT = new McpEndpoint("http://127.0.0.1:8123/mcp", "SECRET-TOKEN");

    @Test
    void buildsFreshExecArgvWithConfigOverrides() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("gpt-5.5", "hi there", "", ENDPOINT));

        assertEquals("codex", cmd.get(0));
        assertTrue(cmd.contains("exec"));
        assertFalse(cmd.contains("resume"));
        assertTrue(cmd.contains("--json"));
        assertTrue(cmd.contains("--skip-git-repo-check"));
        assertAdjacent(cmd, "-m", "gpt-5.5");

        assertTrue(cmd.contains("approval_policy=\"never\""));
        assertTrue(cmd.contains("sandbox_mode=\"read-only\""));
        assertTrue(cmd.contains("mcp_servers.protege.url=\"http://127.0.0.1:8123/mcp\""));
        assertTrue(cmd.contains("mcp_servers.protege.bearer_token_env_var=\"PROTEGE_MCP_TOKEN\""));
        assertTrue(cmd.contains("mcp_servers.protege.default_tools_approval_mode=\"approve\""));

        assertEquals("--", cmd.get(cmd.size() - 2));
        assertEquals(AssistantSteering.SYSTEM_PROMPT + "\n\nhi there", cmd.get(cmd.size() - 1),
                "a new thread's first message leads with the write-workflow steering");
    }

    @Test
    void resumeUsesThreadId() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "again", "thread-7", ENDPOINT));
        int exec = cmd.indexOf("exec");
        assertTrue(exec >= 0);
        assertEquals("resume", cmd.get(exec + 1));
        assertEquals("thread-7", cmd.get(exec + 2));
    }

    @Test
    void resumedThreadDoesNotRepeatTheSteeringPreamble() {
        // The preamble is already in the resumed thread's history; repeating it every turn would
        // push the actual user message further from the model's attention.
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "again", "thread-7", ENDPOINT));
        assertEquals("again", cmd.get(cmd.size() - 1));
    }

    @Test
    void newSessionSteeringPrecedesHandoffContextAndAttachments(@TempDir Path dir) throws Exception {
        // Steering is developer policy: it must lead the first message, ahead of the handoff
        // recap and the attachment appendix that providerPrompt() builds.
        Path doc = Files.writeString(dir.resolve("notes.txt"), "x");
        ChatRequest req = new ChatRequest("", "see [File #1: notes.txt]", "", ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", doc.toFile(), null)),
                false, "Earlier turns: ...");
        List<String> cmd = CodexCliProvider.buildCommand("codex", req);
        String prompt = cmd.get(cmd.size() - 1);
        assertTrue(prompt.startsWith(AssistantSteering.SYSTEM_PROMPT + "\n\n"),
                "steering must lead the first message");
        assertEquals(AssistantSteering.SYSTEM_PROMPT + "\n\n" + req.providerPrompt(), prompt,
                "the provider prompt (handoff + message + attachments) must survive unchanged");
    }

    @Test
    void exitLineSuppressedWhenStreamAlreadyReportedTheError() {
        // A turn.failed/error the parser already showed; the generic "codex exited with code N"
        // line after it would only repeat the failure.
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "", true, "stream said it already", false, null, l);
        assertTrue(l.errors.isEmpty(), "the stream's own error already told the user");
        assertEquals(1, l.exit, "onComplete still reports the exit code");
    }

    @Test
    void exitLineStillReportedWhenTheCliDiedWithoutAStreamError() {
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(101, "panic: something broke", false, "", false, null, l);
        assertEquals(1, l.errors.size(), "with no stream error, the exit line is the only diagnostic");
        assertTrue(l.errors.get(0).contains("codex exited with code 101"));
        assertTrue(l.errors.get(0).contains("panic: something broke"), "stderr tail must survive");
        assertEquals(101, l.exit);
    }

    @Test
    void cleanExitReportsNoErrorLine() {
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(0, "", false, "", true, null, l);
        assertTrue(l.errors.isEmpty());
        assertEquals(0, l.exit);
        assertTrue(l.notices.isEmpty());
    }

    // ---- a refused reasoning effort ---------------------------------------------------------------

    @Test
    void aRefusedEffortIsNamedAsTheEffortPickersDoing() {
        // What the API actually answers when a model does not support the picked value. It names the
        // supported values but nothing about where 'max' came from, so the hint has to.
        String streamError = "unexpected status 400 Bad Request: {\"error\":{\"message\":"
                + "\"Invalid value: 'max'. Supported values are: 'none', 'minimal', 'low', 'medium', "
                + "'high', and 'xhigh'.\"}}";
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(1, "", true, streamError, false, "max", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices,
                "the turn failed on a control the error never mentions: say which one");
        assertTrue(l.errors.isEmpty(), "the stream already reported the failure itself");
        assertEquals(1, l.exit);
    }

    @Test
    void anEffortRejectionInStderrAloneIsStillNamed() {
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "error: unknown config key model_reasoning_effort", false, "",
                false, "high", l);
        assertEquals(1, l.errors.size(), "the exit line is still the only diagnostic");
        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices);
    }

    @Test
    void theEffortNoteNeverDecidesWhoRefusedTheValue() {
        // One note covers two refusers: the API turning down a value the model does not support, and a
        // Codex release whose own parser does not know model_reasoning_effort at all. Blaming the model
        // would send a user in the second case hunting for a supported value that exists, so the note
        // points at the diagnostic and at the way out that works either way.
        String hint = CodexCliProvider.EFFORT_HINT.toLowerCase(java.util.Locale.ROOT);
        assertFalse(hint.contains("model"), "the note must not attribute what it cannot know");
        assertTrue(CodexCliProvider.EFFORT_HINT.contains("(default)"));
        // The second refuser names no value at all ("unknown config key model_reasoning_effort"), so
        // telling the user to pick one the error lists would send them looking for a list that is not
        // there. Both notes therefore offer that route conditionally and (default) unconditionally.
        assertTrue(CodexCliProvider.EFFORT_HINT.contains("if it lists any"),
                "the note must not promise the diagnostic lists values to choose from");
        assertTrue(CodexCliProvider.EFFORT_IGNORED_HINT.contains("if it names any"),
                "the ignored-effort note must not promise it either");
        assertTrue(CodexCliProvider.EFFORT_IGNORED_HINT.contains("(default)"));
    }

    @Test
    void aTurnThatPickedNoEffortIsNeverPointedAtTheEffortPicker() {
        // Same 400 shape, but for some other invalid value: sending the user to a control they left
        // at (default) would be a wrong diagnosis.
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "", true,
                "Invalid value: 'nope'. Supported values are: 'a', 'b'.", false, null, l);
        assertTrue(l.notices.isEmpty());
    }

    @Test
    void anOrdinaryFailureCarriesNoEffortHint() {
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "stream disconnected before completion", false, "", false, "high", l);
        assertTrue(l.notices.isEmpty(), "an unrelated failure must not blame the effort picker");
    }

    @Test
    void effortRejectionRecognisesBothDiagnosticShapes() {
        assertTrue(CodexCliProvider.effortRejection("high", "unknown key MODEL_REASONING_EFFORT"),
                "config-key rejections arrive in either case");
        assertTrue(CodexCliProvider.effortRejection("max",
                "Invalid value: 'max'. Supported values are: 'low', 'high'."));
        assertTrue(CodexCliProvider.effortRejection("max",
                "Reasoning effort is invalid for this model"));
        assertFalse(CodexCliProvider.effortRejection("max", null, "", "  "));
        assertFalse(CodexCliProvider.effortRejection("max", "request timed out"));
        assertFalse(CodexCliProvider.effortRejection(null, "unknown key model_reasoning_effort"),
                "nothing was asked for, so nothing could have been refused");
    }

    @Test
    void aSettingNamedInProseIsARefusalHoweverItIsWorded() {
        // A diagnostic that spells the setting out has said which control it means, and it is the same
        // control whichever way the sentence refuses it. Recognising only "invalid" left the plainest
        // wording of all - the API saying the model does not support the level - with no note at all, on
        // exactly the failure this note exists for.
        for (String diagnostic : List.of(
                "The selected model does not support reasoning effort 'max'.",
                "This model doesn't support the reasoning effort you requested.",
                "reasoning effort is not supported by gpt-5-syn",
                "reasoning effort must be one of: low, medium",
                "unsupported reasoning effort")) {
            assertTrue(CodexCliProvider.effortRejection("max", diagnostic), diagnostic);
            assertFalse(CodexCliProvider.alsoTheRequestedModel("max", "max", diagnostic),
                    "a text that names the setting is not ambiguous about it: " + diagnostic);
        }
        // Naming the setting is not complaining about it: a run that reports what it used has refused
        // nothing, and a note there would tell the user to change a control that worked.
        assertFalse(CodexCliProvider.effortRejection("high", "reasoning effort: high"),
                "a text that only says what it ran with is not a rejection");
        assertFalse(CodexCliProvider.effortRejection("high",
                        "thinking with reasoning effort high took 12s"),
                "nor is one that only reports how long it took");
    }

    @Test
    void theOverrideKeyEchoedBackIsNotARefusalOfIt() {
        // Codex prints the configuration it was handed. A line that names the override key and refuses
        // nothing is the turn running at exactly the effort the user picked, and a note there would tell
        // them their choice was rejected by the turn that honoured it - or, on a turn that failed for some
        // unrelated reason, send them to the one control that was not the problem.
        for (String diagnostic : List.of(
                "debug: model_reasoning_effort=high",
                "config: model_reasoning_effort=\"high\" (from -c)",
                "  model_reasoning_effort: high")) {
            assertFalse(CodexCliProvider.effortRejection("high", diagnostic), diagnostic);
            assertTrue(CodexCliProvider.alsoTheRequestedModel("high", "high", diagnostic),
                    "a text that refuses nothing has settled nothing either: " + diagnostic);
        }
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "debug: model_reasoning_effort=high\nerror: disk full", false,
                "", false, "high", l);
        assertTrue(l.notices.isEmpty(),
                "an unrelated failure must not blame the effort picker: " + l.notices);

        // Every wording that does refuse the key still reads as one, in either case.
        for (String diagnostic : List.of(
                "error: unknown config key model_reasoning_effort",
                "error: unrecognised config key `model_reasoning_effort`",
                "error: unrecognized config key `model_reasoning_effort`",
                "MODEL_REASONING_EFFORT: no such setting",
                "invalid value for model_reasoning_effort",
                "model_reasoning_effort cannot be set for this model")) {
            assertTrue(CodexCliProvider.effortRejection("high", diagnostic), diagnostic);
        }
    }

    @Test
    void aRefusalOnAnotherLineIsNotARefusalOfTheEffort() {
        // A diagnostic is not a sentence: the stream's errors and the CLI's stderr arrive as lines, and
        // "next to" is the whole claim that ties a wording of refusal to the setting it refuses. Read as
        // one blob, a debug line echoing the override the turn ran with plus any unrelated line that
        // refuses something - a mistyped model id, a config key the user set themselves - would classify
        // as the effort being rejected and send them to the one picker that worked.
        for (String diagnostic : List.of(
                "debug: model_reasoning_effort=high\nerror: unknown model 'gpt-5-codexx'",
                "error: unknown model 'gpt-5-codexx'\ndebug: model_reasoning_effort=high",
                "config: model_reasoning_effort=\"high\"\r\nerror: unrecognised config key `sandbox`",
                "  reasoning effort: high\nerror: cannot open /tmp/x: no such file")) {
            assertFalse(CodexCliProvider.effortRejection("high", diagnostic), diagnostic);
        }
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "debug: model_reasoning_effort=high", true,
                "error: unknown model 'gpt-5-codexx'", false, "high", l);
        assertTrue(l.notices.isEmpty(),
                "two diagnostics are two lines, not one sentence either: " + l.notices);

        // A refusal that is on the line naming the setting still reads as one, wherever it sits.
        for (String diagnostic : List.of(
                "starting turn\nerror: unknown config key model_reasoning_effort\ndone",
                "error: unknown model 'gpt-5-codexx'\nreasoning effort 'high' is not supported")) {
            assertTrue(CodexCliProvider.effortRejection("high", diagnostic), diagnostic);
        }
    }

    @Test
    void aModelRefusedOnTheSameLineAsAnEchoedOverrideIsStillTheModel() {
        // Lines are not the only thing a diagnostic joins: one line can carry both the failure and the
        // configuration the run was handed. What decides the reading is which setting the refusal is
        // attached to - "invalid model 'gpt-bad'" says the model, whichever side of it the echoed override
        // sits on, and a notice about the effort picker there sends the user to a control that worked.
        for (String diagnostic : List.of(
                "error: invalid model 'gpt-bad'; model_reasoning_effort=high",
                "model_reasoning_effort=high; error: invalid model 'gpt-bad'",
                "error: unknown model \"gpt-bad\" (reasoning effort: high)")) {
            assertFalse(CodexCliProvider.effortRejection("high", diagnostic), diagnostic);
        }
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "", true,
                "error: invalid model 'gpt-bad'; model_reasoning_effort=high", false, "high", l);
        assertTrue(l.notices.isEmpty(), "no notice about the effort picker: " + l.notices);

        // The setting's own attribution still wins wherever it is there to be read: a release that refuses
        // the override key by name, a line that refuses both, and one that names the model as the thing the
        // effort is unsupported *for* are all refusals of the effort.
        for (String diagnostic : List.of(
                "error: unknown config key model_reasoning_effort",
                "error: invalid model 'gpt-bad' and invalid reasoning effort 'high'",
                "unsupported reasoning effort 'high' for model 'gpt-5'",
                "the model does not support reasoning effort 'high'")) {
            assertTrue(CodexCliProvider.effortRejection("high", diagnostic), diagnostic);
        }
    }

    @Test
    void anEffortTheApiSaysIsNotAvailableIsARefusalOfIt() {
        // Refusing by availability is a refusal: the turn failed for the value the picker sent, and a
        // diagnostic naming neither this plugin's control nor a way out leaves the user with nothing to
        // connect the failure to unless the notice does it.
        for (String diagnostic : List.of(
                "reasoning effort 'max' is not available for this model",
                "reasoning effort 'max' is unavailable on gpt-5",
                "model_reasoning_effort isn't available for your account",
                "reasoning effort 'max' is not enabled for this organization")) {
            assertTrue(CodexCliProvider.effortRejection("max", diagnostic), diagnostic);
        }
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "reasoning effort 'max' is not available for this model", true,
                "", false, "max", l);
        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices,
                "and the user is told which picker to change");
    }

    @Test
    void aRejectedModelIdIsNotBlamedOnTheEffortPicker() {
        // The API refuses a value it does not know in the same words whatever the value was, and a
        // model id is typed by hand into the catalog, so a typo there is the likeliest way to see this
        // shape. The effort the turn actually sent is 'high'; 'gpt-5-codexx' is not it.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(1, "", true,
                "unexpected status 400 Bad Request: {\"error\":{\"message\":\"Invalid value: "
                        + "'gpt-5-codexx'. Supported values are: 'gpt-5', 'gpt-5-codex'.\"}}",
                false, "high", l);

        assertTrue(l.notices.isEmpty(),
                "the value the API refused was not the effort this turn sent: " + l.notices);
    }

    @Test
    void onlyAWholeRefusedValueCountsAsTheEffortThisTurnSent() {
        // A refused value is matched whole. 'high.foo' and 'highest' merely start with what was sent,
        // and a bare value inside a dotted setting name is that name, not the effort - reading either
        // as a refusal of 'high' points the user at a control that had nothing to do with it.
        assertFalse(CodexCliProvider.effortRejection("high",
                "Invalid value: 'high.foo'. Supported values are: 'a', 'b'."));
        assertFalse(CodexCliProvider.effortRejection("high", "Invalid value: 'highest'."));
        assertFalse(CodexCliProvider.effortRejection("low", "invalid value: model.low.beta"));
        assertTrue(CodexCliProvider.effortRejection("high", "Invalid value: high"),
                "an unquoted value is still the value");
        assertTrue(CodexCliProvider.effortRejection("high", "Invalid value: \"high\""),
                "and so is a double-quoted one");
        assertTrue(CodexCliProvider.effortRejection("xhigh",
                "Invalid value: 'max'. Invalid value: 'xhigh'."),
                "every value a diagnostic refuses is considered, not just the first");
    }

    @Test
    void aTurnThatAnsweredIsNotToldItWasRefused() {
        // Codex complains about the override and runs the turn anyway: there is no error listing the
        // values to choose from, and the reply is real - so the refusal wording would be false twice.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(0, "warning: unknown config key model_reasoning_effort", false, "",
                true, "high", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_IGNORED_HINT), l.notices,
                "a turn that succeeded must be told its effort was ignored, not refused");
        assertTrue(l.errors.isEmpty());
    }

    @Test
    void aTurnThatSaidNothingIsNotToldItAnsweredAnyway() {
        // The same clean exit and the same stderr complaint as the turn above, with no reply. Nothing else
        // says a word about this turn - the exit was 0 and the stream reported nothing - so the note is the
        // whole account of it, and "answered anyway" would promise a reply that is not on screen.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(0, "warning: unknown config key model_reasoning_effort", false, "",
                false, "high", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices,
                "a turn with no reply must not be described as having answered");
        assertTrue(l.errors.isEmpty(), "codex itself reported no failure");
    }

    @Test
    void aTurnThatSaidNothingAndExplainedNothingIsStillAccountedFor() {
        // Exit 0, no reply, nothing reported in the stream, no diagnostic naming a setting: every other
        // path here has something to say about the turn and this one had nothing, so the user was left
        // with a blank exchange and no reason for it. An error rather than a note, because the failed
        // turn must also stay out of the conversation the next turn resumes.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(0, "", false, "", false, null, l);

        assertEquals(List.of(CliSupport.describeSilentTurn("codex")), l.errors,
                "the turn nothing else accounts for is described as exactly that");
        assertTrue(l.notices.isEmpty(), "there is no setting to point at");
        assertEquals(0, l.exit, "and the exit code is still reported");
    }

    @Test
    void aTurnThatWasAlreadyExplainedIsNotExplainedTwice() {
        // Each of the other silent-turn shapes has an account already: the stream's own error, the
        // non-zero exit line, or the effort note. Adding the generic one on top would tell the user
        // twice, and the second telling names no cause.
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(0, "", true, "the stream said why", false, null, l);
        assertTrue(l.errors.isEmpty(), "the stream already reported this turn");

        RecordingChatListener died = new RecordingChatListener();
        CodexCliProvider.finishTurn(9, "panic", false, "", false, null, died);
        assertEquals(List.of(CliSupport.describeFailure("codex", 9, "panic")), died.errors,
                "a turn that died reports how, not that it was silent");
    }

    @Test
    void aStreamReportedRefusalOnAZeroExitIsStillARefusal() {
        // codex can report the failure in the event stream and still exit 0; the turn did not answer.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(0, "", true,
                "Invalid value: 'max'. Supported values are: 'low', 'high'.", false, "max", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices);
    }

    @Test
    void aRefusalTheTurnRecoveredFromIsNotDescribedAsOne() {
        // codex surfaces the errors of its retry loop as it goes, so the same 400 can be printed and the
        // turn still answer from codex's own setting. The reply is on screen: telling the user their
        // question was refused would describe a turn they can see was answered.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(0, "", true,
                "Invalid value: 'max'. Supported values are: 'low', 'high'.", true, "max", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_IGNORED_HINT), l.notices,
                "a turn that reported the rejection and answered anyway was not refused");
    }

    @Test
    void textFromATurnThatThenDiedIsNotAnAnswer() {
        // A non-zero exit is decisive on its own: whatever text arrived is a fragment of a turn that
        // never finished, so the effort really was refused and the values to pick from matter.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(1, "", true,
                "Invalid value: 'max'. Supported values are: 'low', 'high'.", true, "max", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices,
                "a partial reply from a failed turn must not soften the diagnosis");
    }

    @Test
    void aValueBothPickersCarryIsNotPinnedOnTheEffortAlone() {
        // A model id is typed by hand into the catalog, so nothing stops one from being spelled 'high'.
        // The 400 names a value and no setting: it fits the effort override and the model argument
        // equally well, and sending the user to the effort picker would be a guess presented as a fact.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(1, "", true,
                "unexpected status 400 Bad Request: {\"error\":{\"message\":\"Invalid value: 'high'."
                        + " Supported values are: 'gpt-5', 'gpt-5-codex'.\"}}",
                false, "high", "high", l);

        assertEquals(List.of(CodexCliProvider.AMBIGUOUS_HINT), l.notices,
                "the error decides between the two pickers no better than a coin: say so");
    }

    @Test
    void theSameCollisionOnATurnThatAnsweredKeepsTheAmbiguity() {
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(0, "", true, "Invalid value: 'high'.", true, "high", "high", l);

        assertEquals(List.of(CodexCliProvider.AMBIGUOUS_IGNORED_HINT), l.notices,
                "a reply arrived, and which picker was complained about is still unknown");
    }

    @Test
    void aDiagnosticThatNamesTheSettingEndsTheAmbiguity() {
        // Same collision, but this text says which setting it means. The model picker being set to the
        // same value changes nothing then: the effort note is the accurate one.
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(1, "error: unknown config key model_reasoning_effort", false, "",
                false, "high", "high", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices);
        assertFalse(CodexCliProvider.alsoTheRequestedModel("high", "high",
                "invalid reasoning effort for this model"),
                "prose that spells out the effort has named its setting too");
    }

    @Test
    void anOrdinaryModelLeavesTheEffortNoteUnambiguous() {
        RecordingChatListener l = new RecordingChatListener();

        CodexCliProvider.finishTurn(1, "", true, "Invalid value: 'high'.", false, "high", "gpt-5.5", l);

        assertEquals(List.of(CodexCliProvider.EFFORT_HINT), l.notices,
                "only the effort this turn sent carries that value, so nothing is in doubt");
        assertFalse(CodexCliProvider.alsoTheRequestedModel("high", null, "Invalid value: 'high'."),
                "a turn at codex's own default model named no model at all");
        assertFalse(CodexCliProvider.alsoTheRequestedModel("high", "  ", "Invalid value: 'high'."));
        assertTrue(CodexCliProvider.alsoTheRequestedModel("high", " HIGH ", "Invalid value: 'high'."),
                "the picker's casing and padding are not what makes the two differ");
    }

    @Test
    void bothAmbiguityNotesNameBothPickersAndNeitherVerdict() {
        for (String note : List.of(CodexCliProvider.AMBIGUOUS_HINT,
                CodexCliProvider.AMBIGUOUS_IGNORED_HINT)) {
            String lower = note.toLowerCase(java.util.Locale.ROOT);
            assertTrue(lower.contains("model") && lower.contains("effort"),
                    "the note has to name both candidates: " + note);
            assertTrue(note.contains("(default)"), "and the way out that always works: " + note);
            assertTrue(lower.contains("does not say which"),
                    "and must not pretend the error decided: " + note);
        }
    }

    @Test
    void theRefusedValueIsMatchedWholeNotAsAPrefix() {
        assertFalse(CodexCliProvider.effortRejection("low",
                "Invalid value: 'lowest'. Supported values are: 'a', 'b'."),
                "'low' must not claim a rejection of 'lowest'");
        assertTrue(CodexCliProvider.effortRejection("low",
                "Invalid value: 'low'. Supported values are: 'a', 'b'."));
        assertTrue(CodexCliProvider.effortRejection("XHigh", "invalid value: xhigh"),
                "the CLI's casing and quoting are not a contract");
    }

    @Test
    void showReasoningRequestsDetailedSummaries() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT, List.of(), true));
        int override = cmd.indexOf("model_reasoning_summary=\"detailed\"");
        assertTrue(override >= 0, "reasoning opt-in must set a summary mode: " + cmd);
        // A global -c override: must precede the exec subcommand so exec resume gets it too.
        assertTrue(override < cmd.indexOf("exec"));
    }

    @Test
    void noReasoningSummaryOverrideWhenOff() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT));
        assertTrue(cmd.stream().noneMatch(a -> a.contains("model_reasoning_summary")),
                "no summary override without the opt-in: " + cmd);
    }

    @Test
    void selectedReasoningEffortUsesCodexConfigOverride() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT, List.of(), false, "", "high"));
        assertTrue(cmd.contains("model_reasoning_effort=\"high\""));
        assertTrue(cmd.indexOf("model_reasoning_effort=\"high\"") < cmd.indexOf("exec"));
    }

    @Test
    void blankReasoningEffortLeavesCodexDefaultUntouched() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT, List.of(), false, "", ""));
        assertTrue(cmd.stream().noneMatch(value -> value.contains("model_reasoning_effort")));
    }

    @Test
    void neverPutsTheBearerTokenOnTheCommandLine() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("gpt-5.5", "hi", "", ENDPOINT));
        assertTrue(cmd.stream().noneMatch(a -> a.contains("SECRET-TOKEN")),
                "the token must travel via the PROTEGE_MCP_TOKEN env var, never argv");
    }

    @Test
    void imageAttachmentsUseCodexImageFlag(@TempDir Path dir) throws Exception {
        Path image = Files.writeString(dir.resolve("screen.png"), "fake");
        ChatRequest req = new ChatRequest("", "look at [Image #1]", "", ENDPOINT,
                List.of(ChatAttachment.image("Image #1", image.toFile(), "image/png")));

        List<String> cmd = CodexCliProvider.buildCommand("codex", req);

        assertAdjacent(cmd, "--image", image.toFile().getAbsolutePath());
        assertEquals(AssistantSteering.SYSTEM_PROMPT + "\n\n" + req.providerPrompt(),
                cmd.get(cmd.size() - 1));
    }

    @Test
    void fileAttachmentsDoNotUseCodexImageFlag(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("notes.txt"), "x");
        ChatRequest req = new ChatRequest("", "look at [File #1: notes.txt]", "", ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", doc.toFile(), null)));

        List<String> cmd = CodexCliProvider.buildCommand("codex", req);

        assertFalse(cmd.contains("--image"), "a plain file must not be passed via --image");
        assertEquals(AssistantSteering.SYSTEM_PROMPT + "\n\n" + req.providerPrompt(),
                cmd.get(cmd.size() - 1));
    }

    private static void assertAdjacent(List<String> cmd, String flag, String value) {
        int i = cmd.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < cmd.size(), "missing flag " + flag);
        assertEquals(value, cmd.get(i + 1), "value after " + flag);
    }
}
