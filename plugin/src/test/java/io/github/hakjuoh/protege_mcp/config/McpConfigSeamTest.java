package io.github.hakjuoh.protege_mcp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;

/**
 * Exercises the package-private, headless-testable seams of {@link McpConfig}
 * ({@link McpConfig#load(Preferences)} and {@link McpConfig#regenerateToken(Preferences)}) against a
 * hand-rolled {@link Preferences} fake backed by a {@link HashMap}. No Protégé {@code PreferencesManager}
 * singleton is touched, so the read/default/token-mint logic runs under surefire without a live GUI.
 */
class McpConfigSeamTest {

    // ---- FakePreferences: a HashMap-backed Preferences via java.lang.reflect.Proxy -----------------

    /**
     * Invocation handler implementing the {@link Preferences} interface over a {@link HashMap}. It honours
     * the get/put methods that {@link McpConfig} actually calls (getInt/getBoolean/getString +
     * putInt/putBoolean/putString), returning the supplied default when a key is absent, and gives a
     * reasonable zero/false/null default for every other interface method.
     */
    private static final class FakePrefsHandler implements InvocationHandler {
        final Map<String, Object> store = new HashMap<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "getInt": {
                    Object v = store.get((String) args[0]);
                    return (v instanceof Integer) ? v : args[1];
                }
                case "putInt":
                    store.put((String) args[0], args[1]);
                    return null;
                case "getBoolean": {
                    Object v = store.get((String) args[0]);
                    return (v instanceof Boolean) ? v : args[1];
                }
                case "putBoolean":
                    store.put((String) args[0], args[1]);
                    return null;
                case "getString": {
                    Object v = store.get((String) args[0]);
                    return (v instanceof String) ? v : args[1];
                }
                case "putString":
                    store.put((String) args[0], args[1]);
                    return null;
                case "clear":
                    store.clear();
                    return null;
                default:
                    // Reasonable default for any Preferences method McpConfig does not use.
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) {
                        return false;
                    }
                    if (ret == int.class) {
                        return 0;
                    }
                    if (ret == long.class) {
                        return 0L;
                    }
                    if (ret == float.class) {
                        return 0f;
                    }
                    if (ret == double.class) {
                        return 0d;
                    }
                    if (ret == void.class) {
                        return null;
                    }
                    return null;
            }
        }
    }

    private static FakePrefsHandler newHandler() {
        return new FakePrefsHandler();
    }

    private static Preferences proxyFor(FakePrefsHandler h) {
        return (Preferences) Proxy.newProxyInstance(
                Preferences.class.getClassLoader(), new Class<?>[] { Preferences.class }, h);
    }

    // ---- load() defaults --------------------------------------------------------------------------

    @Test
    void loadUsesDefaultsWhenStoreEmpty() {
        FakePrefsHandler h = newHandler();
        McpConfig cfg = McpConfig.load(proxyFor(h));
        assertEquals(McpConfig.DEFAULT_PORT, cfg.getPort());
        assertEquals(8123, cfg.getPort());
        assertTrue(cfg.isAutoStart());
        assertFalse(cfg.isReadOnly());
        assertFalse(cfg.isConfirmWrites());
    }

    @Test
    void loadMintsAndPersistsTokenWhenStoreEmpty() {
        FakePrefsHandler h = newHandler();
        McpConfig cfg = McpConfig.load(proxyFor(h));
        String token = cfg.getToken();
        assertTrue(token != null, "generated token must not be null");
        assertFalse(token.trim().isEmpty(), "generated token must be non-blank");
        // The freshly minted token is persisted back into the store under KEY_TOKEN.
        assertTrue(h.store.containsKey(McpConfig.KEY_TOKEN), "token must be persisted to the store");
        assertEquals(token, h.store.get(McpConfig.KEY_TOKEN));
    }

    // ---- load() reads back non-default values -----------------------------------------------------

    @Test
    void loadReadsBackNonDefaultValues() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_PORT, 9000);
        h.store.put(McpConfig.KEY_AUTOSTART, false);
        h.store.put(McpConfig.KEY_READ_ONLY, true);
        h.store.put(McpConfig.KEY_CONFIRM_WRITES, true);
        h.store.put(McpConfig.KEY_TOKEN, "stored-token-abc");

        McpConfig cfg = McpConfig.load(proxyFor(h));
        assertEquals(9000, cfg.getPort());
        assertFalse(cfg.isAutoStart());
        assertTrue(cfg.isReadOnly());
        assertTrue(cfg.isConfirmWrites());
        assertEquals("stored-token-abc", cfg.getToken());
    }

    @Test
    void loadZeroPortIsHonoured() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_PORT, 0);
        McpConfig cfg = McpConfig.load(proxyFor(h));
        assertEquals(0, cfg.getPort());
    }

    // ---- bind address -----------------------------------------------------------------------------

    @Test
    void loadDefaultsBindAddressToIpv4Loopback() {
        assertEquals("127.0.0.1", McpConfig.DEFAULT_BIND_ADDRESS);
        assertEquals("127.0.0.1", McpConfig.load(proxyFor(newHandler())).getBindAddress());
    }

    @Test
    void loadReadsBackStoredBindAddress() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_BIND_ADDRESS, "0.0.0.0");
        assertEquals("0.0.0.0", McpConfig.load(proxyFor(h)).getBindAddress());
    }

    @Test
    void loadSanitizesStoredBindAddress() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_BIND_ADDRESS, "  [::1] ");
        assertEquals("::1", McpConfig.load(proxyFor(h)).getBindAddress(),
                "brackets and whitespace must not reach the Jetty connector");
    }

    @Test
    void sanitizeBindAddressNormalizes() {
        assertEquals("127.0.0.1", McpConfig.sanitizeBindAddress(null));
        assertEquals("127.0.0.1", McpConfig.sanitizeBindAddress(""));
        assertEquals("127.0.0.1", McpConfig.sanitizeBindAddress("   "));
        assertEquals("127.0.0.1", McpConfig.sanitizeBindAddress("[]"),
                "empty brackets are as blank as blank");
        assertEquals("::1", McpConfig.sanitizeBindAddress("[::1]"));
        assertEquals("::1", McpConfig.sanitizeBindAddress(" ::1 "));
        assertEquals("0.0.0.0", McpConfig.sanitizeBindAddress("0.0.0.0"));
        assertEquals("192.168.0.7", McpConfig.sanitizeBindAddress("192.168.0.7"));
        // Only the *surrounding* URL brackets are stripped; the inside passes through verbatim.
        assertEquals("fe80::1%en0", McpConfig.sanitizeBindAddress("[fe80::1%en0]"));
    }

    // ---- load() token handling --------------------------------------------------------------------

    @Test
    void loadKeepsExistingNonBlankTokenUnchanged() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_TOKEN, "keep-me");
        McpConfig cfg = McpConfig.load(proxyFor(h));
        assertEquals("keep-me", cfg.getToken());
        // Not regenerated: the store still holds the original value.
        assertEquals("keep-me", h.store.get(McpConfig.KEY_TOKEN));
    }

    @Test
    void loadRegeneratesWhenStoredTokenIsBlank() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_TOKEN, "");
        McpConfig cfg = McpConfig.load(proxyFor(h));
        assertFalse(cfg.getToken().isEmpty(), "blank stored token must be replaced");
        assertEquals(cfg.getToken(), h.store.get(McpConfig.KEY_TOKEN), "regenerated token must persist");
    }

    @Test
    void loadRegeneratesWhenStoredTokenIsWhitespace() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_TOKEN, "   \t  ");
        McpConfig cfg = McpConfig.load(proxyFor(h));
        assertFalse(cfg.getToken().trim().isEmpty(), "whitespace token must be replaced");
        assertNotEquals("   \t  ", h.store.get(McpConfig.KEY_TOKEN));
        assertEquals(cfg.getToken(), h.store.get(McpConfig.KEY_TOKEN));
    }

    @Test
    void loadRegeneratesWhenStoredTokenIsNull() {
        FakePrefsHandler h = newHandler();
        // getString will return its default ("") since no String is stored; simulate a null-ish absence.
        // Explicitly ensure absence: nothing put under KEY_TOKEN.
        assertNull(h.store.get(McpConfig.KEY_TOKEN));
        McpConfig cfg = McpConfig.load(proxyFor(h));
        assertFalse(cfg.getToken().isEmpty());
        assertEquals(cfg.getToken(), h.store.get(McpConfig.KEY_TOKEN));
    }

    // ---- regenerateToken() ------------------------------------------------------------------------

    @Test
    void regenerateTokenReturnsFreshNonBlankAndPersists() {
        FakePrefsHandler h = newHandler();
        String token = McpConfig.regenerateToken(proxyFor(h));
        assertFalse(token.trim().isEmpty(), "regenerated token must be non-blank");
        assertEquals(token, h.store.get(McpConfig.KEY_TOKEN), "regenerated token must be persisted");
    }

    @Test
    void regenerateTokenReplacesPreviousStoredValue() {
        FakePrefsHandler h = newHandler();
        h.store.put(McpConfig.KEY_TOKEN, "old-value");
        String token = McpConfig.regenerateToken(proxyFor(h));
        assertNotEquals("old-value", token);
        assertEquals(token, h.store.get(McpConfig.KEY_TOKEN));
    }

    // ---- token randomness + URL-safe encoding -----------------------------------------------------

    @Test
    void twoGeneratedTokensDiffer() {
        FakePrefsHandler h1 = newHandler();
        FakePrefsHandler h2 = newHandler();
        String t1 = McpConfig.regenerateToken(proxyFor(h1));
        String t2 = McpConfig.regenerateToken(proxyFor(h2));
        assertNotEquals(t1, t2, "SecureRandom must yield distinct tokens");
    }

    @Test
    void generatedTokenIsUrlSafeBase64WithoutPadding() {
        FakePrefsHandler h = newHandler();
        String token = McpConfig.regenerateToken(proxyFor(h));
        assertFalse(token.contains("+"), "URL-safe alphabet must not contain '+'");
        assertFalse(token.contains("/"), "URL-safe alphabet must not contain '/'");
        assertFalse(token.contains("="), "encoder is configured withoutPadding");
        // 32 random bytes base64url without padding => ceil(32*4/3) = 43 characters.
        assertEquals(43, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]+"), "must be pure URL-safe base64 alphabet");
    }
}
