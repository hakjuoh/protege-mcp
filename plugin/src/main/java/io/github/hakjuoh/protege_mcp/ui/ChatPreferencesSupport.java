package io.github.hakjuoh.protege_mcp.ui;

import io.github.hakjuoh.protege_mcp.chat.ChatModels;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;

import org.protege.editor.core.prefs.Preferences;

import java.util.List;

/** Shared preference-editor operations that do not belong to a Swing container or child editor. */
final class ChatPreferencesSupport {
    private ChatPreferencesSupport() {}

    static String detect(String executable, String override) {
        String resolved = CliSupport.resolveExecutable(executable, override);
        return resolved == null ? "    not found" : "    found: " + resolved;
    }

    static void clearMissingModelSelection(
            Preferences preferences, String clientId, List<String> catalog) {
        String selected = preferences.getString(ChatModels.modelPrefKey(clientId), "");
        if (!selected.isBlank() && !catalog.contains(selected)) {
            preferences.putString(ChatModels.modelPrefKey(clientId), "");
        }
    }
}
