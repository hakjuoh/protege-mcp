package io.github.hakjuoh.protege_mcp.core.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ToolCapabilityCatalogTest {

    @Test
    void completeCatalogUsesOnlyPublicCapabilitiesAndOneSharedImplicationRule() {
        assertEquals(104, ToolCapabilityCatalog.names().size());
        for (String name : ToolCapabilityCatalog.names()) {
            Set<String> required = ToolCapabilityCatalog.required(name);
            assertFalse(required.isEmpty(), name);
            assertTrue(Capability.valuesSet().containsAll(required), name + ": " + required);
        }
        assertTrue(CapabilityAuthorizer.allows(Set.of(CapabilityAuthorizer.LOCAL_ADMIN),
                Capability.NETWORK_ACCESS.value()));
        assertFalse(CapabilityAuthorizer.allows(Set.of(Capability.ONTOLOGY_READ.value()),
                Capability.ONTOLOGY_ADMIN.value()));
        Set<String> providerRead = Set.of(Capability.ONTOLOGY_READ.value(),
                Capability.FILESYSTEM_PROJECT_READ.value(), Capability.NETWORK_ACCESS.value());
        assertEquals(providerRead, ToolCapabilityCatalog.required("search_external_terms"));
        assertEquals(providerRead, ToolCapabilityCatalog.required("inspect_external_term"));
        assertEquals(providerRead, ToolCapabilityCatalog.required("propose_term_reuse"));
        Set<String> materializationCommit = Set.of(
                Capability.ONTOLOGY_ADMIN.value(), Capability.ONTOLOGY_CURATE.value(),
                Capability.FILESYSTEM_PROJECT_READ.value());
        assertEquals(materializationCommit,
                ToolCapabilityCatalog.required("commit_materialization"));
        assertFalse(CapabilityAuthorizer.missing(Set.of(
                        Capability.ONTOLOGY_ADMIN.value()),
                ToolCapabilityCatalog.required("commit_materialization")).isEmpty());
        Set<String> jobRead = Set.of(Capability.ONTOLOGY_READ.value());
        Set<String> jobStart = Set.of(Capability.ONTOLOGY_READ.value(),
                Capability.FILESYSTEM_PROJECT_READ.value());
        Set<String> jobExport = Set.of(Capability.ONTOLOGY_READ.value(),
                Capability.FILESYSTEM_PROJECT_READ.value(),
                Capability.FILESYSTEM_PROJECT_WRITE.value());
        assertEquals(jobStart, ToolCapabilityCatalog.required("start_job"));
        assertEquals(jobRead, ToolCapabilityCatalog.required("get_job"));
        assertEquals(jobRead, ToolCapabilityCatalog.required("cancel_job"));
        assertEquals(jobRead, ToolCapabilityCatalog.required("list_jobs"));
        assertEquals(jobExport, ToolCapabilityCatalog.required("export_job_artifact"));
    }
}
