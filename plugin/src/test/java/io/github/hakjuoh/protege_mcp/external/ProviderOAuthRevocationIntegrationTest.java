package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.oauth.OAuthServlet;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

class ProviderOAuthRevocationIntegrationTest {

    @Test
    void rfc7009RevocationErasesExactGrantCursors() throws Exception {
        OAuthStore oauth = new OAuthStore(() -> "static-token", () -> null, state -> { });
        OAuthStore.Client client = oauth.registerClient(
                List.of("http://127.0.0.1/callback"), "cursor-client");
        OAuthStore.Tokens tokens = oauth.issueTokens(client.clientId, "read", null);
        OAuthStore.TokenIdentity identity = oauth.authenticate(tokens.accessToken);
        ProviderSessionScope scope = new ProviderSessionScope(
                "oauth", identity.clientId(), identity.grantId(), "workspace");
        ProviderCursorStore cursors = new ProviderCursorStore();
        String cursor = cursors.issue(scope, new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, "provider-next"));
        EmbeddedHttpServer server = new EmbeddedHttpServer();
        server.addServlet(new OAuthServlet(oauth, new ObjectMapper(),
                (clientId, grantId) -> cursors.revokeGrant(clientId, grantId)),
                "/oauth/*", false);
        int port = server.start(0);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + port + "/oauth/revoke"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("token=" + URLEncoder.encode(
                            tokens.accessToken, StandardCharsets.UTF_8)))
                    .build();

            assertEquals(200, HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                    () -> cursors.claim(scope, cursor)).code());
        } finally {
            server.stop();
            cursors.close();
        }
    }
}
