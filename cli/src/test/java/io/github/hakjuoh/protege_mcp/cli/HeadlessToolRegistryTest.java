package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolCatalog;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;
import io.github.hakjuoh.protege_mcp.contracts.ToolContractSchemas;
import io.github.hakjuoh.protege_mcp.contracts.ToolSchemaValidator;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessExecutionException;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class HeadlessToolRegistryTest {

    @Test
    void readProfileCanInspectSurfaceButCannotWriteOrRelease() {
        HeadlessToolService service = new HeadlessToolService(Path.of("missing.yaml"),
                new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
        Set<String> read = Set.of(Capability.ONTOLOGY_READ.value(),
                Capability.FILESYSTEM_PROJECT_READ.value());
        var specifications = HeadlessToolRegistry.build(service, read, 123, 456);
        assertEquals(HeadlessToolCatalog.definitions().size(), specifications.size());
        specifications.forEach(specification -> {
            var definition = HeadlessToolCatalog.definition(specification.tool().name());
            assertEquals(ToolContractSchemas.wireOutputSchema(
                            definition.outputSchema()),
                    specification.tool().outputSchema(), specification.tool().name());
            assertEquals(definition.outputSchema(), specification.tool().meta()
                    .get(ToolContractSchemas.SUCCESS_SCHEMA_META_KEY), specification.tool().name());
            assertEquals(ToolContractSchemas.errorSchema(), specification.tool().meta()
                    .get(ToolContractSchemas.ERROR_SCHEMA_META_KEY), specification.tool().name());
        });

        var surface = specifications.stream().filter(specification -> specification.tool().name()
                .equals(HeadlessToolCatalog.SURFACE_TOOL)).findFirst().orElseThrow()
                .callHandler().apply(null,
                        new CallToolRequest(HeadlessToolCatalog.SURFACE_TOOL, Map.of()));
        assertFalse(Boolean.TRUE.equals(surface.isError()),
                () -> String.valueOf(surface.structuredContent()));
        assertEquals(123, ((Map<?, ?>) surface.structuredContent())
                .get("max_inbound_message_bytes"));

        for (String denied : new String[] {"write_import_lock", "run_release_gate",
                "prepare_release", "export_audit_log"}) {
            var result = specifications.stream().filter(specification ->
                    specification.tool().name().equals(denied)).findFirst().orElseThrow()
                    .callHandler().apply(null, new CallToolRequest(denied, Map.of()));
            assertEquals(Boolean.TRUE, result.isError(), denied);
            assertTrue(String.valueOf(result.structuredContent()).contains("missing capabilities"));
            assertEquals("authorization_denied",
                    ((Map<?, ?>) result.structuredContent()).get("code"));
        }
    }

    @Test
    void everyDefaultCapabilityRequirementIsSatisfied() {
        for (HeadlessToolCatalog.Definition definition : HeadlessToolCatalog.definitions()) {
            assertTrue(HeadlessToolService.DEFAULT_CAPABILITIES.containsAll(
                    definition.requiredCapabilities()), definition.name());
        }
    }

    @Test
    void surfaceInspectionRemainsAvailableToANarrowReleaseOnlyProfile() {
        HeadlessToolService service = new HeadlessToolService(Path.of("missing.yaml"),
                new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
        Set<String> release = Set.of(Capability.ONTOLOGY_RELEASE.value(),
                Capability.FILESYSTEM_PROJECT_READ.value());
        var result = HeadlessToolRegistry.build(service, release, 123, 456).stream()
                .filter(specification -> specification.tool().name()
                        .equals(HeadlessToolCatalog.SURFACE_TOOL))
                .findFirst().orElseThrow().callHandler().apply(null,
                        new CallToolRequest(HeadlessToolCatalog.SURFACE_TOOL, Map.of()));
        assertFalse(Boolean.TRUE.equals(result.isError()),
                () -> String.valueOf(result.structuredContent()));
    }

    @Test
    void expectedArgumentErrorsRemainUsefulButUnexpectedDetailsAreHidden() {
        var invalid = HeadlessToolRegistry.failure(new IllegalArgumentException("bad argument"));
        assertEquals("invalid_request", ((Map<?, ?>) invalid.structuredContent()).get("code"));
        assertEquals("bad argument", ((Map<?, ?>) invalid.structuredContent()).get("message"));

        var unexpected = HeadlessToolRegistry.failure(
                new IOException("secret path /private/project.owl"));
        assertEquals("io_failed", ((Map<?, ?>) unexpected.structuredContent()).get("code"));
        assertEquals("Headless I/O operation failed.",
                ((Map<?, ?>) unexpected.structuredContent()).get("message"));
        assertFalse(String.valueOf(unexpected.structuredContent()).contains("/private"));
    }

    @Test
    void auditFailureWhileDeniedUsesTheCrossAdapterCodeAndEvidence(@TempDir Path temp)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            HeadlessToolService service = new HeadlessToolService(temp.resolve("missing.yaml"),
                    new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
            Set<String> read = Set.of(Capability.ONTOLOGY_READ.value(),
                    Capability.FILESYSTEM_PROJECT_READ.value());
            var handler = HeadlessToolRegistry.build(service, read, 123, 456).stream()
                    .filter(specification -> specification.tool().name().equals("prepare_release"))
                    .findFirst().orElseThrow().callHandler();

            var request = new CallToolRequest("prepare_release", Map.of());
            assertEquals("authorization_denied",
                    ((Map<?, ?>) handler.apply(null, request).structuredContent()).get("code"));
            Path stream;
            try (var paths = Files.walk(temp.resolve(".protege-mcp/audit"))) {
                stream = paths.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .findFirst().orElseThrow();
            }
            Path outside = Files.writeString(temp.resolve("outside.jsonl"), "outside");
            Files.delete(stream);
            try {
                Files.createSymbolicLink(stream, outside);
            } catch (UnsupportedOperationException | IOException unavailable) {
                org.junit.jupiter.api.Assumptions.abort("symbolic links unavailable");
            }

            var result = handler.apply(null, request);
            Map<?, ?> body = (Map<?, ?>) result.structuredContent();
            assertEquals("audit_failed_while_denied", body.get("code"));
            assertEquals(false, body.get("retryable"));
            assertEquals(Map.of("request_denied", true, "effects_prevented", true),
                    body.get("details"));
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void typedHeadlessResultMutantIsRejectedWithOutcomeEvidence() {
        var contract = ToolSchemaValidator.compile(Map.of(
                "type", "object", "properties",
                Map.of("count", Map.of("type", "integer")),
                "required", java.util.List.of("count"), "additionalProperties", false));
        HeadlessExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                HeadlessExecutionException.class,
                () -> HeadlessToolRegistry.validateResultContract(
                        "typed_headless", contract, Map.of("count", "wrong"), true));
        assertEquals("result_contract_violation", failure.code());
        assertEquals(true, failure.details().get("outcome_unknown"));
        assertFalse(failure.details().toString().contains("wrong"));
    }

    @Test
    void mutationFailureExposesUnknownOutcomeAndStateCheckEvidence(@TempDir Path temp) {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            HeadlessToolService service = new HeadlessToolService(temp.resolve("missing.yaml"),
                    new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
            var handler = HeadlessToolRegistry.build(service,
                    HeadlessToolService.DEFAULT_CAPABILITIES, 123, 456).stream()
                    .filter(specification -> specification.tool().name().equals("write_import_lock"))
                    .findFirst().orElseThrow().callHandler();

            var result = handler.apply(null, new CallToolRequest("write_import_lock", Map.of()));
            Map<?, ?> body = (Map<?, ?>) result.structuredContent();
            Map<?, ?> details = (Map<?, ?>) body.get("details");
            assertEquals("mutation_outcome_unknown", body.get("code"));
            assertEquals(false, body.get("retryable"));
            assertEquals(true, details.get("outcome_unknown"));
            assertEquals(true, details.get("retry_requires_state_check"));
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void headlessSuccessAndErrorResultsAreCanonicalImmutableJsonSnapshots() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessToolRegistry.ok(Map.of("value", Double.NaN)));

        var result = HeadlessToolRegistry.failure(new IllegalArgumentException("bad argument"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) result.structuredContent();
        assertThrows(UnsupportedOperationException.class, () -> body.put("late", true));
        String text = ((TextContent) result.content().get(0)).text();
        assertEquals(body, new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
    }
}
