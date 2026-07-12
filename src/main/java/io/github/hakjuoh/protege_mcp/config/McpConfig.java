package io.github.hakjuoh.protege_mcp.config;

import java.security.SecureRandom;
import java.util.Base64;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;

/**
 * Immutable snapshot of the plugin's settings, read from (and persisted to) the Protégé
 * {@link Preferences} store. A fresh snapshot is taken each time the server starts.
 *
 * <p>Persistence lives under the preferences set {@value #PREFS_SET}, group {@value #PREFS_GROUP}.
 */
public final class McpConfig {

    // Preferences node holding this plugin's persisted settings (token / OAuth clients / port).
    public static final String PREFS_SET = "io.github.hakjuoh.protege_mcp";
    public static final String PREFS_GROUP = "server";

    public static final String KEY_PORT = "port";
    /**
     * Address the client-facing server (standalone window server, and the shared broker) binds.
     * Loopback by default; anything else exposes the plain-HTTP endpoint to the network — the
     * Preferences panel warns about that. Broker-managed window backends always stay on loopback
     * regardless of this setting (they are internal, reached only through the broker's proxy).
     */
    public static final String KEY_BIND_ADDRESS = "bindAddress";
    public static final String KEY_AUTOSTART = "autoStart";
    /** Share one broker process (and the configured port) across all Protégé windows/instances. */
    public static final String KEY_SHARED_BROKER = "sharedBroker";
    /**
     * How long (seconds) the shared broker keeps running after the last Protégé instance
     * disconnects, so a quick restart reuses the live broker instead of respawning one. {@code 0}
     * means the broker exits as soon as the reference count reaches zero. Propagated to a running
     * broker with every register/heartbeat, so a change applies without a broker restart.
     */
    public static final String KEY_BROKER_LINGER_SECONDS = "brokerLingerSeconds";
    public static final String KEY_READ_ONLY = "readOnly";
    public static final String KEY_CONFIRM_WRITES = "confirmWrites";
    public static final String KEY_TOKEN = "bearerToken";
    /** Persisted embedded-OAuth state (registered clients + access/refresh tokens) as a JSON blob. */
    public static final String KEY_OAUTH_STATE = "oauthState";

    // ---- In-Protégé chat (Architecture Approach B) settings ----
    /** Selected chat provider id ({@code claude} / {@code codex}). */
    public static final String KEY_CHAT_PROVIDER = "chatProvider";
    /** Last model picked for the Claude provider (blank = the CLI's own default). */
    public static final String KEY_CHAT_MODEL_CLAUDE = "chatModelClaude";
    /** Last model picked for the Codex provider (blank = the CLI's own default). */
    public static final String KEY_CHAT_MODEL_CODEX = "chatModelCodex";
    /** Optional absolute path / dir override when the {@code claude} CLI is not on the GUI's PATH. */
    public static final String KEY_CHAT_CLAUDE_PATH = "chatClaudePath";
    /** Optional absolute path / dir override when the {@code codex} CLI is not on the GUI's PATH. */
    public static final String KEY_CHAT_CODEX_PATH = "chatCodexPath";
    /** Whether the user has acknowledged the one-time chat egress disclosure. */
    public static final String KEY_CHAT_CONSENTED = "chatEgressConsented";
    /**
     * Re-versioned egress consent. The disclosure's scope materially changed (it now covers attachments and
     * pasted content sent to the provider), so a new key forces existing users to acknowledge once more rather
     * than silently inheriting consent for something they were never told about.
     */
    public static final String KEY_CHAT_CONSENTED_V2 = "chatEgressConsentedV2";
    /** Whether the chat should show the model's reasoning ("thinking") in the transcript. */
    public static final String KEY_CHAT_SHOW_THINKING = "chatShowThinking";

    /** Default listen port. A configured port of {@code 0} means "pick an ephemeral port". */
    public static final int DEFAULT_PORT = 8123;

    /** Default bind address: IPv4 loopback, reachable from this machine only. */
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

    /** Default broker idle linger: long enough to bridge a normal Protégé restart. */
    public static final int DEFAULT_BROKER_LINGER_SECONDS = 15;

    /** Upper bound for the linger (one hour) — a corrupt value must not pin the broker forever. */
    public static final int MAX_BROKER_LINGER_SECONDS = 3600;

    private final int port;
    private final String bindAddress;
    private final boolean autoStart;
    private final boolean sharedBroker;
    private final int brokerLingerSeconds;
    private final boolean readOnly;
    private final boolean confirmWrites;
    private final String token;

