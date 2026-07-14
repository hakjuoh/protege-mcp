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

    /**
     * The ready-to-paste {@code claude mcp add} command for a running server at {@code endpointUrl}.
     * A bracketed IPv6 URL is quoted: zsh (the macOS default shell) treats {@code [::1]} as a glob
     * character class and kills the whole command with "no matches found" when left bare.
     */
    static String connectCommand(String endpointUrl) {
        String url = endpointUrl.indexOf('[') >= 0 ? "\"" + endpointUrl + "\"" : endpointUrl;
        return "claude mcp add --transport http protege " + url;
    }

    /**
     * First status line: the run state of this window's server. A user-initiated stop is a normal
     * state, not an event to attribute — it reads plainly as "stopped"; {@code lastError} (only ever
     * shown while stopped) covers the actual failures.
     */
    static String statusLine(boolean running, String lastError) {
        if (running) {
            return "MCP server: RUNNING";
        }
        StringBuilder line = new StringBuilder("MCP server: stopped");
        if (lastError != null) {
            line.append("  (last error: ").append(lastError).append(")");
        }
        return line.toString();
    }

    /**
     * True when the heartbeat lost the shared broker while this window still serves as one of its
     * backends: the view then swaps Stop for Start (Start relaunches the broker). {@code brokerUrl}
     * is {@link io.github.hakjuoh.protege_mcp.broker.BrokerLink#brokerMcpUrl()} — null exactly while
     * the broker is unreachable.
     */
    static boolean brokerDown(boolean running, boolean brokerManaged, String brokerUrl) {
        return running && brokerManaged && brokerUrl == null;
    }

    /**
     * Second status line: how a running server is exposed — attached to the shared broker (healthy or
     * down), or standalone. Empty when stopped. The broker states carry this window's direct
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
                    : "Broker is down — press Start to relaunch it; this window still serves at "
                            + directUrl;
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
