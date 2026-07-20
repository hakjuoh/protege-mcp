package io.github.hakjuoh.protege_mcp.server;

import java.io.IOException;

import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards the {@code /mcp} endpoint. A request must carry {@code Authorization: Bearer <token>} where
 * the token is either a live OAuth access token or the plugin's static bearer token (so manual
 * setups keep working). On a missing/invalid token it returns {@code 401} with a
 * {@code WWW-Authenticate} challenge pointing at the protected-resource metadata (RFC 9728), which
 * is what makes OAuth-capable clients (e.g. Claude) launch the browser authorization flow.
 *
 * <p>A broker-managed backend additionally accepts its own per-start broker secret (the
 * {@code X-Protege-Mcp-Broker} header): the shared broker authenticated the client itself and
 * attaches the secret when forwarding, so the backend does not have to re-validate OAuth tokens it
 * has never issued.
 */
public final class AccessTokenFilter implements Filter {

    private static final String PREFIX = "Bearer ";
    /** Must match {@code McpProxyServlet.BROKER_SECRET_HEADER} (kept literal — no broker import). */
    private static final String BROKER_SECRET_HEADER = "X-Protege-Mcp-Broker";

    private final OAuthStore store;
    private final java.util.function.Supplier<String> brokerSecret;

    public AccessTokenFilter(OAuthStore store) {
        this(store, () -> null);
    }

    /** @param brokerSecret live per-start broker secret; supplies null when not broker-managed. */
    public AccessTokenFilter(OAuthStore store, java.util.function.Supplier<String> brokerSecret) {
        this.store = store;
        this.brokerSecret = brokerSecret;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String header = httpRequest.getHeader("Authorization");
        String token = header != null && header.startsWith(PREFIX) ? header.substring(PREFIX.length()) : null;

        AuthenticatedPrincipal principal = null;
        if (isBrokerAuthenticated(httpRequest)) {
            String encoded = httpRequest.getHeader(AuthenticatedPrincipal.BROKER_HEADER);
            if (encoded == null) {
                // A pre-0.6.0 broker authenticates the client but forwards no principal header.
                // Mixed-version operation is supported, so accept it as a legacy broker identity;
                // a PRESENT but malformed header still denies below.
                principal = AuthenticatedPrincipal.legacyBroker();
            } else {
                try {
                    principal = AuthenticatedPrincipal.decode(encoded);
                } catch (IllegalArgumentException malformed) {
                    deny(httpRequest, httpResponse);
                    return;
                }
            }
        } else {
            OAuthStore.TokenIdentity identity = store.authenticate(token);
            if (identity != null) {
                try {
                    principal = identity.principal();
                } catch (IllegalArgumentException invalidScope) {
                    deny(httpRequest, httpResponse);
                    return;
                }
            }
        }
        if (principal != null) {
            httpRequest.setAttribute(AuthenticatedPrincipal.REQUEST_ATTRIBUTE, principal);
            chain.doFilter(request, response);
            return;
        }

        deny(httpRequest, httpResponse);
    }

    private static void deny(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
            throws IOException {

        String host = httpRequest.getHeader("Host");
        if (host == null || host.isEmpty()) {
            host = httpRequest.getServerName() + ":" + httpRequest.getServerPort();
        }
        String metadataUrl = httpRequest.getScheme() + "://" + host
                + "/.well-known/oauth-protected-resource";

        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setHeader("WWW-Authenticate",
                "Bearer resource_metadata=\"" + metadataUrl + "\", error=\"invalid_token\"");
        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write("{\"error\":\"invalid_token\"}");
    }

    private boolean isBrokerAuthenticated(HttpServletRequest request) {
        String expected = brokerSecret.get();
        String presented = request.getHeader(BROKER_SECRET_HEADER);
        return expected != null && presented != null && java.security.MessageDigest.isEqual(
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
