package io.github.hakjuoh.protege_mcp.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Discovers model ids from Claude Code's local settings files. */
final class ClaudeModelMetadata {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ClaudeModelMetadata() {}

    static List<String> discover(Path metadataHome) {
        Set<String> models = new LinkedHashSet<>();
        Path claude = metadataHome.resolve(".claude");
        addFile(models, claude.resolve("settings.json"));
        addFile(models, claude.resolve("settings.local.json"));
        return List.copyOf(models);
    }

    static List<String> parse(String json) {
        Set<String> models = new LinkedHashSet<>();
        try {
            addJsonModels(models, JSON.readTree(json == null ? "" : json));
        } catch (IOException | RuntimeException ignored) {
            // Optional malformed metadata contributes nothing.
        }
        return List.copyOf(models);
    }

    private static void addFile(Set<String> models, Path path) {
        try {
            if (Files.isRegularFile(path)) {
                addJsonModels(models, JSON.readTree(ChatModelMetadataSupport.readMetadata(path)));
            }
        } catch (IOException | RuntimeException ignored) {
            // Discovery is best effort; the configured/default model remains available.
        }
    }

    private static void addJsonModels(Set<String> models, JsonNode root) {
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode model = root.get("model");
        if (model != null && model.isTextual()) {
            ChatModelMetadataSupport.addDiscoveredModel(models, model.asText());
        }
        JsonNode env = root.get("env");
        if (env != null && env.isObject()) {
            env.fields()
                    .forEachRemaining(
                            entry -> {
                                if (isModelKey(entry.getKey()) && entry.getValue().isTextual()) {
                                    ChatModelMetadataSupport.addDiscoveredModel(
                                            models, entry.getValue().asText());
                                }
                            });
        }
    }

    private static boolean isModelKey(String key) {
        return key.equals("ANTHROPIC_MODEL")
                || key.equals("ANTHROPIC_SMALL_FAST_MODEL")
                || (key.startsWith("ANTHROPIC_DEFAULT_") && key.endsWith("_MODEL"));
    }
}
