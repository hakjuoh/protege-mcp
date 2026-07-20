package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The broker's control-plane API for Protégé instances, mounted at {@code /internal/*}. Every call
 * must carry the {@link #SECRET_HEADER} matching the directory secret from {@code ~/.protege-mcp}
 * (readable only by the same OS user — that file read <em>is</em> the authentication). MCP clients
 * never see or need this header; it plays no part in {@code /mcp} auth.
 *
 * <ul>
 *   <li>{@code GET  /internal/info} — liveness/identity probe used by discovery.
 *   <li>{@code POST /internal/register} — {@code {pid, version, token, lingerMs?, windows[]}}
 *       → {@code {processId}}. {@code lingerMs} carries the user's idle-linger preference; a
 *       payload without it (older plugin) leaves the broker's current linger untouched.
 *   <li>{@code POST /internal/heartbeat} — same body plus {@code processId}; 404 = re-register.
 *   <li>{@code POST /internal/unregister} — {@code {processId}}; may drop the refcount to zero.
 *   <li>{@code GET  /internal/clients} — list OAuth clients and their effective capabilities.
 *   <li>{@code POST /internal/revoke-client} — revoke a client and invalidate its session pins.
 *   <li>{@code POST /internal/terminate-session} — invalidate one routed session pin.
 *   <li>{@code POST /internal/shutdown} — graceful exit (used by tests and version takeover).
 * </ul>
 */
public final class InternalApiServlet extends HttpServlet {

    public static final String SECRET_HEADER = "X-Protege-Mcp-Internal";

    private final String dirSecret;
    private final InstanceRegistry registry;
    private final java.util.function.Supplier<BrokerState> identity;
    private final Runnable shutdown;
    private final OAuthStore oauthStore;
    private final ActiveProxyRequests activeRequests;
    private final BackendRevocationFanout backendRevocations;
    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code identity} is a supplier because the bound port is only known after the bind. */
    public InternalApiServlet(String dirSecret, InstanceRegistry registry,
            java.util.function.Supplier<BrokerState> identity, Runnable shutdown) {
        this(dirSecret, registry, identity, shutdown, null, new ActiveProxyRequests());
    }

    public InternalApiServlet(String dirSecret, InstanceRegistry registry,
            java.util.function.Supplier<BrokerState> identity, Runnable shutdown, OAuthStore oauthStore,
            ActiveProxyRequests activeRequests) {
        this.dirSecret = dirSecret;
        this.registry = registry;
        this.identity = identity;
        this.shutdown = shutdown;
        this.oauthStore = oauthStore;
        this.activeRequests = activeRequests;
        this.backendRevocations = new BackendRevocationFanout(registry);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!authorized(req)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"forbidden\"}");
            return;
        }
        String path = req.getPathInfo() == null ? "" : req.getPathInfo();
        boolean getOnly = "/info".equals(path) || "/clients".equals(path);
        if ((getOnly && !"GET".equals(req.getMethod()))
                || (!getOnly && !"POST".equals(req.getMethod()))) {
            resp.setHeader("Allow", getOnly ? "GET" : "POST");
            writeJson(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    mapper.createObjectNode().put("error", "method_not_allowed"));
            return;
        }
        switch (path) {
            case "/info" -> info(resp);
            case "/register" -> register(req, resp);
            case "/heartbeat" -> heartbeat(req, resp);
            case "/unregister" -> unregister(req, resp);
            case "/clients" -> clients(resp);
            case "/revoke-client" -> revokeClient(req, resp);
            case "/terminate-session" -> terminateSession(req, resp);
            case "/shutdown" -> shutdown(resp);
            default -> resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private boolean authorized(HttpServletRequest req) {
        String presented = req.getHeader(SECRET_HEADER);
        return presented != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), dirSecret.getBytes(StandardCharsets.UTF_8));
    }

    private void info(HttpServletResponse resp) throws IOException {
        BrokerState state = identity.get();
        ObjectNode node = mapper.createObjectNode();
        node.put("service", "protege-mcp-broker");
        node.put("pid", state == null ? 0 : state.pid);
        node.put("port", state == null ? 0 : state.port);
        node.put("version", state == null ? "" : state.version);
        node.put("startedAt", state == null ? 0 : state.startedAt);
        node.put("processes", registry.processCount());
        node.put("windows", registry.windowCount());
        writeJson(resp, 200, node);
    }

    private void register(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonNode body = mapper.readTree(req.getInputStream());
        String processId = registry.register(
                body.path("pid").asLong(0),
                body.path("version").asText(""),
                body.path("token").asText(null),
                parseWindows(body));
        registry.noteRequestedLinger(body.path("lingerMs").asLong(-1));
        ObjectNode node = mapper.createObjectNode();
        node.put("processId", processId);
        writeJson(resp, 200, node);
    }

    private void heartbeat(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonNode body = mapper.readTree(req.getInputStream());
        boolean known = registry.heartbeat(
                body.path("processId").asText(""),
                body.path("token").asText(null),
                parseWindows(body));
        if (known) {
            registry.noteRequestedLinger(body.path("lingerMs").asLong(-1));
            writeJson(resp, 200, mapper.createObjectNode().put("ok", true));
        } else {
            // 404 tells the instance the broker lost it (e.g. broker restart) — re-register.
            writeJson(resp, 404, mapper.createObjectNode().put("error", "unknown_process"));
        }
    }

    private void unregister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonNode body = mapper.readTree(req.getInputStream());
        registry.unregister(body.path("processId").asText(""));
        writeJson(resp, 200, mapper.createObjectNode().put("ok", true));
    }

    private void clients(HttpServletResponse resp) throws IOException {
        if (oauthStore == null) {
            writeJson(resp, 503, mapper.createObjectNode().put("error", "oauth_store_unavailable"));
            return;
        }
        ArrayNode clients = mapper.createArrayNode();
        for (OAuthStore.ClientInfo client : oauthStore.listClients()) {
            ObjectNode item = clients.addObject();
            item.put("client_id", client.clientId);
            item.put("client_name", client.clientName);
            item.put("registered_at", client.registeredAt);
            item.put("last_seen_at", client.lastSeenAt);
            item.put("active_access_tokens", client.activeAccessTokens);
            item.put("latest_access_expiry", client.latestAccessExpiry);
            ArrayNode capabilities = item.putArray("capabilities");
            client.capabilities.stream().sorted().forEach(capabilities::add);
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("clients", clients);
        writeJson(resp, 200, body);
    }

    private void revokeClient(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (oauthStore == null) {
            writeJson(resp, 503, mapper.createObjectNode().put("error", "oauth_store_unavailable"));
            return;
        }
        JsonNode body = mapper.readTree(req.getInputStream());
        String clientId = body.path("client_id").asText("");
        if (clientId.isBlank() || clientId.length() > 512) {
            writeJson(resp, 400, mapper.createObjectNode().put("error", "invalid_client_id"));
            return;
        }
        boolean credentialRemoved = oauthStore.revokeClient(clientId);
        // Idempotent by design: after a partial 503 the credential is already absent, but retrying
        // this same request must still re-send every backend fence until all windows acknowledge.
        int sessions = registry.dropSessionsForPrincipal(clientId);
        int inFlight = activeRequests.terminateClient(clientId);
        BackendRevocationFanout.Result backend = backendRevocations.revokeClient(clientId);
        ObjectNode result = mapper.createObjectNode();
        result.put("revoked", backend.confirmed());
        result.put("credential_removed", credentialRemoved);
        result.put("terminated_session_pins", sessions);
        result.put("terminated_in_flight_requests", inFlight);
        result.put("in_flight_termination", inFlight > 0);
        result.put("backend_windows", backend.windows());
        result.put("backend_acknowledged", backend.acknowledged());
        result.put("commit_fence_confirmed", backend.confirmed());
        ArrayNode failed = result.putArray("unacknowledged_window_ids");
        backend.failedWindowIds().forEach(failed::add);
        writeJson(resp, backend.confirmed() ? 200 : 503, result);
    }

    private void terminateSession(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonNode body = mapper.readTree(req.getInputStream());
        String sessionId = body.path("session_id").asText("");
        if (sessionId.isBlank() || sessionId.length() > 512) {
            writeJson(resp, 400, mapper.createObjectNode().put("error", "invalid_session_id"));
            return;
        }
        boolean existed = registry.windowForSession(sessionId).isPresent();
        registry.unpinSession(sessionId);
        int inFlight = activeRequests.terminateSession(sessionId);
        ObjectNode result = mapper.createObjectNode();
        result.put("terminated", existed);
        result.put("terminated_in_flight_requests", inFlight);
        result.put("in_flight_termination", inFlight > 0);
        writeJson(resp, existed || inFlight > 0 ? 200 : 404, result);
    }

    private void shutdown(HttpServletResponse resp) throws IOException {
        writeJson(resp, 200, mapper.createObjectNode().put("ok", true));
        // Flush the acknowledgement before the exit path tears the connector down.
        resp.flushBuffer();
        shutdown.run();
    }

    private List<InstanceRegistry.Window> parseWindows(JsonNode body) {
        List<InstanceRegistry.Window> windows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JsonNode w : body.path("windows")) {
            int port = w.path("port").asInt(0);
            String id = w.path("id").asText("");
            String secret = w.path("secret").asText("");
            if (port <= 0 || id.isEmpty() || secret.isEmpty()) {
                continue; // malformed window entries are skipped, not fatal
            }
            windows.add(new InstanceRegistry.Window(
                    id, port, secret,
                    w.path("title").asText(""),
                    w.path("focusedAt").asLong(0),
                    w.path("registeredAt").asLong(now)));
        }
        return windows;
    }

    private void writeJson(HttpServletResponse resp, int status, ObjectNode node) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(node.toString());
    }
}
