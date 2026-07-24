package io.github.hakjuoh.protege_mcp.oauth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The embedded OAuth 2.1 authorization-server endpoints (mapped at {@code /oauth/*}):
 * <ul>
 *   <li>{@code POST /oauth/register} - Dynamic Client Registration (RFC 7591), public client.
 *   <li>{@code GET  /oauth/authorize} - renders a consent page; {@code POST} records the decision
 *       and redirects to the client's loopback callback with an authorization code (PKCE required).
 *   <li>{@code POST /oauth/token} - {@code authorization_code} (with PKCE verification) and
 *       {@code refresh_token} grants.
 *   <li>{@code POST /oauth/revoke} - Token Revocation (RFC 7009); drops a single access/refresh token.
 * </ul>
 */
public class OAuthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final transient OAuthStore store;
    private final transient ObjectMapper mapper;
    private final transient GrantRevoker grantRevoker;
    private final transient SecureRandom random = new SecureRandom();
    private final transient ConcurrentMap<String, AuthorizationTransaction> pendingAuthorizations =
            new ConcurrentHashMap<>();

    private static final long AUTHORIZATION_TRANSACTION_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_PENDING_AUTHORIZATIONS = 1024;
    private static final int MAX_REDIRECT_URIS = 16;
    private static final int MAX_REDIRECT_URI_LENGTH = 4096;
    private static final int MAX_CLIENT_NAME_LENGTH = 256;
    private static final int MAX_REGISTRATION_BODY_BYTES = 64 * 1024;
    private static final int MAX_CLIENT_ID_LENGTH = 256;
    private static final int MAX_CODE_LENGTH = 256;
    private static final int MAX_STATE_LENGTH = 2048;
    private static final int MAX_SCOPE_LENGTH = 4096;
    private static final int MAX_RESOURCE_LENGTH = 4096;
    private static final int MAX_CODE_CHALLENGE_LENGTH = 256;
    private static final int MAX_CODE_VERIFIER_LENGTH = 256;
    private static final int MAX_GRANT_TYPE_LENGTH = 32;
    private static final int MAX_CSRF_TOKEN_LENGTH = 256;
    private static final int MAX_TOKEN_LENGTH = 512;

    public OAuthServlet(OAuthStore store, ObjectMapper mapper, GrantRevoker grantRevoker) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.grantRevoker = java.util.Objects.requireNonNull(grantRevoker, "grantRevoker");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!OAuthSupport.requireLocal(req, resp, mapper)) {
            return;
        }
        if ("/authorize".equals(req.getPathInfo())) {
            handleAuthorizeGet(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!OAuthSupport.requireLocal(req, resp, mapper)) {
            return;
        }
        String path = req.getPathInfo();
        if ("/register".equals(path)) {
            handleRegister(req, resp);
        } else if ("/authorize".equals(path)) {
            handleAuthorizeDecision(req, resp);
        } else if ("/token".equals(path)) {
            handleToken(req, resp);
        } else if ("/revoke".equals(path)) {
            handleRevoke(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------ /register (RFC 7591)

    @SuppressWarnings("unchecked")
    private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> body;
        try {
            byte[] registrationBody = readBoundedBody(req.getInputStream(), MAX_REGISTRATION_BODY_BYTES);
            if (registrationBody == null) {
                OAuthSupport.writeError(resp, 400, "invalid_client_metadata",
                        "Registration metadata exceeds the size limit.", mapper);
                return;
            }
            body = mapper.readValue(registrationBody, Map.class);
        } catch (IOException e) {
            OAuthSupport.writeError(resp, 400, "invalid_client_metadata", "Body is not valid JSON.", mapper);
            return;
        }
        if (body == null) {
            OAuthSupport.writeError(resp, 400, "invalid_client_metadata", "Body must be a JSON object.", mapper);
            return;
        }
        List<String> redirectUris = new ArrayList<>();
        Object raw = body.get("redirect_uris");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (!(o instanceof String uri)) {
                    OAuthSupport.writeError(resp, 400, "invalid_redirect_uri",
                            "Every redirect_uri must be a string.", mapper);
                    return;
                }
                redirectUris.add(uri);
            }
        }
        if (redirectUris.isEmpty()) {
            OAuthSupport.writeError(resp, 400, "invalid_redirect_uri",
                    "At least one redirect_uri is required.", mapper);
            return;
        }
        if (redirectUris.size() > MAX_REDIRECT_URIS
                || redirectUris.stream().anyMatch(uri -> !validLoopbackRedirect(uri))) {
            OAuthSupport.writeError(resp, 400, "invalid_redirect_uri",
                    "Redirect URIs must be bounded loopback HTTP callbacks.", mapper);
            return;
        }
        Object rawClientName = body.get("client_name");
        String clientName = rawClientName == null ? "MCP client"
                : rawClientName instanceof String value ? value : null;
        if (clientName == null) {
            OAuthSupport.writeError(resp, 400, "invalid_client_metadata",
                    "client_name must be a string.", mapper);
            return;
        }
        if (clientName.isBlank() || clientName.length() > MAX_CLIENT_NAME_LENGTH) {
            OAuthSupport.writeError(resp, 400, "invalid_client_metadata",
                    "client_name must be between 1 and 256 characters.", mapper);
            return;
        }
        OAuthStore.Client client = store.registerClient(redirectUris, clientName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_id", client.clientId);
        out.put("client_id_issued_at", System.currentTimeMillis() / 1000L);
        out.put("client_name", client.clientName);
        out.put("redirect_uris", new ArrayList<>(client.redirectUris));
        out.put("token_endpoint_auth_method", "none");
        out.put("grant_types", java.util.Arrays.asList("authorization_code", "refresh_token"));
        out.put("response_types", java.util.Arrays.asList("code"));
        OAuthSupport.writeJson(resp, HttpServletResponse.SC_CREATED, out, mapper);
    }

    // ------------------------------------------------------------------ /authorize

    private void handleAuthorizeGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String clientId = req.getParameter("client_id");
        String redirectUri = req.getParameter("redirect_uri");
        String responseType = req.getParameter("response_type");
        String codeChallenge = req.getParameter("code_challenge");
        String codeChallengeMethod = req.getParameter("code_challenge_method");

        if (!bounded(clientId, MAX_CLIENT_ID_LENGTH)
                || !bounded(redirectUri, MAX_REDIRECT_URI_LENGTH)
                || !bounded(responseType, 32)
                || !bounded(codeChallenge, MAX_CODE_CHALLENGE_LENGTH)
                || !bounded(codeChallengeMethod, 16)) {
            errorPage(resp, "Authorization request parameters are oversized.");
            return;
        }
        OAuthStore.Client client = clientId == null ? null : store.client(clientId);
        if (client == null || !client.allowsRedirect(redirectUri)) {
            // Never redirect to an unverified URI - show an error page instead.
            errorPage(resp, "Unknown client or unregistered redirect URI.");
            return;
        }
        // The consent phase carries no token and no code yet; without this touch the inactivity
        // sweep cannot tell a user reading the consent page from an abandoned registration.
        store.noteClientActivity(clientId);
        if (!"code".equals(responseType)) {
            redirectError(resp, redirectUri, "unsupported_response_type", safeState(req));
            return;
        }
        if (codeChallenge == null || !"S256".equals(codeChallengeMethod)) {
            redirectError(resp, redirectUri, "invalid_request", safeState(req));
            return;
        }
        if (!bounded(req.getParameter("state"), MAX_STATE_LENGTH)
                || !bounded(req.getParameter("scope"), MAX_SCOPE_LENGTH)
                || !bounded(req.getParameter("resource"), MAX_RESOURCE_LENGTH)) {
            redirectError(resp, redirectUri, "invalid_request", null);
            return;
        }
        if (!validScope(req.getParameter("scope"))) {
            redirectError(resp, redirectUri, "invalid_scope", safeState(req));
            return;
        }
        long expiresAt = System.currentTimeMillis() + AUTHORIZATION_TRANSACTION_TTL_MS;
        String csrfToken;
        synchronized (pendingAuthorizations) {
            long now = System.currentTimeMillis();
            pendingAuthorizations.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
            if (pendingAuthorizations.size() >= MAX_PENDING_AUTHORIZATIONS) {
                errorPage(resp, "Too many authorization requests are pending; try again shortly.");
                return;
            }
            csrfToken = randomToken();
            pendingAuthorizations.put(csrfToken, AuthorizationTransaction.from(req, expiresAt));
        }
        consentPage(resp, req, client, csrfToken);
    }

    private void handleAuthorizeDecision(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String csrfToken = req.getParameter("csrf_token");
        if (!bounded(csrfToken, MAX_CSRF_TOKEN_LENGTH)) {
            errorPage(resp, "The authorization form is missing, expired or was modified.");
            return;
        }
        AuthorizationTransaction transaction = csrfToken == null ? null
                : pendingAuthorizations.remove(csrfToken);
        if (transaction == null || transaction.expiresAt < System.currentTimeMillis()
                || !transaction.matches(req)) {
            errorPage(resp, "The authorization form is missing, expired or was modified.");
            return;
        }

        String clientId = transaction.clientId;
        String redirectUri = transaction.redirectUri;
        String state = transaction.state;

        OAuthStore.Client client = clientId == null ? null : store.client(clientId);
        if (client == null || !client.allowsRedirect(redirectUri)) {
            errorPage(resp, "Unknown client or unregistered redirect URI.");
            return;
        }
        store.noteClientActivity(clientId);
        if (!"allow".equals(req.getParameter("decision"))) {
            redirectError(resp, redirectUri, "access_denied", state);
            return;
        }
        String code = store.newAuthCode(clientId, redirectUri, transaction.codeChallenge,
                transaction.scope, transaction.resource);
        StringBuilder url = new StringBuilder(redirectUri);
        url.append(redirectUri.contains("?") ? '&' : '?');
        url.append("code=").append(enc(code));
        if (state != null) {
            url.append("&state=").append(enc(state));
        }
        resp.sendRedirect(url.toString());
    }

    // ------------------------------------------------------------------ /token

    private void handleToken(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String grantType = req.getParameter("grant_type");
        if (!bounded(grantType, MAX_GRANT_TYPE_LENGTH)) {
            OAuthSupport.writeError(resp, 400, "unsupported_grant_type",
                    "The requested grant type is not supported.", mapper);
            return;
        }
        OAuthStore.Tokens tokens;
        if ("authorization_code".equals(grantType)) {
            if (!bounded(req.getParameter("code"), MAX_CODE_LENGTH)
                    || !bounded(req.getParameter("client_id"), MAX_CLIENT_ID_LENGTH)
                    || !bounded(req.getParameter("redirect_uri"), MAX_REDIRECT_URI_LENGTH)
                    || !bounded(req.getParameter("code_verifier"), MAX_CODE_VERIFIER_LENGTH)) {
                OAuthSupport.writeError(resp, 400, "invalid_grant",
                        "Authorization code parameters are oversized.", mapper);
                return;
            }
            OAuthStore.AuthCode authCode = store.redeemAuthCode(req.getParameter("code"),
                    req.getParameter("client_id"), req.getParameter("redirect_uri"),
                    req.getParameter("code_verifier"));
            if (authCode == null) {
                OAuthSupport.writeError(resp, 400, "invalid_grant",
                        "Authorization code, client, redirect URI or PKCE verifier did not match.", mapper);
                return;
            }
            tokens = store.issueTokens(authCode.clientId, authCode.scope, authCode.resource);
        } else if ("refresh_token".equals(grantType)) {
            if (!bounded(req.getParameter("refresh_token"), MAX_TOKEN_LENGTH)) {
                OAuthSupport.writeError(resp, 400, "invalid_grant", "Refresh token is oversized.", mapper);
                return;
            }
            tokens = store.refresh(req.getParameter("refresh_token"));
            if (tokens == null) {
                OAuthSupport.writeError(resp, 400, "invalid_grant", "Unknown refresh token.", mapper);
                return;
            }
        } else {
            OAuthSupport.writeError(resp, 400, "unsupported_grant_type",
                    "The requested grant type is not supported.", mapper);
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", tokens.accessToken);
        out.put("token_type", "Bearer");
        out.put("expires_in", tokens.expiresInSeconds);
        out.put("refresh_token", tokens.refreshToken);
        if (tokens.scope != null) {
            out.put("scope", tokens.scope);
        }
        OAuthSupport.writeJson(resp, HttpServletResponse.SC_OK, out, mapper);
    }

    // ------------------------------------------------------------------ /revoke (RFC 7009)

    private void handleRevoke(HttpServletRequest req, HttpServletResponse resp) {
        // Public client, single user: accept the token and drop it. RFC 7009 mandates a 200 response
        // whether or not the token was recognised, so unknown/expired tokens are not distinguished.
        String token = req.getParameter("token");
        if (!bounded(token, MAX_TOKEN_LENGTH)) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader("Cache-Control", "no-store");
            return;
        }
        try {
            OAuthStore.RevokedGrant revoked = store.revokeTokenGrant(token,
                    identity -> grantRevoker.prepare(identity.clientId(), identity.grantId()));
            if (revoked != null) {
                grantRevoker.revoke(revoked.clientId(), revoked.grantId());
            }
            resp.setStatus(HttpServletResponse.SC_OK);
        } catch (RuntimeException unavailable) {
            // A write-ahead fence that cannot be made durable must not be reported as revoked.
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        resp.setHeader("Cache-Control", "no-store");
    }

    @FunctionalInterface
    public interface GrantRevoker {
        default void prepare(String clientId, String grantId) { }

        void revoke(String clientId, String grantId);
    }

    // ------------------------------------------------------------------ HTML + helpers

    private void consentPage(HttpServletResponse resp, HttpServletRequest req, OAuthStore.Client client,
            String csrfToken)
            throws IOException {
        Set<String> requested = AuthenticatedPrincipal.capabilitiesForScope(
                req.getParameter("scope"));
        boolean canChange = requested.stream().anyMatch(capability ->
                capability.equals("ontology:curate") || capability.equals("ontology:admin")
                        || capability.equals("ontology:release")
                        || capability.equals("filesystem:project:write")
                        || capability.equals("filesystem:external")
                        || capability.equals("network:access")
                        || capability.equals("server:admin")
                        || capability.equals(AuthenticatedPrincipal.LOCAL_ADMIN_CAPABILITY));
        String[][] hidden = {
                {"client_id", req.getParameter("client_id")},
                {"redirect_uri", req.getParameter("redirect_uri")},
                {"response_type", req.getParameter("response_type")},
                {"code_challenge", req.getParameter("code_challenge")},
                {"code_challenge_method", req.getParameter("code_challenge_method")},
                {"state", req.getParameter("state")},
                {"scope", req.getParameter("scope")},
                {"resource", req.getParameter("resource")},
        };
        StringBuilder inputs = new StringBuilder();
        for (String[] kv : hidden) {
            if (kv[1] != null) {
                inputs.append("<input type=\"hidden\" name=\"").append(kv[0]).append("\" value=\"")
                        .append(OAuthSupport.htmlEscape(kv[1])).append("\"/>");
            }
        }
        inputs.append("<input type=\"hidden\" name=\"csrf_token\" value=\"")
                .append(OAuthSupport.htmlEscape(csrfToken)).append("\"/>");
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>protege-mcp authorization</title><style>"
                + "body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#f5f5f7;"
                + "display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}"
                + ".card{background:#fff;border-radius:12px;padding:32px 36px;max-width:440px;"
                + "box-shadow:0 8px 30px rgba(0,0,0,.12)}h1{font-size:18px;margin:0 0 6px}"
                + "p{color:#444;font-size:14px;line-height:1.5}.who{font-weight:600}"
                + ".row{display:flex;gap:12px;margin-top:24px}button{flex:1;padding:11px;border:0;"
                + "border-radius:8px;font-size:14px;cursor:pointer}.allow{background:#2563eb;color:#fff}"
                + ".deny{background:#e5e5ea;color:#111}</style></head><body><div class=\"card\">"
                + "<h1>Authorize access to Protege</h1>"
                + "<p><span class=\"who\">" + OAuthSupport.htmlEscape(client.clientName) + "</span> wants to "
                + (canChange ? "read or change" : "read")
                + " the ontology through the MCP server on this machine.</p>"
                + "<p>Requested scope: <code>" + OAuthSupport.htmlEscape(
                        req.getParameter("scope") == null ? AuthenticatedPrincipal.LEGACY_FULL_SCOPE
                                : req.getParameter("scope")) + "</code></p>"
                + "<form method=\"post\" action=\"/oauth/authorize\">" + inputs
                + "<div class=\"row\"><button class=\"deny\" name=\"decision\" value=\"deny\">Deny</button>"
                + "<button class=\"allow\" name=\"decision\" value=\"allow\">Allow</button></div></form>"
                + "</div></body></html>";
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html;charset=utf-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'");
        resp.getWriter().write(html);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean validLoopbackRedirect(String redirectUri) {
        if (redirectUri == null || redirectUri.length() > MAX_REDIRECT_URI_LENGTH) {
            return false;
        }
        try {
            URI uri = new URI(redirectUri);
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && ("localhost".equalsIgnoreCase(host)
                            || "127.0.0.1".equals(host)
                            || "[::1]".equals(host)
                            || "::1".equals(host));
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    private record AuthorizationTransaction(String clientId, String redirectUri, String responseType,
            String codeChallenge, String codeChallengeMethod, String state, String scope, String resource,
            long expiresAt) {

        static AuthorizationTransaction from(HttpServletRequest req, long expiresAt) {
            return new AuthorizationTransaction(req.getParameter("client_id"),
                    req.getParameter("redirect_uri"), req.getParameter("response_type"),
                    req.getParameter("code_challenge"), req.getParameter("code_challenge_method"),
                    req.getParameter("state"), req.getParameter("scope"), req.getParameter("resource"),
                    expiresAt);
        }

        boolean matches(HttpServletRequest req) {
            return equal(clientId, req.getParameter("client_id"))
                    && equal(redirectUri, req.getParameter("redirect_uri"))
                    && equal(responseType, req.getParameter("response_type"))
                    && equal(codeChallenge, req.getParameter("code_challenge"))
                    && equal(codeChallengeMethod, req.getParameter("code_challenge_method"))
                    && equal(state, req.getParameter("state"))
                    && equal(scope, req.getParameter("scope"))
                    && equal(resource, req.getParameter("resource"));
        }

        private static boolean equal(String left, String right) {
            return java.util.Objects.equals(left, right);
        }
    }

    private static boolean validScope(String scope) {
        try {
            AuthenticatedPrincipal.capabilitiesForScope(scope);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void errorPage(HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        resp.setContentType("text/html;charset=utf-8");
        resp.getWriter().write("<!doctype html><html><body style=\"font-family:sans-serif;padding:40px\">"
                + "<h2>Authorization error</h2><p>" + OAuthSupport.htmlEscape(message) + "</p></body></html>");
    }

    private void redirectError(HttpServletResponse resp, String redirectUri, String error, String state)
            throws IOException {
        StringBuilder url = new StringBuilder(redirectUri);
        url.append(redirectUri.contains("?") ? '&' : '?').append("error=").append(enc(error));
        if (state != null) {
            url.append("&state=").append(enc(state));
        }
        resp.sendRedirect(url.toString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean bounded(String value, int maximum) {
        return value == null || value.length() <= maximum;
    }

    private static String safeState(HttpServletRequest req) {
        String state = req.getParameter("state");
        return bounded(state, MAX_STATE_LENGTH) ? state : null;
    }

    /** Reads within the allowed memory bound and rejects an oversized body before JSON parsing. */
    private static byte[] readBoundedBody(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return body.toByteArray();
            }
            if (count == 0) {
                int value = input.read();
                if (value < 0) {
                    return body.toByteArray();
                }
                if (++total > maximumBytes) {
                    return null;
                }
                body.write(value);
                continue;
            }
            total += count;
            if (total > maximumBytes) {
                return null;
            }
            body.write(buffer, 0, count);
        }
    }
}
