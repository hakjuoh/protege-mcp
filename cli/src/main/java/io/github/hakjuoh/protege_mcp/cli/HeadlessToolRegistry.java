package io.github.hakjuoh.protege_mcp.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.auth.CapabilityAuthorizer;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolCatalog;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/** MCP specification adapter with the same core capability rule as the plugin registry. */
final class HeadlessToolRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HeadlessToolRegistry() {
    }

    static List<SyncToolSpecification> build(HeadlessToolService service,
            Set<String> grantedCapabilities, int maxInboundBytes, int maxOutboundBytes) {
        if (service == null || grantedCapabilities == null || grantedCapabilities.isEmpty()) {
            throw new IllegalArgumentException("headless service and capabilities are required");
        }
        grantedCapabilities.forEach(Capability::fromValue);
        Set<String> granted = Set.copyOf(grantedCapabilities);
        List<SyncToolSpecification> specifications = new ArrayList<>();
        for (HeadlessToolCatalog.Definition definition : HeadlessToolCatalog.definitions()) {
            Tool tool = Tool.builder(definition.name(), definition.inputSchema())
                    .description(definition.description()).build();
            specifications.add(SyncToolSpecification.builder().tool(tool)
                    .callHandler((exchange, request) -> {
                        List<String> missing = CapabilityAuthorizer.missing(
                                granted, definition.requiredCapabilities());
                        if (!missing.isEmpty()) {
                            try {
                                service.recordDenied(definition.name(), granted,
                                        definition.requiredCapabilities());
                            } catch (RuntimeException auditFailure) {
                                return error("Authorization denied for " + definition.name()
                                        + "; missing capabilities: " + String.join(", ", missing)
                                        + ". Audit attribution also failed; the request remained denied.");
                            }
                            return error("Authorization denied for " + definition.name()
                                    + "; missing capabilities: " + String.join(", ", missing) + ".");
                        }
                        try {
                            Map<String, Object> result = service.execute(definition.name(),
                                    request == null ? Map.of() : request.arguments(), granted,
                                    maxInboundBytes, maxOutboundBytes);
                            return ok(result);
                        } catch (Exception failure) {
                            return error(message(failure));
                        }
                    }).build());
        }
        return List.copyOf(specifications);
    }

    private static CallToolResult ok(Map<String, Object> data) {
        Map<String, Object> body = data == null ? Map.of() : data;
        return CallToolResult.builder().structuredContent(body)
                .addTextContent(json(body)).isError(false).build();
    }

    private static CallToolResult error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null || message.isBlank() ? "error" : message);
        return CallToolResult.builder().structuredContent(body)
                .addTextContent(json(body)).isError(true).build();
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            return String.valueOf(value);
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
