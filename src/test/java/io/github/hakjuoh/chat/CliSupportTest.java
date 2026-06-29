package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executable resolution (the macOS GUI-PATH problem) and the failure-message helper. */
class CliSupportTest {

    @Test
    void resolvesViaDirectoryOverride(@TempDir Path dir) throws IOException {
        File exe = makeExecutable(dir, "faketool");
        assertEquals(exe.getAbsolutePath(), CliSupport.resolveExecutable("faketool", dir.toString()));
    }

    @Test
    void resolvesViaFullPathOverride(@TempDir Path dir) throws IOException {
        File exe = makeExecutable(dir, "faketool");
        assertEquals(exe.getAbsolutePath(), CliSupport.resolveExecutable("faketool", exe.getAbsolutePath()));
    }

    @Test
    void returnsNullForUnknownExecutable() {
        assertNull(CliSupport.resolveExecutable("zzz-no-such-cli-9c2f1a", ""));
    }

    @Test
    void describesFailureWithExitAndStderr() {
        String msg = CliSupport.describeFailure("codex", 2, "boom happened");
        assertTrue(msg.contains("codex"));
        assertTrue(msg.contains("2"));
        assertTrue(msg.contains("boom happened"));
    }

    @Test
    void shellQuoteWrapsAndEscapesSingleQuotes() {
        assertEquals("'plain'", CliSupport.shellQuote("plain"));
        assertEquals("'a'\\''b'", CliSupport.shellQuote("a'b"));
        // JSON with double quotes/braces is safe inside single quotes (unchanged).
        assertEquals("'{\"k\":\"v\"}'", CliSupport.shellQuote("{\"k\":\"v\"}"));
    }

    @Test
    void loginShellWrapRunsCommandViaLoginShellExec() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return; // no POSIX login shell on Windows; command runs directly
        }
        List<String> wrapped = CliSupport.loginShellWrap(List.of("/abs/claude", "-p", "hi there"));
        assertEquals(3, wrapped.size());
        assertEquals("-lc", wrapped.get(1));
        String script = wrapped.get(2);
        assertTrue(script.startsWith("exec "), script);
        assertTrue(script.contains("'/abs/claude'"));
        assertTrue(script.contains("'hi there'"), "args must be single-quoted so spaces/JSON survive");
    }

    private static File makeExecutable(Path dir, String name) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(f.setExecutable(true), "could not mark temp file executable");
        return f;
    }
}
