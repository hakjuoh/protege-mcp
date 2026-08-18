package io.github.hakjuoh.protege_mcp.chat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovers Codex model ids and model-specific reasoning levels from local CLI metadata. */
final class CodexModelMetadata {

    private static final int MAX_EFFORTS = 24;
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CodexModelMetadata() {}

    static List<String> discover(Path metadataHome) {
        Set<String> models = new LinkedHashSet<>();
        Path codex = metadataHome.resolve(".codex");
        CodexConfigModelParser.addConfigModels(
                models, ChatModelMetadataSupport.readMetadata(codex.resolve("config.toml")));
        addCacheModels(models, codex.resolve("models_cache.json"));
        return List.copyOf(models);
    }

    static List<String> efforts(String model, Path metadataHome) {
        String target =
                model == null || model.isBlank()
                        ? CodexConfigModelParser.configuredCodexModel(metadataHome)
                        : model.trim();
        if (target == null || target.isBlank()) {
            return ChatModelMetadataSupport.codexReasoningEfforts();
        }
        return effortsByModel(List.of(target), metadataHome).get(target);
    }

    static Map<String, List<String>> effortsByModel(List<String> models, Path metadataHome) {
        if (models == null || models.isEmpty()) {
            return Map.of();
        }
        JsonNode root = readCache(metadataHome.resolve(".codex").resolve("models_cache.json"));
        Map<String, List<String>> byModel = new LinkedHashMap<>();
        for (String model : models) {
            if (model == null || model.isBlank()) {
                continue;
            }
            String target = model.trim();
            Set<String> efforts = new LinkedHashSet<>();
            efforts.add("");
            addCacheEfforts(efforts, root, target);
            byModel.put(
                    target,
                    efforts.size() > 1
                            ? List.copyOf(efforts)
                            : ChatModelMetadataSupport.codexReasoningEfforts());
        }
        return Map.copyOf(byModel);
    }

    static List<String> parseEfforts(String json, String target) {
        Set<String> efforts = new LinkedHashSet<>();
        efforts.add("");
        try {
            addCacheEfforts(efforts, JSON.readTree(json == null ? "" : json), target);
        } catch (IOException | RuntimeException ignored) {
            // Optional malformed metadata contributes nothing.
        }
        return List.copyOf(efforts);
    }

    private static JsonNode readCache(Path path) {
        try {
            return Files.isRegularFile(path)
                    ? JSON.readTree(ChatModelMetadataSupport.readMetadata(path))
                    : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void addCacheModels(Set<String> models, Path path) {
        JsonNode root = readCache(path);
        JsonNode entries = root == null ? null : root.get("models");
        if (entries == null || !entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            if (isOffered(entry)) {
                ChatModelMetadataSupport.addDiscoveredModel(models, textual(entry, "slug"));
            }
        }
    }

    private static void addCacheEfforts(Set<String> efforts, JsonNode root, String target) {
        JsonNode entries = root == null || !root.isObject() ? null : root.get("models");
        if (entries == null || !entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            if (!java.util.Objects.equals(target, textual(entry, "slug")) || !isOffered(entry)) {
                continue;
            }
            JsonNode levels = entry.get("supported_reasoning_levels");
            if (levels != null && levels.isArray()) {
                for (JsonNode level : levels) {
                    addEffort(efforts, textual(level, "effort"));
                }
            }
        }
    }

    private static String textual(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static void addEffort(Set<String> efforts, String effort) {
        if (ChatReasoningEfforts.isAcceptable(effort) && efforts.size() < MAX_EFFORTS) {
            efforts.add(effort.trim());
        }
    }

    private static boolean isOffered(JsonNode entry) {
        return entry != null
                && !(entry.path("visibility").isTextual()
                        && !"list".equals(entry.path("visibility").textValue()))
                && !(entry.path("supported_in_api").isBoolean()
                        && !entry.path("supported_in_api").booleanValue());
    }
}
