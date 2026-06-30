package io.github.hakjuoh.protege_mcp.server;

import java.util.Collections;
import java.util.List;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.oauth.OAuthMetadataServlet;
import io.github.hakjuoh.protege_mcp.oauth.OAuthServlet;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.tools.ToolCatalog;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * Owns the lifecycle and runtime state of the MCP server for one EditorKit (one Protégé window).
 *
 * <p>Read/write helpers ({@link #isReadOnly()}, {@link #isConfirmWrites()}) read live from the
 * preferences so toggling them takes effect without a restart; the bearer token is held in memory
 * and exposed to the {@link BearerTokenFilter} via a supplier so it can be regenerated live.
 * {@link #start()}/{@link #stop()} touch no Swing state and must be invoked off the EDT by callers.
 */
public final class McpServerController implements ManagedServer {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    private final OntologyAccess access;

    private volatile String token;
    private volatile boolean running;
    private volatile int boundPort;
    private volatile int configuredPort;
    private volatile String lastError;

    private McpServerManager serverManager;
    private EmbeddedHttpServer httpServer;
    private volatile OAuthStore oauthStore;

    public McpServerController(OntologyAccess access) {
        this.access = access;
        this.token = McpConfig.load().getToken();
    }

    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        // Pin the thread context classloader to this bundle while building the MCP server and
        // starting Jetty. The MCP SDK (ServiceLoader), networknt json-schema-validator (meta-schema
        // resources) and Jetty all reach for the TCCL, which under OSGi is NOT this bundle's
        // classloader — leaving it unset causes ClassNotFound / missing-resource failures.
        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            // Warm the embedded Jena subsystem once, single-threaded, before Jetty accepts requests.
            // start() is synchronized and runs before the port binds, so RIOT/ARQ are fully registered
            // before any (multi-threaded) transport thread runs sparql_query — otherwise two concurrent
            // first-time queries can race on Jena's lazy init and see an unpopulated parser registry.
            org.apache.jena.sys.JenaSystem.init();

            McpConfig config = McpConfig.load();
            this.token = config.getToken();
            this.configuredPort = config.getPort();

            ToolContext context = new ToolContext(access, this);
            List<SyncToolSpecification> tools = ToolCatalog.buildAll(context);

            serverManager = new McpServerManager();
            serverManager.build(tools);

            ObjectMapper objectMapper = new ObjectMapper();
            // Persist OAuth clients + tokens to the preferences store so a client that connected once
            // keeps working across Protégé restarts (instead of failing with "Unknown client").
            // KEY_OAUTH_STATE is a single user-global blob; with one window successfully binding the
            // port at a time, that one controller effectively owns it (multiple windows would share
            // the key, but only the port-binding one accepts mutating requests).
            this.oauthStore = new OAuthStore(this::getToken,
                    () -> McpConfig.prefs().getString(McpConfig.KEY_OAUTH_STATE, null),
                    json -> McpConfig.prefs().putString(McpConfig.KEY_OAUTH_STATE, json));

            httpServer = new EmbeddedHttpServer();
            // Auth gate only on /mcp; the OAuth + discovery endpoints must stay public.
            httpServer.addFilter(new AccessTokenFilter(oauthStore), "/mcp/*");
            httpServer.addServlet(serverManager.getTransportServlet(), "/mcp/*", true);
            httpServer.addServlet(new OAuthMetadataServlet(objectMapper), "/.well-known/*", false);
            httpServer.addServlet(new OAuthServlet(oauthStore, objectMapper), "/oauth/*", false);
            boundPort = httpServer.start(configuredPort);

            running = true;
            lastError = null;
            log.info("protege-mcp: MCP server listening on {}", getEndpointUrl());
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("protege-mcp: failed to start MCP server", e);
            safeStopInternals();
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
    }

    public synchronized void stop() {
        safeStopInternals();
        running = false;
        boundPort = 0;
        log.info("protege-mcp: MCP server stopped");
    }

    public synchronized void restart() throws Exception {
        stop();
        start();
    }

    private void safeStopInternals() {
        if (serverManager != null) {
            try {
                serverManager.close();
            } catch (RuntimeException e) {
                log.warn("protege-mcp: error closing MCP server", e);
            } finally {
                serverManager = null;
            }
        }
        if (httpServer != null) {
            try {
                httpServer.stop();
            } finally {
                httpServer = null;
            }
        }
        // Drop the in-memory store so the view shows no clients while stopped; the next start()
        // rebuilds it from the persisted clients + tokens, so existing clients stay connected.
        oauthStore = null;
    }

    public boolean isRunning() {
        return running;
    }

    public int getBoundPort() {
        return boundPort;
    }

    public int getConfiguredPort() {
        return configuredPort;
    }

    public String getEndpointUrl() {
        return "http://127.0.0.1:" + boundPort + "/mcp";
    }

    public String getToken() {
        return token;
    }

    /** Generate, persist, and immediately apply a new bearer token (no restart needed). */
    public String regenerateToken() {
        this.token = McpConfig.regenerateToken();
        return this.token;
    }

    /** Snapshot of OAuth-registered clients (empty when the server is stopped). */
    public List<OAuthStore.ClientInfo> listClients() {
        OAuthStore s = oauthStore;
        return s == null ? Collections.emptyList() : s.listClients();
    }

    /** Revoke a registered OAuth client and all its tokens; returns true if it existed. */
    public boolean revokeClient(String clientId) {
        OAuthStore s = oauthStore;
        if (s == null) {
            return false;
        }
        boolean revoked = s.revokeClient(clientId);
        if (revoked) {
            log.info("protege-mcp: revoked OAuth client {}", clientId);
        }
        return revoked;
    }

    /** Last time the static fallback token authenticated a request (0 = never / server stopped). */
    public long getStaticTokenLastSeen() {
        OAuthStore s = oauthStore;
        return s == null ? 0L : s.getStaticTokenLastSeen();
    }

    /** Live read — reflects preference changes without a restart. */
    public boolean isReadOnly() {
        return McpConfig.prefs().getBoolean(McpConfig.KEY_READ_ONLY, false);
    }

    /** Live read — reflects preference changes without a restart. */
    public boolean isConfirmWrites() {
        return McpConfig.prefs().getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    public String getLastError() {
        return lastError;
    }
}
