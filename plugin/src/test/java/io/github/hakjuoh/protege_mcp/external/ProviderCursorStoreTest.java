package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

class ProviderCursorStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void claimsRollBackOrAtomicallyAdvanceAndTerminalCompletionConsumes() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        ProviderSearchRequest terminal = new ProviderSearchRequest("provider", "query",
                List.of("efo"), "en", 20, null);
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.issue(scope, terminal)).code());
        ProviderSearchRequest first = next("private query", "vendor-one");
        String token = store.issue(scope, first);

        try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
            assertEquals(first, claim.request());
            assertEquals("cursor_in_use", assertThrows(ProviderFailure.class,
                    () -> store.claim(scope, token)).code());
            assertTrue(claim.toString().contains("redacted"));
            assertFalse(claim.toString().contains("private query"));
        }

        ProviderSearchRequest second = next("private query", "vendor-two");
        String successor;
        try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
            successor = claim.advance(second);
        }
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, token)).code());
        try (ProviderCursorStore.Claim claim = store.claim(scope, successor)) {
            assertEquals(second, claim.request());
            claim.complete();
        }
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, successor)).code());
        assertEquals(0, store.activeCount());
    }

    @Test
    void opaqueTokensAreExactlyPrincipalGrantAndWorkspaceScoped() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope owner = scope("client-a", "grant-a", "workspace-a");
        ProviderSearchRequest state = next("diagnosis private phrase", "opaque-vendor-state");
        String token = store.issue(owner, state);

        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertFalse(token.contains("diagnosis"));
        for (ProviderSessionScope wrong : List.of(
                scope("client-b", "grant-a", "workspace-a"),
                scope("client-a", "grant-b", "workspace-a"),
                scope("client-a", "grant-a", "workspace-b"),
                new ProviderSessionScope("assistant", "client-a", "grant-a", "workspace-a"))) {
            ProviderFailure failure = assertThrows(ProviderFailure.class,
                    () -> store.claim(wrong, token));
            assertEquals("cursor_invalid", failure.code());
            assertFalse(failure.getMessage().contains("diagnosis"));
        }
        try (ProviderCursorStore.Claim claim = store.claim(owner, token)) {
            assertEquals(state, claim.request());
        }
    }

    @Test
    void malformedUnknownAndMissingScopeInputsFailWithoutDisclosure() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        ProviderSearchRequest state = next("private query", "private-vendor-state");

        for (String token : List.of("not-a-token", "A".repeat(43))) {
            ProviderFailure failure = assertThrows(ProviderFailure.class,
                    () -> store.claim(scope, token));
            assertEquals("cursor_invalid", failure.code());
            assertFalse(failure.getMessage().contains("private"));
        }
        ProviderFailure missingScope = assertThrows(ProviderFailure.class,
                () -> store.issue(null, state));
        assertEquals("provider_scope_invalid", missingScope.code());
    }

    @Test
    void expiryAndRestartEraseStateAndClockOverflowFailsClosed() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ProviderCursorStore store = new ProviderCursorStore(clock, Duration.ofMinutes(5),
                32, 256, 64 * 1_024);
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        String token = store.issue(scope, next("query", "vendor"));
        clock.advance(Duration.ofMinutes(5));
        assertEquals("cursor_expired", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, token)).code());

        String claimedToken = store.issue(scope, next("query", "claimed-vendor"));
        ProviderCursorStore.Claim claimed = store.claim(scope, claimedToken);
        clock.advance(Duration.ofMinutes(5));
        assertEquals("cursor_expired", assertThrows(ProviderFailure.class,
                () -> claimed.advance(next("query", "too-late"))).code());
        claimed.close();

        String restartToken = store.issue(scope, next("query", "restart-vendor"));
        ProviderCursorStore restarted = new ProviderCursorStore(clock, Duration.ofMinutes(5),
                32, 256, 64 * 1_024);
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> restarted.claim(scope, restartToken)).code());

        MutableTicker overflowTicker = new MutableTicker(0);
        ProviderCursorStore overflowStore = new ProviderCursorStore(overflowTicker,
                Duration.ofMinutes(5), 32, 256, 64 * 1_024);
        assertEquals(0, overflowStore.activeCount());
        overflowTicker.set(Long.MAX_VALUE - 1_000);
        ProviderFailure overflow = assertThrows(ProviderFailure.class,
                () -> overflowStore.issue(scope, next("query", "overflow")));
        assertEquals("cursor_invalid", overflow.code());
    }

    @Test
    void monotonicTimerRegressionErasesStateAndPermanentlyFailsClosed() throws Exception {
        MutableTicker ticker = new MutableTicker(100);
        ProviderCursorStore store = new ProviderCursorStore(ticker, Duration.ofSeconds(5),
                4, 8, 1_024);
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        String token = store.issue(scope, next("query", "vendor"));

        ticker.set(99);
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, token)).code());
        ticker.set(101);
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.issue(scope, next("query", "new-vendor"))).code());
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                store::activeCount).code());
    }

    @Test
    void successorExpiryOverflowRollsBackWithoutLosingCurrentCursor() throws Exception {
        MutableTicker ticker = new MutableTicker(0);
        ProviderCursorStore store = new ProviderCursorStore(ticker, Duration.ofSeconds(5),
                4, 8, 1_024);
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        assertEquals(0, store.activeCount());
        ticker.set(Long.MAX_VALUE - 6_000);
        ProviderSearchRequest current = next("query", "current-vendor");
        String token = store.issue(scope, current);
        ticker.set(Long.MAX_VALUE - 2_000);

        try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
            assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                    () -> claim.advance(next("query", "successor-vendor"))).code());
        }
        try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
            assertEquals(current, claim.request());
        }
    }

    @Test
    void quotasNeverEvictActiveCursorsAndExpiredEntriesReleaseCapacity() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ProviderCursorStore store = new ProviderCursorStore(clock, Duration.ofSeconds(5),
                2, 3, 1_024);
        ProviderSessionScope first = scope("client-a", "grant", "workspace");
        ProviderSessionScope second = scope("client-b", "grant", "workspace");
        ProviderSessionScope third = scope("client-c", "grant", "workspace");
        String one = store.issue(first, next("one", "vendor-one"));
        String two = store.issue(first, next("two", "vendor-two"));
        assertEquals("cursor_quota_exceeded", assertThrows(ProviderFailure.class,
                () -> store.issue(first, next("three", "vendor-three"))).code());
        String other = store.issue(second, next("other", "vendor-other"));
        assertEquals("cursor_quota_exceeded", assertThrows(ProviderFailure.class,
                () -> store.issue(third, next("third", "vendor-third"))).code());

        for (var pair : List.of(MapEntry.of(first, one), MapEntry.of(first, two),
                MapEntry.of(second, other))) {
            try (ProviderCursorStore.Claim claim = store.claim(pair.scope(), pair.token())) {
                assertTrue(claim.request().continuation().startsWith("vendor-"));
            }
        }
        clock.advance(Duration.ofSeconds(6));
        String afterExpiry = store.issue(third, next("third", "vendor-third"));
        try (ProviderCursorStore.Claim claim = store.claim(third, afterExpiry)) {
            assertEquals("vendor-third", claim.request().continuation());
        }
    }

    @Test
    void byteQuotaLeavesExistingCursorUntouched() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore(new MutableClock(NOW),
                Duration.ofMinutes(5), 4, 8, 400);
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        ProviderSearchRequest small = next("small", "ok");
        String token = store.issue(scope, small);
        ProviderFailure oversized = assertThrows(ProviderFailure.class,
                () -> store.issue(scope, next("sensitive-search", "x".repeat(200))));
        assertEquals("cursor_too_large", oversized.code());
        try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
            assertEquals("cursor_too_large", assertThrows(ProviderFailure.class,
                    () -> claim.advance(next("small", "x".repeat(200)))).code());
        }
        try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
            assertEquals(small, claim.request());
        }
    }

    @Test
    void revocationWorkspaceCloseClearAndStoreCloseEraseClaims() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope grantOne = scope("client-a", "grant-1", "workspace-a");
        ProviderSessionScope grantTwo = scope("client-a", "grant-2", "workspace-a");
        ProviderSessionScope other = scope("client-b", "grant-3", "workspace-a");
        String one = store.issue(grantOne, next("one", "vendor-one"));
        String two = store.issue(grantTwo, next("two", "vendor-two"));
        String three = store.issue(other, next("three", "vendor-three"));

        ProviderCursorStore.Claim claimed = store.claim(grantOne, one);
        assertEquals(1, store.revokeGrant("client-a", "grant-1"));
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                claimed::request).code());
        claimed.close();
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(grantOne, one)).code());
        assertEquals(1, store.revokeClient("client-a"));
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(grantTwo, two)).code());
        assertEquals(1, store.clearWorkspace("workspace-a"));
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(other, three)).code());

        String four = store.issue(scope("client-c", "grant", "workspace-b"),
                next("four", "vendor-four"));
        store.clear();
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(scope("client-c", "grant", "workspace-b"), four)).code());
        store.close();
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                () -> store.issue(scope("client-c", "grant", "workspace-b"),
                        next("five", "vendor-five"))).code());
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                store::activeCount).code());
    }

    @Test
    void blankGrantIsNormalizedRevocableAndScopeTextIsRedacted() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope scope = scope("private-client", " ", "private-workspace");
        assertEquals("", scope.grantId());
        assertEquals("ProviderSessionScope[redacted=true]", scope.toString());
        assertFalse(scope.toString().contains("private-client"));
        store.issue(scope, next("query", "vendor"));
        assertEquals(1, store.revokeGrant("private-client", null));
        assertEquals(0, store.activeCount());
    }

    @Test
    void aggregateMemoryConfigurationIsBounded() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderCursorStore(Clock.systemUTC(), Duration.ofMinutes(5),
                        4, 256, 262_145));
    }

    @Test
    void defaultBudgetStoresAContractMaximumUtf8Continuation() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        String continuation = String.valueOf((char) 0x0800)
                .repeat(ProviderSearchRequest.MAX_CONTINUATION_LENGTH);
        ProviderSearchRequest request = next("query", continuation);

        String token = store.issue(scope, request);
        try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
            assertEquals(request, claim.request());
        }
    }

    @Test
    void principalQuotaSpansWorkspacesAndLengthFramingIsInjective() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ProviderCursorStore store = new ProviderCursorStore(clock, Duration.ofMinutes(5),
                1, 4, 1_024);
        ProviderSessionScope first = new ProviderSessionScope("a", "12:b", "3:c", "one");
        ProviderSessionScope second = new ProviderSessionScope("a", "12:b", "3:c", "two");
        store.issue(first, next("query", "vendor-one"));
        assertEquals("cursor_quota_exceeded", assertThrows(ProviderFailure.class,
                () -> store.issue(second, next("query", "vendor-two"))).code());

        ProviderSessionScope framedOne = new ProviderSessionScope("a", "1:b2", "3:c", "one");
        ProviderSessionScope framedTwo = new ProviderSessionScope("a1", "b2", "3:c", "one");
        assertNotEquals(framedOne.principalKey(), framedTwo.principalKey());
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        String token = store.issue(scope, next("query", "vendor"));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> task = () -> {
                start.await();
                try (ProviderCursorStore.Claim claim = store.claim(scope, token)) {
                    attempted.countDown();
                    release.await();
                    return "winner";
                } catch (ProviderFailure failure) {
                    attempted.countDown();
                    return failure.code();
                }
            };
            Future<String> first = executor.submit(task);
            Future<String> second = executor.submit(task);
            start.countDown();
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            release.countDown();
            Set<String> results = Set.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertEquals(Set.of("winner", "cursor_in_use"), results);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void oneClaimSerializesConcurrentAdvanceAndClose() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        String token = store.issue(scope, next("query", "vendor"));
        ProviderCursorStore.Claim claim = store.claim(scope, token);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> advance = executor.submit(() -> {
                start.await();
                try {
                    return claim.advance(next("query", "successor"));
                } catch (IllegalStateException closed) {
                    return "closed";
                }
            });
            Future<String> close = executor.submit(() -> {
                start.await();
                claim.close();
                return "closed";
            });
            start.countDown();
            String advanceResult = advance.get(10, TimeUnit.SECONDS);
            assertEquals("closed", close.get(10, TimeUnit.SECONDS));
            assertTrue(advanceResult.equals("closed")
                    || advanceResult.matches("[A-Za-z0-9_-]{43}"));
            if (advanceResult.equals("closed")) {
                try (ProviderCursorStore.Claim retried = store.claim(scope, token)) {
                    assertEquals("vendor", retried.request().continuation());
                }
            } else {
                try (ProviderCursorStore.Claim successor = store.claim(scope, advanceResult)) {
                    assertEquals("successor", successor.request().continuation());
                }
            }
        } finally {
            claim.close();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAdvanceAndRevocationCannotLeaveAUsableCursor() throws Exception {
        ProviderCursorStore store = new ProviderCursorStore();
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        String token = store.issue(scope, next("query", "vendor"));
        ProviderCursorStore.Claim claim = store.claim(scope, token);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> advance = executor.submit(() -> {
                start.await();
                try {
                    return claim.advance(next("query", "successor"));
                } catch (ProviderFailure revoked) {
                    return revoked.code();
                }
            });
            Future<Integer> revoke = executor.submit(() -> {
                start.await();
                return store.revokeGrant("client", "grant");
            });
            start.countDown();
            String result = advance.get(10, TimeUnit.SECONDS);
            int removed = revoke.get(10, TimeUnit.SECONDS);
            assertTrue(result.equals("cursor_invalid")
                    || result.matches("[A-Za-z0-9_-]{43}"));
            assertEquals(1, removed);
            assertEquals(0, store.activeCount());
        } finally {
            claim.close();
            executor.shutdownNow();
        }
    }

    @Test
    void clearAndCloseInvalidateOutstandingClaims() throws Exception {
        ProviderSessionScope scope = scope("client", "grant", "workspace");
        ProviderCursorStore cleared = new ProviderCursorStore();
        ProviderCursorStore.Claim clearedClaim = cleared.claim(scope,
                cleared.issue(scope, next("query", "clear-vendor")));
        cleared.clear();
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                clearedClaim::request).code());
        clearedClaim.close();

        ProviderCursorStore closed = new ProviderCursorStore();
        ProviderCursorStore.Claim closedClaim = closed.claim(scope,
                closed.issue(scope, next("query", "close-vendor")));
        closed.close();
        assertEquals("cursor_invalid", assertThrows(ProviderFailure.class,
                closedClaim::request).code());
        closedClaim.close();
    }

    private static ProviderSessionScope scope(String client, String grant, String workspace) {
        return new ProviderSessionScope("oauth", client, grant, workspace);
    }

    private static ProviderSearchRequest next(String query, String continuation) {
        return new ProviderSearchRequest("provider", query, List.of("efo"), "en", 20,
                continuation);
    }

    private record MapEntry(ProviderSessionScope scope, String token) {
        static MapEntry of(ProviderSessionScope scope, String token) {
            return new MapEntry(scope, token);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        void set(Instant value) {
            current = value;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC required");
            return this;
        }

        @Override public Instant instant() { return current; }
    }

    private static final class MutableTicker implements LongSupplier {
        private volatile long current;

        private MutableTicker(long current) {
            this.current = current;
        }

        void set(long value) {
            current = value;
        }

        @Override
        public long getAsLong() {
            return current;
        }
    }
}
