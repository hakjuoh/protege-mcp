package io.github.hakjuoh.protege_mcp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the pure text/format helpers in {@link ServerViewText}. This test lives in
 * the same package to reach the package-private class and its static members.
 */
class ServerViewTextTest {

    private static final String SIX_BULLETS = "••••••";

    @Test
    void maskNullReturnsSixBullets() {
        assertEquals(SIX_BULLETS, ServerViewText.mask(null));
        assertEquals(6, ServerViewText.mask(null).length());
    }

    @Test
    void maskEmptyReturnsSixBullets() {
        assertEquals(SIX_BULLETS, ServerViewText.mask(""));
    }

    @Test
    void maskTokenOfLengthSixReturnsSixBullets() {
        // length == 6 is the boundary: still fully masked, no first/last-3 reveal.
        String token = "abcdef";
        assertEquals(6, token.length());
        assertEquals(SIX_BULLETS, ServerViewText.mask(token));
    }

    @Test
    void maskShortTokenReturnsSixBullets() {
        assertEquals(SIX_BULLETS, ServerViewText.mask("ab"));
        assertEquals(SIX_BULLETS, ServerViewText.mask("abcde"));
    }

    @Test
    void maskLongTokenRevealsFirstAndLastThree() {
        assertEquals("abc" + SIX_BULLETS + "hij", ServerViewText.mask("abcdefghij"));
    }

    @Test
    void maskLongTokenMiddleIsAlwaysSixBullets() {
        // Regardless of overall token length (>6), the masked middle is exactly six bullets.
        String masked = ServerViewText.mask("0123456789abcdefghijABCDEFGHIJ");
        assertEquals("012" + SIX_BULLETS + "HIJ", masked);
        String middle = masked.substring(3, masked.length() - 3);
        assertEquals(SIX_BULLETS, middle);
        assertEquals(6, middle.length());
    }

    @Test
    void maskSevenCharTokenIsShortestRevealingCase() {
        // length 7 is the first length that exceeds 6 and thus reveals ends.
        assertEquals("abc" + SIX_BULLETS + "efg", ServerViewText.mask("abcdefg"));
    }

    @Test
    void tsFormatRendersExactPattern() {
        // Literal, timezone-agnostic check on a fixed local date-time: a change to the TS_FORMAT
        // pattern is caught here (a round-trip that reads TS_FORMAT on both sides could not).
        assertEquals("2020-09-13 12:26:40",
                LocalDateTime.of(2020, 9, 13, 12, 26, 40).format(ServerViewText.TS_FORMAT));
    }

    @Test
    void fmtDateTimeProducesNineteenCharPattern() {
        // yyyy-MM-dd HH:mm:ss is exactly 19 chars; timezone-agnostic length check.
        String formatted = ServerViewText.fmtDateTime(1_600_000_000_000L);
        assertEquals(19, formatted.length());
    }

    @Test
    void fmtDateTimeHasSeparatorsAtExpectedPositions() {
        String formatted = ServerViewText.fmtDateTime(1_600_000_000_000L);
        // yyyy-MM-dd HH:mm:ss
        assertEquals('-', formatted.charAt(4));
        assertEquals('-', formatted.charAt(7));
        assertEquals(' ', formatted.charAt(10));
        assertEquals(':', formatted.charAt(13));
        assertEquals(':', formatted.charAt(16));
    }

    @Test
    void fmtDateTimeDigitPositionsAreDigits() {
        String formatted = ServerViewText.fmtDateTime(1_600_000_000_000L);
        int[] digitPositions = {0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18};
        for (int pos : digitPositions) {
            assertTrue(Character.isDigit(formatted.charAt(pos)),
                    "expected digit at position " + pos + " in \"" + formatted + "\"");
        }
    }

    @Test
    void tsFormatRendersExactPatternAtEpoch() {
        // A second fixed value so the exact pattern is pinned independently of any single date.
        assertEquals("1970-01-01 00:00:00",
                LocalDateTime.of(1970, 1, 1, 0, 0, 0).format(ServerViewText.TS_FORMAT));
    }

    @Test
    void connectCommandBuildsFullCommand() {
        assertEquals("claude mcp add --transport http protege http://localhost:8123/mcp",
                ServerViewText.connectCommand("http://localhost:8123/mcp"));
    }

    @Test
    void connectCommandAlwaysHasFixedPrefix() {
        String prefix = "claude mcp add --transport http protege ";
        assertTrue(ServerViewText.connectCommand("http://example/mcp").startsWith(prefix));
        assertTrue(ServerViewText.connectCommand("").startsWith(prefix));
    }

    @Test
    void connectCommandAppendsUrlVerbatim() {
        String prefix = "claude mcp add --transport http protege ";
        String url = "https://host:9999/some/weird path?q=1&x=2";
        String cmd = ServerViewText.connectCommand(url);
        assertEquals(prefix + url, cmd);
        assertEquals(url, cmd.substring(prefix.length()));
    }

    // ---- statusLine -----------------------------------------------------------------------------

