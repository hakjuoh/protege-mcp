package io.github.hakjuoh.protege_mcp.core.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.core.auth.ToolCapabilityCatalog;

class HeadlessToolCatalogTest {

    @Test
    void supportedAndUnavailableNamesPartitionTheLiveCatalogExactly() {
        assertEquals(8, HeadlessToolCatalog.definitions().size());
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
