package io.github.hakjuoh.protege_mcp.server;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Versioned, secret-free authenticated identity propagated from broker to one backend. */
public record AuthenticatedPrincipal(int version, String type, String clientId, String displayName,
        Set<String> capabilities, String grantId) {

    public static final String REQUEST_ATTRIBUTE = AuthenticatedPrincipal.class.getName();
    public static final String CONTEXT_KEY = "protege_mcp.principal";
    public static final String BROKER_HEADER = "X-Protege-Mcp-Principal";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LOCAL_ADMIN = Set.of(
            "ontology:read", "ontology:write", "filesystem:project:read",
            "filesystem:project:write", "filesystem:external", "network:access", "local:admin");

    public AuthenticatedPrincipal {
        if (version != 1 || type == null || type.isBlank() || clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("invalid authenticated principal");
        }
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
    }

    public static AuthenticatedPrincipal staticAdmin() {
        return new AuthenticatedPrincipal(1, "static", "static-local-admin", "Static local token",
                LOCAL_ADMIN, null);
    }

    public static AuthenticatedPrincipal oauthAdmin(String clientId, String name, String grantId) {
        return new AuthenticatedPrincipal(1, "oauth", clientId,
                name == null || name.isBlank() ? clientId : name, LOCAL_ADMIN, grantId);
    }

    /**
     * Identity for a request forwarded by a pre-0.6.0 broker, which authenticates the client but
     * sends no principal header. Mixed-version operation is supported (an older shared broker stays
     * alive while other instances still reference it), so these requests must not be locked out.
     */
    public static AuthenticatedPrincipal legacyBroker() {
        return new AuthenticatedPrincipal(1, "broker-legacy", "legacy-broker",
                "Pre-0.6.0 broker (no principal propagation)", LOCAL_ADMIN, null);
    }

    public String encode() {
        try {
            byte[] json = JSON.writeValueAsBytes(Map.of(
                    "version", version,
                    "type", type,
                    "client_id", clientId,
                    "display_name", displayName == null ? "" : displayName,
                    "capabilities", capabilities,
                    "grant_id", grantId == null ? "" : grantId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("Could not encode principal", impossible);
        }
    }

    public static AuthenticatedPrincipal decode(String encoded) {
        if (encoded == null || encoded.length() > 16_384) {
            throw new IllegalArgumentException("missing or oversized broker principal");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII));
            Map<String, Object> map = JSON.readValue(bytes, new TypeReference<Map<String, Object>>() { });
            Set<String> capabilities = new LinkedHashSet<>();
            Object raw = map.get("capabilities");
            if (raw instanceof Iterable<?>) {
                for (Object value : (Iterable<?>) raw) {
                    if (!(value instanceof String) || ((String) value).isBlank()) {
                        throw new IllegalArgumentException("malformed capability");
                    }
                    capabilities.add((String) value);
                }
            }
            return new AuthenticatedPrincipal(((Number) map.get("version")).intValue(),
                    string(map, "type"), string(map, "client_id"), string(map, "display_name"),
                    capabilities, blankToNull(string(map, "grant_id")));
        } catch (RuntimeException | java.io.IOException e) {
            throw new IllegalArgumentException("malformed broker principal", e);
        }
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("missing " + key);
        return (String) value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
