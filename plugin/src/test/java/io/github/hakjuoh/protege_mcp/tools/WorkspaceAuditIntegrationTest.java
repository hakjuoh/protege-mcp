package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class WorkspaceAuditIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void registryAttributesSuccessAndDenialWithoutRetainingPayload(@TempDir Path temp)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        try {
            Path document = Files.writeString(temp.resolve("root.ttl"), "");
            var manager = OWLManager.createOWLOntologyManager();
            var ontology = manager.createOntology(IRI.create("https://example.org/audit"));
            manager.setOntologyDocumentIRI(ontology, IRI.create(document.toUri()));
            AtomicInteger dispatches = new AtomicInteger();
            ToolContext context = new ToolContext(
                    HeadlessAccess.overHookedDispatches(FakeModelManager.over(ontology), () -> { },
                            1, dispatches), null);
            ToolRegistry registry = new ToolRegistry(context.audit());
            registry.tool("list_ontologies", (exchange, request) -> Tools.ok(Map.of(
                    "valid", true, "ontology_content", "must-not-be-logged")));
            var handler = registry.build().get(0).callHandler();

            var success = handler.apply(exchange(AuthenticatedPrincipal.oauth(
                    "reader-1", "Reader One", "grant-1", "read")), null);
            assertFalse(Boolean.TRUE.equals(success.isError()));
            var denied = handler.apply(null, null);
            assertEquals(Boolean.TRUE, denied.isError());
            assertEquals(1, dispatches.get(),
                    "stable revision/policy attribution must use the cached coordinates");

            List<Path> streams;
            try (var paths = Files.walk(temp.resolve(".protege-mcp/audit"))) {
                streams = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .toList();
            }
            assertEquals(1, streams.size());
            List<Map<String, Object>> events = Files.readAllLines(streams.get(0)).stream()
                    .map(WorkspaceAuditIntegrationTest::parse).toList();
            assertEquals(List.of("started", "succeeded", "denied"), events.stream()
                    .map(event -> ((Map<?, ?>) event.get("result")).get("outcome")).toList());
            assertEquals("reader-1", ((Map<?, ?>) events.get(1).get("actor")).get("client_id"));
            assertTrue(events.get(1).toString().contains("ontology:read"));
            assertEquals(null, ((Map<?, ?>) events.get(1).get("result")).get("committed"));
            assertFalse(events.toString().contains("must-not-be-logged"));
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void auditFailureAfterCompletionReportsThatTheOutcomeStillStands(@TempDir Path temp)
            throws Exception {
        assumeSymlinksSupported(temp);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        try {
            ToolRegistry registry = new ToolRegistry(context(temp).audit());
            registry.tool("list_ontologies", (exchange, request) -> {
                breakAuditStream(temp);
                return Tools.ok(Map.of("valid", true));
            });

            var result = registry.build().get(0).callHandler().apply(
                    exchange(AuthenticatedPrincipal.oauth(
                            "reader-1", "Reader One", "grant-1", "read")), null);

            assertEquals(Boolean.TRUE, result.isError());
            String error = String.valueOf(result.structuredContent());
            assertTrue(error.contains("Audit attribution failed after 'list_ontologies' completed"),
                    error);
            assertTrue(error.contains("NOT rolled back"), error);
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void auditFailureCannotReplaceTheHandlersOwnError(@TempDir Path temp) throws Exception {
        assumeSymlinksSupported(temp);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        try {
            ToolRegistry registry = new ToolRegistry(context(temp).audit());
            registry.tool("list_ontologies", (exchange, request) -> {
                breakAuditStream(temp);
                throw new ToolArgException("the-handlers-own-error");
            });

            var result = registry.build().get(0).callHandler().apply(
                    exchange(AuthenticatedPrincipal.oauth(
                            "reader-1", "Reader One", "grant-1", "read")), null);

            assertEquals(Boolean.TRUE, result.isError());
            String error = String.valueOf(result.structuredContent());
            assertTrue(error.contains("the-handlers-own-error"), error);
            assertFalse(error.contains("audit project lock"),
                    "the audit failure must stay suppressed behind the handler error: " + error);
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void auditFailureOnAnErrorResultDoesNotClaimTheToolCompleted(@TempDir Path temp)
            throws Exception {
        assumeSymlinksSupported(temp);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.toString());
        try {
            ToolRegistry registry = new ToolRegistry(context(temp).audit());
            registry.tool("list_ontologies", (exchange, request) -> {
                breakAuditStream(temp);
                return Tools.error("the-handlers-own-error-result");
            });

            var result = registry.build().get(0).callHandler().apply(
                    exchange(AuthenticatedPrincipal.oauth(
                            "reader-1", "Reader One", "grant-1", "read")), null);

            assertEquals(Boolean.TRUE, result.isError());
            String error = String.valueOf(result.structuredContent());
            assertTrue(error.contains("the tool's own error still stands"), error);
            assertTrue(error.contains("the-handlers-own-error-result"), error);
            assertFalse(error.contains("NOT rolled back"),
                    "a failed tool result must never be reported as a completed outcome: " + error);
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    private static ToolContext context(Path temp) throws Exception {
        Path document = Files.writeString(temp.resolve("root.ttl"), "");
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("https://example.org/audit"));
        manager.setOntologyDocumentIRI(ontology, IRI.create(document.toUri()));
        return new ToolContext(HeadlessAccess.overHookedDispatches(
                FakeModelManager.over(ontology), () -> { }, 1, new AtomicInteger()), null);
    }

    /** Aborts (skips) the test when the filesystem cannot create symbolic links. */
    private static void assumeSymlinksSupported(Path temp) {
        Path target = temp.resolve("symlink-probe-target");
        Path link = temp.resolve("symlink-probe-link");
        try {
            Files.writeString(target, "probe");
            Files.createSymbolicLink(link, target);
            Files.delete(link);
            Files.delete(target);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable");
        }
    }

    /** Replaces the just-created audit stream with a symlink so the next append fails. */
    private static void breakAuditStream(Path temp) {
        try {
            List<Path> streams;
            try (var paths = Files.walk(temp.resolve(".protege-mcp/audit"))) {
                streams = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .toList();
            }
            assertEquals(1, streams.size());
            Path outside = temp.resolve("outside.jsonl");
            Files.writeString(outside, "outside");
            Files.delete(streams.get(0));
            Files.createSymbolicLink(streams.get(0), outside);
        } catch (java.io.IOException unavailable) {
            throw new java.io.UncheckedIOException(unavailable);
        }
    }

    private static McpSyncServerExchange exchange(AuthenticatedPrincipal principal) {
        McpTransportContext context = McpTransportContext.create(Map.of(
                AuthenticatedPrincipal.CONTEXT_KEY, principal));
        return new McpSyncServerExchange(new McpAsyncServerExchange(
                "audit-test", null, null, null, context));
    }

    private static Map<String, Object> parse(String line) {
        try {
            return JSON.readValue(line, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
