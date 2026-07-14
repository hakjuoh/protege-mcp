package io.github.hakjuoh.protege_mcp.broker;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The broker's advertised identity, persisted as {@code broker.json} for discovery. */
public final class BrokerState {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** URL host the broker is reachable at from this machine (loopback default; IPv6 bracketed). */
    static final String DEFAULT_HOST = "127.0.0.1";

    public final long pid;
    public final String host;
    public final int port;
    public final String version;
    public final long startedAt;

    public BrokerState(long pid, int port, String version, long startedAt) {
        this(pid, DEFAULT_HOST, port, version, startedAt);
    }

    /**
     * @param host URL-ready connect host for {@link #baseUrl()} — for a wildcard bind this is the
     *     loopback (see {@code EmbeddedHttpServer.connectHost}), never the wildcard itself, so
     *     instances and local clients can always reach it.
     */
    public BrokerState(long pid, String host, int port, String version, long startedAt) {
        this.pid = pid;
        this.host = host == null || host.isEmpty() ? DEFAULT_HOST : host;
        this.port = port;
        this.version = version;
        this.startedAt = startedAt;
    }

    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("pid", pid);
        node.put("host", host);
        node.put("port", port);
        node.put("version", version);
        node.put("startedAt", startedAt);
        return node.toString();
    }

    static Optional<BrokerState> fromJson(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            int port = node.path("port").asInt(0);
            if (port <= 0) {
                return Optional.empty();
            }
            return Optional.of(new BrokerState(
                    node.path("pid").asLong(0),
                    // Absent in broker.json written by older plugin versions: those brokers always
                    // bound loopback, so the default reconstructs their reality.
                    node.path("host").asText(DEFAULT_HOST),
                    port,
                    node.path("version").asText(""),
                    node.path("startedAt").asLong(0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
