package io.github.hakjuoh.protege_mcp.chat.antigravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.chat.AssistantSteering;
import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientPreferences;
import io.github.hakjuoh.protege_mcp.chat.ChatModelDefinition;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;
import io.github.hakjuoh.protege_mcp.chat.RecordingChatListener;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

class AntigravityCliProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final McpEndpoint ENDPOINT =
            new McpEndpoint("http://127.0.0.1:8123/mcp", "secret-token");

    @Test
    void clientDiscoversModelsFromConfiguredCli(@TempDir Path dir) throws Exception {
        Path executable = Files.writeString(dir.resolve("agy"),
                "#!/bin/sh\nprintf 'gemini-3.5-flash-low\\nclaude-sonnet-4-6\\n'\n");
        assertTrue(executable.toFile().setExecutable(true));

        assertEquals(List.of("gemini-3.5-flash-low", "claude-sonnet-4-6"),
                AntigravityClient.ADAPTER.discoverModels(dir, executable.toString()));
    }

    @Test
    void clientDiscoversModelDefinitionsFromTabularAgyOutput(@TempDir Path dir) throws Exception {
        Path executable = Files.writeString(dir.resolve("agy"),
                "#!/bin/sh\n"
                    + "printf 'gemini-3.7-flash-high     Gemini 3.7 Flash (High)\\n'\n"
                    + "printf 'gemini-3.7-flash-medium   Gemini 3.7 Flash (Medium)\\n'\n"
                    + "printf 'gemini-3.7-flash-low      Gemini 3.7 Flash (Low)\\n'\n"
                    + "printf 'claude-sonnet-4-6         Claude Sonnet 4.6 (Thinking)\\n'\n");
        assertTrue(executable.toFile().setExecutable(true));

        assertEquals(
                List.of(
                        new ChatModelDefinition("gemini-3.7-flash", List.of("low", "medium", "high")),
                        new ChatModelDefinition("claude-sonnet-4-6", List.of())),
                AntigravityClient.ADAPTER.discoverModelDefinitions(dir, executable.toString()));
    }

    @Test
    void suffixedCliModelsBecomeOneModelWithOnlyItsAvailableEfforts() {
        assertEquals(List.of(
                new ChatModelDefinition("gemini-3.6-flash",
                        List.of("low", "medium", "high")),
                new ChatModelDefinition("claude-sonnet-4-6", List.of()),
                new ChatModelDefinition("gpt-oss-120b", List.of("medium"))),
                AntigravityClient.groupEffortSuffixedModels(List.of(
                        "gemini-3.6-flash-high",
                        "gemini-3.6-flash-medium",
                        "gemini-3.6-flash-low",
                        "claude-sonnet-4-6",
                        "gpt-oss-120b-medium")));
    }

    @Test
    void buildsDocumentedHeadlessCommand() {
        ChatRequest request = new ChatRequest("gemini-3", "hello", null, ENDPOINT,
                List.of(), false, "", "high");
        List<String> command = AntigravityCliProvider.buildCommand("/opt/agy", request);

        assertEquals("/opt/agy", command.get(0));
        assertAdjacent(command, "-p", AssistantSteering.SYSTEM_PROMPT + "\n\nhello");
        assertAdjacent(command, "--output-format", "stream-json");
        assertTrue(command.contains("--sandbox"));
        assertAdjacent(command, "--model", "gemini-3");
        assertAdjacent(command, "--effort", "high");
        assertTrue(command.stream().noneMatch(value -> value.contains("secret-token")));
    }

    @Test
    void resumeUsesConversationWithoutRepeatingSteering() {
        List<String> command = AntigravityCliProvider.buildCommand("agy",
                new ChatRequest(null, "again", " conversation-7 ", ENDPOINT));
        assertAdjacent(command, "--conversation", "conversation-7");
        assertAdjacent(command, "-p", "again");
        assertFalse(command.contains("--model"));
    }

    @Test
    void mcpConfigurationUsesStreamableHttpAndBearerHeader() throws Exception {
        JsonNode root = MAPPER.readTree(AntigravityCliProvider.mcpConfigJson(ENDPOINT));
        JsonNode server = root.path("mcpServers").path("protege");
        assertEquals(ENDPOINT.url(), server.path("httpUrl").asText());
        assertFalse(server.has("serverUrl"), "serverUrl would select legacy SSE transport");
        assertEquals("Bearer secret-token", server.path("headers").path("Authorization").asText());
    }

    @Test
    void managedPermissionsAllowOnlyProtegeAndAttachmentReads(@TempDir Path dir) throws Exception {
        Path attachment = Files.writeString(dir.resolve("notes.txt"), "notes");
        ChatRequest request = new ChatRequest("", "see file", null, ENDPOINT,
                List.of(ChatAttachment.file("File #1: notes.txt", attachment.toFile(), "text/plain")));
        JsonNode permissions = MAPPER.readTree(AntigravityCliProvider.settingsJson(request))
                .path("permissions");

        assertTrue(contains(permissions.path("deny"), "command(*)"));
        assertTrue(contains(permissions.path("deny"), "write_file(*)"));
        assertTrue(contains(permissions.path("allow"), "mcp(protege/*)"));
        assertTrue(contains(permissions.path("allow"),
                "read_file(" + attachment.toFile().getAbsolutePath() + ")"));
        assertFalse(contains(permissions.path("allow"),
                "read_file(" + dir.toFile().getAbsolutePath() + ")"),
                "an attachment must not expose sibling files in its parent directory");
    }

    @Test
    void profileBackedNamePathAndModelsAreIndependent(@TempDir Path dir) throws Exception {
        var preferences = McpConfig.prefs();
        preferences.clear();
        try {
            Path executable = Files.writeString(dir.resolve("agy"), "#!/bin/sh\n");
            assertTrue(executable.toFile().setExecutable(true));
            ChatClientPreferences.saveDisplayName(
                    preferences, AntigravityClient.PROFILE, "Antigravity Research");
            preferences.putString(ChatClientPreferences.executablePathPrefKey("antigravity"),
                    executable.toString());
            new ChatClientModelCatalog(AntigravityClient.PROFILE).saveDefinitions(preferences,
                    List.of(new ChatModelDefinition(
                            "gemini-custom", List.of("low", "medium"))));

            AntigravityCliProvider provider = new AntigravityCliProvider();
            assertEquals("Antigravity Research", provider.displayName());
            assertTrue(provider.isAvailable());
            assertEquals(List.of("", "gemini-custom"), provider.listModels());
            assertEquals(List.of("", "low", "medium"),
                    provider.reasoningEfforts("gemini-custom"));
        } finally {
            preferences.clear();
        }
    }

    @Test
    void spawnFailureDeletesTokenBearingMcpConfig(@TempDir Path dir) throws Exception {
        var preferences = McpConfig.prefs();
        preferences.clear();
        try {
            Path executable = Files.writeString(dir.resolve("agy"), "#!/bin/sh\n");
            assertTrue(executable.toFile().setExecutable(true));
            preferences.putString(ChatClientPreferences.executablePathPrefKey("antigravity"),
                    executable.toString());
            AntigravityCliProvider provider = new AntigravityCliProvider(
                    AntigravityClient.PROFILE,
                    (command, environment, workingDirectory, lines, completion) -> {
                        throw new java.io.IOException("spawn failed");
                    });

            assertThrows(java.io.IOException.class, () -> provider.startTurn(
                    new ChatRequest("", "hello", null, ENDPOINT),
                    new RecordingChatListener()));
            assertFalse(Files.exists(provider.managedMcpConfigPath()),
                    "the bearer-token config must not survive a failed process start");
        } finally {
            preferences.clear();
        }
    }

    @Test
    void isolatedMacHomeLinksOnlyTheUsersKeychainDirectory(@TempDir Path dir) throws Exception {
        Path userHome = dir.resolve("user");
        Path keychains = Files.createDirectories(userHome.resolve("Library/Keychains"));
        Path isolated = Files.createDirectories(dir.resolve("isolated"));

        AntigravityCliProvider.linkMacKeychains(isolated, userHome, "Mac OS X");

        Path link = isolated.resolve("Library/Keychains");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(keychains, Files.readSymbolicLink(link));
        assertFalse(Files.exists(isolated.resolve(".gemini")),
                "authentication access must not expose the user's other Antigravity files");
    }

    @Test
    void nonMacHomeDoesNotCreateAKeychainLink(@TempDir Path dir) throws Exception {
        Path userHome = dir.resolve("user");
        Files.createDirectories(userHome.resolve("Library/Keychains"));
        Path isolated = Files.createDirectories(dir.resolve("isolated"));

        AntigravityCliProvider.linkMacKeychains(isolated, userHome, "Linux");

        assertFalse(Files.exists(isolated.resolve("Library")));
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode element : array) {
            if (value.equals(element.asText())) {
                return true;
            }
        }
        return false;
    }

    private static void assertAdjacent(List<String> command, String flag, String value) {
        int index = command.indexOf(flag);
        assertTrue(index >= 0, "missing " + flag + " in " + command);
        assertEquals(value, command.get(index + 1));
    }
}
