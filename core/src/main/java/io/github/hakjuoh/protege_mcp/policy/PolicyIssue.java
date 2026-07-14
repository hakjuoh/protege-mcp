package io.github.hakjuoh.protege_mcp.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/** One actionable project-policy validation issue. */
public record PolicyIssue(String severity, String code, String path, String message) {

    public PolicyIssue {
        if (!"error".equals(severity) && !"warning".equals(severity)) {
            throw new IllegalArgumentException("severity must be error or warning");
        }
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("policy issue code/message must not be blank");
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("severity", severity);
        json.put("code", code);
        if (path != null) {
            json.put("path", path);
        }
        json.put("message", message);
        return json;
    }
}
