package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.CharArrayWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link AccessTokenFilter}.
 *
 * <p>{@code OAuthStore} is {@code final}, so validity is controlled deterministically through its
 * public static-bearer-token seam: a real store is built with a fixed static token supplier, and
 * {@code isValidAccessToken} returns true exactly for that token (constant-time compared) and false
 * otherwise. Servlet request/response/chain are hand-rolled via {@link Proxy} — no Mockito.
 */
final class AccessTokenFilterTest {

    private static final String VALID_TOKEN = "static-bearer-secret";

    // ------------------------------------------------------------------ store helpers

    /** A real store whose only accepted token is {@link #VALID_TOKEN} (via the static-token seam). */
    private static OAuthStore storeAcceptingValidToken() {
        return new OAuthStore(() -> VALID_TOKEN, () -> null, s -> { });
    }

    /** A real store that accepts no token at all (static token is null). */
    private static OAuthStore storeAcceptingNothing() {
        return new OAuthStore(() -> null, () -> null, s -> { });
    }

    // ------------------------------------------------------------------ fakes

    /** Captured state of the fake HttpServletResponse. */
    private static final class ResponseCapture {
        Integer status;
        final Map<String, String> headers = new LinkedHashMap<>();
        String contentType;
        final CharArrayWriter body = new CharArrayWriter();
        final PrintWriter writer = new PrintWriter(body);

        String bodyString() {
            writer.flush();
            return body.toString();
        }
    }

    /** Records whether the chain was invoked and with which request/response. */
    private static final class ChainCapture {
        boolean invoked;
        ServletRequest passedRequest;
        ServletResponse passedResponse;
    }

