package io.github.hakjuoh.protege_mcp.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.github.hakjuoh.protege_mcp.contracts.ModelRevision;

/** Bounded, memory-only, per-backend cache of immutable change-set previews. */
final class ChangeSetStore {

    static final long DEFAULT_TTL_MILLIS = TimeUnit.MINUTES.toMillis(15);
    static final long MAX_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);
    static final int MAX_ENTRIES = 64;
    static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;

    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final ScheduledExecutorService sweeper;
    private long totalBytes;

    ChangeSetStore() {
        this(System::currentTimeMillis, true);
    }

    ChangeSetStore(LongSupplier clock, boolean automaticSweep) {
        this.clock = clock;
        if (automaticSweep) {
            sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "protege-mcp-change-set-sweeper");
                thread.setDaemon(true);
                return thread;
            });
            sweeper.scheduleAtFixedRate(this::sweepQuietly, 1, 1, TimeUnit.MINUTES);
        } else {
            sweeper = null;
        }
    }

    synchronized Entry put(Draft draft, long requestedTtlMillis) {
        sweepExpired();
        long ttl = requestedTtlMillis <= 0 ? DEFAULT_TTL_MILLIS
                : Math.min(requestedTtlMillis, MAX_TTL_MILLIS);
        long bytes = Math.max(draft.estimatedBytes, 1L);
        validateEntryBounds(draft.changes.size(), bytes);
        // Capacity eviction may only reclaim READY entries: evicting a COMMITTING entry would pull
        // the cached delta out from under an in-flight commit (mirrors sweepExpired's state check).
        // When only in-flight entries remain, the NEW preview is refused instead.
        while (entries.size() >= MAX_ENTRIES || totalBytes + bytes > MAX_TOTAL_BYTES) {
            String oldestReady = null;
            for (Entry candidate : entries.values()) {
                if (candidate.state == State.READY) {
                    oldestReady = candidate.id;
                    break;
                }
            }
            if (oldestReady == null) {
                break;
            }
            removeInternal(oldestReady);
        }
        if (entries.size() >= MAX_ENTRIES || totalBytes + bytes > MAX_TOTAL_BYTES) {
            throw new ToolArgException("change-set cache capacity is exhausted");
        }
        String id = UUID.randomUUID().toString();
        long now = clock.getAsLong();
        Entry entry = new Entry(id, now, now + ttl, bytes, draft);
        entries.put(id, entry);
        totalBytes += bytes;
        return entry;
    }

    /** Shared early/final guard: callers can reject a known oversized lower bound before QC. */
    static void validateEntryBounds(int normalizedChanges, long bytes) {
        if (bytes > MAX_ENTRY_BYTES) {
            throw new ToolArgException("change set exceeds the per-preview memory limit of "
                    + MAX_ENTRY_BYTES + " bytes (estimated " + bytes
                    + "); split the request into smaller previews");
        }
        if (normalizedChanges > ChangePlanner.MAX_OPERATIONS * 4) {
            throw new ToolArgException("normalized change set is too large: " + normalizedChanges
                    + " changes exceeds the maximum of " + (ChangePlanner.MAX_OPERATIONS * 4)
                    + "; split the request into smaller previews");
        }
    }

    synchronized Lookup claim(String id) {
        sweepExpired();
        Entry entry = entries.get(id);
        if (entry == null) {
            return new Lookup(null, "unknown_change_set");
        }
        if (entry.state != State.READY) {
            return new Lookup(null, "change_set_in_progress");
        }
        entry.state = State.COMMITTING;
        return new Lookup(entry, null);
    }

    synchronized void release(Entry entry) {
        Entry current = entries.get(entry.id);
        if (current == entry && current.state == State.COMMITTING) {
            if (current.expiresAt <= clock.getAsLong()) {
                removeInternal(entry.id);
            } else {
                current.state = State.READY;
            }
        }
    }

    synchronized void consume(Entry entry) {
        if (entries.get(entry.id) == entry) {
            removeInternal(entry.id);
        }
    }

    synchronized boolean discard(String id) {
        sweepExpired();
        Entry entry = entries.get(id);
        if (entry == null || entry.state != State.READY) {
            return false;
        }
        removeInternal(id);
        return true;
    }

    synchronized int size() {
        sweepExpired();
        return entries.size();
    }

    synchronized void dispose() {
        entries.clear();
        totalBytes = 0;
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
    }

    private synchronized void sweepQuietly() {
        try {
            sweepExpired();
        } catch (RuntimeException ignored) {
            // A daemon cleanup failure must not affect the server; the next operation retries.
        }
    }

    private void sweepExpired() {
        long now = clock.getAsLong();
        List<String> expired = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.expiresAt <= now && entry.state == State.READY) {
                expired.add(entry.id);
            }
        }
        expired.forEach(this::removeInternal);
    }

    private void removeInternal(String id) {
        Entry removed = entries.remove(id);
        if (removed != null) {
            totalBytes -= removed.estimatedBytes;
        }
    }

    static final class Entry {
        final String id;
        final long createdAt;
        final long expiresAt;
        final long estimatedBytes;
        final ModelRevision baseRevision;
        /** The preview's original policy_path argument (null = discovery), replayed at commit. */
        final String configuredPolicyPath;
        /** The policy file the preview resolved; commit refuses when re-resolution lands elsewhere. */
        final String policyPath;
        final String policyDigest;
        final String preflightDigest;
        final String importLockDigest;
        final List<NormalizedChange> changes;
        final List<Map<String, Object>> operations;
        final Map<String, Object> summary;
        final Map<String, Object> preflight;
        final boolean committable;
        final List<String> reasons;
        /** Which planner produced this entry: operations, create_terms, or create_properties. */
        final String plannerKind;
        /** The minimal raw request subset the planner needs, replayed verbatim by rebase. */
        final Map<String, Object> plannerArguments;
        /** The per-request-position resolution signature the rebase comparison pins. */
        final List<String> resolved;
        private State state = State.READY;

        Entry(String id, long createdAt, long expiresAt, long estimatedBytes, Draft draft) {
            this.id = id;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.estimatedBytes = estimatedBytes;
            this.baseRevision = draft.baseRevision;
            this.configuredPolicyPath = draft.configuredPolicyPath;
            this.policyPath = draft.policyPath;
            this.policyDigest = draft.policyDigest;
            this.preflightDigest = draft.preflightDigest;
            this.importLockDigest = draft.importLockDigest;
            this.changes = List.copyOf(draft.changes);
            this.operations = List.copyOf(draft.operations);
            this.summary = Collections.unmodifiableMap(new LinkedHashMap<>(draft.summary));
            this.preflight = draft.preflight == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(draft.preflight));
            this.committable = draft.committable;
            this.reasons = List.copyOf(draft.reasons);
            this.plannerKind = draft.plannerKind;
            this.plannerArguments = Collections.unmodifiableMap(
                    new LinkedHashMap<>(draft.plannerArguments));
            this.resolved = List.copyOf(draft.resolved);
        }

        String expiresAtIso() {
            return Instant.ofEpochMilli(expiresAt).toString();
        }
    }

    record Draft(ModelRevision baseRevision, String configuredPolicyPath, String policyPath,
            String policyDigest, String preflightDigest, String importLockDigest,
            List<NormalizedChange> changes, List<Map<String, Object>> operations,
            Map<String, Object> summary, Map<String, Object> preflight, boolean committable,
            List<String> reasons, String plannerKind, Map<String, Object> plannerArguments,
            List<String> resolved, long estimatedBytes) { }

    record Lookup(Entry entry, String error) { }

    private enum State { READY, COMMITTING }
}
