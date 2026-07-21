package io.github.hakjuoh.protege_mcp.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.auth.CapabilityAuthorizer;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolCatalog;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessExecutionException;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;
import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ToolError;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;
import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/** MCP specification adapter with the same core capability rule as the plugin registry. */
final class HeadlessToolRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(HeadlessToolRegistry.class);

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
            ToolSchemaValidator.Compiled outputContract =
                    ToolSchemaValidator.compile(definition.outputSchema());
            Tool tool = Tool.builder(definition.name(), definition.inputSchema())
                    .description(definition.description())
                    .outputSchema(ToolContractSchemas.wireOutputSchema(
                            definition.outputSchema()))
                    .meta(Map.of(ToolContractSchemas.SUCCESS_SCHEMA_META_KEY,
                            definition.outputSchema(), ToolContractSchemas.ERROR_SCHEMA_META_KEY,
                            definition.errorSchema()))
                    .build();
            specifications.add(SyncToolSpecification.builder().tool(tool)
                    .callHandler((exchange, request) -> {
                        List<String> missing = CapabilityAuthorizer.missing(
                                granted, definition.requiredCapabilities());
                        if (!missing.isEmpty()) {
                            try {
                                service.recordDenied(definition.name(), granted,
                                        definition.requiredCapabilities());
                            } catch (RuntimeException auditFailure) {
                                return error("audit_failed_while_denied",
                                        "Authorization denied for " + definition.name()
                                        + "; missing capabilities: " + String.join(", ", missing)
                                        + ". Audit attribution also failed; the request remained denied.",
                                        false, Map.of("request_denied", true,
                                                "effects_prevented", true));
                            }
                            return error("authorization_denied",
                                    "Authorization denied for " + definition.name()
                                    + "; missing capabilities: " + String.join(", ", missing) + ".",
                                    false);
                        }
                        try {
                            Map<String, Object> result = service.execute(definition.name(),
                                    request == null ? Map.of() : request.arguments(), granted,
                                    maxInboundBytes, maxOutboundBytes);
                            validateResultContract(definition.name(), outputContract, result,
                                    definition.requiredCapabilities().stream().anyMatch(capability ->
                                            capability.equals(Capability.ONTOLOGY_CURATE.value())
                                            || capability.equals(Capability.ONTOLOGY_ADMIN.value())
                                            || capability.equals(
                                                    Capability.FILESYSTEM_PROJECT_WRITE.value())));
                            return ok(result);
                        } catch (Exception failure) {
                            return failure(failure);
                        }
                    }).build());
        }
        return List.copyOf(specifications);
    }

    static void validateResultContract(String name, ToolSchemaValidator.Compiled contract,
            Map<String, Object> result, boolean mutationExpected) {
        List<String> violations = contract.violations(result);
        if (!violations.isEmpty()) {
            throw new HeadlessExecutionException("result_contract_violation",
                    "Tool '" + name + "' returned a result that violates its advertised "
                            + "output schema.",
                    Map.of("violations", violations,
                            "outcome_unknown", mutationExpected), false, null);
        }
    }

    static CallToolResult ok(Map<String, Object> data) {
        Map<String, Object> body = ImmutableJson.resultMap(data == null ? Map.of() : data);
        return CallToolResult.builder().structuredContent(body)
                .addTextContent(json(body)).isError(false).build();
    }

    private static CallToolResult error(String code, String message, boolean retryable) {
        Map<String, Object> body = ImmutableJson.resultMap(ToolError.of(code,
                message == null || message.isBlank() ? "Operation failed." : message,
                retryable).toJson());
        return CallToolResult.builder().structuredContent(body)
                .addTextContent(json(body)).isError(true).build();
    }

    static CallToolResult failure(Exception failure) {
        if (failure instanceof HeadlessExecutionException typed) {
            return error(typed.code(), typed.getMessage(), typed.retryable(), typed.details());
        }
        if (failure instanceof IllegalArgumentException) {
            return error("invalid_request", message(failure), false);
        }
        if (failure instanceof java.io.IOException) {
            log.warn("protege-mcp headless: I/O failure; type={}", failure.getClass().getName());
            return error("io_failed", "Headless I/O operation failed.", false);
        }
        log.warn("protege-mcp headless: unexpected tool failure; type={}",
                failure.getClass().getName());
        return error("internal_error", "Unexpected tool failure.", false);
    }

    private static CallToolResult error(String code, String message, boolean retryable,
            Map<String, Object> details) {
        Map<String, Object> body = ImmutableJson.resultMap(ToolError.of(code,
                message == null || message.isBlank() ? "Operation failed." : message,
                details, retryable).toJson());
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
