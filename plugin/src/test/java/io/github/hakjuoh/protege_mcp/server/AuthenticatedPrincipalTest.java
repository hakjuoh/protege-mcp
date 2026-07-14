package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
