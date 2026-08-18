package io.github.hakjuoh.protege_mcp.chat.opencode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Drives OpenCode ({@code opencode run --format json}) as a headless MCP client. */
public final class OpenCodeCliProvider implements ChatProvider {

    public static final String ID = OpenCodeClient.ID;
    public static final String EXECUTABLE = OpenCodeClient.EXECUTABLE;
    public static final String TOKEN_ENV_VAR = "PROTEGE_MCP_TOKEN";
    public static final String CONFIG_ENV_VAR = "OPENCODE_CONFIG_CONTENT";
    public static final String PURE_ENV_VAR = "OPENCODE_PURE";
    public static final String DISABLE_PROJECT_CONFIG_ENV_VAR = "OPENCODE_DISABLE_PROJECT_CONFIG";
    public static final String XDG_CONFIG_HOME_ENV_VAR = "XDG_CONFIG_HOME";
    public static final String CONFIG_DIR_ENV_VAR = "OPENCODE_CONFIG_DIR";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClientProfile profile;
    private Path managedConfigHome;

    public OpenCodeCliProvider() {
        this(OpenCodeClient.PROFILE);
    }

    public OpenCodeCliProvider(ChatClientProfile profile) {
        if (!OpenCodeClient.ADAPTER.id().equals(profile.adapterId())) {
            throw new IllegalArgumentException("OpenCodeCliProvider requires the opencode-cli adapter");
        }
        this.profile = profile;
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

    @Override public String defaultModel() { return ""; }

    @Override
    public List<String> reasoningEfforts() {
        return reasoningEfforts("");
    }

    @Override
    public List<String> reasoningEfforts(String model) {
        return new ChatClientModelCatalog(profile).reasoningEfforts(McpConfig.prefs(), model);
    }

    @Override
    public ChatProcess startTurn(ChatRequest request, ChatListener listener) throws IOException {
        String executable = resolveExecutable();
        if (executable == null) {
            throw new IOException("The 'opencode' CLI was not found. Install OpenCode from "
                    + "https://opencode.ai/docs/, or set its path in Preferences ▸ Ontology Assistant.");
        }

        OpenCodeEventParser parser = new OpenCodeEventParser(listener);
        String agentId = "protege-mcp-" + UUID.randomUUID();
        Path configHome = prepareManagedConfigHome();
        return CliSupport.spawnDirect(buildCommand(executable, request, agentId),
                environment(request.endpoint(), agentId, configHome),
                CliSupport.neutralWorkingDir(), parser,
                (exit, stderr) -> CliSupport.finishJsonTurn("opencode", exit, stderr,
                        parser.errorReported(), parser.answered(), listener));
    }

    private String resolveExecutable() {
        String override = McpConfig.prefs().getString(
                ChatClientPreferences.executablePathPrefKey(profile.id()), "");
        return CliSupport.resolveExecutable(profile.executable(), override);
    }

    static List<String> buildCommand(String executable, ChatRequest request) {
        return buildCommand(executable, request, "protege-mcp-assistant");
    }

    private static List<String> buildCommand(
            String executable, ChatRequest request, String agentId) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--pure");
        command.add("run");
        command.add("--format");
        command.add("json");
        command.add("--agent");
        command.add(agentId);
        if (request.model() != null && !request.model().isBlank()) {
            command.add("--model");
            command.add(request.model().trim());
        }
        if (request.showReasoning()) {
            command.add("--thinking");
        }
        if (!request.reasoningEffort().isBlank()) {
            command.add("--variant");
            command.add(request.reasoningEffort());
        }
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            command.add("--session");
            command.add(request.sessionId().trim());
        }
        for (ChatAttachment attachment : request.attachments()) {
            File file = attachment.file();
            if (file != null) {
                command.add("--file");
                command.add(file.getAbsolutePath());
            }
        }
        command.add("--");
        boolean newSession = request.sessionId() == null || request.sessionId().isBlank();
        command.add(newSession
                ? AssistantSteering.SYSTEM_PROMPT + "\n\n" + request.providerPrompt()
                : request.providerPrompt());
        return List.copyOf(command);
    }

    static String configJson(McpEndpoint endpoint) {
        return configJson(endpoint, "protege-mcp-assistant");
    }

    static Map<String, String> environment(McpEndpoint endpoint, String agentId) {
        try {
            return environment(endpoint, agentId,
                    CliSupport.createOwnerOnlyTempDirectory("protege-mcp-opencode-test-"));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Map<String, String> environment(
            McpEndpoint endpoint, String agentId, Path configHome) {
        return Map.of(
                TOKEN_ENV_VAR, endpoint.token(),
                CONFIG_ENV_VAR, configJson(endpoint, agentId),
                PURE_ENV_VAR, "1",
                DISABLE_PROJECT_CONFIG_ENV_VAR, "1",
                XDG_CONFIG_HOME_ENV_VAR, configHome.toString(),
                CONFIG_DIR_ENV_VAR, configHome.toString());
    }

    private synchronized Path prepareManagedConfigHome() throws IOException {
        if (managedConfigHome == null) {
            managedConfigHome = CliSupport.createOwnerOnlyTempDirectory("protege-mcp-opencode-");
        }
        return managedConfigHome;
    }

    private static String configJson(McpEndpoint endpoint, String agentId) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", "https://opencode.ai/config.json");
        root.put("share", "disabled");
        ObjectNode server = root.putObject("mcp").putObject(McpEndpoint.SERVER_NAME);
        server.put("type", "remote");
        server.put("url", endpoint.url());
        server.put("enabled", true);
        server.put("oauth", false);
        server.putObject("headers").put(
                "Authorization", "Bearer {env:" + TOKEN_ENV_VAR + "}");
        ObjectNode permission = root.putObject("permission");
        permission.put("*", "deny");
        permission.put(McpEndpoint.SERVER_NAME + "_*", "allow");
        ObjectNode agent = root.putObject("agent").putObject(agentId);
        agent.put("description", "Protégé MCP Assistant turn");
        agent.put("mode", "primary");
        ObjectNode agentPermission = agent.putObject("permission");
        // Agent rules are appended after merged global/managed rules. This final deny makes a user's
        // pre-existing specific allow rules unable to widen the short-lived privileged turn.
        agentPermission.put("*", "deny");
        agentPermission.put(McpEndpoint.SERVER_NAME + "_*", "allow");
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not render OpenCode configuration", impossible);
        }
    }
}
