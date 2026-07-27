package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** How the client reads a broker's answer to a revocation the broker could not finish. */
class BrokerClientTest {

    @Test
    void aPartialRevocationReportsTheCountTheBrokerSentNotTheIdsItCouldName() throws Exception {
        // A broker's list of owed windows is bounded, so it can be shorter than the obligation it
        // describes. Measuring the list would tell the user fewer windows are unfenced than there are.
        withBroker(503, "{\"revoked\":true,\"unacknowledged_windows\":300,"
                + "\"unacknowledged_window_ids\":[\"w-1\",\"w-2\"]}", client -> {
            BrokerClient.IncompleteRevocationException incomplete = assertThrows(
                    BrokerClient.IncompleteRevocationException.class,
                    () -> client.revokeClient("client"));
            assertEquals(300, incomplete.unacknowledgedWindows());
            assertTrue(incomplete.getMessage().contains("300 backend window(s)"),
                    "the message the view shows carries the same number: " + incomplete.getMessage());
        });
    }

    @Test
    void anOlderBrokerThatOnlyListsTheWindowsIsReadTheWayItAlwaysWas() throws Exception {
        // Version skew is ordinary here - the running broker can predate this client. Its list is all it
        // knows, and reading the absent count as zero would describe a partial revocation as a complete
        // one, which is the one thing this exception exists to prevent.
        withBroker(503, "{\"revoked\":true,\"unacknowledged_window_ids\":[\"w-1\",\"w-2\"]}", client -> {
            BrokerClient.IncompleteRevocationException incomplete = assertThrows(
                    BrokerClient.IncompleteRevocationException.class,
                    () -> client.revokeClient("client"));
            assertEquals(2, incomplete.unacknowledgedWindows());
        });
    }

    @Test
    void anUnavailableBrokerIsNotReportedAsAnUnfinishedFence() throws Exception {
        // 503 also answers "the OAuth store is unavailable", where nothing was revoked at all. Retrying
        // is the advice either way, but claiming a credential is gone when it is not would be wrong.
        withBroker(503, "{\"error\":\"oauth_store_unavailable\"}", client -> {
            IOException failed = assertThrows(IOException.class, () -> client.revokeClient("client"));
            assertFalse(failed instanceof BrokerClient.IncompleteRevocationException,
                    "no window list means no fence report: " + failed.getMessage());
            assertTrue(failed.getMessage().contains("oauth_store_unavailable"));
        });
    }

    @FunctionalInterface
    private interface RevocationCase {
        void run(BrokerClient client) throws Exception;
    }

    private static void withBroker(int status, String body, RevocationCase revocation)
            throws Exception {
        HttpServer broker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broker.createContext("/internal/revoke-client", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        broker.start();
        try {
            revocation.run(new BrokerClient(
                    "http://127.0.0.1:" + broker.getAddress().getPort(), "dir-secret"));
        } finally {
            broker.stop(0);
        }
    }
}
