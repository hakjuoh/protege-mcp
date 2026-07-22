package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.tools.PrincipalExecutionGate;
import io.github.hakjuoh.protege_mcp.tools.ToolArgException;

class BrokerControlServletTest {

    @Test
    void secretAuthenticatedRevocationWaitsForActiveBackendExecution() throws Exception {
        String secret = "window-secret";
        PrincipalExecutionGate gate = new PrincipalExecutionGate();
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.oauthAdmin(
                "backend-client", "Backend", "backend-grant");
        EmbeddedHttpServer server = new EmbeddedHttpServer();
        AtomicInteger externalRevocations = new AtomicInteger();
        server.addServlet(new BrokerControlServlet(() -> secret, gate, clientId -> {
            assertEquals("backend-client", clientId);
            externalRevocations.incrementAndGet();
            return 2;
        }, (clientId, grantId) -> 0),
                BrokerControlServlet.PATH + "/*", false);
        int port = server.start(0);
        var active = gate.acquire(principal);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            HttpRequest unauthorized = request(port, "wrong");
            assertEquals(403, http.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode());

            var response = http.sendAsync(request(port, secret), HttpResponse.BodyHandlers.ofString());
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> response.get(100, TimeUnit.MILLISECONDS),
                    "the backend must not acknowledge a fence while old work can still commit");
            active.close();

            HttpResponse<String> fenced = response.get(2, TimeUnit.SECONDS);
            assertEquals(200, fenced.statusCode());
            assertTrue(fenced.body().contains("\"commit_fence_confirmed\":true"));
            assertTrue(fenced.body().contains("\"external_entries_revoked\":2"));
            assertEquals(1, externalRevocations.get());
            assertThrows(ToolArgException.class, () -> gate.acquire(principal));
        } finally {
            active.close();
            server.stop();
        }
    }

    @Test
    void grantRevocationFencesOnlyTheExactGrantAndClearsExternalState() throws Exception {
        String secret = "window-secret";
        PrincipalExecutionGate gate = new PrincipalExecutionGate();
        java.util.concurrent.atomic.AtomicReference<String> revoked =
                new java.util.concurrent.atomic.AtomicReference<>();
        EmbeddedHttpServer server = new EmbeddedHttpServer();
        server.addServlet(new BrokerControlServlet(() -> secret, gate, clientId -> 0,
                (clientId, grantId) -> {
                    revoked.set(clientId + ":" + grantId);
                    return 4;
                }), BrokerControlServlet.PATH + "/*", false);
        int port = server.start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + BrokerControlServlet.PATH + "/revoke-grant"))
                    .timeout(Duration.ofSeconds(3))
                    .header(BrokerControlServlet.BROKER_SECRET_HEADER, secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"client_id\":\"client\",\"grant_id\":\"grant-a\"}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"external_entries_revoked\":4"));
            assertEquals("client:grant-a", revoked.get());
            assertThrows(ToolArgException.class, () -> gate.acquire(
                    AuthenticatedPrincipal.oauthAdmin("client", "Client", "grant-a")));
            gate.acquire(AuthenticatedPrincipal.oauthAdmin(
                    "client", "Client", "grant-b")).close();
        } finally {
            server.stop();
        }
    }

    private static HttpRequest request(int port, String secret) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + BrokerControlServlet.PATH + "/revoke-client"))
                .timeout(Duration.ofSeconds(3))
                .header(BrokerControlServlet.BROKER_SECRET_HEADER, secret)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"client_id\":\"backend-client\"}"))
                .build();
    }
}
