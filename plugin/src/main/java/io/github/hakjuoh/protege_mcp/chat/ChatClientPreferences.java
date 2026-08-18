package io.github.hakjuoh.protege_mcp.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.config.McpConfig;

/** Preference keys and normalization shared by every Assistant client profile. */
public final class ChatClientPreferences {

    /** A display label is navigation text, not an unbounded free-form document. */
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 80;

    private ChatClientPreferences() {
    }

    /** Reads the user-visible name, falling back to the predefined name when no override is stored. */
    public static String displayName(Preferences preferences, ChatClientProfile client) {
        String stored = preferences.getString(displayNamePrefKey(client.id()), "");
        return normalizeDisplayName(stored, client.defaultDisplayName());
    }

    /** Stores only a real override; an empty value means the predefined name remains authoritative. */
    public static void saveDisplayName(Preferences preferences, ChatClientProfile client, String value) {
        String normalized = normalizeDisplayName(value, client.defaultDisplayName());
        preferences.putString(displayNamePrefKey(client.id()),
                normalized.equals(client.defaultDisplayName()) ? "" : normalized);
    }

    /**
     * Produces one line of bounded tab/combo-box text. Pasted control characters are discarded and a
     * blank result resets to the predefined name.
     */
    public static String normalizeDisplayName(String value, String defaultName) {
        String source = value == null ? "" : value.trim();
        StringBuilder normalized = new StringBuilder();
        source.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        && codePoint != 0x2028
                        && codePoint != 0x2029)
                .limit(MAX_DISPLAY_NAME_CODE_POINTS)
                .forEach(normalized::appendCodePoint);
        String result = normalized.toString().trim();
        return result.isEmpty() ? defaultName : result;
    }

    /** Preference key holding the optional user-visible name override. */
    public static String displayNamePrefKey(String clientId) {
        if ("claude".equals(clientId)) {
            return McpConfig.KEY_CHAT_CLIENT_NAME_CLAUDE;
        }
        if ("codex".equals(clientId)) {
            return McpConfig.KEY_CHAT_CLIENT_NAME_CODEX;
        }
        return scopedKey(clientId, "name");
    }

    /** Preference key holding the ordered model catalog for one client profile. */
    public static String modelCatalogPrefKey(String clientId) {
        if ("claude".equals(clientId) || clientId == null) {
            return McpConfig.KEY_CHAT_MODELS_CLAUDE;
        }
        if ("codex".equals(clientId)) {
            return McpConfig.KEY_CHAT_MODELS_CODEX;
        }
        return scopedKey(clientId, "models");
    }

    /** Preference key holding the last selected model for one client profile. */
    public static String selectedModelPrefKey(String clientId) {
        if ("claude".equals(clientId) || clientId == null) {
            return McpConfig.KEY_CHAT_MODEL_CLAUDE;
        }
        if ("codex".equals(clientId)) {
            return McpConfig.KEY_CHAT_MODEL_CODEX;
        }
        return scopedKey(clientId, "model");
    }

    /** Preference key holding the last selected reasoning effort for one client profile. */
    public static String reasoningEffortPrefKey(String clientId) {
        if ("claude".equals(clientId) || clientId == null) {
            return McpConfig.KEY_CHAT_REASONING_EFFORT_CLAUDE;
        }
        if ("codex".equals(clientId)) {
            return McpConfig.KEY_CHAT_REASONING_EFFORT_CODEX;
        }
        return scopedKey(clientId, "reasoningEffort");
    }

    /** Preference key holding an optional executable override for one client profile. */
    public static String executablePathPrefKey(String clientId) {
        if ("claude".equals(clientId)) {
            return McpConfig.KEY_CHAT_CLAUDE_PATH;
        }
        if ("codex".equals(clientId)) {
            return McpConfig.KEY_CHAT_CODEX_PATH;
        }
        return scopedKey(clientId, "executablePath");
    }

    /** Records that first-run CLI model discovery has succeeded for this profile. */
    public static String modelDiscoveryCompletedPrefKey(String clientId) {
        return scopedKey(clientId, "modelDiscoveryCompleted");
    }

    /** Bounded key holding one model's explicit reasoning-effort list. */
    public static String modelReasoningEffortsPrefKey(String clientId, String modelId) {
        String identity = (clientId == null ? "" : clientId.trim()) + "\0"
                + (modelId == null ? "" : modelId.trim());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "chatModelEfforts."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /**
     * Encodes the stable id so a future UUID or external id cannot create ambiguous preference paths.
     * The two legacy clients keep their historical keys through the explicit branches above.
     */
    private static String scopedKey(String clientId, String setting) {
        String id = clientId == null ? "" : clientId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8));
        return "chatClient." + encoded + "." + setting;
    }
}
