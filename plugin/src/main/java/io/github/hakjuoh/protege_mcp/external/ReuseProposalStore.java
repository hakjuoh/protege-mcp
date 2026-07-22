package io.github.hakjuoh.protege_mcp.external;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.hakjuoh.protege_mcp.contracts.ContractJson;

/** Restart-erased memory store for immutable proposals and their optional mint receipt. */
public final class ReuseProposalStore implements AutoCloseable {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    public static final int DEFAULT_MAX_PER_PRINCIPAL = 64;
    public static final int DEFAULT_MAX_BACKEND = 256;
    public static final int DEFAULT_MAX_BYTES = 256 * 1_024;

    private static final ObjectMapper JSON = ContractJson.mapper();

    private static final int RECEIPT_RESERVE_BYTES = 80 * 1_024;
    private static final Runnable NOOP_PUBLISH_HOOK = () -> { };

    private final ScopedEphemeralRegistry<StoredProposal> entries;
    private final Runnable beforeReceiptPublish;

    public ReuseProposalStore() {
        this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), DEFAULT_TTL,
                DEFAULT_MAX_PER_PRINCIPAL, DEFAULT_MAX_BACKEND, DEFAULT_MAX_BYTES,
                NOOP_PUBLISH_HOOK);
    }

    public ReuseProposalStore(Clock clock, Duration ttl, int maxPerPrincipal, int maxBackend,
            int maxBytes) {
        this(ticker(clock), ttl, maxPerPrincipal, maxBackend, maxBytes, NOOP_PUBLISH_HOOK);
    }

    ReuseProposalStore(LongSupplier ticker, Duration ttl, int maxPerPrincipal, int maxBackend,
            int maxBytes) {
        this(ticker, ttl, maxPerPrincipal, maxBackend, maxBytes, NOOP_PUBLISH_HOOK);
    }

    ReuseProposalStore(LongSupplier ticker, Duration ttl, int maxPerPrincipal, int maxBackend,
            int maxBytes, Runnable beforeReceiptPublish) {
        entries = new ScopedEphemeralRegistry<>("proposal", ticker, ttl, maxPerPrincipal,
                maxBackend, maxBytes, ReuseProposalStore::encodedSize);
        this.beforeReceiptPublish = Objects.requireNonNull(
                beforeReceiptPublish, "receipt publish hook");
    }

    public String issue(ProviderSessionScope scope, ReuseProposal proposal) throws ProviderFailure {
        if (scope == null) {
            throw new ProviderFailure("provider_scope_invalid",
                    "Provider session scope is missing", false);
        }
        if (proposal == null) {
            throw new ProviderFailure("proposal_invalid", "Reuse proposal is required", false);
        }
        if (!scope.workspaceId().equals(proposal.inputIdentity().modelRevision().workspaceId())) {
            throw new ProviderFailure("proposal_scope_invalid",
                    "Reuse proposal does not belong to this workspace", false);
        }
        return entries.issue(scope, new StoredProposal(proposal, beforeReceiptPublish));
    }

    /** Exclusively claim one proposal; close rolls back and complete consumes it. */
    public Claim claim(ProviderSessionScope scope, String proposalId) throws ProviderFailure {
        return new Claim(entries.claim(scope, proposalId));
    }

    public int revokeClient(String clientId) {
        return entries.revokeClient(clientId);
    }

    public int revokeGrant(String clientId, String grantId) {
        return entries.revokeGrant(clientId, grantId);
    }

    public int clearWorkspace(String workspaceId) {
        return entries.clearWorkspace(workspaceId);
    }

    public void clear() {
        entries.clear();
    }

    int activeCount() throws ProviderFailure {
        return entries.activeCount();
    }

    @Override
    public void close() {
        entries.close();
    }

    private static int encodedSize(StoredProposal stored) {
        try {
            int proposalBytes = JSON.writeValueAsBytes(stored.proposal().toJson()).length;
            int receiptBytes = stored.proposal().operation()
                    instanceof ReuseOperation.MintLocalWithMapping ? RECEIPT_RESERVE_BYTES : 0;
            return Math.addExact(proposalBytes, receiptBytes);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("proposal serialization failed", invalid);
        } catch (ArithmeticException oversized) {
            throw new IllegalArgumentException("proposal size overflow", oversized);
        }
    }

    private static int encodedReceiptSize(ReuseMintReceipt receipt) {
        try {
            return JSON.writeValueAsBytes(receipt.toJson()).length;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("mint receipt serialization failed", invalid);
        }
    }

    private static LongSupplier ticker(Clock clock) {
        Clock required = Objects.requireNonNull(clock, "clock");
        return required::millis;
    }

    /** Thread-safe claim with one terminal completion or rollback. */
    public static final class Claim implements AutoCloseable {
        private ScopedEphemeralRegistry.Claim<StoredProposal> claim;

        private Claim(ScopedEphemeralRegistry.Claim<StoredProposal> claim) {
            this.claim = claim;
        }

        public synchronized ReuseProposal proposal() throws ProviderFailure {
            return current().value().proposal();
        }

        public synchronized ReuseMintReceipt mintReceipt() throws ProviderFailure {
            return current().value().mintReceipt();
        }

        /** Freeze expiry across one ontology commit and atomically renew while publishing its receipt. */
        public synchronized MintLease beginMint() throws ProviderFailure {
            StoredProposal stored = current().value();
            if (!(stored.proposal().operation()
                    instanceof ReuseOperation.MintLocalWithMapping)) {
                throw new IllegalStateException("reuse proposal is not a mint operation");
            }
            return new MintLease(stored, current().holdExpiry());
        }

        public synchronized void complete() throws ProviderFailure {
            current().complete();
            claim = null;
        }

        @Override
        public synchronized void close() {
            if (claim == null) return;
            claim.close();
            claim = null;
        }

        private synchronized ScopedEphemeralRegistry.Claim<StoredProposal> current() {
            if (claim == null) throw new IllegalStateException("reuse proposal claim is closed");
            return claim;
        }

        @Override
        public String toString() {
            return "ReuseProposalClaim[redacted=true]";
        }
    }

    /** Commit-scoped expiry protection owned by the model-thread mint body. */
    public static final class MintLease implements AutoCloseable {
        private StoredProposal stored;
        private ScopedEphemeralRegistry.ExpiryLease expiry;

        private MintLease(StoredProposal stored, ScopedEphemeralRegistry.ExpiryLease expiry) {
            this.stored = stored;
            this.expiry = expiry;
        }

        public synchronized ReuseProposal proposal() {
            requireActive();
            return stored.proposal();
        }

        public synchronized void recordMint(ReuseMintReceipt receipt) throws ProviderFailure {
            requireActive();
            stored.validateMint(receipt);
            // Renewal deliberately leaves the registry claim and expiry hold owned. No successor
            // can observe or consume the proposal until the prevalidated receipt is published.
            expiry.renew();
            stored.beforeReceiptPublish();
            stored.recordValidatedMint(receipt);
            expiry.close();
            expiry = null;
            stored = null;
        }

        @Override
        public synchronized void close() {
            if (expiry == null) return;
            expiry.close();
            expiry = null;
            stored = null;
        }

        private void requireActive() {
            if (stored == null || expiry == null) {
                throw new IllegalStateException("reuse proposal mint lease is closed");
            }
        }

        @Override
        public String toString() {
            return "ReuseProposalMintLease[redacted=true]";
        }
    }

    private static final class StoredProposal {
        private final ReuseProposal proposal;
        private final Runnable beforeReceiptPublish;
        private ReuseMintReceipt mintReceipt;

        private StoredProposal(ReuseProposal proposal, Runnable beforeReceiptPublish) {
            this.proposal = Objects.requireNonNull(proposal, "proposal");
            this.beforeReceiptPublish = Objects.requireNonNull(beforeReceiptPublish,
                    "receipt publish hook");
        }

        synchronized ReuseProposal proposal() {
            return proposal;
        }

        synchronized ReuseMintReceipt mintReceipt() {
            return mintReceipt;
        }

        synchronized void validateMint(ReuseMintReceipt receipt) {
            Objects.requireNonNull(receipt, "receipt");
            if (encodedReceiptSize(receipt) > RECEIPT_RESERVE_BYTES) {
                throw new IllegalArgumentException("mint receipt exceeds its reserved size");
            }
            if (!proposal.proposalFingerprint().equals(receipt.proposalFingerprint())
                    || !proposal.inputIdentity().modelRevision().equals(receipt.baseRevision())
                    || !(proposal.operation() instanceof ReuseOperation.MintLocalWithMapping mint)
                    || !mint.localEntityIri().equals(receipt.entityIri())) {
                throw new IllegalArgumentException("mint receipt does not match the reuse proposal");
            }
            if (mintReceipt != null && !mintReceipt.equals(receipt)) {
                throw new IllegalStateException("reuse proposal already has a different mint receipt");
            }
        }

        synchronized void recordValidatedMint(ReuseMintReceipt receipt) {
            mintReceipt = receipt;
        }

        synchronized void beforeReceiptPublish() {
            beforeReceiptPublish.run();
        }
    }
}
