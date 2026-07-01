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
    public static final String KEY_AUTOSTART = "autoStart";
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

    private final int port;
    private final boolean autoStart;
    private final boolean readOnly;
    private final boolean confirmWrites;
    private final String token;

    private McpConfig(int port, boolean autoStart, boolean readOnly, boolean confirmWrites, String token) {
        this.port = port;
        this.autoStart = autoStart;
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
        boolean autoStart = p.getBoolean(KEY_AUTOSTART, true);
        boolean readOnly = p.getBoolean(KEY_READ_ONLY, false);
        boolean confirmWrites = p.getBoolean(KEY_CONFIRM_WRITES, false);
        String token = p.getString(KEY_TOKEN, "");
        if (token == null || token.trim().isEmpty()) {
            token = generateToken();
            p.putString(KEY_TOKEN, token);
        }
        return new McpConfig(port, autoStart, readOnly, confirmWrites, token);
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

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public int getPort() {
        return port;
    }

    public boolean isAutoStart() {
        return autoStart;
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
