package io.github.hakjuoh.protege_mcp.external;

/** Exact process-local owner for provider cursors and reuse proposals. */
public record ProviderSessionScope(String principalType, String clientId, String grantId,
        String workspaceId) {

    public ProviderSessionScope {
        principalType = required(principalType, "principal type");
        clientId = required(clientId, "client id");
        grantId = grantId == null || grantId.isBlank() ? "" : bounded(grantId, "grant id");
        workspaceId = required(workspaceId, "workspace id");
    }

    String principalKey() {
        // Workspace is excluded so one principal shares its quota across all workspaces.
        // Length framing remains injective even when ids contain separators or digits.
        return principalType.length() + ":" + principalType
                + clientId.length() + ":" + clientId + grantId.length() + ":" + grantId;
    }

    @Override
    public String toString() {
        return "ProviderSessionScope[redacted=true]";
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return bounded(value, field);
    }

    private static String bounded(String value, String field) {
        if (value.length() > 256 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
