package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.protege.editor.core.prefs.Preferences;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import io.github.hakjuoh.protege_mcp.testing.TestPreferences;

class ChatClientPreferencesTest {

    @Test
    void executablePathsPreserveLegacyKeysAndIsolateCustomProfiles() {
        assertEquals(McpConfig.KEY_CHAT_CLAUDE_PATH,
                ChatClientPreferences.executablePathPrefKey("claude"));
        assertEquals(McpConfig.KEY_CHAT_CODEX_PATH,
                ChatClientPreferences.executablePathPrefKey("codex"));
        assertNotEquals(ChatClientPreferences.executablePathPrefKey("local-a"),
                ChatClientPreferences.executablePathPrefKey("local-b"));
        assertNotEquals(ChatClientPreferences.executablePathPrefKey("antigravity"),
                ChatClientPreferences.executablePathPrefKey("opencode"));
        assertNotEquals(ChatClientPreferences.modelCatalogPrefKey("antigravity"),
                ChatClientPreferences.modelCatalogPrefKey("opencode"));
    }

    @Test
    void predefinedProfilesKeepEveryLegacyPreferenceKey() {
        assertEquals(McpConfig.KEY_CHAT_MODELS_CLAUDE,
                ChatClientPreferences.modelCatalogPrefKey("claude"));
        assertEquals(McpConfig.KEY_CHAT_MODELS_CODEX,
                ChatClientPreferences.modelCatalogPrefKey("codex"));
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE,
                ChatClientPreferences.selectedModelPrefKey("claude"));
        assertEquals(McpConfig.KEY_CHAT_MODEL_CODEX,
                ChatClientPreferences.selectedModelPrefKey("codex"));
        assertEquals(McpConfig.KEY_CHAT_REASONING_EFFORT_CLAUDE,
                ChatClientPreferences.reasoningEffortPrefKey("claude"));
        assertEquals(McpConfig.KEY_CHAT_REASONING_EFFORT_CODEX,
                ChatClientPreferences.reasoningEffortPrefKey("codex"));
    }

    @Test
    void futureProfilesReceiveDistinctKeysInsteadOfFallingBackToClaude() {
        String first = ChatClientPreferences.modelCatalogPrefKey("local-ollama");
        String second = ChatClientPreferences.modelCatalogPrefKey("team-ollama");

        assertNotEquals(first, second);
        assertNotEquals(McpConfig.KEY_CHAT_MODELS_CLAUDE, first);
        assertNotEquals(ChatClientPreferences.selectedModelPrefKey("local-ollama"), first);
        assertNotEquals(ChatClientPreferences.reasoningEffortPrefKey("local-ollama"), first);
    }

    @Test
    void blankFutureProfileIdIsRejectedBeforeItCanShareAKey() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatClientPreferences.modelCatalogPrefKey("  "));
    }

    @Test
    void displayNameOverrideIsIndependentAndBlankRestoresThePredefinedName() {
        Preferences preferences = TestPreferences.cleared();
        ChatClientProfile client = ChatClients.byId("claude");

        assertEquals("Claude Code", ChatClientPreferences.displayName(preferences, client));
        ChatClientPreferences.saveDisplayName(preferences, client, "Research Assistant");
        assertEquals("Research Assistant", ChatClientPreferences.displayName(preferences, client));

        ChatClientPreferences.saveDisplayName(preferences, client, "  ");
        assertEquals("Claude Code", ChatClientPreferences.displayName(preferences, client));
        assertEquals("", preferences.getString(
                ChatClientPreferences.displayNamePrefKey(client.id()), "missing"));
    }

    @Test
    void displayNameIsSingleLineAndBoundedByCodePoints() {
        String longName = "😀".repeat(ChatClientPreferences.MAX_DISPLAY_NAME_CODE_POINTS + 5);
        String normalized = ChatClientPreferences.normalizeDisplayName(
                "  Team\n" + longName + "\u2028  ", "Fallback");

        assertEquals(ChatClientPreferences.MAX_DISPLAY_NAME_CODE_POINTS,
                normalized.codePointCount(0, normalized.length()));
        assertEquals(-1, normalized.indexOf('\n'));
        assertEquals(-1, normalized.indexOf('\u2028'));
    }
}
