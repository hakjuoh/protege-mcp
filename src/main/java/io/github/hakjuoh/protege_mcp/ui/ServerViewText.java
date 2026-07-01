package io.github.hakjuoh.protege_mcp.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Pure text/format helpers for {@link McpServerView}, split out of the Swing component so they can be
 * unit-tested headless (referencing the view class itself would drag in AWT). Behavior is identical to
 * the private helpers these replaced.
 */
final class ServerViewText {

    private ServerViewText() {
    }

    static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Mask a token as {@code abc••••••xyz}; {@code ••••••} when null or 6 chars or shorter. */
    static String mask(String token) {
        if (token == null || token.length() <= 6) {
            return "••••••";
        }
        return token.substring(0, 3) + "••••••" + token.substring(token.length() - 3);
    }

    /** Format epoch milliseconds in the system time zone as {@code yyyy-MM-dd HH:mm:ss}. */
    static String fmtDateTime(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .toLocalDateTime().format(TS_FORMAT);
    }

    /** The ready-to-paste {@code claude mcp add} command for a running server at {@code endpointUrl}. */
    static String connectCommand(String endpointUrl) {
        return "claude mcp add --transport http protege " + endpointUrl;
    }
}
