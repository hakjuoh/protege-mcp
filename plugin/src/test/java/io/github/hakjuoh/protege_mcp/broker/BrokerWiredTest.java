package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.server.BrokerControlServlet;
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
        final java.util.concurrent.atomic.AtomicInteger fencedClients =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean acknowledgeFences;

        FakeBackend(String name) {
            this(name, true);
        }

        FakeBackend(String name, boolean acknowledgeFences) {
            this.name = name;
            this.acknowledgeFences = new java.util.concurrent.atomic.AtomicBoolean(acknowledgeFences);
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            record(req);
            if (BrokerControlServlet.PATH.equals(req.getServletPath())) {
                fencedClients.incrementAndGet();
                resp.setStatus(200);
                resp.setContentType("application/json");
                resp.getWriter().write("{\"commit_fence_confirmed\":"
                        + acknowledgeFences.get() + "}");
                return;
            }
            String forcedStatus = req.getParameter("status");
            if (forcedStatus != null) {
                resp.setStatus(Integer.parseInt(forcedStatus));
                resp.getWriter().write("forced status " + forcedStatus);
                return;
            }
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
        return startBackend(name, true);
    }

    private FakeBackend startBackend(String name, boolean acknowledgeFences) throws Exception {
        FakeBackend servlet = new FakeBackend(name, acknowledgeFences);
        EmbeddedHttpServer server = new EmbeddedHttpServer();
        server.addServlet(servlet, "/mcp/*", true);
        server.addServlet(servlet, BrokerControlServlet.PATH + "/*", false);
        int port = server.start(0);
        backends.add(server);
        servlet.lastHeaders.put("_port", String.valueOf(port));
        return servlet;
    }

    private BrokerClient client() {
        return new BrokerClient("http://127.0.0.1:" + brokerPort, dirSecret);
    }

    private static InstanceRegistry.Window reg(FakeBackend b, String windowId, long focusedAt) {
        int port = Integer.parseInt(b.lastHeaders.get("_port"));
        return new InstanceRegistry.Window(windowId, port, "win-secret-" + windowId,
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
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of());
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
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(older, "w-old", 100), reg(focused, "w-foc", 900)));

        HttpResponse<String> resp = call("POST", "/mcp", STATIC_TOKEN);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("focused"), "default routing = most recently focused window");
        assertEquals("win-secret-w-foc", focused.lastHeaders.get("x-protege-mcp-broker"),
                "the proxy must authenticate to the backend with that window's broker secret");
        AuthenticatedPrincipal forwarded = AuthenticatedPrincipal.decode(
                focused.lastHeaders.get(AuthenticatedPrincipal.BROKER_HEADER.toLowerCase()));
        assertEquals("static-local-admin", forwarded.clientId(),
                "the broker must attach its own verified principal envelope");
        assertEquals("session-of-focused", resp.headers().firstValue("Mcp-Session-Id").orElse(null),
                "the backend's session id must pass through to the client");
    }

    @Test
    void clientCannotSmuggleBrokerTrustHeadersToTheWindow() throws Exception {
        FakeBackend backend = startBackend("header-guard");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(backend, "w-guard", 1)));
        String forged = AuthenticatedPrincipal.oauthAdmin(
                "attacker", "Forged admin", "forged-grant").encode();

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header(AuthenticatedPrincipal.BROKER_HEADER, forged)
                .header(InternalApiServlet.SECRET_HEADER, "forged-directory-secret")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        AuthenticatedPrincipal forwarded = AuthenticatedPrincipal.decode(
                backend.lastHeaders.get(AuthenticatedPrincipal.BROKER_HEADER.toLowerCase()));
        assertEquals("static-local-admin", forwarded.clientId(),
                "the broker must replace, not forward, a client-supplied principal");
        assertFalse(backend.lastHeaders.containsKey(
                InternalApiServlet.SECRET_HEADER.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    void oauthSessionCannotBeReplayedByAnotherAuthenticatedPrincipal() throws Exception {
        FakeBackend backend = startBackend("oauth-owner");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(backend, "w-oauth", 1)));
        OAuthStore.Client owner = broker.oauthStore().registerClient(
                List.of("http://127.0.0.1/owner"), "owner-client");
        OAuthStore.Tokens ownerTokens = broker.oauthStore().issueTokens(owner.clientId, "mcp", null);
        OAuthStore.Client attacker = broker.oauthStore().registerClient(
                List.of("http://127.0.0.1/attacker"), "attacker-client");
        OAuthStore.Tokens attackerTokens = broker.oauthStore().issueTokens(
                attacker.clientId, "mcp", null);

        HttpResponse<String> opened = call("POST", "/mcp", ownerTokens.accessToken);
        assertEquals(200, opened.statusCode());
        AuthenticatedPrincipal forwarded = AuthenticatedPrincipal.decode(
                backend.lastHeaders.get(AuthenticatedPrincipal.BROKER_HEADER.toLowerCase()));
        assertEquals(owner.clientId, forwarded.clientId());

        HttpResponse<String> replay = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp"))
                .header("Authorization", "Bearer " + attackerTokens.accessToken)
                .header("Mcp-Session-Id", "session-of-oauth-owner")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, replay.statusCode());
        assertTrue(replay.body().contains("session_principal_mismatch"));
    }

    @Test
    void sessionStaysPinnedToItsWindowEvenWhenFocusMoves() throws Exception {
        FakeBackend first = startBackend("first");
        BrokerClient c = client();
        String processId = c.register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(first, "w1", 100)));

        HttpResponse<String> init = call("POST", "/mcp", STATIC_TOKEN);
        assertTrue(init.body().contains("first"));

        // A newer window appears and takes the default slot…
        FakeBackend second = startBackend("second");
        c.heartbeat(processId, STATIC_TOKEN, -1, List.of(reg(first, "w1", 100), reg(second, "w2", 999)));

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
    void failedDeleteKeepsSessionPinnedForRetry() throws Exception {
        FakeBackend backend = startBackend("first");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(backend, "w1", 100)));
        call("POST", "/mcp", STATIC_TOKEN); // pins session-of-first

        HttpResponse<String> failed = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp?status=500"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Mcp-Session-Id", "session-of-first")
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, failed.statusCode());

        HttpResponse<String> retry = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Mcp-Session-Id", "session-of-first")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, retry.statusCode());
        assertTrue(retry.body().contains("first"), "the failed DELETE remains retryable");
    }

    @Test
    void successfulDeleteUnpinsTheSession() throws Exception {
        FakeBackend backend = startBackend("first");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(backend, "w1", 100)));
        call("POST", "/mcp", STATIC_TOKEN); // pins session-of-first

        HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Mcp-Session-Id", "session-of-first")
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode());

        HttpResponse<String> retry = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Mcp-Session-Id", "session-of-first")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, retry.statusCode(), "a successful session close removes the pin");
    }

    @Test
    void brokerFileStoreDoesNotEvictClientsAtThePreferencesSizeLimit() {
        OAuthStore store = broker.oauthStore();
        String longUri = "http://localhost/callback/" + "x".repeat(400);
        for (int i = 0; i < 40; i++) {
            OAuthStore.Client registered = store.registerClient(List.of(longUri + i), "client-" + i);
            store.issueTokens(registered.clientId, "mcp", null);
        }
        assertEquals(40, store.listClients().size(),
                "oauth.json has no 8k java.util.prefs limit, so active clients stay registered");
    }

    @Test
    void internalClientRevocationAndSessionTerminationAreAuthenticatedAndExplicit() throws Exception {
        OAuthStore.Client registered = broker.oauthStore().registerClient(
                List.of("http://127.0.0.1/cb"), "review-client");
        broker.oauthStore().issueTokens(registered.clientId, "read", null);
        broker.registry().pinSession("oauth-session", "window",
                AuthenticatedPrincipal.oauth(registered.clientId, "review-client", "grant", "read"));

        List<BrokerClient.ClientInfo> listed = client().listClients();
        assertEquals(1, listed.size());
        assertEquals(registered.clientId, listed.get(0).clientId);
        assertEquals(java.util.Set.of("ontology:read"), listed.get(0).capabilities);
        assertTrue(listed.get(0).latestAccessExpiry > System.currentTimeMillis());

        HttpResponse<String> clients = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/internal/clients"))
                .header(InternalApiServlet.SECRET_HEADER, dirSecret).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, clients.statusCode());
        assertTrue(clients.body().contains(registered.clientId));
        assertEquals("[\"ontology:read\"]", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(clients.body()).path("clients").get(0).path("capabilities").toString());
        assertFalse(clients.body().contains("mcpa_"), "management output must never disclose tokens");

        HttpResponse<String> revoked = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/internal/revoke-client"))
                .header(InternalApiServlet.SECRET_HEADER, dirSecret)
                .POST(HttpRequest.BodyPublishers.ofString("{\"client_id\":\""
                        + registered.clientId + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, revoked.statusCode());
        assertTrue(revoked.body().contains("\"revoked\":true"));
        assertTrue(revoked.body().contains("\"terminated_session_pins\":1"));
        assertTrue(revoked.body().contains("\"terminated_in_flight_requests\":0"));
        assertTrue(revoked.body().contains("\"in_flight_termination\":false"));

        HttpResponse<String> wrongMethod = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/internal/revoke-client"))
                .header(InternalApiServlet.SECRET_HEADER, dirSecret).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, wrongMethod.statusCode());
        assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElse(null));
    }

    @Test
    void revokingAnOauthClientCutsOffItsLiveProxyStream() throws Exception {
        FakeBackend sse = startBackend("revoked-sse");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(sse, "w-revoked", 1)));
        OAuthStore.Client registered = broker.oauthStore().registerClient(
                List.of("http://127.0.0.1/revoked"), "stream-client");
        OAuthStore.Tokens tokens = broker.oauthStore().issueTokens(registered.clientId, "read", null);

        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/mcp?mode=sse"))
                .header("Authorization", "Bearer " + tokens.accessToken)
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals("data: first-from-revoked-sse", reader.readLine());
            assertEquals("", reader.readLine());

            BrokerClient.RevocationResult result = client().revokeClient(registered.clientId);
            assertTrue(result.revoked());
            assertEquals(1, result.terminatedInFlightRequests(),
                    "the management response must account for the stream it actively cancelled");
            assertTrue(result.commitFenceConfirmed());
            assertEquals(1, sse.fencedClients.get(),
                    "the broker must install a commit fence in the serving backend");

            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var eof = executor.submit(() -> {
                    try {
                        return reader.readLine() == null;
                    } catch (IOException closed) {
                        return true;
                    }
                });
                assertTrue(eof.get(3, TimeUnit.SECONDS),
                        "revocation must close the client stream before the backend emits another event");
            } finally {
                executor.shutdownNow();
            }
        } finally {
            sse.sseGate.countDown();
        }
        assertEquals(401, call("POST", "/mcp", tokens.accessToken).statusCode(),
                "the revoked token must not start a follow-up request");
    }

    @Test
    void revocationDoesNotClaimSuccessWithoutEveryBackendCommitFence() throws Exception {
        FakeBackend fenced = startBackend("fenced", true);
        FakeBackend unfenced = startBackend("unfenced", false);
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(fenced, "w-fenced", 2), reg(unfenced, "w-unfenced", 1)));
        OAuthStore.Client registered = broker.oauthStore().registerClient(
                List.of("http://127.0.0.1/unfenced"), "unfenced-client");
        OAuthStore.Tokens tokens = broker.oauthStore().issueTokens(registered.clientId, "read", null);

        IOException incomplete = assertThrows(IOException.class,
                () -> client().revokeClient(registered.clientId));
        assertTrue(incomplete.getMessage().contains("did not confirm the execution fence"));
        assertEquals(1, fenced.fencedClients.get());
        assertEquals(1, unfenced.fencedClients.get());
        assertEquals(401, call("POST", "/mcp", tokens.accessToken).statusCode(),
                "token invalidation remains effective even when backend confirmation is partial");

        unfenced.acknowledgeFences.set(true);
        BrokerClient.RevocationResult retried = client().revokeClient(registered.clientId);
        assertTrue(retried.revoked(), "retry must finish fencing after the credential is already gone");
        assertTrue(retried.commitFenceConfirmed());
        assertEquals(2, fenced.fencedClients.get(), "an idempotent retry reaffirms every live window");
        assertEquals(2, unfenced.fencedClients.get());
    }

    @Test
    void explicitInstancePathTargetsThatWindow() throws Exception {
        FakeBackend a = startBackend("aa");
        FakeBackend b = startBackend("bb");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1,
                List.of(reg(a, "w-a", 900), reg(b, "w-b", 100)));

        HttpResponse<String> resp = call("POST", "/instances/w-b/mcp", STATIC_TOKEN);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("bb"), "explicit path must override the focus default");

        assertEquals(404, call("POST", "/instances/no-such/mcp", STATIC_TOKEN).statusCode());
    }

    @Test
    void explicitInstancePathEnforcesSessionOwnership() throws Exception {
        FakeBackend a = startBackend("aa");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of(reg(a, "w-a", 1)));
        // A session pinned to a DIFFERENT authenticated client/grant.
        broker.registry().pinSession("foreign-session", "w-a",
                AuthenticatedPrincipal.oauthAdmin("other-client", "Other", "grant-z"));

        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/instances/w-a/mcp"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Mcp-Session-Id", "foreign-session")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(403, resp.statusCode(),
                "the explicit /instances route must reject another client's session id");
        assertTrue(resp.body().contains("session_principal_mismatch"),
                "the rejection must be the session-ownership guard, not a routing error");
    }

    @Test
    void explicitInstancePathRejectsAnUnpinnedSession() throws Exception {
        FakeBackend a = startBackend("aa");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of(reg(a, "w-a", 1)));

        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/instances/w-a/mcp"))
                .header("Authorization", "Bearer " + STATIC_TOKEN)
                .header("Mcp-Session-Id", "terminated-or-evicted-session")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode(),
                "an explicit route must not resurrect a session whose ownership pin is gone");
        assertTrue(resp.body().contains("session_window_closed"));
        assertFalse(a.lastHeaders.containsKey("mcp-session-id"),
                "the unowned session id must never reach the backend");
    }

    @Test
    void instancesListingShowsWindowsAndNeedsAuth() throws Exception {
        FakeBackend a = startBackend("aa");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of(reg(a, "w-a", 1)));

        assertEquals(401, call("GET", "/instances", null).statusCode());
        HttpResponse<String> resp = call("GET", "/instances", STATIC_TOKEN);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"/instances/w-a/mcp\""));
        assertTrue(resp.body().contains("\"default_mcp_path\":\"/mcp\""));
    }

    @Test
    void noRegisteredWindowsIs503ForAnAuthenticatedClient() throws Exception {
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of());
        HttpResponse<String> resp = call("POST", "/mcp", STATIC_TOKEN);
        assertEquals(503, resp.statusCode());
        assertTrue(resp.body().contains("no_instances"));
    }

    // ---- streaming --------------------------------------------------------------------------------

    @Test
    void sseEventsPassThroughTheProxyLive() throws Exception {
        FakeBackend sse = startBackend("sse");
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of(reg(sse, "w-sse", 1)));

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
        String processId = c.register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of());
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
    void reportedLingerReachesTheRegistryAndZeroMeansPromptExit() throws Exception {
        BrokerClient c = client();
        String processId = c.register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, 45_000,
                List.of());
        assertEquals(45_000, broker.registry().effectiveLingerMs(300),
                "register must deliver the user's linger preference to the broker");
        // Discriminating step: unregister under the 45 s override. The broker's own default here
        // is 300 ms, so if maintain() ignored the reported linger it would ask to exit within a
        // couple of ticks — staying alive proves the override is what the exit decision uses.
        c.unregister(processId);
        assertFalse(shutdownRequested.await(2_500, TimeUnit.MILLISECONDS),
                "with a reported 45 s linger the broker must outlive its 300 ms default");
        processId = c.register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, 45_000, List.of());
        c.heartbeat(processId, STATIC_TOKEN, 0, List.of());
        assertEquals(0, broker.registry().effectiveLingerMs(300),
                "a heartbeat must update a changed preference on the running broker");
        c.unregister(processId);
        assertTrue(shutdownRequested.await(5, TimeUnit.SECONDS),
                "linger 0: the broker must ask to exit on the first maintenance tick after emptying");
    }

    @Test
    void oldPayloadWithoutLingerLeavesTheBrokerDefaultInForce() throws Exception {
        // A pre-linger plugin registers with no "lingerMs" field (the -1 sentinel omits it): the
        // broker must keep whatever linger it already has, not misread the absence as 0.
        BrokerClient c = client();
        String processId = c.register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of());
        c.heartbeat(processId, STATIC_TOKEN, -1, List.of());
        assertEquals(300, broker.registry().effectiveLingerMs(300),
                "an absent lingerMs must leave the spawn-time default untouched");
    }

    @Test
    void maintenanceLoopSweepsTheBrokersOauthStore() throws Exception {
        // The rules themselves are unit-tested on OAuthStore; this pins the broker-side wiring:
        // the store the servlets use is the one maintain() sweeps, and a fresh registration
        // arriving over HTTP survives the sweep.
        client().register(ProcessHandle.current().pid(), "t", STATIC_TOKEN, -1, List.of());
        HttpRequest register = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + brokerPort + "/oauth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"client_name\":\"wired\",\"redirect_uris\":[\"http://127.0.0.1/cb\"]}"))
                .build();
        assertEquals(201, http.send(register, HttpResponse.BodyHandlers.ofString()).statusCode());
        Thread.sleep(1_500); // let at least one maintenance tick run its sweep
        assertEquals(1, broker.oauthStore().listClients().size(),
                "a fresh (in-grace) registration must survive the maintenance sweep");
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
