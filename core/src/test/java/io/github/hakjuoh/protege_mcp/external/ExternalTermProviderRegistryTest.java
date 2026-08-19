package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ExternalTermProviderRegistryTest {

    @Test
    void defaultRegistryIncludesBuiltInProfiles() throws Exception {
        ExternalTermProviderRegistry registry = ExternalTermProviderRegistry.defaultRegistry();
        Set<String> supported = registry.supportedProfiles();

        assertEquals(Set.of("ols4", "ontoportal"), supported);

        assertNotNull(registry.require("ols4"));
        assertNotNull(registry.require("ontoportal"));
        assertEquals("provider_profile_unsupported",
                assertThrows(ProviderFailure.class, () -> registry.require("bioportal")).code());
        assertEquals("provider_profile_unsupported",
                assertThrows(ProviderFailure.class, () -> registry.require("agroportal")).code());
        assertEquals("provider_profile_unsupported",
                assertThrows(ProviderFailure.class, () -> registry.require("ontoserver")).code());
    }

    @Test
    void requireRejectsUnknownOrBlankProfilesFailClosed() {
        ExternalTermProviderRegistry registry = ExternalTermProviderRegistry.defaultRegistry();

        ProviderFailure unknown = assertThrows(ProviderFailure.class,
                () -> registry.require("unknown_profile"));
        assertEquals("provider_profile_unsupported", unknown.code());
        assertFalse(unknown.retryable());

        ProviderFailure nullProfile = assertThrows(ProviderFailure.class,
                () -> registry.require(null));
        assertEquals("provider_profile_unsupported", nullProfile.code());

        ProviderFailure blankProfile = assertThrows(ProviderFailure.class,
                () -> registry.require("   "));
        assertEquals("provider_profile_unsupported", blankProfile.code());
    }

    @Test
    void customRegistryValidatesProviderMappings() throws Exception {
        FakeTermProvider fake = new FakeTermProvider(List.of());
        ExternalTermProviderRegistry registry = ExternalTermProviderRegistry.of(fake);

        assertTrue(registry.supports("fake"));
        assertFalse(registry.supports("ols4"));
        assertEquals(fake, registry.require("fake"));

        assertThrows(IllegalArgumentException.class,
                () -> new ExternalTermProviderRegistry(Map.of("mismatched", fake)));
    }
}
