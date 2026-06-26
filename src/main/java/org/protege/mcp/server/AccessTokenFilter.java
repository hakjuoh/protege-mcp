package org.protege.mcp.server;

import java.io.IOException;

import org.protege.mcp.oauth.OAuthStore;

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
 */
public final class AccessTokenFilter implements Filter {

    private static final String PREFIX = "Bearer ";

    private final OAuthStore store;

    public AccessTokenFilter(OAuthStore store) {
        this.store = store;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String header = httpRequest.getHeader("Authorization");
        String token = header != null && header.startsWith(PREFIX) ? header.substring(PREFIX.length()) : null;

        if (store.isValidAccessToken(token)) {
            chain.doFilter(request, response);
            return;
        }

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
}
