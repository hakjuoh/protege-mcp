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
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
    /**
     * How many unattested obligations the journal hands on, mirroring the registry's own bound on the ones
     * it can name: the journal carries what a generation could still name and no more.
     *
     * <p>A generation whose tally outran that bound - 256 endpoints released unproven while their processes
     * still reported alive - hands the ones it can name on as they are, and each further one as the process
     * that owed it, which is the same bound again over pids. Nothing is dropped for a window whose process
     * can still be named: a pid is the whole of what settles an obligation for a window that will not come
     * back, so folding the overflow onto its process costs the successor the window id in a failure list and
     * nothing about whether the fence is owed. These bounds are this file's own and fill from every
     * generation that wrote to it, so a journal can be full while the registry handing it obligations is
     * not - the fold is what a generation's first unnamable obligation takes, not only its 257th.
     *
     * <p>An obligation neither bound can take - 256 windows on file and then 256 processes, none of them
     * settled - is not dropped and not carried as a bare number either. The write refuses, which refuses the
     * shutdown that asked for it, so the obligation stays in the memory that still fences it and this broker
     * stays up holding it. A number is what this bound exists not to carry: an obligation no successor could
     * name or ever discharge, a journal that can never be compacted, and revocations refused outright once it
     * fills. Refusing to end is the cost a bound may impose; ending having forgotten is not.
     */
    static final int MAX_UNATTESTED_OBLIGATIONS = InstanceRegistry.MAX_UNATTESTED_WINDOWS;

    private final InstanceRegistry registry;
    private final Consumer<String> savePending;
    /**
     * Trailing tokens are rejected: a journal is one document, and a file that carries a second one after
     * it has been corrupted or rewritten by something else. Reading only the first would load the wrong
     * set of tombstones - the shorter prefix of a truncated rewrite - and silently drop every revocation
     * the rest of the file records, so it fails closed like any other malformed journal. A backend's fence
     * reply is one document too, and one with something appended is not an acknowledgement.
     */
    private final ObjectMapper mapper =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, PendingRevocation> pending = new ConcurrentHashMap<>();
    private final Object persistenceLock = new Object();
    private long journalRevision;
    private long oauthConfirmedRevision = -1;
    /**
     * Endpoints an earlier broker generation released on age alone: named, so this one can say what is
     * owed, and carrying the pid that owned each, so it can find out when it stops being owed. Loaded from
     * the journal, because the memory of the generation that watched the endpoint go is what a shutdown
     * ends. Guarded by {@link #persistenceLock} like every other journal state.
     *
     * <p>An obligation is settled by the one thing that answers it: the process is gone, or that window of
     * it is a fence target here again. Neither is a guess, and one of them eventually happens - so the
     * record is durable without being permanent, which matters because nothing else clears it and a fence
     * owed forever would refuse every revocation this machine ever makes again.
     *
     * <p>One record per window of a process, which is the unit both of those proofs are about - and the unit
     * a result names, since it reports failures by window id. Two incarnations of one window are therefore
     * one obligation here where the registry that watched them go counted two: what the journal can carry is
     * what a successor could name and settle, and an incarnation's identity is its port and its per-window
     * secret, which no successor can verify and this file will not hold. Both are settled by the same proof
     * regardless, so the record is never discharged while either is unproven.
     */
    private final Map<String, InstanceRegistry.UnattestedEndpoint> durableObligations =
            new LinkedHashMap<>();
    /**
     * The same obligations for windows an earlier generation could not name, carried as the processes that
     * owed them: one entry per process, whatever number of its windows overflowed that generation's id
     * bound. Settled by that process exiting and by nothing else - the window is not recorded, so no
     * registration here can be that window coming back - which is a proof that still eventually arrives.
     *
     * <p>Counted as one owed window each, which is what such a record can honestly claim: it says a fence
     * is still owed and cannot say for how many. Under-counting the number keeps a revocation unconfirmed
     * exactly as the exact count would, and the alternative - a number with no way to discharge it - is the
     * latch {@link #MAX_UNATTESTED_OBLIGATIONS} explains. Guarded by {@link #persistenceLock}.
     */
    private final Set<Long> durableProcessObligations = new LinkedHashSet<>();
    /**
     * What is outstanding, for the reads that happen off the persistence lock: a fanout asks while holding
     * its own revocation's monitor, and taking {@link #persistenceLock} there would nest the two locks the
     * opposite way round from {@link #remember}. A snapshot that is read a tick out of date over-reports a
     * settled obligation at worst, which keeps a revocation unconfirmed - the safe side.
     */
    private volatile DurableObligations durable = DurableObligations.NONE;
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
        // An endpoint released on age alone was never proven stopped, so its fence is still owed: an empty
        // registry says the broker has nothing registered, not that everything it once held has stopped.
        // The obligation is recorded in the journal itself rather than only in this broker's memory,
        // because that memory is what a shutdown ends: a successor loads the tombstones with no idea an
        // endpoint of a dead generation was released unproven, and its own quiet shutdown would compact
        // away a fence obligation nothing ever discharged. It travels named and with its pid, so the
        // successor can both say what is owed and see it settled - by that process dying, or by that window
        // of it registering again into reach of the fence. One this generation could no longer name travels
        // as the process that owed it, so a bound on names never leaves an obligation as nothing at all.
        // Recorded whether or not tombstones are pending: this
        // obligation is about an endpoint, not about a credential, and the in-generation tally counts it
        // against revocations that came after it just the same, so a journal written only when something
        // happened to be pending would hand on an obligation for some shutdowns and drop it for others.
        //
        // Read outside the lock: both registry reads take the registry's monitor, and this runs under it
        // already (sealIfShouldExit calls it), so taking persistenceLock first would nest the two the other
        // way round from every other path and invite a deadlock.
        boolean owed = registry.unattestedEndpointCount() > 0;
        List<InstanceRegistry.UnattestedEndpoint> named = registry.unattestedObligations();
        List<Long> byProcess = registry.unattestedProcessObligations();
        synchronized (persistenceLock) {
            if (owed) {
                rememberObligationsLocked(named, byProcess);
                return;
            }
            if (!durableObligations.isEmpty() || !durableProcessObligations.isEmpty()) return;
            if (pending.isEmpty()) return;
            if (oauthConfirmedRevision != journalRevision) {
                throw new IllegalStateException("OAuth revocation replay is not durable");
            }
            persist(List.of(), List.of(), List.of());
            pending.clear();
            journalRevision++;
            oauthConfirmedRevision = journalRevision;
        }
    }

    /**
     * Discharge the obligations of dead generations that are settled, and rewrite the journal without them.
     * Called from the broker's maintenance tick with the same liveness probe the reap uses.
     *
     * <p>Two proofs, both the registry's own: the pid is gone, so nothing is serving any endpoint of it any
     * more; or that window is among the ones a fence can still be delivered to here, so the durable retry
     * reaches it and this registry carries the obligation from now on. A pid the kernel handed to something
     * else reads as alive and keeps the obligation, which is the safe way to be wrong about it.
     *
     * <p>Per obligation, not per pid: the same process registering again is not a report that its other
     * endpoints stopped - the registry quarantines a window missing from a registration precisely because
     * that is not proof - so an obligation whose window did not come back is still owed, and waits for the
     * pid. Every process exits eventually and the journal outlives the brokers that watch for it, so waiting
     * on the pid is a wait that ends.
     *
     * <p>The probe and the registry read happen before the lock is taken, because both would otherwise nest
     * a syscall or the registry monitor inside {@link #persistenceLock}, the opposite order from the seal
     * path. The journal is written before memory drops anything, so a save that fails leaves the obligation
     * exactly where it was, and an obligation recorded while this ran is kept: what is dropped is computed
     * from the map as it stands under the lock, not from the snapshot the proofs were gathered for.
     */
    void dischargeSettledObligations(LongPredicate pidAlive) {
        if (durable.count() == 0) return;
        List<InstanceRegistry.UnattestedEndpoint> owed;
        Set<Long> owedByProcess;
        synchronized (persistenceLock) {
            owed = List.copyOf(durableObligations.values());
            owedByProcess = Set.copyOf(durableProcessObligations);
        }
        Set<InstanceRegistry.UnattestedEndpoint> fenceable = registry.fenceableEndpoints();
        Map<Long, Boolean> liveness = new LinkedHashMap<>();
        Set<String> settled = new LinkedHashSet<>();
        for (InstanceRegistry.UnattestedEndpoint obligation : owed) {
            boolean alive = liveness.computeIfAbsent(obligation.pid(), pidAlive::test);
            if (!alive || fenceable.contains(obligation)) {
                settled.add(obligationKey(obligation));
            }
        }
        // A record that names only the process has one proof and not two: it never said which window
        // overflowed, so no registration here can be that window coming back, and only the process being
        // gone answers it.
        Set<Long> settledProcesses = new LinkedHashSet<>();
        for (Long pid : owedByProcess) {
            if (!liveness.computeIfAbsent(pid, pidAlive::test)) {
                settledProcesses.add(pid);
            }
        }
        if (settled.isEmpty() && settledProcesses.isEmpty()) return;
        synchronized (persistenceLock) {
            List<InstanceRegistry.UnattestedEndpoint> remaining = durableObligations.entrySet().stream()
                    .filter(entry -> !settled.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();
            List<Long> remainingProcesses = durableProcessObligations.stream()
                    .filter(pid -> !settledProcesses.contains(pid))
                    .toList();
            if (remaining.size() == durableObligations.size()
                    && remainingProcesses.size() == durableProcessObligations.size()) {
                return;
            }
            persist(new ArrayList<>(pending.values()), remaining, remainingProcesses);
            durableObligations.keySet().removeAll(settled);
            durableProcessObligations.removeAll(settledProcesses);
            publishDurableObligationsLocked();
        }
    }

    /**
     * Republish the off-lock snapshot of what the journal owes. Called under {@link #persistenceLock} after
     * every change to either durable set, and once from the load this fanout is constructed by, where no
     * other thread can see it yet - so the value a fanout reads always describes a state this journal was
     * actually in.
     */
    private void publishDurableObligationsLocked() {
        durable = new DurableObligations(durableObligations.size() + durableProcessObligations.size(),
                Set.copyOf(durableObligations.keySet()));
    }

    /**
     * Record in the journal the endpoints that left this broker unproven, so no successor compacts these
     * tombstones or answers for those endpoints. Written before the obligations are believed in memory,
     * exactly as {@link #remember} writes an entry: a save that fails leaves both unchanged, and because
     * the caller is the seal path, the throw keeps the broker up for another tick rather than exiting
     * having dropped the record. The pending set is unchanged, so the journal revision is not touched -
     * bumping it would tell {@link #replayOAuthRevocations} that state it has already confirmed needs
     * replaying again.
     *
     * <p>The registry names one obligation per endpoint incarnation and this keeps one per window of a
     * process, so several incarnations of one window fold into the record that stands for all of them - see
     * {@link #durableObligations} for why that is the unit a journal can carry. The obligations it could no
     * longer name arrive as pids and are folded the same way, one entry per process. Folding by key also
     * makes the write idempotent: a seal that runs again, or a generation that hands on obligations it
     * loaded from an earlier one, adds nothing and rewrites nothing.
     *
     * <p>These bounds are the journal's, not the registry's, and the two fill independently: a journal
     * carrying a predecessor's records leaves less room than this generation's own naming bound implies. So
     * a named obligation this file cannot take is folded onto its pid - the same ladder the registry climbs,
     * and the same trade it makes. Folded, the record keeps the proof that always eventually arrives, the
     * process being gone, and gives up the other one this obligation had, that window being a fence target
     * here again: the entry no longer says which window it is about, so nothing can recognise that window
     * coming back. What that costs is time - such an obligation stands until its process ends, where the
     * named record could also have been settled sooner - and it cannot cost correctness, because a
     * revocation waiting on the later proof answers unconfirmed for longer, never confirmed sooner. An
     * obligation that neither bound can take refuses the write outright. Refusing
     * keeps the obligation in this broker's memory and this broker running, which is what the registry does
     * with an endpoint no bound can record; dropping it would end with a successor confirming a fence for an
     * endpoint that was never proven stopped, and there is no third answer that does not forget something.
     */
    private void rememberObligationsLocked(List<InstanceRegistry.UnattestedEndpoint> named,
            List<Long> byProcess) {
        Map<String, InstanceRegistry.UnattestedEndpoint> union =
                new LinkedHashMap<>(durableObligations);
        Set<Long> processUnion = new LinkedHashSet<>(durableProcessObligations);
        List<Long> overflow = new ArrayList<>();
        for (InstanceRegistry.UnattestedEndpoint obligation : named) {
            if (union.containsKey(obligationKey(obligation))) continue;
            if (union.size() < MAX_UNATTESTED_OBLIGATIONS) {
                union.put(obligationKey(obligation), obligation);
            } else {
                overflow.add(obligation.pid());
            }
        }
        for (Long pid : byProcess) {
            if (pid != null && pid > 0) overflow.add(pid);
        }
        for (Long pid : overflow) {
            if (processUnion.contains(pid)) continue;
            if (processUnion.size() >= MAX_UNATTESTED_OBLIGATIONS) {
                throw new IllegalStateException("backend revocation obligations are not durable");
            }
            processUnion.add(pid);
        }
        if (union.size() == durableObligations.size()
                && processUnion.size() == durableProcessObligations.size()) {
            return;
        }
        persist(new ArrayList<>(pending.values()), List.copyOf(union.values()),
                List.copyOf(processUnion));
        durableObligations.clear();
        durableObligations.putAll(union);
        durableProcessObligations.clear();
        durableProcessObligations.addAll(processUnion);
        publishDurableObligationsLocked();
    }

    private static String obligationKey(InstanceRegistry.UnattestedEndpoint obligation) {
        return obligationKey(obligation.pid(), obligation.windowId());
    }

    /**
     * The unit a journal record is written in: one window of one process. Coarser than an
     * {@link InstanceRegistry.EndpointKey} on purpose — the port and secret of an endpoint incarnation mean
     * nothing to a successor, so the record folds every incarnation of a window into one obligation, and
     * everything that reads the journal has to compare it in these terms.
     */
    private static String obligationKey(long pid, String windowId) {
        return pid + " " + windowId;
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
            // Write-ahead: a failed save leaves both the token and in-memory journal unchanged. The
            // unattested obligations are rewritten as they stand, because a rewrite that dropped them
            // would let the next quiet shutdown compact obligations an earlier generation left unproven.
            persist(next, List.copyOf(durableObligations.values()),
                    List.copyOf(durableProcessObligations));
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
        Map<InstanceRegistry.EndpointKey, InstanceRegistry.RevocationTarget> targets =
                new LinkedHashMap<>();
        Set<InstanceRegistry.EndpointKey> attempted = new LinkedHashSet<>();
        registry.revocationTargets().forEach(target -> targets.put(target.key(), target));
        // Forget acknowledgements for endpoints that are no longer an obligation at all, so the set stays
        // bounded by the registry's own bounded sets as windows come and go. Endpoints the registry
        // released on age alone are still obligations and are kept: this journal is retried every second
        // for as long as the tombstone lives, and dropping the proof that this revocation's fence reached
        // one before its record expired would make the next retry read the release as an unmet obligation
        // and leave a fence that demonstrably landed unconfirmable for good.
        Set<InstanceRegistry.EndpointKey> obligations = new LinkedHashSet<>(targets.keySet());
        obligations.addAll(registry.unattestedRevocationEndpoints().keySet());
        revocation.acknowledgedTargets.retainAll(obligations);

        // Capture windows that register while the first batch is in flight too. Eight rounds is a
        // churn guard; any still-new endpoints after it remain unacknowledged, never false-success.
        for (int round = 0; round < 8; round++) {
            registry.revocationTargets().forEach(target -> targets.put(target.key(), target));
            List<InstanceRegistry.RevocationTarget> batch = targets.entrySet().stream()
                    .filter(entry -> !revocation.acknowledgedTargets.contains(entry.getKey()))
                    .filter(entry -> attempted.add(entry.getKey()))
                    .map(Map.Entry::getValue)
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
        Set<InstanceRegistry.EndpointKey> present = new LinkedHashSet<>();
        registry.revocationTargets().forEach(target -> {
            targets.put(target.key(), target);
            present.add(target.key());
        });
        Map<InstanceRegistry.EndpointKey, InstanceRegistry.UnattestedEndpoint> aged =
                registry.unattestedRevocationEndpoints();
        present.addAll(aged.keySet());
        // An endpoint that left both of those sets while this fence was in flight went one way only: the
        // OS reported its pid dead, which is the single event this registry reads as proof that the
        // backend stopped - a retired endpoint stays a target, and one released on age alone stays an
        // obligation. Nothing is listening there to serve the credential any more, so keeping it in this
        // call's cumulative set would report a window as owing a fence that has nowhere left to land,
        // and answer 503 for a process the kernel has already reaped. An obligation this registry can no
        // longer name is not dropped with it: those are counted, not listed, by the aged-out tally below.
        targets.keySet().retainAll(present);
        List<String> failures = new ArrayList<>(targets.entrySet().stream()
                .filter(entry -> !revocation.acknowledgedTargets.contains(entry.getKey()))
                .map(entry -> entry.getValue().window().id)
                .toList());
        int acknowledged = targets.size() - failures.size();
        // An endpoint the registry released on age alone was never proven stopped, and there is nothing
        // left to send a fence to. Counting it as a window that did not acknowledge is the only honest
        // reading: a result that omitted it would confirm a fence for work that may still be draining.
        //
        // Three aged records describe a window this result has already accounted for, and counting any of
        // them again would report one window as two - overstating what is unfenced as badly as understating
        // it misleads. One is an endpoint this revocation's own fence reached first: a record can age out
        // while the request to it is in flight, and proof does not expire with the record it arrived
        // through, so dropping it would leave a fence that demonstrably landed unconfirmable for good.
        // A second is an endpoint that is registered again right now - an equal key is the same live
        // endpoint by construction - and the batch above already fenced it or already named it failed.
        // The obligation stays with the registry for every other credential: nothing was fenced for those.
        DurableObligations onFile = durable;
        int agedOut = registry.unattestedEndpointCount();
        for (Map.Entry<InstanceRegistry.EndpointKey, InstanceRegistry.UnattestedEndpoint> owed
                : aged.entrySet()) {
            InstanceRegistry.UnattestedEndpoint obligation = owed.getValue();
            if (revocation.acknowledgedTargets.contains(owed.getKey())
                    || targets.containsKey(owed.getKey())) {
                agedOut--;
                continue;
            }
            // A third: the journal is already carrying this very obligation, named by the same window of the
            // same process. That happens when a window an earlier generation was owed a fence for registers
            // here and ages out again before the discharge pass that would have settled the record, and it
            // is one window owed one fence either way. The journal's count keeps it - that record outlives
            // this broker - and the in-generation tally lets it go, so the two do not report it twice.
            if (onFile.keys().contains(obligationKey(obligation))) {
                agedOut--;
            }
            if (!failures.contains(obligation.windowId())) {
                failures.add(obligation.windowId());
            }
        }
        // An obligation an earlier generation recorded is owed by an endpoint this registry never held: the
        // journal names it, and the registry that watched it go is gone. Counting each keeps the reading the
        // in-generation tally already gives - still owed, still unconfirmable - across the restart that
        // would otherwise answer "every window acknowledged" to a caller asking whether this credential is
        // fenced everywhere, with no window left to have acknowledged anything. They are settled by proof
        // and not by this call, so a fanout only reads how many are outstanding. A record that names only a
        // process counts once, which is all such a record claims: it says a fence is owed and not for how
        // many windows, and one is enough to keep the revocation unconfirmed - the reading that matters.
        //
        // A fourth, and the one that loop cannot see, because it is about a window that never aged out here
        // at all: the journal names a window this result has already counted as a target of its own. That is
        // the same overlap as the third - a window an earlier generation was owed a fence for, registered
        // here again - caught before rather than after it is released a second time, and it is still one
        // window owed one fence. It has to come off the journal's side and not this one, because the target
        // is where the fence actually went: the batch above either landed it and said so or named the window
        // failed, and the record is settled by that same reachability on the next discharge pass. Counting
        // both would answer a caller asking whether this credential is fenced everywhere with one more
        // unfenced window than the result can name, for a window that is right here and accounted for.
        // Only a named record can be recognised this way. One that stands for a process alone never said
        // which of its windows it was about, so no registration here can be that window coming back - the
        // trade that record made when it was folded - and it stays counted.
        int onFileOwed = onFile.count();
        if (!onFile.keys().isEmpty()) {
            Set<String> countedHere = new LinkedHashSet<>();
            targets.values().forEach(target ->
                    countedHere.add(obligationKey(target.pid(), target.window().id)));
            for (String owed : onFile.keys()) {
                if (countedHere.contains(owed)) {
                    onFileOwed--;
                }
            }
        }
        int owedBeyondThisRegistry = Math.max(0, agedOut) + Math.max(0, onFileOwed);
        return new Result(totalWindows(targets.size(), owedBeyondThisRegistry),
                acknowledged, failures);
    }

    /**
     * The windows one fanout accounted for: the live targets plus the obligations that aged out beyond
     * them. Summed as a long and clamped, because the aged-out tally saturates rather than wrapping and
     * adding the live targets to a saturated one is the last place this total could still turn negative -
     * which {@link Result#unacknowledged()} would read as a fence confirmed by every window that never
     * acknowledged it. Package-private so the arithmetic is testable without ageing out two billion
     * endpoints.
     */
    static int totalWindows(int targets, int agedOut) {
        return (int) Math.min(Integer.MAX_VALUE, (long) targets + Math.max(0, agedOut));
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
            // Absent means no generation left an unproven endpoint behind; present means exactly the
            // endpoints listed, each named by its window id and owned by a pid this broker can ask the OS
            // about. Anything else is a journal this broker cannot read: an entry with no usable pid records
            // an obligation nothing could ever settle, and coercing one - or reading a bare flag as some
            // unnamed number of them - would decide the question the record exists to answer by guessing.
            JsonNode unattested = root.path("unattested");
            if (!unattested.isMissingNode()) {
                if (!unattested.isArray() || unattested.size() > MAX_UNATTESTED_OBLIGATIONS) {
                    throw new IllegalArgumentException("backend revocation journal is invalid");
                }
                for (JsonNode owed : unattested) {
                    JsonNode pid = owed.path("pid");
                    String windowId = owed.path("window_id").asText("");
                    if (!pid.isIntegralNumber() || pid.longValue() <= 0 || !validId(windowId)) {
                        throw new IllegalArgumentException("backend revocation journal is invalid");
                    }
                    var obligation =
                            new InstanceRegistry.UnattestedEndpoint(windowId, pid.longValue());
                    durableObligations.putIfAbsent(obligationKey(obligation), obligation);
                }
            }
            // The obligations that generation could not name a window for, carried as the processes that
            // owed them. Held to the same reading as the named ones: a list, of pids the OS can be asked
            // about, no longer than a generation could have folded - and absent where there were none. A
            // record that is not a pid is one nothing could ever discharge, so it is not a journal either.
            JsonNode unattestedProcesses = root.path("unattested_processes");
            if (!unattestedProcesses.isMissingNode()) {
                if (!unattestedProcesses.isArray()
                        || unattestedProcesses.size() > MAX_UNATTESTED_OBLIGATIONS) {
                    throw new IllegalArgumentException("backend revocation journal is invalid");
                }
                for (JsonNode pid : unattestedProcesses) {
                    if (!pid.isIntegralNumber() || pid.longValue() <= 0) {
                        throw new IllegalArgumentException("backend revocation journal is invalid");
                    }
                    durableProcessObligations.add(pid.longValue());
                }
            }
            publishDurableObligationsLocked();
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

    /**
     * Write the journal document. Both kinds of unattested obligation are only written when there are some,
     * so an ordinary journal is the same document it has always been and an older broker reading one of ours
     * sees the revocations it knows about either way.
     */
    private void persist(List<PendingRevocation> snapshot,
            List<InstanceRegistry.UnattestedEndpoint> obligations, List<Long> processObligations) {
        ObjectNode root = mapper.createObjectNode().put("version", 1);
        if (!obligations.isEmpty()) {
            var owed = root.putArray("unattested");
            obligations.forEach(obligation -> owed.addObject()
                    .put("window_id", obligation.windowId())
                    .put("pid", obligation.pid()));
        }
        if (!processObligations.isEmpty()) {
            var owedByProcess = root.putArray("unattested_processes");
            processObligations.forEach(owedByProcess::add);
        }
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
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                + window.port + BrokerControlServlet.PATH + path))
                        .timeout(timeout)
                        .header(BrokerControlServlet.BROKER_SECRET_HEADER, window.secret)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
            } catch (RuntimeException unaddressable) {
                // A registered endpoint whose port or secret cannot even be put into a request is an
                // endpoint this fence cannot reach - the same standing as one that refused the POST, and
                // the honest answer is the one every other unreachable backend gets. Letting the failure
                // out instead would abort the whole fanout on a 500 with no accounting, and because the
                // tombstone is already durable it would then throw again on every retry, taking the rest
                // of the broker's maintenance tick with it.
                pending.add(CompletableFuture.completedFuture(new WindowResult(target.key(), false)));
                continue;
            }
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
            // Only the JSON boolean literal counts. Coercing "true" or 1 would read a reply this broker
            // does not recognise as proof that a fence was installed, and a reply it does not recognise
            // is not from a backend that installed one - the same reading the trailing-token strictness
            // above already applies to the shape of the document.
            JsonNode confirmed = mapper.readTree(response.body()).path("commit_fence_confirmed");
            return confirmed.isBoolean() && confirmed.booleanValue();
        } catch (java.io.IOException malformed) {
            return false;
        }
    }

    record Result(int windows, int acknowledged, List<String> failedWindowIds) {
        Result {
            failedWindowIds = Collections.unmodifiableList(new ArrayList<>(failedWindowIds));
        }

        /**
         * Whether every window owed this fence acknowledged it. Read off {@link #unacknowledged()} rather
         * than the id list alone, so the two can never disagree: an obligation past the registry's id
         * bound is owed without being nameable, and reporting that as confirmed because no id was
         * available would claim a fence that nothing acknowledged.
         */
        boolean confirmed() {
            return unacknowledged() == 0;
        }

        /**
         * How many windows did not acknowledge the fence. Counted, not derived from
         * {@link #failedWindowIds()}: the ids a registry still remembers for endpoints it released on age
         * alone are bounded, so the list can name fewer windows than are actually owed, and a caller that
         * measured the list would report the smaller number as the whole of it.
         */
        int unacknowledged() {
            return Math.max(windows - acknowledged, failedWindowIds.size());
        }
    }

    private record WindowResult(InstanceRegistry.EndpointKey target, boolean acknowledged) { }

    /**
     * What the journal owes, as one value: how many obligations are outstanding, and the keys of the ones it
     * carries by name. Published as a single reference rather than as two fields, because a fanout compares
     * the two - an obligation this journal names and this registry names again is one obligation, not two -
     * and two volatiles read a moment apart could show a key without the count that includes it, which would
     * report fewer unfenced windows than are owed. One immutable value cannot come apart that way.
     */
    private record DurableObligations(int count, Set<String> keys) {
        private static final DurableObligations NONE = new DurableObligations(0, Set.of());
    }

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
