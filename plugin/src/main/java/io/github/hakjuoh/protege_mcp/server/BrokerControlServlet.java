package io.github.hakjuoh.protege_mcp.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.tools.PrincipalExecutionGate;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Broker-secret control endpoint that installs a client execution fence in one window backend. */
public final class BrokerControlServlet extends HttpServlet {

    public static final String PATH = "/broker-control";
    public static final String BROKER_SECRET_HEADER = "X-Protege-Mcp-Broker";

    private final Supplier<String> brokerSecret;
    private final PrincipalExecutionGate executions;
    private final ObjectMapper mapper = new ObjectMapper();

    public BrokerControlServlet(Supplier<String> brokerSecret, PrincipalExecutionGate executions) {
        this.brokerSecret = brokerSecret;
        this.executions = executions;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!authorized(req)) {
            write(resp, 403, mapper.createObjectNode().put("error", "forbidden"));
            return;
        }
        if (!"POST".equals(req.getMethod()) || !"/revoke-client".equals(req.getPathInfo())) {
            write(resp, 404, mapper.createObjectNode().put("error", "not_found"));
            return;
        }
        JsonNode body = mapper.readTree(req.getInputStream());
        String clientId = body.path("client_id").asText("");
        if (clientId.isBlank() || clientId.length() > 512) {
            write(resp, 400, mapper.createObjectNode().put("error", "invalid_client_id"));
            return;
        }
        PrincipalExecutionGate.Revocation revoked = executions.revokeClient(clientId);
        var result = mapper.createObjectNode();
        result.put("revoked", true);
        result.put("observed_active", revoked.observedActive());
        result.put("wait_ms", revoked.waitMillis());
        result.put("commit_fence_confirmed", true);
        write(resp, 200, result);
    }

    private boolean authorized(HttpServletRequest req) {
        String expected = brokerSecret.get();
        String presented = req.getHeader(BROKER_SECRET_HEADER);
        return expected != null && presented != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private void write(HttpServletResponse resp, int status,
            com.fasterxml.jackson.databind.node.ObjectNode body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(body.toString());
    }
}
