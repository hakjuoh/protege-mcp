package io.github.hakjuoh.protege_mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link OAuthServlet}. The servlet's real work lives in private
 * {@code handle*} helpers; those are exercised through the public {@link OAuthServlet#doGet} /
 * {@link OAuthServlet#doPost} entry points (the plan marked the private helpers "avoid", but they
 * are reachable and fully testable through the public methods with hand-rolled fakes).
 *
 * <p>Fakes are hand-rolled — no Mockito. {@link FakeRequest} records parameters / path-info / body,
 * {@link FakeResponse} records status, headers, content type, written body, redirect target and
 * {@code sendError}. The collaborators are a REAL {@link OAuthStore} (constructible with plain
 * lambda hooks, no Protege runtime) and a real {@link ObjectMapper}. Generated ids/codes/tokens are
 * asserted on format/prefix and cross-call uniqueness, never on exact value, so everything is
 * deterministic.
 */
class OAuthServletTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static OAuthStore emptyStore() {
        return new OAuthStore(() -> "static-tok", () -> null, s -> { });
    }

    private static OAuthServlet servlet(OAuthStore store) {
        return new OAuthServlet(store, MAPPER);
    }

    // ---------------------------------------------------------------------------------------------
    // Hand-rolled HttpServletRequest fake
    // ---------------------------------------------------------------------------------------------

    /** Minimal HttpServletRequest recording pathInfo, request parameters and a JSON body. */
    private static final class FakeRequest implements HttpServletRequest {
        String pathInfo;
        final Map<String, String> params = new LinkedHashMap<>();
        byte[] body = new byte[0];
        boolean throwOnGetInputStream = false;
        String remoteAddr = "127.0.0.1";

        FakeRequest path(String p) {
            this.pathInfo = p;
            return this;
        }

        FakeRequest from(String addr) {
            this.remoteAddr = addr;
            return this;
        }

        FakeRequest param(String k, String v) {
            params.put(k, v);
            return this;
        }

        FakeRequest jsonBody(String json) {
            this.body = json.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        @Override
        public String getPathInfo() {
            return pathInfo;
        }

        @Override
        public String getParameter(String name) {
            return params.get(name);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (throwOnGetInputStream) {
                throw new IOException("stream unavailable");
            }
            ByteArrayInputStream backing = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return backing.read();
                }

                @Override
                public boolean isFinished() {
                    return backing.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        // ---- everything else is unused by the servlet ----
        @Override public String getAuthType() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.http.Cookie[] getCookies() { throw new UnsupportedOperationException(); }
        @Override public long getDateHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public String getHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Enumeration<String> getHeaders(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Enumeration<String> getHeaderNames() { throw new UnsupportedOperationException(); }
        @Override public int getIntHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public String getMethod() { throw new UnsupportedOperationException(); }
        @Override public String getPathTranslated() { throw new UnsupportedOperationException(); }
        @Override public String getContextPath() { throw new UnsupportedOperationException(); }
        @Override public String getQueryString() { throw new UnsupportedOperationException(); }
        @Override public String getRemoteUser() { throw new UnsupportedOperationException(); }
        @Override public boolean isUserInRole(String role) { throw new UnsupportedOperationException(); }
        @Override public java.security.Principal getUserPrincipal() { throw new UnsupportedOperationException(); }
        @Override public String getRequestedSessionId() { throw new UnsupportedOperationException(); }
        @Override public String getRequestURI() { throw new UnsupportedOperationException(); }
        @Override public StringBuffer getRequestURL() { throw new UnsupportedOperationException(); }
        @Override public String getServletPath() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.http.HttpSession getSession(boolean create) { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.http.HttpSession getSession() { throw new UnsupportedOperationException(); }
        @Override public String changeSessionId() { throw new UnsupportedOperationException(); }
        @Override public boolean isRequestedSessionIdValid() { throw new UnsupportedOperationException(); }
        @Override public boolean isRequestedSessionIdFromCookie() { throw new UnsupportedOperationException(); }
        @Override public boolean isRequestedSessionIdFromURL() { throw new UnsupportedOperationException(); }
        @Override public boolean authenticate(HttpServletResponse response) { throw new UnsupportedOperationException(); }
        @Override public void login(String username, String password) { throw new UnsupportedOperationException(); }
        @Override public void logout() { throw new UnsupportedOperationException(); }
        @Override public java.util.Collection<jakarta.servlet.http.Part> getParts() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.http.Part getPart(String name) { throw new UnsupportedOperationException(); }
        @Override public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { throw new UnsupportedOperationException(); }
        @Override public Object getAttribute(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Enumeration<String> getAttributeNames() { throw new UnsupportedOperationException(); }
        @Override public String getCharacterEncoding() { throw new UnsupportedOperationException(); }
        @Override public void setCharacterEncoding(String env) { throw new UnsupportedOperationException(); }
        @Override public int getContentLength() { throw new UnsupportedOperationException(); }
        @Override public long getContentLengthLong() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { throw new UnsupportedOperationException(); }
        @Override public java.io.BufferedReader getReader() { throw new UnsupportedOperationException(); }
        @Override public String getRemoteAddr() { return remoteAddr; }
        @Override public String getRemoteHost() { throw new UnsupportedOperationException(); }
        @Override public void setAttribute(String name, Object o) { throw new UnsupportedOperationException(); }
        @Override public void removeAttribute(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Locale getLocale() { throw new UnsupportedOperationException(); }
        @Override public java.util.Enumeration<java.util.Locale> getLocales() { throw new UnsupportedOperationException(); }
        @Override public boolean isSecure() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) { throw new UnsupportedOperationException(); }
        @Override public int getRemotePort() { throw new UnsupportedOperationException(); }
        @Override public String getLocalName() { throw new UnsupportedOperationException(); }
        @Override public String getLocalAddr() { throw new UnsupportedOperationException(); }
        @Override public int getLocalPort() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.ServletContext getServletContext() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.AsyncContext startAsync() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) { throw new UnsupportedOperationException(); }
        @Override public boolean isAsyncStarted() { throw new UnsupportedOperationException(); }
        @Override public boolean isAsyncSupported() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.AsyncContext getAsyncContext() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.DispatcherType getDispatcherType() { throw new UnsupportedOperationException(); }
        @Override public String getProtocol() { throw new UnsupportedOperationException(); }
        @Override public String getScheme() { throw new UnsupportedOperationException(); }
        @Override public String getServerName() { throw new UnsupportedOperationException(); }
        @Override public int getServerPort() { throw new UnsupportedOperationException(); }
        @Override public java.util.Map<String, String[]> getParameterMap() { throw new UnsupportedOperationException(); }
        @Override public java.util.Enumeration<String> getParameterNames() { throw new UnsupportedOperationException(); }
        @Override public String[] getParameterValues(String name) { throw new UnsupportedOperationException(); }
        @Override public String getRequestId() { throw new UnsupportedOperationException(); }
        @Override public String getProtocolRequestId() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.ServletConnection getServletConnection() { throw new UnsupportedOperationException(); }
    }

    // ---------------------------------------------------------------------------------------------
    // Hand-rolled HttpServletResponse fake
    // ---------------------------------------------------------------------------------------------

    /** Records status, headers, content type, written body, redirect target and sendError. */
    private static final class FakeResponse implements HttpServletResponse {
        int status = -1;
        int errorStatus = -1;
        String contentType;
        String redirect;
        final Map<String, String> headers = new LinkedHashMap<>();
        final StringWriter buffer = new StringWriter();
        final PrintWriter writer = new PrintWriter(buffer);

        String body() {
            writer.flush();
            return buffer.toString();
        }

        @Override
        public void sendError(int sc) {
            this.errorStatus = sc;
            this.status = sc;
        }

        @Override
        public void sendError(int sc, String msg) {
            this.errorStatus = sc;
            this.status = sc;
        }

        @Override
        public void sendRedirect(String location) {
            this.redirect = location;
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void setContentType(String type) {
            this.contentType = type;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setHeader(String name, String value) {
            headers.put(name, value);
        }

        @Override
        public PrintWriter getWriter() {
            return writer;
        }

        // ---- everything else is unused by the servlet ----
        @Override public void addCookie(jakarta.servlet.http.Cookie cookie) { throw new UnsupportedOperationException(); }
        @Override public boolean containsHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public String encodeURL(String url) { throw new UnsupportedOperationException(); }
        @Override public String encodeRedirectURL(String url) { throw new UnsupportedOperationException(); }
        @Override public void sendRedirect(String location, int sc, boolean clearBuffer) { throw new UnsupportedOperationException(); }
        @Override public void setDateHeader(String name, long date) { throw new UnsupportedOperationException(); }
        @Override public void addDateHeader(String name, long date) { throw new UnsupportedOperationException(); }
        @Override public void addHeader(String name, String value) { throw new UnsupportedOperationException(); }
        @Override public void setIntHeader(String name, int value) { throw new UnsupportedOperationException(); }
        @Override public void addIntHeader(String name, int value) { throw new UnsupportedOperationException(); }
        @Override public String getHeader(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Collection<String> getHeaders(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Collection<String> getHeaderNames() { throw new UnsupportedOperationException(); }
        @Override public String getCharacterEncoding() { throw new UnsupportedOperationException(); }
        @Override public jakarta.servlet.ServletOutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public void setCharacterEncoding(String charset) { throw new UnsupportedOperationException(); }
        @Override public void setContentLength(int len) { throw new UnsupportedOperationException(); }
        @Override public void setContentLengthLong(long len) { throw new UnsupportedOperationException(); }
        @Override public void setBufferSize(int size) { throw new UnsupportedOperationException(); }
        @Override public int getBufferSize() { throw new UnsupportedOperationException(); }
        @Override public void flushBuffer() { throw new UnsupportedOperationException(); }
        @Override public void resetBuffer() { throw new UnsupportedOperationException(); }
        @Override public boolean isCommitted() { throw new UnsupportedOperationException(); }
        @Override public void reset() { throw new UnsupportedOperationException(); }
        @Override public void setLocale(java.util.Locale loc) { throw new UnsupportedOperationException(); }
        @Override public java.util.Locale getLocale() { throw new UnsupportedOperationException(); }
    }

    // ---------------------------------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------------------------------

    /** Compute the S256 code_challenge for a given verifier (mirrors {@link PkceUtil#verifyS256}). */
    private static String s256(String verifier) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Register a client with the given redirect uri and return it. */
    private static OAuthStore.Client register(OAuthStore store, String redirectUri) {
        return store.registerClient(List.of(redirectUri), "MyApp");
    }

    // =============================================================================================
    // constructor
    // =============================================================================================

    @Test
    void constructorAcceptsStoreAndMapper() {
        OAuthServlet s = new OAuthServlet(emptyStore(), MAPPER);
        assertNotNull(s, "servlet constructed with store + mapper");
    }

    // =============================================================================================
    // doGet routing
    // =============================================================================================

    @Test
    void doGetAuthorizeWithUnknownClientRendersErrorPage() throws IOException {
        FakeRequest req = new FakeRequest().path("/authorize"); // no client_id
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.status,
                "unknown client on /authorize -> 400 error page");
        assertTrue(resp.body().contains("Authorization error"), "error page HTML rendered");
        assertNull(resp.redirect, "must NOT redirect to an unverified URI");
    }

    @Test
    void doGetRegisterReturns404() throws IOException {
        FakeRequest req = new FakeRequest().path("/register");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus, "GET /register -> 404");
    }

    @Test
    void doGetTokenReturns404() throws IOException {
        FakeRequest req = new FakeRequest().path("/token");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus, "GET /token -> 404");
    }

    @Test
    void doGetRevokeReturns404() throws IOException {
        FakeRequest req = new FakeRequest().path("/revoke");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus, "GET /revoke -> 404");
    }

    @Test
    void doGetUnknownPathReturns404() throws IOException {
        FakeRequest req = new FakeRequest().path("/nope");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus, "GET /nope -> 404");
    }

    @Test
    void doGetNullPathInfoReturns404() throws IOException {
        FakeRequest req = new FakeRequest().path(null);
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus, "null pathInfo GET -> 404");
    }

    @Test
    void doGetAuthorizeRequiresExactMatchNotSubpath() throws IOException {
        FakeRequest req = new FakeRequest().path("/authorize/extra");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus,
                "GET /authorize/extra is not an exact match -> 404");
    }

    // =============================================================================================
    // doPost routing
    // =============================================================================================

    @Test
    void doPostUnknownPathReturns404() throws IOException {
        FakeRequest req = new FakeRequest().path("/nope");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus, "POST /nope -> 404");
    }

    @Test
    void doPostNullPathInfoReturns404() throws IOException {
        FakeRequest req = new FakeRequest().path(null);
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus, "null pathInfo POST -> 404");
    }

    @Test
    void doPostRevokeExactMatchRequired() throws IOException {
        FakeRequest req = new FakeRequest().path("/revoke/x");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);
        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.errorStatus,
                "POST /revoke/x not exact -> 404");
    }

    // =============================================================================================
    // POST /register (RFC 7591)
    // =============================================================================================

    @Test
    void registerWithValidRedirectUrisReturns201WithClientMetadata() throws IOException {
        OAuthStore store = emptyStore();
        FakeRequest req = new FakeRequest().path("/register")
                .jsonBody("{\"redirect_uris\":[\"http://127.0.0.1:5000/cb\"],\"client_name\":\"App\"}");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_CREATED, resp.status, "successful register -> 201");
        assertEquals("application/json", resp.contentType, "register response is JSON");
        assertEquals("no-store", resp.headers.get("Cache-Control"), "Cache-Control no-store");

        JsonNode out = MAPPER.readTree(resp.body());
        assertTrue(out.path("client_id").asText().startsWith("mcpc_"), "client_id minted with prefix");
        assertEquals("App", out.path("client_name").asText(), "client_name echoed");
        assertEquals("none", out.path("token_endpoint_auth_method").asText(), "public client");
        assertEquals("http://127.0.0.1:5000/cb", out.path("redirect_uris").get(0).asText(),
                "redirect_uris echoed");
        assertTrue(out.has("client_id_issued_at"), "client_id_issued_at present");
        assertEquals("authorization_code", out.path("grant_types").get(0).asText(),
                "grant_types includes authorization_code");
        assertEquals("refresh_token", out.path("grant_types").get(1).asText(),
                "grant_types includes refresh_token");
        assertEquals("code", out.path("response_types").get(0).asText(), "response_types is code");

        // The client is actually persisted in the store.
        assertNotNull(store.client(out.path("client_id").asText()), "client stored");
    }

    @Test
    void registerDefaultsClientNameWhenMissing() throws IOException {
        FakeRequest req = new FakeRequest().path("/register")
                .jsonBody("{\"redirect_uris\":[\"http://127.0.0.1/cb\"]}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        JsonNode out = MAPPER.readTree(resp.body());
        assertEquals("MCP client", out.path("client_name").asText(),
                "missing client_name defaults to 'MCP client'");
    }

    @Test
    void registerFiltersNullElementsFromRedirectUris() throws IOException {
        FakeRequest req = new FakeRequest().path("/register")
                .jsonBody("{\"redirect_uris\":[null,\"http://127.0.0.1/cb\",null]}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_CREATED, resp.status,
                "null entries filtered, one real uri remains -> 201");
        JsonNode out = MAPPER.readTree(resp.body());
        assertEquals(1, out.path("redirect_uris").size(), "only the non-null uri kept");
    }

    @Test
    void registerMissingRedirectUrisReturns400InvalidRedirectUri() throws IOException {
        FakeRequest req = new FakeRequest().path("/register").jsonBody("{\"client_name\":\"x\"}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(400, resp.status, "no redirect_uris -> 400");
        JsonNode out = MAPPER.readTree(resp.body());
        assertEquals("invalid_redirect_uri", out.path("error").asText(), "invalid_redirect_uri error");
    }

    @Test
    void registerEmptyRedirectUrisListReturns400() throws IOException {
        FakeRequest req = new FakeRequest().path("/register").jsonBody("{\"redirect_uris\":[]}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(400, resp.status, "empty redirect_uris list -> 400");
        assertEquals("invalid_redirect_uri", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_redirect_uri error");
    }

    @Test
    void registerRedirectUrisNotAListReturns400() throws IOException {
        // A string (not a List) for redirect_uris leaves the collected list empty -> 400.
        FakeRequest req = new FakeRequest().path("/register")
                .jsonBody("{\"redirect_uris\":\"http://not-a-list/cb\"}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(400, resp.status, "non-list redirect_uris -> empty -> 400");
        assertEquals("invalid_redirect_uri", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_redirect_uri error");
    }

    @Test
    void registerInvalidJsonBodyReturns400InvalidClientMetadata() throws IOException {
        FakeRequest req = new FakeRequest().path("/register").jsonBody("{ this is not json");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(400, resp.status, "malformed JSON body -> 400");
        assertEquals("invalid_client_metadata", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_client_metadata error for bad JSON");
    }

    @Test
    void registerInputStreamIoExceptionReturns400InvalidClientMetadata() throws IOException {
        FakeRequest req = new FakeRequest().path("/register");
        req.throwOnGetInputStream = true;
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(400, resp.status, "IOException reading body -> 400");
        assertEquals("invalid_client_metadata", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_client_metadata error when body cannot be read");
    }

    // =============================================================================================
    // GET /authorize (consent page + error branches)
    // =============================================================================================

    @Test
    void authorizeGetValidRequestRendersConsentPageWithHiddenInputs() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1:5000/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1:5000/cb")
                .param("response_type", "code")
                .param("code_challenge", "abc123")
                .param("code_challenge_method", "S256")
                .param("state", "st-1")
                .param("scope", "read");
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        assertEquals(HttpServletResponse.SC_OK, resp.status, "valid authorize GET -> 200 consent page");
        assertEquals("text/html;charset=utf-8", resp.contentType, "consent page is HTML");
        assertEquals("no-store", resp.headers.get("Cache-Control"), "no-store");
        String html = resp.body();
        assertTrue(html.contains("Authorize access to Protégé"), "consent heading present");
        assertTrue(html.contains("value=\"allow\""), "allow button present");
        assertTrue(html.contains("value=\"deny\""), "deny button present");
        assertTrue(html.contains("name=\"code_challenge\" value=\"abc123\""),
                "code_challenge carried as hidden input");
        assertTrue(html.contains("name=\"state\" value=\"st-1\""), "state carried as hidden input");
        assertNull(resp.redirect, "consent page is not a redirect");
    }

    @Test
    void authorizeGetHtmlEscapesClientNameToPreventXss() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = store.registerClient(List.of("http://127.0.0.1/cb"),
                "<script>alert(1)</script>");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("response_type", "code")
                .param("code_challenge", "chal")
                .param("code_challenge_method", "S256");
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        String html = resp.body();
        assertFalse(html.contains("<script>alert(1)</script>"), "raw client name must not appear");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
                "client name HTML-escaped in consent page");
    }

    @Test
    void authorizeGetHtmlEscapesHiddenInputValues() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("response_type", "code")
                .param("code_challenge", "chal")
                .param("code_challenge_method", "S256")
                .param("state", "\"><img src=x>");
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        String html = resp.body();
        assertFalse(html.contains("\"><img src=x>"), "raw injected state must not appear unescaped");
        assertTrue(html.contains("&quot;&gt;&lt;img src=x&gt;"), "state value HTML-escaped");
    }

    @Test
    void authorizeGetUnknownClientRendersErrorPageNoRedirect() throws IOException {
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", "mcpc_unknown")
                .param("redirect_uri", "http://evil/cb")
                .param("response_type", "code")
                .param("code_challenge", "c")
                .param("code_challenge_method", "S256");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.status, "unknown client -> 400");
        assertTrue(resp.body().contains("Authorization error"), "error page rendered");
        assertNull(resp.redirect, "no redirect to unverified URI");
    }

    @Test
    void authorizeGetUnregisteredRedirectUriRendersErrorPage() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/OTHER") // not registered
                .param("response_type", "code")
                .param("code_challenge", "c")
                .param("code_challenge_method", "S256");
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.status,
                "redirect uri not registered -> 400 error page");
        assertNull(resp.redirect, "no redirect to unverified URI");
    }

    @Test
    void authorizeGetWrongResponseTypeRedirectsWithUnsupportedResponseType() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("response_type", "token") // not "code"
                .param("code_challenge", "c")
                .param("code_challenge_method", "S256")
                .param("state", "abc");
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        assertNotNull(resp.redirect, "unsupported response_type redirects to the (verified) uri");
        assertTrue(resp.redirect.startsWith("http://127.0.0.1/cb?"), "redirect uses ? separator");
        assertTrue(resp.redirect.contains("error=unsupported_response_type"),
                "error=unsupported_response_type in redirect");
        assertTrue(resp.redirect.contains("&state=abc"), "state echoed in redirect");
    }

    @Test
    void authorizeGetMissingCodeChallengeRedirectsWithInvalidRequest() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("response_type", "code")
                .param("code_challenge_method", "S256"); // no code_challenge
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        assertNotNull(resp.redirect, "missing code_challenge redirects");
        assertTrue(resp.redirect.contains("error=invalid_request"),
                "error=invalid_request when PKCE challenge missing");
    }

    @Test
    void authorizeGetNonS256ChallengeMethodRedirectsWithInvalidRequest() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("response_type", "code")
                .param("code_challenge", "chal")
                .param("code_challenge_method", "plain"); // not S256
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        assertNotNull(resp.redirect, "non-S256 method redirects");
        assertTrue(resp.redirect.contains("error=invalid_request"),
                "error=invalid_request when method != S256");
    }

    @Test
    void authorizeGetRedirectErrorUsesAmpersandWhenRedirectHasQuery() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb?foo=bar");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb?foo=bar")
                .param("response_type", "token")
                .param("code_challenge", "c")
                .param("code_challenge_method", "S256");
        FakeResponse resp = new FakeResponse();
        servlet(store).doGet(req, resp);

        assertTrue(resp.redirect.startsWith("http://127.0.0.1/cb?foo=bar&error="),
                "existing query string means the error param is appended with &");
    }

    // =============================================================================================
    // POST /authorize (decision)
    // =============================================================================================

    @Test
    void authorizeDecisionAllowRedirectsWithCodeAndState() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "allow")
                .param("code_challenge", s256("verifier-xyz"))
                .param("scope", "read")
                .param("resource", "res")
                .param("state", "state 1"); // space to check encoding
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertNotNull(resp.redirect, "allow -> redirect to callback");
        assertTrue(resp.redirect.startsWith("http://127.0.0.1/cb?code="),
                "redirect carries code with ? separator");
        assertTrue(resp.redirect.contains("&state=state+1"),
                "state URL-encoded (space -> +) and appended with &");
    }

    @Test
    void authorizeDecisionAllowIssuesConsumableAuthCode() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        String challenge = s256("my-verifier");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "allow")
                .param("code_challenge", challenge)
                .param("scope", "read")
                .param("resource", "res");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        // Extract the code from the redirect and confirm it is a live auth code bound to our params.
        String redirect = resp.redirect;
        int codeIdx = redirect.indexOf("code=") + "code=".length();
        int end = redirect.indexOf('&', codeIdx);
        String code = end < 0 ? redirect.substring(codeIdx) : redirect.substring(codeIdx, end);
        code = java.net.URLDecoder.decode(code, StandardCharsets.UTF_8);

        OAuthStore.AuthCode ac = store.consumeAuthCode(code);
        assertNotNull(ac, "auth code from redirect is consumable");
        assertEquals(c.clientId, ac.clientId, "auth code bound to client");
        assertEquals("http://127.0.0.1/cb", ac.redirectUri, "auth code bound to redirect uri");
        assertEquals(challenge, ac.codeChallenge, "auth code carries PKCE challenge");
        assertEquals("read", ac.scope, "auth code carries scope");
        assertEquals("res", ac.resource, "auth code carries resource");
    }

    @Test
    void authorizeDecisionAllowWithoutStateOmitsStateParam() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "allow")
                .param("code_challenge", s256("v"));
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertNotNull(resp.redirect, "allow -> redirect");
        assertFalse(resp.redirect.contains("state="), "no state param when state absent");
    }

    @Test
    void authorizeDecisionDenyRedirectsWithAccessDenied() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "deny")
                .param("state", "s");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertNotNull(resp.redirect, "deny -> redirect");
        assertTrue(resp.redirect.contains("error=access_denied"), "deny -> error=access_denied");
        assertTrue(resp.redirect.contains("&state=s"), "state echoed on deny");
    }

    @Test
    void authorizeDecisionMissingDecisionTreatedAsDeny() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertTrue(resp.redirect.contains("error=access_denied"),
                "any decision != 'allow' (including missing) -> access_denied");
    }

    @Test
    void authorizeDecisionUnknownClientRendersErrorPage() throws IOException {
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", "mcpc_missing")
                .param("redirect_uri", "http://evil/cb")
                .param("decision", "allow");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.status, "unknown client -> 400");
        assertNull(resp.redirect, "no redirect to unverified URI on decision");
    }

    @Test
    void authorizeDecisionUnregisteredRedirectUriRendersErrorPage() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/OTHER")
                .param("decision", "allow");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.status,
                "unregistered redirect uri on decision -> 400 error page");
        assertNull(resp.redirect, "no redirect");
    }

    // =============================================================================================
    // POST /token
    // =============================================================================================

    @Test
    void tokenAuthorizationCodeGrantSucceedsAndReturnsTokens() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        String verifier = "verifier-abcdefghijklmnop";
        String code = store.newAuthCode(c.clientId, "http://127.0.0.1/cb", s256(verifier),
                "read", "res");

        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("code_verifier", verifier);
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_OK, resp.status, "code grant -> 200");
        assertEquals("application/json", resp.contentType, "token response is JSON");
        assertEquals("no-store", resp.headers.get("Cache-Control"), "no-store");
        JsonNode out = MAPPER.readTree(resp.body());
        assertTrue(out.path("access_token").asText().startsWith("mcpt_"), "access_token minted");
        assertEquals("Bearer", out.path("token_type").asText(), "token_type Bearer");
        assertTrue(out.path("refresh_token").asText().startsWith("mcpr_"), "refresh_token minted");
        assertEquals(30L * 24 * 3600, out.path("expires_in").asLong(), "expires_in 30 days");
        assertEquals("read", out.path("scope").asText(), "scope echoed");
    }

    @Test
    void tokenGrantOmitsScopeWhenNull() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        String verifier = "v-1234567890";
        String code = store.newAuthCode(c.clientId, "http://127.0.0.1/cb", s256(verifier),
                null, null); // null scope

        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("code_verifier", verifier);
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        JsonNode out = MAPPER.readTree(resp.body());
        assertFalse(out.has("scope"), "null scope omitted from token response");
    }

    @Test
    void tokenAuthorizationCodeUnknownCodeReturnsInvalidGrant() throws IOException {
        OAuthStore store = emptyStore();
        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code")
                .param("code", "mcpa_nope")
                .param("client_id", "cid")
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("code_verifier", "v");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(400, resp.status, "unknown code -> 400");
        assertEquals("invalid_grant", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_grant for unknown code");
    }

    @Test
    void tokenAuthorizationCodeWrongClientIdReturnsInvalidGrant() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        String verifier = "verifier-x";
        String code = store.newAuthCode(c.clientId, "http://127.0.0.1/cb", s256(verifier), "s", "r");

        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("client_id", "different-client")
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("code_verifier", verifier);
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(400, resp.status, "client mismatch -> 400");
        assertEquals("invalid_grant", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_grant on client mismatch");
    }

    @Test
    void tokenAuthorizationCodeWrongRedirectUriReturnsInvalidGrant() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        String verifier = "verifier-y";
        String code = store.newAuthCode(c.clientId, "http://127.0.0.1/cb", s256(verifier), "s", "r");

        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/OTHER")
                .param("code_verifier", verifier);
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(400, resp.status, "redirect uri mismatch -> 400");
        assertEquals("invalid_grant", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_grant on redirect uri mismatch");
    }

    @Test
    void tokenAuthorizationCodeBadPkceVerifierReturnsInvalidGrant() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        String code = store.newAuthCode(c.clientId, "http://127.0.0.1/cb", s256("real-verifier"),
                "s", "r");

        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("code_verifier", "WRONG-verifier"); // does not hash to the challenge
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(400, resp.status, "PKCE mismatch -> 400");
        assertEquals("invalid_grant", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_grant on PKCE verifier mismatch");
    }

    @Test
    void tokenAuthorizationCodeIsSingleUse() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        String verifier = "verifier-single-use";
        String code = store.newAuthCode(c.clientId, "http://127.0.0.1/cb", s256(verifier), "s", "r");

        FakeRequest req1 = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code").param("code", code)
                .param("client_id", c.clientId).param("redirect_uri", "http://127.0.0.1/cb")
                .param("code_verifier", verifier);
        FakeResponse resp1 = new FakeResponse();
        servlet(store).doPost(req1, resp1);
        assertEquals(200, resp1.status, "first code use succeeds");

        FakeRequest req2 = new FakeRequest().path("/token")
                .param("grant_type", "authorization_code").param("code", code)
                .param("client_id", c.clientId).param("redirect_uri", "http://127.0.0.1/cb")
                .param("code_verifier", verifier);
        FakeResponse resp2 = new FakeResponse();
        servlet(store).doPost(req2, resp2);
        assertEquals(400, resp2.status, "reusing consumed code -> 400");
        assertEquals("invalid_grant", MAPPER.readTree(resp2.body()).path("error").asText(),
                "invalid_grant on reused code");
    }

    @Test
    void tokenRefreshGrantRotatesAndSucceeds() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Tokens issued = store.issueTokens("cid", "read", "res");

        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", issued.refreshToken);
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(200, resp.status, "refresh grant -> 200");
        JsonNode out = MAPPER.readTree(resp.body());
        assertTrue(out.path("access_token").asText().startsWith("mcpt_"), "new access token minted");
        assertNotEquals(issued.accessToken, out.path("access_token").asText(),
                "refresh rotates the access token");
        assertEquals("read", out.path("scope").asText(), "scope preserved across refresh");
    }

    @Test
    void tokenRefreshUnknownReturnsInvalidGrant() throws IOException {
        OAuthStore store = emptyStore();
        FakeRequest req = new FakeRequest().path("/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", "mcpr_unknown");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(400, resp.status, "unknown refresh token -> 400");
        assertEquals("invalid_grant", MAPPER.readTree(resp.body()).path("error").asText(),
                "invalid_grant for unknown refresh token");
    }

    @Test
    void tokenUnsupportedGrantTypeReturns400() throws IOException {
        OAuthStore store = emptyStore();
        FakeRequest req = new FakeRequest().path("/token").param("grant_type", "password");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(400, resp.status, "unsupported grant type -> 400");
        JsonNode out = MAPPER.readTree(resp.body());
        assertEquals("unsupported_grant_type", out.path("error").asText(),
                "unsupported_grant_type error");
        assertEquals("password", out.path("error_description").asText(),
                "the offending grant_type is echoed as the description");
    }

    @Test
    void tokenNullGrantTypeReturnsUnsupportedGrantType() throws IOException {
        OAuthStore store = emptyStore();
        FakeRequest req = new FakeRequest().path("/token"); // no grant_type
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(400, resp.status, "missing grant type -> 400");
        assertEquals("unsupported_grant_type", MAPPER.readTree(resp.body()).path("error").asText(),
                "null grant_type falls through to unsupported_grant_type");
    }

    // =============================================================================================
    // POST /revoke (RFC 7009)
    // =============================================================================================

    @Test
    void revokeKnownTokenReturns200AndDropsIt() throws IOException {
        OAuthStore store = emptyStore();
        String cid = store.registerClient(List.of("http://a/cb"), "app").clientId;
        OAuthStore.Tokens issued = store.issueTokens(cid, "read", "res");
        assertTrue(store.isValidAccessToken(issued.accessToken), "token valid before revoke");

        FakeRequest req = new FakeRequest().path("/revoke").param("token", issued.accessToken);
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_OK, resp.status, "revoke -> 200");
        assertEquals("no-store", resp.headers.get("Cache-Control"), "no-store header set");
        assertFalse(store.isValidAccessToken(issued.accessToken), "token dropped after revoke");
    }

    @Test
    void revokeUnknownTokenStillReturns200() throws IOException {
        // RFC 7009 mandates 200 whether or not the token was recognised.
        OAuthStore store = emptyStore();
        FakeRequest req = new FakeRequest().path("/revoke").param("token", "mcpt_never-existed");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_OK, resp.status, "unknown token revoke -> 200");
        assertEquals("no-store", resp.headers.get("Cache-Control"), "no-store header set");
    }

    @Test
    void revokeNullTokenStillReturns200() throws IOException {
        OAuthStore store = emptyStore();
        FakeRequest req = new FakeRequest().path("/revoke"); // no token param
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(HttpServletResponse.SC_OK, resp.status, "null token revoke -> 200");
    }

    // =============================================================================================
    // enc / URL-encoding behaviour (observed through the redirect targets)
    // =============================================================================================

    @Test
    void redirectEncodesSpaceAsPlusInState() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "deny")
                .param("state", "a b"); // space
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertTrue(resp.redirect.contains("state=a+b"),
                "URLEncoder encodes space as + (application/x-www-form-urlencoded)");
    }

    @Test
    void redirectEncodesReservedCharactersInState() throws IOException {
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "deny")
                .param("state", "a/b&c=d");
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertTrue(resp.redirect.contains("state=a%2Fb%26c%3Dd"),
                "slash, ampersand and equals are percent-encoded in the state value");
    }

    @Test
    void redirectEncodesGeneratedAuthCode() throws IOException {
        // The code itself is url-safe base64 (no reserved chars), so it round-trips unescaped, but
        // confirm the code= param is present and decodes back to a live auth code.
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize")
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "allow")
                .param("code_challenge", s256("v"));
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertTrue(resp.redirect.contains("code=mcpa_"), "generated auth code present in redirect");
    }

    // ---------------------------------------------------------------------------------------------
    // Same-machine gate: the consent-less flow must not be drivable from another host
    // ---------------------------------------------------------------------------------------------

    /** 203.0.113.x (TEST-NET-3) is reserved for documentation — never a local interface address. */
    private static final String REMOTE_ADDR = "203.0.113.9";

    @Test
    void remotePeerCannotRegister() throws IOException {
        FakeRequest req = new FakeRequest().path("/register").from(REMOTE_ADDR)
                .jsonBody("{\"redirect_uris\":[\"http://127.0.0.1/cb\"]}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);

        assertEquals(403, resp.status);
        assertTrue(resp.body().contains("access_denied"), resp.body());
        assertTrue(resp.body().contains("static bearer token"),
                "the 403 must point remote clients at the supported auth path: " + resp.body());
    }

    @Test
    void remotePeerCannotDriveTheAuthorizeDecision() throws IOException {
        // The Allow decision is bound to nothing but reachability, so a remote POST
        // decision=allow would mint a code with zero user involvement — the exact hole the
        // gate closes for non-loopback binds.
        OAuthStore store = emptyStore();
        OAuthStore.Client c = register(store, "http://127.0.0.1/cb");
        FakeRequest req = new FakeRequest().path("/authorize").from(REMOTE_ADDR)
                .param("client_id", c.clientId)
                .param("redirect_uri", "http://127.0.0.1/cb")
                .param("decision", "allow")
                .param("code_challenge", s256("v"));
        FakeResponse resp = new FakeResponse();
        servlet(store).doPost(req, resp);

        assertEquals(403, resp.status);
        assertNull(resp.redirect, "no authorization code may be minted for a remote peer");
    }

    @Test
    void remotePeerCannotRenderTheConsentPage() throws IOException {
        FakeRequest req = new FakeRequest().path("/authorize").from(REMOTE_ADDR);
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doGet(req, resp);
        assertEquals(403, resp.status);
    }

    @Test
    void ipv6LoopbackPeerPassesTheGate() throws IOException {
        FakeRequest req = new FakeRequest().path("/register").from("::1")
                .jsonBody("{\"redirect_uris\":[\"http://127.0.0.1/cb\"]}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);
        assertEquals(201, resp.status, "IPv6 loopback is as local as 127.0.0.1: " + resp.body());
    }

    @Test
    void unparseablePeerAddressFailsClosed() throws IOException {
        FakeRequest req = new FakeRequest().path("/register").from("not-an-address")
                .jsonBody("{\"redirect_uris\":[\"http://127.0.0.1/cb\"]}");
        FakeResponse resp = new FakeResponse();
        servlet(emptyStore()).doPost(req, resp);
        assertEquals(403, resp.status);
    }
}
