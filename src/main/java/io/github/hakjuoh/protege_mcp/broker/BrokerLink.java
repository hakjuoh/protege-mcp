package io.github.hakjuoh.protege_mcp.broker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.server.McpServerController;
import io.github.hakjuoh.protege_mcp.server.McpServerManager;

/**
 * This Protégé process's connection to the shared broker: one registration for the whole JVM,
 * carrying every attached window's backend (ephemeral port + per-window broker secret), refreshed by
 * a heartbeat that also propagates the current bearer token and the user's broker idle-linger
 * preference. Windows attach/detach as EditorKits
 * come and go; when the last one detaches (or the JVM exits — a shutdown hook backs this up), the
 * process unregisters, dropping the broker's reference count toward its self-shutdown.
 *
 * <p>Self-healing: a heartbeat answered with "unknown process" (broker restarted) re-registers, and
 * a heartbeat that cannot reach the broker at all (crashed/killed) re-runs discovery and — throttled
 * — re-spawns one. An idle broker left behind by a different plugin version is retired and replaced
 * (never while other instances still use it). The window servers themselves are untouched by any of
 * this — only the registration is redone.
 *
 * <p>Threading: the mutating flows ({@code attach}/{@code detach}/{@code beat}) serialize on this
 * object, but the read-side used by the EDT ({@link #brokerMcpUrl()}, {@link #isAttached}) is
 * lock-free — a slow spawn/discovery must never stall the UI.
 */
public final class BrokerLink {

    private static final Logger log = LoggerFactory.getLogger(BrokerLink.class);
    private static final long HEARTBEAT_MS = 2_000;
    private static final long ENSURE_THROTTLE_MS = 10_000;

    private static final BrokerLink INSTANCE = new BrokerLink(BrokerHome.defaultHome());

    private final BrokerHome home;
    private final Map<McpServerController, InstanceRegistry.Window> windows = new ConcurrentHashMap<>();

    private String dirSecret;
    private BrokerClient client;
    private String processId;
    private long lastEnsureAttemptAt;
    private boolean shutdownHookInstalled;

    /** EDT-readable mirrors of the synchronized state (see class javadoc on threading). */
    private volatile String brokerBaseUrl;
    private volatile ScheduledExecutorService heartbeats;

    BrokerLink(BrokerHome home) {
        this.home = home;
    }

    public static BrokerLink get() {
        return INSTANCE;
    }

    /**
     * Bring this window under the shared broker: ensure a broker is alive (spawning one if this is
     * the first instance), start the window's server on an ephemeral port in broker-managed mode,
     * and include it in this process's registration. Returns false — with the controller left in
     * the state the caller found it (a server WE started for the broker is stopped again) — when no
     * broker can be reached or spawned; the caller then uses standalone mode.
     */
    public synchronized boolean attach(McpServerController controller) {
        if (windows.containsKey(controller) && controller.isRunning()) {
            return true; // already attached and serving — don't mint a new window identity
        }
        boolean startedHere = false;
        boolean windowAdded = false;
        try {
            if (!ensureClient(true)) {
                return false;
            }
            if (!controller.isRunning()) {
                controller.startBrokerManaged();
                startedHere = true;
            }
            String secret = controller.getBrokerSecret();
            if (!controller.isBrokerManaged() || secret == null) {
                // Running, but as a standalone server (e.g. started before the preference flipped).
                log.warn("protege-mcp: window server is not broker-managed — leaving it standalone");
                return false;
            }
            long now = System.currentTimeMillis();
            windows.put(controller, new InstanceRegistry.Window(
                    UUID.randomUUID().toString(), controller.getBoundPort(), secret,
                    "Protégé window " + (windows.size() + 1), now, now));
            windowAdded = true;
            syncRegistration();
            installShutdownHook();
            startHeartbeats();
            log.info("protege-mcp: window registered with the shared broker at {}", client.baseUrl());
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("protege-mcp: could not attach to the shared broker — falling back to standalone", e);
            // Leave no zombie behind: an unregistered broker-managed server would be unreachable via
            // the broker AND would block the standalone fallback from starting.
            if (windowAdded) {
                windows.remove(controller);
            }
            if (startedHere) {
                try {
                    controller.stop();
                } catch (RuntimeException stopFailure) {
                    log.warn("protege-mcp: could not stop the half-attached window server", stopFailure);
                }
            }
            return false;
        }
    }

    /** Remove a window from the registration; unregisters the process when it was the last one. */
    public synchronized void detach(McpServerController controller) {
        if (windows.remove(controller) == null) {
            return;
        }
        if (windows.isEmpty()) {
            unregisterQuietly();
            stopHeartbeats();
        } else {
            try {
                syncRegistration();
            } catch (Exception e) {
                log.debug("protege-mcp: broker sync after window detach failed (heartbeat will retry)", e);
            }
        }
    }

    /**
     * The broker's client-facing MCP URL for the view (null when detached or the broker is
     * currently unreachable). Lock-free: called from the EDT refresh timer.
     */
    public String brokerMcpUrl() {
        String base = brokerBaseUrl;
        return base == null ? null : base + "/mcp";
    }

    /** Lock-free: called from the EDT. */
    public boolean isAttached(McpServerController controller) {
        return windows.containsKey(controller);
    }

    /** Push a heartbeat soon (e.g. right after a token regeneration) instead of waiting a tick. */
    public void pokeHeartbeat() {
        ScheduledExecutorService h = heartbeats;
        if (h != null) {
            h.execute(this::beat);
        }
    }

