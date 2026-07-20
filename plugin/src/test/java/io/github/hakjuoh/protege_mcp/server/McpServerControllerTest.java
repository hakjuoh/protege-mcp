package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.tools.ToolArgException;
import io.github.hakjuoh.protege_mcp.tools.ToolContext;

/**
 * Method-level tests for {@link McpServerController}.
 *
 * <p>Scope: everything that is reachable <em>without</em> booting Jetty, the MCP SDK, or a live
 * Protégé EditorKit. {@link McpServerController#start()} and {@link McpServerController#restart()}
 * bind a real loopback port and build the real MCP server / OAuth store over a live
 * {@code OWLModelManager}, so they are runtime-only and intentionally out of scope here. Because
 * {@code start()} is never called, the {@code oauthStore} field stays {@code null} throughout, so the
 * OAuth-delegating methods are exercised on their server-stopped (null-store) branch, and
 * {@link McpServerController#stop()} exercises the all-null {@code safeStopInternals} no-op path.
 *
 * <p>The controller reaches {@link McpConfig} which is backed by Protégé's
 * {@code PreferencesManager}; in a headless JVM that resolves to a real java.util.prefs store shared
 * across the process. To keep tests deterministic and side-effect-free, each test snapshots and
 * restores the preference keys it touches. The {@code OntologyAccess} passed to the controller wraps
 * a {@code null} EditorKit — legal because the controller only dereferences {@code access} inside
 * {@code start()}, which we never call.
 */
class McpServerControllerTest {

