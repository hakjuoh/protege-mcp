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

    /**
     * First status line: the run state of this window's server. A user-initiated stop is named as
     * such — including that the Ontology Assistant will respect it — so the state never looks like a
     * failure; {@code lastError} (only ever shown while stopped) covers the actual failures.
     */
    static String statusLine(boolean running, boolean userStopped, String lastError) {
        if (running) {
            return "MCP server: RUNNING";
        }
        StringBuilder line = new StringBuilder("MCP server: stopped");
        if (userStopped) {
            line.append(" by Stop — the Ontology Assistant will not restart it; press Start to serve again");
        }
        if (lastError != null) {
            line.append("  (last error: ").append(lastError).append(")");
        }
        return line.toString();
    }

    /**
     * Second status line: how a running server is exposed — attached to the shared broker (healthy or
     * reconnecting), or standalone. Empty when stopped. The broker states carry this window's direct
     * URL because the Endpoint URL field shows the broker's URL in that mode; the standalone state
     * doesn't repeat the URL (the field already shows it) but names the port fallback when one is in
     * effect.
     */
    static String connectionLine(boolean running, boolean brokerManaged, String brokerUrl,
            boolean brokerPreferred, String directUrl, boolean portFallback, int configuredPort) {
        if (!running) {
            return "";
        }
        if (brokerManaged) {
            return brokerUrl != null
                    ? "Shared broker: connected — this window direct: " + directUrl
                    : "Shared broker: UNREACHABLE, reconnecting automatically — this window still "
                            + "serves at " + directUrl;
        }
        String line = brokerPreferred
                ? "Standalone (not attached to the shared broker)"
                : "Standalone";
        if (portFallback) {
            line += " — configured port " + configuredPort + " was busy; bound an ephemeral port instead";
        }
        return line;
    }
}
