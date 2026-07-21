package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import io.github.hakjuoh.protege_mcp.catalog.McpCatalog;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.auth.CapabilityAuthorizer;
import io.github.hakjuoh.protege_mcp.core.auth.ToolCapabilityCatalog;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;
import io.github.hakjuoh.protege_mcp.contracts.Legacy072ToolContracts;
import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ImmutableJson;
import io.github.hakjuoh.protege_mcp.contracts.ToolError;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Collects the {@link SyncToolSpecification}s contributed by the {@link ToolProvider}s during catalog
 * assembly. It replaces the per-provider {@code new ArrayList<>()} / {@code add(...)} / {@code return}
 * boilerplate with a single fluent sink: each {@link #tool} call is delegated to {@link ToolSpecs#of},
 * so the (validated) factory remains the single point where a specification is constructed.
 *
 * <p>A registry is single-use per {@link ToolCatalog#buildAll}: the providers register into one shared
 * instance in declaration order and {@link #build()} yields the accumulated list.
 */
public final class ToolRegistry {

    private final List<SyncToolSpecification> specs = new ArrayList<>();
    private final Set<String> registeredNames = new LinkedHashSet<>();
    private final Map<String, RegisteredDefinition> registeredDefinitions = new LinkedHashMap<>();
    private final WorkspaceAudit audit;
    private final PrincipalExecutionGate executions;

    public ToolRegistry() {
        this(null, null);
    }

    ToolRegistry(WorkspaceAudit audit) {
        this(audit, null);
    }

    ToolRegistry(WorkspaceAudit audit, PrincipalExecutionGate executions) {
        this.audit = audit;
        this.executions = executions;
    }

    /**
     * Register a handler by name, resolving its description and input schema from the shared JSON
     * catalog.
     */
    public ToolRegistry tool(String name,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        McpCatalog.ToolDefinition definition = McpCatalog.get().tool(name);
        Set<String> required = ToolCapabilityCatalog.required(name);
        return tool(definition.name(), definition.description(), definition.inputSchema(),
                definition.outputSchema(), definition.errorSchema(), required, handler);
    }

    /**
     * Register explicitly supplied metadata. Retained for focused factory tests and extensions that
     * are not part of the built-in catalog. Every registered handler crosses the same guarded
     * execution boundary so providers only implement their processing logic.
     */
    public ToolRegistry tool(String name, String description, Map<String, Object> inputSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        return tool(name, description, inputSchema,
                Set.of(Capability.SERVER_ADMIN.value()), handler);
    }

    /** Register extension metadata with an explicit, non-empty capability requirement. */
    public ToolRegistry tool(String name, String description, Map<String, Object> inputSchema,
            Set<String> requiredCapabilities,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        if (handler != null && !Legacy072ToolContracts.liveToolNames().contains(name)) {
            throw new IllegalArgumentException("Post-0.7.2 extension tool '" + name
                    + "' must declare an explicit typed output schema.");
        }
        return tool(name, description, inputSchema,
                ToolContractSchemas.legacySuccessSchema(), ToolContractSchemas.errorSchema(),
                requiredCapabilities, handler);
    }

    /** Register an extension with explicit typed success and shared error contracts. */
    public ToolRegistry tool(String name, String description, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, Map<String, Object> errorSchema,
            Set<String> requiredCapabilities,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        if (handler == null) {
            // The guard wrapper would otherwise hide a null handler from the SDK builder's
            // validation, deferring the failure to the first call instead of registration.
            throw new IllegalArgumentException("Tool '" + name + "' registered without a handler.");
        }
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) {
            throw new IllegalArgumentException("Tool '" + name
                    + "' registered without required capabilities.");
        }
        if (!ToolContractSchemas.errorSchema().equals(errorSchema)) {
            throw new IllegalArgumentException("Tool '" + name
                    + "' registered without the shared error schema.");
        }
        for (String capability : requiredCapabilities) {
            Capability.fromValue(capability);
        }
        if (!registeredNames.add(name)) {
            throw new IllegalArgumentException("Tool '" + name + "' registered more than once.");
        }
        Map<String, Object> safeInput = ImmutableJson.map(inputSchema);
        Map<String, Object> safeOutput = ImmutableJson.map(outputSchema);
        Map<String, Object> safeError = ImmutableJson.map(errorSchema);
        ToolSchemaValidator.validateInput(safeInput, "tool '" + name + "' input");
        ToolSchemaValidator.validateOutput(safeOutput, "tool '" + name + "' output");
        ToolSchemaValidator.validateOutput(safeError, "tool '" + name + "' error");
        if (!Legacy072ToolContracts.liveToolNames().contains(name)) {
            ToolSchemaValidator.validateTypedOutput(safeOutput,
                    "post-0.7.2 extension tool '" + name + "' output");
        }
        Set<String> required = Set.copyOf(requiredCapabilities);
        registeredDefinitions.put(name, new RegisteredDefinition(description, safeInput,
                safeOutput, safeError, required));
        ToolSchemaValidator.Compiled successContract = ToolSchemaValidator.compile(safeOutput);
        ToolSchemaValidator.Compiled failureContract = ToolSchemaValidator.compile(safeError);
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> guarded =
                (exchange, request) -> Tools.guard(() -> {
                    AuthenticatedPrincipal principal = principal(exchange);
                    Map<String, Object> arguments = request == null || request.arguments() == null
                            ? Map.of() : request.arguments();
                    PrincipalExecutionGate.Lease lease = null;
                    try {
                        requireAuthorized(principal, name, required);
                        if (executions != null) {
                            lease = executions.acquire(principal);
                        }
                    } catch (RuntimeException denied) {
                        if (audit != null) {
                            try {
                                audit.denied(name, principal, arguments, required,
                                        mutationExpected(required));
                            } catch (RuntimeException auditFailure) {
                                ToolArgException refusal = new ToolArgException(
                                        "audit_failed_while_denied",
                                        denied.getMessage() + " Audit attribution also failed; "
                                                + "the request remained denied.",
                                        Map.of("request_denied", true,
                                                "effects_prevented", true), false);
                                refusal.addSuppressed(auditFailure);
                                throw refusal;
                            }
                        }
                        throw denied;
                    }
                    try (PrincipalExecutionGate.Lease executionLease = lease) {
                        final WorkspaceAudit.Ticket ticket;
                        try {
                            ticket = audit == null ? null
                                    : audit.begin(name, principal, arguments,
                                            mutationExpected(required));
                        } catch (RuntimeException auditFailure) {
                            ToolArgException attribution = new ToolArgException(
                                    "audit_failed_before_execution",
                                    "Audit attribution failed before '" + name
                                            + "' started; the tool body was not executed.",
                                    Map.of("effects_prevented", true), true);
                            attribution.addSuppressed(auditFailure);
                            throw attribution;
                        }
                        final CallToolResult result;
                        try {
                            result = ToolResults.immutableSnapshot(handler.apply(exchange, request));
                            validateResultContract(name, result, mutationExpected(required),
                                    successContract, failureContract);
                        } catch (McpAccessException accessFailure) {
                            boolean mutation = mutationExpected(required);
                            boolean prevented = accessFailure.effectsPrevented();
                            ToolArgException typed = new ToolArgException(
                                    prevented ? "model_access_prevented"
                                            : mutation ? "mutation_outcome_unknown"
                                            : "model_access_outcome_unknown",
                                    accessFailure.getMessage() == null ? "Model access failed."
                                            : accessFailure.getMessage(),
                                    Map.of("effects_prevented", prevented,
                                            "outcome_unknown", !prevented,
                                            "mutation_outcome_unknown", mutation && !prevented),
                                    prevented);
                            throw auditedFailure(ticket, typed, name, mutation);
                        } catch (RuntimeException failure) {
                            throw auditedFailure(ticket, failure, name, mutationExpected(required));
                        }
                        if (audit != null) {
                            try {
                                audit.complete(ticket, result);
                            } catch (RuntimeException auditFailure) {
                                boolean failed = Boolean.TRUE.equals(result.isError());
                                boolean mutation = mutationExpected(required);
                                ToolArgException attribution = new ToolArgException(
                                        failed ? "audit_failed_while_recording_failure"
                                                : "audit_failed_after_completion",
                                        failed
                                        ? "Audit attribution failed while recording the failure "
                                        + "of '" + name + "'; the tool's own error still stands: "
                                        + errorText(result)
                                        : "Audit attribution failed after '" + name + "' completed; "
                                        + "its outcome — including any committed changes — still "
                                        + "stands and was NOT rolled back. Do not retry before "
                                        + "checking the current state. " + rootMessage(auditFailure),
                                        Map.of("tool_completed", !failed,
                                                "outcome_unknown", mutation,
                                                "retry_requires_state_check", mutation), false);
                                attribution.addSuppressed(auditFailure);
                                throw attribution;
                            }
                        }
                        return result;
                    }
                });
        specs.add(ToolSpecs.of(name, description, safeInput, safeOutput, safeError, guarded));
        return this;
    }

    private RuntimeException auditedFailure(WorkspaceAudit.Ticket ticket,
            RuntimeException failure, String name, boolean mutation) {
        RuntimeException evidenced = withOutcomeEvidence(failure, name, mutation);
        if (audit == null) return evidenced;
        try {
            audit.failed(ticket, evidenced);
            return evidenced;
        } catch (RuntimeException auditFailure) {
            String originalCode = evidenced instanceof ToolArgException typed
                    ? typed.code() : failure.getClass().getSimpleName();
            ToolArgException attribution = new ToolArgException(
                    "audit_failed_while_recording_failure",
                    "Audit attribution failed while recording the failure of '" + name
                            + "'; the tool's own error still stands.",
                    Map.of("tool_error_code", originalCode,
                            "outcome_unknown", mutation,
                            "retry_requires_state_check", mutation), false);
            attribution.addSuppressed(auditFailure);
            return attribution;
        }
    }

    private static RuntimeException withOutcomeEvidence(RuntimeException failure,
            String name, boolean mutation) {
        if (!mutation) return failure;
        if (failure instanceof ToolArgException typed
                && Boolean.TRUE.equals(typed.details().get("effects_prevented"))) {
            return failure;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        String code = "mutation_outcome_unknown";
        String message = "Tool '" + name + "' failed after execution began; its mutation outcome "
                + "is unknown. Check current state before retrying.";
        List<String> canaries = List.of();
        if (failure instanceof ToolArgException typed) {
            details.putAll(typed.details());
            code = typed.code();
            message = typed.getMessage();
            canaries = typed.secretCanaries();
        }
        details.put("outcome_unknown", true);
        details.put("mutation_outcome_unknown", true);
        details.put("retry_requires_state_check", true);
        details.remove("effects_prevented");
        ToolArgException evidenced = new ToolArgException(
                code, message, details, false, canaries);
        evidenced.initCause(failure);
        return evidenced;
    }

    private static void validateResultContract(String name, CallToolResult result,
            boolean mutationExpected, ToolSchemaValidator.Compiled successContract,
            ToolSchemaValidator.Compiled failureContract) {
        if (result == null) {
            throw new ToolArgException("result_contract_violation",
                    "Tool '" + name + "' returned no MCP result.",
                    Map.of("outcome_unknown", mutationExpected), false);
        }
        boolean failed = Boolean.TRUE.equals(result.isError());
        List<String> violations = (failed ? failureContract : successContract)
                .violations(result.structuredContent());
        if (!violations.isEmpty()) {
            throw new ToolArgException("result_contract_violation",
                    "Tool '" + name + "' returned a result that violates its advertised "
                            + (failed ? "error" : "output") + " schema.",
                    Map.of("violations", violations, "outcome_unknown", mutationExpected), false);
        }
        if (failed && result.structuredContent() instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> details = raw.get("details") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            Map<String, Object> canonical = ToolError.of(String.valueOf(raw.get("code")),
                    String.valueOf(raw.get("message")), details,
                    Boolean.TRUE.equals(raw.get("retryable"))).toJson();
            if (!canonical.equals(raw)) {
                throw new ToolArgException("result_contract_violation",
                        "Tool '" + name + "' returned a non-canonical or unsanitized error result.",
                        Map.of("outcome_unknown", mutationExpected), false);
            }
        }
    }

    /** The specifications collected so far, in registration order (the registry's own mutable list). */
    public List<SyncToolSpecification> build() {
        return specs;
    }

    List<SyncToolSpecification> buildComplete() {
        Set<String> catalog = McpCatalog.get().toolNames();
        if (!ToolCapabilityCatalog.names().equals(catalog)) {
            throw new IllegalStateException("Built-in capability declarations do not match the MCP "
                    + "catalog; missing=" + difference(catalog, ToolCapabilityCatalog.names())
                    + ", extra=" + difference(ToolCapabilityCatalog.names(), catalog));
        }
        if (!registeredNames.equals(catalog)) {
            throw new IllegalStateException("Registered tools do not match the MCP catalog; missing="
                    + difference(catalog, registeredNames) + ", extra="
                    + difference(registeredNames, catalog));
        }
        for (String name : catalog) {
            McpCatalog.ToolDefinition expected = McpCatalog.get().tool(name);
            RegisteredDefinition actual = registeredDefinitions.get(name);
            RegisteredDefinition catalogDefinition = new RegisteredDefinition(
                    expected.description(), expected.inputSchema(), expected.outputSchema(),
                    expected.errorSchema(), ToolCapabilityCatalog.required(name));
            if (!catalogDefinition.equals(actual)) {
                throw new IllegalStateException("Registered contract for '" + name
                        + "' does not match the MCP catalog/capability declaration.");
            }
        }
        return specs;
    }

    public static Set<String> requiredCapabilities(String toolName) {
        return ToolCapabilityCatalog.required(toolName);
    }

    private static String errorText(CallToolResult result) {
        if (result.structuredContent() instanceof Map<?, ?> body
                && body.get("error") instanceof String message) {
            return message;
        }
        return String.valueOf(result.structuredContent());
    }

    /**
     * The deepest causal message: AuditFileMutex labels every wrapped I/O failure as a
     * lock-acquisition problem, but the root cause (e.g. a corrupted stream) is the actionable one.
     */
    private static String rootMessage(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null) deepest = deepest.getCause();
        String message = deepest.getMessage();
        return message == null || message.isBlank() ? String.valueOf(failure.getMessage()) : message;
    }

    private static void requireAuthorized(AuthenticatedPrincipal principal, String tool,
            Set<String> required) {
        List<String> missing = CapabilityAuthorizer.missing(
                principal == null ? null : principal.capabilities(), required);
        if (!missing.isEmpty()) {
            throw new ToolArgException("authorization_denied", "Authorization denied for " + tool
                    + "; missing capabilities: " + String.join(", ", missing) + ".",
                    Map.of("missing_capabilities", missing), false);
        }
    }

    private static AuthenticatedPrincipal principal(McpSyncServerExchange exchange) {
        if (exchange == null) return null;
        Object value = exchange.transportContext() == null ? null
                : exchange.transportContext().get(AuthenticatedPrincipal.CONTEXT_KEY);
        return value instanceof AuthenticatedPrincipal principal ? principal : null;
    }

    private static boolean mutationExpected(Set<String> required) {
        return required.contains(Capability.ONTOLOGY_CURATE.value())
                || required.contains(Capability.ONTOLOGY_ADMIN.value())
                || required.contains(Capability.FILESYSTEM_PROJECT_WRITE.value());
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> difference = new LinkedHashSet<>(left);
        difference.removeAll(right);
        return difference;
    }

    private record RegisteredDefinition(String description, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, Map<String, Object> errorSchema,
            Set<String> requiredCapabilities) { }
}
