package io.github.hakjuoh.protege_mcp.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.core.auth.Capability;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolCatalog;
import io.github.hakjuoh.protege_mcp.core.headless.HeadlessToolService;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

class HeadlessToolRegistryTest {

    @Test
    void readProfileCanInspectSurfaceButCannotWriteOrRelease() {
        HeadlessToolService service = new HeadlessToolService(Path.of("missing.yaml"),
                new org.semanticweb.HermiT.ReasonerFactory(), Clock.systemUTC());
        Set<String> read = Set.of(Capability.ONTOLOGY_READ.value(),
                Capability.FILESYSTEM_PROJECT_READ.value());
        var specifications = HeadlessToolRegistry.build(service, read, 123, 456);
        assertEquals(HeadlessToolCatalog.definitions().size(), specifications.size());

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
}
