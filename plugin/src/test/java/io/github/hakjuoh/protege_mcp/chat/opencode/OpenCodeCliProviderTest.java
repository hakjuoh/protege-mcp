package io.github.hakjuoh.protege_mcp.chat.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientPreferences;
import io.github.hakjuoh.protege_mcp.chat.ChatModelDefinition;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

class OpenCodeCliProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final McpEndpoint ENDPOINT =
            new McpEndpoint("http://127.0.0.1:8123/mcp", "secret-token");

    @Test
    void clientDiscoversModelsFromConfiguredCli(@TempDir Path dir) throws Exception {
        Path executable = Files.writeString(dir.resolve("opencode"),
                "#!/bin/sh\nprintf 'opencode/mimo-v2.5-free\\nollama-cloud/qwen3.5:397b\\n'\n");
        assertTrue(executable.toFile().setExecutable(true));

        assertEquals(List.of("opencode/mimo-v2.5-free", "ollama-cloud/qwen3.5:397b"),
                OpenCodeClient.ADAPTER.discoverModels(dir, executable.toString()));
    }

    @Test
    void verboseDiscoveryAssociatesVariantsWithTheirOwnModels() {
        String output = """
                opencode/mimo-v2.5-free
                {
                  "id": "mimo-v2.5-free",
                  "variants": {}
                }
                opencode/deepseek-v4-flash-free
                {
                  "id": "deepseek-v4-flash-free",
                  "api": {"url": "https://opencode.ai/zen/v1"},
                  "variants": {
                    "high": {"reasoningEffort": "high"},
                    "max": {"reasoningEffort": "max"}
                  }
                }
                """;

        assertEquals(List.of(
                new ChatModelDefinition("opencode/mimo-v2.5-free", List.of()),
                new ChatModelDefinition(
                        "opencode/deepseek-v4-flash-free", List.of("high", "max"))),
                OpenCodeClient.parseVerboseModels(output));
    }

    @Test
    void configuredCliVerboseDiscoveryReturnsStructuredDefinitions(@TempDir Path dir)
            throws Exception {
        Path executable = Files.writeString(dir.resolve("opencode"), """
                #!/bin/sh
                printf 'opencode/mimo-v2.5-free\n{\"variants\":{}}\n'
                printf 'opencode/deepseek-v4-flash-free\n'
                printf '{\"variants\":{\"high\":{},\"max\":{}}}\n'
                """);
        assertTrue(executable.toFile().setExecutable(true));

        assertEquals(List.of(
                new ChatModelDefinition("opencode/mimo-v2.5-free", List.of()),
                new ChatModelDefinition(
                        "opencode/deepseek-v4-flash-free", List.of("high", "max"))),
                OpenCodeClient.ADAPTER.discoverModelDefinitions(dir, executable.toString()));
    }

    @Test
    void malformedVerboseMetadataDropsOnlyThatModelsOptionalEfforts() {
        String output = """
                opencode/broken
                {not-json}
                opencode/working
                {"variants":{"low":{},"high":{}}}
                """;

        assertEquals(List.of(
                new ChatModelDefinition("opencode/broken", List.of()),
                new ChatModelDefinition("opencode/working", List.of("low", "high"))),
                OpenCodeClient.parseVerboseModels(output));
    }

    @Test
    void cachedProviderMetadataCanSeedOneModelsEfforts(@TempDir Path home) throws Exception {
        Path cache = home.resolve(".cache/opencode/models.json");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, """
                {
                  "opencode": {
                    "models": {
                      "deepseek-v4-flash-free": {
                        "reasoning_options": [
                          {"type": "effort", "values": ["high", "max"]}
                        ]
                      },
                      "mimo-v2.5-free": {
                        "reasoning_options": []
                      }
                    }
                  }
                }
                """);

        assertEquals(List.of("high", "max"), OpenCodeClient.cachedReasoningEfforts(
                home, "opencode/deepseek-v4-flash-free"));
        assertEquals(List.of(), OpenCodeClient.cachedReasoningEfforts(
                home, "opencode/mimo-v2.5-free"));
        assertEquals(java.util.Map.of(
                "opencode/deepseek-v4-flash-free", List.of("high", "max"),
                "opencode/mimo-v2.5-free", List.of()),
                OpenCodeClient.cachedReasoningEffortsByModel(home, List.of(
                        "opencode/deepseek-v4-flash-free", "opencode/mimo-v2.5-free")));
    }

    @Test
    void buildsJsonRunCommandWithProviderModelAndThinking() {
        ChatRequest request = new ChatRequest("anthropic/claude-sonnet-4-5", "hello", null,
                ENDPOINT, List.of(), true, "", "high");
        List<String> command = OpenCodeCliProvider.buildCommand("opencode", request);

        assertEquals(List.of("opencode", "--pure", "run", "--format", "json"),
                command.subList(0, 5));
        assertAdjacent(command, "--model", "anthropic/claude-sonnet-4-5");
        assertAdjacent(command, "--agent", "protege-mcp-assistant");
        assertTrue(command.contains("--thinking"));
        assertAdjacent(command, "--variant", "high");
        assertEquals("--", command.get(command.size() - 2));
        assertEquals("hello", command.get(command.size() - 1));
        assertTrue(command.stream().noneMatch(value -> value.contains("secret-token")));
    }

    @Test
    void resumeAndAttachmentsUseNativeFlags(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "notes");
        ChatRequest request = new ChatRequest(null, "read it", " session-9 ", ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", file.toFile(), "text/plain")));
        List<String> command = OpenCodeCliProvider.buildCommand("opencode", request);

        assertAdjacent(command, "--session", "session-9");
        assertAdjacent(command, "--file", file.toFile().getAbsolutePath());
        assertFalse(command.contains("--model"));
        assertEquals(request.providerPrompt(), command.get(command.size() - 1));
    }

    @Test
    void inlineConfigReferencesTokenEnvironmentAndDeniesOtherTools() throws Exception {
        String json = OpenCodeCliProvider.configJson(ENDPOINT);
        JsonNode root = MAPPER.readTree(json);
        JsonNode server = root.path("mcp").path("protege");

        assertEquals("remote", server.path("type").asText());
        assertEquals(ENDPOINT.url(), server.path("url").asText());
        assertFalse(server.path("oauth").asBoolean(true));
        assertEquals("Bearer {env:PROTEGE_MCP_TOKEN}",
                server.path("headers").path("Authorization").asText());
        assertFalse(json.contains("secret-token"), "the secret belongs only in the process environment");
        assertEquals("disabled", root.path("share").asText());
        assertEquals("deny", root.path("permission").path("*").asText());
        assertEquals("allow", root.path("permission").path("protege_*").asText());
        JsonNode agentPermission = root.path("agent").path("protege-mcp-assistant")
                .path("permission");
        assertEquals("deny", agentPermission.path("*").asText());
        assertEquals("allow", agentPermission.path("protege_*").asText());
    }

    @Test
    void runtimeEnvironmentDisablesExternalPluginsAndProjectConfiguration() {
        var environment = OpenCodeCliProvider.environment(ENDPOINT, "one-turn-agent");

        assertEquals("1", environment.get(OpenCodeCliProvider.PURE_ENV_VAR));
        assertEquals("1", environment.get(
                OpenCodeCliProvider.DISABLE_PROJECT_CONFIG_ENV_VAR));
        assertEquals(environment.get(OpenCodeCliProvider.XDG_CONFIG_HOME_ENV_VAR),
                environment.get(OpenCodeCliProvider.CONFIG_DIR_ENV_VAR));
        assertTrue(Path.of(environment.get(OpenCodeCliProvider.XDG_CONFIG_HOME_ENV_VAR)).isAbsolute());
        assertEquals("secret-token", environment.get(OpenCodeCliProvider.TOKEN_ENV_VAR));
        assertTrue(environment.get(OpenCodeCliProvider.CONFIG_ENV_VAR)
                .contains("one-turn-agent"));
    }

    @Test
    void profileBackedNamePathAndModelsAreIndependent(@TempDir Path dir) throws Exception {
        var preferences = McpConfig.prefs();
        preferences.clear();
        try {
            Path executable = Files.writeString(dir.resolve("opencode"), "#!/bin/sh\n");
            assertTrue(executable.toFile().setExecutable(true));
            ChatClientPreferences.saveDisplayName(
                    preferences, OpenCodeClient.PROFILE, "Local OpenCode");
            preferences.putString(ChatClientPreferences.executablePathPrefKey("opencode"),
                    executable.toString());
            new ChatClientModelCatalog(OpenCodeClient.PROFILE).saveDefinitions(preferences,
                    List.of(new ChatModelDefinition(
                            "ollama/qwen3", List.of("low", "high"))));

            OpenCodeCliProvider provider = new OpenCodeCliProvider();
            assertEquals("Local OpenCode", provider.displayName());
            assertTrue(provider.isAvailable());
            assertEquals(List.of("", "ollama/qwen3"), provider.listModels());
            assertEquals(List.of("", "low", "high"),
                    provider.reasoningEfforts("ollama/qwen3"));
        } finally {
            preferences.clear();
        }
    }

    private static void assertAdjacent(List<String> command, String flag, String value) {
        int index = command.indexOf(flag);
        assertTrue(index >= 0, "missing " + flag + " in " + command);
        assertEquals(value, command.get(index + 1));
    }
}
