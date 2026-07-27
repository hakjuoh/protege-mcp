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
 * The broker's control-plane API for Protege instances, mounted at {@code /internal/*}. Every call
 * must carry the {@link #SECRET_HEADER} matching the directory secret from {@code ~/.protege-mcp}
 * (readable only by the same OS user - that file read <em>is</em> the authentication). MCP clients
 * never see or need this header; it plays no part in {@code /mcp} auth.
 *
 * <ul>
 *   <li>{@code GET  /internal/info} - liveness/identity probe used by discovery.
 *   <li>{@code POST /internal/register} - {@code {pid, version, token, lingerMs?, windows[]}}
 *       -> {@code {processId}}. {@code lingerMs} carries the user's idle-linger preference; a
 *       payload without it (older plugin) leaves the broker's current linger untouched.
 *   <li>{@code POST /internal/heartbeat} - same body plus {@code processId}; 404 = re-register.
 *   <li>{@code POST /internal/unregister} - {@code {processId}}; may drop the refcount to zero.
 *   <li>{@code GET  /internal/clients} - list OAuth clients and their effective capabilities.
 *   <li>{@code POST /internal/revoke-client} - revoke a client and invalidate its session pins.
 *   <li>{@code POST /internal/terminate-session} - invalidate one routed session pin.
 *   <li>{@code POST /internal/shutdown} - graceful exit (used by tests and version takeover).
 * </ul>
 */
public final class InternalApiServlet extends HttpServlet {

    public static final String SECRET_HEADER = "X-Protege-Mcp-Internal";
    /** The largest port a URI can name; past it a registration addresses nothing. */
    private static final int MAX_PORT = 65_535;

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
        this(dirSecret, registry, identity, shutdown, oauthStore, activeRequests,
                new BackendRevocationFanout(registry));
    }

    InternalApiServlet(String dirSecret, InstanceRegistry registry,
            java.util.function.Supplier<BrokerState> identity, Runnable shutdown, OAuthStore oauthStore,
            ActiveProxyRequests activeRequests, BackendRevocationFanout backendRevocations) {
        this.dirSecret = dirSecret;
        this.registry = registry;
        this.identity = identity;
        this.shutdown = shutdown;
        this.oauthStore = oauthStore;
        this.activeRequests = activeRequests;
        this.backendRevocations = backendRevocations;
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
        node.put("retained_processes", registry.retainedProcessCount());
        node.put("windows", registry.windowCount());
        node.put("shutdown_eligible", registry.shutdownEligible());
        writeJson(resp, 200, node);
    }

    private void register(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonNode body = mapper.readTree(req.getInputStream());
        long pid = body.path("pid").asLong(0);
        if (pid <= 0) {
            // A payload the registry would refuse: answer with the reason rather than a 500, so the
            // instance logs something it can act on instead of retrying an unsatisfiable registration.
            writeJson(resp, 400, mapper.createObjectNode().put("error", "invalid_pid"));
            return;
        }
        String processId;
        try {
            processId = registry.register(
                    pid,
                    body.path("version").asText(""),
                    body.path("token").asText(null),
                    parseWindows(body));
        } catch (IllegalArgumentException refused) {
            // Same reasoning as the pid: a payload that cannot be held whole - past the registry's window
            // bound, carrying no window list at all, or naming an endpoint no request can be addressed to -
            // is refused before any state is touched. Say so, rather than letting it surface as a 500 the
            // instance cannot distinguish from a broker fault it should keep retrying against.
            writeJson(resp, 400, mapper.createObjectNode().put("error", "invalid_windows"));
            return;
        } catch (IllegalStateException unavailable) {
            unavailable(resp);
            return;
        }
        registry.noteRequestedLinger(body.path("lingerMs").asLong(-1));
        ObjectNode node = mapper.createObjectNode();
        node.put("processId", processId);
        writeJson(resp, 200, node);
    }

    private void heartbeat(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonNode body = mapper.readTree(req.getInputStream());
        boolean known;
        try {
            known = registry.heartbeat(
                    body.path("processId").asText(""),
                    body.path("token").asText(null),
                    parseWindows(body));
        } catch (IllegalArgumentException refused) {
            // Refused before the registry retires or replaces anything, so the process keeps the window
            // set it last reported successfully - which is the point of refusing the payload whole rather
            // than holding the part of it that parsed, or reading a payload that carries no list at all as
            // an empty one; if it only ever sends this payload it goes stale and is reaped into quarantine,
            // where its endpoints are still owed every fence.
            writeJson(resp, 400, mapper.createObjectNode().put("error", "invalid_windows"));
            return;
        } catch (IllegalStateException unavailable) {
            unavailable(resp);
            return;
        }
        if (known) {
            registry.noteRequestedLinger(body.path("lingerMs").asLong(-1));
            writeJson(resp, 200, mapper.createObjectNode().put("ok", true));
        } else {
            // 404 tells the instance the broker lost it (e.g. broker restart) - re-register.
            writeJson(resp, 404, mapper.createObjectNode().put("error", "unknown_process"));
        }
    }

    private void unregister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JsonNode body = mapper.readTree(req.getInputStream());
        try {
            registry.unregister(body.path("processId").asText(""));
        } catch (IllegalStateException unavailable) {
            // The window's endpoints stay live in the registry, so the instance is still routable and
            // still fenceable; it goes stale and is reaped if the close is never accepted.
            unavailable(resp);
            return;
        }
        writeJson(resp, 200, mapper.createObjectNode().put("ok", true));
    }

    /**
     * A registry that cannot take this call right now, answered as such. Every state that gets here
     * passes - a quarantine that the next reap drains, a process table a departing instance frees, a
     * registry sealed for the shutdown a successor broker follows - so the instance should keep trying,
     * which is what a 503 tells it. Left uncaught these surface as a container-rendered 500, which the
     * caller cannot tell from a broker fault and which answers with something other than this API's JSON.
     */
    private void unavailable(HttpServletResponse resp) throws IOException {
        writeJson(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                mapper.createObjectNode().put("error", "broker_unavailable"));
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
        try {
            backendRevocations.prepareClient(clientId);
            activeRequests.prepareClient(clientId);
            registry.prepareClientRevocation(clientId);
        } catch (RuntimeException unavailable) {
            ObjectNode failed = mapper.createObjectNode().put("revoked", false)
                    .put("credential_removed", false)
                    .put("commit_fence_confirmed", false)
                    .put("error", "revocation_fence_unavailable");
            writeJson(resp, 503, failed);
            return;
        }
        boolean credentialRemoved = oauthStore.revokeClient(clientId);
        // Idempotent by design: after a partial 503 the credential is already absent, but retrying
        // this same request must still re-send every backend fence until all windows acknowledge.
        int inFlight = activeRequests.terminateClient(clientId);
        int sessions = registry.dropSessionsForPrincipal(clientId);
        BackendRevocationFanout.Result backend = backendRevocations.executeClient(clientId);
        ObjectNode result = mapper.createObjectNode();
        result.put("revoked", backend.confirmed());
        result.put("credential_removed", credentialRemoved);
        result.put("terminated_session_pins", sessions);
        result.put("terminated_in_flight_requests", inFlight);
        result.put("in_flight_termination", inFlight > 0);
        result.put("backend_windows", backend.windows());
        result.put("backend_acknowledged", backend.acknowledged());
        result.put("commit_fence_confirmed", backend.confirmed());
        // Counted as well as listed: the ids come from a bounded set, so a client that measured the list
        // would understate how many windows are still owed a fence.
        result.put("unacknowledged_windows", backend.unacknowledged());
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
        try {
            activeRequests.prepareSession(sessionId);
        } catch (RuntimeException unavailable) {
            writeJson(resp, 503, mapper.createObjectNode()
                    .put("error", "revocation_fence_unavailable"));
            return;
        }
        registry.unpinSession(sessionId);
        int inFlight = activeRequests.terminateSession(sessionId);
        ObjectNode result = mapper.createObjectNode();
        result.put("terminated", existed);
        result.put("terminated_in_flight_requests", inFlight);
        result.put("in_flight_termination", inFlight > 0);
        writeJson(resp, existed || inFlight > 0 ? 200 : 404, result);
    }

    private void shutdown(HttpServletResponse resp) throws IOException {
        boolean sealed;
        try {
            // The same durable compaction the idle exit runs, under the same registry monitor: this
            // shutdown ends the memory holding every obligation for an endpoint released on age alone, and
            // a successor that never heard of one would confirm a fence nothing proved.
            sealed = registry.sealForRequestedShutdown(backendRevocations::clearForQuiescentShutdown);
        } catch (RuntimeException notDurable) {
            // Nothing could be written down, so nothing may be forgotten: keep this broker running with
            // its obligations intact rather than acknowledge an exit that drops them.
            writeJson(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, mapper.createObjectNode()
                    .put("error", "revocation_fence_unavailable"));
            return;
        }
        if (!sealed) {
            writeJson(resp, HttpServletResponse.SC_CONFLICT, mapper.createObjectNode()
                    .put("error", "broker_not_quiescent"));
            return;
        }
        writeJson(resp, 200, mapper.createObjectNode().put("ok", true));
        // Flush the acknowledgement before the exit path tears the connector down.
        resp.flushBuffer();
        shutdown.run();
    }

    private List<InstanceRegistry.Window> parseWindows(JsonNode body) {
        JsonNode declared = body.path("windows");
        // An empty list is a process saying it has no windows open, which is a set the broker holds. A
        // payload with no list at all - or one whose "windows" is not an array - says nothing about the
        // set, and reading it as "none" would retire every window the process still has serving on its
        // behalf. That is the same silent unregistration a single unusable entry is refused for below, so
        // it is refused the same way rather than answered with a registry the caller never asked for.
        if (!declared.isArray()) {
            throw new IllegalArgumentException("broker windows are not a list");
        }
        List<InstanceRegistry.Window> windows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JsonNode w : declared) {
            int port = w.path("port").asInt(0);
            String id = w.path("id").asText("");
            String secret = w.path("secret").asText("");
            if (port <= 0 || port > MAX_PORT || !addressable(id, 256) || !addressable(secret, 512)) {
                // An entry that names no reachable endpoint is refused with the payload it arrived in,
                // on the registry's own all-or-nothing terms: a heartbeat is the whole window set of a
                // process, so keeping the rest of it would retire the windows the entry was meant to
                // report - one bad entry silently unregistering a window that is still serving. The
                // caller is told which payload was refused instead, and its previous set stands until it
                // sends one the broker can hold whole.
                throw new IllegalArgumentException("broker window is not addressable");
            }
            windows.add(new InstanceRegistry.Window(
                    id, port, secret,
                    w.path("title").asText(""),
                    w.path("focusedAt").asLong(0),
                    w.path("registeredAt").asLong(now)));
        }
        return windows;
    }

    /**
     * Whether a window identity is one the broker can build a request from: non-empty, inside its bound,
     * and printable US-ASCII with no space. The secret travels as an HTTP header value and the id is
     * reported and matched as a plain token, so a control character or a character outside that range
     * makes the fence request unbuildable rather than merely unanswered. Registrations mint both from
     * generated identifiers (a UUID and a URL-safe base64 token), so an honest instance always passes;
     * one that does not names an endpoint the broker could neither route to nor revoke.
     */
    private static boolean addressable(String value, int maxChars) {
        return !value.isEmpty() && value.length() <= maxChars
                && value.chars().allMatch(c -> c > 0x20 && c < 0x7F);
    }

    private void writeJson(HttpServletResponse resp, int status, ObjectNode node) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(node.toString());
    }
}
