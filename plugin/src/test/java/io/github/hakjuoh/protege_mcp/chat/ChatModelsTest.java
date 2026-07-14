package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void modelPrefKeyForAnyOtherProviderDefaultsToTheClaudeKey() {
        // Any non-codex id (incl. null / unknown) maps to the Claude key — the two keys must never swap.
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE, ChatModels.modelPrefKey("something-else"));
        assertEquals(McpConfig.KEY_CHAT_MODEL_CLAUDE, ChatModels.modelPrefKey(null));
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
