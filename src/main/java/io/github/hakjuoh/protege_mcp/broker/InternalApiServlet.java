package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 *   <li>{@code POST /internal/shutdown} — graceful exit (used by tests and version takeover).
 * </ul>
 */
public final class InternalApiServlet extends HttpServlet {

    public static final String SECRET_HEADER = "X-Protege-Mcp-Internal";

    private final String dirSecret;
    private final InstanceRegistry registry;
    private final java.util.function.Supplier<BrokerState> identity;
    private final Runnable shutdown;
    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code identity} is a supplier because the bound port is only known after the bind. */
    public InternalApiServlet(String dirSecret, InstanceRegistry registry,
            java.util.function.Supplier<BrokerState> identity, Runnable shutdown) {
        this.dirSecret = dirSecret;
        this.registry = registry;
        this.identity = identity;
        this.shutdown = shutdown;
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
        switch (path) {
            case "/info" -> info(resp);
            case "/register" -> register(req, resp);
            case "/heartbeat" -> heartbeat(req, resp);
            case "/unregister" -> unregister(req, resp);
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