    @Test
    void statusLineRunningIsPlainRegardlessOfLatchAndError() {
        // While running, neither the latch nor a previous error belongs in the status: a latch seen
        // alongside running is the transient Stop-in-flight shape (latched first, stop about to
        // land), and lastError describes why the server is NOT running.
        assertEquals("MCP server: RUNNING", ServerViewText.statusLine(true, false, null));
        assertEquals("MCP server: RUNNING", ServerViewText.statusLine(true, true, "old failure"));
    }

    @Test
    void statusLineStoppedIsPlainWithoutLatchOrError() {
        assertEquals("MCP server: stopped", ServerViewText.statusLine(false, false, null));
    }

    @Test
    void statusLineUserStoppedNamesTheLatchAndTheWayBack() {
        String line = ServerViewText.statusLine(false, true, null);
        assertTrue(line.contains("stopped by Stop"), "must attribute the stop to the user: " + line);
        assertTrue(line.contains("Ontology Assistant will not restart it"),
                "must say the chat respects the stop: " + line);
        assertTrue(line.contains("press Start"), "must point at the way back: " + line);
    }

    @Test
    void statusLineStoppedAppendsLastError() {
        assertEquals("MCP server: stopped  (last error: bind failed)",
                ServerViewText.statusLine(false, false, "bind failed"));
    }

    @Test
    void statusLineUserStoppedAndErrorShowsBoth() {
        // Rare shape (Start cleared the latch is the normal path), but nothing may be swallowed.
        String line = ServerViewText.statusLine(false, true, "bind failed");
        assertTrue(line.contains("stopped by Stop"), line);
        assertTrue(line.contains("(last error: bind failed)"), line);
    }

    // ---- connectionLine -------------------------------------------------------------------------

    private static final String DIRECT = "http://127.0.0.1:54321/mcp";

    @Test
    void connectionLineEmptyWhileStopped() {
        assertEquals("", ServerViewText.connectionLine(false, true, "http://x/mcp", true,
                DIRECT, false, 8123));
        assertEquals("", ServerViewText.connectionLine(false, false, null, false, DIRECT, true, 8123));
    }

    @Test
    void connectionLineBrokerConnectedNamesTheDirectEndpoint() {
        // In broker mode the Endpoint URL field shows the broker's URL, so this line is the only
        // place the window's own (direct) URL is visible.
        assertEquals("Shared broker: connected — this window direct: " + DIRECT,
                ServerViewText.connectionLine(true, true, "http://127.0.0.1:8123/mcp", true,
                        DIRECT, false, 0));
    }

    @Test
    void connectionLineBrokerUnreachableSaysReconnectingAndStillServing() {
        String line = ServerViewText.connectionLine(true, true, null, true, DIRECT, false, 0);
        assertTrue(line.contains("UNREACHABLE"), "the outage must be unmissable: " + line);
        assertTrue(line.contains("reconnecting automatically"),
                "must say the self-healing needs no user action: " + line);
        assertTrue(line.contains(DIRECT), "must say this window keeps serving directly: " + line);
    }

    @Test
    void connectionLineStandaloneWhenBrokerPreferredNamesTheDetachment() {
        assertEquals("Standalone (not attached to the shared broker)",
                ServerViewText.connectionLine(true, false, null, true, DIRECT, false, 8123));
    }

    @Test
    void connectionLineStandaloneWhenBrokerNotPreferredIsPlain() {
        assertEquals("Standalone",
                ServerViewText.connectionLine(true, false, null, false, DIRECT, false, 8123));
    }

    @Test
    void connectionLineStandaloneNamesThePortFallback() {
        String line = ServerViewText.connectionLine(true, false, null, false, DIRECT, true, 8123);
        assertEquals("Standalone — configured port 8123 was busy; bound an ephemeral port instead",
                line);
    }

    @Test
    void connectionLineStandaloneBrokerPreferredWithPortFallbackShowsBoth() {
        String line = ServerViewText.connectionLine(true, false, null, true, DIRECT, true, 8123);
        assertTrue(line.contains("not attached to the shared broker"), line);
        assertTrue(line.contains("configured port 8123 was busy"), line);
    }

    @Test
    void connectionLineBrokerModeIgnoresThePortFallbackFlag() {
        // A broker backend is ephemeral by design (configuredPort 0), so a fallback note would be
        // noise even if the flag were ever set in that mode.
        String line = ServerViewText.connectionLine(true, true, "http://127.0.0.1:8123/mcp", true,
                DIRECT, true, 8123);
        assertEquals("Shared broker: connected — this window direct: " + DIRECT, line);
    }

    @Test
    void connectionLineBrokerUrlIrrelevantInStandaloneMode() {
        // brokerBaseUrl is process-wide state: another window may be attached while this one runs
        // standalone — the line must describe THIS window's mode only.
        assertEquals("Standalone",
                ServerViewText.connectionLine(true, false, "http://127.0.0.1:8123/mcp", false,
                        DIRECT, false, 8123));
    }
}
