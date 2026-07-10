package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

/**
 * End-to-end broker behavior over real sockets: a {@link BrokerServer} on an ephemeral port fronting
 * fake per-window backends (plain servlets standing in for the plugin's window servers). Covers the
 * auth gate, default + explicit + session-pinned routing, the forwarded broker secret, live SSE
 * pass-through, the {@code /internal} control plane, and the reference-count shutdown.
 */
class BrokerWiredTest {

    @TempDir
    java.nio.file.Path tmp;

    private BrokerHome home;
    private String dirSecret;
    private BrokerServer broker;
    private int brokerPort;
    private final CountDownLatch shutdownRequested = new CountDownLatch(1);
    private final List<EmbeddedHttpServer> backends = new java.util.ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static final String STATIC_TOKEN = "static-tok-abc";

    /** Backend fake: records the forwarded broker secret, echoes its name, emits a session id. */
    private static final class FakeBackend extends HttpServlet {
        final String name;
        final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
        final CountDownLatch sseGate = new CountDownLatch(1);

        FakeBackend(String name) {
            this.name = name;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            record(req);
            if ("sse".equals(req.getParameter("mode"))) {
                resp.setStatus(200);
                resp.setContentType("text/event-stream");
                var out = resp.getOutputStream();
                out.write("data: first-from-".concat(name).concat("\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    sseGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.write("data: second-from-".concat(name).concat("\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            resp.setStatus(200);
            resp.setContentType("application/json");
            resp.setHeader("Mcp-Session-Id", "session-of-" + name);
            resp.getWriter().write("{\"served_by\":\"" + name + "\"}");
        }

        private void record(HttpServletRequest req) {
            var names = req.getHeaderNames();
            while (names.hasMoreElements()) {
                String h = names.nextElement();
                lastHeaders.put(h.toLowerCase(java.util.Locale.ROOT), req.getHeader(h));
            }
        }
    }

    @BeforeEach
    void startBroker() throws Exception {
        home = new BrokerHome(tmp.resolve("home"));
        dirSecret = home.ensureDirSecret();
        broker = new BrokerServer(home, dirSecret, "test", shutdownRequested::countDown,
                8_000, 300, 60_000); // short linger so the refcount exit is observable
        brokerPort = broker.start(0);
    }

    @AfterEach
    void stopEverything() {
        broker.stop();
        for (EmbeddedHttpServer b : backends) {
            b.stop();
        }
    }

    private FakeBackend startBackend(String name) throws Exception {
        FakeBackend servlet = new FakeBackend(name);
        EmbeddedHttpServer server = new EmbeddedHttpServer();
        server.addServlet(servlet, "/mcp/*", true);
        int port = server.start(0);
        backends.add(server);
        servlet.lastHeaders.put("_port", String.valueOf(port));
        return servlet;
    }

    private BrokerClient client() {
        return new BrokerClient("http://127.0.0.1:" + brokerPort, dirSecret);
    }

    private static BrokerClient.WindowReg reg(FakeBackend b, String windowId, long focusedAt) {
        int port = Integer.parseInt(b.lastHeaders.get("_port"));
        return new BrokerClient.WindowReg(windowId, port, "win-secret-" + windowId,
                "win " + windowId, focusedAt, focusedAt);
    }

    private HttpResponse<String> call(String method, String path, String bearer) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + brokerPort + path))
                .timeout(Duration.ofSeconds(5));
        if (bearer != null) {
            rb.header("Authorization", "Bearer " + bearer);
        }
        rb.method(method, "POST".equals(method)
                ? HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\"}")
                : HttpRequest.BodyPublishers.noBody());
        return http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- auth ------------------------------------------------------------------------------------

