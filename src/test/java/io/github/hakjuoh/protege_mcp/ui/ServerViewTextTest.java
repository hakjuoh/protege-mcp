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
}
