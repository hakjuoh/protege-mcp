package io.github.hakjuoh.protege_mcp.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ToolCapabilityCatalogTest {

    @Test
    void completeCatalogUsesOnlyPublicCapabilitiesAndOneSharedImplicationRule() {
        assertEquals(84, ToolCapabilityCatalog.names().size());
        for (String name : ToolCapabilityCatalog.names()) {
            Set<String> required = ToolCapabilityCatalog.required(name);
            assertFalse(required.isEmpty(), name);
            assertTrue(Capability.valuesSet().containsAll(required), name + ": " + required);
        }
        assertTrue(CapabilityAuthorizer.allows(Set.of(CapabilityAuthorizer.LOCAL_ADMIN),
                Capability.NETWORK_ACCESS.value()));
        assertFalse(CapabilityAuthorizer.allows(Set.of(Capability.ONTOLOGY_READ.value()),
                Capability.ONTOLOGY_ADMIN.value()));
    }
}
