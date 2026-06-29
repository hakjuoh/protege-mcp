package io.github.hakjuoh.oauth;

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
 */
public final class OAuthStore {

    private static final Logger log = LoggerFactory.getLogger(OAuthStore.class);

    private static final long CODE_TTL_MS = 120_000L;            // 2 minutes
    private static final long ACCESS_TTL_MS = 30L * 24 * 3600 * 1000; // 30 days (localhost, single user)
    /** Margin under java.util.prefs' 8192-char per-value limit; above this we evict to fit. */
    private static final int MAX_PERSIST_CHARS = 8000;

    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<String> staticToken;
    private final Supplier<String> loadState;
    private final Consumer<String> saveState;
    private final Object persistLock = new Object();

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, AuthCode> codes = new ConcurrentHashMap<>();
    private final Map<String, Grant> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, Grant> refreshTokens = new ConcurrentHashMap<>();

    /** Last time the static fallback token authenticated a request (0 = never). */
    private final AtomicLong staticTokenLastSeen = new AtomicLong(0L);

    /**
     * @param staticToken supplies the current static fallback bearer token (also accepted on /mcp)
     * @param loadState   returns the previously persisted state JSON (or null/empty on first run)
     * @param saveState   persists the given state JSON; pass a no-op to disable persistence
     */
    public OAuthStore(Supplier<String> staticToken, Supplier<String> loadState,
            Consumer<String> saveState) {
        this.staticToken = staticToken;
        this.loadState = loadState;
        this.saveState = saveState;
        load();
    }

    // -------------------------------------------------------------- clients (RFC 7591)

    public Client registerClient(List<String> redirectUris, String clientName) {
        String id = "mcpc_" + randomId();
        Client c = new Client(id, clientName, new LinkedHashSet<>(redirectUris),
                System.currentTimeMillis());
        // persistLock makes the mutation + persist() snapshot atomic so a concurrent persist() can
        // never serialize a half-applied change.
        synchronized (persistLock) {
            clients.put(id, c);
            persist();
        }
        log.info("protege-mcp oauth: registered client {} ({}), redirect_uris={}",
                id, clientName, c.redirectUris);
        return c;
    }

    public Client client(String clientId) {
        return clients.get(clientId);
    }

    // -------------------------------------------------------------- authorization codes

    public String newAuthCode(String clientId, String redirectUri, String codeChallenge,
            String scope, String resource) {
        purgeExpired();
        String code = "mcpa_" + randomId();
        codes.put(code, new AuthCode(clientId, redirectUri, codeChallenge, scope, resource,
                System.currentTimeMillis() + CODE_TTL_MS));
        return code;
    }

    /** Single-use: returns and removes the code, or null if unknown/expired. */
    public AuthCode consumeAuthCode(String code) {
        AuthCode a = codes.remove(code);
        if (a == null || a.expiresAt < System.currentTimeMillis()) {
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
        long expiresAt = System.currentTimeMillis() + ACCESS_TTL_MS;
        synchronized (persistLock) {
            // Keep at most one active grant pair per client: drop any prior pair (whether from an
            // earlier browser-auth run or a refresh) so tokens can't accumulate without bound, which
            // would otherwise bloat the persisted blob and re-introduce "Unknown client" after a
            // restart once it overflows the preference-value size limit.
            accessTokens.values().removeIf(g -> clientId.equals(g.clientId));
            refreshTokens.values().removeIf(g -> clientId.equals(g.clientId));
            accessTokens.put(accessToken, new Grant(clientId, grantId, scope, resource, expiresAt));
            refreshTokens.put(refreshToken, new Grant(clientId, grantId, scope, resource, Long.MAX_VALUE));
            persist();
        }
        log.info("protege-mcp oauth: issued tokens to client {} (scope={})", clientId, scope);
        return new Tokens(accessToken, refreshToken, scope, ACCESS_TTL_MS / 1000L);
    }

    public Tokens refresh(String refreshToken) {
        // issueTokens() drops the client's prior grant pair, so this rotates the refresh token too.
        Grant g = refreshTokens.get(refreshToken);
        if (g == null) {
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
            staticTokenLastSeen.set(System.currentTimeMillis());
            return true;
        }
        Grant g = accessTokens.get(token);
        if (g != null && g.expiresAt > System.currentTimeMillis()) {
            Client c = clients.get(g.clientId);
            if (c != null) {
                // Best-effort, in-memory: not persisted on the hot auth path (that would write prefs
                // on every request), so the displayed "last seen" only reaches disk via a later
                // register/issue/revoke and may read stale right after a restart.
                c.lastSeenAt.set(System.currentTimeMillis());
            }
            return true;
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
            removed = clients.remove(clientId);
            accessTokens.values().removeIf(g -> clientId.equals(g.clientId));
            refreshTokens.values().removeIf(g -> clientId.equals(g.clientId));
            codes.values().removeIf(a -> clientId.equals(a.clientId));
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

    /** Snapshot of the currently registered clients for display (newest registration first). */
    public List<ClientInfo> listClients() {
        long now = System.currentTimeMillis();
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
     * <p>The Protégé preference store is backed by {@code java.util.prefs}, which caps a single
     * value at 8192 chars and <em>throws</em> above that. To guarantee the write always succeeds (a
     * silent failure would freeze the persisted state and re-introduce "Unknown client" on the next
     * restart), expired tokens are purged and, if the blob is still too large, the least-recently-seen
     * clients are evicted until it fits.
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
                while (json.length() > MAX_PERSIST_CHARS && evictLeastRecentlySeenClient()) {
                    evicted++;
                    json = serializeState();
                }
                if (evicted > 0) {
                    log.warn("protege-mcp oauth: persisted state exceeded the {}-char preference "
                            + "limit; evicted {} least-recently-seen client(s) — they will re-authorize "
                            + "on next connect", MAX_PERSIST_CHARS, evicted);
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

    /** Evict the client that authenticated longest ago (then oldest registration) and its tokens. */
    private boolean evictLeastRecentlySeenClient() {
        Client victim = null;
        for (Client c : clients.values()) {
            if (victim == null
                    || c.lastSeenAt.get() < victim.lastSeenAt.get()
                    || (c.lastSeenAt.get() == victim.lastSeenAt.get()
                            && c.registeredAt < victim.registeredAt)) {
                victim = c;
            }
        }
        if (victim == null) {
            return false;
        }
        String id = victim.clientId;
        clients.remove(id);
        accessTokens.values().removeIf(g -> id.equals(g.clientId));
        refreshTokens.values().removeIf(g -> id.equals(g.clientId));
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
        long now = System.currentTimeMillis();
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
