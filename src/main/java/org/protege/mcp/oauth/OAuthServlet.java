package org.protege.mcp.oauth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The embedded OAuth 2.1 authorization-server endpoints (mapped at {@code /oauth/*}):
 * <ul>
 *   <li>{@code POST /oauth/register} — Dynamic Client Registration (RFC 7591), public client.
 *   <li>{@code GET  /oauth/authorize} — renders a consent page; {@code POST} records the decision
 *       and redirects to the client's loopback callback with an authorization code (PKCE required).
 *   <li>{@code POST /oauth/token} — {@code authorization_code} (with PKCE verification) and
 *       {@code refresh_token} grants.
 *   <li>{@code POST /oauth/revoke} — Token Revocation (RFC 7009); drops a single access/refresh token.
 * </ul>
 */
public class OAuthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final transient OAuthStore store;
    private final transient ObjectMapper mapper;

    public OAuthServlet(OAuthStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if ("/authorize".equals(req.getPathInfo())) {
            handleAuthorizeGet(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
            body = mapper.readValue(req.getInputStream(), Map.class);
        } catch (IOException e) {
            OAuthSupport.writeError(resp, 400, "invalid_client_metadata", "Body is not valid JSON.", mapper);
            return;
        }
        List<String> redirectUris = new ArrayList<>();
        Object raw = body.get("redirect_uris");
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o != null) {
                    redirectUris.add(String.valueOf(o));
                }
            }
        }
        if (redirectUris.isEmpty()) {
            OAuthSupport.writeError(resp, 400, "invalid_redirect_uri",
                    "At least one redirect_uri is required.", mapper);
            return;
        }
        String clientName = body.get("client_name") == null ? "MCP client"
                : String.valueOf(body.get("client_name"));
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

        OAuthStore.Client client = clientId == null ? null : store.client(clientId);
        if (client == null || !client.allowsRedirect(redirectUri)) {
            // Never redirect to an unverified URI — show an error page instead.
            errorPage(resp, "Unknown client or unregistered redirect URI.");
            return;
        }
        if (!"code".equals(responseType)) {
            redirectError(resp, redirectUri, "unsupported_response_type", req.getParameter("state"));
            return;
        }
        if (codeChallenge == null || !"S256".equals(codeChallengeMethod)) {
            redirectError(resp, redirectUri, "invalid_request", req.getParameter("state"));
            return;
        }
        consentPage(resp, req, client);
    }

    private void handleAuthorizeDecision(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String clientId = req.getParameter("client_id");
        String redirectUri = req.getParameter("redirect_uri");
        String state = req.getParameter("state");

        OAuthStore.Client client = clientId == null ? null : store.client(clientId);
        if (client == null || !client.allowsRedirect(redirectUri)) {
            errorPage(resp, "Unknown client or unregistered redirect URI.");
            return;
        }
        if (!"allow".equals(req.getParameter("decision"))) {
            redirectError(resp, redirectUri, "access_denied", state);
            return;
        }
        String code = store.newAuthCode(clientId, redirectUri, req.getParameter("code_challenge"),
                req.getParameter("scope"), req.getParameter("resource"));
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
        OAuthStore.Tokens tokens;
        if ("authorization_code".equals(grantType)) {
            OAuthStore.AuthCode authCode = store.consumeAuthCode(req.getParameter("code"));
            if (authCode == null
                    || !authCode.clientId.equals(req.getParameter("client_id"))
                    || !authCode.redirectUri.equals(req.getParameter("redirect_uri"))
                    || !PkceUtil.verifyS256(req.getParameter("code_verifier"), authCode.codeChallenge)) {
                OAuthSupport.writeError(resp, 400, "invalid_grant",
                        "Authorization code, client, redirect URI or PKCE verifier did not match.", mapper);
                return;
            }
            tokens = store.issueTokens(authCode.clientId, authCode.scope, authCode.resource);
        } else if ("refresh_token".equals(grantType)) {
            tokens = store.refresh(req.getParameter("refresh_token"));
            if (tokens == null) {
                OAuthSupport.writeError(resp, 400, "invalid_grant", "Unknown refresh token.", mapper);
                return;
            }
        } else {
            OAuthSupport.writeError(resp, 400, "unsupported_grant_type", grantType, mapper);
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
        store.revokeToken(req.getParameter("token"));
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setHeader("Cache-Control", "no-store");
    }

    // ------------------------------------------------------------------ HTML + helpers

    private void consentPage(HttpServletResponse resp, HttpServletRequest req, OAuthStore.Client client)
            throws IOException {
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
                + "<h1>Authorize access to Protégé</h1>"
                + "<p><span class=\"who\">" + OAuthSupport.htmlEscape(client.clientName) + "</span> wants to "
                + "read and edit the ontology currently open in Protégé via the MCP server on this "
                + "machine. Edits appear in the GUI and can be undone.</p>"
                + "<form method=\"post\" action=\"/oauth/authorize\">" + inputs
                + "<div class=\"row\"><button class=\"deny\" name=\"decision\" value=\"deny\">Deny</button>"
                + "<button class=\"allow\" name=\"decision\" value=\"allow\">Allow</button></div></form>"
                + "</div></body></html>";
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html;charset=utf-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(html);
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
}