    /**
     * User-initiated recovery (the view's Start while the broker is down): run the heartbeat's
     * re-discovery/re-spawn immediately, dropping the {@link #ENSURE_THROTTLE_MS} backoff that
     * paces the automatic retries. Safe when the broker is actually fine — the beat then just
     * heartbeats as usual.
     */
    public void reconnectNow() {
        synchronized (this) {
            lastEnsureAttemptAt = 0;
        }
        pokeHeartbeat();
    }

    // ------------------------------------------------------------------ internals

    /** Ensure a live BrokerClient; when {@code spawnIfMissing}, discovery may spawn the broker. */
    private boolean ensureClient(boolean spawnIfMissing) throws InterruptedException, IOException {
        if (client != null && client.probe()) {
            brokerBaseUrl = client.baseUrl();
            return true;
        }
        client = null;
        brokerBaseUrl = null;
        if (dirSecret == null) {
            dirSecret = home.ensureDirSecret();
        }
        var live = BrokerMain.findLiveBroker(home, dirSecret);
        if (live.isPresent()) {
            BrokerClient candidate = maybeRetireForUpgrade(
                    new BrokerClient(live.get().baseUrl(), dirSecret), spawnIfMissing);
            if (candidate != null) {
                client = candidate;
                brokerBaseUrl = candidate.baseUrl();
                return true;
            }
        }
        if (!spawnIfMissing) {
            return false;
        }
        McpConfig config = McpConfig.load();
        client = BrokerSpawner.ensureBroker(home, dirSecret,
                config.getPort(), config.getBindAddress(), McpServerManager.SERVER_VERSION,
                config.getBrokerLingerMs()).orElse(null);
        brokerBaseUrl = client == null ? null : client.baseUrl();
        return client != null;
    }

    /**
     * Version takeover: an <em>idle</em> broker from a different plugin version is asked to retire
     * so this (newer or older, but matching) plugin can spawn its own; a mismatched broker that
     * other instances still reference is kept — interop beats disruption. Returns the client to use,
     * or null when the caller should proceed to spawn.
     */
    private BrokerClient maybeRetireForUpgrade(BrokerClient candidate, boolean maySpawn)
            throws InterruptedException {
        try {
            JsonNode info = candidate.info();
            String brokerVersion = info.path("version").asText("");
            if (McpServerManager.SERVER_VERSION.equals(brokerVersion)) {
                return candidate;
            }
            if (info.path("processes").asInt(0) > 0 || !maySpawn) {
                log.warn("protege-mcp: shared broker runs plugin version {} (this window runs {}) and "
                        + "is still referenced — using it as-is", brokerVersion,
                        McpServerManager.SERVER_VERSION);
                return candidate;
            }
            log.info("protege-mcp: retiring idle shared broker v{} to replace it with v{}",
                    brokerVersion, McpServerManager.SERVER_VERSION);
            candidate.requestShutdown();
            for (int i = 0; i < 20 && candidate.probe(); i++) {
                Thread.sleep(150);
            }
            return null;
        } catch (IOException e) {
            return null; // unreachable after all — rediscover/spawn
        }
    }

    /** (Re-)register this process with the full current window list. */
    private void syncRegistration() throws IOException, InterruptedException {
        McpConfig config = McpConfig.load();
        if (processId == null) {
            processId = client.register(ProcessHandle.current().pid(), McpServerManager.SERVER_VERSION,
                    config.getToken(), config.getBrokerLingerMs(), new ArrayList<>(windows.values()));
        } else if (!client.heartbeat(processId, config.getToken(), config.getBrokerLingerMs(),
                new ArrayList<>(windows.values()))) {
            processId = null;
            syncRegistration();
        }
    }

    private void startHeartbeats() {
        if (heartbeats != null) {
            return;
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "protege-mcp-broker-heartbeat");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::beat, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        heartbeats = executor;
    }

    private void stopHeartbeats() {
        ScheduledExecutorService executor = heartbeats;
        heartbeats = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private synchronized void beat() {
        if (windows.isEmpty()) {
            return;
        }
        List<InstanceRegistry.Window> regs = new ArrayList<>(windows.values());
        McpConfig config = McpConfig.load();
        try {
            if (processId != null && client != null
                    && client.heartbeat(processId, config.getToken(), config.getBrokerLingerMs(), regs)) {
                return;
            }
            // 404: the broker is alive but restarted — it lost us; fall through and re-register.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException | RuntimeException unreachable) {
            // The broker process itself is gone (crashed/killed): fall through to the throttled
            // re-discovery, which re-SPAWNS a broker — this must not be short-circuited by the
            // exception, or a dead broker would never be replaced until a new window opens.
            brokerBaseUrl = null;
        }
        processId = null;
        long now = System.currentTimeMillis();
        if (now - lastEnsureAttemptAt < ENSURE_THROTTLE_MS) {
            return;
        }
        lastEnsureAttemptAt = now;
        try {
            if (ensureClient(true)) {
                syncRegistration();
                log.info("protege-mcp: re-registered with the shared broker at {}", client.baseUrl());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("protege-mcp: broker re-attach failed (will retry)", e);
        }
    }

    private void unregisterQuietly() {
        if (processId != null && client != null) {
            try {
                client.unregister(processId);
            } catch (Exception e) {
                log.debug("protege-mcp: broker unregister failed (reaper will collect us)", e);
            }
        }
        processId = null;
    }

    private void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (this) {
                unregisterQuietly();
            }
        }, "protege-mcp-broker-unregister"));
    }
}
