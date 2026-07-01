package io.github.hakjuoh.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.config.McpConfig;
import io.github.hakjuoh.oauth.OAuthStore;

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
}
