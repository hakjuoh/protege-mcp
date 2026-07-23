package io.github.hakjuoh.protege_mcp.core.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.core.auth.ToolCapabilityCatalog;
import io.github.hakjuoh.protege_mcp.core.auth.Capability;

class HeadlessToolCatalogTest {

    @Test
    void supportedAndUnavailableNamesPartitionTheLiveCatalogExactly() {
        assertEquals(18, HeadlessToolCatalog.definitions().size());
        assertTrue(HeadlessToolCatalog.supportedNames().contains("materialize_inferences"));
        assertTrue(HeadlessToolCatalog.supportedNames().contains("commit_materialization"));
        Set<String> headlessReasonerRead = Set.of(Capability.ONTOLOGY_READ.value(),
                Capability.FILESYSTEM_PROJECT_READ.value());
        assertEquals(headlessReasonerRead, HeadlessToolCatalog.definition(
                "get_reasoner_capabilities").requiredCapabilities());
        assertEquals(headlessReasonerRead, HeadlessToolCatalog.definition(
                "validate_rules").requiredCapabilities());
        assertEquals(Set.of(Capability.ONTOLOGY_ADMIN.value(),
                        Capability.ONTOLOGY_CURATE.value(),
                        Capability.FILESYSTEM_PROJECT_READ.value(),
                        Capability.FILESYSTEM_PROJECT_WRITE.value()),
                HeadlessToolCatalog.definition("commit_materialization")
                        .requiredCapabilities());
        assertTrue(HeadlessToolCatalog.supportedNames().contains(
                HeadlessToolCatalog.SURFACE_TOOL));
        assertTrue(HeadlessToolCatalog.definition(
                HeadlessToolCatalog.SURFACE_TOOL).requiredCapabilities().isEmpty());
        HeadlessToolCatalog.definitions().stream()
                .filter(definition -> !HeadlessToolCatalog.SURFACE_TOOL.equals(definition.name()))
                .forEach(definition -> assertFalse(definition.requiredCapabilities().isEmpty(),
                        definition.name()));
        assertFalse(HeadlessToolCatalog.unavailableLiveToolNames().contains(
                "validate_project_policy"));
        assertTrue(HeadlessToolCatalog.unavailableLiveToolNames().contains("create_class"));

        Set<String> headlessLiveNames = new LinkedHashSet<>(HeadlessToolCatalog.supportedNames());
        headlessLiveNames.remove(HeadlessToolCatalog.SURFACE_TOOL);
        Set<String> partition = new LinkedHashSet<>(headlessLiveNames);
        partition.addAll(HeadlessToolCatalog.unavailableLiveToolNames());
        assertEquals(ToolCapabilityCatalog.names(), partition);
    }
}
