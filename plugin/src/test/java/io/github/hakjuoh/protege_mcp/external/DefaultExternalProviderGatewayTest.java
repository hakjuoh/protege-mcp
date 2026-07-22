package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultExternalProviderGatewayTest {

    @TempDir
    Path temporary;

    private static final ProviderSessionScope OWNER = new ProviderSessionScope(
            "oauth", "client", "grant", "workspace");

    @Test
    void opaqueCursorAdvancesTerminallyAndNeverDisclosesProviderState() throws Exception {
        ProviderCursorStore cursors = new ProviderCursorStore();
        List<ProviderSearchRequest> requests = new ArrayList<>();
        DefaultExternalProviderGateway.ProviderCalls calls = calls(requests);
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(cursors, calls);
        ProviderSearchRequest initial = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, null);

        ExternalProviderGateway.SearchOutcome first = gateway.search(OWNER, initial, null,
                ignored -> invocation(List.of("efo"), List.of("en"), 10));

        assertEquals(1, first.page().items().size());
        assertNull(requests.get(0).continuation());
        assertNull(first.page().continuation());
        assertNotEquals("vendor-private-state", first.nextCursor());
        assertEquals(43, first.nextCursor().length());

        ProviderSessionScope wrong = new ProviderSessionScope(
                "oauth", "client", "other-grant", "workspace");
        ProviderFailure wrongScope = assertThrows(ProviderFailure.class,
                () -> gateway.search(wrong, null, first.nextCursor(),
                        ignored -> invocation(List.of("efo"), List.of("en"), 10)));
        assertEquals("cursor_invalid", wrongScope.code());

        ExternalProviderGateway.SearchOutcome second = gateway.search(OWNER, null,
                first.nextCursor(),
                ignored -> invocation(List.of("efo"), List.of("en"), 10));
        assertNull(second.nextCursor());
        assertEquals("vendor-private-state", requests.get(1).continuation());
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, null, first.nextCursor(),
                        ignored -> invocation(List.of("efo"), List.of("en"), 10))).code());
    }

    @Test
    void currentPolicyIsAppliedAgainBeforeCursorExecution() throws Exception {
        List<ProviderSearchRequest> requests = new ArrayList<>();
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(
                new ProviderCursorStore(), calls(requests));
        ProviderSearchRequest initial = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, null);
        ExternalProviderGateway.SearchOutcome first = gateway.search(OWNER, initial, null,
                ignored -> invocation(List.of("efo"), List.of("en"), 10));

        ProviderFailure narrowed = assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, null, first.nextCursor(),
                        ignored -> invocation(List.of("go"), List.of("en"), 10)));

        assertEquals("provider_policy_changed", narrowed.code());
        assertEquals(1, requests.size(), "provider call must not run after policy narrowing");

        ExternalProviderGateway.SearchOutcome retried = gateway.search(OWNER, null,
                first.nextCursor(),
                ignored -> invocation(List.of("efo"), List.of("en"), 10));
        assertNull(retried.nextCursor(), "failed claim must roll back for an authorized retry");
    }

    @Test
    void closeErasesOutstandingCursorsAndRejectsNewUse() throws Exception {
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(
                new ProviderCursorStore(), calls(new ArrayList<>()));
        ProviderSearchRequest initial = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, null);
        ExternalProviderGateway.SearchOutcome first = gateway.search(OWNER, initial, null,
                ignored -> invocation(List.of("efo"), List.of("en"), 10));

        gateway.close();

        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, null, first.nextCursor(),
                        ignored -> invocation(List.of("efo"), List.of("en"), 10))).code());
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, initial, null,
                        ignored -> invocation(List.of("efo"), List.of("en"), 10))).code());
    }

    @Test
    void clientAndGrantRevocationEraseMatchingCursors() throws Exception {
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(
                new ProviderCursorStore(), calls(new ArrayList<>()));
        ProviderSearchRequest initial = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, null);
        ExternalProviderGateway.SearchOutcome first = gateway.search(OWNER, initial, null,
                ignored -> invocation(List.of("efo"), List.of("en"), 10));

        assertEquals(1, gateway.revokeGrant("client", "grant"));
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, null, first.nextCursor(),
                        ignored -> invocation(List.of("efo"), List.of("en"), 10))).code());

        ExternalProviderGateway.SearchOutcome second = gateway.search(OWNER, initial, null,
                ignored -> invocation(List.of("efo"), List.of("en"), 10));
        assertEquals(1, gateway.revokeClient("client"));
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, null, second.nextCursor(),
                        ignored -> invocation(List.of("efo"), List.of("en"), 10))).code());
    }

    @Test
    void malformedModeAndInspectPolicyMismatchFailBeforeCalls() {
        List<ProviderSearchRequest> requests = new ArrayList<>();
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(
                new ProviderCursorStore(), calls(requests));
        ProviderSearchRequest initial = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, null);

        assertEquals("provider_request_invalid", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, initial, "also-present",
                        ignored -> invocation(List.of("efo"), List.of("en"), 10))).code());
        ProviderInspectRequest inspect = new ProviderInspectRequest(
                "ols", "efo", "https://example.org/term", "en");
        assertEquals("provider_policy_changed", assertThrows(ProviderFailure.class,
                () -> gateway.inspect(inspect,
                        ignored -> invocation(List.of("go"), List.of("en"), 10))).code());
        assertTrue(requests.isEmpty());
    }

    @Test
    void productionCompositionUsesOwnerConfigOlsCacheAndPolicyTtlOffline() throws Exception {
        Path providers = temporary.resolve("providers");
        Path credentials = temporary.resolve("credentials");
        Path cache = temporary.resolve("cache");
        String config = "{\"version\":1,\"origins\":[{\"alias\":\"ebi\","
                + "\"profile\":\"ols4\",\"origin\":\"https://example.org/ols4\"}],"
                + "\"credentials\":[]}";
        OwnerOnlyFiles.write(providers, ProviderOwnerConfig.FILE_NAME,
                config.getBytes(StandardCharsets.UTF_8));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        AtomicInteger networkCalls = new AtomicInteger();
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(
                new ProviderCursorStore(clock, Duration.ofMinutes(5), 32, 128, 512 * 1_024),
                new DefaultExternalProviderGateway.RuntimeRoots(providers, credentials, cache),
                (authority, store, gate, acquisition) -> request -> {
                    networkCalls.incrementAndGet();
                    acquisition.recordSuccess(authority, authority.cacheScopeFingerprint(null),
                            authority.origin().origin());
                    String body;
                    if (request.relativePath().equals("/api/search")) {
                        body = "{\"response\":{\"numFound\":1,\"start\":0,\"docs\":[{"
                                + "\"iri\":\"https://example.org/EFO_1\","
                                + "\"ontology_name\":\"efo\",\"label\":\"Cell\","
                                + "\"type\":\"class\"}]}}";
                    } else if (request.relativePath().contains("/terms/")) {
                        body = "{\"iri\":\"https://example.org/EFO_1\",\"lang\":\"en\","
                                + "\"label\":\"Cell\",\"ontology_name\":\"efo\","
                                + "\"type\":\"class\"}";
                    } else {
                        body = "{\"ontologyId\":\"efo\",\"version\":\"1\","
                                + "\"config\":{}}";
                    }
                    return new ProviderResponse(body.getBytes(StandardCharsets.UTF_8),
                            URI.create("https://example.org/ols4" + request.relativePath()),
                            clock.instant(), 0);
                }, clock);
        ExternalProviderGateway.Invocation invocation = new ExternalProviderGateway.Invocation(
                "ols", "ols4", "ebi", null, "sha256:" + "1".repeat(64),
                Duration.ofMinutes(15), true, List.of("efo"), List.of("en"), 10,
                authority -> authority.projectFingerprint(), origin -> { });
        ProviderSearchRequest search = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, null);

        ExternalProviderGateway.SearchOutcome first = gateway.search(
                OWNER, search, null, ignored -> invocation);
        clock.advance(Duration.ofMinutes(10));
        ExternalProviderGateway.SearchOutcome cached = gateway.search(
                OWNER, search, null, ignored -> invocation);
        ProviderInspectRequest inspect = new ProviderInspectRequest(
                "ols", "efo", "https://example.org/EFO_1", "en");
        ExternalProviderGateway.InspectOutcome inspected = gateway.inspect(
                inspect, ignored -> invocation);
        ExternalProviderGateway.InspectOutcome cachedInspect = gateway.inspect(
                inspect, ignored -> invocation);

        assertEquals(1, first.page().items().size());
        assertTrue(cached.cacheHit(), "a 15-minute policy TTL must survive ten minutes");
        assertEquals("Cell", inspected.result().labels().get(0).value());
        assertTrue(cachedInspect.cacheHit());
        assertEquals(3, networkCalls.get());

        clock.advance(Duration.ofMinutes(6));
        assertEquals(false, gateway.search(OWNER, search, null, ignored -> invocation).cacheHit());
        assertEquals(4, networkCalls.get());
        ExternalProviderGateway.Invocation freshOnly = new ExternalProviderGateway.Invocation(
                "ols", "ols4", "ebi", null, "sha256:" + "1".repeat(64),
                Duration.ofMinutes(15), false, List.of("efo"), List.of("en"), 10,
                authority -> authority.projectFingerprint(), origin -> { });
        ProviderSearchRequest fresh = new ProviderSearchRequest(
                "ols", "fresh", List.of("efo"), "en", 10, null);
        assertEquals(false, gateway.search(OWNER, fresh, null,
                ignored -> freshOnly).cacheHit());
        assertEquals(false, gateway.search(OWNER, fresh, null,
                ignored -> freshOnly).cacheHit());
        assertEquals(6, networkCalls.get(), "fresh-only policy must not retain cache entries");
        assertEquals(false, gateway.inspect(inspect, ignored -> freshOnly).cacheHit());
        assertEquals(false, gateway.inspect(inspect, ignored -> freshOnly).cacheHit());
        assertEquals(10, networkCalls.get(),
                "fresh-only inspection must bypass old cache and retain no replacement");
        gateway.close();
    }

    @Test
    void cacheDisabledProductionCallCreatesNoCacheOrCredentialState() throws Exception {
        Path providers = temporary.resolve("no-store-providers");
        Path credentials = temporary.resolve("no-store-credentials");
        Path cache = temporary.resolve("no-store-cache");
        String config = "{\"version\":1,\"origins\":[{\"alias\":\"ebi\","
                + "\"profile\":\"ols4\",\"origin\":\"https://example.org/ols4\"}],"
                + "\"credentials\":[]}";
        OwnerOnlyFiles.write(providers, ProviderOwnerConfig.FILE_NAME,
                config.getBytes(StandardCharsets.UTF_8));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-21T00:00:00Z"));
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(
                new ProviderCursorStore(),
                new DefaultExternalProviderGateway.RuntimeRoots(providers, credentials, cache),
                (authority, store, gate, acquisition) -> request -> {
                    acquisition.recordSuccess(authority, authority.cacheScopeFingerprint(null),
                            authority.origin().origin());
                    String body = "{\"response\":{\"numFound\":1,\"start\":0,\"docs\":[{"
                            + "\"iri\":\"https://example.org/EFO_1\","
                            + "\"ontology_name\":\"efo\",\"label\":\"Cell\","
                            + "\"type\":\"class\"}]}}";
                    return new ProviderResponse(body.getBytes(StandardCharsets.UTF_8),
                            URI.create("https://example.org/ols4/api/search"), clock.instant(), 0);
                }, clock);
        ExternalProviderGateway.Invocation invocation = new ExternalProviderGateway.Invocation(
                "ols", "ols4", "ebi", null, "sha256:" + "2".repeat(64),
                Duration.ZERO, false, List.of("efo"), List.of("en"), 10,
                authority -> authority.projectFingerprint(), origin -> { });

        ExternalProviderGateway.SearchOutcome outcome = gateway.search(OWNER,
                new ProviderSearchRequest("ols", "cell", List.of("efo"), "en", 10, null),
                null, ignored -> invocation);

        assertEquals(1, outcome.page().items().size());
        assertTrue(Files.notExists(cache));
        assertTrue(Files.notExists(credentials));
        gateway.close();
    }

    @Test
    void policyChangeAfterProviderSuccessPreventsPublicationAndCursorIssue() throws Exception {
        ProviderCursorStore cursors = new ProviderCursorStore();
        ExternalProviderGateway.Invocation allowed = invocation(
                List.of("efo"), List.of("en"), 10);
        ExternalProviderGateway.Invocation changed = invocation(
                List.of("efo"), List.of("en"), 9);
        AtomicReference<ExternalProviderGateway.Invocation> current =
                new AtomicReference<>(allowed);
        DefaultExternalProviderGateway.ProviderCalls calls =
                new DefaultExternalProviderGateway.ProviderCalls() {
                    @Override
                    public DefaultExternalProviderGateway.ProviderCallSearch search(
                            ExternalProviderGateway.Invocation invocation,
                            ProviderSearchRequest request) {
                        current.set(changed);
                        return new DefaultExternalProviderGateway.ProviderCallSearch(
                                new ProviderPage(List.of(evidence()), 2, "private-next",
                                        Instant.parse("2026-07-21T00:00:00Z"), 0), false,
                                () -> { });
                    }

                    @Override
                    public DefaultExternalProviderGateway.ProviderCallInspect inspect(
                            ExternalProviderGateway.Invocation invocation,
                            ProviderInspectRequest request) {
                        current.set(changed);
                        return new DefaultExternalProviderGateway.ProviderCallInspect(
                                evidence(), false, () -> { });
                    }
                };
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(cursors, calls);
        ProviderSearchRequest search = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 9, null);

        assertEquals("provider_policy_changed", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, search, null, ignored -> current.get())).code());
        assertEquals(0, cursors.activeCount());

        current.set(allowed);
        ProviderInspectRequest inspect = new ProviderInspectRequest(
                "ols", "efo", "https://example.org/term", "en");
        assertEquals("provider_policy_changed", assertThrows(ProviderFailure.class,
                () -> gateway.inspect(inspect, ignored -> current.get())).code());
    }

    @Test
    void ownerFinalFenceRunsAfterPolicyRecheckAndBeforeCursorIssue() throws Exception {
        ProviderCursorStore cursors = new ProviderCursorStore();
        AtomicInteger resolutions = new AtomicInteger();
        DefaultExternalProviderGateway.ProviderCalls calls =
                new DefaultExternalProviderGateway.ProviderCalls() {
                    @Override
                    public DefaultExternalProviderGateway.ProviderCallSearch search(
                            ExternalProviderGateway.Invocation invocation,
                            ProviderSearchRequest request) {
                        return new DefaultExternalProviderGateway.ProviderCallSearch(
                                new ProviderPage(List.of(evidence()), 2, "private-next",
                                        Instant.parse("2026-07-21T00:00:00Z"), 0), false,
                                () -> {
                                    assertEquals(2, resolutions.get());
                                    throw new ProviderFailure("provider_authority_changed",
                                            "Owner provider authority changed", false);
                                });
                    }

                    @Override
                    public DefaultExternalProviderGateway.ProviderCallInspect inspect(
                            ExternalProviderGateway.Invocation invocation,
                            ProviderInspectRequest request) {
                        throw new AssertionError("inspection is not expected");
                    }
                };
        DefaultExternalProviderGateway gateway = new DefaultExternalProviderGateway(cursors, calls);
        ProviderSearchRequest search = new ProviderSearchRequest(
                "ols", "cell", List.of("efo"), "en", 10, null);

        assertEquals("provider_authority_changed", assertThrows(ProviderFailure.class,
                () -> gateway.search(OWNER, search, null, ignored -> {
                    resolutions.incrementAndGet();
                    return invocation(List.of("efo"), List.of("en"), 10);
                })).code());
        assertEquals(0, cursors.activeCount());
    }

    private static DefaultExternalProviderGateway.ProviderCalls calls(
            List<ProviderSearchRequest> requests) {
        return new DefaultExternalProviderGateway.ProviderCalls() {
            @Override
            public DefaultExternalProviderGateway.ProviderCallSearch search(
                    ExternalProviderGateway.Invocation invocation,
                    ProviderSearchRequest request) {
                requests.add(request);
                String continuation = request.continuation() == null
                        ? "vendor-private-state" : null;
                ProviderPage page = new ProviderPage(List.of(evidence()), 2, continuation,
                        Instant.parse("2026-07-21T00:00:00Z"), 0);
                return new DefaultExternalProviderGateway.ProviderCallSearch(
                        page, false, () -> { });
            }

            @Override
            public DefaultExternalProviderGateway.ProviderCallInspect inspect(
                    ExternalProviderGateway.Invocation invocation,
                    ProviderInspectRequest request) {
                throw new AssertionError("inspect call must not run");
            }
        };
    }

    private static ExternalProviderGateway.Invocation invocation(List<String> ontologies,
            List<String> languages, int maxResults) {
        return new ExternalProviderGateway.Invocation("ols", "ols4", "ebi", null,
                "sha256:" + "0".repeat(64), Duration.ZERO, false,
                ontologies, languages, maxResults, authority -> authority.projectFingerprint(),
                origin -> { });
    }

    private static ProviderResult evidence() {
        return ProviderResult.create("ols", "ols4", "efo", null,
                "https://example.org/term", "class",
                List.of(new ProviderResult.LocalizedText("Cell", "en")), List.of(), List.of(),
                null, null, "exact_label", 1.0, null,
                Instant.parse("2026-07-21T00:00:00Z"),
                URI.create("https://example.org/api/term"), 0, false, null);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
