package io.github.hakjuoh.protege_mcp.chat.antigravity;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.hakjuoh.protege_mcp.chat.AssistantSteering;
import io.github.hakjuoh.protege_mcp.chat.ChatAttachment;
import io.github.hakjuoh.protege_mcp.chat.ChatClientModelCatalog;
import io.github.hakjuoh.protege_mcp.chat.ChatClientPreferences;
import io.github.hakjuoh.protege_mcp.chat.ChatClientProfile;
import io.github.hakjuoh.protege_mcp.chat.ChatListener;
import io.github.hakjuoh.protege_mcp.chat.ChatProcess;
import io.github.hakjuoh.protege_mcp.chat.ChatProvider;
import io.github.hakjuoh.protege_mcp.chat.ChatRequest;
import io.github.hakjuoh.protege_mcp.chat.CliSupport;
import io.github.hakjuoh.protege_mcp.chat.McpEndpoint;
import io.github.hakjuoh.protege_mcp.config.McpConfig;

/** Drives Google Antigravity CLI ({@code agy}) in documented headless stream-JSON mode. */
public final class AntigravityCliProvider implements ChatProvider {

    public static final String ID = AntigravityClient.ID;
    public static final String EXECUTABLE = AntigravityClient.EXECUTABLE;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOME_ENV = "HOME";

    private final ChatClientProfile profile;
    private final ProcessSpawner spawner;
    private Path managedHome;

    public AntigravityCliProvider() {
        this(AntigravityClient.PROFILE, CliSupport::spawn);
    }

    public AntigravityCliProvider(ChatClientProfile profile) {
        this(profile, CliSupport::spawn);
    }

