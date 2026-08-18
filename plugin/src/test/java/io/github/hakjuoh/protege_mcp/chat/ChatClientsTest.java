package io.github.hakjuoh.protege_mcp.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.config.McpConfig;

class ChatClientsTest {

    @Test
    void predefinedProfilesDescribeRuntimeAdaptersInDisplayOrder() {
        List<ChatClientProfile> clients = ChatClients.predefined();

        assertEquals(List.of("claude", "codex", "antigravity", "opencode"),
                clients.stream().map(ChatClientProfile::id).toList());
        assertEquals(List.of("claude-cli", "codex-cli", "antigravity-cli", "opencode-cli"),
                clients.stream().map(ChatClientProfile::adapterId).toList());
        assertEquals(List.of("Claude Code", "Codex", "Antigravity", "OpenCode"),
                clients.stream().map(ChatClientProfile::defaultDisplayName).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> clients.add(clients.get(0)));
        assertEquals(clients.stream().map(ChatClientProfile::id).toList(),
                Providers.all().stream().map(ChatProvider::id).toList(),
                "settings and runtime providers must come from the same registration source");
    }

    @Test
    void lookupUsesStableIds() {
        assertEquals("codex", ChatClients.byId("codex").id());
        assertNull(ChatClients.byId(null));
        assertNull(ChatClients.byId("Codex"));
        assertEquals("antigravity", ChatClients.byId("antigravity").id());
        assertEquals("opencode", ChatClients.byId("opencode").id());
        assertNull(ChatClients.byId("ollama"));
    }

    @Test
    void profileRejectsBlankIdentityAndRuntimeFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChatClientProfile("", adapter(), "Name", "exe"));
        assertThrows(NullPointerException.class,
                () -> new ChatClientProfile("id", null, "Name", "exe"));
    }

    @Test
    void predefinedAdaptersExposePlatformCommandsAndOfficialHttpsGuides() {
        List<ChatClientInstallGuide> guides = ChatClients.predefined().stream()
                .map(client -> client.adapter().installationGuide().orElseThrow())
                .toList();

        assertEquals(List.of("code.claude.com", "learn.chatgpt.com", "antigravity.google",
                        "opencode.ai"),
                guides.stream().map(guide -> guide.documentationUri().getHost()).toList());
        assertEquals(List.of("claude", "codex", "agy", "opencode"),
                guides.stream().map(guide -> guide.firstRunInstruction().split("'")[1]).toList());
        assertTrue(guides.stream().allMatch(guide ->
                "Terminal".equals(guide.commandFor("Mac OS X").label())
                && "PowerShell".equals(guide.commandFor("Windows 11").label())
                && !guide.commandFor("Linux").command().isBlank()));
    }

    @Test
    void installGuideRejectsUnsafeOrIncompleteMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new ChatClientInstallGuide(
                "unix", "windows", "first run", URI.create("http://example.test/guide")));
        assertThrows(IllegalArgumentException.class, () -> new ChatClientInstallGuide(
                " ", "windows", "first run", URI.create("https://example.test/guide")));
    }

    @Test
    void everyPredefinedProviderUsesItsSavedPerModelEfforts() {
        var preferences = McpConfig.prefs();
        preferences.clear();
        try {
            int index = 0;
            for (ChatClientProfile client : ChatClients.predefined()) {
                String effort = "client-effort-" + index++;
                new ChatClientModelCatalog(client).saveDefinitions(preferences,
                        List.of(new ChatModelDefinition("model-a", List.of(effort))));
                ChatProvider provider = client.adapter().createProvider(client);
                assertEquals(List.of("", effort), provider.reasoningEfforts("model-a"),
                        client.id());
            }
        } finally {
            preferences.clear();
        }
    }

    private static ChatClientAdapter adapter() {
        return new ChatClientAdapter() {
            @Override public String id() { return "test-adapter"; }
            @Override public ChatProvider createProvider(ChatClientProfile profile) { return null; }
            @Override public List<String> discoverModels(Path home) { return List.of(); }
        };
    }
}