    private McpConfig(int port, String bindAddress, boolean autoStart, boolean sharedBroker,
            int brokerLingerSeconds, boolean readOnly, boolean confirmWrites, String token) {
        this.port = port;
        this.bindAddress = bindAddress;
        this.autoStart = autoStart;
        this.sharedBroker = sharedBroker;
        this.brokerLingerSeconds = brokerLingerSeconds;
        this.readOnly = readOnly;
        this.confirmWrites = confirmWrites;
        this.token = token;
    }

    /** The per-plugin preferences node (never null; created on first access). */
    public static Preferences prefs() {
        return PreferencesManager.getInstance().getPreferencesForSet(PREFS_SET, PREFS_GROUP);
    }

    /**
     * Read the current settings. Generates and persists a bearer token on first run (or whenever
     * the stored token is blank).
     */
    public static McpConfig load() {
        return load(prefs());
    }

    /**
     * Testable core of {@link #load()}: reads a snapshot from the given preferences node, minting and
     * persisting a bearer token when the stored one is blank. Split out so the read/default/token-mint
     * logic can be exercised headless against a fake {@link Preferences} without the Protégé singleton.
     */
    static McpConfig load(Preferences p) {
        int port = p.getInt(KEY_PORT, DEFAULT_PORT);
        String bindAddress = sanitizeBindAddress(p.getString(KEY_BIND_ADDRESS, DEFAULT_BIND_ADDRESS));
        boolean autoStart = p.getBoolean(KEY_AUTOSTART, true);
        boolean sharedBroker = p.getBoolean(KEY_SHARED_BROKER, true);
        int brokerLingerSeconds = clampBrokerLingerSeconds(
                p.getInt(KEY_BROKER_LINGER_SECONDS, DEFAULT_BROKER_LINGER_SECONDS));
        boolean readOnly = p.getBoolean(KEY_READ_ONLY, false);
        boolean confirmWrites = p.getBoolean(KEY_CONFIRM_WRITES, false);
        String token = p.getString(KEY_TOKEN, "");
        if (token == null || token.trim().isEmpty()) {
            token = generateToken();
            p.putString(KEY_TOKEN, token);
        }
        return new McpConfig(port, bindAddress, autoStart, sharedBroker, brokerLingerSeconds,
                readOnly, confirmWrites, token);
    }

    /** Clamp a stored/typed linger into {@code 0..}{@value #MAX_BROKER_LINGER_SECONDS} seconds. */
    public static int clampBrokerLingerSeconds(int raw) {
        return Math.max(0, Math.min(raw, MAX_BROKER_LINGER_SECONDS));
    }

    /**
     * Normalize a stored/typed bind address: trim, strip the URL-style brackets off an IPv6 literal
     * ({@code [::1]} → {@code ::1} — Jetty wants the bare form), and fall back to the loopback
     * default when blank. No resolution or reachability check happens here; an address the OS
     * refuses to bind surfaces as the server's start error.
     */
    public static String sanitizeBindAddress(String raw) {
        String address = raw == null ? "" : raw.trim();
        if (address.length() >= 2 && address.charAt(0) == '[' && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1).trim();
        }
        return address.isEmpty() ? DEFAULT_BIND_ADDRESS : address;
    }

    /** Generate a fresh URL-safe 256-bit bearer token and persist it. */
    public static String regenerateToken() {
        return regenerateToken(prefs());
    }

    /** Testable core of {@link #regenerateToken()} writing to the given preferences node. */
    static String regenerateToken(Preferences p) {
        String token = generateToken();
        p.putString(KEY_TOKEN, token);
        return token;
    }

    /**
     * Mints a fresh URL-safe 256-bit random secret (32 {@link SecureRandom} bytes, base64url,
     * no padding). Does NOT persist it — callers decide whether/where it is stored.
     */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public int getPort() {
        return port;
    }

    /** See {@link #KEY_BIND_ADDRESS}; already sanitized, never blank. */
    public String getBindAddress() {
        return bindAddress;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    /** Whether MCP access is shared through the cross-process broker (default) or per-window. */
    public boolean isSharedBroker() {
        return sharedBroker;
    }

    /** See {@link #KEY_BROKER_LINGER_SECONDS}; already clamped to {@code 0..3600}. */
    public int getBrokerLingerSeconds() {
        return brokerLingerSeconds;
    }

    /** The linger in the milliseconds the broker protocol speaks. */
    public long getBrokerLingerMs() {
        return brokerLingerSeconds * 1000L;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isConfirmWrites() {
        return confirmWrites;
    }

    public String getToken() {
        return token;
    }
}
