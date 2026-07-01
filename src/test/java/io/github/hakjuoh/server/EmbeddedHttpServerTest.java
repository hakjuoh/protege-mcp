package io.github.hakjuoh.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link EmbeddedHttpServer}. The class registers servlets/filters into
 * internal lists and then builds a real Jetty 12 host on {@code 127.0.0.1}; per the plan's
 * fixtureNotes, binding ephemeral loopback ports is intended and cleaned up in {@link #tearDown()}.
 */
class EmbeddedHttpServerTest {

    private EmbeddedHttpServer host;

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.stop();
            host = null;
        }
    }

    // ---- reflection helpers -------------------------------------------------

    private static Object field(EmbeddedHttpServer h, String fieldName) throws Exception {
        Field f = EmbeddedHttpServer.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(h);
    }

    // ---- minimal hand-rolled servlet / filter fakes -------------------------

    /** Servlet that records how many times it was invoked and echoes a fixed body. */
    private static final class RecordingServlet extends HttpServlet {
        final AtomicInteger hits = new AtomicInteger();
        final String body;

        RecordingServlet(String body) {
            this.body = body;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            hits.incrementAndGet();
            resp.setStatus(200);
            resp.getWriter().write(body);
        }
    }

    /** Filter that records invocation and appends a marker header, then continues the chain. */
    private static final class MarkingFilter implements Filter {
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final AtomicReference<String> orderTrace;
        final String marker;

        MarkingFilter(String marker, AtomicReference<String> orderTrace) {
            this.marker = marker;
            this.orderTrace = orderTrace;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            invoked.set(true);
            if (response instanceof HttpServletResponse) {
                ((HttpServletResponse) response).addHeader("X-Marker", marker);
            }
            orderTrace.accumulateAndGet("F", (a, b) -> a + b);
            chain.doFilter(request, response);
        }
    }

    // ---- HTTP client helper -------------------------------------------------

    private static final class HttpResult {
        final int status;
        final String body;
        final String markerHeader;

        HttpResult(int status, String body, String markerHeader) {
            this.status = status;
            this.body = body;
            this.markerHeader = markerHeader;
        }
    }

    private static HttpResult get(int port, String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        int status = conn.getResponseCode();
        String marker = conn.getHeaderField("X-Marker");
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (in != null) {
            body = new String(in.readAllBytes());
            in.close();
        }
        conn.disconnect();
        return new HttpResult(status, body, marker);
    }

    // ---- addServlet ---------------------------------------------------------

    @Test
    void addServletStoresAsyncFlagTrueEndToEnd() throws Exception {
        host = new EmbeddedHttpServer();
        RecordingServlet s = new RecordingServlet("async-true");
        host.addServlet(s, "/svc/*", true);
        int port = host.start(0);
        HttpResult r = get(port, "/svc/ping");
        assertEquals(200, r.status, "servlet registered with asyncSupported=true must serve requests");
        assertEquals("async-true", r.body, "response body should match the servlet's fixed body");
    }

    @Test
    void addServletStoresAsyncFlagFalseEndToEnd() throws Exception {
        host = new EmbeddedHttpServer();
        RecordingServlet s = new RecordingServlet("async-false");
        host.addServlet(s, "/svc/*", false);
        int port = host.start(0);
        HttpResult r = get(port, "/svc/ping");
        assertEquals(200, r.status, "servlet registered with asyncSupported=false must still serve requests");
        assertEquals("async-false", r.body, "response body should match the servlet's fixed body");
    }

    @Test
    void addServletDoesNotStartServer() throws Exception {
        host = new EmbeddedHttpServer();
        host.addServlet(new RecordingServlet("x"), "/a/*", true);
        assertFalse(host.isRunning(), "registering a servlet must not start the server");
    }

    // ---- addFilter ----------------------------------------------------------

    @Test
    void addFilterDoesNotStartServer() throws Exception {
        host = new EmbeddedHttpServer();
        host.addFilter(new MarkingFilter("m", new AtomicReference<>("")), "/a/*");
        assertFalse(host.isRunning(), "registering a filter must not start the server");
    }

    // ---- start --------------------------------------------------------------

    @Test
    void startZeroBindsEphemeralPortGreaterThanZero() throws Exception {
        host = new EmbeddedHttpServer();
        int port = host.start(0);
        assertTrue(port > 0, "start(0) should bind an ephemeral port and return a positive port number");
    }

    @Test
    void startCreatesServerAndConnector() throws Exception {
        host = new EmbeddedHttpServer();
        host.start(0);
        assertNotNull(field(host, "server"), "start() should create a Server");
        assertNotNull(field(host, "connector"), "start() should create a ServerConnector");
    }

    @Test
    void startBindsLoopbackHost() throws Exception {
        host = new EmbeddedHttpServer();
        host.start(0);
        ServerConnector connector = (ServerConnector) field(host, "connector");
        assertEquals("127.0.0.1", connector.getHost(), "connector must be bound to the loopback interface only");
    }

    @Test
    void startReturnValueMatchesConnectorLocalPort() throws Exception {
        host = new EmbeddedHttpServer();
        int returned = host.start(0);
        ServerConnector connector = (ServerConnector) field(host, "connector");
        assertEquals(connector.getLocalPort(), returned,
                "start() should return connector.getLocalPort()");
    }

    @Test
    void startSetsContextPathToRoot() throws Exception {
        host = new EmbeddedHttpServer();
        RecordingServlet s = new RecordingServlet("root-ctx");
        // Registered at "/*" so a hit at the server root confirms the context path is "/".
        host.addServlet(s, "/*", true);
        int port = host.start(0);
        HttpResult r = get(port, "/anything");
        assertEquals(200, r.status, "servlet at /* under context path / should serve any root path");
        assertEquals("root-ctx", r.body, "context path / should route the request to the registered servlet");
    }

    @Test
    void startSetsStopTimeout() throws Exception {
        host = new EmbeddedHttpServer();
        host.start(0);
        Server server = (Server) field(host, "server");
        assertEquals(2_000L, server.getStopTimeout(), "start() should set a 2000ms stop timeout");
    }

    @Test
    void startInvokesRegisteredServletOverHttp() throws Exception {
        host = new EmbeddedHttpServer();
        RecordingServlet s = new RecordingServlet("hello");
        host.addServlet(s, "/echo/*", true);
        int port = host.start(0);
        HttpResult r = get(port, "/echo/x");
        assertEquals(200, r.status, "a previously added servlet must be reachable after start()");
        assertEquals("hello", r.body, "servlet body should be returned");
        assertEquals(1, s.hits.get(), "the registered servlet should have been invoked exactly once");
    }

    @Test
    void startInvokesRegisteredFilterOverHttp() throws Exception {
        host = new EmbeddedHttpServer();
        AtomicReference<String> trace = new AtomicReference<>("");
        MarkingFilter filter = new MarkingFilter("F1", trace);
        host.addServlet(new RecordingServlet("body"), "/echo/*", true);
        host.addFilter(filter, "/echo/*");
        int port = host.start(0);
        HttpResult r = get(port, "/echo/x");
        assertEquals(200, r.status, "filtered request should still reach the servlet");
        assertTrue(filter.invoked.get(), "a previously added filter must run for matching requests");
        assertEquals("F1", r.markerHeader, "filter should have added its marker header");
    }

    @Test
    void startAppliesFilterForAsyncDispatcherType() throws Exception {
        // The filter is registered for REQUEST + ASYNC. A plain synchronous request exercises REQUEST;
        // the mapping being installed at all is confirmed by the filter running for the request.
        host = new EmbeddedHttpServer();
        AtomicReference<String> trace = new AtomicReference<>("");
        MarkingFilter filter = new MarkingFilter("DT", trace);
        host.addServlet(new RecordingServlet("b"), "/mcp/*", true);
        host.addFilter(filter, "/mcp/*");
        int port = host.start(0);
        HttpResult r = get(port, "/mcp/call");
        assertEquals(200, r.status, "request through the filter mapping should succeed");
        assertEquals("DT", r.markerHeader, "filter registered with REQUEST+ASYNC dispatch must fire on a REQUEST");
    }

    @Test
    void startRunsFilterBeforeServlet() throws Exception {
        host = new EmbeddedHttpServer();
        AtomicReference<String> trace = new AtomicReference<>("");
        MarkingFilter filter = new MarkingFilter("F", trace);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                trace.accumulateAndGet("S", (a, b) -> a + b);
                resp.setStatus(200);
                resp.getWriter().write("done");
            }
        };
        host.addServlet(servlet, "/ord/*", true);
        host.addFilter(filter, "/ord/*");
        int port = host.start(0);
        get(port, "/ord/x");
        assertEquals("FS", trace.get(),
                "the filter must be invoked before the servlet (filters installed ahead of servlets)");
    }

    @Test
    void startTwiceReplacesServerWithFreshInstance() throws Exception {
        // start() unconditionally assigns a brand-new Server/ServerConnector, so a second start(0)
        // does not throw — it orphans the first server and binds a new ephemeral port.
        host = new EmbeddedHttpServer();
        int firstPort = host.start(0);
        Object firstServer = field(host, "server");

        int secondPort = host.start(0);
        Object secondServer = field(host, "server");

        assertTrue(secondPort > 0, "second start(0) should bind another ephemeral port");
        assertTrue(firstServer != secondServer,
                "second start() should replace the server field with a fresh instance");
        assertNotEquals(firstPort, secondPort,
                "an orphaned first server still holds its port, so the new one must pick a different port");
        assertTrue(host.isRunning(), "the host should report running via the newly started server");
    }

    @Test
    void startOnOccupiedPortThrows() throws Exception {
        EmbeddedHttpServer first = new EmbeddedHttpServer();
        int port = first.start(0);
        try {
            EmbeddedHttpServer second = new EmbeddedHttpServer();
            try {
                assertThrows(Exception.class, () -> second.start(port),
                        "binding a port already held by another server should throw");
            } finally {
                second.stop();
            }
        } finally {
            first.stop();
        }
    }

    // ---- isRunning ----------------------------------------------------------

    @Test
    void isRunningFalseBeforeStart() {
        host = new EmbeddedHttpServer();
        assertFalse(host.isRunning(), "isRunning() must be false before start() (server is null)");
    }

    @Test
    void isRunningTrueAfterStart() throws Exception {
        host = new EmbeddedHttpServer();
        host.start(0);
        assertTrue(host.isRunning(), "isRunning() must be true once the server has started");
    }

    @Test
    void isRunningFalseAfterStop() throws Exception {
        host = new EmbeddedHttpServer();
        host.start(0);
        host.stop();
        assertFalse(host.isRunning(), "isRunning() must be false after stop() nulls the server");
    }

    // ---- stop ---------------------------------------------------------------

    @Test
    void stopBeforeStartIsNoOp() {
        host = new EmbeddedHttpServer();
        host.stop(); // server is null — must not throw
        assertFalse(host.isRunning(), "stop() on a never-started host should be a harmless no-op");
    }

    @Test
    void stopNullsServerAndConnector() throws Exception {
        host = new EmbeddedHttpServer();
        host.start(0);
        host.stop();
        assertNull(field(host, "server"), "stop() should null the server reference");
        assertNull(field(host, "connector"), "stop() should null the connector reference");
    }

    @Test
    void stopIsIdempotent() throws Exception {
        host = new EmbeddedHttpServer();
        host.start(0);
        host.stop();
        host.stop(); // second stop hits the null-server branch — must not throw
        assertNull(field(host, "server"), "server should remain null after a second stop()");
    }

    @Test
    void stopReleasesPortForRebind() throws Exception {
        // After stop() the port must be free, confirming the server actually shut down (best-effort stop).
        host = new EmbeddedHttpServer();
        int port = host.start(0);
        host.stop();
        EmbeddedHttpServer rebind = new EmbeddedHttpServer();
        try {
            int rebound = rebind.start(port);
            assertEquals(port, rebound, "the port should be reusable once the first server has stopped");
        } finally {
            rebind.stop();
        }
    }
}
