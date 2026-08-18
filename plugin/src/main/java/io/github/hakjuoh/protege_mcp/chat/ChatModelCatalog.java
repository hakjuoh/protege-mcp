package io.github.hakjuoh.protege_mcp.chat;

import org.protege.editor.core.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.SwingUtilities;

/**
 * Owns the model ids shown by the Ontology Assistant. When a user has not saved a catalog yet, the
 * catalog is bootstrapped from the model already selected plus the locally installed CLI's own
 * metadata. Metadata that cannot be used contributes nothing, leaving just that selected model —
 * and the picker shows the CLI-default entry alone only when nothing was selected either. Once
 * saved, including as an empty list, the user's catalog is the source of truth. The empty list is
 * intentional: it means the CLI must choose its configured default.
 */
public final class ChatModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ChatModelCatalog.class);

    /**
     * Views that want to know saved client-facing catalog settings changed. The notification also
     * covers a client display-name edit because the same open provider selector must repaint on OK.
     * Copy-on-write because the Preferences panel fires from the event thread while a view may be
     * disposing on that same thread.
     */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private ChatModelCatalog() {}

    /**
     * Returns the configured model ids, with the local CLI metadata used only before first save.
     */
    public static List<String> load(Preferences preferences, String providerId) {
        return load(preferences, providerId, home());
    }

    /** Returns a profile-bound catalog, including metadata supplied by its runtime adapter. */
    static List<String> load(Preferences preferences, ChatClientProfile client) {
        return load(preferences, client, home());
    }

    /**
     * Loads a catalog from the supplied CLI metadata root; package-private for deterministic tests.
     */
    static List<String> load(Preferences preferences, String providerId, Path metadataHome) {
        String stored = preferences.getString(modelPrefKey(providerId), null);
        return stored == null
                ? bootstrapModels(
                        preferences, ChatClients.byId(providerId), providerId, metadataHome)
                : parseStoredModels(stored);
    }

    static List<String> load(Preferences preferences, ChatClientProfile client, Path metadataHome) {
        String stored = preferences.getString(modelPrefKey(client.id()), null);
        return stored == null
                ? bootstrapModels(preferences, client, client.id(), metadataHome)
                : parseStoredModels(stored);
    }

    /**
     * The catalog offered before the user has saved one: the model they already had selected, then
     * whatever the CLI's own metadata names. Carrying the remembered selection over matters for an
     * upgrade — earlier releases offered a hard-coded alias list, and without this the id that is
     * still in preferences would silently drop out of the picker and the next turn would quietly
     * run on a different model.
     */
    private static List<String> bootstrapModels(
            Preferences preferences,
            ChatClientProfile client,
            String providerId,
            Path metadataHome) {
        List<String> models = new ArrayList<>();
        models.add(preferences.getString(ChatModels.modelPrefKey(providerId), ""));
        if (client != null) {
            models.addAll(client.adapter().discoverModels(metadataHome));
        }
        return normalize(models);
    }

    /** Persists a normalized, ordered, duplicate-free model catalog. */
    public static void save(Preferences preferences, String providerId, List<String> models) {
        preferences.putString(modelPrefKey(providerId), serializeModels(models));
    }

    /**
     * Registers a listener notified after client catalog/name edits are saved. Notifications always
     * arrive on the event dispatch thread, so a listener may touch Swing state directly.
     */
    public static void addChangeListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /** Unregisters a listener; a view must call this as it is disposed. */
    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Announces that saved client-facing catalog settings changed. Each listener is re-checked when
     * its notification runs: a view disposed between the save and the queued notification must not
     * be called. One listener that throws is contained: the remaining views still refresh, and the
     * exception never escapes into the Preferences dialog's OK handling, which has already saved
     * the catalog by this point.
     */
    public static void fireChanged() {
        for (Runnable listener : LISTENERS) {
            Runnable notify =
                    () -> {
                        if (!LISTENERS.contains(listener)) {
                            return;
                        }
                        try {
                            listener.run();
                        } catch (RuntimeException failed) {
                            log.warn(
                                    "protege-mcp: a chat view failed to pick up the edited model"
                                        + " catalog",
                                    failed);
                        }
                    };
            if (SwingUtilities.isEventDispatchThread()) {
                notify.run();
            } else {
                SwingUtilities.invokeLater(notify);
            }
        }
    }

    /** The preference key for a client profile's editable model catalog. */
    public static String modelPrefKey(String clientId) {
        return ChatClientPreferences.modelCatalogPrefKey(clientId);
    }

    /** Pure parser used by the preference store and headless tests. */
    static List<String> parseStoredModels(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return normalize(List.of(stored.split("\\R", -1)));
    }

    /**
     * Pure serializer; model ids cannot contain a line break because the CLI receives one argv
     * value.
     */
    static String serializeModels(List<String> models) {
        return String.join("\n", normalize(models));
    }

    /** Returns a catalog with one item moved one position, or the same order at a boundary. */
    public static List<String> moveModel(List<String> models, int index, int direction) {
        List<String> moved = new ArrayList<>(normalize(models));
        int target = index + direction;
        if (index < 0
                || index >= moved.size()
                || (direction != -1 && direction != 1)
                || target < 0
                || target >= moved.size()) {
            return List.copyOf(moved);
        }
        String value = moved.get(index);
        moved.set(index, moved.get(target));
        moved.set(target, value);
        return List.copyOf(moved);
    }

    /** Returns the model picker values, including the blank CLI-default entry first. */
    public static List<String> pickerModels(Preferences preferences, String providerId) {
        return pickerModels(preferences, providerId, home());
    }

    static List<String> pickerModels(Preferences preferences, ChatClientProfile client) {
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(load(preferences, client, home()));
        return List.copyOf(values);
    }

    /** Builds picker values from the supplied CLI metadata root. */
    static List<String> pickerModels(
            Preferences preferences, String providerId, Path metadataHome) {
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(load(preferences, providerId, metadataHome));
        return List.copyOf(values);
    }

    /** Claude's effort values accepted by current Claude Code releases. */
    public static List<String> claudeReasoningEfforts() {
        return List.of("", "low", "medium", "high", "xhigh", "max");
    }

    /**
     * Codex effort values, in the CLI's own ascending order, for a model the local metadata does
     * not describe. This is the set the {@code codex} binary itself will accept for {@code
     * model_reasoning_effort}; whether the model behind it supports one is the API's answer, and a
     * refusal is reported as such. Offering fewer than the CLI accepts would hide a level that
     * works.
     */
    public static List<String> codexReasoningEfforts() {
        return ChatModelMetadataSupport.codexReasoningEfforts();
    }

    /** Adapter hook for Claude Code's local settings metadata. */
    public static List<String> discoverClaudeModels(Path metadataHome) {
        return ClaudeModelMetadata.discover(metadataHome);
    }

    /** Adapter hook for Codex's local config and model cache. */
    public static List<String> discoverCodexModels(Path metadataHome) {
        return CodexModelMetadata.discover(metadataHome);
    }

    /**
     * Returns Codex effort values for a model, using the local cache when it describes that model.
     */
    public static List<String> codexReasoningEfforts(String model) {
        return codexReasoningEfforts(model, home());
    }

    /**
     * Narrows the effort values from the supplied CLI metadata root.
     *
     * <p>Only {@code models_cache.json} is consulted, because that is the only file Codex itself
     * reads. A stale sibling backup would otherwise be able to narrow the picker down to values the
     * running CLI no longer accepts, which fails at the API rather than in the panel.
     */
    public static List<String> codexReasoningEfforts(String model, Path metadataHome) {
        return CodexModelMetadata.efforts(model, metadataHome);
    }

    /** Parses the Codex cache at most once for an ordered group of catalog models. */
    public static Map<String, List<String>> codexReasoningEffortsByModel(
            List<String> models, Path metadataHome) {
        return CodexModelMetadata.effortsByModel(models, metadataHome);
    }

    /**
     * Parses Claude settings metadata without touching the filesystem; package-private for tests.
     */
    static List<String> parseClaudeModelMetadata(String json) {
        return ClaudeModelMetadata.parse(json);
    }

    /**
     * Parses one Codex cache payload without touching the filesystem; package-private for tests.
     */
    static List<String> parseCodexReasoningEfforts(String json, String target) {
        return CodexModelMetadata.parseEfforts(json, target);
    }

    /** Adds one bounded, valid metadata model id while preserving discovery order. */
    static void addDiscoveredModel(Set<String> models, String model) {
        ChatModelMetadataSupport.addDiscoveredModel(models, model);
    }

    /**
     * Whether a model id can be offered. A control character would either break the
     * one-value-per-id storage line or make the CLI launch fail at argv assembly, and an absurdly
     * long id only exists to overflow the preference value, so neither is worth carrying into the
     * picker. Unicode's own separators are rejected for the same reason: the stored catalog is
     * split on {@code \R}, which treats them as line breaks even though they are not ISO control
     * characters.
     */
    public static boolean isAcceptableModelId(String model) {
        return ChatModelMetadataSupport.isAcceptableModelId(model);
    }

    /** The longest model id the catalog stores. */
    public static int maxModelIdChars() {
        return ChatModelMetadataSupport.maxModelIdChars();
    }

    /** The most model ids one provider's catalog stores. */
    public static int maxModels() {
        return ChatModelMetadataSupport.maxModels();
    }

    /**
     * Reads optional CLI metadata with a bounded size; package-private for headless tests. UTF-8
     * byte-order marks are dropped wherever they appear, not only at the start: editors write one
     * there and it is not part of the document — left in place it glues onto the first key or makes
     * the JSON unparseable, so the file would silently contribute nothing. One that turns up later
     * is no more content than the first, and it is worse than useless: a mark before {@code
     * [profiles.work]} or before a {@code profile =} line hides the very syntax that decides which
     * model runs, which would read a profile's model as the top-level one and narrow the effort
     * picker against it.
     */
    static String readMetadata(Path path) {
        return ChatModelMetadataSupport.readMetadata(path);
    }

    private static Path home() {
        String userHome = System.getProperty("user.home", "");
        return Path.of(userHome.isBlank() ? "." : userHome);
    }

    private static List<String> normalize(List<String> models) {
        Set<String> unique = new LinkedHashSet<>();
        if (models != null) {
            for (String model : models) {
                addDiscoveredModel(unique, model);
            }
        }
        return List.copyOf(unique);
    }
}
