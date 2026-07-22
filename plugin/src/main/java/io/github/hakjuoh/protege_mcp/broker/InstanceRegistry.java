package io.github.hakjuoh.protege_mcp.broker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;

/**
 * The broker's in-memory model of who is alive: every registered Protege <em>process</em> and its
 * per-window MCP backends. This is the reference count that drives the broker's lifetime - when the
 * last process is proven dead, the broker shuts itself down after a short linger. Unregister and a
 * heartbeat that removes or replaces a window update routing immediately but do not prove that a
 * backend operation stopped, so retired endpoint incarnations remain revocation-only while the pid
 * is alive.
 *
 * <p>Also owns MCP session routing: a Streamable-HTTP session is pinned to the backend window that
 * created it (the {@code Mcp-Session-Id} response header at initialize time), because MCP session
 * state lives in that backend and cannot be migrated mid-session. New sessions go to the most
 * recently focused (falling back to most recently registered) window.
 *
 * <p>Pure logic with an injected clock so lifetime/reaping is unit-testable without sleeps.
 */
public final class InstanceRegistry {

    /** Sessions to remember; beyond this the least recently used pin is dropped (client re-inits). */
    private static final int MAX_SESSIONS = 500;
    private static final int MAX_REVOCATION_TOMBSTONES = 2_048;
    static final int MAX_PROCESSES = 32;
    static final int MAX_WINDOWS_PER_PROCESS = 128;
    static final int MAX_QUARANTINED_WINDOWS = MAX_PROCESSES * MAX_WINDOWS_PER_PROCESS;

    /** Cap for an instance-requested linger - mirrors the preference's one-hour bound, so a
     * corrupt or malicious registration payload cannot pin the broker process forever. */
    static final long MAX_REQUESTED_LINGER_MS = 3_600_000L;

    public static final class Window {
        public final String id;
        public final int port;
        public final String secret;
        public final String title;
        public final long focusedAt;
        public final long registeredAt;

        public Window(String id, int port, String secret, String title, long focusedAt, long registeredAt) {
            this.id = id;
            this.port = port;
            this.secret = secret;
            this.title = title;
            this.focusedAt = focusedAt;
            this.registeredAt = registeredAt;
        }
    }

    static final class Process {
        final String processId;
        final long pid;
        final String version;
        volatile String token;
        volatile long lastSeenAt;
        volatile Map<String, Window> windows = Map.of();

        Process(String processId, long pid, String version) {
            this.processId = processId;
            this.pid = pid;
            this.version = version;
        }
    }

    static final class EndpointKey {
        private final String id;
        private final int port;
        private final String secret;

