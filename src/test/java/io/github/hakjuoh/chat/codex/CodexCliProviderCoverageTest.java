package io.github.hakjuoh.chat.codex;

import io.github.hakjuoh.chat.ChatAttachment;
import io.github.hakjuoh.chat.ChatRequest;
import io.github.hakjuoh.chat.McpEndpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Supplementary coverage for {@link CodexCliProvider}: ChatProvider identity/model contract,
 * TOML {@code -c} override escaping, and the sessionId/model omission-and-trimming branches of
 * {@code buildCommand} that {@code CodexCliProviderTest} does not already exercise.
 */
class CodexCliProviderCoverageTest {

    private static final McpEndpoint ENDPOINT =
            new McpEndpoint("http://127.0.0.1:8123/mcp", "SECRET-TOKEN");

    private static CodexCliProvider provider() {
        return new CodexCliProvider();
    }

    // ---- ChatProvider identity / model contract --------------------------------------------

    @Test
    void idIsStableLowercaseCodex() {
        assertEquals("codex", provider().id(), "id() is the stable provider key");
    }

    @Test
    void displayNameIsCodex() {
        assertEquals("Codex", provider().displayName(), "displayName() is the UI label");
    }

    @Test
    void defaultModelIsEmptyMeaningCliDefault() {
        assertEquals("", provider().defaultModel(),
                "an empty default model defers to the CLI's own default");
    }

    @Test
    void listModelsReturnsTheKnownIdsWithBlankFirst() {
        List<String> models = provider().listModels();
        assertEquals(List.of("", "gpt-5.5", "gpt-5.4", "o3"), models,
                "listModels() offers the blank default plus common ids");
        assertEquals("", models.get(0), "the first entry is the blank (CLI-default) model");
    }

    @Test
    void listModelsIsImmutable() {
        List<String> models = provider().listModels();
        assertThrows(UnsupportedOperationException.class, () -> models.add("gpt-6"),
                "listModels() returns an immutable List.of()");
    }

    @Test
    void executableConstantIsCodex() {
        assertEquals("codex", CodexCliProvider.EXECUTABLE);
    }

    @Test
    void tokenEnvVarConstantIsProtegeMcpToken() {
        assertEquals("PROTEGE_MCP_TOKEN", CodexCliProvider.TOKEN_ENV_VAR);
    }

    // ---- buildCommand: structural ordering -------------------------------------------------

    @Test
    void executableIsAlwaysTheFirstArgument() {
        List<String> cmd = CodexCliProvider.buildCommand("/opt/bin/codex",
                new ChatRequest("", "hi", "", ENDPOINT));
        assertEquals("/opt/bin/codex", cmd.get(0), "the resolved exe leads the argv");
    }