    @Test
    void mcpWithoutBearerIs401WithOAuthChallenge() throws Exception {
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, List.of());
        HttpResponse<String> resp = call("POST", "/mcp", null);
        assertEquals(401, resp.statusCode());
        assertTrue(resp.headers().firstValue("WWW-Authenticate").orElse("").contains("resource_metadata"),
                "the broker must present the same OAuth challenge a window server does");
    }

    @Test
    void internalApiRequiresTheDirectorySecret() throws Exception {
        HttpResponse<String> bare = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/internal/info"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, bare.statusCode(), "no directory secret, no control plane");

        assertTrue(client().probe(), "with the secret the info probe answers");
    }

    // ---- routing ---------------------------------------------------------------------------------

    @Test
    void bearerRequestRoutesToMostRecentlyFocusedWindowAndCarriesItsSecret() throws Exception {
        FakeBackend older = startBackend("older");
        FakeBackend focused = startBackend("focused");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN,
                List.of(reg(older, "w-old", 100), reg(focused, "w-foc", 900)));

        HttpResponse<String> resp = call("POST", "/mcp", STATIC_TOKEN);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("focused"), "default routing = most recently focused window");
        assertEquals("win-secret-w-foc", focused.lastHeaders.get("x-protege-mcp-broker"),
                "the proxy must authenticate to the backend with that window's broker secret");
        assertEquals("session-of-focused", resp.headers().firstValue("Mcp-Session-Id").orElse(null),
                "the backend's session id must pass through to the client");
    }

    @Test
    void sessionStaysPinnedToItsWindowEvenWhenFocusMoves() throws Exception {
        FakeBackend first = startBackend("first");
        BrokerClient c = client();
        String processId = c.register(ProcessHandle.current().pid(), "t", STATIC_TOKEN,
                List.of(reg(first, "w1", 100)));

        HttpResponse<String> init = call("POST", "/mcp", STATIC_TOKEN);
        assertTrue(init.body().contains("first"));

        // A newer window appears and takes the default slot…
        FakeBackend second = startBackend("second");
        c.heartbeat(processId, STATIC_TOKEN, List.of(reg(first, "w1", 100), reg(second, "w2", 999)));

        // …but the established session keeps hitting the window that owns its state.
        HttpRequest pinned = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + brokerPort + "/mcp"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Mcp-Session-Id", "session-of-first")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> resp = http.send(pinned, HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.body().contains("first"), "session-pinned routing must beat the focus default");

        // A fresh (no-session) request goes to the new default.
        assertTrue(call("POST", "/mcp", STATIC_TOKEN).body().contains("second"));
    }

    @Test
    void explicitInstancePathTargetsThatWindow() throws Exception {
        FakeBackend a = startBackend("aa");
        FakeBackend b = startBackend("bb");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN,
                List.of(reg(a, "w-a", 900), reg(b, "w-b", 100)));

        HttpResponse<String> resp = call("POST", "/instances/w-b/mcp", STATIC_TOKEN);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("bb"), "explicit path must override the focus default");

        assertEquals(404, call("POST", "/instances/no-such/mcp", STATIC_TOKEN).statusCode());
    }

    @Test
    void instancesListingShowsWindowsAndNeedsAuth() throws Exception {
        FakeBackend a = startBackend("aa");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, List.of(reg(a, "w-a", 1)));

        assertEquals(401, call("GET", "/instances", null).statusCode());
        HttpResponse<String> resp = call("GET", "/instances", STATIC_TOKEN);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"/instances/w-a/mcp\""));
        assertTrue(resp.body().contains("\"default_mcp_path\":\"/mcp\""));
    }

    @Test
    void noRegisteredWindowsIs503ForAnAuthenticatedClient() throws Exception {
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, List.of());
        HttpResponse<String> resp = call("POST", "/mcp", STATIC_TOKEN);
        assertEquals(503, resp.statusCode());
        assertTrue(resp.body().contains("no_instances"));
    }

    // ---- streaming --------------------------------------------------------------------------------

    @Test
    void sseEventsPassThroughTheProxyLive() throws Exception {
        FakeBackend sse = startBackend("sse");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, List.of(reg(sse, "w-sse", 1)));

        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp?mode=sse"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            // The FIRST event must arrive while the backend is still holding the stream open — that
            // is the proof the proxy flushes per chunk instead of buffering the response.
            AtomicReference<String> firstLine = new AtomicReference<>();
            Thread readerThread = new Thread(() -> {
                try {
                    firstLine.set(reader.readLine());
                } catch (IOException ignored) {
                }
            });
            readerThread.start();
            readerThread.join(3_000);
            assertEquals("data: first-from-sse", firstLine.get(),
                    "the first SSE event must stream through before the backend finishes");

            sse.sseGate.countDown();
            reader.readLine(); // blank line after the first event
            assertEquals("data: second-from-sse", reader.readLine());
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    @Test
    void brokerAsksToExitOnceTheLastInstanceUnregisters() throws Exception {
        BrokerClient c = client();
        String processId = c.register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, List.of());
        c.unregister(processId);
        assertTrue(shutdownRequested.await(5, TimeUnit.SECONDS),
                "refcount 0 past the linger must trigger the broker's self-shutdown");
    }

    @Test
    void internalShutdownIsHonoured() throws Exception {
        client().requestShutdown();
        assertTrue(shutdownRequested.await(5, TimeUnit.SECONDS));
    }

    @Test
    void stateFileAdvertisesTheLiveBroker() {
        BrokerState state = home.readState().orElseThrow();
        assertEquals(brokerPort, state.port);
        assertEquals(ProcessHandle.current().pid(), state.pid);
        assertNotNull(BrokerMain.findLiveBroker(home, dirSecret).orElse(null),
                "discovery must find this broker through the state file + info probe");
    }
}
