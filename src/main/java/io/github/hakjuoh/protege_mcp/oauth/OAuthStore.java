package io.github.hakjuoh.protege_mcp.oauth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * State for the embedded OAuth 2.1 authorization server: dynamically registered clients, one-time
 * authorization codes (bound to PKCE challenge + redirect + resource), and access/refresh tokens.
 *
 * <p>Registered clients and access/refresh tokens are <b>persisted</b> across restarts via the
 * injected load/save hooks (the Protégé preferences store), so a client that connected once keeps
 * working after Protégé restarts instead of failing re-authorization with "Unknown client".
 * Authorization codes are intentionally <b>not</b> persisted — they are 2-minute, mid-flow only.
 *
 * <p>Access-token validation also accepts the plugin's static bearer token, so manual
 * {@code --header "Authorization: Bearer <token>"} setups keep working alongside OAuth.
 *
 * <p>For the "Connected clients" view, each client tracks when it was registered and last made an
 * authenticated request ({@link #listClients()}), and individual clients/tokens can be revoked
 * ({@link #revokeClient(String)}, {@link #revokeToken(String)}).
 *
 * <p>Dead registrations clean themselves up — no manual revoking after a client reconnects: when a
 * re-registered client completes authorization, the same-name registrations it replaced are dropped
 * (see {@code removeSupersededSiblings}), and {@link #sweepInactiveClients()} reaps abandoned
 * auth flows after {@link #ABANDONED_CLIENT_GRACE_MS} and clients silent past
 * {@link #INACTIVE_CLIENT_TTL_MS}.
 */
public final class OAuthStore {

    private static final Logger log = LoggerFactory.getLogger(OAuthStore.class);

    private static final long CODE_TTL_MS = 120_000L;            // 2 minutes
    private static final long ACCESS_TTL_MS = 30L * 24 * 3600 * 1000; // 30 days (localhost, single user)
    /**
     * A registration holding no token and no pending code (an abandoned auth flow) is cleaned up
     * automatically once this old. The consent phase has no code yet (it is minted on Allow), so
     * this must out-wait a human parked on the consent page: every authorize touch also counts as
     * activity ({@link #noteClientActivity}), so the hour runs from the last consent-page render,
     * not from registration.
     */
    static final long ABANDONED_CLIENT_GRACE_MS = 60 * 60_000L;  // 1 hour
    /** A client that has not authenticated anything this long is cleaned up, tokens and all. */
    static final long INACTIVE_CLIENT_TTL_MS = 60L * 24 * 3600 * 1000; // 60 days (2x access TTL)
    /** Margin under java.util.prefs' 8192-char per-value limit; above this we evict to fit. */
    private static final int PREFERENCES_MAX_PERSIST_CHARS = 8000;

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();
    private final LongSupplier clock;
    private final Supplier<String> staticToken;
    private final Supplier<String> loadState;
    private final Consumer<String> saveState;
    /** Positive persistence cap, or {@code 0} for an uncapped file-backed store. */
    private final int maxPersistChars;
    private final Object persistLock = new Object();

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, AuthCode> codes = new ConcurrentHashMap<>();
    private final Map<String, Grant> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, Grant> refreshTokens = new ConcurrentHashMap<>();

    /** Last time the static fallback token authenticated a request (0 = never). */
    private final AtomicLong staticTokenLastSeen = new AtomicLong(0L);

    /**
     * When this store last (re)hydrated. {@code lastSeenAt} is bumped in memory on the hot auth
     * path but only persisted on the next mutation, so after a restart the persisted value can
     * understate real activity by weeks; the cleanup rules therefore never act on evidence older
     * than this process's own view (sweep clocks restart at hydration).
     */
    private volatile long hydratedAt;

    /**
     * @param staticToken supplies the current static fallback bearer token (also accepted on /mcp)
     * @param loadState   returns the previously persisted state JSON (or null/empty on first run)
     * @param saveState   persists the given state JSON; pass a no-op to disable persistence
     */
    public OAuthStore(Supplier<String> staticToken, Supplier<String> loadState,
            Consumer<String> saveState) {
        this(staticToken, loadState, saveState, true, PREFERENCES_MAX_PERSIST_CHARS);
    }

    /**
     * @param loadPersistedNow whether to rehydrate the persisted clients + tokens immediately. Pass
     *     {@code false} when the caller cannot yet tell whether this store should see the shared
     *     persisted state — e.g. before the server's port is bound — and call
     *     {@link #loadPersisted()} once it can.
     */
    public OAuthStore(Supplier<String> staticToken, Supplier<String> loadState,
            Consumer<String> saveState, boolean loadPersistedNow) {
        this(staticToken, loadState, saveState, loadPersistedNow,
                PREFERENCES_MAX_PERSIST_CHARS);
    }

    /**
     * Build a store with an explicit persistence-size policy.
     *
     * @param maxPersistChars positive character cap for a preferences-backed value, or {@code 0}
     *     when the save hook writes a normal file with no 8k {@code java.util.prefs} limit
     */
    public OAuthStore(Supplier<String> staticToken, Supplier<String> loadState,
            Consumer<String> saveState, boolean loadPersistedNow, int maxPersistChars) {
        this(staticToken, loadState, saveState, loadPersistedNow, maxPersistChars,
                System::currentTimeMillis);
    }

    /** Test seam: an injected clock makes the time-based cleanup rules assertable without sleeps. */
    OAuthStore(Supplier<String> staticToken, Supplier<String> loadState,
            Consumer<String> saveState, boolean loadPersistedNow, LongSupplier clock) {
        this(staticToken, loadState, saveState, loadPersistedNow,
                PREFERENCES_MAX_PERSIST_CHARS, clock);
    }

    /** Test seam combining an explicit persistence cap with an injected clock. */
    OAuthStore(Supplier<String> staticToken, Supplier<String> loadState,
            Consumer<String> saveState, boolean loadPersistedNow, int maxPersistChars,
            LongSupplier clock) {
        if (maxPersistChars < 0) {
            throw new IllegalArgumentException("maxPersistChars must be >= 0");
        }
        this.clock = clock;
        this.staticToken = staticToken;
        this.loadState = loadState;
        this.saveState = saveState;
        this.maxPersistChars = maxPersistChars;
        this.hydratedAt = clock.getAsLong();
        if (loadPersistedNow) {
            load();
        }
    }

    /**
     * Rehydrate persisted clients + tokens for a store constructed with {@code loadPersistedNow =
     * false}. Locked against a concurrent {@link #persist()} so a half-loaded state can never be
     * snapshotted back out.
     */
    public void loadPersisted() {
        synchronized (persistLock) {
            hydratedAt = clock.getAsLong();
            load();
        }
    }

    // -------------------------------------------------------------- clients (RFC 7591)

    public Client registerClient(List<String> redirectUris, String clientName) {
        String id = "mcpc_" + randomId();
        Client c = new Client(id, clientName, new LinkedHashSet<>(redirectUris),
                clock.getAsLong());
        // persistLock makes the mutation + persist() snapshot atomic so a concurrent persist() can
        // never serialize a half-applied change.
        synchronized (persistLock) {
            // A registration burst (repeated failed reconnects) is the moment dead weight piles up;
            // sweeping here keeps the store clean even when nothing ever reads the client list.
            sweepInactiveClients();
            clients.put(id, c);
            persist();
        }
        log.info("protege-mcp oauth: registered client {} ({}), redirect_uris={}",
                id, clientName, c.redirectUris);
        return c;
    }

    public Client client(String clientId) {
        if (clientId == null) {
            return null;
        }
        return clients.get(clientId);
    }

    // -------------------------------------------------------------- authorization codes

    public String newAuthCode(String clientId, String redirectUri, String codeChallenge,
            String scope, String resource) {
        purgeExpired();
        String code = "mcpa_" + randomId();
        codes.put(code, new AuthCode(clientId, redirectUri, codeChallenge, scope, resource,
                clock.getAsLong() + CODE_TTL_MS));
        return code;
    }

    /** Single-use: returns and removes the code, or null if unknown/expired (or null input). */
    public AuthCode consumeAuthCode(String code) {
        if (code == null) {
            return null;
        }
        AuthCode a = codes.remove(code);
        if (a == null || a.expiresAt < clock.getAsLong()) {
            return null;
        }
        return a;
    }

    // -------------------------------------------------------------- tokens

    public Tokens issueTokens(String clientId, String scope, String resource) {
        // The access + refresh pair share a grant id so revoking either one drops both (RFC 7009).
        String grantId = randomId();
        String accessToken = "mcpt_" + randomId();
        String refreshToken = "mcpr_" + randomId();
        long expiresAt = clock.getAsLong() + ACCESS_TTL_MS;
        synchronized (persistLock) {
            // Keep at most one active grant pair per client: drop any prior pair (whether from an
            // earlier browser-auth run or a refresh) so tokens can't accumulate without bound, which
            // would otherwise bloat the persisted blob and re-introduce "Unknown client" after a
            // restart once it overflows the preference-value size limit.
            accessTokens.values().removeIf(g -> clientId.equals(g.clientId));
            refreshTokens.values().removeIf(g -> clientId.equals(g.clientId));
            accessTokens.put(accessToken, new Grant(clientId, grantId, scope, resource, expiresAt));
            refreshTokens.put(refreshToken, new Grant(clientId, grantId, scope, resource, Long.MAX_VALUE));
            Client owner = clients.get(clientId);
            if (owner != null) {
                // Issuing (or refreshing) is client activity: without this bump a client whose
                // requests only ever used a fresh token pair would look idle to the cleanup below.
                owner.lastSeenAt.set(clock.getAsLong());
                removeSupersededSiblings(owner);
            }
            persist();
        }
        log.info("protege-mcp oauth: issued tokens to client {} (scope={})", clientId, scope);
        return new Tokens(accessToken, refreshToken, scope, ACCESS_TTL_MS / 1000L);
    }

    /**
     * The reconnect cleanup: an MCP client that lost or discarded its credentials re-registers
     * (RFC 7591 mints a fresh {@code client_id}), leaving its previous registration behind as dead
     * weight the user otherwise removes by hand. Once the NEW registration proves itself by
     * completing authorization (tokens issued), same-name registrations that predate it and have
     * not authenticated since it appeared are dropped with their tokens. A pending (unexpired)
     * authorization code marks an in-flight flow and is never yanked; a same-name client seen
     * <em>after</em> the successor registered is demonstrably alive and kept. Call under
     * {@link #persistLock}; the caller persists.
     *
     * <p>Known trade-off: two <em>concurrently live</em> same-name clients with separate
     * credential stores cannot be told apart from a reconnect — the one idle across the other's
     * authorization loses its tokens and must re-authorize (a browser consent, so a human is in
     * the loop each round). Authorization is same-machine-only, and same-machine clients of one
     * app share a credential store, so this needs two distinct apps claiming one name; accepted
     * in exchange for the reconnect case cleaning up immediately.
     */
    private void removeSupersededSiblings(Client successor) {
        if (successor.clientName == null || successor.clientName.isEmpty()) {
            return; // no name to match on — never supersede anonymous registrations
        }
        purgeExpired(); // an expired code must not shield a dead registration
        List<Client> victims = new ArrayList<>();
        for (Client c : clients.values()) {
            if (c != successor
                    && successor.clientName.equals(c.clientName)
                    && c.registeredAt < successor.registeredAt
                    && c.lastSeenAt.get() < successor.registeredAt
                    && !hasPendingCode(c.clientId)) {
                victims.add(c);
            }
        }
        for (Client victim : victims) {
            dropClientLocked(victim.clientId);
            log.info("protege-mcp oauth: cleaned up client {} ({}) — superseded by re-registered "
                    + "client {}", victim.clientId, victim.clientName, successor.clientId);
        }
    }

    public Tokens refresh(String refreshToken) {
        if (refreshToken == null) {
            return null;
        }
        // issueTokens() drops the client's prior grant pair, so this rotates the refresh token too.
        Grant g = refreshTokens.get(refreshToken);
        if (g == null) {
            return null;
        }
        if (clients.get(g.clientId) == null) {
            // Fail closed like isValidAccessToken: a grant whose client record is gone (cleaned
            // up, revoked, or evicted) must not mint fresh tokens — answering 200 here would trap
            // the client in a refresh-then-401 loop instead of sending it back to re-register.
            return null;
        }
        log.debug("protege-mcp oauth: refreshing tokens for client {}", g.clientId);
        return issueTokens(g.clientId, g.scope, g.resource);
    }

    /** Accept either a live OAuth access token or the plugin's static bearer token. */
    public boolean isValidAccessToken(String token) {
        if (token == null) {
            return false;
        }
        String configured = staticToken.get();
        if (configured != null && PkceUtil.constantTimeEquals(token, configured)) {
            staticTokenLastSeen.set(clock.getAsLong());
            return true;
        }
        Grant g = accessTokens.get(token);
        if (g != null && g.expiresAt > clock.getAsLong()) {
            Client c = clients.get(g.clientId);
            if (c != null) {
                // A token is only honoured while its owning client still exists: revokeClient() and
                // eviction both drop a client together with its tokens, so an orphaned-but-live grant
                // only arises from corrupted persisted state — fail closed there rather than accept a
                // bearer token whose client is gone.
                // Best-effort, in-memory: not persisted on the hot auth path (that would write prefs
                // on every request), so the displayed "last seen" only reaches disk via a later
                // register/issue/revoke and may read stale right after a restart.
                c.lastSeenAt.set(clock.getAsLong());
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------- revocation

    /**
     * Revoke a registered client: remove it along with every access/refresh token and pending
     * authorization code it owns. Returns true if the client existed.
     */
    public boolean revokeClient(String clientId) {
        if (clientId == null) {
            return false;
        }
        Client removed;
        synchronized (persistLock) {
            removed = dropClientLocked(clientId);
            if (removed != null) {
                persist();
            }
        }
        if (removed != null) {
            log.info("protege-mcp oauth: revoked client {} ({}) and all its tokens",
                    clientId, removed.clientName);
        }
        return removed != null;
    }

    /**
     * Revoke a token (RFC 7009). Because the access/refresh pair issued together share a grant id,
     * revoking either one also drops its sibling — so a client that revokes its refresh token does
     * not keep a still-live access token. Returns true if the token was recognised.
     */
    public boolean revokeToken(String token) {
        if (token == null) {
            return false;
        }
        synchronized (persistLock) {
            Grant grant = accessTokens.get(token);
            if (grant == null) {
                grant = refreshTokens.get(token);
            }
            if (grant == null) {
                return false;
            }
            String grantId = grant.grantId;
            accessTokens.values().removeIf(g -> grantId.equals(g.grantId));
            refreshTokens.values().removeIf(g -> grantId.equals(g.grantId));
            log.debug("protege-mcp oauth: revoked token grant for client {}", grant.clientId);
            persist();
        }
        return true;
    }

    /**
     * Drop registrations that are demonstrably dead: token-less, code-less ones past
     * {@link #ABANDONED_CLIENT_GRACE_MS} (abandoned auth flows — and, deliberately, a client that
     * RFC-7009-revoked all its tokens without re-authorizing: dynamic clients re-register on the
     * next connect), and any client silent past {@link #INACTIVE_CLIENT_TTL_MS} (its tokens go
     * with it — a client that idle would have to refresh anyway). Both clocks run from
     * {@link #hydratedAt} at the earliest, so a restart with stale persisted {@code lastSeenAt}
     * never triggers an early reap. Grants whose owning client is gone (only reachable through a
     * cleanup/exchange race) are dropped too — they can never validate but would otherwise sit in
     * the persisted blob forever. Runs opportunistically from {@link #registerClient},
     * {@link #listClients()} and the broker's maintenance loop; persists only on removal.
     *
     * @return how many clients were cleaned up
     */
    public int sweepInactiveClients() {
        synchronized (persistLock) {
            long now = clock.getAsLong();
            purgeExpired();
            List<Client> victims = new ArrayList<>();
            for (Client c : clients.values()) {
                long lastEvidence = Math.max(hydratedAt, Math.max(c.registeredAt, c.lastSeenAt.get()));
                boolean deadWeight = !hasTokens(c.clientId) && !hasPendingCode(c.clientId)
                        && now - lastEvidence > ABANDONED_CLIENT_GRACE_MS;
                if (deadWeight || now - lastEvidence > INACTIVE_CLIENT_TTL_MS) {
                    victims.add(c);
                }
            }
            for (Client victim : victims) {
                dropClientLocked(victim.clientId);
                log.info("protege-mcp oauth: cleaned up inactive client {} ({})",
                        victim.clientId, victim.clientName);
            }
            boolean orphansDropped = accessTokens.values().removeIf(g -> !clients.containsKey(g.clientId));
            orphansDropped |= refreshTokens.values().removeIf(g -> !clients.containsKey(g.clientId));
            if (!victims.isEmpty() || orphansDropped) {
                persist();
            }
            return victims.size();
        }
    }

    /**
     * Count an authorization touch (consent page render, allow/deny decision) as client activity.
     * The consent phase holds no token and no code yet, so without this a human parked on the
     * consent page looks exactly like an abandoned registration to {@link #sweepInactiveClients}.
     */
    public void noteClientActivity(String clientId) {
        Client c = clientId == null ? null : clients.get(clientId);
        if (c != null) {
            c.lastSeenAt.set(clock.getAsLong());
        }
    }

    /** Remove a client with everything it owns (tokens, pending codes). Call under persistLock. */
    private Client dropClientLocked(String clientId) {
        Client removed = clients.remove(clientId);
        accessTokens.values().removeIf(g -> clientId.equals(g.clientId));
        refreshTokens.values().removeIf(g -> clientId.equals(g.clientId));
        codes.values().removeIf(a -> clientId.equals(a.clientId));
        return removed;
    }

    private boolean hasTokens(String clientId) {
        for (Grant g : accessTokens.values()) {
            if (clientId.equals(g.clientId)) {
                return true;
            }
        }
        for (Grant g : refreshTokens.values()) {
            if (clientId.equals(g.clientId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingCode(String clientId) {
        for (AuthCode a : codes.values()) {
            if (clientId.equals(a.clientId)) {
                return true;
            }
        }
        return false;
    }

    /** Snapshot of the currently registered clients for display (newest registration first). */
    public List<ClientInfo> listClients() {
        sweepInactiveClients();
        long now = clock.getAsLong();
        List<ClientInfo> out = new ArrayList<>();
        for (Client c : clients.values()) {
            int activeAccess = 0;
            long latestExpiry = 0L;
            for (Grant g : accessTokens.values()) {
                if (c.clientId.equals(g.clientId) && g.expiresAt > now) {
                    activeAccess++;
                    if (g.expiresAt > latestExpiry) {
                        latestExpiry = g.expiresAt;
                    }
                }
            }
            out.add(new ClientInfo(c.clientId, c.clientName, c.registeredAt, c.lastSeenAt.get(),
                    activeAccess, latestExpiry));
        }
        out.sort((a, b) -> Long.compare(b.registeredAt, a.registeredAt));
        return out;
    }

    /** Last time the static fallback token authenticated a request (0 = never). */
    public long getStaticTokenLastSeen() {
        return staticTokenLastSeen.get();
    }

    // -------------------------------------------------------------- persistence

    /**
     * Serialize clients + tokens to JSON and hand it to the save hook. Codes are not persisted.
     *
     * <p>When {@link #maxPersistChars} is positive, this is the standalone Protégé preference store:
     * {@code java.util.prefs} caps a single value at 8192 chars and <em>throws</em> above that. To
     * guarantee the write always succeeds (a silent failure would freeze the persisted state and
     * re-introduce "Unknown client" on the next restart), expired tokens are purged and, if the blob
     * is still too large, the least-recently-seen clients are evicted until it fits. The broker's
     * normal-file store sets the cap to zero, so it never evicts merely for serialized size.
     */
    private void persist() {
        if (saveState == null) {
            return;
        }
        synchronized (persistLock) {
            try {
                purgeExpired();
                String json = serializeState();
                int evicted = 0;
                while (maxPersistChars > 0 && json.length() > maxPersistChars
                        && evictLeastRecentlySeenClient()) {
                    evicted++;
                    json = serializeState();
                }
                if (evicted > 0) {
                    log.warn("protege-mcp oauth: persisted state exceeded the {}-char preference "
                            + "limit; evicted {} least-recently-seen client(s) — they will re-authorize "
                            + "on next connect", maxPersistChars, evicted);
                }
                saveState.accept(json);
            } catch (Exception e) {
                log.warn("protege-mcp oauth: failed to persist state", e);
            }
        }
    }

    private String serializeState() throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> clientList = new ArrayList<>();
        for (Client c : clients.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientId", c.clientId);
            m.put("clientName", c.clientName);
            m.put("redirectUris", new ArrayList<>(c.redirectUris));
            m.put("registeredAt", c.registeredAt);
            m.put("lastSeenAt", c.lastSeenAt.get());
            clientList.add(m);
        }
        root.put("clients", clientList);
        root.put("accessTokens", grantsToJson(accessTokens));
        root.put("refreshTokens", grantsToJson(refreshTokens));
        return mapper.writeValueAsString(root);
    }

    /**
     * Evict the client whose last activity (authenticated request, or the registration itself for
     * a never-seen client) is oldest, together with its tokens. Counting registration as activity
     * matters at capacity: ordering by raw {@code lastSeenAt} would rank a just-registered client
     * (still 0) below every previously seen one and evict it during its own registration — it
     * would then finish authorization against a record that no longer exists.
     */
    private boolean evictLeastRecentlySeenClient() {
        Client victim = null;
        long victimActivity = Long.MAX_VALUE;
        for (Client c : clients.values()) {
            long activity = Math.max(c.lastSeenAt.get(), c.registeredAt);
            if (victim == null
                    || activity < victimActivity
                    || (activity == victimActivity && c.registeredAt < victim.registeredAt)) {
                victim = c;
                victimActivity = activity;
            }
        }
        if (victim == null) {
            return false;
        }
        dropClientLocked(victim.clientId);
        return true;
    }

    private static List<Map<String, Object>> grantsToJson(Map<String, Grant> tokens) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Grant> e : tokens.entrySet()) {
            Grant g = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", e.getKey());
            m.put("clientId", g.clientId);
            m.put("grantId", g.grantId);
            m.put("scope", g.scope);
            m.put("resource", g.resource);
            m.put("expiresAt", g.expiresAt);
            out.add(m);
        }
        return out;
    }

    /** Rehydrate persisted clients + tokens on construction, dropping anything already expired. */
    private void load() {
        if (loadState == null) {
            return;
        }
        String json;
        try {
            json = loadState.get();
        } catch (Exception e) {
            log.warn("protege-mcp oauth: failed to read persisted state", e);
            return;
        }
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(json);
            for (JsonNode c : root.path("clients")) {
                Set<String> uris = new LinkedHashSet<>();
                for (JsonNode u : c.path("redirectUris")) {
                    uris.add(u.asText());
                }
                Client client = new Client(text(c, "clientId"), text(c, "clientName"), uris,
                        c.path("registeredAt").asLong());
                client.lastSeenAt.set(c.path("lastSeenAt").asLong(0L));
                clients.put(client.clientId, client);
            }
            loadGrants(root.path("accessTokens"), accessTokens);
            loadGrants(root.path("refreshTokens"), refreshTokens);
            purgeExpired();
            log.info("protege-mcp oauth: restored {} client(s), {} access token(s) from preferences",
                    clients.size(), accessTokens.size());
        } catch (Exception e) {
            log.warn("protege-mcp oauth: failed to load persisted state; starting fresh", e);
        }
    }

    private static void loadGrants(JsonNode array, Map<String, Grant> target) {
        for (JsonNode g : array) {
            String token = text(g, "token");
            if (token == null) {
                continue;
            }
            target.put(token, new Grant(text(g, "clientId"), text(g, "grantId"),
                    text(g, "scope"), text(g, "resource"), g.path("expiresAt").asLong()));
        }
    }

    /** Text value of a field, or null if the field is missing or JSON null. */
    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    private void purgeExpired() {
        long now = clock.getAsLong();
        codes.values().removeIf(c -> c.expiresAt < now);
        accessTokens.values().removeIf(g -> g.expiresAt < now);
    }

    private String randomId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // -------------------------------------------------------------- value holders

    public static final class Client {
        public final String clientId;
        public final String clientName;
        public final Set<String> redirectUris;
        public final long registeredAt;
        final AtomicLong lastSeenAt = new AtomicLong(0L);

        Client(String clientId, String clientName, Set<String> redirectUris, long registeredAt) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.redirectUris = redirectUris;
            this.registeredAt = registeredAt;
        }

        public boolean allowsRedirect(String uri) {
            return uri != null && redirectUris.contains(uri);
        }
    }

    public static final class AuthCode {
        public final String clientId;
        public final String redirectUri;
        public final String codeChallenge;
        public final String scope;
        public final String resource;
        final long expiresAt;

        AuthCode(String clientId, String redirectUri, String codeChallenge, String scope,
                String resource, long expiresAt) {
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.scope = scope;
            this.resource = resource;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Grant {
        final String clientId;
        final String grantId;
        final String scope;
        final String resource;
        final long expiresAt;

        Grant(String clientId, String grantId, String scope, String resource, long expiresAt) {
            this.clientId = clientId;
            this.grantId = grantId;
            this.scope = scope;
            this.resource = resource;
            this.expiresAt = expiresAt;
        }
    }

    public static final class Tokens {
        public final String accessToken;
        public final String refreshToken;
        public final String scope;
        public final long expiresInSeconds;

        Tokens(String accessToken, String refreshToken, String scope, long expiresInSeconds) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.scope = scope;
            this.expiresInSeconds = expiresInSeconds;
        }
    }

    /** Immutable per-client snapshot for the UI — decoupled from the mutable {@link Client}. */
    public static final class ClientInfo {
        public final String clientId;
        public final String clientName;
        public final long registeredAt;
        public final long lastSeenAt;          // 0 = never authenticated
        public final int activeAccessTokens;
        public final long latestAccessExpiry;  // 0 = no active access token

        ClientInfo(String clientId, String clientName, long registeredAt, long lastSeenAt,
                int activeAccessTokens, long latestAccessExpiry) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.registeredAt = registeredAt;
            this.lastSeenAt = lastSeenAt;
            this.activeAccessTokens = activeAccessTokens;
            this.latestAccessExpiry = latestAccessExpiry;
        }
    }
}
