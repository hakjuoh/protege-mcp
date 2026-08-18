package io.github.hakjuoh.protege_mcp.chat.claude;

import io.github.hakjuoh.protege_mcp.chat.ChatClientInstallGuide;
import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientAdapter;
import io.github.hakjuoh.protege_mcp.chat.ChatModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatReasoningEfforts;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Claude Code client identity and predefined settings. */
public final class ClaudeClient {

    public static final String ID = "claude";
    public static final String EXECUTABLE = "claude";
    public static final ChatClientInstallGuide INSTALL_GUIDE = new ChatClientInstallGuide(
            "curl -fsSL https://claude.ai/install.sh | bash",
            "winget install Anthropic.ClaudeCode",
            "After installation, run 'claude' once and follow the browser sign-in flow.",
            URI.create("https://code.claude.com/docs/en/setup"));
    public static final ChatClientAdapter ADAPTER = new ChatClientAdapter() {
        @Override public String id() { return "claude-cli"; }
        @Override public ChatProvider createProvider(ChatClientProfile profile) {
            return new ClaudeCliProvider(profile);
        }
        @Override public List<String> discoverModels(Path metadataHome) {
            return ChatModelCatalog.discoverClaudeModels(metadataHome);
        }
        @Override public List<String> reasoningEfforts(Path metadataHome, String model) {
            // Claude exposes one CLI-level --effort range rather than per-model metadata. Seed each
            // row independently with that range so users can narrow models that accept less.
            return ChatReasoningEfforts.normalize(ChatModelCatalog.claudeReasoningEfforts());
        }
        @Override public Optional<ChatClientInstallGuide> installationGuide() {
            return Optional.of(INSTALL_GUIDE);
        }
    };
    public static final ChatClientProfile PROFILE = new ChatClientProfile(
            ID, ADAPTER, "Claude Code", EXECUTABLE);
    public static final ChatClientModelCatalog MODEL_CATALOG = new ChatClientModelCatalog(PROFILE);

    private ClaudeClient() {
    }
}
