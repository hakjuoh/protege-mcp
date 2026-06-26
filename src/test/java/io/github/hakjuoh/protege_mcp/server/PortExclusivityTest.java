package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

/**
 * Grounds the premise behind the cross-window hand-off: the MCP server binds a single loopback port,
 * and a second bind on the same port fails with {@link BindException} — which is exactly the
 * {@code java.io.IOException: Failed to bind to /127.0.0.1:8123} Jetty raised in the field when a
 * second EditorKit auto-started while the first still owned the port. {@code EditorKitSwapHandoffTest}
 * models this exclusivity with its fake; this test confirms the model matches the OS.
 */
class PortExclusivityTest {

    @Test
    void twoServersCannotShareTheSameLoopbackPort() throws Exception {
        try (ServerSocket first = new ServerSocket()) {
            first.bind(new InetSocketAddress("127.0.0.1", 0)); // ephemeral, like an already-running owner
            int port = first.getLocalPort();
            assertTrue(port > 0);

            try (ServerSocket second = new ServerSocket()) {
                assertThrows(BindException.class,
                        () -> second.bind(new InetSocketAddress("127.0.0.1", port)),
                        "a second server must not be able to bind the port the first already holds");
            }
        }
    }
}
