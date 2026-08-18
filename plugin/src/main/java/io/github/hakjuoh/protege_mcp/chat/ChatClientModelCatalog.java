package io.github.hakjuoh.protege_mcp.chat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.protege.editor.core.prefs.Preferences;

/**
 * A model catalog bound to one stable client profile.
 *
 * <p>This keeps UI and runtime code from repeatedly passing untyped client-id strings. The existing
 * {@link ChatModelCatalog} remains the storage and metadata engine; this object is the per-client
 * settings boundary that a future user-created profile can own independently.
 */
public final class ChatClientModelCatalog {

    private final ChatClientProfile client;

    public ChatClientModelCatalog(ChatClientProfile client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public ChatClientProfile client() {
        return client;
    }

    public List<String> load(Preferences preferences) {
        return ChatModelCatalog.load(preferences, client);
    }

    public void save(Preferences preferences, List<String> models) {
        ChatModelCatalog.save(preferences, client.id(), models);
    }

    /** Loads the ordered models and each model's saved or client-provided effort metadata. */
    public List<ChatModelDefinition> loadDefinitions(Preferences preferences) {
        List<ChatModelDefinition> definitions = new ArrayList<>();
        List<String> models = load(preferences);
        Path metadataHome = home();
        List<String> modelsWithoutSavedEfforts = models.stream()
                .filter(model -> !hasSavedReasoningEfforts(preferences, model))
                .toList();
        Map<String, List<String>> fallbackEfforts = modelsWithoutSavedEfforts.isEmpty()
                ? Map.of()
                : client.adapter().reasoningEfforts(metadataHome, modelsWithoutSavedEfforts);
        for (String model : models) {
            String stored = preferences.getString(
                    ChatClientPreferences.modelReasoningEffortsPrefKey(client.id(), model), null);
            List<String> efforts = stored == null
                    ? fallbackEfforts.getOrDefault(model, List.of())
                    : ChatReasoningEfforts.parseStored(stored);
            definitions.add(new ChatModelDefinition(model, efforts));
        }
        return List.copyOf(definitions);
    }

    /** Saves definitions while retaining the legacy ordered model key for upgrade compatibility. */
    public void saveDefinitions(
            Preferences preferences, List<ChatModelDefinition> definitions) {
        Map<String, ChatModelDefinition> normalized = new LinkedHashMap<>();
        if (definitions != null) {
            for (ChatModelDefinition definition : definitions) {
                if (definition != null && ChatModelCatalog.isAcceptableModelId(definition.id())
                        && normalized.size() < ChatModelCatalog.maxModels()) {
                    normalized.putIfAbsent(definition.id(), definition);
                }
            }
        }
        ChatModelCatalog.save(preferences, client.id(), List.copyOf(normalized.keySet()));
        normalized.values().forEach(definition -> preferences.putString(
                ChatClientPreferences.modelReasoningEffortsPrefKey(
                        client.id(), definition.id()),
                ChatReasoningEfforts.serialize(definition.reasoningEfforts())));
    }

    /** Runtime picker efforts for the selected model, including the implicit default first. */
    public List<String> reasoningEfforts(Preferences preferences, String model) {
        String selected = model == null ? "" : model.trim();
        if (!selected.isEmpty() && load(preferences).contains(selected)) {
            String stored = preferences.getString(
                    ChatClientPreferences.modelReasoningEffortsPrefKey(
                            client.id(), selected), null);
            List<String> explicit = stored == null
                    ? client.adapter().reasoningEfforts(home(), selected)
                    : ChatReasoningEfforts.parseStored(stored);
            return ChatReasoningEfforts.withDefault(explicit);
        }
        return ChatReasoningEfforts.withDefault(
                client.adapter().reasoningEfforts(home(), selected));
    }

    /** Whether the user or an earlier discovery snapshot has explicitly saved this model's list. */
    public boolean hasSavedReasoningEfforts(Preferences preferences, String model) {
        return preferences.getString(ChatClientPreferences.modelReasoningEffortsPrefKey(
                client.id(), model), null) != null;
    }

    public List<String> pickerModels(Preferences preferences) {
        return ChatModelCatalog.pickerModels(preferences, client);
    }

    public String preferenceKey() {
        return ChatModelCatalog.modelPrefKey(client.id());
    }

    private static Path home() {
        String userHome = System.getProperty("user.home", "");
        return Path.of(userHome.isBlank() ? "." : userHome);
    }
}
