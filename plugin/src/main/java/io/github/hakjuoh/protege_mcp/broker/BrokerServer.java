package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.oauth.OAuthMetadataServlet;
import io.github.hakjuoh.protege_mcp.oauth.OAuthServlet;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.server.AccessTokenFilter;
import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

/**
 * The shared broker's HTTP host: the one fixed, always-alive MCP entry point that every Protégé
 * process (and its windows) registers with. Reuses the plugin's own building blocks — the loopback
 * Jetty host, the bearer/OAuth access filter and the OAuth authorization server — but persists OAuth
 * state to {@code ~/.protege-mcp/oauth.json} instead of Protégé preferences (the broker process has
 * no Protégé runtime, and the file store has no 8k preference-value limit).
 *
 * <p>Lifetime: a maintenance loop reaps instances whose heartbeats stopped or whose OS process died,
 * and invokes the shutdown callback once the registry has been empty past its linger (the reference
 * count the user asked for: no registered instances → the broker exits). The static bearer token is
 * whatever the most recently seen instance reported, so a token regenerated in Protégé propagates
 * within one heartbeat.
 */
public final class BrokerServer {

    static final long HEARTBEAT_STALE_MS = 8_000;
    /** Fallback idle linger; the user's preference arrives per register/heartbeat and overrides. */
    static final long IDLE_LINGER_MS = 15_000;
    static final long BOOT_GRACE_MS = 60_000;

    private final BrokerHome home;
    private final String dirSecret;
    private final String version;
    private final Runnable shutdown;
    private final long staleMs;
    private final long lingerMs;
    private final long bootGraceMs;

    private final InstanceRegistry registry = new InstanceRegistry(System::currentTimeMillis);
    private final ObjectMapper mapper = new ObjectMapper();

    private EmbeddedHttpServer http;
    private ScheduledExecutorService maintenance;
    private volatile BrokerState identity;
    private volatile OAuthStore oauthStore;

    public BrokerServer(BrokerHome home, String dirSecret, String version, Runnable shutdown) {
        this(home, dirSecret, version, shutdown, HEARTBEAT_STALE_MS, IDLE_LINGER_MS, BOOT_GRACE_MS);
    }

    /** Test seam: shortened lifetimes so lifecycle behavior is assertable without real minutes. */
    BrokerServer(BrokerHome home, String dirSecret, String version, Runnable shutdown,
            long staleMs, long lingerMs, long bootGraceMs) {
        this.home = home;
        this.dirSecret = dirSecret;
        this.version = version;
        this.shutdown = shutdown;
        this.staleMs = staleMs;
        this.lingerMs = lingerMs;
        this.bootGraceMs = bootGraceMs;
    }

    public InstanceRegistry registry() {
        return registry;
    }

    public BrokerState identity() {
        return identity;
    }

    /** Test seam: the OAuth store the maintenance loop sweeps (null before {@code start}). */
    OAuthStore oauthStore() {
        return oauthStore;
    }

    /**
     * Bind the loopback default. See {@link #start(String, int)}.
     */
    public synchronized int start(int port) throws Exception {
        return start(BrokerState.DEFAULT_HOST, port);
    }

    /**
     * Bind (strictly — no ephemeral fallback here; {@link BrokerMain} decides what a bind conflict
     * means for a singleton broker), publish {@code broker.json}, and start the maintenance loop.
     *
     * @param bindAddress address to bind — the user's bind preference travels here via the
     *     spawner's {@code --bind}; {@code broker.json} advertises its connect form so instances
     *     and clients can reach a non-loopback or wildcard bind.
     * @return the bound port.
     */
    public synchronized int start(String bindAddress, int port) throws Exception {
        // oauth.json is a normal file, not one java.util.prefs value: keep every active client
        // instead of applying the standalone store's 8k preference-size eviction policy.
        OAuthStore oauthStore = new OAuthStore(registry::latestToken, this::readOauthState,
                this::writeOauthState, true, 0);
        this.oauthStore = oauthStore;

        http = new EmbeddedHttpServer();
        http.bindTo(bindAddress);
        // Same auth gate as a window server — OAuth access token or a static bearer token — except
        // the static check accepts ANY registered process's current token (regeneration propagates
        // per heartbeat). /internal is deliberately NOT behind it: its auth is the directory secret.
        http.addFilter(new BrokerTokenFilter(registry, new AccessTokenFilter(oauthStore)), "/mcp/*");
        http.addFilter(new BrokerTokenFilter(registry, new AccessTokenFilter(oauthStore)), "/instances/*");
        http.addServlet(new McpProxyServlet(registry), "/mcp/*", true);
        http.addServlet(new McpProxyServlet(registry), "/instances/*", true);
        http.addServlet(new OAuthMetadataServlet(mapper), "/.well-known/*", false);
        http.addServlet(new OAuthServlet(oauthStore, mapper), "/oauth/*", false);
        http.addServlet(new InternalApiServlet(dirSecret, registry, this::identity, this::shutdownAsync,
                oauthStore),
                "/internal/*", false);

        int bound = http.start(port);
        identity = new BrokerState(ProcessHandle.current().pid(),
                EmbeddedHttpServer.connectHost(bindAddress), bound, version,
                System.currentTimeMillis());
        home.writeState(identity);

        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "protege-mcp-broker-maintenance");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleWithFixedDelay(this::maintain, 1_000, 1_000, TimeUnit.MILLISECONDS);
        return bound;
    }

    public synchronized void stop() {
        if (maintenance != null) {
            maintenance.shutdownNow();
            maintenance = null;
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        BrokerState id = identity;
        if (id != null) {
            home.deleteStateIfOwnedBy(id.pid);
        }
    }

    private void maintain() {
        try {
            registry.reap(staleMs, pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            OAuthStore store = oauthStore;
            if (store != null) {
                // The broker has no clients view; time-based cleanup of dead OAuth registrations
                // (abandoned re-connects, long-gone clients) happens here instead.
                store.sweepInactiveClients();
            }
            // The linger is the user's preference, delivered with every register/heartbeat — the
            // spawn-time value only covers the window before the first registration.
            if (registry.shouldExit(registry.effectiveLingerMs(lingerMs), bootGraceMs)) {
                System.out.println("protege-mcp-broker: no registered instances — exiting");
                shutdown.run();
            }
        } catch (RuntimeException e) {
            // the maintenance loop must never die; a single bad tick is skipped
            System.err.println("protege-mcp-broker: maintenance tick failed: " + e);
        }
    }

    private void shutdownAsync() {
        // Never stop Jetty from one of its own request threads — hand the exit to the shutdown
        // callback on a fresh thread so the acknowledging response can complete.
        Thread t = new Thread(shutdown, "protege-mcp-broker-shutdown");
        t.setDaemon(true);
        t.start();
    }

    private String readOauthState() {
        try {
            Path file = home.oauthFile();
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null; // unreadable state = start fresh; OAuthStore logs and continues
        }
    }

    private void writeOauthState(String json) {
        try {
            // Owner-only + atomic, same discipline as broker.json (tokens live in this file).
            home.writeOauthState(json);
        } catch (IOException e) {
            // OAuthStore treats a failed persist as non-fatal (it retries on the next mutation)
            System.err.println("protege-mcp-broker: failed to persist oauth state: " + e);
        }
    }
}
