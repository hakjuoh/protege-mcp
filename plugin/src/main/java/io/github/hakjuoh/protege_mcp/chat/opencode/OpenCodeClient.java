package io.github.hakjuoh.protege_mcp.chat.opencode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.chat.ChatClientAdapter;
import io.github.hakjuoh.protege_mcp.chat.ChatClientInstallGuide;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.ChatModelDefinition;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatReasoningEfforts;

/** OpenCode CLI identity and predefined settings. */
public final class OpenCodeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String ID = "opencode";
    public static final String EXECUTABLE = "opencode";
    public static final ChatClientInstallGuide INSTALL_GUIDE = new ChatClientInstallGuide(
            "curl -fsSL https://opencode.ai/install | bash",
            "npm install -g opencode-ai",
            "After installation, run 'opencode' and enter '/connect' to configure a model provider.",
            URI.create("https://opencode.ai/docs/"));
    public static final ChatClientAdapter ADAPTER = new ChatClientAdapter() {
        @Override public String id() { return "opencode-cli"; }
        @Override public ChatProvider createProvider(ChatClientProfile profile) {
            return new OpenCodeCliProvider(profile);
        }
        @Override public List<String> discoverModels(Path metadataHome) {
            return List.of();
        }
        @Override public List<String> discoverModels(Path metadataHome, String executableOverride) {
            return CliSupport.discoverModelIds(EXECUTABLE, executableOverride);
        }
        @Override public List<String> reasoningEfforts(Path metadataHome, String model) {
            return cachedReasoningEfforts(metadataHome, model);
        }
        @Override public Map<String, List<String>> reasoningEfforts(
                Path metadataHome, List<String> models) {
            return cachedReasoningEffortsByModel(metadataHome, models);
        }
        @Override public List<ChatModelDefinition> discoverModelDefinitions(
                Path metadataHome, String executableOverride) {
            Optional<String> output = CliSupport.runDiscoveryCommand(
                    EXECUTABLE, executableOverride, List.of("models", "--verbose"));
            List<ChatModelDefinition> definitions = output
                    .map(OpenCodeClient::parseVerboseModels).orElseGet(List::of);
            if (!definitions.isEmpty()) {
                return definitions;
            }
            return ChatClientAdapter.super.discoverModelDefinitions(
                    metadataHome, executableOverride);
        }
        @Override public Optional<ChatClientInstallGuide> installationGuide() {
            return Optional.of(INSTALL_GUIDE);
        }
    };
    public static final ChatClientProfile PROFILE = new ChatClientProfile(
            ID, ADAPTER, "OpenCode", EXECUTABLE);
    public static final ChatClientModelCatalog MODEL_CATALOG = new ChatClientModelCatalog(PROFILE);

    private OpenCodeClient() {
    }

    static List<String> cachedReasoningEfforts(Path metadataHome, String qualifiedModel) {
        String model = qualifiedModel == null ? "" : qualifiedModel.trim();
        return cachedReasoningEffortsByModel(metadataHome, List.of(model))
                .getOrDefault(model, List.of());
    }

    static Map<String, List<String>> cachedReasoningEffortsByModel(
            Path metadataHome, List<String> qualifiedModels) {
        if (qualifiedModels == null || qualifiedModels.isEmpty()) {
            return Map.of();
        }
        Path cache = metadataHome.resolve(".cache/opencode/models.json");
        JsonNode root = null;
        try {
            if (!Files.isRegularFile(cache) || Files.size(cache) > 8L * 1024 * 1024) {
                return Map.of();
            }
            root = MAPPER.readTree(Files.readString(cache));
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
        if (root == null) {
            return Map.of();
        }
        Map<String, List<String>> efforts = new LinkedHashMap<>();
        for (String qualifiedModel : qualifiedModels) {
            String model = qualifiedModel == null ? "" : qualifiedModel.trim();
            int slash = model.indexOf('/');
            if (slash <= 0 || slash == model.length() - 1) {
                continue;
            }
            JsonNode entry = root.path(model.substring(0, slash)).path("models")
                    .path(model.substring(slash + 1));
            efforts.put(model, reasoningOptions(entry.path("reasoning_options")));
        }
        return Map.copyOf(efforts);
    }

    static List<ChatModelDefinition> parseVerboseModels(String output) {
        List<ChatModelDefinition> definitions = new ArrayList<>();
        String currentModel = "";
        StringBuilder json = null;
        int depth = 0;
        for (String line : (output == null ? "" : output).split("\\R")) {
            if (json == null) {
                String candidate = line.trim();
                if (ChatModelCatalog.isAcceptableModelId(candidate)
                        && candidate.indexOf('/') > 0) {
                    currentModel = candidate;
                } else if (!currentModel.isEmpty() && candidate.startsWith("{")) {
                    json = new StringBuilder();
                    json.append(line).append('\n');
                    depth = jsonBraceDelta(line);
                    if (depth == 0) {
                        addVerboseDefinition(definitions, currentModel, json.toString());
                        currentModel = "";
                        json = null;
                        if (definitions.size() == ChatModelCatalog.maxModels()) {
                            break;
                        }
                    }
                }
            } else {
                json.append(line).append('\n');
                depth += jsonBraceDelta(line);
                if (depth == 0) {
                    addVerboseDefinition(definitions, currentModel, json.toString());
                    currentModel = "";
                    json = null;
                    if (definitions.size() == ChatModelCatalog.maxModels()) {
                        break;
                    }
                }
            }
        }
        return List.copyOf(definitions);
    }

    private static void addVerboseDefinition(
            List<ChatModelDefinition> definitions, String model, String json) {
        try {
            JsonNode variants = MAPPER.readTree(json).path("variants");
            List<String> efforts = new ArrayList<>();
            if (variants.isObject()) {
                variants.fieldNames().forEachRemaining(efforts::add);
            }
            definitions.add(new ChatModelDefinition(model, efforts));
        } catch (IOException | RuntimeException ignored) {
            // Keep the model selectable even if one metadata block is malformed; only its optional
            // effort enrichment is lost, and later blocks can still be parsed.
            definitions.add(new ChatModelDefinition(model, List.of()));
        }
    }

    private static List<String> reasoningOptions(JsonNode options) {
        List<String> efforts = new ArrayList<>();
        if (options.isArray()) {
            for (JsonNode option : options) {
                if (!"effort".equals(option.path("type").asText())) {
                    continue;
                }
                JsonNode values = option.path("values");
                if (values.isArray()) {
                    values.forEach(value -> {
                        if (value.isTextual()) {
                            efforts.add(value.asText());
                        }
                    });
                }
            }
        }
        return ChatReasoningEfforts.normalize(efforts);
    }

    private static int jsonBraceDelta(String line) {
        int delta = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\' && inString) {
                escaped = true;
            } else if (character == '"') {
                inString = !inString;
            } else if (!inString && character == '{') {
                delta++;
            } else if (!inString && character == '}') {
                delta--;
            }
        }
        return delta;
    }
}
