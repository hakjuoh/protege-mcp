package io.github.hakjuoh.protege_mcp.broker;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The broker's advertised identity, persisted as {@code broker.json} for discovery. */
public final class BrokerState {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final long pid;
    public final int port;
    public final String version;
    public final long startedAt;

    public BrokerState(long pid, int port, String version, long startedAt) {
        this.pid = pid;
        this.port = port;
        this.version = version;
        this.startedAt = startedAt;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("pid", pid);
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
                    port,
                    node.path("version").asText(""),
                    node.path("startedAt").asLong(0)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
