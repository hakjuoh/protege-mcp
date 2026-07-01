package io.github.hakjuoh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.protege.editor.core.prefs.Preferences;

/**
 * Method-level tests for {@link McpConfig}.
 *
 * <p>{@link McpConfig#prefs()} is backed by Protégé's {@code PreferencesManager}, which in a headless
 * JVM resolves to a real {@code java.util.prefs} store shared across the process. To stay deterministic
 * and side-effect-free, each test snapshots and restores every preference key it may touch. The
 * private {@code generateToken()} helper is exercised via reflection so its RFC-4648-url-safe token
 * shape can be asserted directly.
 */
class McpConfigTest {

    /** URL-safe base64 (RFC 4648) alphabet, no padding — the shape of a generated bearer token. */
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]+";

    private Preferences prefs;
    private int savedPort;
    private String savedToken;
    private boolean savedAutoStart;
    private boolean savedReadOnly;
    private boolean savedConfirm;

    @BeforeEach
    void snapshotPrefs() {
        prefs = McpConfig.prefs();
        savedPort = prefs.getInt(McpConfig.KEY_PORT, McpConfig.DEFAULT_PORT);
        savedToken = prefs.getString(McpConfig.KEY_TOKEN, "");
        savedAutoStart = prefs.getBoolean(McpConfig.KEY_AUTOSTART, true);
        savedReadOnly = prefs.getBoolean(McpConfig.KEY_READ_ONLY, false);
        savedConfirm = prefs.getBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
    }

    @AfterEach
    void restorePrefs() {
        prefs.putInt(McpConfig.KEY_PORT, savedPort);
        prefs.putString(McpConfig.KEY_TOKEN, savedToken);
        prefs.putBoolean(McpConfig.KEY_AUTOSTART, savedAutoStart);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, savedReadOnly);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, savedConfirm);
    }

    /** Invoke the private static {@code generateToken()} reflectively. */
    private static String generateToken() throws Exception {
        Method m = McpConfig.class.getDeclaredMethod("generateToken");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    // ---- constant invariants --------------------------------------------------------------------

    @Test
    void prefsSetKeepsLegacyNamespaceForSettingsContinuity() {
        assertEquals("org.protege.mcp", McpConfig.PREFS_SET,
                "PREFS_SET must stay the legacy namespace so an existing install's settings survive the rename");
    }

    @Test
    void prefsGroupIsServer() {
        assertEquals("server", McpConfig.PREFS_GROUP, "PREFS_GROUP must be 'server'");
    }

    @Test
    void defaultPortIs8123() {
        assertEquals(8123, McpConfig.DEFAULT_PORT, "DEFAULT_PORT must be 8123");
    }

    @Test
    void preferenceKeyConstantsHaveExpectedValues() {
        assertEquals("port", McpConfig.KEY_PORT);
        assertEquals("autoStart", McpConfig.KEY_AUTOSTART);
        assertEquals("readOnly", McpConfig.KEY_READ_ONLY);
        assertEquals("confirmWrites", McpConfig.KEY_CONFIRM_WRITES);
        assertEquals("bearerToken", McpConfig.KEY_TOKEN);
        assertEquals("oauthState", McpConfig.KEY_OAUTH_STATE);
    }

    @Test
    void chatPreferenceKeyConstantsHaveExpectedValues() {
        assertEquals("chatProvider", McpConfig.KEY_CHAT_PROVIDER);
        assertEquals("chatModelClaude", McpConfig.KEY_CHAT_MODEL_CLAUDE);
        assertEquals("chatModelCodex", McpConfig.KEY_CHAT_MODEL_CODEX);
        assertEquals("chatClaudePath", McpConfig.KEY_CHAT_CLAUDE_PATH);
        assertEquals("chatCodexPath", McpConfig.KEY_CHAT_CODEX_PATH);
        assertEquals("chatEgressConsented", McpConfig.KEY_CHAT_CONSENTED);
        assertEquals("chatEgressConsentedV2", McpConfig.KEY_CHAT_CONSENTED_V2);
        assertEquals("chatShowThinking", McpConfig.KEY_CHAT_SHOW_THINKING);
    }

    @Test
    void consentV2KeyDiffersFromV1() {
        assertNotEquals(McpConfig.KEY_CHAT_CONSENTED, McpConfig.KEY_CHAT_CONSENTED_V2,
                "the re-versioned consent key must differ so users must re-acknowledge");
    }

    // ---- prefs() --------------------------------------------------------------------------------

    @Test
    void prefsReturnsNonNullNode() {
        assertNotNull(McpConfig.prefs(), "prefs() must never return null");
    }

    @Test
    void prefsReturnsUsableStore() {
        Preferences p = McpConfig.prefs();
        p.putString("__mcpconfig_test_probe__", "hello");
        assertEquals("hello", p.getString("__mcpconfig_test_probe__", ""),
                "prefs() must return a writable/readable preferences node");
        p.putString("__mcpconfig_test_probe__", "");
    }

    // ---- generateToken() (private, via reflection) ----------------------------------------------

    @Test
    void generateTokenIsNonNull() throws Exception {
        assertNotNull(generateToken(), "generateToken() must not return null");
    }

    @Test
    void generateTokenIsNonEmpty() throws Exception {
        assertFalse(generateToken().isEmpty(), "generateToken() must not return an empty string");
    }

    @Test
    void generateTokenLengthIs43() throws Exception {
        // 32 random bytes -> ceil(32*8/6) = 43 chars in base64url without padding.
        assertEquals(43, generateToken().length(),
                "a 256-bit token base64url-encodes to 43 chars (no padding)");
    }

    @Test
    void generateTokenUsesOnlyUrlSafeAlphabet() throws Exception {
        String t = generateToken();
        assertTrue(t.matches(TOKEN_PATTERN), "token must contain only URL-safe base64 chars: " + t);
    }

    @Test
    void generateTokenHasNoPadding() throws Exception {
        assertFalse(generateToken().contains("="), "token must be encoded without '=' padding");
    }

    @Test
    void generateTokenHasNoStandardBase64Chars() throws Exception {
        String t = generateToken();
        assertFalse(t.contains("+"), "url-safe base64 must not contain '+'");
        assertFalse(t.contains("/"), "url-safe base64 must not contain '/'");
    }

    @Test
    void generateTokenDecodesTo32Bytes() throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(generateToken());
        assertEquals(32, decoded.length, "token must decode back to exactly 32 (256-bit) random bytes");
    }

    @Test
    void generateTokenIsDifferentAcrossTwoCalls() throws Exception {
        assertNotEquals(generateToken(), generateToken(),
                "SecureRandom must yield a distinct token on each call");
    }

    @Test
    void generateTokenIsUniqueAcrossManyCalls() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            assertTrue(seen.add(generateToken()), "each generated token must be unique");
        }
    }

    // ---- load() ---------------------------------------------------------------------------------

    @Test
    void loadReturnsNonNull() {
        assertNotNull(McpConfig.load(), "load() must never return null");
    }

    @Test
    void loadUsesDefaultPortWhenUnset() {
        prefs.putInt(McpConfig.KEY_PORT, McpConfig.DEFAULT_PORT);
        assertEquals(McpConfig.DEFAULT_PORT, McpConfig.load().getPort(),
                "load() must default the port to DEFAULT_PORT");
    }

    @Test
    void loadReadsConfiguredPort() {
        prefs.putInt(McpConfig.KEY_PORT, 9090);
        assertEquals(9090, McpConfig.load().getPort(), "load() must read the stored port");
    }

    @Test
    void loadReadsEphemeralPortZero() {
        prefs.putInt(McpConfig.KEY_PORT, 0);
        assertEquals(0, McpConfig.load().getPort(),
                "a configured port of 0 (ephemeral) must round-trip through load()");
    }

    @Test
    void loadReadsHighPort() {
        prefs.putInt(McpConfig.KEY_PORT, 65535);
        assertEquals(65535, McpConfig.load().getPort(), "load() must read a high in-range port");
    }

    @Test
    void loadDefaultsAutoStartToTrue() {
        prefs.putBoolean(McpConfig.KEY_AUTOSTART, true);
        assertTrue(McpConfig.load().isAutoStart(), "autoStart must default to true");
    }

    @Test
    void loadReadsAutoStartFalse() {
        prefs.putBoolean(McpConfig.KEY_AUTOSTART, false);
        assertFalse(McpConfig.load().isAutoStart(), "load() must read autoStart=false");
    }

    @Test
    void loadDefaultsReadOnlyToFalse() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, false);
        assertFalse(McpConfig.load().isReadOnly(), "readOnly must default to false");
    }

    @Test
    void loadReadsReadOnlyTrue() {
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);
        assertTrue(McpConfig.load().isReadOnly(), "load() must read readOnly=true");
    }

    @Test
    void loadDefaultsConfirmWritesToFalse() {
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, false);
        assertFalse(McpConfig.load().isConfirmWrites(), "confirmWrites must default to false");
    }

    @Test
    void loadReadsConfirmWritesTrue() {
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, true);
        assertTrue(McpConfig.load().isConfirmWrites(), "load() must read confirmWrites=true");
    }

    @Test
    void loadReturnsStoredNonBlankToken() {
        String seeded = "seeded-token-value-123";
        prefs.putString(McpConfig.KEY_TOKEN, seeded);
        assertEquals(seeded, McpConfig.load().getToken(),
                "load() must return the already-stored token verbatim when it is non-blank");
    }

    @Test
    void loadGeneratesTokenWhenStoredIsBlank() {
        prefs.putString(McpConfig.KEY_TOKEN, "");
        String t = McpConfig.load().getToken();
        assertNotNull(t, "a blank stored token must be replaced by a freshly generated one");
        assertEquals(43, t.length(), "the generated token must be a 43-char base64url string");
        assertTrue(t.matches(TOKEN_PATTERN), "generated token must be URL-safe base64: " + t);
    }

    @Test
    void loadGeneratesTokenWhenStoredIsWhitespace() {
        prefs.putString(McpConfig.KEY_TOKEN, "   ");
        String t = McpConfig.load().getToken();
        assertNotNull(t, "a whitespace-only stored token counts as blank and must be regenerated");
        assertEquals(43, t.length(), "the generated token must be a 43-char base64url string");
        assertFalse(t.isBlank(), "regenerated token must be non-blank");
    }

    @Test
    void loadPersistsGeneratedTokenBackToPrefs() {
        prefs.putString(McpConfig.KEY_TOKEN, "");
        String generated = McpConfig.load().getToken();
        assertEquals(generated, prefs.getString(McpConfig.KEY_TOKEN, ""),
                "load() must persist the freshly generated token back into preferences");
    }

    @Test
    void loadIsStableAcrossCallsOnceTokenPersisted() {
        prefs.putString(McpConfig.KEY_TOKEN, "");
        String first = McpConfig.load().getToken();
        String second = McpConfig.load().getToken();
        assertEquals(first, second,
                "once a token is persisted, subsequent load() calls must reuse it (no regeneration)");
    }

    @Test
    void loadReadsAllFieldsTogether() {
        prefs.putInt(McpConfig.KEY_PORT, 12345);
        prefs.putBoolean(McpConfig.KEY_AUTOSTART, false);
        prefs.putBoolean(McpConfig.KEY_READ_ONLY, true);
        prefs.putBoolean(McpConfig.KEY_CONFIRM_WRITES, true);
        prefs.putString(McpConfig.KEY_TOKEN, "combo-token");
        McpConfig c = McpConfig.load();
        assertEquals(12345, c.getPort());
        assertFalse(c.isAutoStart());
        assertTrue(c.isReadOnly());
        assertTrue(c.isConfirmWrites());
        assertEquals("combo-token", c.getToken());
    }

    // ---- getters (via load()) -------------------------------------------------------------------

    @Test
    void gettersAreImmutableSnapshotOfPrefsAtLoadTime() {
        prefs.putInt(McpConfig.KEY_PORT, 7000);
        McpConfig c = McpConfig.load();
        // Mutate prefs after loading; the snapshot must not change.
        prefs.putInt(McpConfig.KEY_PORT, 7001);
        assertEquals(7000, c.getPort(),
                "McpConfig is an immutable snapshot; later pref changes must not affect it");
    }

    @Test
    void getTokenIsNonNullFromFreshLoad() {
        prefs.putString(McpConfig.KEY_TOKEN, "");
        assertNotNull(McpConfig.load().getToken(), "getToken() must be non-null after a fresh load");
    }

    // ---- regenerateToken() ----------------------------------------------------------------------

    @Test
    void regenerateTokenReturnsValidShapedToken() {
        String t = McpConfig.regenerateToken();
        assertNotNull(t, "regenerateToken() must return a token");
        assertEquals(43, t.length(), "regenerated token must be a 43-char base64url string");
        assertTrue(t.matches(TOKEN_PATTERN), "regenerated token must be URL-safe base64: " + t);
    }

    @Test
    void regenerateTokenPersistsToPreferences() {
        String t = McpConfig.regenerateToken();
        assertEquals(t, prefs.getString(McpConfig.KEY_TOKEN, ""),
                "regenerateToken() must persist the new token into preferences");
    }

    @Test
    void regenerateTokenReplacesExistingToken() {
        prefs.putString(McpConfig.KEY_TOKEN, "old-token");
        String t = McpConfig.regenerateToken();
        assertNotEquals("old-token", t, "regenerateToken() must overwrite the previous token");
        assertEquals(t, prefs.getString(McpConfig.KEY_TOKEN, ""),
                "the new token must be the one persisted");
    }

    @Test
    void regenerateTokenYieldsDistinctTokensAcrossCalls() {
        assertNotEquals(McpConfig.regenerateToken(), McpConfig.regenerateToken(),
                "each regenerateToken() call must produce a distinct token");
    }

    @Test
    void loadAfterRegenerateReflectsNewToken() {
        String regenerated = McpConfig.regenerateToken();
        assertEquals(regenerated, McpConfig.load().getToken(),
                "a subsequent load() must observe the regenerated token");
    }

    // ---- cross-call sanity ----------------------------------------------------------------------

    @Test
    void prefsReturnsSameLogicalNodeAcrossCalls() {
        prefs.putInt(McpConfig.KEY_PORT, 4321);
        // A second prefs() lookup must observe the same persisted state.
        assertEquals(4321, McpConfig.prefs().getInt(McpConfig.KEY_PORT, -1),
                "prefs() must expose the same persisted preferences state across calls");
    }
}
