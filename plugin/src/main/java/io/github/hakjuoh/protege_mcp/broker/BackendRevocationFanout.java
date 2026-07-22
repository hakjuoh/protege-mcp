package io.github.hakjuoh.protege_mcp.broker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.hakjuoh.protege_mcp.oauth.OAuthStore;
import io.github.hakjuoh.protege_mcp.server.BrokerControlServlet;

/** Durable bounded client/grant revocation journal and parallel backend fence delivery. */
final class BackendRevocationFanout {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REVOCATION_TIMEOUT = Duration.ofMinutes(10);
    static final int MAX_PENDING = 1_024;
    static final int MAX_JOURNAL_BYTES = 2 * 1_024 * 1_024;

    private final InstanceRegistry registry;
    private final Consumer<String> savePending;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PendingRevocation> pending = new ConcurrentHashMap<>();
    private final Object persistenceLock = new Object();
    private long journalRevision;
    private long oauthConfirmedRevision = -1;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    BackendRevocationFanout(InstanceRegistry registry) {
        this(registry, () -> null, ignored -> { });
    }

    BackendRevocationFanout(InstanceRegistry registry, Supplier<String> loadPending,
            Consumer<String> savePending) {
        this.registry = registry;
        this.savePending = savePending == null ? ignored -> { } : savePending;
        load(loadPending == null ? null : loadPending.get());
    }

    Result revokeClient(String clientId) {
        prepareClient(clientId);
        return executeClient(clientId);
    }

    Result revokeGrant(String clientId, String grantId) {
        prepareGrant(clientId, grantId);
        return executeGrant(clientId, grantId);
    }

    void prepareClient(String clientId) {
        remember(new PendingRevocation("client", clientId, null));
    }

    void prepareGrant(String clientId, String grantId) {
        remember(new PendingRevocation("grant", clientId, grantId));
    }

    Result executeClient(String clientId) {
        return execute(new PendingRevocation("client", clientId, null));
    }

    Result executeGrant(String clientId, String grantId) {
        return execute(new PendingRevocation("grant", clientId, grantId));
    }

    /** Retry durable tombstones against windows that have not acknowledged them yet. */
    void retryPending() {
        List.copyOf(pending.values()).forEach(this::revoke);
    }

    int pendingCount() {
        return pending.size();
    }

    /** Reapply write-ahead entries to OAuth state before the broker accepts requests. */
    void replayOAuthRevocations(OAuthStore oauthStore) {
        List<PendingRevocation> snapshot;
        long revision;
        synchronized (persistenceLock) {
            if (pending.isEmpty()) return;
            if (oauthConfirmedRevision == journalRevision) return;
            snapshot = List.copyOf(pending.values());
            revision = journalRevision;
        }
        for (PendingRevocation revocation : snapshot) {
            if (revocation.grantId == null) {
                oauthStore.revokeClient(revocation.clientId);
            } else {
                oauthStore.revokeGrant(revocation.clientId, revocation.grantId);
            }
        }
        oauthStore.persistState();
        synchronized (persistenceLock) {
            if (journalRevision == revision) oauthConfirmedRevision = revision;
        }
    }

    /** Safe only after the broker's empty-registry linger has expired and it is shutting down. */
    void clearForQuiescentShutdown() {
        synchronized (persistenceLock) {
            if (pending.isEmpty()) return;
            if (oauthConfirmedRevision != journalRevision) {
                throw new IllegalStateException("OAuth revocation replay is not durable");
            }
            persist(List.of());
            pending.clear();
            journalRevision++;
            oauthConfirmedRevision = journalRevision;
        }
    }

    private void remember(PendingRevocation requested) {
        if (!validId(requested.clientId)
                || (requested.grantId != null && !validId(requested.grantId))) {
            throw new IllegalArgumentException("backend revocation identity is invalid");
        }
        synchronized (persistenceLock) {
            if (pending.containsKey(requested.key())) return;
            if (pending.size() >= MAX_PENDING) {
                throw new IllegalStateException("backend revocation journal capacity is exhausted");
            }
            List<PendingRevocation> next = new ArrayList<>(pending.values());
            next.add(requested);
            // Write-ahead: a failed save leaves both the token and in-memory journal unchanged.
            persist(next);
            pending.put(requested.key(), requested);
            journalRevision++;
        }
    }

    private Result execute(PendingRevocation identity) {
        PendingRevocation revocation = pending.get(identity.key());
        if (revocation == null) {
            throw new IllegalStateException("backend revocation was not durably prepared");
        }
        return revoke(revocation);
    }

    private Result revoke(PendingRevocation revocation) {
        synchronized (revocation) {
            return revokeLocked(revocation);
        }
    }

