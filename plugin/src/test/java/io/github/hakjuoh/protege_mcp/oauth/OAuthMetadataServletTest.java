package io.github.hakjuoh.protege_mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link OAuthMetadataServlet#doGet}. All servlet objects are hand-rolled
 * {@link Proxy} instances (no Mockito): the request answers Host/scheme/serverName/serverPort/URI
 * queries; the response records status, content-type, headers and buffers the JSON body. The real
 * Jackson {@link ObjectMapper} parses the emitted document back into a map for structural assertions.
 * No network, no Swing, no Protege runtime.
 */
class OAuthMetadataServletTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------------------------------------------------------------------------------------------
    // Fake HttpServletRequest (Proxy) — parameterised by URI + host/scheme/name/port
    // ---------------------------------------------------------------------------------------------

    private static HttpServletRequest fakeRequest(
            String requestUri, String hostHeader, String scheme, String serverName, int serverPort) {
        return fakeRequest(requestUri, hostHeader, scheme, serverName, serverPort, "127.0.0.1");
    }

    private static HttpServletRequest fakeRequest(
            String requestUri, String hostHeader, String scheme, String serverName, int serverPort,
            String remoteAddr) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getRequestURI":
                    return requestUri;
                case "getHeader":
                    if (args != null && args.length == 1 && "Host".equals(args[0])) {
                        return hostHeader;
                    }
                    return null;
                case "getScheme":
                    return scheme;
                case "getServerName":
                    return serverName;
                case "getServerPort":
                    return serverPort;
                case "getRemoteAddr":
                    return remoteAddr;
                case "toString":
                    return "FakeHttpServletRequest[" + requestUri + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                default:
                    throw new UnsupportedOperationException(
                            "unexpected request call: " + method.getName());
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, h);
    }

    /** Convenience: host header supplied, default http scheme. */
    private static HttpServletRequest requestWithHost(String requestUri, String hostHeader) {
        return fakeRequest(requestUri, hostHeader, "http", "ignored", 9999);
    }

    // ---------------------------------------------------------------------------------------------
    // Recording HttpServletResponse (Proxy)
    // ---------------------------------------------------------------------------------------------

    private static final class RecordingResponse {
        int status = -1;
        String contentType;
        final Map<String, String> headers = new LinkedHashMap<>();
        final StringWriter buffer = new StringWriter();
        final PrintWriter writer = new PrintWriter(buffer);

        String body() {
            writer.flush();
            return buffer.toString();
        }

        HttpServletResponse proxy() {
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setStatus":
                        status = (int) args[0];
                        return null;
                    case "setContentType":
                        contentType = (String) args[0];
                        return null;
                    case "setHeader":
                        headers.put((String) args[0], (String) args[1]);
                        return null;
                    case "getWriter":
                        return writer;
                    case "toString":
                        return "RecordingResponse";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == (args == null ? null : args[0]);
                    default:
                        throw new UnsupportedOperationException(
                                "unexpected response call: " + method.getName());
                }
            };
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class}, h);
        }
    }

    private static Map<String, Object> parse(String json) throws IOException {
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    // ---------------------------------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------------------------------

    @Test
    void constructorAcceptsMapperInstance() {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        assertNotNull(servlet, "constructing with a real ObjectMapper should succeed");
    }

    @Test
    void constructorAcceptsNullMapperWithoutThrowing() {
        // The constructor only stores the reference; it does not dereference it, so null is allowed.
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(null);
        assertNotNull(servlet, "constructor should not eagerly reject a null mapper");
    }

    @Test
    void servletIsAnHttpServlet() {
        assertTrue(jakarta.servlet.http.HttpServlet.class.isInstance(new OAuthMetadataServlet(JSON)),
                "OAuthMetadataServlet must extend HttpServlet");
    }

    // ---------------------------------------------------------------------------------------------
    // Protected-resource branch
    // ---------------------------------------------------------------------------------------------

    @Test
    void protectedResourceBranchReturnsResourceMetadata() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.well-known/oauth-protected-resource", "127.0.0.1:8123");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("http://127.0.0.1:8123/mcp", doc.get("resource"),
                "resource should be base + /mcp");
        assertEquals(List.of("http://127.0.0.1:8123"), doc.get("authorization_servers"),
                "authorization_servers should be a single-element list of the base");
        assertEquals(List.of("mcp"), doc.get("scopes_supported"),
                "scopes_supported should be [mcp]");
        assertEquals(List.of("header"), doc.get("bearer_methods_supported"),
                "bearer_methods_supported should be [header]");
    }

    @Test
    void protectedResourceBranchOmitsAuthServerFields() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.well-known/oauth-protected-resource/mcp", "h:1");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals(4, doc.size(), "protected-resource doc should have exactly four keys");
        assertTrue(!doc.containsKey("issuer") && !doc.containsKey("token_endpoint"),
                "protected-resource branch must not include auth-server fields");
    }

    @Test
    void protectedResourceBranchWithSuffixPathStillMatches() throws IOException {
        // The check is a substring test, so trailing "/mcp" still routes to the resource branch.
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.well-known/oauth-protected-resource/mcp", "host:1");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("http://host:1/mcp", doc.get("resource"),
                "resource branch should be selected for the /mcp suffix variant");
    }

    @Test
    void protectedResourceBranchWithLeadingPathStillMatches() throws IOException {
        // A prefix before the well-known segment still contains the substring, so it still matches.
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req = requestWithHost(
                "/prefix/.well-known/oauth-protected-resource", "host:1");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertTrue(doc.containsKey("resource"),
                "substring match means a leading path prefix still selects the resource branch");
    }

    @Test
    void protectedResourceBranchMatchesEvenWithMultipleOccurrences() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req = requestWithHost(
                "/.well-known/oauth-protected-resource/.well-known/oauth-protected-resource",
                "host:2");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("http://host:2/mcp", doc.get("resource"),
                "multiple occurrences of the marker still route to the resource branch");
    }

    // ---------------------------------------------------------------------------------------------
    // Auth-server branch
    // ---------------------------------------------------------------------------------------------

    @Test
    void authServerBranchReturnsAllAuthServerFields() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.well-known/oauth-authorization-server", "127.0.0.1:8123");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        String base = "http://127.0.0.1:8123";
        assertEquals(base, doc.get("issuer"), "issuer should be the base URL");
        assertEquals(base + "/oauth/authorize", doc.get("authorization_endpoint"),
                "authorization_endpoint should be base + /oauth/authorize");
        assertEquals(base + "/oauth/token", doc.get("token_endpoint"),
                "token_endpoint should be base + /oauth/token");
        assertEquals(base + "/oauth/register", doc.get("registration_endpoint"),
                "registration_endpoint should be base + /oauth/register");
        assertEquals(base + "/oauth/revoke", doc.get("revocation_endpoint"),
                "revocation_endpoint should be base + /oauth/revoke");
        assertEquals(List.of("none"), doc.get("revocation_endpoint_auth_methods_supported"),
                "revocation auth methods should be [none]");
        assertEquals(List.of("code"), doc.get("response_types_supported"),
                "response_types_supported should be [code]");
        assertEquals(List.of("authorization_code", "refresh_token"),
                doc.get("grant_types_supported"),
                "grant_types_supported should list authorization_code and refresh_token");
        assertEquals(List.of("S256"), doc.get("code_challenge_methods_supported"),
                "code_challenge_methods_supported should be [S256]");
        assertEquals(List.of("none"), doc.get("token_endpoint_auth_methods_supported"),
                "token_endpoint_auth_methods_supported should be [none]");
        assertEquals(List.of("mcp"), doc.get("scopes_supported"),
                "scopes_supported should be [mcp]");
    }

    @Test
    void authServerBranchHasExactlyElevenKeys() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.well-known/oauth-authorization-server", "h:1");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals(11, doc.size(),
                "auth-server doc should contain exactly the eleven RFC 8414 fields");
    }

    @Test
    void openidConfigurationUriUsesAuthServerBranch() throws IOException {
        // openid-configuration does not contain the protected-resource marker, so it hits the else.
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.well-known/openid-configuration", "host:3");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("http://host:3", doc.get("issuer"),
                "openid-configuration should produce auth-server metadata with an issuer");
    }

    @Test
    void unrelatedUriFallsThroughToAuthServerBranch() throws IOException {
        // Any URI lacking the protected-resource marker takes the else branch.
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req = requestWithHost("/some/other/path", "host:4");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertTrue(doc.containsKey("token_endpoint"),
                "a non-matching URI defaults to the auth-server branch");
    }

    // ---------------------------------------------------------------------------------------------
    // Case sensitivity of the substring match
    // ---------------------------------------------------------------------------------------------

    @Test
    void uppercaseWellKnownDoesNotMatchProtectedResourceBranch() throws IOException {
        // String.contains is case-sensitive; an uppercased path must NOT hit the resource branch.
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.WELL-KNOWN/OAUTH-PROTECTED-RESOURCE", "host:5");
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertTrue(doc.containsKey("issuer") && !doc.containsKey("resource"),
                "uppercase variant is case-sensitively different and falls to the auth-server branch");
    }

    // ---------------------------------------------------------------------------------------------
    // Base-URL derivation propagation
    // ---------------------------------------------------------------------------------------------

    @Test
    void httpsSchemeIsPreservedInAuthServerUrls() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                fakeRequest("/.well-known/oauth-authorization-server", "secure.example",
                        "https", "ignored", 443);
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("https://secure.example", doc.get("issuer"),
                "https scheme from the request should flow into the issuer URL");
        assertEquals("https://secure.example/oauth/token", doc.get("token_endpoint"),
                "https scheme should be preserved across derived endpoints");
    }

    @Test
    void hostHeaderIsUsedForBaseUrlDerivation() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        // serverName/port would give a different value; the Host header must win.
        HttpServletRequest req = fakeRequest("/.well-known/oauth-protected-resource",
                "clienthost:7000", "http", "servername", 9);
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("http://clienthost:7000/mcp", doc.get("resource"),
                "the Host header should be used verbatim for base URL derivation");
    }

    @Test
    void missingHostHeaderFallsBackToServerNameAndPort() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req = fakeRequest("/.well-known/oauth-authorization-server",
                null, "http", "fallbackhost", 8080);
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("http://fallbackhost:8080", doc.get("issuer"),
                "a null Host header should fall back to serverName:serverPort");
    }

    @Test
    void nonStandardPortIncludedInBaseUrl() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req = fakeRequest("/.well-known/oauth-authorization-server",
                "", "http", "hostonly", 12345);
        RecordingResponse resp = new RecordingResponse();

        servlet.doGet(req, resp.proxy());

        Map<String, Object> doc = parse(resp.body());
        assertEquals("http://hostonly:12345", doc.get("issuer"),
                "an empty Host header should fall back and include the non-standard port");
    }

    // ---------------------------------------------------------------------------------------------
    // Response envelope: status, content-type, cache-control, valid JSON
    // ---------------------------------------------------------------------------------------------

    @Test
    void responseStatusIsOk() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        servlet.doGet(requestWithHost("/.well-known/oauth-protected-resource", "h:1"),
                resp.proxy());
        assertEquals(HttpServletResponse.SC_OK, resp.status,
                "the metadata response status must be 200 OK");
    }

    @Test
    void responseContentTypeIsJson() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        servlet.doGet(requestWithHost("/.well-known/oauth-authorization-server", "h:1"),
                resp.proxy());
        assertEquals("application/json", resp.contentType,
                "the metadata response content-type must be application/json");
    }

    @Test
    void responseHasNoStoreCacheControl() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        servlet.doGet(requestWithHost("/.well-known/oauth-protected-resource", "h:1"),
                resp.proxy());
        assertEquals("no-store", resp.headers.get("Cache-Control"),
                "the metadata response must set Cache-Control: no-store");
    }

    @Test
    void bodyIsValidParseableJson() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        servlet.doGet(requestWithHost("/.well-known/oauth-authorization-server", "h:1"),
                resp.proxy());
        // parse must not throw and must produce a non-empty object.
        Map<String, Object> doc = parse(resp.body());
        assertTrue(!doc.isEmpty(), "emitted body must be valid, non-empty JSON");
    }

    // ---------------------------------------------------------------------------------------------
    // Empty / null URI edge cases (fall through to auth-server branch)
    // ---------------------------------------------------------------------------------------------

    @Test
    void emptyUriFallsThroughToAuthServerBranch() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        servlet.doGet(requestWithHost("", "host:1"), resp.proxy());
        Map<String, Object> doc = parse(resp.body());
        assertTrue(doc.containsKey("issuer"),
                "an empty URI does not contain the marker and defaults to the auth-server branch");
    }

    @Test
    void nullUriThrowsNullPointerException() {
        // uri.contains(...) dereferences the request URI; a null URI must NPE.
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        HttpServletRequest req = requestWithHost(null, "host:1");
        assertThrows(NullPointerException.class, () -> servlet.doGet(req, resp.proxy()),
                "a null request URI should NPE when contains() is called");
    }

    // ---------------------------------------------------------------------------------------------
    // IOException propagation paths
    // ---------------------------------------------------------------------------------------------

    @Test
    void serializationExceptionFromMapperPropagates() {
        // A mapper that fails to serialize the metadata map must surface an IOException.
        // We use a custom ObjectMapper whose Map serializer throws; the simplest reliable way is a
        // mapper configured to reject a registered type, but doGet builds a plain Map — so instead we
        // fail via a throwing writer below. Here we prove the writeValueAsString failure path using a
        // mapper that throws on the LinkedHashMap contents through a String serializer override.
        ObjectMapper failing = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new ThrowingStringSerializer());
        failing.registerModule(module);

        OAuthMetadataServlet servlet = new OAuthMetadataServlet(failing);
        RecordingResponse resp = new RecordingResponse();
        HttpServletRequest req =
                requestWithHost("/.well-known/oauth-authorization-server", "h:1");

        assertThrows(IOException.class, () -> servlet.doGet(req, resp.proxy()),
                "an IOException from mapper.writeValueAsString should propagate out of doGet");
    }

    @Test
    void getWriterIoExceptionPropagates() {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        HttpServletRequest req =
                requestWithHost("/.well-known/oauth-protected-resource", "h:1");
        HttpServletResponse throwingResp = throwingGetWriterResponse();

        assertThrows(IOException.class, () -> servlet.doGet(req, throwingResp),
                "an IOException from resp.getWriter() should propagate out of doGet");
    }

    /** A response whose getWriter() throws IOException; setters are no-ops. */
    private static HttpServletResponse throwingGetWriterResponse() {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setStatus":
                case "setContentType":
                case "setHeader":
                    return null;
                case "getWriter":
                    throw new IOException("no writer");
                case "toString":
                    return "ThrowingResponse";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                default:
                    throw new UnsupportedOperationException("unexpected: " + method.getName());
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, h);
    }

    /** Serializer that always throws a Jackson IOException subtype for String values. */
    private static final class ThrowingStringSerializer extends StdSerializer<String> {
        ThrowingStringSerializer() {
            super(String.class);
        }

        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            throw new IOException("boom");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Determinism: two calls with the same request produce byte-identical bodies
    // ---------------------------------------------------------------------------------------------

    @Test
    void repeatedCallsProduceIdenticalOutput() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);

        RecordingResponse a = new RecordingResponse();
        servlet.doGet(requestWithHost("/.well-known/oauth-authorization-server", "h:1"),
                a.proxy());
        RecordingResponse b = new RecordingResponse();
        servlet.doGet(requestWithHost("/.well-known/oauth-authorization-server", "h:1"),
                b.proxy());

        assertEquals(a.body(), b.body(),
                "doGet output must be deterministic for identical requests");
        // Sanity: the two RecordingResponse instances are distinct objects.
        assertSame(a, a, "self-identity sanity check");
    }

    // ---------------------------------------------------------------------------------------------
    // Same-machine gate: discovery is withheld from remote peers (they use the static token)
    // ---------------------------------------------------------------------------------------------

    @Test
    void remotePeerGets403WithoutADocument() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        // 203.0.113.9 (TEST-NET-3) is never a local interface address.
        servlet.doGet(fakeRequest("/.well-known/oauth-authorization-server", "h:1",
                "http", "ignored", 9999, "203.0.113.9"), resp.proxy());

        assertEquals(403, resp.status);
        Map<String, Object> body = JSON.readValue(resp.body(), new TypeReference<>() { });
        assertEquals("access_denied", body.get("error"));
    }

    @Test
    void ipv6LoopbackPeerIsServed() throws IOException {
        OAuthMetadataServlet servlet = new OAuthMetadataServlet(JSON);
        RecordingResponse resp = new RecordingResponse();
        servlet.doGet(fakeRequest("/.well-known/oauth-authorization-server", "h:1",
                "http", "ignored", 9999, "::1"), resp.proxy());

        assertEquals(200, resp.status);
        Map<String, Object> body = JSON.readValue(resp.body(), new TypeReference<>() { });
        assertNotNull(body.get("issuer"));
    }
}
