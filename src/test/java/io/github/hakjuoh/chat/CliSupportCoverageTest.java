package io.github.hakjuoh.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Supplementary coverage for {@link CliSupport} not already exercised by {@code CliSupportTest}:
 * the override fall-through / null-blank branches of {@code resolveExecutable}, the full behaviour of
 * {@code describeFailure} (truncation, null/blank stderr), {@code shellQuote} metacharacter safety,
 * {@code loginShellWrap} shell-selection and structure, {@code neutralWorkingDir}, and the process
 * helpers {@code probeVersion}/{@code spawn} driven through real POSIX shells. Process tests are
 * skipped on non-POSIX platforms via {@link Assumptions}.
 */
class CliSupportCoverageTest {

    private static boolean posix() {
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static File makeExecutable(Path dir, String name, String body) throws IOException {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), body);
        assertTrue(f.setExecutable(true), "could not mark temp file executable");
        return f;
    }

    // ---------------------------------------------------------------- resolveExecutable

    @Test
    void resolveExecutableNullOverrideReturnsNullWhenNotFoundAnywhere() {
        assertNull(CliSupport.resolveExecutable("zzz-no-such-cli-abc123-xyz", null),
                "null override with a bogus name should search PATH/fallbacks and find nothing");
    }

    @Test
    void resolveExecutableBlankOverrideSearchesFallbacks() {
        assertNull(CliSupport.resolveExecutable("zzz-no-such-cli-blankovr-777", "   "),
                "blank override is treated as absent");
    }

    @Test
    void resolveExecutableNonExecutableFullPathOverrideIsNotReturned(@TempDir Path dir) throws IOException {
        File f = dir.resolve("plainfile").toFile();
        Files.writeString(f.toPath(), "not executable");
        // Ensure it is NOT executable so the override direct-file branch is skipped.
        f.setExecutable(false);
        Assumptions.assumeFalse(f.canExecute(), "filesystem does not honour setExecutable(false)");
        String r = CliSupport.resolveExecutable("plainfile", f.getAbsolutePath());
        assertNull(r, "a non-executable file passed as override must not be returned");
    }

    @Test
    void resolveExecutableDirectoryOverrideWithoutExecutableFallsThrough(@TempDir Path dir) {
        // Directory exists but contains no such executable -> falls through to PATH; name is bogus -> null.
        String r = CliSupport.resolveExecutable("zzz-no-such-cli-dir-555", dir.toString());
        assertNull(r, "directory override lacking the executable falls through to PATH search");
    }

    @Test
    void resolveExecutableDirectoryOverrideBeatsPath(@TempDir Path dir) throws IOException {
        File exe = makeExecutable(dir, "faketool2", "#!/bin/sh\necho hi\n");
        Assumptions.assumeTrue(exe.canExecute(), "need an executable temp file");
        assertEquals(exe.getAbsolutePath(),
                CliSupport.resolveExecutable("faketool2", dir.toString()),
                "executable inside the override directory should be resolved");
    }

    // ---------------------------------------------------------------- describeFailure

    @Test
    void describeFailureNullStderrOmitsColon() {
        String msg = CliSupport.describeFailure("claude", 1, null);
        assertEquals("claude exited with code 1.", msg);
        assertFalse(msg.contains(":"), "no stderr -> no ': ' separator");
    }

    @Test
    void describeFailureEmptyStderrOmitsColon() {
        assertEquals("codex exited with code 3.", CliSupport.describeFailure("codex", 3, ""));
    }

    @Test
    void describeFailureWhitespaceStderrTreatedAsEmpty() {
        assertEquals("cli exited with code 7.", CliSupport.describeFailure("cli", 7, "   \n\t  "));
    }

    @Test
    void describeFailureNegativeExitIncluded() {
        String msg = CliSupport.describeFailure("cli", -1, "");
        assertTrue(msg.contains("-1"), "exit code -1 should appear in the message");
    }

    @Test
    void describeFailureNameWithSpacesPreserved() {
        String msg = CliSupport.describeFailure("my cli", 2, "boom");
        assertTrue(msg.startsWith("my cli exited with code 2: "), msg);
        assertTrue(msg.endsWith("boom"), msg);
    }

    @Test
    void describeFailureExactly600CharsIncludedFully() {
        String stderr = "x".repeat(600);
        String msg = CliSupport.describeFailure("cli", 1, stderr);
        assertTrue(msg.contains(stderr), "600-char stderr must be included verbatim (no truncation)");
        assertFalse(msg.contains("…"), "600 chars is at the boundary and not truncated");
    }

    @Test
    void describeFailure601CharsTruncatedToLast600WithEllipsis() {
        // 601 distinct-boundary content: leading marker char then 600 body chars.
        String stderr = "H" + "y".repeat(600);
        String msg = CliSupport.describeFailure("cli", 1, stderr);
        assertTrue(msg.contains("…"), "over-600 stderr must be ellipsis-truncated");
        assertFalse(msg.contains("Hy"), "the leading char must be dropped by tail truncation");
        String expectedTail = "…" + "y".repeat(600);
        assertTrue(msg.endsWith(expectedTail), "should keep the last 600 chars prefixed by ellipsis");
    }

    @Test
    void describeFailureTrimsStderrBeforeMeasuring() {
        String msg = CliSupport.describeFailure("cli", 1, "  hello  ");
        assertEquals("cli exited with code 1: hello", msg, "stderr is trimmed before appending");
    }

    // ---------------------------------------------------------------- shellQuote

    @Test
    void shellQuoteEmptyString() {
        assertEquals("''", CliSupport.shellQuote(""));
    }

    @Test
    void shellQuoteMultipleSingleQuotesEachEscaped() {
        assertEquals("'a'\\''b'\\''c'", CliSupport.shellQuote("a'b'c"));
    }

    @Test
    void shellQuoteLeadingAndTrailingSingleQuote() {
        assertEquals("''\\''x'\\'''", CliSupport.shellQuote("'x'"));
    }

    @Test
    void shellQuoteMetacharactersUnchangedInsideQuotes() {
        assertEquals("'$HOME `id` \\ && | ;'", CliSupport.shellQuote("$HOME `id` \\ && | ;"));
    }

    @Test
    void shellQuoteMultilinePreserved() {
        assertEquals("'line1\nline2'", CliSupport.shellQuote("line1\nline2"));
    }

    @Test
    void shellQuoteDoubleQuotesAndBracesPreserved() {
        assertEquals("'{\"a\": [1,2]}'", CliSupport.shellQuote("{\"a\": [1,2]}"));
    }

    // ---------------------------------------------------------------- loginShellWrap

    @Test
    void loginShellWrapStructureThreeElementsExecPrefix() {
        Assumptions.assumeTrue(posix(), "POSIX login-shell wrapping only");
        List<String> wrapped = CliSupport.loginShellWrap(List.of("tool", "--flag", "val ue"));
        assertEquals(3, wrapped.size(), "wrapped form is [shell, -lc, script]");
        assertEquals("-lc", wrapped.get(1));
        String script = wrapped.get(2);
        assertTrue(script.startsWith("exec "), "script must exec in-place: " + script);
        assertTrue(script.contains("'tool'"), script);
        assertTrue(script.contains("'--flag'"), script);
        assertTrue(script.contains("'val ue'"), script);
        // args must appear in order.
        int iTool = script.indexOf("'tool'");
        int iFlag = script.indexOf("'--flag'");
        int iVal = script.indexOf("'val ue'");
        assertTrue(iTool < iFlag && iFlag < iVal, "quoted args must appear in original order");
    }

    @Test
    void loginShellWrapShellIsExecutablePath() {
        Assumptions.assumeTrue(posix(), "POSIX login-shell wrapping only");
        List<String> wrapped = CliSupport.loginShellWrap(List.of("x"));
        File shell = new File(wrapped.get(0));
        assertTrue(shell.canExecute(), "selected shell must be an executable file: " + wrapped.get(0));
    }

    @Test
    void loginShellWrapEscapesEmbeddedSingleQuotes() {
        Assumptions.assumeTrue(posix(), "POSIX login-shell wrapping only");
        List<String> wrapped = CliSupport.loginShellWrap(List.of("echo", "a'b"));
        assertTrue(wrapped.get(2).contains("'a'\\''b'"), "embedded quote must be POSIX-escaped: " + wrapped.get(2));
    }

    @Test
    void loginShellWrapPreservesJsonArgument() {
        Assumptions.assumeTrue(posix(), "POSIX login-shell wrapping only");
        String json = "{\"prompt\":\"hi\"}";
        List<String> wrapped = CliSupport.loginShellWrap(List.of("cli", json));
        assertTrue(wrapped.get(2).contains("'" + json + "'"), "JSON arg preserved intact in single quotes");
    }

    // ---------------------------------------------------------------- neutralWorkingDir

    @Test
    void neutralWorkingDirReturnsExistingDirectoryUnderTmp() {
        File d = CliSupport.neutralWorkingDir();
        assertNotNull(d);
        assertTrue(d.isDirectory(), "neutral working dir should exist as a directory");
        assertTrue(d.getName().equals("protege-mcp-chat")
                        || d.equals(new File(System.getProperty("user.home", "."))),
                "expected the tmp scratch dir or the user.home fallback: " + d);
    }

    @Test
    void neutralWorkingDirIsIdempotent() {
        File a = CliSupport.neutralWorkingDir();
        File b = CliSupport.neutralWorkingDir();
        assertEquals(a.getAbsolutePath(), b.getAbsolutePath(), "subsequent calls return the same directory");
    }

    // ---------------------------------------------------------------- probeVersion

    @Test
    void probeVersionNullExeReturnsNull() {
        assertNull(CliSupport.probeVersion(null));
    }

    @Test
    void probeVersionNonExistentPathReturnsNull() {
        assertNull(CliSupport.probeVersion("/no/such/path/zzz-cli-does-not-exist-42"),
                "IOException on spawn should surface as null");
    }

    @Test
    void probeVersionReturnsFirstStdoutLine(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "verprint",
                "#!/bin/sh\necho 'v1.2.3'\necho 'second line'\n");
        Assumptions.assumeTrue(exe.canExecute());
        assertEquals("v1.2.3", CliSupport.probeVersion(exe.getAbsolutePath()),
                "only the first stdout line is returned");
    }

    @Test
    void probeVersionEmptyOutputReturnsNull(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "verquiet", "#!/bin/sh\nexit 0\n");
        Assumptions.assumeTrue(exe.canExecute());
        assertNull(CliSupport.probeVersion(exe.getAbsolutePath()),
                "no stdout -> readLine null -> return null");
    }

    @Test
    void probeVersionNonZeroExitStillReturnsStdoutFirstLine(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "verfail", "#!/bin/sh\necho 'partial'\nexit 5\n");
        Assumptions.assumeTrue(exe.canExecute());
        assertEquals("partial", CliSupport.probeVersion(exe.getAbsolutePath()),
                "exit code is ignored; first stdout line returned");
    }

    @Test
    void probeVersionMergesStderrIntoStdout(@TempDir Path dir) throws IOException {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        // redirectErrorStream(true): a line written only to stderr is readable as the first line.
        File exe = makeExecutable(dir, "verstderr", "#!/bin/sh\necho 'err-version' 1>&2\n");
        Assumptions.assumeTrue(exe.canExecute());
        assertEquals("err-version", CliSupport.probeVersion(exe.getAbsolutePath()),
                "stderr is merged into stdout so its line is read");
    }

    // ---------------------------------------------------------------- spawn

    @Test
    void spawnStreamsStdoutLinesAndCallsCompletion(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "streamer", "#!/bin/sh\necho one\necho two\nexit 0\n");
        Assumptions.assumeTrue(exe.canExecute());

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        AtomicReference<String> stderrRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        ChatProcess cp = CliSupport.spawn(List.of(exe.getAbsolutePath()), null, dir.toFile(),
                lines::add,
                (code, err) -> {
                    exitCode.set(code);
                    stderrRef.set(err);
                    done.countDown();
                });
        assertNotNull(cp);
        assertTrue(done.await(15, TimeUnit.SECONDS), "completion handler must fire");
        assertEquals(List.of("one", "two"), lines, "each stdout line delivered without newline, in order");
        assertEquals(0, exitCode.get(), "exit code propagated to completion handler");
        assertNotNull(stderrRef.get(), "stderr buffer (possibly empty) passed to completion handler");
        assertTrue(stderrRef.get().isEmpty(), "no stderr produced");
    }

    @Test
    void spawnDeliversEmptyLineForBlankStdoutLine(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "blankline", "#!/bin/sh\necho ''\necho after\n");
        Assumptions.assumeTrue(exe.canExecute());

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        CliSupport.spawn(List.of(exe.getAbsolutePath()), null, dir.toFile(),
                lines::add, (c, e) -> done.countDown());
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertEquals(List.of("", "after"), lines, "blank line becomes empty string");
    }

    @Test
    void spawnCapturesStderrIntoCompletionBuffer(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "errmaker",
                "#!/bin/sh\necho 'the error text' 1>&2\nexit 4\n");
        Assumptions.assumeTrue(exe.canExecute());

        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        AtomicReference<String> stderrRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        CliSupport.spawn(List.of(exe.getAbsolutePath()), null, dir.toFile(),
                l -> { }, (code, err) -> { exitCode.set(code); stderrRef.set(err); done.countDown(); });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertEquals(4, exitCode.get());
        assertTrue(stderrRef.get().contains("the error text"), "stderr drained into buffer: " + stderrRef.get());
    }

    @Test
    void spawnLineHandlerExceptionDoesNotKillStream(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "threelines",
                "#!/bin/sh\necho a\necho b\necho c\n");
        Assumptions.assumeTrue(exe.canExecute());

        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        CliSupport.spawn(List.of(exe.getAbsolutePath()), null, dir.toFile(),
                line -> {
                    seen.add(line);
                    if ("b".equals(line)) {
                        throw new RuntimeException("boom on line b");
                    }
                },
                (c, e) -> done.countDown());
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertEquals(List.of("a", "b", "c"), seen,
                "a handler exception on one line must not stop subsequent lines");
    }

    @Test
    void spawnMergesExtraEnvIntoChildEnvironment(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "envecho", "#!/bin/sh\necho \"$MY_TEST_VAR\"\n");
        Assumptions.assumeTrue(exe.canExecute());

        Map<String, String> extra = new HashMap<>();
        extra.put("MY_TEST_VAR", "hello-env-42");
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        CliSupport.spawn(List.of(exe.getAbsolutePath()), extra, dir.toFile(),
                lines::add, (c, e) -> done.countDown());
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertEquals(List.of("hello-env-42"), lines, "extraEnv must be visible to the child");
    }

    @Test
    void spawnNullWorkingDirUsesInheritedDirectory(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "pwdtool", "#!/bin/sh\necho done\n");
        Assumptions.assumeTrue(exe.canExecute());
        CountDownLatch done = new CountDownLatch(1);
        // Passing a null workingDir must not throw and must still run to completion.
        ChatProcess cp = CliSupport.spawn(List.of(exe.getAbsolutePath()), null, null,
                l -> { }, (c, e) -> done.countDown());
        assertNotNull(cp);
        assertTrue(done.await(15, TimeUnit.SECONDS), "null workingDir still runs the process to completion");
    }

    @Test
    void spawnedProcessIsAliveReflectsAndCancelStops(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        // Long sleeper so we can observe isAlive()==true, then cancel to force exit.
        File exe = makeExecutable(dir, "sleeper", "#!/bin/sh\nsleep 30\n");
        Assumptions.assumeTrue(exe.canExecute());

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        ChatProcess cp = CliSupport.spawn(List.of(exe.getAbsolutePath()), null, dir.toFile(),
                l -> { }, (c, e) -> { completed.set(true); done.countDown(); });
        assertTrue(cp.isAlive(), "sleeping child should report alive");
        cp.cancel();
        assertTrue(done.await(15, TimeUnit.SECONDS), "cancel must lead the completion handler to fire");
        assertTrue(completed.get());
        // Give the OS a beat; then it should be dead.
        for (int i = 0; i < 50 && cp.isAlive(); i++) {
            Thread.sleep(50);
        }
        assertFalse(cp.isAlive(), "cancelled process should no longer be alive");
    }

    @Test
    void chatProcessCancelIsIdempotent(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "sleeper2", "#!/bin/sh\nsleep 30\n");
        Assumptions.assumeTrue(exe.canExecute());
        CountDownLatch done = new CountDownLatch(1);
        ChatProcess cp = CliSupport.spawn(List.of(exe.getAbsolutePath()), null, dir.toFile(),
                l -> { }, (c, e) -> done.countDown());
        cp.cancel();
        cp.cancel(); // second call is a no-op and must not throw
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }

    @Test
    void spawnReturnsSameProcessHandleType(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(posix(), "needs a POSIX shell script");
        File exe = makeExecutable(dir, "quick", "#!/bin/sh\nexit 0\n");
        Assumptions.assumeTrue(exe.canExecute());
        CountDownLatch done = new CountDownLatch(1);
        ChatProcess cp = CliSupport.spawn(List.of(exe.getAbsolutePath()), null, dir.toFile(),
                l -> { }, (c, e) -> done.countDown());
        assertSame(ChatProcess.class, cp.getClass(), "spawn returns a ChatProcess handle");
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }

    // ---------------------------------------------------------------- writeOwnerOnlyTempFile

    @Test
    void writeOwnerOnlyTempFileWritesContentAndRestrictsToOwner() throws Exception {
        String content = "{\"secret\":\"do-not-leak\"}";
        File f = CliSupport.writeOwnerOnlyTempFile("protege-mcp-test-", ".json", content);
        try {
            assertTrue(f.isFile());
            assertEquals(content, Files.readString(f.toPath()));
            if (posix()) {
                // Owner-only: no group/other read or write — this is why passing the PATH keeps the
                // secret from other local users, unlike putting it on the argv.
                assertEquals("rw-------",
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(f.toPath())));
            }
        } finally {
            Files.deleteIfExists(f.toPath());
        }
    }
}