    private Result revokeLocked(PendingRevocation revocation) {
        ObjectNode json = mapper.createObjectNode().put("client_id", revocation.clientId);
        String path = "/revoke-client";
        if (revocation.grantId != null) {
            json.put("grant_id", revocation.grantId);
            path = "/revoke-grant";
        }
        long deadline = System.nanoTime() + REVOCATION_TIMEOUT.toNanos();
        Map<InstanceRegistry.EndpointKey, InstanceRegistry.Window> targets = new LinkedHashMap<>();
        Set<InstanceRegistry.EndpointKey> attempted = new LinkedHashSet<>();
        registry.revocationTargets().forEach(target ->
                targets.put(target.key(), target.window()));
        revocation.acknowledgedTargets.retainAll(targets.keySet());

        // Capture windows that register while the first batch is in flight too. Eight rounds is a
        // churn guard; any still-new endpoints after it remain unacknowledged, never false-success.
        for (int round = 0; round < 8; round++) {
            registry.revocationTargets().forEach(target ->
                    targets.put(target.key(), target.window()));
            List<InstanceRegistry.RevocationTarget> batch = targets.entrySet().stream()
                    .filter(entry -> !revocation.acknowledgedTargets.contains(entry.getKey()))
                    .filter(entry -> attempted.add(entry.getKey()))
                    .map(entry -> new InstanceRegistry.RevocationTarget(
                            entry.getKey(), entry.getValue()))
                    .toList();
            if (batch.isEmpty()) {
                break;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            for (WindowResult result : sendBatch(batch, path, json.toString(),
                    Duration.ofNanos(remainingNanos))) {
                if (result.acknowledged) {
                    revocation.acknowledgedTargets.add(result.target);
                }
            }
        }
        registry.revocationTargets().forEach(target ->
                targets.put(target.key(), target.window()));
        List<String> failures = targets.entrySet().stream()
                .filter(entry -> !revocation.acknowledgedTargets.contains(entry.getKey()))
                .map(entry -> entry.getValue().id)
                .toList();
        int acknowledged = targets.size() - failures.size();
        return new Result(targets.size(), acknowledged, failures);
    }

    private void load(String json) {
        if (json == null) return;
        if (json.isBlank()) {
            throw new IllegalStateException("backend revocation journal is blank");
        }
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JOURNAL_BYTES) {
                throw new IllegalArgumentException("backend revocation journal exceeds its bound");
            }
            var root = mapper.readTree(json);
            if (root.path("version").asInt(-1) != 1 || !root.path("revocations").isArray()) {
                throw new IllegalArgumentException("backend revocation journal is invalid");
            }
            if (root.path("revocations").size() > MAX_PENDING) {
                throw new IllegalArgumentException("backend revocation journal exceeds its bound");
            }
            for (var item : root.path("revocations")) {
                String kind = item.path("kind").asText("");
                String clientId = item.path("client_id").asText("");
                String grantId = item.path("grant_id").asText(null);
                if (validId(clientId) && (("client".equals(kind) && grantId == null)
                        || ("grant".equals(kind) && validId(grantId)))) {
                    PendingRevocation revocation = new PendingRevocation(kind, clientId, grantId);
                    pending.putIfAbsent(revocation.key(), revocation);
                } else {
                    throw new IllegalArgumentException("backend revocation entry is invalid");
                }
            }
            if (!pending.isEmpty()) journalRevision = 1;
        } catch (RuntimeException | java.io.IOException malformed) {
            throw new IllegalStateException("backend revocation journal is invalid", malformed);
        }
    }

    private void persist(List<PendingRevocation> snapshot) {
        ObjectNode root = mapper.createObjectNode().put("version", 1);
        var values = root.putArray("revocations");
        snapshot.stream().distinct().sorted((left, right) -> left.key().compareTo(right.key()))
                .forEach(revocation -> {
                    ObjectNode value = values.addObject().put("kind", revocation.kind)
                            .put("client_id", revocation.clientId);
                    if (revocation.grantId != null) value.put("grant_id", revocation.grantId);
                });
        String json = root.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JOURNAL_BYTES) {
            throw new IllegalStateException("backend revocation journal exceeds its byte bound");
        }
        savePending.accept(json);
    }

    private static boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 512;
    }

    private List<WindowResult> sendBatch(List<InstanceRegistry.RevocationTarget> targets, String path,
            String json, Duration timeout) {
        List<CompletableFuture<WindowResult>> pending = new ArrayList<>();
        for (InstanceRegistry.RevocationTarget target : targets) {
            InstanceRegistry.Window window = target.window();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + window.port + BrokerControlServlet.PATH + path))
                    .timeout(timeout)
                    .header(BrokerControlServlet.BROKER_SECRET_HEADER, window.secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            pending.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> new WindowResult(target.key(),
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

    private record WindowResult(InstanceRegistry.EndpointKey target, boolean acknowledged) { }

    private static final class PendingRevocation {
        private final String kind;
        private final String clientId;
        private final String grantId;
        private final Set<InstanceRegistry.EndpointKey> acknowledgedTargets =
                ConcurrentHashMap.newKeySet();

        private PendingRevocation(String kind, String clientId, String grantId) {
            this.kind = kind;
            this.clientId = clientId;
            this.grantId = grantId;
        }

        private String key() {
            return kind + ":" + clientId.length() + ":" + clientId + ":"
                    + (grantId == null ? "" : grantId.length() + ":" + grantId);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PendingRevocation value && key().equals(value.key());
        }

        @Override
        public int hashCode() {
            return key().hashCode();
        }
    }
}
