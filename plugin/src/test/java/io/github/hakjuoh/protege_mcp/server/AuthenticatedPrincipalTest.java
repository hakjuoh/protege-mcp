package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.core.auth.Capability;

class AuthenticatedPrincipalTest {

    @Test
    void roundTripContainsIdentityAndCapabilitiesButNoBearerSecret() {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.oauthAdmin(
                "client-1", "Codex", "grant-1");
        String encoded = principal.encode();
        AuthenticatedPrincipal decoded = AuthenticatedPrincipal.decode(encoded);
        assertEquals(principal, decoded);
        assertTrue(decoded.capabilities().contains("ontology:write"));
        assertFalse(encoded.contains("Bearer"));
    }

    @Test
    void malformedOrUnsupportedVersionFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> AuthenticatedPrincipal.decode("%%%"));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedPrincipal(
                2, "oauth", "client", "name", java.util.Set.of(), null));
    }

    @Test
    void oauthScopesProduceLeastPrivilegeCapabilities() {
        AuthenticatedPrincipal read = AuthenticatedPrincipal.oauth(
                "reader", "Reader", "grant-r", "read");
        assertEquals(java.util.Set.of("ontology:read"), read.capabilities());
        assertTrue(read.allows("ontology:read"));
        assertFalse(read.allows("ontology:curate"));

        AuthenticatedPrincipal selected = AuthenticatedPrincipal.oauth(
                "curator", "Curator", "grant-c",
                "ontology:read ontology:curate filesystem:project:read");
        assertEquals(java.util.Set.of("ontology:read", "ontology:curate",
                "filesystem:project:read"), selected.capabilities());
        assertFalse(selected.allows("filesystem:project:write"));
    }

    @Test
    void legacyMcpAndOmittedScopesRetainLocalAdminWhileUnknownScopesFailClosed() {
        for (String scope : new String[] {null, "", "mcp"}) {
            AuthenticatedPrincipal principal = AuthenticatedPrincipal.oauth(
                    "legacy", "Legacy", "grant", scope);
            for (Capability capability : Capability.values()) {
                assertTrue(principal.allows(capability.value()), capability::value);
            }
            assertTrue(principal.capabilities().contains(
                    AuthenticatedPrincipal.LOCAL_ADMIN_CAPABILITY));
        }
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticatedPrincipal.capabilitiesForScope("read unknown"));
    }
}
