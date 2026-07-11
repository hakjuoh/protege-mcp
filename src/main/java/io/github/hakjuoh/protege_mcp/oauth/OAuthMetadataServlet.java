package io.github.hakjuoh.protege_mcp.oauth;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves OAuth discovery documents (mapped at {@code /.well-known/*}):
 * <ul>
 *   <li>{@code /.well-known/oauth-protected-resource[/mcp]} — RFC 9728 protected-resource metadata
 *   <li>{@code /.well-known/oauth-authorization-server} and {@code /.well-known/openid-configuration}
 *       — RFC 8414 authorization-server metadata
 * </ul>
 * The MCP server and the authorization server share the same localhost origin, so the issuer and
 * the resource are derived from the request the client used.
 */
public class OAuthMetadataServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final transient ObjectMapper mapper;

    public OAuthMetadataServlet(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Same-machine only, like /oauth/* itself: a remote client that cannot use the flow should
        // not discover it either — its 401 handling then falls straight through to the static
        // bearer token instead of dead-ending in a forbidden authorization attempt.
        if (!OAuthSupport.requireLocal(req, resp, mapper)) {
            return;
        }
        String base = OAuthSupport.baseUrl(req);
        String uri = req.getRequestURI();
        Map<String, Object> doc = new LinkedHashMap<>();

        if (uri.contains("/.well-known/oauth-protected-resource")) {
            doc.put("resource", base + "/mcp");
            doc.put("authorization_servers", Arrays.asList(base));
            doc.put("scopes_supported", Arrays.asList("mcp"));
            doc.put("bearer_methods_supported", Arrays.asList("header"));
        } else {
            // oauth-authorization-server / openid-configuration
            doc.put("issuer", base);
            doc.put("authorization_endpoint", base + "/oauth/authorize");
            doc.put("token_endpoint", base + "/oauth/token");
            doc.put("registration_endpoint", base + "/oauth/register");
            doc.put("revocation_endpoint", base + "/oauth/revoke");
            doc.put("revocation_endpoint_auth_methods_supported", Arrays.asList("none"));
            doc.put("response_types_supported", Arrays.asList("code"));
            doc.put("grant_types_supported", Arrays.asList("authorization_code", "refresh_token"));
            doc.put("code_challenge_methods_supported", Arrays.asList("S256"));
            doc.put("token_endpoint_auth_methods_supported", Arrays.asList("none"));
            doc.put("scopes_supported", Arrays.asList("mcp"));
        }
        OAuthSupport.writeJson(resp, HttpServletResponse.SC_OK, doc, mapper);
    }
}
