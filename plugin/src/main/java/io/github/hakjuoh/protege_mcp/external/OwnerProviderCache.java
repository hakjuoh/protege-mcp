package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Owner-authorized cache for fully validated provider pages and term evidence. */
public final class OwnerProviderCache {

    public static final int DEFAULT_MAX_ENTRIES = 256;
    public static final int DEFAULT_MAX_BYTES = 32 * 1_024 * 1_024;
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final OwnerCredentialStore credentials;
    private final AuthorityLoader authorityLoader;
    private final ProjectGate projectGate;
    private final ProviderNetworkExecutor.NetworkGate networkGate;
    private final ProviderCacheStore store;

    /**
     * Create a cache whose every operation reloads the owner binding from the canonical owner home.
     */
    public OwnerProviderCache(String originAlias, String providerId, String profile,
            String credentialId, String projectFingerprint,
            ProjectGate projectGate,
            ProviderNetworkExecutor.NetworkGate networkGate) throws ProviderFailure {
        this(ProviderLocalPaths.cache(), new OwnerCredentialStore(),
                () -> ProviderOwnerConfig.loadDefault().resolve(originAlias, providerId, profile,
                        credentialId, projectFingerprint),
                projectGate, networkGate, Clock.systemUTC(), DEFAULT_TTL,
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
    }

    OwnerProviderCache(Path root, OwnerCredentialStore credentials, AuthorityLoader authorityLoader,
            ProjectGate projectGate, ProviderNetworkExecutor.NetworkGate networkGate,
            Clock clock, Duration ttl, int maxEntries, int maxBytes) throws ProviderFailure {
        requireConfiguration(credentials, authorityLoader, projectGate, networkGate, clock, ttl,
                maxEntries, maxBytes);
        this.credentials = credentials;
        this.authorityLoader = authorityLoader;
        this.projectGate = projectGate;
        this.networkGate = networkGate;
        this.store = new ProviderCacheStore(root, clock, ttl, maxEntries, maxBytes);
    }

    /**
     * Bind one provider operation to the owner authority and credential generation that must be
     * used by its network executor and by its eventual cache publication.
     */
    public Acquisition beginSearchAcquisition(ProviderOwnerConfig.ResolvedProvider expected,
            ProviderSearchRequest request) throws ProviderFailure {
        requireProvider(expected, request == null ? null : request.providerId());
        return beginAcquisition(expected, ProviderCacheStore.Kind.SEARCH, searchIdentity(request));
    }

    public Acquisition beginInspectAcquisition(ProviderOwnerConfig.ResolvedProvider expected,
            ProviderInspectRequest request) throws ProviderFailure {
        requireProvider(expected, request == null ? null : request.providerId());
        return beginAcquisition(expected, ProviderCacheStore.Kind.INSPECT,
                inspectIdentity(request));
    }

    private Acquisition beginAcquisition(ProviderOwnerConfig.ResolvedProvider expected,
            ProviderCacheStore.Kind kind, List<String> identity) throws ProviderFailure {
        try (ScopeLease current = revalidate(expected)) {
            authorize(current.authority().origin().origin());
            return new Acquisition(this, current.authority(), current.fingerprint(), kind,
                    acquisitionIdentity(kind, identity));
        }
    }

    public Optional<ProviderPage> getSearch(ProviderOwnerConfig.ResolvedProvider expected,
            ProviderSearchRequest request) throws ProviderFailure {
        requireProvider(expected, request == null ? null : request.providerId());
        byte[] queryCanary = request.query().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            List<String> identity = searchIdentity(request);
            byte[] payload;
            String beforeScope;
            try (ScopeLease before = revalidate(expected)) {
                authorize(before.authority().origin().origin());
                beforeScope = before.fingerprint();
                payload = store.get(ProviderCacheStore.Kind.SEARCH, beforeScope, identity,
                        before.secretInternal(), queryCanary).orElse(null);
            }
            if (payload == null) return Optional.empty();
            try {
                try (ScopeLease after = revalidate(expected)) {
                    authorize(after.authority().origin().origin());
                    if (!beforeScope.equals(after.fingerprint())) return Optional.empty();
                    ProviderPage page = decodePage(payload);
                    validatePage(after.authority(), request, page);
                    authorize(page.items());
                    return Optional.of(page);
                }
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
        } finally {
            Arrays.fill(queryCanary, (byte) 0);
        }
    }

    public Optional<ProviderResult> getInspect(ProviderOwnerConfig.ResolvedProvider expected,
            ProviderInspectRequest request) throws ProviderFailure {
        requireProvider(expected, request == null ? null : request.providerId());
        List<String> identity = inspectIdentity(request);
        byte[] payload;
        String beforeScope;
        try (ScopeLease before = revalidate(expected)) {
            authorize(before.authority().origin().origin());
            beforeScope = before.fingerprint();
            payload = store.get(ProviderCacheStore.Kind.INSPECT, beforeScope, identity,
                    before.secretInternal(), null).orElse(null);
        }
        if (payload == null) return Optional.empty();
        try {
            try (ScopeLease after = revalidate(expected)) {
                authorize(after.authority().origin().origin());
                if (!beforeScope.equals(after.fingerprint())) return Optional.empty();
                ProviderResult result = decodeResult(payload);
                validateResult(after.authority(), request, result);
                authorize(List.of(result));
                return Optional.of(result);
            }
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Publish one terminal search page. Returns false when otherwise valid evidence is deliberately
     * non-cacheable (cursor-bearing, sensitive, or over a configured bound). Every call consumes
     * its acquisition, including a false result or later validation failure.
     */
    public boolean putSearch(ProviderOwnerConfig.ResolvedProvider expected, Acquisition acquisition,
            ProviderSearchRequest request, ProviderPage page) throws ProviderFailure {
        requireProvider(expected, request == null ? null : request.providerId());
        Publication publication = consume(acquisition, expected, ProviderCacheStore.Kind.SEARCH,
                searchIdentity(request));
        validatePage(expected, request, page);
        if (page.continuation() != null || publication.crossOrigin()) return false;
        authorize(page.items());
        byte[] payload = encodePage(page);
        if (payload == null) return false;
        byte[] queryCanary = request.query().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            return put(ProviderCacheStore.Kind.SEARCH, expected, publication.scope(),
                    searchIdentity(request), payload, queryCanary);
        } finally {
            Arrays.fill(payload, (byte) 0);
            Arrays.fill(queryCanary, (byte) 0);
        }
    }

    /**
     * Publish one inspected term. Returns false when valid evidence is sensitive or over a bound.
     * Every call consumes its acquisition, including a false result or later validation failure.
     */
    public boolean putInspect(ProviderOwnerConfig.ResolvedProvider expected,
            Acquisition acquisition, ProviderInspectRequest request, ProviderResult result)
            throws ProviderFailure {
        requireProvider(expected, request == null ? null : request.providerId());
        Publication publication = consume(acquisition, expected, ProviderCacheStore.Kind.INSPECT,
                inspectIdentity(request));
        validateResult(expected, request, result);
        if (publication.crossOrigin()) return false;
        authorize(List.of(result));
        byte[] payload = encodeResult(result);
        if (payload == null) return false;
        try {
            return put(ProviderCacheStore.Kind.INSPECT, expected, publication.scope(),
                    inspectIdentity(request), payload, null);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private boolean put(ProviderCacheStore.Kind kind,
            ProviderOwnerConfig.ResolvedProvider expected, String acquisitionScope,
            List<String> identity, byte[] payload, byte[] queryCanary) throws ProviderFailure {
        try (ScopeLease first = revalidate(expected)) {
            authorize(first.authority().origin().origin());
            if (!acquisitionScope.equals(first.fingerprint())) throw staleAcquisition();
            if (!ProviderCacheSafety.safe(payload, first.secretInternal(), queryCanary)) return false;
            try (ScopeLease current = revalidate(expected)) {
                authorize(current.authority().origin().origin());
                if (!first.fingerprint().equals(current.fingerprint())) throw staleAcquisition();
                return store.put(kind, current.fingerprint(), identity, payload,
                        current.secretInternal(), queryCanary);
            }
        }
    }

    private Publication consume(Acquisition acquisition,
            ProviderOwnerConfig.ResolvedProvider expected,
            ProviderCacheStore.Kind kind, List<String> identity) throws ProviderFailure {
        if (acquisition == null) throw staleAcquisition();
        return acquisition.consume(this, expected, kind, acquisitionIdentity(kind, identity));
    }

    private void authorizeNetworkAttempt(Acquisition acquisition,
            ProviderOwnerConfig.ResolvedProvider candidate, String candidateScope, URI target)
            throws ProviderFailure {
        try {
            acquisition.requireSnapshot(candidate, candidateScope);
            try (ScopeLease live = revalidate(candidate)) {
                authorize(live.authority().origin().origin());
                acquisition.requireSnapshot(live.authority(), live.fingerprint());
                if (!candidateScope.equals(live.fingerprint())) throw staleAcquisition();
                URI targetOrigin = ProviderNetworkUris.origin(target);
                authorize(targetOrigin);
                acquisition.noteOrigin(targetOrigin);
            }
        } catch (ProviderFailure failure) {
            acquisition.invalidate();
            throw failure;
        } catch (RuntimeException failure) {
            acquisition.invalidate();
            throw authorityChanged();
        }
    }

    private ScopeLease revalidate(ProviderOwnerConfig.ResolvedProvider expected)
            throws ProviderFailure {
        if (expected == null) throw authorityChanged();
        ProviderOwnerConfig.ResolvedProvider current = authorityLoader.resolve();
        if (!sameBinding(expected, current)) throw authorityChanged();
        authorizeProject(current);
        OwnerCredentialStore.CredentialLease lease = null;
        byte[] secret = null;
        try {
            if (current.credential() != null) {
                lease = credentials.open(current.credential().id());
                secret = lease.copySecret();
            }
            String fingerprint = current.cacheScopeFingerprint(lease);
            return new ScopeLease(current, fingerprint, lease, secret);
        } catch (ProviderFailure failure) {
            if (secret != null) Arrays.fill(secret, (byte) 0);
            if (lease != null) lease.close();
            throw failure;
        }
    }

    private void authorizeProject(ProviderOwnerConfig.ResolvedProvider current)
            throws ProviderFailure {
        try {
            String fresh = projectGate.currentFingerprint(current);
            if (!current.projectFingerprint().equals(fresh)) throw new IllegalStateException();
        } catch (ProviderFailure | RuntimeException denied) {
            throw new ProviderFailure("provider_policy_changed",
                    "Project provider policy changed before cache access", false);
        }
    }

    private static boolean sameBinding(ProviderOwnerConfig.ResolvedProvider expected,
            ProviderOwnerConfig.ResolvedProvider current) {
        return current != null && expected.providerId().equals(current.providerId())
                && expected.projectFingerprint().equals(current.projectFingerprint())
                && expected.origin().equals(current.origin())
                && java.util.Objects.equals(expected.credential(), current.credential());
    }

    private void authorize(URI uri) throws ProviderFailure {
        try {
            networkGate.authorize(ProviderNetworkUris.origin(uri));
        } catch (ProviderFailure | RuntimeException denied) {
            throw new ProviderFailure("provider_network_denied",
                    "Provider network authority denied cached evidence", false);
        }
    }

    private void authorize(List<ProviderResult> results) throws ProviderFailure {
        Set<URI> origins = new HashSet<>();
        for (ProviderResult result : results) {
            try {
                origins.add(ProviderNetworkUris.origin(result.sourceUrl()));
            } catch (RuntimeException invalid) {
                throw cacheFailure("Provider cache source URL is invalid");
            }
        }
        for (URI origin : origins) authorize(origin);
    }

    private static void requireProvider(ProviderOwnerConfig.ResolvedProvider expected,
            String requestProvider) throws ProviderFailure {
        if (expected == null || requestProvider == null
                || !expected.providerId().equals(requestProvider)) throw authorityChanged();
    }

    private static void validatePage(ProviderOwnerConfig.ResolvedProvider authority,
            ProviderSearchRequest request, ProviderPage page) throws ProviderFailure {
        if (page == null) throw cacheFailure("Provider cache page is missing");
        if (page.items().size() > request.limit()) {
            throw cacheFailure("Provider cache page exceeds the requested limit");
        }
        for (ProviderResult result : page.items()) {
            validateResult(authority, result);
            if (!request.ontologies().isEmpty()
                    && !request.ontologies().contains(result.sourceOntology())) {
                throw cacheFailure("Provider cache evidence escaped its ontology filter");
            }
        }
    }

    private static void validateResult(ProviderOwnerConfig.ResolvedProvider authority,
            ProviderResult result) throws ProviderFailure {
        if (authority == null || result == null
                || !authority.providerId().equals(result.providerId())
                || !authority.origin().profile().equals(result.profile())) {
            throw cacheFailure("Provider cache evidence escaped its binding");
        }
    }

    private static void validateResult(ProviderOwnerConfig.ResolvedProvider authority,
            ProviderInspectRequest request, ProviderResult result) throws ProviderFailure {
        validateResult(authority, result);
        if (!request.ontology().equals(result.sourceOntology())
                || !request.iri().equals(result.entityIri())) {
            throw cacheFailure("Provider cache evidence does not match the inspect request");
        }
    }

    private static ProviderPage decodePage(byte[] payload) throws ProviderFailure {
        try {
            return ProviderCacheCodec.decodePage(payload);
        } catch (IOException | RuntimeException invalid) {
            throw cacheFailure("Provider cache page is invalid");
        }
    }

    private static ProviderResult decodeResult(byte[] payload) throws ProviderFailure {
        try {
            return ProviderCacheCodec.decodeResult(payload);
        } catch (IOException | RuntimeException invalid) {
            throw cacheFailure("Provider cache evidence is invalid");
        }
    }

    private static byte[] encodePage(ProviderPage page) {
        try {
            return ProviderCacheCodec.encodePage(page);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private static byte[] encodeResult(ProviderResult result) {
        try {
            return ProviderCacheCodec.encodeResult(result);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private static List<String> searchIdentity(ProviderSearchRequest request) {
        if (request == null) throw new IllegalArgumentException("search request is missing");
        List<String> values = new ArrayList<>();
        values.add(request.providerId());
        values.add(request.query());
        values.add(Integer.toString(request.ontologies().size()));
        values.addAll(request.ontologies());
        values.add(request.language());
        values.add(Integer.toString(request.limit()));
        values.add(request.continuation() == null ? "" : request.continuation());
        return values;
    }

    private static List<String> inspectIdentity(ProviderInspectRequest request) {
        if (request == null) throw new IllegalArgumentException("inspect request is missing");
        return List.of(request.providerId(), request.ontology(), request.iri(), request.language());
    }

    private static String acquisitionIdentity(ProviderCacheStore.Kind kind,
            List<String> identity) {
        List<String> values = new ArrayList<>(identity.size() + 1);
        values.add(kind.name());
        values.addAll(identity);
        return ProviderRequestIdentity.digest(values);
    }

    private static void requireConfiguration(OwnerCredentialStore credentials,
            AuthorityLoader authorityLoader, ProjectGate projectGate,
            ProviderNetworkExecutor.NetworkGate networkGate, Clock clock, Duration ttl,
            int maxEntries, int maxBytes) {
        if (credentials == null || authorityLoader == null || projectGate == null
                || networkGate == null || clock == null
                || ttl == null || ttl.isZero() || ttl.isNegative()
                || ttl.compareTo(Duration.ofHours(1)) > 0
                || maxEntries < 1 || maxEntries > DEFAULT_MAX_ENTRIES
                || maxBytes < 4_096 || maxBytes > DEFAULT_MAX_BYTES) {
            throw new IllegalArgumentException("provider cache configuration is invalid");
        }
    }

    private static ProviderFailure authorityChanged() {
        return new ProviderFailure("provider_authority_changed",
                "Provider owner authority changed before cache access", false);
    }

    private static ProviderFailure cacheFailure(String message) {
        return new ProviderFailure("provider_cache_invalid", message, false);
    }

    private static ProviderFailure staleAcquisition() {
        return new ProviderFailure("provider_acquisition_stale",
                "Provider evidence was not acquired under the current owner authority", false);
    }

    @FunctionalInterface
    interface AuthorityLoader {
        ProviderOwnerConfig.ResolvedProvider resolve() throws ProviderFailure;
    }

    /** Reloads the active project policy and returns its current complete fingerprint. */
    @FunctionalInterface
    public interface ProjectGate {
        String currentFingerprint(ProviderOwnerConfig.ResolvedProvider authority)
                throws ProviderFailure;
    }

    /** Opaque, single-publication cache authority shared with the restricted network executor. */
    public static final class Acquisition {
        private final OwnerProviderCache owner;
        private final ProviderOwnerConfig.ResolvedProvider authority;
        private final String scope;
        private final ProviderCacheStore.Kind kind;
        private final String requestFingerprint;
        private final URI ownerNetworkOrigin;
        private boolean crossOrigin;
        private boolean networkSucceeded;
        private boolean invalidated;
        private boolean consumed;

        private Acquisition(OwnerProviderCache owner,
                ProviderOwnerConfig.ResolvedProvider authority, String scope,
                ProviderCacheStore.Kind kind, String requestFingerprint) {
            this.owner = owner;
            this.authority = authority;
            this.scope = scope;
            this.kind = kind;
            this.requestFingerprint = requestFingerprint;
            this.ownerNetworkOrigin = ProviderNetworkUris.origin(authority.origin().origin());
        }

        void authorizeAttempt(ProviderOwnerConfig.ResolvedProvider candidate,
                String candidateScope, URI target) throws ProviderFailure {
            owner.authorizeNetworkAttempt(this, candidate, candidateScope, target);
        }

        void recordSuccess(ProviderOwnerConfig.ResolvedProvider candidate,
                String candidateScope, URI target) throws ProviderFailure {
            owner.authorizeNetworkAttempt(this, candidate, candidateScope, target);
            synchronized (this) {
                requireSnapshot(candidate, candidateScope);
                networkSucceeded = true;
            }
        }

        private synchronized void requireSnapshot(
                ProviderOwnerConfig.ResolvedProvider candidate, String candidateScope)
                throws ProviderFailure {
            if (consumed || invalidated || !sameBinding(authority, candidate)
                    || !scope.equals(candidateScope)) throw staleAcquisition();
        }

        private synchronized void invalidate() {
            invalidated = true;
        }

        private synchronized void noteOrigin(URI origin) {
            if (!ownerNetworkOrigin.equals(origin)) crossOrigin = true;
        }

        private synchronized Publication consume(OwnerProviderCache candidateOwner,
                ProviderOwnerConfig.ResolvedProvider candidateAuthority,
                ProviderCacheStore.Kind candidateKind, String candidateRequestFingerprint)
                throws ProviderFailure {
            if (consumed || invalidated || !networkSucceeded || owner != candidateOwner
                    || !sameBinding(authority, candidateAuthority) || kind != candidateKind
                    || !requestFingerprint.equals(candidateRequestFingerprint)) {
                invalidated = true;
                throw staleAcquisition();
            }
            consumed = true;
            return new Publication(scope, crossOrigin);
        }

        @Override
        public String toString() {
            return "ProviderAcquisition[redacted=true]";
        }
    }

    private record Publication(String scope, boolean crossOrigin) { }

    private static final class ScopeLease implements AutoCloseable {
        private final ProviderOwnerConfig.ResolvedProvider authority;
        private final String fingerprint;
        private OwnerCredentialStore.CredentialLease lease;
        private byte[] secret;

        private ScopeLease(ProviderOwnerConfig.ResolvedProvider authority, String fingerprint,
                OwnerCredentialStore.CredentialLease lease, byte[] secret) {
            this.authority = authority;
            this.fingerprint = fingerprint;
            this.lease = lease;
            this.secret = secret;
        }

        ProviderOwnerConfig.ResolvedProvider authority() { return authority; }
        String fingerprint() { return fingerprint; }
        byte[] secretInternal() { return secret; }

        @Override
        public void close() {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
                secret = null;
            }
            if (lease != null) {
                lease.close();
                lease = null;
            }
        }
    }
}
