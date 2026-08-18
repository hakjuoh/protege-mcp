package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/** Executable resolution (the macOS GUI-PATH problem) and the failure-message helper. */
class CliSupportTest {

    @Test
    void resolvesViaDirectoryOverride(@TempDir Path dir) throws IOException {
        File exe = makeExecutable(dir, "faketool");
        assertEquals(
                exe.getAbsolutePath(), CliSupport.resolveExecutable("faketool", dir.toString()));
    }

    @Test
    void resolvesViaFullPathOverride(@TempDir Path dir) throws IOException {
        File exe = makeExecutable(dir, "faketool");
        assertEquals(
                exe.getAbsolutePath(),
                CliSupport.resolveExecutable("faketool", exe.getAbsolutePath()));
    }

    @Test
    void returnsNullForUnknownExecutable() {
        assertNull(CliSupport.resolveExecutable("zzz-no-such-cli-9c2f1a", ""));
    }

    @Test
    void resolvesOpenCodeFromItsOfficialInstallerDirectory(@TempDir Path dir) throws IOException {
        File executable =
                makeExecutable(Files.createDirectories(dir.resolve(".opencode/bin")), "opencode");
        assertEquals(
                executable.getAbsolutePath(),
                CliSupport.resolveExecutable(
                        "opencode", "", dir.resolve("not-on-path").toString(), dir.toString()));
    }

    @Test
    void discoversBoundedUniqueModelIdsFromCli(@TempDir Path dir) throws IOException {
        File executable = dir.resolve("models-cli").toFile();
        Files.writeString(
                executable.toPath(),
                "#!/bin/sh\nprintf 'provider/a\\nprovider/b\\nprovider/a\\n'\n");
        assertTrue(executable.setExecutable(true));

        assertEquals(
                List.of("provider/a", "provider/b"),
                CliSupport.discoverModelIds("models-cli", executable.getAbsolutePath()));
    }

    @Test
    void discoversModelIdsFromTabularCliOutput(@TempDir Path dir) throws IOException {
        File executable = dir.resolve("models-cli").toFile();
        Files.writeString(
                executable.toPath(),
                "#!/bin/sh\n"
                    + "printf 'gemini-3.7-flash-high     Gemini 3.7 Flash (High)\\n'\n"
                    + "printf 'gemini-3.7-flash-medium   Gemini 3.7 Flash (Medium)\\n'\n"
                    + "printf 'claude-sonnet-4-6         Claude Sonnet 4.6 (Thinking)\\n'\n");
        assertTrue(executable.setExecutable(true));

        assertEquals(
                List.of("gemini-3.7-flash-high", "gemini-3.7-flash-medium", "claude-sonnet-4-6"),
                CliSupport.discoverModelIds("models-cli", executable.getAbsolutePath()));
    }

    @Test
    void modelDiscoveryClosesNonInteractiveInput(@TempDir Path dir) throws IOException {
        File executable = dir.resolve("models-cli").toFile();
        Files.writeString(
                executable.toPath(),
                "#!/bin/sh\ncat >/dev/null\nprintf 'gemini-3.6-flash-high\\n'\n");
        assertTrue(executable.setExecutable(true));

        assertEquals(
                List.of("gemini-3.6-flash-high"),
                CliSupport.discoverModelIds("models-cli", executable.getAbsolutePath(), 1000));
    }

    @Test
    void failedModelCommandContributesNothing(@TempDir Path dir) throws IOException {
        File executable = dir.resolve("models-cli").toFile();
        Files.writeString(executable.toPath(), "#!/bin/sh\necho provider/a\nexit 2\n");
        assertTrue(executable.setExecutable(true));

        assertTrue(
                CliSupport.discoverModelIds("models-cli", executable.getAbsolutePath()).isEmpty());
    }

    @Test
    void modelDiscoveryIgnoresStderrDiagnostics(@TempDir Path dir) throws IOException {
        File executable = dir.resolve("models-cli").toFile();
        Files.writeString(
                executable.toPath(),
                "#!/bin/sh\necho 'authentication warning' >&2\necho provider/a\n");
        assertTrue(executable.setExecutable(true));

        assertEquals(
                List.of("provider/a"),
                CliSupport.discoverModelIds("models-cli", executable.getAbsolutePath()));
    }

    @Test
    void oversizedModelOutputContributesNothing(@TempDir Path dir) throws IOException {
        File executable = dir.resolve("models-cli").toFile();
        Files.writeString(
                executable.toPath(),
                "#!/bin/sh\n"
                    + "dd if=/dev/zero bs=70000 count=1 2>/dev/null | tr '\\000' x\n"
                    + "printf '\\n"
                    + "'\n");
        assertTrue(executable.setExecutable(true));

        assertTrue(
                CliSupport.discoverModelIds("models-cli", executable.getAbsolutePath()).isEmpty());
    }

    @Test
    void timedOutModelDiscoveryTerminatesDescendants(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        File executable = dir.resolve("models-cli").toFile();
        Files.writeString(
                executable.toPath(),
                "#!/bin/sh\nsleep 20 &\n"
                        + "echo $! > "
                        + CliSupport.shellQuote(pidFile.toString())
                        + "\nwait\n");
        assertTrue(executable.setExecutable(true));

        assertTrue(
                CliSupport.discoverModelIds("models-cli", executable.getAbsolutePath(), 1000)
                        .isEmpty());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(pidFile), "the fixture must publish its child pid before timeout");
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true));
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
        assertTrue(
                script.contains("'hi there'"), "args must be single-quoted so spaces/JSON survive");
    }

    @Test
    void managedDirectoryAndFileAreOwnerOnly() throws IOException {
        Path directory = CliSupport.createOwnerOnlyTempDirectory("protege-mcp-test-");
        Path file = directory.resolve("config.json");
        CliSupport.writeOwnerOnlyFile(file, "{\"secret\":true}");

        assertEquals("{\"secret\":true}", Files.readString(file));
        try {
            Set<PosixFilePermission> directoryPermissions =
                    Files.getPosixFilePermissions(directory);
            Set<PosixFilePermission> filePermissions = Files.getPosixFilePermissions(file);
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    directoryPermissions);
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    filePermissions);
        } catch (UnsupportedOperationException ignored) {
            assertTrue(directory.toFile().canRead() && directory.toFile().canWrite());
            assertTrue(file.toFile().canRead() && file.toFile().canWrite());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void sharedJsonCompletionReportsOnlyMissingDiagnostics() {
        RecordingChatListener failed = new RecordingChatListener();
        CliSupport.finishJsonTurn("agy", 2, "boom", false, false, failed);
        assertEquals(1, failed.errors.size());
        assertTrue(failed.errors.get(0).contains("agy exited with code 2"));
        assertEquals(2, failed.exit);

        RecordingChatListener silent = new RecordingChatListener();
        CliSupport.finishJsonTurn("opencode", 0, "", false, false, silent);
        assertTrue(silent.errors.get(0).contains("without an answer"));

        RecordingChatListener alreadyExplained = new RecordingChatListener();
        CliSupport.finishJsonTurn("opencode", 1, "duplicate", true, false, alreadyExplained);
        assertTrue(alreadyExplained.errors.isEmpty());
        assertEquals(1, alreadyExplained.exit);
    }

    private static File makeExecutable(Path dir, String name) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(f.setExecutable(true), "could not mark temp file executable");
        return f;
    }
}
