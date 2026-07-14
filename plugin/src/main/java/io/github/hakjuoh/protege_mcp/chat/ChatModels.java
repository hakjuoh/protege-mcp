package io.github.hakjuoh.protege_mcp.chat;

import io.github.hakjuoh.protege_mcp.config.McpConfig;

/**
 * Pure model-selection decisions for the chat UI: which preference key stores a provider's last model, and
 * normalizing the model combo-box selection to the id passed to the CLI ({@code ""} = the CLI's own
 * default). Split out of {@code ChatView} so these rules are headless-testable; the view delegates.
 */
public final class ChatModels {

    private ChatModels() {
    }

    /** The preferences key holding the last-picked model for the given provider id. */
    public static String modelPrefKey(String providerId) {
        return "codex".equals(providerId)
                ? McpConfig.KEY_CHAT_MODEL_CODEX
                : McpConfig.KEY_CHAT_MODEL_CLAUDE;
    }

    /**
     * Normalize a model combo-box selection to the id passed to the provider: a null/blank selection or the
     * "use the CLI's default" label ({@code defaultLabel}) becomes {@code ""} (meaning the CLI's own
     * default); otherwise the trimmed selection.
     */
    public static String normalizeModel(String selectedItem, String defaultLabel) {
        String s = selectedItem == null ? "" : selectedItem.trim();
        return (s.isEmpty() || s.equals(defaultLabel)) ? "" : s;
    }
}
