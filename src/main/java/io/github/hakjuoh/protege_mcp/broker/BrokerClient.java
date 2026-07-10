package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

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

    /** One registered window as reported by the instance. */
    public static final class WindowReg {
        public final String id;
        public final int port;
        public final String secret;
        public final String title;
        public final long focusedAt;
        public final long registeredAt;

        public WindowReg(String id, int port, String secret, String title, long focusedAt, long registeredAt) {
            this.id = id;
            this.port = port;
            this.secret = secret;
            this.title = title;
            this.focusedAt = focusedAt;
            this.registeredAt = registeredAt;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

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

    /** @return the broker-assigned process id used in every subsequent heartbeat/unregister. */
    public String register(long pid, String version, String token, List<WindowReg> windows)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("pid", pid);
        body.put("version", version);
        body.put("token", token);
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
    public boolean heartbeat(String processId, String token, List<WindowReg> windows)
            throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("processId", processId);
        body.put("token", token);
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

    private static ArrayNode windowsJson(List<WindowReg> windows) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (WindowReg w : windows) {
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
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
