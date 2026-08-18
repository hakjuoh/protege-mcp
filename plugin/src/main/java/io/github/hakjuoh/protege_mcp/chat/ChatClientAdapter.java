package io.github.hakjuoh.protege_mcp.chat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime behavior shared by client profiles of the same type.
 *
 * <p>A profile owns user settings and identity; an adapter owns process behavior and local model
 * discovery. Keeping the strategy on the profile means two future user-created profiles can reuse
 * one adapter without sharing preferences.
 */
public interface ChatClientAdapter {

    /** Stable adapter type, such as {@code claude-cli}. */
    String id();

    /** Creates the runtime provider for one independently configured profile. */
    ChatProvider createProvider(ChatClientProfile profile);

    /** Best-effort model ids discovered from this adapter's local metadata. */
    List<String> discoverModels(Path metadataHome);

    /**
     * Potentially slower discovery using the configured executable. Callers must run this overload
     * away from the Swing event thread. Adapters backed only by local metadata inherit the cheap path.
     */
    default List<String> discoverModels(Path metadataHome, String executableOverride) {
        return discoverModels(metadataHome);
    }

    /** Cheap local/default effort metadata for one model; values exclude the implicit default. */
    default List<String> reasoningEfforts(Path metadataHome, String model) {
        return List.of();
    }

    /**
     * Batch form used while opening Preferences, so file-backed adapters can parse metadata once
     * rather than once per catalog row.
     */
    default Map<String, List<String>> reasoningEfforts(
            Path metadataHome, List<String> models) {
        Map<String, List<String>> efforts = new LinkedHashMap<>();
        if (models != null) {
            for (String model : models) {
                if (model != null) {
                    List<String> values = reasoningEfforts(metadataHome, model);
                    efforts.put(model, values == null ? List.of() : values);
                }
            }
        }
        return Map.copyOf(efforts);
    }

    /** Structured discovery used by Preferences; adapters may enrich models with CLI metadata. */
    default List<ChatModelDefinition> discoverModelDefinitions(
            Path metadataHome, String executableOverride) {
        return discoverModels(metadataHome, executableOverride).stream()
                .map(model -> new ChatModelDefinition(
                        model, reasoningEfforts(metadataHome, model)))
                .toList();
    }

    /** Official installation guidance, when this adapter is backed by an installable local CLI. */
    default Optional<ChatClientInstallGuide> installationGuide() {
        return Optional.empty();
    }
}