    @Test
    void configOverridesPrecedeTheExecKeyword() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT));
        int approval = cmd.indexOf("approval_policy=\"never\"");
        int exec = cmd.indexOf("exec");
        assertTrue(approval >= 0, "approval_policy override present");
        assertTrue(exec >= 0, "exec keyword present");
        assertTrue(approval < exec, "-c overrides come before the exec subcommand");
    }

    @Test
    void promptIsTheFinalArgumentAfterDoubleDash() {
        ChatRequest req = new ChatRequest("", "final message", "", ENDPOINT);
        List<String> cmd = CodexCliProvider.buildCommand("codex", req);
        assertEquals("--", cmd.get(cmd.size() - 2), "'--' separates flags from the prompt");
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1),
                "the provider prompt is the trailing argument");
    }

    // ---- buildCommand: sessionId branches --------------------------------------------------

    @Test
    void nullSessionIdOmitsResume() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", null, ENDPOINT));
        assertFalse(cmd.contains("resume"), "a null sessionId starts a fresh exec run");
    }

    @Test
    void blankSessionIdOmitsResume() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT));
        assertFalse(cmd.contains("resume"), "an empty sessionId starts a fresh exec run");
    }

    @Test
    void whitespaceSessionIdOmitsResume() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "   \t ", ENDPOINT));
        assertFalse(cmd.contains("resume"), "a whitespace-only sessionId is treated as blank");
    }

    @Test
    void sessionIdIsTrimmedBeforeResume() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "  thread-99  ", ENDPOINT));
        int exec = cmd.indexOf("exec");
        assertTrue(exec >= 0);
        assertEquals("resume", cmd.get(exec + 1), "resume follows exec");
        assertEquals("thread-99", cmd.get(exec + 2), "the surrounding whitespace is stripped");
    }

    // ---- buildCommand: model branches ------------------------------------------------------

    @Test
    void nullModelOmitsModelFlag() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest(null, "hi", "", ENDPOINT));
        assertFalse(cmd.contains("-m"), "a null model leaves the CLI default in place");
    }

    @Test
    void blankModelOmitsModelFlag() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT));
        assertFalse(cmd.contains("-m"), "an empty model leaves the CLI default in place");
    }

    @Test
    void whitespaceModelOmitsModelFlag() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("   ", "hi", "", ENDPOINT));
        assertFalse(cmd.contains("-m"), "a whitespace-only model is treated as blank");
    }

    @Test
    void modelIsTrimmedBeforeModelFlag() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("  o3  ", "hi", "", ENDPOINT));
        int i = cmd.indexOf("-m");
        assertTrue(i >= 0 && i + 1 < cmd.size(), "-m flag present with a value");
        assertEquals("o3", cmd.get(i + 1), "the model id is trimmed");
    }

    // ---- buildCommand: TOML escaping in -c overrides ---------------------------------------

    @Test
    void tomlEscapesEmbeddedDoubleQuotesInUrl() {
        McpEndpoint quoted = new McpEndpoint("http://h/\"q\"", "T");
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", quoted));
        assertTrue(cmd.contains("mcp_servers.protege.url=\"http://h/\\\"q\\\"\""),
                "embedded double quotes are backslash-escaped inside the TOML literal");
    }

    @Test
    void tomlEscapesEmbeddedBackslashesInUrl() {
        McpEndpoint backslash = new McpEndpoint("http://h/a\\b", "T");
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", backslash));
        assertTrue(cmd.contains("mcp_servers.protege.url=\"http://h/a\\\\b\""),
                "a backslash is doubled inside the TOML literal");
    }

    @Test
    void tomlEscapesBackslashBeforeQuoteSoQuoteEscapeIsNotDoubled() {
        // Order matters: escaping backslashes first then quotes means the escaping backslash
        // introduced for a quote is NOT itself re-escaped. Input: \"  ->  \\\"  (not \\\\").
        McpEndpoint mixed = new McpEndpoint("http://h/\\\"", "T");
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", mixed));
        assertTrue(cmd.contains("mcp_servers.protege.url=\"http://h/\\\\\\\"\""),
                "input backslash-quote becomes doubled-backslash then escaped-quote");
    }

    @Test
    void staticOverridesUseFixedTomlLiterals() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT));
        assertTrue(cmd.contains("approval_policy=\"never\""));
        assertTrue(cmd.contains("sandbox_mode=\"read-only\""));
        assertTrue(cmd.contains("mcp_servers.protege.bearer_token_env_var=\"PROTEGE_MCP_TOKEN\""));
        assertTrue(cmd.contains("mcp_servers.protege.default_tools_approval_mode=\"approve\""));
    }

    @Test
    void bearerTokenEnvVarOverrideCarriesTheEnvVarNameNotTheToken() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "hi", "", ENDPOINT));
        assertTrue(cmd.stream().noneMatch(a -> a.contains("SECRET-TOKEN")),
                "the raw token never appears in any -c override");
        assertTrue(cmd.contains("mcp_servers.protege.bearer_token_env_var=\"PROTEGE_MCP_TOKEN\""),
                "the override names the env var Codex reads the token from");
    }

    // ---- buildCommand: attachments ---------------------------------------------------------

    @Test
    void multipleImagesEachGetTheirOwnImageFlag(@TempDir Path dir) throws Exception {
        File a = Files.writeString(dir.resolve("a.png"), "x").toFile();
        File b = Files.writeString(dir.resolve("b.png"), "y").toFile();
        ChatRequest req = new ChatRequest("", "two images", "", ENDPOINT,
                List.of(ChatAttachment.image("Image #1", a, "image/png"),
                        ChatAttachment.image("Image #2", b, "image/png")));

        List<String> cmd = CodexCliProvider.buildCommand("codex", req);

        assertEquals(2, cmd.stream().filter("--image"::equals).count(),
                "each image attachment adds its own --image flag");
        assertTrue(cmd.contains(a.getAbsolutePath()));
        assertTrue(cmd.contains(b.getAbsolutePath()));
    }

    @Test
    void pastedTextAttachmentDoesNotUseImageFlag() {
        ChatRequest req = new ChatRequest("", "see [Paste #1]", "", ENDPOINT,
                List.of(ChatAttachment.pastedText("Paste #1", "a long body")));

        List<String> cmd = CodexCliProvider.buildCommand("codex", req);

        assertFalse(cmd.contains("--image"), "pasted text travels in the prompt, not via --image");
        assertEquals(req.providerPrompt(), cmd.get(cmd.size() - 1),
                "the enriched provider prompt (including pasted body) is the trailing argument");
    }

    @Test
    void noAttachmentsMeansNoImageFlag() {
        List<String> cmd = CodexCliProvider.buildCommand("codex",
                new ChatRequest("", "plain", "", ENDPOINT));
        assertFalse(cmd.contains("--image"), "a plain request carries no --image flag");
    }

    @Test
    void emptyPromptIsStillPassedAsTrailingArgument() {
        ChatRequest req = new ChatRequest("", "", "", ENDPOINT);
        List<String> cmd = CodexCliProvider.buildCommand("codex", req);
        assertEquals("", cmd.get(cmd.size() - 1), "an empty prompt is still emitted after --");
    }

    // ---- startTurn: null-executable error branch (no prefs mutation needed) -----------------

    @Test
    void startTurnThrowsInformativeIOExceptionWhenExecutableIsNull() {
        // resolveExecutable() returns null only when neither the pref override nor PATH yields a
        // 'codex' binary. Rather than depend on the host having/not-having codex, assert the message
        // shape only when we actually observe the not-found path; otherwise skip without failing.
        CodexCliProvider p = provider();
        if (p.isAvailable()) {
            // Codex is installed on this host; the not-found branch is unreachable here.
            return;
        }
        io.github.hakjuoh.chat.ChatListener sink = new RecordingListener();
        java.io.IOException ex = assertThrows(java.io.IOException.class,
                () -> p.startTurn(new ChatRequest("", "hi", "", ENDPOINT), sink),
                "a missing codex CLI surfaces as an IOException");
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("codex"),
                "the error names the missing 'codex' CLI: " + ex.getMessage());
    }

    /** Minimal no-op ChatListener (every ChatListener method has a no-op default). */
    private static final class RecordingListener implements io.github.hakjuoh.chat.ChatListener {
    }
}
