package io.github.hakjuoh.server;

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
 * only. Hosts the MCP transport servlet plus the OAuth authorization-server endpoints, with the
 * access-token filter scoped to {@code /mcp}. Async is enabled because the Streamable-HTTP transport
 * uses {@code request.startAsync()} for SSE streams. Register servlets/filters, then {@link #start}.
 */
public final class EmbeddedHttpServer {

    private static final String LOOPBACK = "127.0.0.1";

    private final List<ServletReg> servlets = new ArrayList<>();
    private final List<FilterReg> filters = new ArrayList<>();

    private Server server;
    private ServerConnector connector;

    /** Register a servlet at {@code pathSpec} (e.g. {@code /mcp/*}). */
    public void addServlet(HttpServlet servlet, String pathSpec, boolean asyncSupported) {
        servlets.add(new ServletReg(servlet, pathSpec, asyncSupported));
    }

    /** Register a filter at {@code pathSpec}. */
    public void addFilter(Filter filter, String pathSpec) {
        filters.add(new FilterReg(filter, pathSpec));
    }

    /**
     * Start the host on the loopback interface.
     *
     * @param port requested port; {@code 0} picks an ephemeral free port.
     * @return the actual bound port.
     */
    public synchronized int start(int port) throws Exception {
        server = new Server();

        connector = new ServerConnector(server);
        connector.setHost(LOOPBACK);
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

        return connector.getLocalPort();
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
