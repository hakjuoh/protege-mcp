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
        server.addServlet(new BrokerControlServlet(() -> secret, gate),
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
            assertThrows(ToolArgException.class, () -> gate.acquire(principal));
        } finally {
            active.close();
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
