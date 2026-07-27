package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.server.EmbeddedHttpServer;

/**
 * What the internal control plane answers when the registry cannot take a call. The registry refuses
 * work it has no room for - a full endpoint quarantine, a full process table, a registry sealed for
 * shutdown - and every one of those states passes: the next reap drains the quarantine, a departing
 * instance frees the table, a successor broker follows the shutdown. So the instance's job is to keep
 * trying, and the answer has to say so rather than surface as a container-rendered fault the caller
 * cannot tell from a broken broker.
 *
 * <p>The servlet is mounted on its own server here, with a clock that does not move: a real
 * {@link BrokerServer} runs a maintenance tick that reaps the pids these tests invent and ages out the
 * quarantine they fill, so the state under test would not hold still.
 */
class InternalApiCapacityTest {

    private static final String DIR_SECRET = "dir-secret-for-capacity-test";
    /** One pid, re-registering: each new window set retires the previous one into quarantine. */
    private static final long PID = 4_242;
    /** Re-registrations needed to fill the quarantine: the first fills no slots, each later one 128. */
    private static final int ROUNDS =
            InstanceRegistry.MAX_QUARANTINED_WINDOWS / InstanceRegistry.MAX_WINDOWS_PER_PROCESS;

    private final AtomicLong now = new AtomicLong(1_000);
    private final InstanceRegistry registry = new InstanceRegistry(now::get);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private EmbeddedHttpServer server;
    private int port;

    @BeforeEach
    void mountTheInternalApi() throws Exception {
        server = new EmbeddedHttpServer();
        server.addServlet(new InternalApiServlet(DIR_SECRET, registry, () -> null, () -> { }),
                "/internal/*", false);
        port = server.start(0);
    }

    @AfterEach
    void stopTheServer() {
        server.stop();
    }

    @Test
    void aRegistrationTheRegistryHasNoRoomForIsUnavailableRatherThanAFault() throws Exception {
        String handle = fillTheQuarantine();

        HttpResponse<String> refused = post("/internal/register", "{\"pid\":" + PID
                + ",\"version\":\"1.0\",\"token\":\"tok\",\"windows\":" + windowsJson("overflow", 1) + "}");

        assertEquals(503, refused.statusCode(),
                "a registry with no room is unavailable, not broken: " + refused.body());
        assertTrue(refused.body().contains("broker_unavailable"),
                "the reason is named in this API's own JSON: " + refused.body());
        assertEquals("application/json", contentType(refused),
                "answered by the API, not by the container's error page");
        assertTrue(registry.heartbeat(handle, "tok", liveWindows()),
                "the registration that was already there is untouched - refusing costs it nothing");
    }

    @Test
    void aHeartbeatThatWouldRetireAWindowIntoAFullQuarantineIsUnavailable() throws Exception {
        String handle = fillTheQuarantine();

        // Reporting fewer windows retires the ones left out, and there is no room to hold them.
        HttpResponse<String> refused = post("/internal/heartbeat", "{\"processId\":\"" + handle
                + "\",\"token\":\"tok\",\"windows\":[]}");

        assertEquals(503, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("broker_unavailable"), refused.body());
        assertEquals(InstanceRegistry.MAX_WINDOWS_PER_PROCESS, registry.windowCount(),
                "the process keeps the window set it last reported, so it stays routable and fenceable");
    }

    @Test
    void aWindowCloseTheQuarantineCannotHoldIsUnavailable() throws Exception {
        String handle = fillTheQuarantine();

        HttpResponse<String> refused = post("/internal/unregister",
                "{\"processId\":\"" + handle + "\"}");

        assertEquals(503, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("broker_unavailable"), refused.body());
        assertEquals(1, registry.processCount(),
                "the instance is still registered, so it is still routable and still owed every fence");
    }

    @Test
    void theSameCallsSucceedWhileThereIsRoom() throws Exception {
        HttpResponse<String> registered = post("/internal/register", "{\"pid\":" + PID
                + ",\"version\":\"1.0\",\"token\":\"tok\",\"windows\":" + windowsJson("first", 2) + "}");
        assertEquals(200, registered.statusCode(), registered.body());
        String handle = registered.body().replaceAll(".*\"processId\":\"([^\"]+)\".*", "$1");

        HttpResponse<String> beat = post("/internal/heartbeat", "{\"processId\":\"" + handle
                + "\",\"token\":\"tok\",\"windows\":" + windowsJson("first", 1) + "}");
        assertEquals(200, beat.statusCode(), beat.body());

        HttpResponse<String> closed = post("/internal/unregister",
                "{\"processId\":\"" + handle + "\"}");
        assertEquals(200, closed.statusCode(), closed.body());
    }

    /**
     * Fill the endpoint quarantine to its bound with one live process, by re-registering the same pid
     * with a fresh window set each time: every re-registration retires the set before it. Returns the
     * live process handle, whose windows are the only ones not quarantined.
     */
    private String fillTheQuarantine() {
        String handle = null;
        for (int round = 0; round <= ROUNDS; round++) {
            handle = registry.register(PID, "1.0", "tok",
                    windows("round" + round, InstanceRegistry.MAX_WINDOWS_PER_PROCESS));
        }
        assertEquals(InstanceRegistry.MAX_WINDOWS_PER_PROCESS, registry.windowCount(),
                "one live window set; every earlier one is quarantined");
        return handle;
    }

    /** The window set the live registration last reported, i.e. the one the last round registered. */
    private static List<InstanceRegistry.Window> liveWindows() {
        return windows("round" + ROUNDS, InstanceRegistry.MAX_WINDOWS_PER_PROCESS);
    }

    private static List<InstanceRegistry.Window> windows(String prefix, int count) {
        List<InstanceRegistry.Window> windows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = prefix + "-" + i;
            windows.add(new InstanceRegistry.Window(id, 20_000 + i, "secret-" + id, "title-" + id, i, i));
        }
        return windows;
    }

    private static String windowsJson(String prefix, int count) {
        StringBuilder json = new StringBuilder("[");
        for (InstanceRegistry.Window w : windows(prefix, count)) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(w.id).append("\",\"port\":").append(w.port)
                    .append(",\"secret\":\"").append(w.secret).append("\",\"title\":\"").append(w.title)
                    .append("\",\"focusedAt\":").append(w.focusedAt)
                    .append(",\"registeredAt\":").append(w.registeredAt).append('}');
        }
        return json.append(']').toString();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header(InternalApiServlet.SECRET_HEADER, DIR_SECRET)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("")
                .split(";")[0].trim();
    }
}
