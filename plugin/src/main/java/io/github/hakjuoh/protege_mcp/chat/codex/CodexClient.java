package io.github.hakjuoh.protege_mcp.chat.codex;

import io.github.hakjuoh.protege_mcp.chat.ChatClientInstallGuide;
import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientAdapter;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatReasoningEfforts;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Codex client identity and predefined settings. */
public final class CodexClient {

    public static final String ID = "codex";
    public static final String EXECUTABLE = "codex";
    public static final ChatClientInstallGuide INSTALL_GUIDE = new ChatClientInstallGuide(
            "npm install --global @openai/codex",
            "npm install --global @openai/codex",
            "After installation, run 'codex' once and choose Sign in with ChatGPT or another "
                    + "available sign-in method.",
            URI.create("https://learn.chatgpt.com/docs/codex/cli"));
    public static final ChatClientAdapter ADAPTER = new ChatClientAdapter() {
        @Override public String id() { return "codex-cli"; }
        @Override public ChatProvider createProvider(ChatClientProfile profile) {
            return new CodexCliProvider(profile);
        }
        @Override public List<String> discoverModels(Path metadataHome) {
            return ChatModelCatalog.discoverCodexModels(metadataHome);
        }
        @Override public List<String> reasoningEfforts(Path metadataHome, String model) {
            return ChatReasoningEfforts.normalize(
                    ChatModelCatalog.codexReasoningEfforts(model, metadataHome));
        }
        @Override public Map<String, List<String>> reasoningEfforts(
                Path metadataHome, List<String> models) {
            Map<String, List<String>> discovered =
                    ChatModelCatalog.codexReasoningEffortsByModel(models, metadataHome);
            Map<String, List<String>> explicit = new LinkedHashMap<>();
            discovered.forEach((model, efforts) ->
                    explicit.put(model, ChatReasoningEfforts.normalize(efforts)));
            return Map.copyOf(explicit);
        }
        @Override public Optional<ChatClientInstallGuide> installationGuide() {
            return Optional.of(INSTALL_GUIDE);
        }
    };
    public static final ChatClientProfile PROFILE = new ChatClientProfile(
            ID, ADAPTER, "Codex", EXECUTABLE);
    public static final ChatClientModelCatalog MODEL_CATALOG = new ChatClientModelCatalog(PROFILE);

    private CodexClient() {
    }
}
