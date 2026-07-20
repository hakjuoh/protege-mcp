package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import io.github.hakjuoh.protege_mcp.catalog.McpCatalog;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.auth.CapabilityAuthorizer;
import io.github.hakjuoh.protege_mcp.core.auth.ToolCapabilityCatalog;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
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
                required, handler);
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
        if (handler == null) {
            // The guard wrapper would otherwise hide a null handler from the SDK builder's
            // validation, deferring the failure to the first call instead of registration.
            throw new IllegalArgumentException("Tool '" + name + "' registered without a handler.");
        }
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) {
            throw new IllegalArgumentException("Tool '" + name
                    + "' registered without required capabilities.");
        }
        for (String capability : requiredCapabilities) {
            Capability.fromValue(capability);
        }
        if (!registeredNames.add(name)) {
            throw new IllegalArgumentException("Tool '" + name + "' registered more than once.");
        }
        Set<String> required = Set.copyOf(requiredCapabilities);
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
                                ToolArgException refusal = new ToolArgException(denied.getMessage()
                                        + " Audit attribution also failed; the request remained denied.");
                                refusal.addSuppressed(auditFailure);
                                throw refusal;
                            }
                        }
                        throw denied;
                    }
                    try (PrincipalExecutionGate.Lease executionLease = lease) {
                        WorkspaceAudit.Ticket ticket = audit == null ? null
                                : audit.begin(name, principal, arguments, mutationExpected(required));
                        final CallToolResult result;
                        try {
                            result = handler.apply(exchange, request);
                        } catch (RuntimeException failure) {
                            if (audit != null) {
                                try {
                                    audit.failed(ticket, failure);
                                } catch (RuntimeException auditFailure) {
                                    // The handler's own error is the actionable one; keep it.
                                    failure.addSuppressed(auditFailure);
                                }
                            }
                            throw failure;
                        }
                        if (audit != null) {
                            try {
                                audit.complete(ticket, result);
                            } catch (RuntimeException auditFailure) {
                                // An untyped exception so Tools.guard leaves a server-side stack
                                // trace (with the suppressed audit failure) before answering.
                                IllegalStateException attribution = new IllegalStateException(
                                        Boolean.TRUE.equals(result.isError())
                                        ? "Audit attribution failed while recording the failure "
                                        + "of '" + name + "'; the tool's own error still stands: "
                                        + errorText(result)
                                        : "Audit attribution failed after '" + name + "' completed; "
                                        + "its outcome — including any committed changes — still "
                                        + "stands and was NOT rolled back. Do not retry before "
                                        + "checking the current state. " + rootMessage(auditFailure));
                                attribution.addSuppressed(auditFailure);
                                throw attribution;
                            }
                        }
                        return result;
                    }
                });
        specs.add(ToolSpecs.of(name, description, inputSchema, guarded));
        return this;
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
            throw new ToolArgException("Authorization denied for " + tool
                    + "; missing capabilities: " + String.join(", ", missing) + ".");
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
}
