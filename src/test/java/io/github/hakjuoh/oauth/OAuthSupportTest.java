package io.github.hakjuoh.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link OAuthSupport}. All fakes are hand-rolled: the request is a
 * {@link Proxy} over {@link HttpServletRequest} backed by a header/property map, and the response is
 * a minimal {@link Proxy} over {@link HttpServletResponse} that records status/content-type/headers
 * and hands back a {@link StringWriter}-backed {@link PrintWriter}. No network, no Swing, no Protege
 * runtime.
 */
class OAuthSupportTest {

    // ---------------------------------------------------------------------------------------------
    // Fake HttpServletRequest (Proxy)
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a proxy HttpServletRequest that answers getHeader/getScheme/getServerName/getServerPort
     * from the supplied values and throws for everything else.
     */
    private static HttpServletRequest fakeRequest(
            String hostHeader, String scheme, String serverName, int serverPort) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
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
                case "toString":
                    return "FakeHttpServletRequest";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                default:
                    throw new UnsupportedOperationException("unexpected request call: "
                            + method.getName());
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, h);
    }

    // ---------------------------------------------------------------------------------------------
    // Fake HttpServletResponse (Proxy) that records what OAuthSupport touches
    // ---------------------------------------------------------------------------------------------

    /** Records the mutations OAuthSupport performs so tests can assert them. */
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
                        throw new UnsupportedOperationException("unexpected response call: "
                                + method.getName());
                }
            };
            return (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class}, h);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // baseUrl
    // ---------------------------------------------------------------------------------------------

    @Test
    void baseUrlUsesHostHeaderWhenPresent() {
        HttpServletRequest req = fakeRequest("127.0.0.1:8123", "http", "ignored", 9999);
        assertEquals("http://127.0.0.1:8123", OAuthSupport.baseUrl(req),
                "Host header should be used verbatim with the request scheme");
    }

    @Test
    void baseUrlPreservesHttpsScheme() {
        HttpServletRequest req = fakeRequest("example.com", "https", "ignored", 1);
        assertEquals("https://example.com", OAuthSupport.baseUrl(req),
                "scheme should be taken from the request");
    }

    @Test
    void baseUrlFallsBackToServerNameAndPortWhenHostNull() {
        HttpServletRequest req = fakeRequest(null, "http", "myhost", 8080);
        assertEquals("http://myhost:8080", OAuthSupport.baseUrl(req),
                "null Host header should fall back to serverName:serverPort");
    }

    @Test
    void baseUrlFallsBackToServerNameAndPortWhenHostEmpty() {
        HttpServletRequest req = fakeRequest("", "https", "srv", 443);
        assertEquals("https://srv:443", OAuthSupport.baseUrl(req),
                "empty Host header should fall back to serverName:serverPort");
    }

    @Test
    void baseUrlIncludesPortInHostHeaderPath() {
        HttpServletRequest req = fakeRequest("host:5555", "http", "x", 1);
        assertEquals("http://host:5555", OAuthSupport.baseUrl(req),
                "port present in Host header should be preserved");
    }

    @Test
    void baseUrlThrowsNpeOnNullRequest() {
        assertThrows(NullPointerException.class, () -> OAuthSupport.baseUrl(null),
                "null request should NPE when dereferenced");
    }

    // ---------------------------------------------------------------------------------------------
    // writeJson
    // ---------------------------------------------------------------------------------------------

    @Test
    void writeJsonSetsStatusContentTypeAndCacheControl() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("k", "v");
        OAuthSupport.writeJson(resp.proxy(), 201, body, new ObjectMapper());

        assertEquals(201, resp.status, "status should be set");
        assertEquals("application/json", resp.contentType, "content type should be application/json");
        assertEquals("no-store", resp.headers.get("Cache-Control"),
                "Cache-Control should be no-store");
    }

    @Test
    void writeJsonSerializesBodyToWriter() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("a", 1);
        body.put("b", "two");
        OAuthSupport.writeJson(resp.proxy(), 200, body, new ObjectMapper());

        assertEquals("{\"a\":1,\"b\":\"two\"}", resp.body(),
                "body should be JSON-serialized and written");
    }

    @Test
    void writeJsonSerializesNullBodyAsJsonNull() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeJson(resp.proxy(), 200, null, new ObjectMapper());
        assertEquals("null", resp.body(), "null body should serialize to the JSON literal null");
        assertEquals(200, resp.status, "status should still be set for a null body");
    }

    @Test
    void writeJsonSerializesNestedObjects() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("x", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nested", inner);
        OAuthSupport.writeJson(resp.proxy(), 200, body, new ObjectMapper());
        assertEquals("{\"nested\":{\"x\":true}}", resp.body(),
                "nested maps should be serialized recursively");
    }

    @Test
    void writeJsonAcceptsNon200Status() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeJson(resp.proxy(), 500, java.util.Collections.emptyMap(),
                new ObjectMapper());
        assertEquals(500, resp.status, "non-200 status codes should pass through");
        assertEquals("{}", resp.body(), "empty map serializes to {}");
    }

    @Test
    void writeJsonPropagatesSerializationException() {
        RecordingResponse resp = new RecordingResponse();
        // A mapper whose serializer for Unserializable throws a JsonProcessingException (an
        // IOException subtype) so writeValueAsString fails.
        ObjectMapper failing = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Unserializable.class, new ThrowingSerializer());
        failing.registerModule(module);

        assertThrows(IOException.class,
                () -> OAuthSupport.writeJson(resp.proxy(), 200, new Unserializable(), failing),
                "an IOException from mapper.writeValueAsString should propagate");
    }

    /** Marker type that our failing mapper is configured to reject. */
    private static final class Unserializable {
    }

    /** Serializer that always throws a Jackson IOException subtype. */
    private static final class ThrowingSerializer extends StdSerializer<Unserializable> {
        ThrowingSerializer() {
            super(Unserializable.class);
        }

        @Override
        public void serialize(Unserializable value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            throw new IOException("boom");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // writeError
    // ---------------------------------------------------------------------------------------------

    @Test
    void writeErrorIncludesDescriptionWhenNonNull() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeError(resp.proxy(), 400, "invalid_request", "missing param",
                new ObjectMapper());
        assertEquals("{\"error\":\"invalid_request\",\"error_description\":\"missing param\"}",
                resp.body(), "both error and error_description should be present and ordered");
        assertEquals(400, resp.status, "status should be forwarded to writeJson");
    }

    @Test
    void writeErrorOmitsDescriptionWhenNull() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeError(resp.proxy(), 401, "unauthorized", null, new ObjectMapper());
        assertEquals("{\"error\":\"unauthorized\"}", resp.body(),
                "null description should be omitted from the body");
    }

    @Test
    void writeErrorIncludesEmptyDescriptionWhenEmptyString() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeError(resp.proxy(), 400, "e", "", new ObjectMapper());
        // Empty string is non-null so the key IS present (the source only null-checks).
        assertEquals("{\"error\":\"e\",\"error_description\":\"\"}", resp.body(),
                "empty (non-null) description should still be included as an empty string");
    }

    @Test
    void writeErrorSetsJsonHeadersAndStatus() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeError(resp.proxy(), 403, "forbidden", "no", new ObjectMapper());
        assertEquals(403, resp.status, "status should be set via writeJson");
        assertEquals("application/json", resp.contentType, "content type should be JSON");
        assertEquals("no-store", resp.headers.get("Cache-Control"),
                "Cache-Control should be no-store");
    }

    @Test
    void writeErrorPreservesErrorFirstOrdering() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeError(resp.proxy(), 400, "ERR", "DESC", new ObjectMapper());
        String out = resp.body();
        assertTrue(out.indexOf("\"error\"") < out.indexOf("\"error_description\""),
                "error must be serialized before error_description (LinkedHashMap ordering)");
    }

    @Test
    void writeErrorSerializesNullErrorValue() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeError(resp.proxy(), 400, null, "d", new ObjectMapper());
        assertEquals("{\"error\":null,\"error_description\":\"d\"}", resp.body(),
                "a null error string serializes to the JSON literal null");
    }

    @Test
    void writeErrorDoesNotHtmlEscapeSpecialCharacters() throws IOException {
        RecordingResponse resp = new RecordingResponse();
        OAuthSupport.writeError(resp.proxy(), 400, "e", "a<b>&\"c", new ObjectMapper());
        // JSON serialization escapes the quote (\") but does NOT html-escape < > &.
        assertEquals("{\"error\":\"e\",\"error_description\":\"a<b>&\\\"c\"}", resp.body(),
                "writeError delegates to JSON serialization, not HTML escaping");
    }

    @Test
    void writeErrorPropagatesIoException() {
        // writeError delegates to writeJson, so an IOException from the response writer must surface.
        HttpServletResponse throwingWriterResp = throwingGetWriterResponse();
        assertThrows(IOException.class,
                () -> OAuthSupport.writeError(throwingWriterResp, 400, "e", "d", new ObjectMapper()),
                "an IOException surfacing from writeJson should propagate through writeError");
    }

    // ---------------------------------------------------------------------------------------------
    // IOException propagation from getWriter()
    // ---------------------------------------------------------------------------------------------

    @Test
    void writeJsonPropagatesGetWriterIoException() {
        HttpServletResponse resp = throwingGetWriterResponse();
        assertThrows(IOException.class,
                () -> OAuthSupport.writeJson(resp, 200, java.util.Collections.emptyMap(),
                        new ObjectMapper()),
                "an IOException from getWriter() should propagate");
    }

    /** A response whose getWriter() throws IOException; other setters are no-ops. */
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

    // ---------------------------------------------------------------------------------------------
    // htmlEscape
    // ---------------------------------------------------------------------------------------------

    @Test
    void htmlEscapeReturnsEmptyForNull() {
        assertEquals("", OAuthSupport.htmlEscape(null), "null input should return empty string");
    }

    @Test
    void htmlEscapeReturnsEmptyForEmpty() {
        assertEquals("", OAuthSupport.htmlEscape(""), "empty input should return empty string");
    }

    @Test
    void htmlEscapeEscapesAmpersand() {
        assertEquals("a&amp;b", OAuthSupport.htmlEscape("a&b"), "& should become &amp;");
    }

    @Test
    void htmlEscapeEscapesLessThan() {
        assertEquals("a&lt;b", OAuthSupport.htmlEscape("a<b"), "< should become &lt;");
    }

    @Test
    void htmlEscapeEscapesGreaterThan() {
        assertEquals("a&gt;b", OAuthSupport.htmlEscape("a>b"), "> should become &gt;");
    }

    @Test
    void htmlEscapeEscapesDoubleQuote() {
        assertEquals("a&quot;b", OAuthSupport.htmlEscape("a\"b"), "\" should become &quot;");
    }

    @Test
    void htmlEscapeEscapesSingleQuote() {
        assertEquals("a&#39;b", OAuthSupport.htmlEscape("a'b"), "' should become &#39;");
    }

    @Test
    void htmlEscapeReplacesAllOccurrences() {
        assertEquals("&amp;&amp;&amp;", OAuthSupport.htmlEscape("&&&"),
                "every occurrence of & should be escaped");
    }

    @Test
    void htmlEscapeEscapesMixedSpecials() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;",
                OAuthSupport.htmlEscape("<a href=\"x\">&'</a>"),
                "all special characters in a mixed string should be escaped");
    }

    @Test
    void htmlEscapeLeavesPlainTextUnchanged() {
        assertEquals("plain text 123", OAuthSupport.htmlEscape("plain text 123"),
                "text without special characters should be returned unchanged");
    }

    @Test
    void htmlEscapeDoesNotDoubleEscapeAmpersandFirst() {
        // & is replaced first; the &lt; produced by escaping < must NOT have its & re-escaped.
        assertEquals("&lt;", OAuthSupport.htmlEscape("<"),
                "< should escape to exactly &lt; (no double-escape of the introduced &)");
        assertEquals("&amp;lt;", OAuthSupport.htmlEscape("&lt;"),
                "a literal &lt; input should have only its own & escaped, becoming &amp;lt;");
    }

    @Test
    void htmlEscapeHandlesConsecutiveSpecials() {
        assertEquals("&amp;&lt;&gt;&amp;", OAuthSupport.htmlEscape("&<>&"),
                "consecutive special characters should each be escaped independently");
    }

    @Test
    void htmlEscapeNeverReturnsNull() {
        assertNotNull(OAuthSupport.htmlEscape(null), "htmlEscape must never return null");
        assertFalse(OAuthSupport.htmlEscape("abc").isEmpty(), "non-empty plain input stays non-empty");
    }
}
