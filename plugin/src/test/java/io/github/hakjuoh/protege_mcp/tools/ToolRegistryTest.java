package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.catalog.McpCatalog;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;

class ToolRegistryTest {

    @Test
    void resourceBackedToolResolvesMetadataByName() {
        ToolRegistry registry = new ToolRegistry();
        assertSame(registry, registry.tool("list_ontologies",
                (exchange, request) -> Tools.text("ok")));

        var specification = registry.build().get(0);
        assertEquals("list_ontologies", specification.tool().name());
        assertTrue(specification.tool().description().startsWith("List EVERY ontology"));
        assertEquals("object", specification.tool().inputSchema().get("type"));
    }

    @Test
    void nullHandlerFailsAtRegistrationNotAtTheFirstCall() {
        ToolRegistry registry = new ToolRegistry();
        for (org.junit.jupiter.api.function.Executable registration : new org.junit.jupiter.api.function.Executable[] {
                () -> registry.tool("list_ontologies", null),
                () -> registry.tool("t", "d", Map.of("type", "object"), null)}) {
            IllegalArgumentException rejected = org.junit.jupiter.api.Assertions
                    .assertThrows(IllegalArgumentException.class, registration);
            assertTrue(rejected.getMessage().contains("without a handler"), rejected.getMessage());
        }
        assertTrue(registry.build().isEmpty(), "a rejected registration must not add a spec");
    }

    @Test
    void registeredHandlerConvertsExpectedExceptionsAtTheRegistryBoundary() {
        ToolRegistry registry = new ToolRegistry();
        registry.tool("list_ontologies", (exchange, request) -> {
            throw new ToolArgException("invalid request");
        });

        var result = registry.build().get(0).callHandler().apply(
                ToolTestExchange.localAdmin(), null);

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(Map.of("error", "invalid request"), result.structuredContent());
    }

    @Test
    void nullExchangeFailsClosedBeforeHandlerExecution() {
        AtomicBoolean invoked = new AtomicBoolean();
        ToolRegistry registry = new ToolRegistry();
        registry.tool("list_ontologies", (exchange, request) -> {
            invoked.set(true);
            return Tools.text("must not run");
        });

        var result = registry.build().get(0).callHandler().apply(null, null);

        assertEquals(Boolean.TRUE, result.isError());
        assertFalse(invoked.get(), "a missing exchange must not reach the handler");
        assertTrue(String.valueOf(result.structuredContent()).contains("missing capabilities"));
    }

    @Test
    void auditFailureCannotHideTheAuthorizationRefusal() {
        ToolContext context = new ToolContext(null, null);
        ToolRegistry registry = new ToolRegistry(context.audit());
        registry.tool("list_ontologies", (exchange, request) -> Tools.text("must not run"));

        var result = registry.build().get(0).callHandler().apply(null, null);

        assertEquals(Boolean.TRUE, result.isError());
        String error = String.valueOf(result.structuredContent());
        assertTrue(error.contains("missing capabilities"), error);
        assertTrue(error.contains("Audit attribution also failed"), error);
    }

    @Test
    void everyCatalogToolHasExactlyOneNonEmptyKnownCapabilityDeclaration() {
        assertEquals(84, McpCatalog.get().toolNames().size());
        assertEquals(McpCatalog.get().toolNames(), ToolCatalog.buildAll(
                new ToolContext(null, null)).stream()
                        .map(spec -> spec.tool().name())
                        .collect(java.util.stream.Collectors.toCollection(
                                java.util.LinkedHashSet::new)));
        for (String name : McpCatalog.get().toolNames()) {
            Set<String> required = ToolRegistry.requiredCapabilities(name);
            assertFalse(required.isEmpty(), name);
            assertTrue(Capability.valuesSet().containsAll(required), name + ": " + required);
        }
    }