        private EndpointKey(Window window) {
            this.id = window.id;
            this.port = window.port;
            this.secret = window.secret;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EndpointKey value
                    && port == value.port
                    && Objects.equals(id, value.id)
                    && Objects.equals(secret, value.secret);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, port, secret);
        }
    }

    record RevocationTarget(EndpointKey key, Window window) { }

    private record QuarantinedWindow(long pid, Window window) { }

    private final LongSupplier clock;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    /**
     * Routing-inactive processes whose OS pid is still live. Their last endpoint snapshot remains
     * eligible only for revocation fanout: a stale heartbeat or best-effort unregister cannot prove
     * that an in-flight backend operation has stopped.
     */
    private final Map<String, Process> quarantinedProcesses = new LinkedHashMap<>();
    /** Removed or replaced endpoint incarnations retained until their owning OS pid dies. */
    private final Map<EndpointKey, QuarantinedWindow> quarantinedWindows = new LinkedHashMap<>();
    private final Map<String, SessionPin> sessionToWindow = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SessionPin> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });
    /** Process-lifetime fences prevent a late upstream response from recreating a revoked pin. */
    private final Set<String> revokedSessionClients = new HashSet<>();
    private final Set<String> revokedSessionGrants = new HashSet<>();

    /** Timestamp of the moment the registry last became empty; 0 while non-empty. */
    private volatile long emptySince;
    /** True once anything has ever registered (distinguishes "idle again" from "never used"). */
    private volatile boolean everRegistered;
    /** Once quiescent shutdown commits, no late registration may race journal compaction. */
    private boolean sealedForShutdown;
    /**
     * Idle linger most recently requested by an instance (the user's preference, carried on every
     * register/heartbeat), or -1 while none has reported one. Deliberately retained after the
     * registry empties - the linger matters exactly then, after the last unregister.
     */
    private volatile long requestedLingerMs = -1;

    public InstanceRegistry(LongSupplier clock) {
        this.clock = clock;
        this.emptySince = clock.getAsLong();
    }

    /** Register a Protege process and its windows; returns the process handle id for heartbeats. */
    public synchronized String register(long pid, String version, String token, List<Window> windows) {
        if (sealedForShutdown) {
            throw new IllegalStateException("broker registry is shutting down");
        }
        Map<String, Window> indexedWindows = index(windows);
        long reclaimed = quarantinedProcesses.values().stream()
                .filter(process -> process.pid == pid)
                .count();
        long retained = processes.size() + quarantinedProcesses.size() - reclaimed;
        if (retained >= MAX_PROCESSES) {
            throw new IllegalStateException("broker process capacity is exhausted");
        }
        quarantinedProcesses.values().removeIf(process -> process.pid == pid);
        String processId = UUID.randomUUID().toString();
        Process p = new Process(processId, pid, version);
        p.token = token;
        p.lastSeenAt = clock.getAsLong();
        p.windows = indexedWindows;
        processes.put(processId, p);
        everRegistered = true;
        emptySince = 0;
        return processId;
    }

    /**
     * Refresh a process's liveness, token and window list. Returns false for an unknown process id
     * (e.g. the broker restarted) - the caller must re-register.
     */
    public synchronized boolean heartbeat(String processId, String token, List<Window> windows) {
        if (sealedForShutdown) return false;
        Process p = processes.get(processId);
        if (p == null) {
            return false;
        }
        Map<String, Window> indexedWindows = index(windows);
        retireMissingEndpoints(p.pid, p.windows, indexedWindows);
        p.token = token;
        p.lastSeenAt = clock.getAsLong();
        p.windows = indexedWindows;
        return true;
    }

    public synchronized void unregister(String processId) {
        Process removed = processes.get(processId);
        if (removed != null) {
            retireEndpoints(removed.pid, removed.windows.values());
            processes.remove(processId);
            quarantinedProcesses.put(removed.processId, removed);
            emptySince = 0;
        }
        dropOrphanedSessions();
    }

    /**
     * Drop processes whose heartbeats went stale or whose OS process died (crash safety - a killed
     * Protege never unregisters). Returns how many were reaped.
     */
    public synchronized int reap(long staleAfterMs, LongPredicate pidAlive) {
        long now = clock.getAsLong();
        List<String> dead = new ArrayList<>();
        Map<Long, Boolean> pidStatus = new LinkedHashMap<>();
        for (Process p : processes.values()) {
            boolean alive = pidStatus.computeIfAbsent(p.pid, pidAlive::test);
            if (now - p.lastSeenAt > staleAfterMs || !alive) {
                if (alive) retireEndpoints(p.pid, p.windows.values());
                dead.add(p.processId);
                if (alive) quarantinedProcesses.put(p.processId, p);
            }
        }
        for (String id : dead) {
            processes.remove(id);
        }
        if (!dead.isEmpty()) {
            dropOrphanedSessions();
        }
        quarantinedProcesses.values().removeIf(process ->
                !pidStatus.computeIfAbsent(process.pid, pidAlive::test));
        quarantinedWindows.values().removeIf(endpoint ->
                !pidStatus.computeIfAbsent(endpoint.pid, pidAlive::test));
        if (processes.isEmpty() && quarantinedProcesses.isEmpty()
                && quarantinedWindows.isEmpty()) {
            if (emptySince == 0) emptySince = now;
        } else {
            emptySince = 0;
        }
        return dead.size();
    }

    public int processCount() {
        return processes.size();
    }

    public int windowCount() {
        int n = 0;
        for (Process p : processes.values()) {
            n += p.windows.size();
        }
        return n;
    }

    synchronized int retainedProcessCount() {
        return processes.size() + quarantinedProcesses.size();
    }

    /**
     * Record the idle linger an instance asked for (ignored when negative, capped at
     * {@link #MAX_REQUESTED_LINGER_MS}). Instances on one machine share the preference store, so
     * last-writer-wins converges to the user's current setting within one heartbeat.
     */
    public void noteRequestedLinger(long lingerMs) {
        if (lingerMs >= 0) {
            requestedLingerMs = Math.min(lingerMs, MAX_REQUESTED_LINGER_MS);
        }
    }

    /** The linger to apply: the last instance-reported value, or {@code defaultMs} before any. */
    public long effectiveLingerMs(long defaultMs) {
        long requested = requestedLingerMs;
        return requested >= 0 ? requested : defaultMs;
    }

    /**
     * True when the broker should exit: it has (ever) served instances and has now been empty for at
     * least {@code lingerMs}, or nothing registered within {@code bootGraceMs} of construction.
     */
    public synchronized boolean shouldExit(long lingerMs, long bootGraceMs) {
        if (!quarantinedProcesses.isEmpty() || !quarantinedWindows.isEmpty()) return false;
        long since = emptySince;
        if (since == 0) {
            return false; // non-empty
        }
        long idleFor = clock.getAsLong() - since;
        return everRegistered ? idleFor >= lingerMs : idleFor >= bootGraceMs;
    }

    /** Atomically commit the empty-registry shutdown decision against register/heartbeat. */
    public synchronized boolean sealIfShouldExit(long lingerMs, long bootGraceMs) {
        return sealIfShouldExit(lingerMs, bootGraceMs, () -> { });
    }

    /** Run the final durable compaction while registration is excluded, then commit the seal. */
    public synchronized boolean sealIfShouldExit(long lingerMs, long bootGraceMs,
            Runnable beforeSeal) {
        if (!shouldExit(lingerMs, bootGraceMs)) return false;
        beforeSeal.run();
        sealedForShutdown = true;
        return true;
    }

    synchronized boolean shutdownEligible() {
        return !sealedForShutdown && processes.isEmpty() && quarantinedProcesses.isEmpty()
                && quarantinedWindows.isEmpty();
    }

    synchronized boolean sealForRequestedShutdown() {
        if (!shutdownEligible()) return false;
        sealedForShutdown = true;
        return true;
    }

    /** The static bearer token to accept, taken from the most recently seen process (live prefs). */
    public String latestToken() {
        Process latest = null;
        for (Process p : processes.values()) {
            if (latest == null || p.lastSeenAt > latest.lastSeenAt) {
                latest = p;
            }
        }
        return latest == null ? null : latest.token;
    }

    /**
     * Constant-time match of a presented static bearer token against <em>every</em> registered
     * process's token. Two Protege processes briefly report different tokens while a regeneration
     * propagates through preferences; accepting any current one keeps auth from flapping with the
     * heartbeat order.
     */
    public boolean matchesAnyToken(String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        byte[] presentedBytes = presented.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean match = false;
        for (Process p : processes.values()) {
            String token = p.token;
            if (token != null && java.security.MessageDigest.isEqual(
                    presentedBytes, token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                match = true; // no early exit - keep the comparison count independent of the input
            }
        }
        return match;
    }

    public Optional<Window> windowById(String windowId) {
        for (Process p : processes.values()) {
            Window w = p.windows.get(windowId);
            if (w != null) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    /** Routing default for new sessions: most recently focused, then most recently registered. */
    public Optional<Window> defaultWindow() {
        Window best = null;
        for (Process p : processes.values()) {
            for (Window w : p.windows.values()) {
                if (best == null
                        || w.focusedAt > best.focusedAt
                        || (w.focusedAt == best.focusedAt && w.registeredAt > best.registeredAt)) {
                    best = w;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public void pinSession(String sessionId, String windowId) {
        pinSession(sessionId, windowId, null);
    }

    public void pinSession(String sessionId, String windowId, AuthenticatedPrincipal principal) {
        if (sessionId != null && !sessionId.isEmpty() && windowId != null) {
            String clientId = principal == null ? null : principal.clientId();
            String grantId = principal == null ? null : principal.grantId();
            synchronized (sessionToWindow) {
                if (clientId != null && (revokedSessionClients.contains(clientId)
                        || revokedSessionGrants.contains(grantKey(clientId, grantId)))) {
                    return;
                }
                sessionToWindow.put(sessionId, new SessionPin(windowId, clientId, grantId));
            }
        }
    }

    public Optional<Window> windowForSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }
        SessionPin pin = sessionToWindow.get(sessionId);
        return pin == null ? Optional.empty() : windowById(pin.windowId);
    }

    public boolean sessionOwnedBy(String sessionId, AuthenticatedPrincipal principal) {
        if (sessionId == null || sessionId.isEmpty()) return true;
        SessionPin pin = sessionToWindow.get(sessionId);
        if (pin == null || pin.clientId == null) return true; // compatibility pin from an older request
        return principal != null && pin.clientId.equals(principal.clientId())
                && java.util.Objects.equals(pin.grantId, principal.grantId());
    }

    public void unpinSession(String sessionId) {
        if (sessionId != null) {
            sessionToWindow.remove(sessionId);
        }
    }

    public int dropSessionsForPrincipal(String clientId) {
        if (clientId == null) return 0;
        synchronized (sessionToWindow) {
            rememberRevocation(revokedSessionClients, clientId);
            int before = sessionToWindow.size();
            sessionToWindow.values().removeIf(pin -> clientId.equals(pin.clientId));
            return before - sessionToWindow.size();
        }
    }

    public int dropSessionsForGrant(String clientId, String grantId) {
        if (clientId == null || grantId == null) return 0;
        synchronized (sessionToWindow) {
            rememberRevocation(revokedSessionGrants, grantKey(clientId, grantId));
            int before = sessionToWindow.size();
            sessionToWindow.values().removeIf(pin -> clientId.equals(pin.clientId)
                    && grantId.equals(pin.grantId));
            return before - sessionToWindow.size();
        }
    }

    void prepareClientRevocation(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("client id is required");
        }
        synchronized (sessionToWindow) {
            rememberRevocation(revokedSessionClients, clientId);
        }
    }

    void prepareGrantRevocation(String clientId, String grantId) {
        if (clientId == null || clientId.isBlank() || grantId == null || grantId.isBlank()) {
            throw new IllegalArgumentException("grant identity is required");
        }
        synchronized (sessionToWindow) {
            rememberRevocation(revokedSessionGrants, grantKey(clientId, grantId));
        }
    }

    /** Windows across all processes, for the {@code /instances} listing (no secrets included). */
    public List<Window> listWindows() {
        List<Window> all = new ArrayList<>();
        for (Process p : processes.values()) {
            all.addAll(p.windows.values());
        }
        all.sort((a, b) -> Long.compare(b.registeredAt, a.registeredAt));
        return all;
    }

    /**
     * Active and quarantine-only endpoint snapshots for execution-fence delivery. Only an exactly
     * identical active endpoint deduplicates its retired snapshot; quarantined windows are never
     * routable.
     */
    synchronized List<RevocationTarget> revocationTargets() {
        Map<EndpointKey, Window> byEndpoint = new LinkedHashMap<>();
        quarantinedWindows.forEach((key, value) -> byEndpoint.put(key, value.window));
        for (Process p : processes.values()) {
            p.windows.values().forEach(window -> byEndpoint.put(new EndpointKey(window), window));
        }
        List<RevocationTarget> all = byEndpoint.entrySet().stream()
                .map(entry -> new RevocationTarget(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Long.compare(
                        b.window.registeredAt, a.window.registeredAt))
                .toList();
        return all;
    }

    synchronized List<Window> revocationWindows() {
        return revocationTargets().stream().map(RevocationTarget::window).toList();
    }

    private void dropOrphanedSessions() {
        synchronized (sessionToWindow) {
            sessionToWindow.values().removeIf(pin -> windowById(pin.windowId).isEmpty());
        }
    }

    private static Map<String, Window> index(List<Window> windows) {
        if (windows.size() > MAX_WINDOWS_PER_PROCESS) {
            throw new IllegalArgumentException("too many broker windows");
        }
        Map<String, Window> byId = new LinkedHashMap<>();
        for (Window w : windows) {
            byId.put(w.id, w);
        }
        return Collections.unmodifiableMap(byId);
    }

    private void retireMissingEndpoints(long pid, Map<String, Window> previous,
            Map<String, Window> current) {
        Set<EndpointKey> currentEndpoints = new HashSet<>();
        current.values().forEach(window -> currentEndpoints.add(new EndpointKey(window)));
        retireEndpoints(pid, previous.values().stream()
                .filter(window -> !currentEndpoints.contains(new EndpointKey(window)))
                .toList());
    }

    private void retireEndpoints(long pid, java.util.Collection<Window> windows) {
        List<Map.Entry<EndpointKey, QuarantinedWindow>> additions = windows.stream()
                .map(window -> Map.entry(new EndpointKey(window),
                        new QuarantinedWindow(pid, window)))
                .filter(entry -> !quarantinedWindows.containsKey(entry.getKey()))
                .toList();
        if (quarantinedWindows.size() + additions.size() > MAX_QUARANTINED_WINDOWS) {
            throw new IllegalStateException("broker quarantined window capacity is exhausted");
        }
        additions.forEach(entry -> quarantinedWindows.put(entry.getKey(), entry.getValue()));
    }

    private static String grantKey(String clientId, String grantId) {
        if (clientId == null || grantId == null) return "";
        return clientId.length() + ":" + clientId + grantId.length() + ":" + grantId;
    }

    private void rememberRevocation(Set<String> target, String value) {
        if (target.contains(value)) return;
        if (revokedSessionClients.size() + revokedSessionGrants.size()
                >= MAX_REVOCATION_TOMBSTONES) {
            throw new IllegalStateException("session revocation tombstone capacity is exhausted");
        }
        target.add(value);
    }

    private record SessionPin(String windowId, String clientId, String grantId) { }
}
