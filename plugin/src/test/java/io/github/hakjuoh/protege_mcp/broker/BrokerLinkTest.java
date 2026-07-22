package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class BrokerLinkTest {

    @TempDir
    Path temporary;

    @Test
    void takeoverShutdownRaceKeepsTheStillLiveBroker() throws Exception {
        AtomicInteger infoCalls = new AtomicInteger();
        AtomicInteger shutdownCalls = new AtomicInteger();
        EmbeddedHttpServer server = new EmbeddedHttpServer();
        server.addServlet(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                    throws IOException {
                response.setContentType("application/json");
                if ("/info".equals(request.getPathInfo())) {
                    infoCalls.incrementAndGet();
                    response.setStatus(200);
                    response.getWriter().write("{\"service\":\"protege-mcp-broker\","
                            + "\"version\":\"older\",\"shutdown_eligible\":true}");
                } else if ("/shutdown".equals(request.getPathInfo())) {
                    shutdownCalls.incrementAndGet();
                    response.setStatus(409);
                    response.getWriter().write("{\"error\":\"broker_not_quiescent\"}");
                } else {
                    response.setStatus(404);
                }
            }
        }, "/internal/*", false);
        int port = server.start(0);
        try {
            BrokerClient candidate = new BrokerClient("http://127.0.0.1:" + port, "secret");
            BrokerLink link = new BrokerLink(new BrokerHome(temporary.resolve("home")));

            BrokerClient retained = link.maybeRetireForUpgrade(candidate, true);

            assertSame(candidate, retained);
            assertEquals(1, shutdownCalls.get());
            assertEquals(2, infoCalls.get(),
                    "the 409 fallback must probe the candidate before retaining it");
        } finally {
            server.stop();
        }
    }
}
