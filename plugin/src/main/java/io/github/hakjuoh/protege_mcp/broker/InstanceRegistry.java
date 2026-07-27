package io.github.hakjuoh.protege_mcp.broker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    /**
     * How long a retired registration or endpoint is held on the strength of its OS pid still being
     * alive. An OS pid is not an identity: the kernel reuses it, so a Protege that dies can have its
     * pid inherited by an unrelated long-lived process, and "pid alive" then never becomes false. Left
     * unbounded, that one coincidence pins the quarantine - and with it {@link #shouldExit()} - for as
     * long as the machine is up, so the broker outlives every instance it was refcounting. Quarantine
     * exists so a revocation fence still reaches a backend that may not have finished; nothing
     * legitimately needs it half an hour after the registration stopped heartbeating, and routing
     * never consults quarantine at all.
     */
    static final long MAX_QUARANTINE_MS = 30 * 60_000L;
    /** Window ids to name in a revocation result after their endpoint aged out; the count is exact. */
    static final int MAX_UNATTESTED_WINDOWS = 256;

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

    /**
     * The identity of one endpoint <em>incarnation</em>: its window id, the port it listens on and the
     * per-window broker secret. All three are minted together and none is reused — a window id is a fresh
     * UUID each time a window attaches, and the secret a fresh token each time its server starts — so a
     * second incarnation can never present this same triple, and an equal key is the same live endpoint
     * registering again (a heartbeat the broker missed, say). That is what lets a revocation fanout keep
     * an acknowledgement across re-registrations without ever carrying it over to a new incarnation.
     */
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

    /**
     * One endpoint a fence is sent to, with the OS pid behind it. The pid is not part of the endpoint's
     * identity — {@link EndpointKey} is that — but it is how an obligation is written down, here and in the
     * journal that outlives this registry, so a fanout needs it to tell whether a record it is carrying is
     * about a window it is fencing right now or about one nothing can reach any more.
     */
    record RevocationTarget(long pid, EndpointKey key, Window window) { }

    /**
     * An endpoint released on age alone: the window id a revocation result names it by, and the OS pid
     * that was still reporting alive when its record went. The pid is the only handle on it that outlives
     * this registry, so it is what the journal hands to a successor and what settles the obligation.
     */
    record UnattestedEndpoint(String windowId, long pid) { }

    /**
     * A retired endpoint incarnation held for revocation. {@code unrecordable} marks the one kind that is
     * held past its retention age too: an endpoint whose obligation neither the window nor the process
     * bound could record, kept as a fence target because releasing it would forget it. It is set by
     * {@link #expireAgedQuarantine} and cleared by the endpoint being retired again, which starts its
     * ordinary retention over.
     */
    private record QuarantinedWindow(long pid, Window window, long retiredAt, boolean unrecordable) { }

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
    /**
     * Endpoints whose retired record was released on age alone, each mapped to the window id a result
     * names it by and the OS pid that owned it. A fence for them can no longer be delivered or disproved,
     * so they are reported unacknowledged from then on rather than vanishing into a clean revocation
     * result. Keyed by the endpoint rather than by that id: one window id can cover several incarnations,
     * and a fanout has to be able to tell the one it holds an acknowledgement for from the one it does
     * not. The pid is what a later broker generation can still ask the OS about, so an obligation handed
     * on in the journal carries it.
     */
    private final Map<EndpointKey, UnattestedEndpoint> unattestedEndpointIds = new LinkedHashMap<>();
    /**
     * The processes whose aged-out endpoints this registry could no longer name individually. Past the id
     * bound a window cannot be listed, but the pid that owned it can, and the pid is the whole of what
     * settles such an obligation — so the ones beyond the bound are folded onto their process instead of
     * being left as a tally nothing outside this generation could be told about. One entry stands for
     * however many windows of that process overflowed.
     */
    private final Set<Long> unattestedProcesses = new LinkedHashSet<>();
    /**
     * How many windows of a process were released against a held record's proof, per process. These are the
     * one kind of release that writes nothing down at all — every way of recording an obligation is full,
     * and what stands for the process is an endpoint of it kept in quarantine rather than a note — so they
     * would otherwise be increments nothing could ever take back off {@link #unattestedEndpoints}. The proof
     * that settles them is the same one the held record answers to, that pid being gone, and it has to
     * settle these too: a broker that ever reached this state would answer every revocation asked of it
     * afterwards unconfirmed, for the rest of its life, with nothing anywhere left to fence. Bounded like
     * every other record here; a pid that cannot get an entry leaves its release counted and undischargeable,
     * which over-reports what is unfenced rather than under-reporting it.
     */
    private final Map<Long, Integer> unattestedHeldOverflow = new LinkedHashMap<>();
    private int unattestedEndpoints;
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
        if (pid <= 0) {
            // Every reclaim, quarantine and reap decision keys off the OS pid. A registration without
            // a real one can never be proven dead, so it would hold the broker open forever and its
            // retired endpoints would never leave quarantine.
            throw new IllegalArgumentException("broker registration requires an OS pid");
        }
        Map<String, Window> indexedWindows = index(windows);
        refuseForeignWindowIds(pid, indexedWindows);
        List<Process> superseded = processes.values().stream()
                .filter(process -> process.pid == pid)
                .toList();
        long reclaimed = superseded.size() + quarantinedProcesses.values().stream()
                .filter(process -> process.pid == pid)
                .count();
        long retained = processes.size() + quarantinedProcesses.size() - reclaimed;
        if (retained >= MAX_PROCESSES) {
            throw new IllegalStateException("broker process capacity is exhausted");
        }
        // A process registering again replaces its own earlier registration: leaving that one active
        // would let routing keep resolving windows it alone knows (a closed view answering on a reused
        // port), and would spend capacity on a registration nothing will ever heartbeat again. Retire
        // before removing, so endpoints the new registration no longer carries reach quarantine and a
        // revocation fanout still finds them while the pid lives.
        for (Process replaced : superseded) {
            retireMissingEndpoints(pid, replaced.windows, indexedWindows);
            processes.remove(replaced.processId);
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
        refuseForeignWindowIds(p.pid, indexedWindows);
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
        // A retired registration leaves quarantine when its pid dies OR when it has been held for
        // MAX_QUARANTINE_MS: a pid the kernel handed to some other process would otherwise keep
        // testing alive forever. Death is settled first, because this very pass may be the one that
        // proves it: an endpoint whose process the kernel has already reaped is not owed a fence, and
        // ageing it out first would leave the broker holding an obligation for it - reported
        // unacknowledged for the rest of its life - that no fence could ever discharge. Ageing out is
        // for pids this broker can no longer read anything into, never for one it just read dead.
        quarantinedProcesses.values().removeIf(process ->
                !pidStatus.computeIfAbsent(process.pid, pidAlive::test));
        boolean quarantineEvicted = quarantinedWindows.values().removeIf(endpoint ->
                !pidStatus.computeIfAbsent(endpoint.pid, pidAlive::test));
        quarantineEvicted |= expireAgedQuarantine(now);
        dischargeProvenDeadObligations(pidStatus, pidAlive);
        // After the quarantine eviction, never before it: a session pinned to a window whose endpoint
        // is only quarantined is still recoverable, and dropping the pin first would turn a transient
        // stale heartbeat into a permanently dead session for a client that is still connected.
        if (!dead.isEmpty() || quarantineEvicted) {
            dropOrphanedSessions();
        }
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
        return sealForRequestedShutdown(() -> { });
    }

    /**
     * Commit an asked-for shutdown, running the same final durable compaction the idle exit runs.
     *
     * <p>Asked for or timed out, the shutdown ends this generation's memory just the same, and what that
     * memory holds includes obligations for endpoints released on age alone. A seal that skipped the
     * compaction would hand a successor a journal that never heard of them, and the successor would
     * confirm a fence for an endpoint nothing proved stopped - which is the one thing these obligations
     * exist to prevent. This path is the one a version takeover uses, so it is the likelier of the two.
     *
     * <p>{@code beforeSeal} throwing refuses the shutdown: the obligations could not be written down, so
     * ending the memory that holds them is not allowed yet, and the caller is told to keep this broker.
     */
    synchronized boolean sealForRequestedShutdown(Runnable beforeSeal) {
        if (!shutdownEligible()) return false;
        beforeSeal.run();
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
        Map<EndpointKey, RevocationTarget> byEndpoint = new LinkedHashMap<>();
        quarantinedWindows.forEach((key, value) ->
                byEndpoint.put(key, new RevocationTarget(value.pid, key, value.window)));
        for (Process p : processes.values()) {
            p.windows.values().forEach(window -> {
                EndpointKey key = new EndpointKey(window);
                byEndpoint.put(key, new RevocationTarget(p.pid, key, window));
            });
        }
        List<RevocationTarget> all = byEndpoint.values().stream()
                .sorted((a, b) -> Long.compare(
                        b.window().registeredAt, a.window().registeredAt))
                .toList();
        return all;
    }

    synchronized List<Window> revocationWindows() {
        return revocationTargets().stream().map(RevocationTarget::window).toList();
    }

    /**
     * Forget session pins whose window can no longer come back. A pin whose window is only in
     * quarantine is kept: its owning pid is still alive, so the instance may re-register that same
     * window id (a stale heartbeat reaped a live process, and the very next beat re-registers it), and
     * the pinned MCP session then resumes. Unpinning there would make the client re-initialize and
     * lose its server-side session state for nothing. Routing stays honest either way — it resolves
     * only through {@link #windowById}, so a quarantined pin routes nowhere until the window is back.
     */
    private void dropOrphanedSessions() {
        synchronized (sessionToWindow) {
            sessionToWindow.values().removeIf(pin -> windowById(pin.windowId).isEmpty()
                    && quarantinedWindow(pin.windowId).isEmpty());
        }
    }

    /** A retired endpoint incarnation of this window id, still held because its pid is alive. */
    private Optional<Window> quarantinedWindow(String windowId) {
        if (windowId == null) {
            return Optional.empty();
        }
        for (QuarantinedWindow quarantined : quarantinedWindows.values()) {
            if (windowId.equals(quarantined.window.id)) {
                return Optional.of(quarantined.window);
            }
        }
        return Optional.empty();
    }

    /**
     * Refuse a registration carrying a window id another live process already holds. A window id has to
     * name one endpoint for the whole broker, not one per process: {@link #windowById} answers with the
     * first process that has it, so a collision makes routing — and every session pinned by id — depend on
     * map order, and {@code /instances} would advertise two endpoints under the same path. Protege windows
     * carry random ids, so an honest instance never collides; refusing keeps a broker that is spoken to by
     * something else from silently redirecting a live session.
     *
     * <p>Only live registrations count. A quarantined endpoint is already retired and routable only through
     * a pin, and a process replacing its own earlier registration keeps its ids by design, so neither may
     * turn a legitimate re-registration into a refusal.
     */
    private void refuseForeignWindowIds(long pid, Map<String, Window> windows) {
        for (Process other : processes.values()) {
            if (other.pid == pid) {
                continue;
            }
            for (String id : windows.keySet()) {
                if (other.windows.containsKey(id)) {
                    throw new IllegalArgumentException("duplicate broker window id");
                }
            }
        }
    }

    /**
     * Index a registration's windows by id. Every well-formed endpoint in the payload has to survive
     * into the map: one the registry drops is one it can neither route to nor fence, so a revocation
     * would report confirmed while that endpoint kept serving the credential. Both ways an entry could
     * be lost - a list past the bound, or a second entry reusing an id already taken - refuse the whole
     * registration instead. Callers index before mutating anything, so the refusal changes nothing and
     * the instance retries; its windows reach quarantine on the reap rather than vanishing.
     */
    private static Map<String, Window> index(List<Window> windows) {
        if (windows.size() > MAX_WINDOWS_PER_PROCESS) {
            throw new IllegalArgumentException("too many broker windows");
        }
        Map<String, Window> byId = new LinkedHashMap<>();
        for (Window w : windows) {
            if (byId.put(w.id, w) != null) {
                throw new IllegalArgumentException("duplicate broker window id");
            }
        }
        return Collections.unmodifiableMap(byId);
    }

    /**
     * Release retired registrations held longer than {@link #MAX_QUARANTINE_MS}, regardless of what
     * their pid now reports. Returns whether any endpoint went, so the caller can drop the session
     * pins that were waiting for it.
     *
     * <p>Ageing out frees the record, never the obligation: an endpoint that leaves this way was not
     * proven stopped, so its window id is remembered as unattested and every later fanout reports it
     * unacknowledged. That keeps what a revocation claims identical to what it claimed before this
     * bound existed — where the endpoint stayed and its fence POST failed instead — so the bound is
     * about memory, the broker's lifetime and routing, and nothing about attestation.
     *
     * <p>Which is why one endpoint per process does not age out at all: the one whose obligation nothing
     * here can record, because both the window bound and the process bound are full. That record stays in
     * quarantine — a fence target, reported unacknowledged — rather than becoming an obligation only this
     * generation's memory holds. One per process, because that is the grain such an obligation is owed at:
     * the record is settled by its pid being gone, which is equally the proof for every other endpoint of
     * that process, so a second unrecordable window of a process already holding one is released and
     * counted like any overflow onto {@link #unattestedProcesses}. Held records therefore cannot outgrow
     * the machine's live processes, and the ordinary quarantine keeps draining around them.
     */
    private boolean expireAgedQuarantine(long now) {
        // lastSeenAt / retiredAt are each the last moment that registration was real.
        quarantinedProcesses.values().removeIf(process -> now - process.lastSeenAt > MAX_QUARANTINE_MS);
        List<EndpointKey> released = new ArrayList<>();
        Map<EndpointKey, QuarantinedWindow> held = new LinkedHashMap<>();
        for (Map.Entry<EndpointKey, QuarantinedWindow> entry : quarantinedWindows.entrySet()) {
            QuarantinedWindow endpoint = entry.getValue();
            if (now - endpoint.retiredAt() <= MAX_QUARANTINE_MS) {
                continue;
            }
            // One endpoint is one obligation, however often it comes back and ages out again: the count
            // and the ids have to describe the same set, or a fanout that recognises an obligation it
            // already fenced would still be left with a window it can never name or account for. Past the
            // id bound a repeat cannot be recognised, so it is counted again - an over-count keeps the
            // revocation unconfirmable, which is the safe direction.
            EndpointKey key = entry.getKey();
            if (unattestedEndpointIds.containsKey(key)) {
                released.add(key);
                continue;
            }
            if (unattestedEndpointIds.size() < MAX_UNATTESTED_WINDOWS) {
                unattestedEndpointIds.put(key, new UnattestedEndpoint(endpoint.window().id, endpoint.pid()));
            } else if (unattestedProcesses.contains(endpoint.pid())
                    || unattestedProcesses.size() < MAX_UNATTESTED_WINDOWS) {
                // The window is past the id bound, so nothing can name it - but its process can be named,
                // and a pid is the whole of what settles an obligation for a window that will not come
                // back. Folding the overflow onto the process keeps it something a journal can hand to a
                // successor: an obligation counted here and nowhere else would be lost at the shutdown
                // that ends this generation's memory of it, and a successor would then confirm a fence for
                // an endpoint that may still be serving. One entry covers however many of that process's
                // windows overflow, so a process already recorded absorbs its next window whatever the
                // bound says.
                unattestedProcesses.add(endpoint.pid());
            } else if (holdsUnrecordableEndpointOf(endpoint.pid(), key, held)) {
                // Nothing can record this obligation either, but this process is already owed a fence for
                // an endpoint kept exactly because of that - and that record answers to the same proof
                // this one would: the pid being gone. Keeping a second of them would buy nothing a
                // revocation reads differently and would let held records grow with windows rather than
                // with processes, so this one is released and counted, exactly like an overflow folded
                // onto a process. Counted against that pid, because a count nothing is filed under is a
                // count nothing can settle: this release answers to the same proof the record it defers to
                // does, and has to be given back to the tally when that proof arrives.
                countHeldOverflow(endpoint.pid());
                unattestedEndpoints = saturatingIncrement(unattestedEndpoints);
                released.add(key);
                continue;
            } else {
                // Every way of recording this obligation is full: its window cannot be named, its process
                // cannot be either, and no endpoint of that process is being held for it already.
                // Releasing the record here would be the one outcome that is not allowed - an endpoint
                // that may still be serving forgotten, and a later fence reported confirmed with nothing
                // left to have confirmed it - so the record is kept instead. Kept, it stays exactly what
                // it was before any of these bounds existed: a fence target this broker still sends to and
                // still reports unacknowledged, which is strictly better than any obligation record,
                // because a backend that is still there actually receives the fence. What that costs is
                // retention - the endpoint holds its quarantine slot and this broker stops idle-exiting
                // while it does - and retention is a cost a bound may impose. Forgetting is not.
                held.put(key, new QuarantinedWindow(endpoint.pid(), endpoint.window(),
                        endpoint.retiredAt(), true));
                continue;
            }
            unattestedEndpoints = saturatingIncrement(unattestedEndpoints);
            released.add(key);
        }
        held.forEach(quarantinedWindows::put);
        released.forEach(quarantinedWindows::remove);
        return !released.isEmpty();
    }

    /**
     * Whether this process already has an endpoint held past its retention age because nothing could
     * record the obligation for it — counting the ones marked in the pass now running, so two of a
     * process's windows ageing out together cannot both be kept. The endpoint being examined does not
     * answer for itself: it is the record that would be released, and a record cannot make itself
     * redundant.
     */
    private boolean holdsUnrecordableEndpointOf(long pid, EndpointKey examined,
            Map<EndpointKey, QuarantinedWindow> markedThisPass) {
        for (Map.Entry<EndpointKey, QuarantinedWindow> entry : quarantinedWindows.entrySet()) {
            QuarantinedWindow endpoint = entry.getValue();
            if (endpoint.pid() == pid && !entry.getKey().equals(examined)
                    && (endpoint.unrecordable() || markedThisPass.containsKey(entry.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Count one window released against a held record's proof, under the pid whose record it deferred to.
     * A pid already counted takes its next one whatever the bound says, exactly as a recorded process
     * absorbs its next overflow: the entry is what the proof will be looked up by, not what says how many.
     * Saturates rather than wrapping, and a pid the bound has no room for is simply not counted here — the
     * release is still on the tally, and stays there for the life of this broker.
     */
    private void countHeldOverflow(long pid) {
        Integer counted = unattestedHeldOverflow.get(pid);
        if (counted != null) {
            unattestedHeldOverflow.put(pid, saturatingIncrement(counted));
        } else if (unattestedHeldOverflow.size() < MAX_UNATTESTED_WINDOWS) {
            unattestedHeldOverflow.put(pid, 1);
        }
    }

    /**
     * Discharge the obligations this registry is owed for processes the OS no longer has, and take them
     * off the tally. Runs on every reap, after the eviction and the expiry, with the same liveness probe
     * and the same cached answers - so an endpoint that ages out in this pass is judged against this
     * pass's reading of its pid rather than the next one's.
     *
     * <p>The proof is the one every other part of this fence accepts, and the one this reap already acts
     * on when it evicts a quarantined endpoint: a pid the kernel has reaped is a process serving nothing,
     * so no window of it is owed a fence any longer. Without this the tally only ever grew, and one wedged
     * instance that aged out and later exited would leave every revocation this broker was asked for
     * afterwards answering unconfirmed with nothing left anywhere to fence - the latch the journal's own
     * discharge exists to avoid, and the same one the eviction order above is written to avoid.
     *
     * <p>A record that stood for a process rather than a window comes off as one, though it may have
     * covered several of that process's windows: how many those were is not recorded per pid, so the tally
     * keeps counting them. That over-reports what is unfenced, which keeps revocations unconfirmed - the
     * safe way to be wrong. The releases counted against a held record's proof are the exception, because
     * how many of those there were is the one thing {@link #unattestedHeldOverflow} does record, so they
     * all come off together. A tally that has saturated is not decremented at all, for the same reason
     * the process record comes off as one: past that point it no longer knows what it is counting.
     */
    private void dischargeProvenDeadObligations(Map<Long, Boolean> pidStatus, LongPredicate pidAlive) {
        if (unattestedEndpointIds.isEmpty() && unattestedProcesses.isEmpty()
                && unattestedHeldOverflow.isEmpty()) {
            return;
        }
        long discharged = 0;
        for (Iterator<UnattestedEndpoint> owed = unattestedEndpointIds.values().iterator();
                owed.hasNext();) {
            if (!pidStatus.computeIfAbsent(owed.next().pid(), pidAlive::test)) {
                owed.remove();
                discharged++;
            }
        }
        for (Iterator<Long> owed = unattestedProcesses.iterator(); owed.hasNext();) {
            if (!pidStatus.computeIfAbsent(owed.next(), pidAlive::test)) {
                owed.remove();
                discharged++;
            }
        }
        for (Iterator<Map.Entry<Long, Integer>> owed = unattestedHeldOverflow.entrySet().iterator();
                owed.hasNext();) {
            Map.Entry<Long, Integer> counted = owed.next();
            if (!pidStatus.computeIfAbsent(counted.getKey(), pidAlive::test)) {
                discharged += counted.getValue();
                owed.remove();
            }
        }
        unattestedEndpoints = saturatingDecrement(unattestedEndpoints, discharged);
    }

    /**
     * One more unattested endpoint, or the same count once it can no longer grow. This tally is compared
     * against zero to decide whether a fence obligation is still owed and added to a fanout's window
     * total, and an int that wrapped would read as a negative: an idle broker would compact a live
     * obligation away as "nothing owed", and a revocation would report fewer windows than it fenced.
     * Saturating keeps both readings in the safe direction - still owed, still unconfirmable.
     * Package-private so the arithmetic is testable without ageing out two billion endpoints.
     */
    static int saturatingIncrement(int count) {
        return count == Integer.MAX_VALUE ? count : count + 1;
    }

    /**
     * The same tally with {@code discharged} settled obligations taken off it, in the same safe direction.
     * A saturated tally is left alone: past that point it no longer knows which increments it is made of,
     * so subtracting from it would be arithmetic on a number that has stopped meaning one obligation each.
     * Never below zero either — each discharged record was counted at least once, so a tally that read low
     * would say a lie is possible here that the clamp keeps impossible: fewer owed than are owed.
     */
    static int saturatingDecrement(int count, long discharged) {
        if (discharged <= 0 || count == Integer.MAX_VALUE) return count;
        return (int) Math.max(0, count - discharged);
    }

    /**
     * Window ids whose retired endpoint left quarantine on age alone, so no fanout can prove its fence
     * landed. Reported as unacknowledged until something proves the endpoint stopped, and only one thing
     * can: its process being gone, which {@link #dischargeProvenDeadObligations} looks for on every reap.
     * Ageing out is not that proof — "we stopped tracking it" never becomes evidence that it stopped — so
     * absent that proof these are reported for the rest of this broker's life.
     */
    synchronized List<String> unattestedRevocationWindowIds() {
        return unattestedEndpointIds.values().stream().map(UnattestedEndpoint::windowId).toList();
    }

    /**
     * The same obligations by endpoint identity, so a fanout can recognise one it already holds an
     * acknowledgement from — its own fence landed before the record aged out from under it. Each is named
     * the way a journal names one, window id and pid, because a fanout also has to recognise an obligation
     * an earlier generation already wrote down: that is one window owed one fence, counted here or on file
     * but not in both.
     */
    synchronized Map<EndpointKey, UnattestedEndpoint> unattestedRevocationEndpoints() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unattestedEndpointIds));
    }

    /**
     * The obligations this registry can still name, with the pid that owned each one, for a journal that
     * has to outlive it. Only the named ones: past {@link #MAX_UNATTESTED_WINDOWS} a window has no entry
     * here, and what is left of such an obligation is its process - see
     * {@link #unattestedProcessObligations()}, which is what the same journal carries it as.
     */
    synchronized List<UnattestedEndpoint> unattestedObligations() {
        return List.copyOf(unattestedEndpointIds.values());
    }

    /**
     * The processes owed a fence for a window this registry could not name, for the same journal. Each is
     * an obligation in its own right and is settled by its process exiting and by nothing else: which
     * window of it overflowed was never recorded, so no registration can be read as that window coming
     * back. A pid already carried by a named obligation is listed here too when a further window of it
     * overflows, because the two are discharged on different evidence and the named one may go first.
     */
    synchronized List<Long> unattestedProcessObligations() {
        return List.copyOf(unattestedProcesses);
    }

    /**
     * The endpoints a fence can still be delivered to, each named the way an obligation is: the window id
     * and the pid that owns it. Both live registrations and the retired incarnations still held in
     * quarantine, because {@link #revocationTargets()} sends to both - so a window in this set is one a
     * durable retry reaches, and one this registry will record an obligation for itself if it ever goes
     * unproven from here.
     *
     * <p>Named per window and not per pid, because a registration is not a report that the process's other
     * endpoints stopped: this registry deliberately reads a window missing from one as retired-but-unproven
     * and quarantines it (see {@link #retireMissingEndpoints}), so "that pid registered again" says nothing
     * about the window an earlier generation was owed a fence for. The window being reachable again does say
     * something about it, and about every earlier incarnation of it: one window has one server at a time, so
     * a later incarnation exists only because the one before it was stopped to make room.
     */
    synchronized Set<UnattestedEndpoint> fenceableEndpoints() {
        Set<UnattestedEndpoint> reachable = new LinkedHashSet<>();
        processes.values().forEach(process -> process.windows.values().forEach(window ->
                reachable.add(new UnattestedEndpoint(window.id, process.pid))));
        quarantinedWindows.values().forEach(endpoint ->
                reachable.add(new UnattestedEndpoint(endpoint.window.id, endpoint.pid)));
        return reachable;
    }

    /** How many endpoints aged out unattested, including any past the id-retention bound. */
    synchronized int unattestedEndpointCount() {
        return unattestedEndpoints;
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
        long retiredAt = clock.getAsLong();
        // Expiry first, and never only in reap: an exhausted quarantine makes this method throw, which
        // would refuse registrations and abort the very reap pass that releases it.
        if (expireAgedQuarantine(retiredAt)) {
            dropOrphanedSessions();
        }
        List<Map.Entry<EndpointKey, QuarantinedWindow>> retirements = windows.stream()
                .map(window -> Map.entry(new EndpointKey(window),
                        new QuarantinedWindow(pid, window, retiredAt, false)))
                .toList();
        long additions = retirements.stream()
                .filter(entry -> !quarantinedWindows.containsKey(entry.getKey()))
                .count();
        if (quarantinedWindows.size() + additions > MAX_QUARANTINED_WINDOWS) {
            throw new IllegalStateException("broker quarantined window capacity is exhausted");
        }
        // Re-stamped, not skipped, when the endpoint is already held: an endpoint that came back and
        // was retired again starts its retention now. Keeping the first retirement's timestamp would
        // expire a just-retired endpoint immediately and end its revocation coverage.
        retirements.forEach(entry -> quarantinedWindows.put(entry.getKey(), entry.getValue()));
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
