package io.github.hakjuoh.protege_mcp.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/**
 * Method-level tests for {@link McpServerManager}.
 *
 * <p>{@code McpServerManager} is a pure builder/config holder that wires the MCP SDK's sync server and
 * Streamable-HTTP transport together and owns their lifecycle. The tests here cover everything
 * reachable <em>without</em> invoking {@link McpServerManager#build(java.util.List)}.
 *
 * <p><b>Why {@code build()} is out of scope.</b> {@code build()} constructs the SDK's
 * {@code DefaultJsonSchemaValidator}, which pulls in {@code com.networknt.schema} and a Jackson
 * databind newer than the one resolved onto this module's <em>test</em> classpath. Attempting the
 * real {@code build()} in the test JVM fails during class initialisation
 * ({@code NoClassDefFoundError: com.networknt.schema.serialization.JsonMapperFactory$Holder} /
 * a {@code MapperBuilder.streamFactory} {@code VerifyError}) — a classpath/version conflict, not a
 * defect in the class under test. That makes {@code build()} (and therefore any assertion about the
 * built server's metadata/capabilities and any {@code close()} path that requires a built server)
 * effectively runtime-only here. Those cases are documented as skipped in the structured summary.
 *
 * <p>What remains fully testable: the public string constants, {@link
 * McpServerManager#getEndpointPath()}, the null-before-build state of {@link
 * McpServerManager#getTransportServlet()}, and the {@link McpServerManager#close()} no-op branch when
 * no server has been built (the {@code server == null} guard). The private {@code server} field is
 * inspected by reflection to prove close() leaves it null.
 */
class McpServerManagerTest {

    private static Object readField(McpServerManager m, String name) throws Exception {
        Field f = McpServerManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(m);
    }

    // ---- constants ------------------------------------------------------------------------------

    @Test
    void serverNameConstantIsProtegeMcp() {
        assertEquals("protege-mcp", McpServerManager.SERVER_NAME,
                "SERVER_NAME is the advertised MCP server name");
    }

    @Test
    void serverVersionConstantIsNonBlank() {
        assertNotNull(McpServerManager.SERVER_VERSION, "SERVER_VERSION must be set");
        assertFalse(McpServerManager.SERVER_VERSION.isBlank(), "SERVER_VERSION must not be blank");
    }

    @Test
    void serverVersionConstantLooksLikeADottedVersion() {
        assertTrueMsg(McpServerManager.SERVER_VERSION.matches("\\d+\\.\\d+\\.\\d+"),
                "SERVER_VERSION should be a dotted numeric version: " + McpServerManager.SERVER_VERSION);
    }

    // ---- getEndpointPath ------------------------------------------------------------------------

    @Test
    void getEndpointPathReturnsMcpConstant() {
        assertEquals("/mcp", new McpServerManager().getEndpointPath(),
                "endpoint path is the fixed /mcp constant");
    }

    @Test
    void getEndpointPathStartsWithSlash() {
        assertTrueMsg(new McpServerManager().getEndpointPath().startsWith("/"),
                "an endpoint path must be rooted with a leading slash");
    }

    @Test
    void getEndpointPathIsStableAcrossCalls() {
        McpServerManager m = new McpServerManager();
        assertEquals(m.getEndpointPath(), m.getEndpointPath(),
                "repeated getEndpointPath() calls return the same constant");
    }

    @Test
    void getEndpointPathDoesNotRequireBuild() {
        assertEquals("/mcp", new McpServerManager().getEndpointPath(),
                "endpoint path is available before build() is ever called");
    }

    @Test
    void getEndpointPathIsInstanceIndependent() {
        assertEquals(new McpServerManager().getEndpointPath(), new McpServerManager().getEndpointPath(),
                "the endpoint path is a fixed constant, identical across manager instances");
    }

    // ---- getTransportServlet (before build) -----------------------------------------------------

    @Test
    void getTransportServletIsNullBeforeBuild() {
        assertNull(new McpServerManager().getTransportServlet(),
                "transport is null until build() constructs it");
    }

    @Test
    void getTransportServletStaysNullAcrossRepeatedCallsBeforeBuild() {
        McpServerManager m = new McpServerManager();
        assertNull(m.getTransportServlet(), "first read is null before build()");
        assertNull(m.getTransportServlet(), "still null on a repeated read before build()");
    }

    @Test
    void transportFieldIsNullOnFreshInstance() {
        assertNull(new McpServerManager().getTransportServlet(),
                "the transport is null before build()");
    }

    // ---- close (never built → server == null guard) ---------------------------------------------

    @Test
    void serverFieldIsNullOnFreshInstance() throws Exception {
        assertNull(readField(new McpServerManager(), "server"),
                "the server field defaults to null before build()");
    }

    @Test
    void closeBeforeBuildIsNoop() {
        McpServerManager m = new McpServerManager();
        assertDoesNotThrow(m::close, "close() before build() must not throw (server is null)");
    }

    @Test
    void closeBeforeBuildLeavesServerNull() throws Exception {
        McpServerManager m = new McpServerManager();
        m.close();
        assertNull(readField(m, "server"),
                "the server == null guard means close() is a no-op that keeps server null");
    }

    @Test
    void closeBeforeBuildDoesNotCreateTransport() throws Exception {
        McpServerManager m = new McpServerManager();
        m.close();
        assertNull(readField(m, "transport"),
                "close() must not fabricate a transport when nothing was built");
    }

    @Test
    void closeIsIdempotentBeforeBuild() {
        McpServerManager m = new McpServerManager();
        m.close();
        assertDoesNotThrow(m::close, "repeated close() on a never-built manager stays a no-op");
    }

    @Test
    void closeManyTimesBeforeBuildIsNoop() throws Exception {
        McpServerManager m = new McpServerManager();
        for (int i = 0; i < 5; i++) {
            m.close();
        }
        assertNull(readField(m, "server"), "server stays null across many pre-build close() calls");
        assertNull(readField(m, "transport"),
                "transport stays null across many pre-build close() calls");
    }

    // ---- helper ---------------------------------------------------------------------------------

    private static void assertTrueMsg(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
