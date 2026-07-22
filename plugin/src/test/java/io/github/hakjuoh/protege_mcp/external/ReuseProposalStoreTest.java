package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

class ReuseProposalStoreTest {

    private static final String WORKSPACE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_WORKSPACE = "223e4567-e89b-12d3-a456-426614174000";
    private static final String EXTERNAL = "https://example.org/external";
    private static final String LOCAL = "https://example.org/local";
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void claimsRollBackAndCompletionConsumesWithoutExposingContent() throws Exception {
        ReuseProposalStore store = new ReuseProposalStore();
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        ReuseProposal proposal = proposal(WORKSPACE, "private note");
        String id = store.issue(scope, proposal);

        assertTrue(id.matches("[A-Za-z0-9_-]{43}"));
        assertFalse(id.contains("private"));
        try (ReuseProposalStore.Claim claim = store.claim(scope, id)) {
            assertEquals(proposal, claim.proposal());
            assertEquals("proposal_in_use", assertThrows(ProviderFailure.class,
                    () -> store.claim(scope, id)).code());
            assertEquals("ReuseProposalClaim[redacted=true]", claim.toString());
        }
        try (ReuseProposalStore.Claim claim = store.claim(scope, id)) {
            claim.complete();
            assertThrows(IllegalStateException.class, claim::proposal);
            assertThrows(IllegalStateException.class, claim::complete);
        }
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, id)).code());
        assertEquals(0, store.activeCount());
    }

    @Test
    void exactScopeAndProposalWorkspaceAreRequired() throws Exception {
        ReuseProposalStore store = new ReuseProposalStore();
        ProviderSessionScope owner = scope("client-a", "grant-a", WORKSPACE);
        String id = store.issue(owner, proposal(WORKSPACE, "note"));

        for (ProviderSessionScope wrong : List.of(
                scope("client-b", "grant-a", WORKSPACE),
                scope("client-a", "grant-b", WORKSPACE),
                scope("client-a", "grant-a", OTHER_WORKSPACE),
                new ProviderSessionScope("assistant", "client-a", "grant-a", WORKSPACE))) {
            assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                    () -> store.claim(wrong, id)).code());
        }
        assertEquals("proposal_scope_invalid", assertThrows(ProviderFailure.class,
                () -> store.issue(owner, proposal(OTHER_WORKSPACE, "note"))).code());
        assertEquals("provider_scope_invalid", assertThrows(ProviderFailure.class,
                () -> store.issue(null, proposal(WORKSPACE, "note"))).code());
        assertEquals("provider_scope_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(null, id)).code());
    }

    @Test
    void expiryRestartTimerRegressionAndCloseEraseState() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ReuseProposalStore store = new ReuseProposalStore(clock, Duration.ofMinutes(15),
                64, 256, 256 * 1_024);
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        String expired = store.issue(scope, proposal(WORKSPACE, "expired"));
        ReuseProposalStore.Claim expiringClaim = store.claim(scope, expired);
        clock.advance(Duration.ofMinutes(15));
        assertEquals("proposal_expired", assertThrows(ProviderFailure.class,
                expiringClaim::complete).code());
        expiringClaim.close();

        String restart = store.issue(scope, proposal(WORKSPACE, "restart"));
        ReuseProposalStore restarted = new ReuseProposalStore(clock, Duration.ofMinutes(15),
                64, 256, 256 * 1_024);
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                () -> restarted.claim(scope, restart)).code());

        clock.set(NOW.minusSeconds(1));
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                () -> store.issue(scope, proposal(WORKSPACE, "regression"))).code());
        clock.set(NOW.plus(Duration.ofMinutes(16)));
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                store::activeCount).code());

        ReuseProposalStore closeStore = new ReuseProposalStore();
        ReuseProposalStore.Claim claim = closeStore.claim(scope,
                closeStore.issue(scope, proposal(WORKSPACE, "close")));
        closeStore.close();
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                claim::proposal).code());
        claim.close();
    }

    @Test
    void countAndByteQuotasDoNotEvictExistingProposals() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ReuseProposalStore store = new ReuseProposalStore(clock, Duration.ofMinutes(15),
                2, 3, 4_096);
        ProviderSessionScope first = scope("client-a", "grant", WORKSPACE);
        ProviderSessionScope second = scope("client-b", "grant", WORKSPACE);
        ProviderSessionScope third = scope("client-c", "grant", WORKSPACE);
        String one = store.issue(first, proposal(WORKSPACE, "one"));
        String two = store.issue(first, proposal(WORKSPACE, "two"));
        assertEquals("proposal_quota_exceeded", assertThrows(ProviderFailure.class,
                () -> store.issue(first, proposal(WORKSPACE, "three"))).code());
        String other = store.issue(second, proposal(WORKSPACE, "other"));
        assertEquals("proposal_quota_exceeded", assertThrows(ProviderFailure.class,
                () -> store.issue(third, proposal(WORKSPACE, "third"))).code());
        assertEquals("proposal_too_large", assertThrows(ProviderFailure.class,
                () -> store.issue(third, proposal(WORKSPACE, "x".repeat(5_000)))).code());

        for (ScopedId item : List.of(new ScopedId(first, one), new ScopedId(first, two),
                new ScopedId(second, other))) {
            try (ReuseProposalStore.Claim claim = store.claim(item.scope(), item.id())) {
                ReuseOperation.AddMapping operation =
                        (ReuseOperation.AddMapping) claim.proposal().operation();
                assertTrue(operation.mappingCells().containsKey("comment"));
            }
        }
    }

    @Test
    void defaultByteBoundaryAcceptsBoundedPlanAndRejectsOversizedPlan() throws Exception {
        assertEquals(Duration.ofMinutes(15), ReuseProposalStore.DEFAULT_TTL);
        assertEquals(64, ReuseProposalStore.DEFAULT_MAX_PER_PRINCIPAL);
        assertEquals(256, ReuseProposalStore.DEFAULT_MAX_BACKEND);
        assertEquals(256 * 1_024, ReuseProposalStore.DEFAULT_MAX_BYTES);
        ReuseProposalStore store = new ReuseProposalStore();
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        ReuseProposal accepted = proposalWithExtensions(WORKSPACE, 4, 45_000);
        String id = store.issue(scope, accepted);
        try (ReuseProposalStore.Claim claim = store.claim(scope, id)) {
            assertEquals(accepted.proposalFingerprint(), claim.proposal().proposalFingerprint());
        }

        ReuseProposal oversized = proposalWithExtensions(WORKSPACE, 4, 65_300);
        assertEquals("proposal_too_large", assertThrows(ProviderFailure.class,
                () -> store.issue(scope, oversized)).code());
        try (ReuseProposalStore.Claim claim = store.claim(scope, id)) {
            assertEquals(accepted, claim.proposal());
        }
    }

    @Test
    void principalQuotaSpansWorkspaces() throws Exception {
        ReuseProposalStore store = new ReuseProposalStore(new MutableClock(NOW),
                Duration.ofMinutes(15), 1, 4, 256 * 1_024);
        ProviderSessionScope first = scope("client", "grant", WORKSPACE);
        ProviderSessionScope second = scope("client", "grant", OTHER_WORKSPACE);
        store.issue(first, proposal(WORKSPACE, "first"));
        assertEquals("proposal_quota_exceeded", assertThrows(ProviderFailure.class,
                () -> store.issue(second, proposal(OTHER_WORKSPACE, "second"))).code());
    }

    @Test
    void revocationClearAndWorkspaceCloseInvalidateOutstandingClaims() throws Exception {
        ReuseProposalStore store = new ReuseProposalStore();
        ProviderSessionScope first = scope("client-a", "grant-1", WORKSPACE);
        ProviderSessionScope second = scope("client-a", "grant-2", WORKSPACE);
        ProviderSessionScope third = scope("client-b", "grant-3", OTHER_WORKSPACE);
        String one = store.issue(first, proposal(WORKSPACE, "one"));
        String two = store.issue(second, proposal(WORKSPACE, "two"));
        String three = store.issue(third, proposal(OTHER_WORKSPACE, "three"));

        ReuseProposalStore.Claim claim = store.claim(first, one);
        assertEquals(1, store.revokeGrant("client-a", "grant-1"));
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                claim::proposal).code());
        claim.close();
        assertEquals(1, store.revokeClient("client-a"));
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(second, two)).code());
        assertEquals(1, store.clearWorkspace(OTHER_WORKSPACE));
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(third, three)).code());

        store.issue(first, proposal(WORKSPACE, "clear"));
        store.clear();
        assertEquals(0, store.activeCount());
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        ReuseProposalStore store = new ReuseProposalStore();
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        String id = store.issue(scope, proposal(WORKSPACE, "note"));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> task = () -> {
                start.await();
                try (ReuseProposalStore.Claim claim = store.claim(scope, id)) {
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
            assertEquals(Set.of("winner", "proposal_in_use"),
                    Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completeCloseAndRevocationRacesAreLinearizable() throws Exception {
        ReuseProposalStore store = new ReuseProposalStore();
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        ReuseProposalStore.Claim closeRace = store.claim(scope,
                store.issue(scope, proposal(WORKSPACE, "close-race")));
        CountDownLatch closeStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> completed = executor.submit(() -> {
                closeStart.await();
                try {
                    closeRace.complete();
                    return "completed";
                } catch (IllegalStateException closed) {
                    return "closed";
                }
            });
            Future<String> closed = executor.submit(() -> {
                closeStart.await();
                closeRace.close();
                return "closed";
            });
            closeStart.countDown();
            assertTrue(Set.of("completed", "closed").contains(completed.get(10, TimeUnit.SECONDS)));
            assertEquals("closed", closed.get(10, TimeUnit.SECONDS));
            store.clear();

            ReuseProposalStore.Claim revokeRace = store.claim(scope,
                    store.issue(scope, proposal(WORKSPACE, "revoke-race")));
            CountDownLatch revokeStart = new CountDownLatch(1);
            Future<String> consume = executor.submit(() -> {
                revokeStart.await();
                try {
                    revokeRace.complete();
                    return "completed";
                } catch (ProviderFailure revoked) {
                    return revoked.code();
                }
            });
            Future<Integer> revoke = executor.submit(() -> {
                revokeStart.await();
                return store.revokeGrant("client", "grant");
            });
            revokeStart.countDown();
            String outcome = consume.get(10, TimeUnit.SECONDS);
            int removed = revoke.get(10, TimeUnit.SECONDS);
            assertTrue(outcome.equals("completed") || outcome.equals("proposal_invalid"));
            assertTrue(removed == 0 || removed == 1);
            assertEquals(outcome.equals("completed") ? 0 : 1, removed);
            assertEquals(0, store.activeCount());
            revokeRace.close();
        } finally {
            closeRace.close();
            executor.shutdownNow();
        }
    }

    @Test
    void mintReceiptSurvivesClaimRollbackAndIsConsumedWithProposal() throws Exception {
        ReuseProposalStore store = new ReuseProposalStore();
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        ReuseProposal proposal = mintProposal(WORKSPACE);
        String id = store.issue(scope, proposal);
        ModelRevision base = proposal.inputIdentity().modelRevision();
        ReuseMintReceipt receipt = ReuseMintReceipt.create(proposal.proposalFingerprint(),
                LOCAL, base, new ModelRevision(WORKSPACE, 8,
                        "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64)),
                null, null, null);

        try (ReuseProposalStore.Claim claim = store.claim(scope, id)) {
            assertEquals(null, claim.mintReceipt());
            try (ReuseProposalStore.MintLease lease = claim.beginMint()) {
                lease.recordMint(receipt);
            }
            assertEquals(receipt, claim.mintReceipt());
            try (ReuseProposalStore.MintLease lease = claim.beginMint()) {
                assertThrows(IllegalStateException.class, () -> lease.recordMint(
                        ReuseMintReceipt.create(proposal.proposalFingerprint(), LOCAL, base,
                                new ModelRevision(WORKSPACE, 9,
                                        "sha256:" + "e".repeat(64),
                                        "sha256:" + "f".repeat(64)), null, null, null)));
            }
        }
        try (ReuseProposalStore.Claim claim = store.claim(scope, id)) {
            assertEquals(receipt, claim.mintReceipt());
            claim.complete();
        }
        assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, id)).code());

        String addId = store.issue(scope, proposal(WORKSPACE, "add"));
        try (ReuseProposalStore.Claim claim = store.claim(scope, addId)) {
            assertThrows(IllegalStateException.class, claim::beginMint);
        }
    }

    @Test
    void mintLeaseFreezesExpiryAndPublishesReceiptWithRenewal() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        ReuseProposalStore store = new ReuseProposalStore(clock, Duration.ofMinutes(1),
                4, 8, 256 * 1_024);
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        ReuseProposal proposal = mintProposal(WORKSPACE);
        String id = store.issue(scope, proposal);
        ReuseProposalStore.Claim claim = store.claim(scope, id);
        ReuseProposalStore.MintLease lease = claim.beginMint();
        clock.advance(Duration.ofMinutes(2));
        assertEquals("proposal_in_use", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, id)).code());
        claim.close();

        assertEquals(proposal, lease.proposal());
        ReuseMintReceipt receipt = ReuseMintReceipt.create(proposal.proposalFingerprint(),
                LOCAL, proposal.inputIdentity().modelRevision(),
                new ModelRevision(WORKSPACE, 8, "sha256:" + "c".repeat(64),
                        "sha256:" + "d".repeat(64)), null, null, null);
        lease.recordMint(receipt);
        lease.close();

        clock.advance(Duration.ofSeconds(59));
        try (ReuseProposalStore.Claim resumed = store.claim(scope, id)) {
            assertEquals(receipt, resumed.mintReceipt());
        }
        clock.advance(Duration.ofSeconds(1));
        assertEquals("proposal_expired", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, id)).code());

        String abandonedId = store.issue(scope, mintProposal(WORKSPACE));
        ReuseProposalStore.Claim abandonedClaim = store.claim(scope, abandonedId);
        ReuseProposalStore.MintLease abandoned = abandonedClaim.beginMint();
        clock.advance(Duration.ofMinutes(2));
        abandoned.close();
        abandonedClaim.close();
        assertEquals("proposal_expired", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, abandonedId)).code());

        ReuseProposal mismatchedProposal = mintProposal(WORKSPACE);
        String mismatchedId = store.issue(scope, mismatchedProposal);
        ReuseProposalStore.Claim mismatchedClaim = store.claim(scope, mismatchedId);
        try (ReuseProposalStore.MintLease mismatched = mismatchedClaim.beginMint()) {
            ReuseMintReceipt wrong = ReuseMintReceipt.create(
                    mismatchedProposal.proposalFingerprint(), EXTERNAL,
                    mismatchedProposal.inputIdentity().modelRevision(),
                    new ModelRevision(WORKSPACE, 9, "sha256:" + "e".repeat(64),
                            "sha256:" + "f".repeat(64)), null, null, null);
            assertThrows(IllegalArgumentException.class, () -> mismatched.recordMint(wrong));
        }
        clock.advance(Duration.ofMinutes(2));
        mismatchedClaim.close();
        assertEquals("proposal_expired", assertThrows(ProviderFailure.class,
                () -> store.claim(scope, mismatchedId)).code());
    }

    @Test
    void deferredClaimCloseCannotExposeRenewedProposalBeforeReceipt() throws Exception {
        CountDownLatch publishReached = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        ReuseProposalStore store = new ReuseProposalStore(() -> 0L, Duration.ofMinutes(1),
                4, 8, 256 * 1_024, () -> {
                    publishReached.countDown();
                    try {
                        if (!allowPublish.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("receipt publish barrier timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("receipt publish barrier interrupted",
                                interrupted);
                    }
                });
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        ReuseProposal proposal = mintProposal(WORKSPACE);
        String id = store.issue(scope, proposal);
        ReuseProposalStore.Claim original = store.claim(scope, id);
        ReuseProposalStore.MintLease lease = original.beginMint();
        original.close();
        ReuseMintReceipt receipt = ReuseMintReceipt.create(proposal.proposalFingerprint(),
                LOCAL, proposal.inputIdentity().modelRevision(),
                new ModelRevision(WORKSPACE, 8, "sha256:" + "c".repeat(64),
                        "sha256:" + "d".repeat(64)), null, null, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> publisher = executor.submit(() -> {
                lease.recordMint(receipt);
                return true;
            });
            assertTrue(publishReached.await(10, TimeUnit.SECONDS));
            CountDownLatch successorClaimed = new CountDownLatch(1);
            Future<Boolean> consumer = executor.submit(() -> {
                while (true) {
                    try (ReuseProposalStore.Claim resumed = store.claim(scope, id)) {
                        successorClaimed.countDown();
                        resumed.complete();
                        return true;
                    } catch (ProviderFailure inUse) {
                        if (!"proposal_in_use".equals(inUse.code())) throw inUse;
                        Thread.yield();
                    }
                }
            });
            assertFalse(successorClaimed.await(100, TimeUnit.MILLISECONDS));
            allowPublish.countDown();
            assertTrue(publisher.get(10, TimeUnit.SECONDS));
            assertTrue(successorClaimed.await(10, TimeUnit.SECONDS));
            assertTrue(consumer.get(10, TimeUnit.SECONDS));
            assertEquals("proposal_invalid", assertThrows(ProviderFailure.class,
                    () -> store.claim(scope, id)).code());
        } finally {
            allowPublish.countDown();
            lease.close();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentOriginalReaderCannotDeadlockReceiptPublication() throws Exception {
        CountDownLatch publishReached = new CountDownLatch(1);
        CountDownLatch allowPublish = new CountDownLatch(1);
        ReuseProposalStore store = new ReuseProposalStore(() -> 0L, Duration.ofMinutes(1),
                4, 8, 256 * 1_024, () -> {
                    publishReached.countDown();
                    try {
                        if (!allowPublish.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("receipt publish barrier timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("receipt publish barrier interrupted",
                                interrupted);
                    }
                });
        ProviderSessionScope scope = scope("client", "grant", WORKSPACE);
        ReuseProposal proposal = mintProposal(WORKSPACE);
        String id = store.issue(scope, proposal);
        ReuseProposalStore.Claim claim = store.claim(scope, id);
        ReuseProposalStore.MintLease lease = claim.beginMint();
        ReuseMintReceipt receipt = ReuseMintReceipt.create(proposal.proposalFingerprint(),
                LOCAL, proposal.inputIdentity().modelRevision(),
                new ModelRevision(WORKSPACE, 8, "sha256:" + "c".repeat(64),
                        "sha256:" + "d".repeat(64)), null, null, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> publisher = executor.submit(() -> {
                lease.recordMint(receipt);
                return true;
            });
            assertTrue(publishReached.await(10, TimeUnit.SECONDS));
            Future<ReuseMintReceipt> reader = executor.submit(claim::mintReceipt);
            allowPublish.countDown();
            assertTrue(publisher.get(10, TimeUnit.SECONDS));
            ReuseMintReceipt observed = reader.get(10, TimeUnit.SECONDS);
            assertTrue(observed == null || receipt.equals(observed));
            assertEquals(receipt, claim.mintReceipt());
        } finally {
            allowPublish.countDown();
            lease.close();
            claim.close();
            executor.shutdownNow();
        }
    }

    private static ProviderSessionScope scope(String client, String grant, String workspace) {
        return new ProviderSessionScope("oauth", client, grant, workspace);
    }

    private static ReuseProposal proposal(String workspace, String note) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("subject_id", LOCAL);
        mapping.put("predicate_id", "skos:exactMatch");
        mapping.put("object_id", EXTERNAL);
        mapping.put("mapping_justification", "semapv:ManualMappingCuration");
        mapping.put("comment", note);
        return proposal(workspace, mapping);
    }

    private static ReuseProposal proposalWithExtensions(String workspace, int count, int length) {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("subject_id", LOCAL);
        mapping.put("predicate_id", "skos:exactMatch");
        mapping.put("object_id", EXTERNAL);
        mapping.put("mapping_justification", "semapv:ManualMappingCuration");
        for (int index = 0; index < count; index++) {
            mapping.put("extension_" + index, "x".repeat(length));
        }
        return proposal(workspace, mapping);
    }

    private static ReuseProposal proposal(String workspace, Map<String, String> mapping) {
        ModelRevision revision = new ModelRevision(workspace, 7, "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64));
        ProviderResult result = result();
        ReuseProposalInputIdentity identity = ReuseProposalInputIdentity.create(result, "en",
                revision, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64),
                targetIdentity());
        return ReuseProposal.create(result, identity, new ReuseOperation.AddMapping(mapping));
    }

    private static ReuseProposal mintProposal(String workspace) {
        ModelRevision revision = new ModelRevision(workspace, 7, "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64));
        ProviderResult result = result();
        ReuseProposalInputIdentity identity = ReuseProposalInputIdentity.create(result, "en",
                revision, "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64),
                targetIdentity());
        return ReuseProposal.create(result, identity,
                new ReuseOperation.MintLocalWithMapping(LOCAL,
                        ReuseOperation.MintedEntityType.CLASS,
                        List.of(new ProviderResult.LocalizedText("Local term", "en")),
                        Map.of("subject_id", LOCAL, "predicate_id", "skos:exactMatch",
                                "object_id", EXTERNAL,
                                "mapping_justification", "semapv:ManualMappingCuration")));
    }

    private static ProviderResult result() {
        return ProviderResult.create("fake", "fake", "efo", "https://example.org/efo.owl",
                EXTERNAL, "class",
                List.of(new ProviderResult.LocalizedText("External term", "en")),
                List.of(), List.of("Description"), "CC0", "fixture",
                "direct match", 1.0, "1", NOW,
                URI.create("https://example.org/term"), 0, false, null);
    }

    private static ReuseProposalTargetIdentity targetIdentity() {
        return ReuseProposalTargetIdentity.create("/project", "/project/policy.yaml",
                "/project/mappings.tsv", false);
    }

    private record ScopedId(ProviderSessionScope scope, String id) { }

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
}