    AntigravityCliProvider(ChatClientProfile profile, ProcessSpawner spawner) {
        if (!AntigravityClient.ADAPTER.id().equals(profile.adapterId())) {
            throw new IllegalArgumentException(
                    "AntigravityCliProvider requires the antigravity-cli adapter");
        }
        this.profile = profile;
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    @Override public String id() { return profile.id(); }

    @Override
    public String displayName() {
        return ChatClientPreferences.displayName(McpConfig.prefs(), profile);
    }

    @Override public boolean isAvailable() { return resolveExecutable() != null; }

    @Override
    public List<String> listModels() {
        return new ChatClientModelCatalog(profile).pickerModels(McpConfig.prefs());
    }

    @Override
    public List<String> reasoningEfforts() {
        return reasoningEfforts("");
    }

    @Override
    public List<String> reasoningEfforts(String model) {
        return new ChatClientModelCatalog(profile).reasoningEfforts(McpConfig.prefs(), model);
    }

    @Override public String defaultModel() { return ""; }

    @Override
    public ChatProcess startTurn(ChatRequest request, ChatListener listener) throws IOException {
        String executable = resolveExecutable();
        if (executable == null) {
            throw new IOException("The 'agy' CLI was not found. Install Antigravity CLI from "
                    + "https://antigravity.google/docs/cli/install, or set its path in "
                    + "Preferences ▸ Ontology Assistant.");
        }

        Path home = prepareManagedHome(request);
        Path mcpConfig = home.resolve(".gemini/config/mcp_config.json");
        AntigravityEventParser parser = new AntigravityEventParser(listener);
        try {
            return spawner.spawn(buildCommand(executable, request),
                    Map.of(HOME_ENV, home.toString()), CliSupport.neutralWorkingDir(), parser,
                    (exit, stderr) -> {
                        deleteTokenConfig(mcpConfig);
                        CliSupport.finishJsonTurn("agy", exit, stderr,
                                parser.errorReported(), parser.answered(), listener);
                    });
        } catch (IOException | RuntimeException failure) {
            deleteTokenConfig(mcpConfig);
            throw failure;
        }
    }

    private synchronized Path prepareManagedHome(ChatRequest request) throws IOException {
        if (managedHome == null) {
            managedHome = CliSupport.createOwnerOnlyTempDirectory("protege-mcp-agy-");
            linkMacKeychains(managedHome, Path.of(System.getProperty("user.home", "")),
                    System.getProperty("os.name", ""));
            registerDirectoryForExitCleanup(managedHome.resolve(".gemini"));
            registerDirectoryForExitCleanup(managedHome.resolve(".gemini/config"));
            registerDirectoryForExitCleanup(managedHome.resolve(".gemini/antigravity-cli"));
        }
        CliSupport.writeOwnerOnlyFile(managedHome.resolve(".gemini/config/mcp_config.json"),
                mcpConfigJson(request.endpoint()));
        CliSupport.writeOwnerOnlyFile(
                managedHome.resolve(".gemini/antigravity-cli/settings.json"), settingsJson(request));
        return managedHome;
    }

    /**
     * Antigravity stores its Google OAuth token in the macOS login keychain. The Security framework
     * resolves that keychain relative to {@code HOME}; because this provider intentionally gives the
     * CLI an isolated HOME for MCP and permission settings, expose the same user-owned Keychains
     * directory there. This grants no access the child process did not already have as the same OS user,
     * and leaves every other home-directory path isolated.
     */
    static void linkMacKeychains(Path isolatedHome, Path userHome, String osName) throws IOException {
        if (osName == null || !osName.toLowerCase().contains("mac")
                || userHome == null || userHome.toString().isBlank()) {
            return;
        }
        Path keychains = userHome.resolve("Library/Keychains");
        if (!Files.isDirectory(keychains)) {
            return;
        }
        Path library = isolatedHome.resolve("Library");
        Files.createDirectories(library);
        // deleteOnExit runs in reverse registration order: register the parent before its child link.
        library.toFile().deleteOnExit();
        Path link = library.resolve("Keychains");
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, keychains);
            link.toFile().deleteOnExit();
        }
    }

    Path managedMcpConfigPath() {
        return managedHome == null ? null : managedHome.resolve(".gemini/config/mcp_config.json");
    }

    private static void registerDirectoryForExitCleanup(Path directory) throws IOException {
        Files.createDirectories(directory);
        directory.toFile().deleteOnExit();
    }

    private static void deleteTokenConfig(Path mcpConfig) {
        try {
            Files.deleteIfExists(mcpConfig);
        } catch (IOException ignored) {
            // The short-lived bearer token expires; delete-on-exit remains a backstop.
        }
    }

    private String resolveExecutable() {
        String override = McpConfig.prefs().getString(
                ChatClientPreferences.executablePathPrefKey(profile.id()), "");
        return CliSupport.resolveExecutable(profile.executable(), override);
    }

    static List<String> buildCommand(String executable, ChatRequest request) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-p");
        boolean newSession = request.sessionId() == null || request.sessionId().isBlank();
        command.add(newSession
                ? AssistantSteering.SYSTEM_PROMPT + "\n\n" + request.providerPrompt()
                : request.providerPrompt());
        command.add("--output-format");
        command.add("stream-json");
        command.add("--print-timeout");
        command.add("15m");
        command.add("--sandbox");
        if (request.model() != null && !request.model().isBlank()) {
            command.add("--model");
            command.add(request.model().trim());
        }
        if (!request.reasoningEffort().isBlank()) {
            command.add("--effort");
            command.add(request.reasoningEffort());
        }
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            command.add("--conversation");
            command.add(request.sessionId().trim());
        }
        return List.copyOf(command);
    }

    static String mcpConfigJson(McpEndpoint endpoint) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(McpEndpoint.SERVER_NAME);
        server.put("serverUrl", endpoint.url());
        server.putObject("headers").put("Authorization", "Bearer " + endpoint.token());
        return json(root);
    }

    static String settingsJson(ChatRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode permissions = root.putObject("permissions");
        ArrayNode deny = permissions.putArray("deny");
        deny.add("command(*)");
        deny.add("write_file(*)");
        deny.add("read_url(*)");
        deny.add("execute_url(*)");
        deny.add("unsandboxed(*)");
        ArrayNode allow = permissions.putArray("allow");
        allow.add("mcp(" + McpEndpoint.SERVER_NAME + "/*)");
        Set<String> files = new LinkedHashSet<>();
        for (ChatAttachment attachment : request.attachments()) {
            File file = attachment.file();
            if (file != null) {
                files.add(file.getAbsolutePath());
            }
        }
        files.forEach(path -> allow.add("read_file(" + path + ")"));
        return json(root);
    }

    private static String json(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not render Antigravity configuration", impossible);
        }
    }

    @FunctionalInterface
    interface ProcessSpawner {
        ChatProcess spawn(List<String> command, Map<String, String> environment, File workingDirectory,
                Consumer<String> lineHandler, BiConsumer<Integer, String> completionHandler)
                throws IOException;
    }
}
