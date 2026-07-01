package io.github.hakjuoh.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link McpServerView}.
 *
 * <p>{@code McpServerView} extends Protégé's {@code AbstractOWLViewComponent} (a Swing component)
 * and every instance method touches Swing fields, so instances cannot be constructed headless
 * without {@link java.awt.HeadlessException}. This test therefore exercises the pure, side-effect
 * free private {@code static} helpers ({@code mask}, {@code fmtDateTime}) and the class-level
 * constants ({@code CLIENT_COLUMNS}, {@code COL_CLIENT_ID}, {@code TS_FORMAT}) via reflection.
 * These are the only parts of the class that are deterministic and safe to run without an AWT
 * display / OSGi runtime; the Swing-lifecycle and controller-interaction methods are runtime-only.
 */
class McpServerViewTest {

    /** The six-bullet mask literal used by {@code McpServerView.mask}. */
    private static final String BULLETS = "••••••";

    // ---- reflection helpers -------------------------------------------------

    private static String invokeMask(String token) throws Exception {
        Method m = McpServerView.class.getDeclaredMethod("mask", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, token);
    }

    private static String invokeFmtDateTime(long millis) throws Exception {
        Method m = McpServerView.class.getDeclaredMethod("fmtDateTime", long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, millis);
    }

    private static Object staticField(String name) throws Exception {
        Field f = McpServerView.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /** Expected rendering of an epoch-millis value using the class's own zone + pattern. */
    private static String expectedFormat(long millis) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .toLocalDateTime().format(fmt);
    }

    // ---- mask() -------------------------------------------------------------

    @Test
    void maskNullReturnsSixBullets() throws Exception {
        assertEquals(BULLETS, invokeMask(null), "null token should mask to six bullets");
    }

    @Test
    void maskEmptyReturnsSixBullets() throws Exception {
        assertEquals(BULLETS, invokeMask(""), "empty token (length 0 <= 6) masks to six bullets");
    }

    @Test
    void maskThreeCharReturnsSixBullets() throws Exception {
        assertEquals(BULLETS, invokeMask("123"), "3-char token (<= 6) masks to six bullets");
    }

    @Test
    void maskExactlySixCharReturnsSixBullets() throws Exception {
        assertEquals(BULLETS, invokeMask("123456"),
                "6-char token is the boundary (length <= 6) and masks to six bullets");
    }

    @Test
    void maskSevenCharShowsFirstThreeAndLastThree() throws Exception {
        assertEquals("123" + BULLETS + "567", invokeMask("1234567"),
                "7-char token is the first length > 6 and keeps first 3 + last 3");
    }

    @Test
    void maskTenCharShowsFirstThreeAndLastThree() throws Exception {
        assertEquals("012" + BULLETS + "789", invokeMask("0123456789"),
                "10-char token keeps first 3 + last 3 around the bullets");
    }

    @Test
    void maskLongTokenKeepsOnlyEndpoints() throws Exception {
        String token = "abcdefghijklmnopqrstuvwxyz";
        assertEquals("abc" + BULLETS + "xyz", invokeMask(token),
                "long token exposes only first and last three characters");
    }

    // ---- fmtDateTime() ------------------------------------------------------

    @Test
    void fmtDateTimeEpochZeroMatchesSystemZoneRendering() throws Exception {
        assertEquals(expectedFormat(0L), invokeFmtDateTime(0L),
                "epoch 0 must render via system zone + yyyy-MM-dd HH:mm:ss pattern");
    }

    @Test
    void fmtDateTimeNormalTimestampMatchesSystemZoneRendering() throws Exception {
        long millis = 1_000_000_000_000L;
        assertEquals(expectedFormat(millis), invokeFmtDateTime(millis),
                "a normal epoch-millis value must render with the class pattern/zone");
    }

    @Test
    void fmtDateTimeAnotherTimestampMatchesSystemZoneRendering() throws Exception {
        long millis = 1_609_459_200_000L; // 2021-01-01T00:00:00Z
        assertEquals(expectedFormat(millis), invokeFmtDateTime(millis),
                "second distinct timestamp confirms consistent formatting");
    }

    @Test
    void fmtDateTimeMatchesExpectedPatternShape() throws Exception {
        String out = invokeFmtDateTime(1_000_000_000_000L);
        assertTrue(out.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "output must match 'yyyy-MM-dd HH:mm:ss' shape, was: " + out);
    }

    @Test
    void fmtDateTimeIsDeterministicAcrossCalls() throws Exception {
        long millis = 1_234_567_890_000L;
        assertEquals(invokeFmtDateTime(millis), invokeFmtDateTime(millis),
                "same millis must format identically across two calls");
    }

    // ---- constants ----------------------------------------------------------

    @Test
    void clientColumnsHaveExpectedHeaders() throws Exception {
        String[] cols = (String[]) staticField("CLIENT_COLUMNS");
        assertNotNull(cols, "CLIENT_COLUMNS must exist");
        assertEquals(6, cols.length, "there should be six client-table columns");
        assertEquals("Client", cols[0], "column 0 header");
        assertEquals("Client ID", cols[1], "column 1 header");
        assertEquals("Registered", cols[2], "column 2 header");
        assertEquals("Last seen", cols[3], "column 3 header");
        assertEquals("Active tokens", cols[4], "column 4 header");
        assertEquals("Expires", cols[5], "column 5 header");
    }

    @Test
    void colClientIdIndexPointsAtClientIdHeader() throws Exception {
        int colClientId = (Integer) staticField("COL_CLIENT_ID");
        String[] cols = (String[]) staticField("CLIENT_COLUMNS");
        assertEquals(1, colClientId, "COL_CLIENT_ID must be index 1");
        assertEquals("Client ID", cols[colClientId],
                "COL_CLIENT_ID must index the 'Client ID' column");
    }

    @Test
    void tsFormatMatchesFmtDateTimeRendering() throws Exception {
        DateTimeFormatter tsFormat = ServerViewText.TS_FORMAT;
        assertNotNull(tsFormat, "TS_FORMAT must exist");
        long millis = 1_000_000_000_000L;
        String viaConstant = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .toLocalDateTime().format(tsFormat);
        assertEquals(invokeFmtDateTime(millis), viaConstant,
                "TS_FORMAT is the formatter fmtDateTime uses");
    }
}
