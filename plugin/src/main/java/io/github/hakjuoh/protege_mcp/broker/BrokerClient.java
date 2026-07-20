package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * HTTP client for the broker's {@code /internal} control plane, used by Protégé instances to
 * register/heartbeat/unregister and by a booting broker to detect a live sibling. All calls carry
 * the directory secret; all timeouts are short — the broker is on the loopback, so anything slower
 * than a couple of seconds means it is gone and the caller should re-discover/re-spawn.
 */
public final class BrokerClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REVOCATION_TIMEOUT = Duration.ofMinutes(11);

    private final String baseUrl;
    private final String dirSecret;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(TIMEOUT)
            .build();

    public BrokerClient(String baseUrl, String dirSecret) {
        this.baseUrl = baseUrl;
        this.dirSecret = dirSecret;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Stateless liveness probe used by discovery (also from a booting sibling broker). */
    public static boolean probe(BrokerState state, String dirSecret) {
        return new BrokerClient(state.baseUrl(), dirSecret).probe();
    }

    public boolean probe() {
        try {
            HttpResponse<String> resp = send("GET", "/internal/info", null);
            return resp.statusCode() == 200 && resp.body().contains("\"protege-mcp-broker\"");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** The broker's identity/usage document ({@code /internal/info}), for version-skew decisions. */
    public JsonNode info() throws IOException, InterruptedException {
        HttpResponse<String> resp = send("GET", "/internal/info", null);
        if (resp.statusCode() != 200) {
            throw new IOException("broker info failed: HTTP " + resp.statusCode());
        }
        return MAPPER.readTree(resp.body());
    }

    /**
     * @param lingerMs the user's broker idle-linger preference, carried so a running broker adopts
     *     it without a respawn; pass a negative value to leave the broker's linger untouched
     * @return the broker-assigned process id used in every subsequent heartbeat/unregister.
     */
    public String register(long pid, String version, String token, long lingerMs,
            List<InstanceRegistry.Window> windows) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("pid", pid);
        body.put("version", version);
        body.put("token", token);
        if (lingerMs >= 0) {
            body.put("lingerMs", lingerMs);
        }
        body.set("windows", windowsJson(windows));
        HttpResponse<String> resp = send("POST", "/internal/register", body.toString());
        if (resp.statusCode() != 200) {
            throw new IOException("broker register failed: HTTP " + resp.statusCode());
        }
        JsonNode node = MAPPER.readTree(resp.body());
        String processId = node.path("processId").asText("");
        if (processId.isEmpty()) {
            throw new IOException("broker register returned no processId");
        }
        return processId;
    }

    /** @return false when the broker does not know the process (restarted) — re-register then. */
    public boolean heartbeat(String processId, String token, long lingerMs,
            List<InstanceRegistry.Window> windows) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("processId", processId);
        body.put("token", token);
        if (lingerMs >= 0) {
            body.put("lingerMs", lingerMs);
        }
        body.set("windows", windowsJson(windows));
        HttpResponse<String> resp = send("POST", "/internal/heartbeat", body.toString());
        if (resp.statusCode() == 404) {
            return false;
        }
        if (resp.statusCode() != 200) {
            throw new IOException("broker heartbeat failed: HTTP " + resp.statusCode());
        }
        return true;
    }

    public void unregister(String processId) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("processId", processId);
        send("POST", "/internal/unregister", body.toString());
    }

    public void requestShutdown() throws IOException, InterruptedException {
        send("POST", "/internal/shutdown", "{}");
    }

    /** Secret-free client snapshots for the Protégé management view. */
    public List<ClientInfo> listClients() throws IOException, InterruptedException {
        HttpResponse<String> resp = send("GET", "/internal/clients", null);
        if (resp.statusCode() != 200) {
            throw new IOException("broker client listing failed: HTTP " + resp.statusCode());
        }
        List<ClientInfo> clients = new ArrayList<>();
        for (JsonNode node : MAPPER.readTree(resp.body()).path("clients")) {
            Set<String> capabilities = new LinkedHashSet<>();
            for (JsonNode capability : node.path("capabilities")) {
                if (capability.isTextual()) {
                    capabilities.add(capability.asText());
                }
            }
            clients.add(new ClientInfo(
                    node.path("client_id").asText(""),
                    node.path("client_name").asText(""),
                    node.path("registered_at").asLong(0),
                    node.path("last_seen_at").asLong(0),
                    node.path("active_access_tokens").asInt(0),
                    node.path("latest_access_expiry").asLong(0),
                    capabilities));
        }
        return Collections.unmodifiableList(clients);
    }

    public RevocationResult revokeClient(String clientId) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_id", clientId);
        HttpResponse<String> resp = send("POST", "/internal/revoke-client", body.toString(),
                REVOCATION_TIMEOUT);
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 503) {
                JsonNode result = MAPPER.readTree(resp.body());
                if (result.has("unacknowledged_window_ids")) {
                    throw new IncompleteRevocationException(
                            result.path("unacknowledged_window_ids").size());
                }
                throw new IOException("broker client revocation is unavailable: "
                        + result.path("error").asText("service_unavailable"));
            }
            throw new IOException("broker client revocation failed: HTTP " + resp.statusCode());
        }
        JsonNode result = MAPPER.readTree(resp.body());
        boolean revoked = result.path("revoked").asBoolean(false);
        boolean fenceConfirmed = result.path("commit_fence_confirmed").asBoolean(false);
        if (!revoked || !fenceConfirmed) {
            throw new IOException("broker response did not confirm the client execution fence");
        }
        return new RevocationResult(true,
                result.path("terminated_session_pins").asInt(0),
                result.path("terminated_in_flight_requests").asInt(0),
                true);
    }

    public static final class ClientInfo {
        public final String clientId;
        public final String clientName;
        public final long registeredAt;
        public final long lastSeenAt;
        public final int activeAccessTokens;
        public final long latestAccessExpiry;
        public final Set<String> capabilities;

        ClientInfo(String clientId, String clientName, long registeredAt, long lastSeenAt,
                int activeAccessTokens, long latestAccessExpiry, Set<String> capabilities) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.registeredAt = registeredAt;
            this.lastSeenAt = lastSeenAt;
            this.activeAccessTokens = activeAccessTokens;
            this.latestAccessExpiry = latestAccessExpiry;
            this.capabilities = Set.copyOf(capabilities);
        }
    }

    public record RevocationResult(boolean revoked, int terminatedSessionPins,
            int terminatedInFlightRequests, boolean commitFenceConfirmed) { }

    public static final class IncompleteRevocationException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int unacknowledgedWindows;

        private IncompleteRevocationException(int unacknowledgedWindows) {
            super("broker revoked the credential, but " + unacknowledgedWindows
                    + " backend window(s) did not confirm the execution fence; retry revocation");
            this.unacknowledgedWindows = unacknowledgedWindows;
        }

        public int unacknowledgedWindows() {
            return unacknowledgedWindows;
        }
    }

    private static ArrayNode windowsJson(List<InstanceRegistry.Window> windows) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (InstanceRegistry.Window w : windows) {
            ObjectNode node = arr.addObject();
            node.put("id", w.id);
            node.put("port", w.port);
            node.put("secret", w.secret);
            node.put("title", w.title);
            node.put("focusedAt", w.focusedAt);
            node.put("registeredAt", w.registeredAt);
        }
        return arr;
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        return send(method, path, body, TIMEOUT);
    }

    private HttpResponse<String> send(String method, String path, String body, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header(InternalApiServlet.SECRET_HEADER, dirSecret);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
