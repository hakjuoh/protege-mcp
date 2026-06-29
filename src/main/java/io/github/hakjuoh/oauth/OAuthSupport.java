package io.github.hakjuoh.oauth;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Small shared helpers for the OAuth servlets: base-URL derivation, JSON output, HTML escaping. */
final class OAuthSupport {

    private OAuthSupport() {
    }

    /** The issuer/base origin the client actually used (e.g. {@code http://127.0.0.1:8123}). */
    static String baseUrl(HttpServletRequest req) {
        String host = req.getHeader("Host");
        if (host == null || host.isEmpty()) {
            host = req.getServerName() + ":" + req.getServerPort();
        }
        return req.getScheme() + "://" + host;
    }

    static void writeJson(HttpServletResponse resp, int status, Object body, ObjectMapper mapper)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(mapper.writeValueAsString(body));
    }

    static void writeError(HttpServletResponse resp, int status, String error, String description,
            ObjectMapper mapper) throws IOException {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", error);
        if (description != null) {
            body.put("error_description", description);
        }
        writeJson(resp, status, body, mapper);
    }

    static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
