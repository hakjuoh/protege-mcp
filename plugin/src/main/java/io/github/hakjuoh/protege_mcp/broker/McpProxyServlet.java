package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

/**
 * Streams MCP traffic between clients and the per-window backend servers. Mounted at {@code /mcp/*}
 * (default routing) and {@code /instances/*} (explicit {@code /instances/{windowId}/mcp} targeting).
 *
 * <p>Routing: a request carrying an {@code Mcp-Session-Id} goes to the window that created that
 * session (pinned when the initialize response first surfaced the id — MCP session state lives in
 * the backend and cannot move); a request without one goes to the registry's default window. The
 * response body is pumped chunk-by-chunk with a flush per read so SSE streams pass through live.
 *
 * <p>The client was already authenticated by the broker's token filter before reaching this servlet;
 * the forwarded request additionally carries the target window's private broker secret, which the
 * backend accepts in place of a bearer token (the backend never has to re-validate OAuth tokens it
 * has never seen).
 */
public final class McpProxyServlet extends HttpServlet {

    /** Header the backend's AccessTokenFilter accepts as broker-authenticated. */
    public static final String BROKER_SECRET_HEADER = "X-Protege-Mcp-Broker";
    static final String SESSION_HEADER = "Mcp-Session-Id";

    /**
     * SSE keep-alive comment probe. A listening MCP stream can be silent indefinitely, so a vanished
     * client would otherwise pin a proxy thread (blocked on the backend read) forever; a periodic
     * comment line — which SSE clients must ignore — makes the dead downstream fail a write, and the
     * watchdog then closes the upstream body to unblock the pump thread.
     */
    private static final long KEEPALIVE_MS = 10_000;
    private static final byte[] KEEPALIVE_COMMENT = ": keep-alive\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final java.util.concurrent.ScheduledExecutorService KEEPALIVE =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "protege-mcp-broker-sse-keepalive");
                t.setDaemon(true);
                return t;
            });

    /** Hop-by-hop and container-managed headers that must not be forwarded either way. */
    private static final Set<String> SKIP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length", "expect", "date");

    private final InstanceRegistry registry;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public McpProxyServlet(InstanceRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if ("/instances".equals(req.getServletPath())
                && (req.getPathInfo() == null || "/".equals(req.getPathInfo()))) {
            listInstances(resp);
            return;
        }
        Target target = resolveTarget(req);
        if (target.error != null) {
            writeError(resp, target.errorStatus, target.error, target.errorDetail);
            return;
        }
        InstanceRegistry.Window window = target.window;

        URI upstream = URI.create("http://127.0.0.1:" + window.port + target.path
                + (req.getQueryString() != null ? "?" + req.getQueryString() : ""));
        HttpRequest.Builder builder = HttpRequest.newBuilder(upstream);
        copyRequestHeaders(req, builder);
        builder.header(BROKER_SECRET_HEADER, window.secret);
        AuthenticatedPrincipal principal = principal(req);
        if (principal == null) {
            writeError(resp, 401, "missing_principal", "authenticated broker request has no principal");
            return;
        }
        builder.header(AuthenticatedPrincipal.BROKER_HEADER, principal.encode());
        String method = req.getMethod();
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return req.getInputStream();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<InputStream> upstreamResp;
        try {
            upstreamResp = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            writeError(resp, 502, "instance_unreachable",
                    "the Protégé window behind this session is not answering; it may have been closed");
            return;
        }

        resp.setStatus(upstreamResp.statusCode());
        for (Map.Entry<String, java.util.List<String>> h : upstreamResp.headers().map().entrySet()) {
            String name = h.getKey();
            if (name == null || SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || name.startsWith(":")) {
                continue;
            }
            for (String value : h.getValue()) {
                resp.addHeader(name, value);
            }
        }

        // Pin the session to this window the moment the backend names it (the initialize response),
        // so every follow-up request for the session lands on the same backend.
        upstreamResp.headers().firstValue(SESSION_HEADER)
                .ifPresent(sid -> registry.pinSession(sid, window.id, principal));

        boolean sse = upstreamResp.headers().firstValue("Content-Type")
                .map(ct -> ct.toLowerCase(Locale.ROOT).contains("text/event-stream"))
                .orElse(false);

        InputStream in = upstreamResp.body();
        OutputStream out = resp.getOutputStream();
        Object writeLock = new Object();
        java.util.concurrent.ScheduledFuture<?> watchdog = null;
        if (sse) {
            watchdog = KEEPALIVE.scheduleWithFixedDelay(() -> {
                synchronized (writeLock) {
                    try {
                        out.write(KEEPALIVE_COMMENT);
                        out.flush();
                    } catch (IOException clientGone) {
                        closeQuietly(in); // unblocks the pump's read → thread + backend released
                    }
                }
            }, KEEPALIVE_MS, KEEPALIVE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        boolean upstreamFailed = false;
        try {
            byte[] buf = new byte[8192];
            while (true) {
                int n;
                try {
                    n = in.read(buf);
                } catch (IOException upstreamGone) {
                    // Backend died mid-stream (window closed / crash), or the watchdog closed the
                    // body after the client vanished.
                    upstreamFailed = true;
                    break;
                }
                if (n < 0) {
                    break;
                }
                try {
                    synchronized (writeLock) {
                        out.write(buf, 0, n);
                        out.flush(); // per-chunk flush keeps SSE events flowing live through the proxy
                    }
                } catch (IOException clientGone) {
                    break; // closing the upstream (finally) cancels the backend side
                }
            }
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            closeQuietly(in);
        }

        if ("DELETE".equals(method) && upstreamResp.statusCode() >= 200
                && upstreamResp.statusCode() < 300 && !upstreamFailed) {
            registry.unpinSession(req.getHeader(SESSION_HEADER));
        }
        if (upstreamFailed) {
            // The response is already committed: abort the connection instead of writing a clean
            // terminating chunk, so a still-alive client SEES the truncation rather than mistaking a
            // half-delivered stream for a complete response.
            throw new IOException("upstream ended abnormally while streaming");
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // releasing resources — nothing sensible to do with a failed close
        }
    }

    /** GET /instances — the registered windows (id + title + timestamps), newest first. */
    private void listInstances(HttpServletResponse resp) throws IOException {
        com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
        for (InstanceRegistry.Window w : registry.listWindows()) {
            com.fasterxml.jackson.databind.node.ObjectNode node = arr.addObject();
            node.put("id", w.id);
            node.put("title", w.title);
            node.put("registered_at", w.registeredAt);
            node.put("focused_at", w.focusedAt);
            node.put("mcp_path", "/instances/" + w.id + "/mcp");
        }
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.set("instances", arr);
        body.put("default_mcp_path", "/mcp");
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write(body.toString());
    }

    private Target resolveTarget(HttpServletRequest req) {
        String servletPath = req.getServletPath();
        String pathInfo = req.getPathInfo() == null ? "" : req.getPathInfo();
        // Session ownership guards EVERY route: an explicit /instances/{windowId}/mcp request that
        // presents another client's session id must not reach that session's backend either.
        String sessionId = req.getHeader(SESSION_HEADER);
        Optional<InstanceRegistry.Window> pinnedWindow = registry.windowForSession(sessionId);
        if (sessionId != null && !registry.sessionOwnedBy(sessionId, principal(req))) {
            return Target.error(403, "session_principal_mismatch",
                    "this MCP session belongs to a different authenticated client/grant");
        }
        if ("/instances".equals(servletPath)) {
            // /instances/{windowId}/mcp[...] — explicit targeting of one window.
            String[] parts = pathInfo.split("/", 3); // ["", windowId, rest]
            if (parts.length < 3 || parts[1].isEmpty()) {
                return Target.error(404, "unknown_instance_path",
                        "use /instances/{windowId}/mcp — list windows at /instances");
            }
            Optional<InstanceRegistry.Window> window = registry.windowById(parts[1]);
            if (window.isEmpty()) {
                return Target.error(404, "unknown_instance",
                        "no registered Protégé window has id " + parts[1]);
            }
            // MCP session state lives in the backend that created it and cannot move. If this request
            // carries a session id already pinned to a DIFFERENT window, routing it to the explicitly
            // named window would hit a backend that never saw the session — reject rather than misroute.
            if (sessionId != null && !sessionId.isEmpty()) {
                // An unknown pin is rejected too. It can result from explicit admin termination, LRU
                // eviction, or a broker restart while the backend still retains session state; forwarding
                // it would bypass client/grant ownership because the broker no longer knows the owner.
                if (pinnedWindow.isEmpty()) {
                    return Target.error(404, "session_window_closed",
                            "this MCP session is no longer routed by the broker; start a new session");
                }
                if (!pinnedWindow.get().id.equals(window.get().id)) {
                    return Target.error(409, "session_window_mismatch",
                            "this MCP session is pinned to a different Protégé window; omit the session "
                                    + "id to start a new one on this window");
                }
            }
            return Target.of(window.get(), "/" + parts[2]);
        }

        // /mcp[...] — session-pinned, else the default (most recently focused/registered) window.
        if (pinnedWindow.isPresent()) {
            return Target.of(pinnedWindow.get(), servletPath + pathInfo);
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            return Target.error(404, "session_window_closed",
                    "the Protégé window serving this MCP session is gone; start a new session");
        }
        Optional<InstanceRegistry.Window> fallback = registry.defaultWindow();
        if (fallback.isEmpty()) {
            return Target.error(503, "no_instances",
                    "no Protégé instance is registered with the broker — open an ontology in Protégé");
        }
        return Target.of(fallback.get(), servletPath + pathInfo);
    }

    private static void copyRequestHeaders(HttpServletRequest req, HttpRequest.Builder builder) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || BROKER_SECRET_HEADER.equalsIgnoreCase(name)
                    || AuthenticatedPrincipal.BROKER_HEADER.equalsIgnoreCase(name)
                    || InternalApiServlet.SECRET_HEADER.equalsIgnoreCase(name)) {
                continue; // never let a client smuggle broker-trust headers upstream
            }
            Enumeration<String> values = req.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    builder.header(name, values.nextElement());
                } catch (IllegalArgumentException restricted) {
                    // JDK HttpClient refuses some headers it manages itself — skip them.
                }
            }
        }
    }

    private static AuthenticatedPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(AuthenticatedPrincipal.REQUEST_ATTRIBUTE);
        return value instanceof AuthenticatedPrincipal ? (AuthenticatedPrincipal) value : null;
    }

    private void writeError(HttpServletResponse resp, int status, String error, String detail)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        // Serialize properly: `detail` can embed client-controlled text (e.g. a windowId segment).
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        node.put("error", error);
        node.put("detail", detail);
        resp.getWriter().write(node.toString());
    }

    private static final class Target {
        final InstanceRegistry.Window window;
        final String path;
        final String error;
        final String errorDetail;
        final int errorStatus;

        private Target(InstanceRegistry.Window window, String path, int errorStatus, String error,
                String errorDetail) {
            this.window = window;
            this.path = path;
            this.errorStatus = errorStatus;
            this.error = error;
            this.errorDetail = errorDetail;
        }

        static Target of(InstanceRegistry.Window window, String path) {
            return new Target(window, path, 0, null, null);
        }

        static Target error(int status, String error, String detail) {
            return new Target(null, null, status, error, detail);
        }
    }
}
