package io.github.hakjuoh.protege_mcp.server;

import java.util.Collections;
import java.util.List;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.oauth.OAuthMetadataServlet;
import io.github.hakjuoh.protege_mcp.oauth.OAuthServlet;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.tools.ToolCatalog;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;
import io.github.hakjuoh.protege_mcp.tools.WriteConfirmer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * Owns the lifecycle and runtime state of the MCP server for one EditorKit (one Protégé window).
 *
 * <p>Read/write helpers ({@link #isReadOnly()}, {@link #isConfirmWrites()}) and the bearer token
 * ({@link #getToken()}) read live from the preferences, so toggling them — or regenerating the token
 * in <em>any</em> window — takes effect on every live server in the process without a restart.
 * {@link #start()}/{@link #stop()} touch no Swing state and must be invoked off the EDT by callers.
 */
public final class McpServerController implements ManagedServer {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    private final OntologyAccess access;
    private final WriteConfirmer confirmer;

    private volatile boolean running;
    private volatile int boundPort;
    private volatile int configuredPort;
    private volatile String lastError;
    /** True while running as a broker-managed backend (ephemeral port behind the shared broker). */
    private volatile boolean brokerManaged;
    /** Latched by the view's Stop button; every start refuses until the view's Start clears it. */
    private volatile boolean userStopped;
    /** Per-start secret the broker presents in place of a bearer token; null in standalone mode. */
    private volatile String brokerSecret;

    private McpServerManager serverManager;
    private EmbeddedHttpServer httpServer;
    private volatile OAuthStore oauthStore;
    /** Latched true exactly while this server's hydrated OAuth store may write the shared blob. */
    private volatile boolean oauthPersistAllowed;
    private volatile ToolContext toolContext;

    public McpServerController(OntologyAccess access) {
        this(access, null);
    }

    /**
     * @param confirmer the write-confirmation gate injected into the {@link ToolContext} (the Swing
     *     dialog at runtime), or {@code null} when none is wired (writes then fail closed if the
     *     confirm-writes preference is on).
     */
    public McpServerController(OntologyAccess access, WriteConfirmer confirmer) {
        this.access = access;
        this.confirmer = confirmer;
        // Ensure a bearer token exists from the moment the controller is visible in the view
        // (load() mints and persists one when the stored token is blank).
        McpConfig.load();
    }

    public synchronized void start() throws Exception {
        startInternal(false);
    }

    /**
     * Start as a broker-managed backend: always on an ephemeral port (the shared broker owns the
     * user-facing configured port), with a fresh per-window secret the broker's proxy presents in
     * place of a bearer token, and with NO shared-blob OAuth hydration/persistence (the broker is
     * the single OAuth authority in this mode).
     */
    public synchronized void startBrokerManaged() throws Exception {
        startInternal(true);
    }

    private void startInternal(boolean asBrokerBackend) throws Exception {
        if (running) {
            return;
        }
        // The last line of defense for the user's explicit Stop: the auto-start paths check the
        // latch up front, but a Stop can land while one of them is already past its check (e.g.
        // while a broker attach is failing over to a standalone start). Refusing HERE, under the
        // same monitor stop() takes, resolves any such race to stopped-and-latched instead of
        // silently overriding the user. Before the try, so a refusal never touches lastError.
        if (userStopped) {
            throw new IllegalStateException("the user stopped this window's MCP server with its Stop "
                    + "button — press Start in the MCP Server view to run it again");
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
            // Broker mode is ephemeral by design: the broker owns the configured port, and a
            // configuredPort of 0 keeps isPortFallback()/the registry election semantics honest.
            this.configuredPort = asBrokerBackend ? 0 : config.getPort();
            this.brokerManaged = asBrokerBackend;
            this.brokerSecret = asBrokerBackend ? McpConfig.generateToken() : null;

            ToolContext context = new ToolContext(access, this, confirmer);
            this.toolContext = context;
            List<SyncToolSpecification> tools = ToolCatalog.buildAll(context);

            serverManager = new McpServerManager();
            serverManager.build(tools);

            ObjectMapper objectMapper = new ObjectMapper();
            // Persist OAuth clients + tokens to the preferences store so a client that connected once
            // keeps working across Protégé restarts (instead of failing with "Unknown client").
            // The store starts EMPTY and is hydrated from the user-global persisted blob only after
            // the bind proves this server holds the configured port: a fallback-port server must
            // neither accept grants the configured-port owner may later revoke (revocation would
            // never reach this server's memory) nor persist its own — it serves the static bearer
            // token and clients registered live with it, in memory only.
            this.oauthStore = new OAuthStore(this::getToken,
                    () -> McpConfig.prefs().getString(McpConfig.KEY_OAUTH_STATE, null),
                    this::persistOAuthState, false);

            httpServer = new EmbeddedHttpServer();
            // Auth gate only on /mcp; the OAuth + discovery endpoints must stay public. In broker
            // mode the filter additionally accepts this window's broker secret, which the broker's
            // proxy attaches after IT authenticated the client.
            httpServer.addFilter(new AccessTokenFilter(oauthStore, this::getBrokerSecret), "/mcp/*");
            httpServer.addServlet(serverManager.getTransportServlet(), "/mcp/*", true);
            httpServer.addServlet(new OAuthMetadataServlet(objectMapper), "/.well-known/*", false);
            httpServer.addServlet(new OAuthServlet(oauthStore, objectMapper), "/oauth/*", false);
            // The configured port is process-exclusive: another Protégé window or a second Protégé
            // instance may already hold it. Chat and the tools need a server bound to THIS window's
            // ontologies, so fall back to an ephemeral port instead of failing the whole start.
            boundPort = httpServer.startWithFallback(configuredPort);
            if (!asBrokerBackend && !isPortFallback()) {
                // Standalone configured-port owner only: a broker backend never touches the shared
                // preferences OAuth blob (the broker process is the OAuth authority in that mode).
                oauthStore.loadPersisted();
                oauthPersistAllowed = true;
            }

            running = true;
            lastError = null;
            if (isPortFallback()) {
                log.warn("protege-mcp: configured port {} is already in use (another Protégé window or "
                        + "instance, or another app) — this window's MCP server bound ephemeral port {} "
                        + "instead", configuredPort, boundPort);
            }
            log.info("protege-mcp: MCP server listening on {}", getEndpointUrl());
        } catch (Exception e) {
            lastError = e.getMessage();
            boundPort = 0;
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
        brokerManaged = false;
        brokerSecret = null;
        log.info("protege-mcp: MCP server stopped");
    }

    public synchronized void restart() throws Exception {
        stop();
        start();
    }

    private void safeStopInternals() {
        // Close the persist gate before tearing anything down so an in-flight OAuth request that
        // outlives the bounded Jetty stop can no longer write the shared blob.
        oauthPersistAllowed = false;
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
        // Release tool-scoped resources (notably the SPARQL cache's model listeners) so a restart does
        // not leak a listener + its cached snapshots. Best-effort; must not block stop.
        if (toolContext != null) {
            try {
                toolContext.dispose();
            } catch (RuntimeException e) {
                log.warn("protege-mcp: error disposing tool context", e);
            } finally {
                toolContext = null;
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

    /**
     * True while this server runs on an ephemeral fallback port because the configured port was
     * already in use when it started (typically held by another Protégé window or instance). Always
     * false when the configured port is {@code 0} — that is ephemeral by choice, not a fallback.
     */
    public boolean isPortFallback() {
        return boundPort != 0 && configuredPort != 0 && boundPort != configuredPort;
    }

    /** True while running as a broker-managed backend behind the shared broker. */
    public boolean isBrokerManaged() {
        return brokerManaged;
    }

    /** See {@link ManagedServer#isUserStopped()}: an explicit Stop that no auto-start may override. */
    @Override
    public boolean isUserStopped() {
        return userStopped;
    }

    /**
     * Record ({@code true}, the view's Stop button) or withdraw ({@code false}, its Start button) the
     * user's explicit stop. Start clears the latch even when the subsequent start attempt fails: the
     * user has said "run", so the lazy/auto starts may try again on their next occasion.
     */
    public void setUserStopped(boolean stopped) {
        userStopped = stopped;
    }

    /** The per-start secret the broker's proxy authenticates with; null in standalone mode. */
    public String getBrokerSecret() {
        return brokerSecret;
    }

    /**
     * Save hook for the {@link OAuthStore}. KEY_OAUTH_STATE is a single user-global blob shared by
     * every Protégé window and process, so only a store known to hold the full persisted state may
     * write it back. The gate is a fact latched in {@code start()} exactly when
     * {@link OAuthStore#loadPersisted()} has hydrated this store — i.e. only on the configured-port
     * owner, only after hydration — and closed again on stop. Gating on the latch rather than on
     * live port state means neither a mutation racing the post-bind hydration nor an in-flight
     * request outliving {@code stop()} can clobber the blob with a partial snapshot; a fallback-port
     * server is never hydrated, so it never persists at all (its OAuth state stays in memory).
     */
    void persistOAuthState(String json) {
        if (!oauthPersistAllowed) {
            return;
        }
        McpConfig.prefs().putString(McpConfig.KEY_OAUTH_STATE, json);
    }

    public String getEndpointUrl() {
        return "http://127.0.0.1:" + boundPort + "/mcp";
    }

    /**
     * Live read — a token regenerated in any window immediately applies to every live server in the
     * process (each {@link AccessTokenFilter} authenticates through this supplier per request), so a
     * leaked token cannot stay valid on a concurrently running fallback-port server.
     */
    public String getToken() {
        return McpConfig.load().getToken();
    }

    /** Generate, persist, and immediately apply a new bearer token (no restart needed). */
    public String regenerateToken() {
        return McpConfig.regenerateToken();
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
