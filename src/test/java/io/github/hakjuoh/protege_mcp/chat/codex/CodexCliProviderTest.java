package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
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
        assertEquals("hi there", cmd.get(cmd.size() - 1));
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
    void exitLineSuppressedWhenStreamAlreadyReportedTheError() {
        // A turn.failed/error the parser already showed; the generic "codex exited with code N"
        // line after it would only repeat the failure.
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(1, "", true, l);
        assertTrue(l.errors.isEmpty(), "the stream's own error already told the user");
        assertEquals(1, l.exit, "onComplete still reports the exit code");
    }

    @Test
    void exitLineStillReportedWhenTheCliDiedWithoutAStreamError() {
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(101, "panic: something broke", false, l);
        assertEquals(1, l.errors.size(), "with no stream error, the exit line is the only diagnostic");
        assertTrue(l.errors.get(0).contains("codex exited with code 101"));
        assertTrue(l.errors.get(0).contains("panic: something broke"), "stderr tail must survive");
        assertEquals(101, l.exit);
    }

    @Test
    void cleanExitReportsNoErrorLine() {
        RecordingChatListener l = new RecordingChatListener();
        CodexCliProvider.finishTurn(0, "", false, l);
        assertTrue(l.errors.isEmpty());
        assertEquals(0, l.exit);
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
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1));
    }

    @Test
    void fileAttachmentsDoNotUseCodexImageFlag(@TempDir Path dir) throws Exception {
        Path doc = Files.writeString(dir.resolve("notes.txt"), "x");
        ChatRequest req = new ChatRequest("", "look at [File #1: notes.txt]", "", ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", doc.toFile(), null)));

        List<String> cmd = CodexCliProvider.buildCommand("codex", req);

        assertFalse(cmd.contains("--image"), "a plain file must not be passed via --image");
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1));
    }

    private static void assertAdjacent(List<String> cmd, String flag, String value) {
        int i = cmd.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < cmd.size(), "missing flag " + flag);
        assertEquals(value, cmd.get(i + 1), "value after " + flag);
    }
}
