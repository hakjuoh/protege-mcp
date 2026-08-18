package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.hakjuoh.protege_mcp.config.McpConfig;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link ChatModels}, the pure model-selection rules extracted from {@code ChatView}. */
class ChatModelsTest {

    @Test
    void modelPrefKeyForCodexIsTheCodexKey() {
        assertEquals(McpConfig.KEY_CHAT_MODEL_CODEX, ChatModels.modelPrefKey("codex"));
    }

    @Test
    void modelPrefKeyForClaudeIsTheClaudeKey() {
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE, ChatModels.modelPrefKey("claude"));
    }

    @Test
    void modelPrefKeyForAClientAddedLaterIsIndependent() {
        assertEquals(ChatClientPreferences.selectedModelPrefKey("something-else"),
                ChatModels.modelPrefKey("something-else"));
        assertNotEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE,
                ChatModels.modelPrefKey("something-else"));
        // Null retains the old defensive fallback for callers that have no selected client.
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE, ChatModels.modelPrefKey(null));
    }

    @Test
    void reasoningEffortPreferenceKeysStayProviderSpecific() {
        assertEquals(McpConfig.KEY_CHAT_REASONING_EFFORT_CLAUDE,
                ChatModels.reasoningEffortPrefKey("claude"));
        assertEquals(McpConfig.KEY_CHAT_REASONING_EFFORT_CODEX,
                ChatModels.reasoningEffortPrefKey("codex"));
    }

    @Test
    void normalizeModelTreatsNullAsCliDefault() {
        assertEquals("", ChatModels.normalizeModel(null, "Default (CLI decides)"));
    }

    @Test
    void normalizeModelTreatsBlankAsCliDefault() {
        assertEquals("", ChatModels.normalizeModel("   ", "Default (CLI decides)"));
    }

    @Test
    void normalizeModelTreatsTheDefaultLabelAsCliDefault() {
        String label = "Default (CLI decides)";
        assertEquals("", ChatModels.normalizeModel(label, label));
        assertEquals("", ChatModels.normalizeModel("  " + label + "  ", label),
                "the label is recognized after trimming");
    }

    @Test
    void normalizeModelReturnsTheTrimmedModelIdOtherwise() {
        assertEquals("opus-4", ChatModels.normalizeModel("  opus-4  ", "Default (CLI decides)"));
        assertEquals("gpt-5", ChatModels.normalizeModel("gpt-5", "Default (CLI decides)"));
    }
}
