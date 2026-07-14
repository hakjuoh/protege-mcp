package io.github.hakjuoh.protege_mcp.oauth;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Small shared helpers for the OAuth servlets: base-URL derivation, JSON output, HTML escaping. */
final class OAuthSupport {

    private OAuthSupport() {
    }

    /**
     * True when the request originated on this machine: loopback, or a peer address that belongs
     * to one of this machine's own interfaces (a local client that connected via the LAN URL of a
     * non-loopback bind). Literal-IP parsing only — {@code getRemoteAddr()} never returns a name,
     * so no DNS is involved; anything unparseable counts as remote (fail closed).
     */
    static boolean isLocalRequest(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        if (remote == null || remote.isEmpty()) {
            return false; // getByName(null/"") would answer loopback — fail closed instead
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(remote);
            return addr.isLoopbackAddress() || java.net.NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception unparseableOrUnknown) {
            return false;
        }
    }

    /**
     * Gate for every OAuth endpoint: 403 unless the request is local (returns whether the caller
     * may proceed). The embedded flow's Allow decision is bound to nothing but reachability — no
     * session or login ties it to the machine's user — so it is only safe while same-machine
     * processes are the only ones who can drive it. With a non-loopback bind address the MCP
     * endpoint may be remote-reachable, but authorization must not be; remote clients authenticate
     * with the static bearer token instead.
     */
    static boolean requireLocal(HttpServletRequest req, HttpServletResponse resp, ObjectMapper mapper)
            throws IOException {
        if (isLocalRequest(req)) {
            return true;
        }
        writeError(resp, HttpServletResponse.SC_FORBIDDEN, "access_denied",
                "OAuth endpoints accept same-machine connections only; remote clients "
                        + "authenticate with the static bearer token", mapper);
        return false;
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
