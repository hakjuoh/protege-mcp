package io.github.hakjuoh.protege_mcp.chat.antigravity;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.github.hakjuoh.protege_mcp.chat.ChatClientAdapter;
import io.github.hakjuoh.protege_mcp.chat.ChatClientInstallGuide;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatModelDefinition;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;

/** Google Antigravity CLI identity and predefined settings. */
public final class AntigravityClient {

    private static final List<String> EFFORT_ORDER = List.of("low", "medium", "high");

    public static final String ID = "antigravity";
    public static final String EXECUTABLE = "agy";
    public static final ChatClientInstallGuide INSTALL_GUIDE = new ChatClientInstallGuide(
            "curl -fsSL https://antigravity.google/cli/install.sh | bash",
            "irm https://antigravity.google/cli/install.ps1 | iex",
            "After installation, run 'agy' once. It reuses a saved keyring session or opens a "
                    + "browser so you can sign in.",
            URI.create("https://antigravity.google/docs/cli/install"));
    public static final ChatClientAdapter ADAPTER = new ChatClientAdapter() {
        @Override public String id() { return "antigravity-cli"; }
        @Override public ChatProvider createProvider(ChatClientProfile profile) {
            return new AntigravityCliProvider(profile);
        }
        @Override public List<String> discoverModels(Path metadataHome) {
            return List.of();
        }
        @Override public List<String> discoverModels(Path metadataHome, String executableOverride) {
            return CliSupport.discoverModelIds(EXECUTABLE, executableOverride);
        }
        @Override public List<String> reasoningEfforts(Path metadataHome, String model) {
            String effort = suffixedEffort(model);
            return effort.isEmpty() ? List.of() : List.of(effort);
        }
        @Override public List<ChatModelDefinition> discoverModelDefinitions(
                Path metadataHome, String executableOverride) {
            return groupEffortSuffixedModels(
                    CliSupport.discoverModelIds(EXECUTABLE, executableOverride));
        }
        @Override public Optional<ChatClientInstallGuide> installationGuide() {
            return Optional.of(INSTALL_GUIDE);
        }
    };
    public static final ChatClientProfile PROFILE = new ChatClientProfile(
            ID, ADAPTER, "Antigravity", EXECUTABLE);
    public static final ChatClientModelCatalog MODEL_CATALOG = new ChatClientModelCatalog(PROFILE);

    private AntigravityClient() {
    }

    static List<ChatModelDefinition> groupEffortSuffixedModels(List<String> modelIds) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        if (modelIds != null) {
            for (String modelId : modelIds) {
                String id = modelId == null ? "" : modelId.trim();
                if (!ChatModelCatalog.isAcceptableModelId(id)) {
                    continue;
                }
                String effort = suffixedEffort(id);
                String model = effort.isEmpty()
                        ? id
                        : id.substring(0, id.length() - effort.length() - 1);
                grouped.computeIfAbsent(model, ignored -> new ArrayList<>());
                if (!effort.isEmpty() && !grouped.get(model).contains(effort)) {
                    grouped.get(model).add(effort);
                }
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new ChatModelDefinition(entry.getKey(), EFFORT_ORDER.stream()
                        .filter(entry.getValue()::contains).toList()))
                .toList();
    }

    private static String suffixedEffort(String model) {
        String value = model == null ? "" : model.trim();
        for (String effort : EFFORT_ORDER) {
            if (value.endsWith("-" + effort) && value.length() > effort.length() + 1) {
                return effort;
            }
        }
        return "";
    }
}
