package io.github.hakjuoh.protege_mcp.sssom;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable bounded validation finding for one mapping store or row. */
public record SssomFinding(String severity, String code, String mappingId, String column,
        String message) {

    public SssomFinding {
        if (!"error".equals(severity) && !"warning".equals(severity)) {
            throw new IllegalArgumentException("SSSOM finding severity must be error or warning");
        }
        if (code == null || !code.matches("^[a-z][a-z0-9_]{0,63}$")
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException("SSSOM finding code and message are required");
        }
        if (message.length() > 1024) message = message.substring(0, 1024);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", severity);
        result.put("code", code);
        result.put("mapping_id", mappingId);
        result.put("column", column);
        result.put("message", message);
        return result;
    }
}