    /** URL-safe base64 (RFC 4648) alphabet, no padding — the shape of a generated bearer token. */
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]+";

    private Preferences prefs;
    private String savedPort;
    private String savedToken;
    private String savedReadOnly;
    private String savedConfirm;

    @BeforeEach
    void snapshotPrefs() {
        prefs = McpConfig.prefs();
        // Snapshot as strings so we can distinguish "absent" from a real value and restore faithfully.
        savedPort = String.valueOf(prefs.getInt(McpConfig.KEY_PORT, McpConfig.DEFAULT_PORT));
        savedToken = prefs.getString(McpConfig.KEY_TOKEN, "");
        savedReadOnly = String.valueOf(prefs.getBoolean(McpConfig.KEY_READ_ONLY, false));
        savedConfirm = String.valueOf(prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false));
    }

    @AfterEach
    void restorePrefs() {
        prefs.putInt(McpConfig.KEY_PORT, Integer.parseInt(savedPort));
        prefs.putString(McpConfig.KEY_TOKEN, savedToken);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, Boolean.parseBoolean(savedReadOnly));
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, Boolean.parseBoolean(savedConfirm));
    }

    private McpServerController newController() {
        // The controller never touches the EditorKit unless start() runs, so a null-backed access is safe.
        return new McpServerController(new OntologyAccess(null));
    }

    // ---- constructor ----------------------------------------------------------------------------

    @Test
    void constructorSucceedsWithFakeOntologyAccess() {
        assertDoesNotThrow(this::newController,
                "constructor must not require a live Protégé runtime when start() is not called");
    }

    @Test
    void constructorReadsInitialTokenFromConfig() {
        String seeded = "seeded-token-abc123";
        prefs.putString(McpConfig.KEY_TOKEN, seeded);
        McpServerController c = newController();
        assertEquals(seeded, c.getToken(),
                "constructor must initialise the token from McpConfig.load()");
    }

    @Test
    void constructorGeneratesTokenWhenNoneStored() {
        prefs.putString(McpConfig.KEY_TOKEN, "");
        McpServerController c = newController();
        String t = c.getToken();
        assertNotNull(t, "a blank stored token must be replaced by a freshly generated one");
        assertTrue(t.matches(TOKEN_PATTERN), "generated token must be URL-safe base64: " + t);
        assertEquals(43, t.length(), "a 256-bit token base64url-encodes to 43 chars (no padding)");
    }

    @Test
    void constructorLeavesServerStopped() {
        McpServerController c = newController();
        assertFalse(c.isRunning(), "a freshly constructed controller must not be running");
        assertEquals(0, c.getBoundPort(), "bound port must be 0 before start()");
        assertNull(c.getLastError(), "lastError must be null before any start attempt");
    }

    // ---- isRunning ------------------------------------------------------------------------------

    @Test
    void isRunningFalseBeforeStart() {
        assertFalse(newController().isRunning(), "must be false before start()");
    }

    @Test
    void isRunningFalseAfterStopOnNeverStartedController() {
        McpServerController c = newController();
        c.stop();
        assertFalse(c.isRunning(), "stop() on a never-started controller keeps running=false");
    }

    // ---- stop (never-started, all-null internals) -----------------------------------------------

    @Test
    void stopOnNeverStartedControllerIsNoop() {
        McpServerController c = newController();
        assertDoesNotThrow(c::stop,
                "stop() must not NPE when serverManager/httpServer/oauthStore are all null");
        assertFalse(c.isRunning());
        assertEquals(0, c.getBoundPort());
    }

    @Test
    void stopIsIdempotent() {
        McpServerController c = newController();
        c.stop();
        assertDoesNotThrow(c::stop, "stop() after stop() must remain a no-op");
        assertFalse(c.isRunning());
        assertEquals(0, c.getBoundPort(), "bound port stays 0 across repeated stop() calls");
    }

    // ---- isUserStopped / setUserStopped ----------------------------------------------------------

    @Test
    void isUserStoppedFalseInitially() {
        assertFalse(newController().isUserStopped(),
                "a fresh controller carries no user-stop latch — auto-start must be allowed");
    }

    @Test
    void setUserStoppedLatchesAndClears() {
        McpServerController c = newController();
        c.setUserStopped(true);
        assertTrue(c.isUserStopped(), "the view's Stop must latch");
        c.setUserStopped(false);
        assertFalse(c.isUserStopped(), "the view's Start must clear the latch");
    }

    @Test
    void programmaticStopDoesNotLatchUserStopped() {
        // Only the view's Stop button represents the user; hook dispose and the broker's attach
        // rollback also call stop() and must NOT block later auto-starts.
        McpServerController c = newController();
        c.stop();
        assertFalse(c.isUserStopped(), "stop() itself is not a user decision — no latch");
    }

    @Test
    void stopLeavesAnExistingLatchInPlace() {
        // Stop-latch-then-stop is the view's actual call order; the latch must survive the stop.
        McpServerController c = newController();
        c.setUserStopped(true);
        c.stop();
        assertTrue(c.isUserStopped(), "stop() must not clear a latch the user just set");
    }

    @Test
    void startRefusesWhileUserStopped() {
        // The only start() call in this file: the latch refusal precedes ALL runtime machinery
        // (TCCL pinning, Jena init, Jetty), so this is headless-safe and boots nothing. It pins the
        // latch's last line of defense — an auto-start path that raced past its own up-front check
        // (e.g. a broker attach failing over to a standalone start) must be refused here, under the
        // same monitor stop() takes, instead of silently overriding the user's Stop.
        McpServerController c = newController();
        c.setUserStopped(true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, c::start,
                "start() must refuse while the user-stop latch is set");
        assertTrue(thrown.getMessage().contains("press Start"), thrown.getMessage());
        assertFalse(c.isRunning(), "a refused start must leave the server stopped");
        assertTrue(c.isUserStopped(), "the latch must survive the refusal");
        assertNull(c.getLastError(), "a latch refusal is not a failure — lastError must stay clear");
    }

    @Test
    void startBrokerManagedRefusesWhileUserStopped() {
        // Same guard on the broker-backend path (BrokerLink.attach), same headless safety.
        McpServerController c = newController();
        c.setUserStopped(true);

        assertThrows(IllegalStateException.class, c::startBrokerManaged,
                "startBrokerManaged() must refuse while the user-stop latch is set");
        assertFalse(c.isRunning());
        assertTrue(c.isUserStopped());
    }

    // ---- getBoundPort ---------------------------------------------------------------------------

    @Test
    void getBoundPortIsZeroInitially() {
        assertEquals(0, newController().getBoundPort(), "no port is bound before start()");
    }

    @Test
    void getBoundPortIsZeroAfterStop() {
        McpServerController c = newController();
        c.stop();
        assertEquals(0, c.getBoundPort(), "stop() resets the bound port to 0");
    }

    // ---- getConfiguredPort ----------------------------------------------------------------------

    @Test
    void getConfiguredPortIsZeroBeforeStart() {
        // configuredPort is only populated inside start(); default int field value is 0 beforehand.
        assertEquals(0, newController().getConfiguredPort(),
                "configuredPort is unset (0) until start() reads it from McpConfig");
    }

    // ---- getEndpointUrl -------------------------------------------------------------------------

    @Test
    void getEndpointUrlBeforeStartUsesZeroPort() {
        assertEquals("http://127.0.0.1:0/mcp", newController().getEndpointUrl(),
                "endpoint URL is built from the loopback host, boundPort (0), and /mcp path");
    }

    // ---- getToken -------------------------------------------------------------------------------

    @Test
    void getTokenReturnsConstructorToken() {
        String seeded = "constructor-token-xyz";
        prefs.putString(McpConfig.KEY_TOKEN, seeded);
        assertEquals(seeded, newController().getToken(),
                "getToken() reflects the token captured in the constructor");
    }

    @Test
    void getTokenReflectsRegeneration() {
        McpServerController c = newController();
        String before = c.getToken();
        String after = c.regenerateToken();
        assertEquals(after, c.getToken(),
                "getToken() must return the freshly regenerated token immediately (no restart)");
        assertNotEquals(before, c.getToken(), "the token must actually change after regeneration");
    }

    // ---- regenerateToken ------------------------------------------------------------------------

    @Test
    void regenerateTokenReturnsWellFormedToken() {
        String t = newController().regenerateToken();
        assertNotNull(t, "regenerateToken() must return a non-null token");
        assertTrue(t.matches(TOKEN_PATTERN), "regenerated token must be URL-safe base64: " + t);
        assertEquals(43, t.length(), "a 256-bit token base64url-encodes to 43 chars (no padding)");
    }

    @Test
    void regenerateTokenUpdatesTokenField() {
        McpServerController c = newController();
        String before = c.getToken();
        String returned = c.regenerateToken();
        assertEquals(returned, c.getToken(),
                "the value returned by regenerateToken() must be stored in the token field");
        assertNotEquals(before, returned, "regeneration must yield a token different from the old one");
    }

    @Test
    void regenerateTokenPersistsToPreferences() {
        McpServerController c = newController();
        String regenerated = c.regenerateToken();
        assertEquals(regenerated, prefs.getString(McpConfig.KEY_TOKEN, ""),
                "regenerateToken() must persist the new token to the preferences store");
    }

    @Test
    void regenerateTokenYieldsDistinctTokensAcrossCalls() {
        McpServerController c = newController();
        String first = c.regenerateToken();
        String second = c.regenerateToken();
        assertNotEquals(first, second,
                "two consecutive regenerations must produce different tokens (SecureRandom)");
    }

    // ---- listClients (server stopped → null store) ----------------------------------------------

    @Test
    void listClientsIsEmptyWhenServerStopped() {
        List<OAuthStore.ClientInfo> clients = newController().listClients();
        assertNotNull(clients, "listClients() must never return null");
        assertTrue(clients.isEmpty(), "with no running server (null oauthStore) the list is empty");
    }

    @Test
    void listClientsEmptyAfterStop() {
        McpServerController c = newController();
        c.stop();
        assertTrue(c.listClients().isEmpty(),
                "stop() nulls the store, so listClients() stays empty");
    }

    // ---- revokeClient (server stopped → null store) ---------------------------------------------

    @Test
    void revokeClientReturnsFalseWhenServerStopped() {
        assertFalse(newController().revokeClient("some-client"),
                "revokeClient() must return false when there is no store to revoke from");
    }

    @Test
    void revokeClientWithNullIdReturnsFalseWhenServerStopped() {
        assertDoesNotThrow(() -> {
            boolean revoked = newController().revokeClient(null);
            assertFalse(revoked,
                    "a null clientId with no store returns false via the early null-store guard");
        });
    }

    @Test
    void revokeClientAfterStopReturnsFalse() {
        McpServerController c = newController();
        c.stop();
        assertFalse(c.revokeClient("client-id"),
                "after stop() the store is null so revocation reports false");
    }

    // ---- getStaticTokenLastSeen (server stopped → null store) -----------------------------------

    @Test
    void getStaticTokenLastSeenIsZeroWhenServerStopped() {
        assertEquals(0L, newController().getStaticTokenLastSeen(),
                "with no running server the static-token last-seen timestamp is 0");
    }

    @Test
    void getStaticTokenLastSeenIsZeroAfterStop() {
        McpServerController c = newController();
        c.stop();
        assertEquals(0L, c.getStaticTokenLastSeen(),
                "stop() nulls the store, so last-seen reports 0");
    }

    @Test
    void assistantCredentialUsesBoundedWriteOrReadProfileAndRevokes() throws Exception {
        McpServerController controller = newController();
        OAuthStore store = new OAuthStore(() -> null, () -> null, ignored -> { });
        setRuntimeStore(controller, store);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);

        McpServerController.AssistantCredential writable = controller.issueAssistantCredential(
                "codex", "turn-1", true);
        Set<String> capabilities = writable.principal().capabilities();
        assertTrue(capabilities.containsAll(Set.of("ontology:read", "ontology:curate",
                "ontology:admin", "ontology:release", "filesystem:project:read",
                "filesystem:project:write")));
        assertFalse(capabilities.contains("server:admin"));
        assertFalse(capabilities.contains("filesystem:external"));
        assertFalse(capabilities.contains("network:access"));
        assertFalse(capabilities.contains("local:admin"));
        assertEquals("assistant:codex", store.authenticate(writable.token()).principalType());
        assertTrue(controller.renewAssistantCredential(writable.token()));
        assertTrue(controller.revokeAssistantCredential(writable.token()));
        assertNull(store.authenticate(writable.token()));
        assertFalse(controller.renewAssistantCredential(writable.token()));

        prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);
        McpServerController.AssistantCredential readOnly = controller.issueAssistantCredential(
                "claude", "session-123", true);
        assertEquals(Set.of("ontology:read"), readOnly.principal().capabilities());
        controller.revokeAssistantCredential(readOnly.token());

        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        McpServerController.AssistantCredential preferenceReadOnly =
                controller.issueAssistantCredential("claude", "turn-2", false);
        assertEquals(Set.of("ontology:read"), preferenceReadOnly.principal().capabilities());
        controller.revokeAssistantCredential(preferenceReadOnly.token());
    }

    @Test
    void assistantCredentialRequiresRunningStoreAndSafeIdentity() throws Exception {
        McpServerController stopped = newController();
        assertThrows(IllegalStateException.class,
                () -> stopped.issueAssistantCredential("codex", "turn-1", true));

        OAuthStore store = new OAuthStore(() -> null, () -> null, ignored -> { });
        setRuntimeStore(stopped, store);
        assertThrows(IllegalArgumentException.class,
                () -> stopped.issueAssistantCredential("Codex/../../", "turn-1", true));
        assertThrows(IllegalArgumentException.class,
                () -> stopped.issueAssistantCredential("codex", "raw session with spaces", true));
    }

    @Test
    void standaloneClientRevocationWaitsForExecutionWithoutBlockingServerStop() throws Exception {
        McpServerController controller = newController();
        OAuthStore store = new OAuthStore(() -> null, () -> null, ignored -> { });
        ToolContext context = new ToolContext(null, controller);
        setRuntimeStore(controller, store);
        setRuntimeContext(controller, context);
        OAuthStore.Client client = store.registerClient(List.of("http://127.0.0.1/client"), "client");
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.oauthAdmin(
                client.clientId, "client", "grant-client");
        var active = context.executions().acquire(principal);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var revoked = pool.submit(() -> controller.revokeClient(client.clientId));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> revoked.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            var stopped = pool.submit(controller::stop);
            stopped.get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(controller.isRunning(),
                    "the controller lifecycle monitor must not be held during the fence wait");
            active.close();
            assertTrue(revoked.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(ToolArgException.class, () -> context.executions().acquire(principal));
        } finally {
            active.close();
            pool.shutdownNow();
        }
    }

    @Test
    void assistantRevocationFencesOnlyThatTurnsGrant() throws Exception {
        McpServerController controller = newController();
        OAuthStore store = new OAuthStore(() -> null, () -> null, ignored -> { });
        ToolContext context = new ToolContext(null, controller);
        setRuntimeStore(controller, store);
        setRuntimeContext(controller, context);
        var first = controller.issueAssistantCredential("codex", "same-chat", true);
        var active = context.executions().acquire(first.principal());
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var revoked = pool.submit(() -> controller.revokeAssistantCredential(first));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> revoked.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            active.close();
            assertTrue(revoked.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(ToolArgException.class,
                    () -> context.executions().acquire(first.principal()));

            var nextTurn = controller.issueAssistantCredential("codex", "same-chat", true);
            try (var ignored = context.executions().acquire(nextTurn.principal())) {
                assertNotEquals(first.principal().grantId(), nextTurn.principal().grantId());
            }
        } finally {
            active.close();
            pool.shutdownNow();
        }
    }

    // ---- getLastError ---------------------------------------------------------------------------

    @Test
    void getLastErrorIsNullInitially() {
        assertNull(newController().getLastError(),
                "no error has occurred on a freshly constructed controller");
    }

    @Test
    void getLastErrorIsNullAfterStopOnNeverStartedController() {
        McpServerController c = newController();
        c.stop();
        assertNull(c.getLastError(),
                "stop() does not fabricate an error on a never-started controller");
    }

    // ---- isReadOnly / isConfirmWrites (live preference reads) ------------------------------------

    @Test
    void isReadOnlyDefaultsToFalse() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        assertFalse(newController().isReadOnly(),
                "isReadOnly() reflects the stored preference (false)");
    }

    @Test
    void isReadOnlyReflectsPreferenceLive() {
        McpServerController c = newController();
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);
        assertTrue(c.isReadOnly(),
                "isReadOnly() must read the preference live, reflecting a post-construction change");
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        assertFalse(c.isReadOnly(),
                "toggling the preference back must take effect without a restart");
    }

    @Test
    void isConfirmWritesDefaultsToFalse() {
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        assertFalse(newController().isConfirmWrites(),
                "isConfirmWrites() reflects the stored preference (false)");
    }

    @Test
    void isConfirmWritesReflectsPreferenceLive() {
        McpServerController c = newController();
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, true);
        assertTrue(c.isConfirmWrites(),
                "isConfirmWrites() must read the preference live");
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        assertFalse(c.isConfirmWrites(),
                "toggling confirm-writes back must take effect without a restart");
    }

    // ---- ManagedServer contract -----------------------------------------------------------------

    @Test
    void controllerIsAManagedServer() {
        ManagedServer server = newController();
        assertSame(Boolean.FALSE, Boolean.valueOf(server.isRunning()),
                "as a ManagedServer, a fresh controller reports not-running");
    }

    @Test
    void regeneratedTokenAppliesToEveryControllerImmediately() {
        // getToken() reads the preferences live: with two servers possibly running at once (a
        // configured-port owner plus a fallback-port server in another window), a token regenerated
        // in ANY window must stop the old token everywhere, not just on the regenerating controller.
        McpServerController a = newController();
        McpServerController b = newController();
        String old = b.getToken();
        String fresh = a.regenerateToken();
        assertEquals(fresh, b.getToken(),
                "every controller must serve the regenerated token immediately (live prefs read)");
        assertNotEquals(old, b.getToken(), "the old token must no longer be reported anywhere");
    }

    // ---- isPortFallback / persistOAuthState -------------------------------------------------------

    /** start() is runtime-only here (see class javadoc), so the port fields are set reflectively. */
    private static void setPorts(McpServerController c, int configured, int bound) throws Exception {
        Field cf = McpServerController.class.getDeclaredField("configuredPort");
        cf.setAccessible(true);
        cf.setInt(c, configured);
        Field bf = McpServerController.class.getDeclaredField("boundPort");
        bf.setAccessible(true);
        bf.setInt(c, bound);
    }

    private static void setRuntimeStore(McpServerController controller, OAuthStore store)
            throws Exception {
        Field storeField = McpServerController.class.getDeclaredField("oauthStore");
        storeField.setAccessible(true);
        storeField.set(controller, store);
        Field runningField = McpServerController.class.getDeclaredField("running");
        runningField.setAccessible(true);
        runningField.setBoolean(controller, true);
    }

    private static void setRuntimeContext(McpServerController controller, ToolContext context)
            throws Exception {
        Field contextField = McpServerController.class.getDeclaredField("toolContext");
        contextField.setAccessible(true);
        contextField.set(controller, context);
    }

    @Test
    void isPortFallbackFalseOnFreshController() {
        assertFalse(newController().isPortFallback(),
                "no ports are bound before start(), so nothing can be a fallback");
    }

    @Test
    void isPortFallbackTruthTable() throws Exception {
        McpServerController c = newController();

        setPorts(c, 8123, 8123);
        assertFalse(c.isPortFallback(), "bound == configured is the normal bind, not a fallback");

        setPorts(c, 8123, 54321);
        assertTrue(c.isPortFallback(), "bound != configured (configured != 0) is the fallback shape");

        setPorts(c, 0, 54321);
        assertFalse(c.isPortFallback(), "configured 0 is ephemeral by choice, never a fallback");

        setPorts(c, 8123, 0);
        assertFalse(c.isPortFallback(), "bound 0 means stopped / failed start, not a fallback");
    }

    /** The persist gate is latched by start() after hydration; latch it directly here (no Jetty). */
    private static void setPersistAllowed(McpServerController c, boolean allowed) throws Exception {
        Field f = McpServerController.class.getDeclaredField("oauthPersistAllowed");
        f.setAccessible(true);
        f.setBoolean(c, allowed);
    }

    @Test
    void persistOAuthStatePersistsOnceHydrationLatchedTheGate() throws Exception {
        String savedOauth = prefs.getString(McpConfig.KEY_OAUTH_STATE, "");
        try {
            McpServerController c = newController();
            setPersistAllowed(c, true); // as start() does on the configured-port owner post-hydration
            c.persistOAuthState("{\"clients\":\"owner\"}");
            assertEquals("{\"clients\":\"owner\"}", prefs.getString(McpConfig.KEY_OAUTH_STATE, ""),
                    "the hydrated configured-port owner persists the user-global OAuth blob");
        } finally {
            prefs.putString(McpConfig.KEY_OAUTH_STATE, savedOauth);
        }
    }

    @Test
    void persistOAuthStateSkipsWriteWhileTheGateIsUnlatched() throws Exception {
        // The unlatched gate covers every state that must not write the shared blob: a fallback-port
        // server (never hydrated), an owner-side mutation racing the post-bind hydration, and an
        // in-flight request that outlives stop().
        String savedOauth = prefs.getString(McpConfig.KEY_OAUTH_STATE, "");
        try {
            prefs.putString(McpConfig.KEY_OAUTH_STATE, "{\"clients\":\"owner\"}");
            McpServerController c = newController();
            c.persistOAuthState("{\"clients\":\"fallback\"}");
            assertEquals("{\"clients\":\"owner\"}", prefs.getString(McpConfig.KEY_OAUTH_STATE, ""),
                    "an unhydrated server must not clobber the owner's persisted OAuth clients");
        } finally {
            prefs.putString(McpConfig.KEY_OAUTH_STATE, savedOauth);
        }
    }

    @Test
    void stopClosesThePersistGate() throws Exception {
        String savedOauth = prefs.getString(McpConfig.KEY_OAUTH_STATE, "");
        try {
            prefs.putString(McpConfig.KEY_OAUTH_STATE, "{\"clients\":\"owner\"}");
            McpServerController c = newController();
            setPersistAllowed(c, true);
            c.stop();
            c.persistOAuthState("{\"clients\":\"late-request\"}");
            assertEquals("{\"clients\":\"owner\"}", prefs.getString(McpConfig.KEY_OAUTH_STATE, ""),
                    "a request outliving stop() must no longer write the shared blob");
        } finally {
            prefs.putString(McpConfig.KEY_OAUTH_STATE, savedOauth);
        }
    }
}