    @Test
    void readScopedPrincipalCanReadButCannotCurateAdminReleaseOrReadFiles() {
        AuthenticatedPrincipal reader = AuthenticatedPrincipal.oauth(
                "reader", "Reader", "grant-r", "read");
        assertAuthorized("list_ontologies", reader);
        for (String denied : new String[] {"create_class", "set_ontology_id",
                "extract_module", "get_project_policy", "run_release_gate", "prepare_release"}) {
            assertDenied(denied, reader);
        }
        assertDenied("export_audit_log", reader);
    }

    @Test
    void explicitCapabilitiesComposeAndMissingProductionPrincipalFailsClosed() {
        AuthenticatedPrincipal policyReader = AuthenticatedPrincipal.oauth(
                "policy", "Policy reader", "grant-p",
                "ontology:read filesystem:project:read");
        assertAuthorized("get_project_policy", policyReader);
        assertDenied("get_project_policy", AuthenticatedPrincipal.oauth(
                "reader", "Reader", "grant-r", "ontology:read"));
        assertDenied("list_ontologies", null);

        AuthenticatedPrincipal legacy = AuthenticatedPrincipal.oauth(
                "legacy", "Legacy", "grant-l", "mcp");
        for (String tool : new String[] {"create_class", "set_ontology_id",
                "get_project_policy", "prepare_release"}) {
            assertAuthorized(tool, legacy);
        }
    }

    @Test
    void guardedHandlerCannotFinishAfterItsClientRevocationCompletes() throws Exception {
        PrincipalExecutionGate gate = new PrincipalExecutionGate();
        ToolRegistry registry = new ToolRegistry(null, gate);
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.oauthAdmin(
                "commit-client", "Commit client", "commit-grant");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        registry.tool("list_ontologies", (exchange, request) -> {
            entered.countDown();
            try {
                if (!allowCommit.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new ToolArgException("test commit gate timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolArgException("test interrupted");
            }
            commits.incrementAndGet();
            return Tools.text("committed");
        });
        var handler = registry.build().get(0).callHandler();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var call = pool.submit(() -> handler.apply(exchange(principal), null));
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            var revoke = pool.submit(() -> gate.revokeClient(principal.clientId()));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> revoke.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));

            allowCommit.countDown();
            assertFalse(Boolean.TRUE.equals(call.get(2, java.util.concurrent.TimeUnit.SECONDS).isError()));
            revoke.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, commits.get(), "the older handler committed before revocation returned");

            var denied = handler.apply(exchange(principal), null);
            assertEquals(Boolean.TRUE, denied.isError());
            assertEquals(1, commits.get(), "no handler may commit after the revocation boundary");
            assertTrue(String.valueOf(denied.structuredContent()).contains("revoked"));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertAuthorized(String tool, AuthenticatedPrincipal principal) {
        AtomicBoolean invoked = new AtomicBoolean();
        ToolRegistry registry = new ToolRegistry();
        registry.tool(tool, (exchange, request) -> {
            invoked.set(true);
            return Tools.text("ok");
        });
        var result = registry.build().get(0).callHandler().apply(exchange(principal), null);
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> tool + ": " + result.structuredContent());
        assertTrue(invoked.get(), tool);
    }

    private static void assertDenied(String tool, AuthenticatedPrincipal principal) {
        AtomicBoolean invoked = new AtomicBoolean();
        ToolRegistry registry = new ToolRegistry();
        registry.tool(tool, (exchange, request) -> {
            invoked.set(true);
            return Tools.text("must not run");
        });
        var result = registry.build().get(0).callHandler().apply(exchange(principal), null);
        assertEquals(Boolean.TRUE, result.isError(), tool);
        assertFalse(invoked.get(), "denied handler must not execute: " + tool);
        assertTrue(String.valueOf(result.structuredContent()).contains("missing capabilities"),
                () -> tool + ": " + result.structuredContent());
    }

    private static McpSyncServerExchange exchange(AuthenticatedPrincipal principal) {
        McpTransportContext context = principal == null ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(
                        AuthenticatedPrincipal.CONTEXT_KEY, principal));
        return new McpSyncServerExchange(new McpAsyncServerExchange(
                "tool-registry-test", null, null, null, context));
    }
}