    /**
     * Builds a fake HttpServletRequest via Proxy. Only the methods the filter calls are supported;
     * anything else throws so an untested code path is caught rather than silently no-op.
     */
    private static HttpServletRequest request(Map<String, String> headers, String scheme,
            String serverName, int serverPort) {
        Map<String, String> lower = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            lower.put(e.getKey().toLowerCase(), e.getValue());
        }
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getHeader":
                    return lower.get(((String) args[0]).toLowerCase());
                case "getScheme":
                    return scheme;
                case "getServerName":
                    return serverName;
                case "getServerPort":
                    return serverPort;
                case "toString":
                    return "FakeHttpServletRequest";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("unexpected request call: " + method.getName());
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                AccessTokenFilterTest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, h);
    }

    private static HttpServletResponse response(ResponseCapture cap) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setStatus":
                    cap.status = (Integer) args[0];
                    return null;
                case "setHeader":
                    cap.headers.put((String) args[0], (String) args[1]);
                    return null;
                case "setContentType":
                    cap.contentType = (String) args[0];
                    return null;
                case "getWriter":
                    return cap.writer;
                case "toString":
                    return "FakeHttpServletResponse";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("unexpected response call: " + method.getName());
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                AccessTokenFilterTest.class.getClassLoader(),
                new Class<?>[] { HttpServletResponse.class }, h);
    }

    private static FilterChain chain(ChainCapture cap) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("doFilter".equals(method.getName())) {
                cap.invoked = true;
                cap.passedRequest = (ServletRequest) args[0];
                cap.passedResponse = (ServletResponse) args[1];
                return null;
            }
            if ("toString".equals(method.getName())) {
                return "FakeFilterChain";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            throw new UnsupportedOperationException("unexpected chain call: " + method.getName());
        };
        return (FilterChain) Proxy.newProxyInstance(
                AccessTokenFilterTest.class.getClassLoader(),
                new Class<?>[] { FilterChain.class }, h);
    }

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ constructor

    @Test
    void constructorAcceptsNullStoreWithoutThrowing() {
        // The constructor merely stores the reference; it must not eagerly dereference it.
        AccessTokenFilter filter = new AccessTokenFilter(null);
        assertNotNull(filter, "constructor should not fail fast on a null store");
    }

    @Test
    void nullStoreFailsOnlyWhenDoFilterUsesIt() {
        AccessTokenFilter filter = new AccessTokenFilter(null);
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer x", "Host", "h:1"),
                "http", "h", 1);
        assertThrows(NullPointerException.class,
                () -> filter.doFilter(req, response(rc), chain(new ChainCapture())),
                "doFilter must dereference the store, so a null store throws NPE here");
    }

    // ------------------------------------------------------------------ valid-token path

    @Test
    void validBearerTokenInvokesChainAndReturnsEarly() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer " + VALID_TOKEN),
                "http", "localhost", 8080);

        filter.doFilter(req, response(rc), chain(cc));

        assertTrue(cc.invoked, "chain.doFilter must be called for a valid token");
        assertNull(rc.status, "no 401 status should be set when the token is valid");
        assertTrue(rc.headers.isEmpty(), "no WWW-Authenticate header should be set for a valid token");
    }

    @Test
    void validTokenPassesOriginalRequestAndResponseToChain() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer " + VALID_TOKEN),
                "http", "localhost", 8080);
        HttpServletResponse resp = response(rc);

        filter.doFilter(req, resp, chain(cc));

        assertTrue(cc.invoked, "chain must be invoked");
        assertTrue(cc.passedRequest == req, "the original request object must be forwarded to the chain");
        assertTrue(cc.passedResponse == resp, "the original response object must be forwarded to the chain");
    }

    // ------------------------------------------------------------------ 401 unauthorized paths

    private void assertUnauthorized(ResponseCapture rc, ChainCapture cc, String expectedMetadataUrl) {
        assertFalse(cc.invoked, "chain.doFilter must NOT be called for an invalid token");
        assertEquals(Integer.valueOf(HttpServletResponse.SC_UNAUTHORIZED), rc.status,
                "status must be 401");
        assertEquals("application/json", rc.contentType, "content type must be application/json");
        assertEquals("{\"error\":\"invalid_token\"}", rc.bodyString(),
                "body must be the RFC-9728 invalid_token JSON");
        assertEquals("Bearer resource_metadata=\"" + expectedMetadataUrl + "\", error=\"invalid_token\"",
                rc.headers.get("WWW-Authenticate"),
                "WWW-Authenticate challenge must point at the protected-resource metadata");
    }

    @Test
    void validPrefixButUnknownTokenReturns401() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer wrong-token", "Host", "example.org:9000"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://example.org:9000/.well-known/oauth-protected-resource");
    }

    @Test
    void missingAuthorizationHeaderReturns401() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Host", "h:1"), "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://h:1/.well-known/oauth-protected-resource");
    }

    @Test
    void headerNotStartingWithBearerPrefixReturns401() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        // Basic auth scheme, not Bearer -> token parsed as null -> invalid.
        HttpServletRequest req = request(headers("Authorization", "Basic " + VALID_TOKEN, "Host", "h:1"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://h:1/.well-known/oauth-protected-resource");
    }

    @Test
    void bearerWordAloneWithoutTrailingSpaceReturns401() throws Exception {
        // "Bearer" (7 chars) does not start with "Bearer " (8 chars) -> token is null.
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer", "Host", "h:1"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://h:1/.well-known/oauth-protected-resource");
    }

    @Test
    void bearerPrefixWithEmptyTokenReturns401() throws Exception {
        // "Bearer " -> token is the empty string, which the store rejects.
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer ", "Host", "h:1"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://h:1/.well-known/oauth-protected-resource");
    }

    @Test
    void bearerPrefixCaseSensitiveLowercaseReturns401() throws Exception {
        // "bearer " != "Bearer " -> prefix does not match -> token null -> 401.
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "bearer " + VALID_TOKEN, "Host", "h:1"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://h:1/.well-known/oauth-protected-resource");
    }

    @Test
    void tokenComparisonIsCaseSensitive() throws Exception {
        // Same token but different case must not validate (constant-time exact compare).
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingValidToken());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(
                headers("Authorization", "Bearer " + VALID_TOKEN.toUpperCase(), "Host", "h:1"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://h:1/.well-known/oauth-protected-resource");
    }

    @Test
    void storeAcceptingNothingRejectsEvenWellFormedBearer() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingNothing());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer " + VALID_TOKEN, "Host", "h:1"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://h:1/.well-known/oauth-protected-resource");
    }

    // ------------------------------------------------------------------ metadata-URL / host construction

    @Test
    void hostHeaderUsedVerbatimInMetadataUrl() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingNothing());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(
                headers("Authorization", "Bearer x", "Host", "my.host:1234"),
                "https", "should-not-be-used", 99);

        filter.doFilter(req, response(rc), chain(cc));

        // Host header wins over serverName/serverPort; scheme still comes from getScheme().
        assertUnauthorized(rc, cc, "https://my.host:1234/.well-known/oauth-protected-resource");
    }

    @Test
    void nullHostHeaderFallsBackToServerNameAndPort() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingNothing());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer x"),
                "http", "fallback.example", 7777);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://fallback.example:7777/.well-known/oauth-protected-resource");
    }

    @Test
    void emptyHostHeaderFallsBackToServerNameAndPort() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingNothing());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer x", "Host", ""),
                "http", "fallback.example", 7777);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "http://fallback.example:7777/.well-known/oauth-protected-resource");
    }

    @Test
    void schemeIsTakenFromGetSchemeForMetadataUrl() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingNothing());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer x", "Host", "h:8"),
                "https", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertUnauthorized(rc, cc, "https://h:8/.well-known/oauth-protected-resource");
    }

    // ------------------------------------------------------------------ granular assertions

    @Test
    void statusHeaderContentTypeAndBodyAllSetOn401() throws Exception {
        AccessTokenFilter filter = new AccessTokenFilter(storeAcceptingNothing());
        ChainCapture cc = new ChainCapture();
        ResponseCapture rc = new ResponseCapture();
        HttpServletRequest req = request(headers("Authorization", "Bearer x", "Host", "h:1"),
                "http", "ignored", 0);

        filter.doFilter(req, response(rc), chain(cc));

        assertEquals(Integer.valueOf(401), rc.status, "SC_UNAUTHORIZED is 401");
        assertEquals("application/json", rc.contentType, "content type");
        assertEquals("{\"error\":\"invalid_token\"}", rc.bodyString(), "JSON body");
        assertNotNull(rc.headers.get("WWW-Authenticate"), "challenge header must be present");
    }
}
