package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-HTTP proof of the RECEIVING end of the broker-principal trust boundary: the window backend's
 * {@link AccessTokenFilter} broker-secret gate, mounted on {@link EmbeddedHttpServer} exactly as the
 * live server wires it (filter + transport servlet both at {@code /mcp/*}; see
 * {@code McpServerController} lines 161-162). {@link AccessTokenFilterTest} already pins these same
 * decisions method-level via {@code java.lang.reflect.Proxy}; this fixture re-proves them over the
 * wire through Jetty so a co-resident process that never learned the per-start broker secret cannot
 * inject a forged {@code X-Protege-Mcp-Principal} envelope into the backend.
 *
 * <p>The complement — the broker itself stripping forged principal / internal headers and refusing
 * cross-principal session replay — lives in {@code BrokerWiredTest}; only this receiving hop was
 * missing over real HTTP.
 */
final class BackendPrincipalTrustWiredTest {

    /** The per-start secret the broker attaches when forwarding; the trust anchor for the backend. */
    private static final String BROKER_SECRET = "window-broker-secret";

    /**
     * The broker-secret header. Mirrored as a literal exactly like {@link AccessTokenFilterTest}
     * because {@code AccessTokenFilter.BROKER_SECRET_HEADER} is private (see AccessTokenFilter.java
     * line 31: {@code "X-Protege-Mcp-Broker"}).
     */
    private static final String BROKER_SECRET_HEADER = "X-Protege-Mcp-Broker";

    private final AtomicInteger backendHits = new AtomicInteger();
    private EmbeddedHttpServer server;
    private int port;
    private HttpClient http;

    @BeforeEach
    void startBackend() throws Exception {
        // A store that accepts no bearer token at all, so the ONLY way past the filter is a valid
        // broker envelope (mirrors AccessTokenFilterTest#storeAcceptingNothing).
        OAuthStore store = new OAuthStore(() -> null, () -> null, s -> { });
        server = new EmbeddedHttpServer();
        server.addFilter(new AccessTokenFilter(store, () -> BROKER_SECRET), "/mcp/*");
        server.addServlet(new EchoPrincipalServlet(backendHits), "/mcp/*", false);
        port = server.start(0);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stopBackend() {
        if (server != null) {
            server.stop();
        }
    }

    // Case A: co-resident spoof with NO broker secret is rejected and never reaches the backend.
    @Test
    void forgedPrincipalWithoutBrokerSecretIsRejectedAndNeverReachesBackend() throws Exception {
        AuthenticatedPrincipal forged =
                AuthenticatedPrincipal.oauthAdmin("attacker", "Attacker", "grant-x");
        HttpRequest request = post()
                .header(AuthenticatedPrincipal.BROKER_HEADER, forged.encode())
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(),
                "a forged principal with no broker secret must be denied (SC_UNAUTHORIZED)");
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").contains("Bearer"),
                "the 401 must carry the RFC-9728 Bearer challenge");
        assertEquals(0, backendHits.get(),
                "the backend servlet must never run for an untrusted forged envelope");
    }

    // Case B: forged principal presented with the WRONG broker secret is rejected (over the wire
    // twin of AccessTokenFilterTest#wrongBrokerSecretWithForgedPrincipalIsRejected).
    @Test
    void forgedPrincipalWithWrongBrokerSecretIsRejected() throws Exception {
        AuthenticatedPrincipal forged =
                AuthenticatedPrincipal.oauthAdmin("attacker", "Attacker", "grant-x");
        HttpRequest request = post()
                .header(BROKER_SECRET_HEADER, "wrong-secret")
                .header(AuthenticatedPrincipal.BROKER_HEADER, forged.encode())
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(),
                "a wrong broker secret fails the constant-time gate, so the store (which accepts no "
                        + "token) denies the request");
        assertEquals(0, backendHits.get(),
                "a wrong-secret forgery must never reach the backend servlet");
    }

    // Case C: the correct broker secret is the trust anchor BY DESIGN — the backend trusts the
    // envelope the broker forwarded. Returning 200 here is correct, not a forgery.
    @Test
    void correctBrokerSecretMakesBackendTrustTheForwardedEnvelope() throws Exception {
        // Forward a NON-admin (read-only) grant so the capability oracle below is discriminating:
        // oauthAdmin's LOCAL_ADMIN set equals the legacy/static-admin default, so if the receiving hop
        // ever re-minted the principal as admin the assertion could not tell. A read-only grant means
        // echoed[1] must be exactly ontology:read, and any escalation on decode would flip it and fail.
        AuthenticatedPrincipal forwarded =
                AuthenticatedPrincipal.oauth("codex-client", "Codex", "grant-1", "read");
        HttpRequest request = post()
                .header(BROKER_SECRET_HEADER, BROKER_SECRET)
                .header(AuthenticatedPrincipal.BROKER_HEADER, forwarded.encode())
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(),
                "a valid broker secret + envelope is trusted by design");
        assertEquals(1, backendHits.get(), "the backend servlet must run for a trusted envelope");

        AuthenticatedPrincipal decoded = AuthenticatedPrincipal.decode(forwarded.encode());
        String[] echoed = response.body().split("\n", 2);
        assertEquals(decoded.clientId(), echoed[0],
                "the request attribute the servlet saw must be the DECODED envelope's client_id");
        assertEquals(capabilities(decoded), echoed[1],
                "the request attribute the servlet saw must carry the DECODED envelope's capabilities");
    }

    // Case D: mixed-version legacy broker — correct secret, NO principal header — is trusted as the
    // legacy-broker identity (over the wire twin of the legacy-broker method-level test).
    @Test
    void correctBrokerSecretWithoutPrincipalHeaderIsTrustedAsLegacyBroker() throws Exception {
        HttpRequest request = post()
                .header(BROKER_SECRET_HEADER, BROKER_SECRET)
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(),
                "a pre-0.6.0 broker (valid secret, no principal header) stays supported");
        assertEquals(1, backendHits.get(), "the backend servlet must run for the legacy broker");

        String[] echoed = response.body().split("\n", 2);
        assertEquals(AuthenticatedPrincipal.legacyBroker().clientId(), echoed[0],
                "a legacy broker request must surface the synthetic legacy-broker identity");
    }

    private HttpRequest.Builder post() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
    }

    private static String capabilities(AuthenticatedPrincipal principal) {
        return principal.capabilities().stream().sorted().collect(Collectors.joining(","));
    }

    /**
     * Reflects the {@link AuthenticatedPrincipal} the filter placed on the request (its client_id on
     * line 1, sorted comma-joined capabilities on line 2) and counts every invocation so a test can
     * prove the servlet was NOT reached when the filter denied the request.
     */
    private static final class EchoPrincipalServlet extends HttpServlet {

        private final AtomicInteger hits;

        EchoPrincipalServlet(AtomicInteger hits) {
            this.hits = hits;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            hits.incrementAndGet();
            AuthenticatedPrincipal principal = (AuthenticatedPrincipal) req.getAttribute(
                    AuthenticatedPrincipal.REQUEST_ATTRIBUTE);
            resp.setStatus(200);
            resp.setContentType("text/plain");
            if (principal == null) {
                resp.getWriter().write("NO_PRINCIPAL\n");
                return;
            }
            resp.getWriter().write(principal.clientId() + "\n" + capabilities(principal));
        }
    }
}
