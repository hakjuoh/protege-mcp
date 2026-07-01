package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the package-private command-wrapping seams of {@link CliSupport}:
 * {@link CliSupport#resolveLoginShell(String)}, {@link CliSupport#loginShellWrap(List, String, String)}
 * and {@link CliSupport#shellQuote(String)}. These are pure string/File-probe helpers with no Swing or
 * Protégé dependency, so every branch is reachable off the EDT. The parametrized {@code loginShellWrap}
 * overload lets us pin {@code os.name} / {@code $SHELL} without touching the real environment.
 */
class CliSupportSeamTest {

    // ---- resolveLoginShell --------------------------------------------------

    @Test
    void resolveLoginShellReturnsAValidExecutablePathAsIs() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        assertEquals("/bin/sh", CliSupport.resolveLoginShell("/bin/sh"));
    }

    @Test
    void resolveLoginShellNullFallsBackToBashWhenPresentElseSh() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        String resolved = CliSupport.resolveLoginShell(null);
        String expected = new File("/bin/bash").canExecute() ? "/bin/bash" : "/bin/sh";
        assertEquals(expected, resolved);
    }

    @Test
    void resolveLoginShellBlankFallsBackToBashWhenPresentElseSh() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        String resolved = CliSupport.resolveLoginShell("   ");
        String expected = new File("/bin/bash").canExecute() ? "/bin/bash" : "/bin/sh";
        assertEquals(expected, resolved);
    }

    @Test
    void resolveLoginShellNonExecutablePathFallsBackToBashWhenPresentElseSh() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        String resolved = CliSupport.resolveLoginShell("/definitely/not/a/real/shell/xyzzy");
        String expected = new File("/bin/bash").canExecute() ? "/bin/bash" : "/bin/sh";
        assertEquals(expected, resolved);
    }

    @Test
    void resolveLoginShellAlwaysReturnsAnExecutableOnPosix() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        // Whatever the input, the result must be a runnable shell path (bash or sh).
        assertTrue(new File(CliSupport.resolveLoginShell(null)).canExecute());
        assertTrue(new File(CliSupport.resolveLoginShell("")).canExecute());
    }

    // ---- loginShellWrap: Windows branch -------------------------------------

    @Test
    void loginShellWrapReturnsCommandUnchangedOnWindows() {
        List<String> cmd = List.of("claude", "--print", "hi there");
        List<String> wrapped = CliSupport.loginShellWrap(cmd, "Windows 11", "C:\\Windows\\System32\\cmd.exe");
        assertSame(cmd, wrapped, "Windows branch must return the exact same list instance unwrapped");
    }

    @Test
    void loginShellWrapWindowsMatchIsCaseInsensitive() {
        List<String> cmd = List.of("codex", "exec");
        // "win" appears case-insensitively inside "WINDOWS".
        assertSame(cmd, CliSupport.loginShellWrap(cmd, "WINDOWS", null));
        assertSame(cmd, CliSupport.loginShellWrap(cmd, "Some Windows Server", null));
    }

    // ---- loginShellWrap: POSIX branch ---------------------------------------

    @Test
    void loginShellWrapPosixProducesThreeElementShellInvocation() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        List<String> wrapped = CliSupport.loginShellWrap(List.of("claude", "--print"), "Mac OS X", "/bin/sh");
        assertEquals(3, wrapped.size());
        assertEquals("/bin/sh", wrapped.get(0));
        assertEquals("-lc", wrapped.get(1));
        assertTrue(wrapped.get(2).startsWith("exec "), "script must start with 'exec '");
    }

    @Test
    void loginShellWrapPosixShellElementMatchesResolveLoginShell() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        // With a null $SHELL the wrapper must use the same fallback resolveLoginShell computes.
        List<String> wrapped = CliSupport.loginShellWrap(List.of("claude"), "Linux", null);
        assertEquals(CliSupport.resolveLoginShell(null), wrapped.get(0));
    }

    @Test
    void loginShellWrapPosixQuotesEachArgIntoTheExecScript() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        List<String> wrapped = CliSupport.loginShellWrap(List.of("claude", "--model", "opus"), "Mac OS X", "/bin/sh");
        String script = wrapped.get(2);
        assertEquals("exec 'claude' '--model' 'opus'", script);
    }

    @Test
    void loginShellWrapPosixQuotesArgWithSpaces() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        List<String> wrapped = CliSupport.loginShellWrap(List.of("claude", "hello world"), "Mac OS X", "/bin/sh");
        String script = wrapped.get(2);
        assertTrue(script.contains("'hello world'"),
                "space-containing arg must be single-quoted intact: " + script);
    }

    @Test
    void loginShellWrapPosixEmptyCommandStillWrapsAsBareExec() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        List<String> wrapped = CliSupport.loginShellWrap(List.of(), "Mac OS X", "/bin/sh");
        assertEquals(3, wrapped.size());
        assertEquals("exec", wrapped.get(2), "no args means the script is just 'exec'");
    }

    // ---- shellQuote ---------------------------------------------------------

    @Test
    void shellQuoteWrapsSimpleStringInSingleQuotes() {
        assertEquals("'claude'", CliSupport.shellQuote("claude"));
    }

    @Test
    void shellQuoteWrapsSpacesWithoutEscaping() {
        assertEquals("'hello world'", CliSupport.shellQuote("hello world"));
    }

    @Test
    void shellQuoteEscapesEmbeddedSingleQuote() {
        // "a'b" -> 'a'\''b'
        assertEquals("'a'\\''b'", CliSupport.shellQuote("a'b"));
    }

    @Test
    void shellQuoteEscapesMultipleSingleQuotes() {
        // "''" -> ''\'''\'''
        assertEquals("''\\'''\\'''", CliSupport.shellQuote("''"));
    }

    @Test
    void shellQuoteEmptyStringBecomesEmptyQuotes() {
        assertEquals("''", CliSupport.shellQuote(""));
    }

    // ---- round-trip ---------------------------------------------------------

    @Test
    void argWithSpaceAndQuoteSurvivesWrappingAsSingleQuotedToken() {
        Assumptions.assumeTrue(new File("/bin/sh").canExecute());
        String tricky = "say 'hi' now";
        List<String> wrapped = CliSupport.loginShellWrap(List.of("claude", tricky), "Mac OS X", "/bin/sh");
        String script = wrapped.get(2);
        // The quoted form of the tricky arg must appear verbatim inside the exec script.
        String quoted = CliSupport.shellQuote(tricky);
        assertNotNull(quoted);
        assertTrue(script.contains(quoted),
                "wrapped script must contain the exact quoted arg\nexpected substring: " + quoted
                        + "\nscript: " + script);
        // And the script is a single 'exec ...' line (no unescaped break-out).
        assertTrue(script.startsWith("exec 'claude' "), script);
    }
}
