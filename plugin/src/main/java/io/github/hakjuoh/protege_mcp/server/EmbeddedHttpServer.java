package io.github.hakjuoh.protege_mcp.server;

import java.net.BindException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * A minimal embedded Jetty 12 (Jakarta Servlet 6 / {@code ee10}) host, bound to {@code 127.0.0.1}
 * unless {@link #bindTo} chose another address. Hosts the MCP transport servlet plus the OAuth
 * authorization-server endpoints, with the access-token filter scoped to {@code /mcp}. Async is
 * enabled because the Streamable-HTTP transport uses {@code request.startAsync()} for SSE streams.
 * Register servlets/filters, then {@link #start}.
 */
public final class EmbeddedHttpServer {

    private static final String LOOPBACK = "127.0.0.1";

    private final List<ServletReg> servlets = new ArrayList<>();
    private final List<FilterReg> filters = new ArrayList<>();

    private String bindAddress = LOOPBACK;
    private Server server;
    private ServerConnector connector;

    /** Bind {@code address} instead of the default {@code 127.0.0.1}. Call before {@link #start}. */
    public void bindTo(String address) {
        this.bindAddress = address;
    }

    /** True for the wildcard addresses that mean "every interface" ({@code 0.0.0.0} / {@code ::}). */
    public static boolean isWildcard(String address) {
        return "0.0.0.0".equals(address) || "::".equals(address);
    }

    /**
     * The host to put in a URL that connects to a server bound at {@code address}: a wildcard bind
     * is reachable via plain loopback, and a bare IPv6 literal needs its URL brackets. A hostname
     * or IPv4 literal passes through unchanged.
     */
    public static String connectHost(String address) {
        if (isWildcard(address)) {
            return LOOPBACK;
        }
        return address.indexOf(':') >= 0 ? "[" + address + "]" : address;
    }

    /**
     * True when {@code address} keeps the server private to this machine. Deliberately literal-only
     * (loopback names/addresses, not a DNS lookup): the Preferences warning uses it, and a lookup
     * there could block the EDT.
     */
    public static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address) || "localhost".equals(address)
                || (address != null && address.startsWith("127."));
    }

    /** Register a servlet at {@code pathSpec} (e.g. {@code /mcp/*}). */
    public void addServlet(HttpServlet servlet, String pathSpec, boolean asyncSupported) {
        servlets.add(new ServletReg(servlet, pathSpec, asyncSupported));
    }

    /** Register a filter at {@code pathSpec}. */
    public void addFilter(Filter filter, String pathSpec) {
        filters.add(new FilterReg(filter, pathSpec));
    }

    /**
     * Start the host on the configured bind address (loopback unless {@link #bindTo} was called).
     *
     * @param port requested port; {@code 0} picks an ephemeral free port.
     * @return the actual bound port.
     */
    public synchronized int start(int port) throws Exception {
        if (isWildcard(bindAddress) && port != 0) {
            // BSD/macOS quirk: with SO_REUSEADDR (Jetty's connector default) a wildcard bind
            // SUCCEEDS while another process holds 127.0.0.1 on the same port — and that specific
            // listener then receives all loopback traffic, i.e. every URL this server hands out
            // (they all say 127.0.0.1) would reach the WRONG process. Probe loopback first so the
            // coexistence surfaces as the bind conflict it really is. (Ephemeral ports are chosen
            // free across all addresses by the kernel — nothing to probe.)
            try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new java.net.InetSocketAddress(LOOPBACK, port));
            } catch (java.io.IOException held) {
                BindException conflict =
                        new BindException("port " + port + " is already in use on 127.0.0.1");
                conflict.initCause(held);
                throw conflict;
            }
        }

        server = new Server();

        connector = new ServerConnector(server);
        connector.setHost(bindAddress);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        for (FilterReg f : filters) {
            FilterHolder holder = new FilterHolder(f.filter);
            holder.setAsyncSupported(true);
            context.addFilter(holder, f.pathSpec, EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        }
        for (ServletReg s : servlets) {
            ServletHolder holder = new ServletHolder(s.servlet);
            holder.setAsyncSupported(s.asyncSupported);
            context.addServlet(holder, s.pathSpec);
        }

        server.setHandler(context);
        server.setStopTimeout(2_000L);
        server.start();

        int bound = connector.getLocalPort();
        if (!isWildcard(bindAddress) && !bindAddress.startsWith("127.")) {
            addLoopbackAliasQuietly(bound);
        }
        return bound;
    }

    /**
     * A server bound to one specific non-IPv4-loopback address ({@code ::1}, a LAN IP) is
     * unreachable at {@code http://127.0.0.1:<port>} — but that loopback form is exactly what older
     * plugin versions (whose {@code broker.json} has no host field) and long-standing client
     * configs dial. Alias the same port on IPv4 loopback so those keep working. Best-effort: a
     * conflict simply skips the alias (the primary may already cover loopback — e.g. a
     * {@code localhost} bind — or a foreign loopback listener owns the port; the primary bind
     * stays authoritative either way).
     */
    private void addLoopbackAliasQuietly(int boundPort) {
        ServerConnector alias = new ServerConnector(server);
        alias.setHost(LOOPBACK);
        alias.setPort(boundPort);
        server.addConnector(alias);
        try {
            alias.start();
        } catch (Exception conflict) {
            server.removeConnector(alias);
        }
    }

    /**
     * Start on {@code preferredPort}, falling back to an ephemeral port when that port is already in
     * use — e.g. another Protégé window or a second Protégé instance already runs the MCP server.
     * Non-bind failures (and any failure when {@code preferredPort} is {@code 0}) propagate as-is.
     *
     * @return the actual bound port; differs from {@code preferredPort} exactly when the fallback ran.
     */
    public synchronized int startWithFallback(int preferredPort) throws Exception {
        try {
            return start(preferredPort);
        } catch (Exception e) {
            if (preferredPort == 0 || !isBindConflict(e)) {
                throw e;
            }
            stop(); // release the half-started server before rebinding
            return start(0);
        }
    }

    /** True when {@code t}'s cause chain contains a bind conflict (the port is already in use). */
    public static boolean isBindConflict(Throwable t) {
        for (int depth = 0; t != null && depth < 16; t = t.getCause(), depth++) {
            if (t instanceof BindException) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isRunning() {
        return server != null && server.isStarted();
    }

    /** Stop the host (best effort, bounded by the stop timeout). Must not be called on the EDT. */
    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ignored) {
                // best effort — window close / dispose must not be blocked by a stuck server
            } finally {
                server = null;
                connector = null;
            }
        }
    }

    private static final class ServletReg {
        final HttpServlet servlet;
        final String pathSpec;
        final boolean asyncSupported;

        ServletReg(HttpServlet servlet, String pathSpec, boolean asyncSupported) {
            this.servlet = servlet;
            this.pathSpec = pathSpec;
            this.asyncSupported = asyncSupported;
        }
    }

    private static final class FilterReg {
        final Filter filter;
        final String pathSpec;

        FilterReg(Filter filter, String pathSpec) {
            this.filter = filter;
            this.pathSpec = pathSpec;
        }
    }
}
