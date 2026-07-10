package io.github.hakjuoh.protege_mcp.broker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * The broker's in-memory model of who is alive: every registered Protégé <em>process</em> and its
 * per-window MCP backends. This is the reference count that drives the broker's lifetime — when the
 * last process unregisters (or is reaped after its heartbeats stop / its pid dies), the broker shuts
 * itself down after a short linger.
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

    private final LongSupplier clock;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToWindow = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    /** Timestamp of the moment the registry last became empty; 0 while non-empty. */
    private volatile long emptySince;
    /** True once anything has ever registered (distinguishes "idle again" from "never used"). */
    private volatile boolean everRegistered;

    public InstanceRegistry(LongSupplier clock) {
        this.clock = clock;
        this.emptySince = clock.getAsLong();
    }

    /** Register a Protégé process and its windows; returns the process handle id for heartbeats. */
    public synchronized String register(long pid, String version, String token, List<Window> windows) {
        String processId = UUID.randomUUID().toString();
        Process p = new Process(processId, pid, version);
        p.token = token;
        p.lastSeenAt = clock.getAsLong();
        p.windows = index(windows);
        processes.put(processId, p);
        everRegistered = true;
        emptySince = 0;
        return processId;
    }

    /**
     * Refresh a process's liveness, token and window list. Returns false for an unknown process id
     * (e.g. the broker restarted) — the caller must re-register.
     */
    public synchronized boolean heartbeat(String processId, String token, List<Window> windows) {
        Process p = processes.get(processId);
        if (p == null) {
            return false;
        }
        p.token = token;
        p.lastSeenAt = clock.getAsLong();
        p.windows = index(windows);
        return true;
    }

    public synchronized void unregister(String processId) {
        if (processes.remove(processId) != null && processes.isEmpty()) {
            emptySince = clock.getAsLong();
        }
        dropOrphanedSessions();
    }

    /**
     * Drop processes whose heartbeats went stale or whose OS process died (crash safety — a killed
     * Protégé never unregisters). Returns how many were reaped.
     */
    public synchronized int reap(long staleAfterMs, LongPredicate pidAlive) {
        long now = clock.getAsLong();
        List<String> dead = new ArrayList<>();
        for (Process p : processes.values()) {
            if (now - p.lastSeenAt > staleAfterMs || !pidAlive.test(p.pid)) {
                dead.add(p.processId);
            }
        }
        for (String id : dead) {
            processes.remove(id);
        }
        if (!dead.isEmpty()) {
            if (processes.isEmpty()) {
                emptySince = now;
            }
            dropOrphanedSessions();
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

    /**
     * True when the broker should exit: it has (ever) served instances and has now been empty for at
     * least {@code lingerMs}, or nothing registered within {@code bootGraceMs} of construction.
     */
    public boolean shouldExit(long lingerMs, long bootGraceMs) {
        long since = emptySince;
        if (since == 0) {
            return false; // non-empty
        }
        long idleFor = clock.getAsLong() - since;
        return everRegistered ? idleFor >= lingerMs : idleFor >= bootGraceMs;
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
     * process's token. Two Protégé processes briefly report different tokens while a regeneration
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
                match = true; // no early exit — keep the comparison count independent of the input
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
        if (sessionId != null && !sessionId.isEmpty() && windowId != null) {
            sessionToWindow.put(sessionId, windowId);
        }
    }

    public Optional<Window> windowForSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }
        String windowId = sessionToWindow.get(sessionId);
        return windowId == null ? Optional.empty() : windowById(windowId);
    }

    public void unpinSession(String sessionId) {
        if (sessionId != null) {
            sessionToWindow.remove(sessionId);
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

    private void dropOrphanedSessions() {
        synchronized (sessionToWindow) {
            sessionToWindow.values().removeIf(windowId -> windowById(windowId).isEmpty());
        }
    }

    private static Map<String, Window> index(List<Window> windows) {
        Map<String, Window> byId = new LinkedHashMap<>();
        for (Window w : windows) {
            byId.put(w.id, w);
        }
        return Collections.unmodifiableMap(byId);
    }
}
