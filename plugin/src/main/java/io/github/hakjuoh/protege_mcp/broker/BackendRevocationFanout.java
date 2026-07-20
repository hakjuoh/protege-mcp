package io.github.hakjuoh.protege_mcp.broker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.hakjuoh.protege_mcp.server.BrokerControlServlet;

/** Sends one client-revocation fence to every currently registered window backend in parallel. */
final class BackendRevocationFanout {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REVOCATION_TIMEOUT = Duration.ofMinutes(10);

    private final InstanceRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    BackendRevocationFanout(InstanceRegistry registry) {
        this.registry = registry;
    }

    Result revokeClient(String clientId) {
        ObjectNode json = mapper.createObjectNode().put("client_id", clientId);
        long deadline = System.nanoTime() + REVOCATION_TIMEOUT.toNanos();
        Map<String, InstanceRegistry.Window> targets = new LinkedHashMap<>();
        Set<String> attempted = new LinkedHashSet<>();
        Set<String> acknowledged = new LinkedHashSet<>();

        // Capture windows that register while the first batch is in flight too. Eight rounds is a
        // churn guard; any still-new ids after it are included as unacknowledged, never false-success.
        for (int round = 0; round < 8; round++) {
            registry.listWindows().forEach(window -> targets.put(window.id, window));
            List<InstanceRegistry.Window> batch = targets.values().stream()
                    .filter(window -> attempted.add(window.id))
                    .toList();
            if (batch.isEmpty()) {
                break;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            for (WindowResult result : sendBatch(batch, json.toString(),
                    Duration.ofNanos(remainingNanos))) {
                if (result.acknowledged) {
                    acknowledged.add(result.windowId);
                }
            }
        }
        registry.listWindows().forEach(window -> targets.put(window.id, window));
        List<String> failures = targets.keySet().stream()
                .filter(id -> !acknowledged.contains(id))
                .toList();
        return new Result(targets.size(), acknowledged.size(), failures);
    }

    private List<WindowResult> sendBatch(List<InstanceRegistry.Window> windows, String json,
            Duration timeout) {
        List<CompletableFuture<WindowResult>> pending = new ArrayList<>();
        for (InstanceRegistry.Window window : windows) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + window.port + BrokerControlServlet.PATH + "/revoke-client"))
                    .timeout(timeout)
                    .header(BrokerControlServlet.BROKER_SECRET_HEADER, window.secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            pending.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> new WindowResult(window.id,
                            failure == null && acknowledged(response))));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        List<WindowResult> results = new ArrayList<>();
        for (CompletableFuture<WindowResult> future : pending) {
            results.add(future.join());
        }
        return results;
    }

    private boolean acknowledged(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return false;
        }
        try {
            return mapper.readTree(response.body()).path("commit_fence_confirmed").asBoolean(false);
        } catch (java.io.IOException malformed) {
            return false;
        }
    }

    record Result(int windows, int acknowledged, List<String> failedWindowIds) {
        Result {
            failedWindowIds = Collections.unmodifiableList(new ArrayList<>(failedWindowIds));
        }

        boolean confirmed() {
            return failedWindowIds.isEmpty();
        }
    }

    private record WindowResult(String windowId, boolean acknowledged) { }
}
