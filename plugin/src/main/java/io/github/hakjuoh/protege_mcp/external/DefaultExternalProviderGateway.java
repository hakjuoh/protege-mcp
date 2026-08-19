package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Default provider gateway with owner-bound transport, cache, and opaque cursor handling. */
public final class DefaultExternalProviderGateway implements ExternalProviderGateway {

    private final ExternalTermProviderRegistry registry;
    private final ProviderCursorStore cursors;
    private final ProviderCalls calls;
    private final RuntimeRoots roots;
    private final TransportFactory transportFactory;
    private final Clock clock;

    public DefaultExternalProviderGateway() {
        this(new ProviderCursorStore());
    }

    DefaultExternalProviderGateway(ProviderCursorStore cursors) {
        this(cursors, (ProviderCalls) null);
    }

    DefaultExternalProviderGateway(ProviderCursorStore cursors, ExternalTermProviderRegistry registry) {
        this(cursors, null, null, null, Clock.systemUTC(), registry);
    }

    DefaultExternalProviderGateway(ProviderCursorStore cursors, ProviderCalls calls) {
        this(cursors, calls, null, null, Clock.systemUTC(), ExternalTermProviderRegistry.defaultRegistry());
    }

    DefaultExternalProviderGateway(ProviderCursorStore cursors, RuntimeRoots roots,
            TransportFactory transportFactory, Clock clock) {
        this(cursors, null, roots, transportFactory, clock, ExternalTermProviderRegistry.defaultRegistry());
    }

    DefaultExternalProviderGateway(ProviderCursorStore cursors, RuntimeRoots roots,
            TransportFactory transportFactory, Clock clock, ExternalTermProviderRegistry registry) {
        this(cursors, null, roots, transportFactory, clock, registry);
    }

    private DefaultExternalProviderGateway(ProviderCursorStore cursors, ProviderCalls calls,
            RuntimeRoots roots, TransportFactory transportFactory, Clock clock,
            ExternalTermProviderRegistry registry) {
        this.cursors = java.util.Objects.requireNonNull(cursors, "cursors");
        this.roots = roots;
        this.transportFactory = transportFactory;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.registry = registry == null ? ExternalTermProviderRegistry.defaultRegistry() : registry;
        this.calls = calls == null ? new ProviderCalls() {
            @Override
            public ProviderCallSearch search(Invocation invocation,
                    ProviderSearchRequest request) throws ProviderFailure {
                return fetchSearch(invocation, request);
            }

            @Override
            public ProviderCallInspect inspect(Invocation invocation,
                    ProviderInspectRequest request) throws ProviderFailure {
                return fetchInspect(invocation, request);
            }
        } : calls;
    }

    @Override
    public boolean supportsProfile(String profile) {
        return registry.supports(profile);
    }

    @Override
    public SearchOutcome search(ProviderSessionScope scope, ProviderSearchRequest initialRequest,
            String cursor, InvocationResolver resolver) throws ProviderFailure {
        if (scope == null || resolver == null || (initialRequest == null) == (cursor == null)) {
            throw new ProviderFailure("provider_request_invalid",
                    "Supply either a new search request or one opaque cursor", false);
        }
        if (cursor != null) {
            try (ProviderCursorStore.Claim claim = cursors.claim(scope, cursor)) {
                return executeSearch(scope, claim.request(), resolver, claim);
            }
        }
        return executeSearch(scope, initialRequest, resolver, null);
    }

    @Override
    public InspectOutcome inspect(ProviderInspectRequest request, InvocationResolver resolver)
            throws ProviderFailure {
        if (request == null || resolver == null) {
            throw new ProviderFailure("provider_request_invalid",
                    "Provider inspection request is required", false);
        }
        Invocation invocation = requireInvocation(request.providerId(), resolver);
        authorizeInspect(invocation, request);
        ProviderCallInspect call = calls.inspect(invocation, request);
        Invocation current = requireInvocation(request.providerId(), resolver);
        requireSameInvocation(invocation, current);
        authorizeInspect(current, request);
        call.finalAuthority().validate();
        return new InspectOutcome(call.result(), call.cacheHit());
    }

    private ProviderCallInspect fetchInspect(Invocation invocation, ProviderInspectRequest request)
            throws ProviderFailure {
        ExternalTermProvider selectedProvider = provider(invocation.profile());
        Runtime runtime = runtime(invocation);
        Optional<OwnerProviderCache.InspectRead> cached = invocation.cacheReadAllowed()
                ? runtime.cache().getInspectForPublication(runtime.authority(), request)
                : Optional.empty();
        if (cached.isPresent()) {
            OwnerProviderCache.InspectRead read = cached.orElseThrow();
            return new ProviderCallInspect(read.result(), true,
                    () -> runtime.cache().finalFenceInspect(runtime.authority(), read.scope(),
                            request, read.result()));
        }

        OwnerProviderCache.Acquisition acquisition = runtime.cache()
                .beginInspectAcquisition(runtime.authority(), request);
        String acquisitionScope = acquisition.scopeFingerprint();
        ProviderResult result = selectedProvider.inspect(request,
                transport(invocation, runtime, acquisition));
        if (invocation.cacheWriteAllowed()) {
            runtime.cache().putInspect(runtime.authority(), acquisition, request, result);
        } else {
            runtime.cache().discardInspect(runtime.authority(), acquisition, request, result);
        }
        return new ProviderCallInspect(result, false,
                () -> runtime.cache().finalFenceInspect(runtime.authority(), acquisitionScope,
                        request, result));
    }

    private SearchOutcome executeSearch(ProviderSessionScope scope, ProviderSearchRequest request,
            InvocationResolver resolver, ProviderCursorStore.Claim claim) throws ProviderFailure {
        Invocation invocation = requireInvocation(request.providerId(), resolver);
        authorizeSearch(invocation, request);
        ProviderCallSearch call = calls.search(invocation, request);
        ProviderPage page = call.page();
        Invocation current = requireInvocation(request.providerId(), resolver);
        requireSameInvocation(invocation, current);
        authorizeSearch(current, request);
        call.finalAuthority().validate();

        String nextCursor = null;
        if (page.continuation() == null) {
            if (claim != null) claim.complete();
        } else {
            final ProviderSearchRequest next;
            try {
                next = new ProviderSearchRequest(request.providerId(),
                        request.query(), request.ontologies(), request.language(), request.limit(),
                        page.continuation());
            } catch (IllegalArgumentException invalidContinuation) {
                throw new ProviderFailure("provider_response_invalid",
                        "Provider returned invalid continuation state", false);
            }
            nextCursor = claim == null ? cursors.issue(scope, next) : claim.advance(next);
        }
        return new SearchOutcome(invocation.providerId(), invocation.profile(),
                publicPage(page), nextCursor, call.cacheHit());
    }

    private ProviderCallSearch fetchSearch(Invocation invocation, ProviderSearchRequest request)
            throws ProviderFailure {
        ExternalTermProvider selectedProvider = provider(invocation.profile());
        Runtime runtime = runtime(invocation);
        Optional<OwnerProviderCache.SearchRead> cached = request.continuation() == null
                && invocation.cacheReadAllowed()
                ? runtime.cache().getSearchForPublication(runtime.authority(), request)
                : Optional.empty();
        if (cached.isPresent()) {
            OwnerProviderCache.SearchRead read = cached.orElseThrow();
            return new ProviderCallSearch(read.page(), true,
                    () -> runtime.cache().finalFenceSearch(runtime.authority(), read.scope(),
                            request, read.page()));
        }

        OwnerProviderCache.Acquisition acquisition = runtime.cache()
                .beginSearchAcquisition(runtime.authority(), request);
        String acquisitionScope = acquisition.scopeFingerprint();
        ProviderPage page = selectedProvider.search(request,
                transport(invocation, runtime, acquisition));
        if (request.continuation() == null && invocation.cacheWriteAllowed()) {
            runtime.cache().putSearch(runtime.authority(), acquisition, request, page);
        } else {
            runtime.cache().discardSearch(runtime.authority(), acquisition, request, page);
        }
        return new ProviderCallSearch(page, false,
                () -> runtime.cache().finalFenceSearch(runtime.authority(), acquisitionScope,
                        request, page));
    }

    private Runtime runtime(Invocation invocation) throws ProviderFailure {
        ProviderOwnerConfig.ResolvedProvider authority = resolveOwner(invocation);
        ProviderNetworkExecutor.NetworkGate networkGate = ownerBoundNetworkGate(
                authority, invocation.networkGate());
        OwnerCredentialStore credentials = authority.credential() == null ? null
                : roots == null ? new OwnerCredentialStore()
                        : new OwnerCredentialStore(roots.credentials());
        Duration ttl = invocation.cacheTtl().isZero()
                ? OwnerProviderCache.DEFAULT_TTL
                : invocation.cacheTtl();
        OwnerProviderCache cache;
        if (invocation.cacheReadAllowed() || invocation.cacheWriteAllowed()) {
            Path cacheRoot = roots == null ? ProviderLocalPaths.cache() : roots.cache();
            cache = new OwnerProviderCache(cacheRoot, credentials,
                    () -> resolveOwner(invocation), invocation.projectGate(),
                    networkGate, clock, ttl, OwnerProviderCache.DEFAULT_MAX_ENTRIES,
                    OwnerProviderCache.DEFAULT_MAX_BYTES);
        } else {
            cache = new OwnerProviderCache(credentials, () -> resolveOwner(invocation),
                    invocation.projectGate(), networkGate);
        }
        return new Runtime(authority, credentials, cache, networkGate);
    }

    /**
     * Bind project egress permission to the exact origin selected by the owner-local alias. This
     * makes that binding the provider allowlist for live transport, redirects, and cached evidence
     * without weakening the separate global host policy used by document/import fetching.
     */
    private static ProviderNetworkExecutor.NetworkGate ownerBoundNetworkGate(
            ProviderOwnerConfig.ResolvedProvider authority,
            ProviderNetworkExecutor.NetworkGate projectGate) {
        URI ownerOrigin = ProviderNetworkUris.origin(authority.origin().origin());
        return requested -> {
            final URI actual;
            try {
                actual = ProviderNetworkUris.origin(requested);
            } catch (RuntimeException invalid) {
                throw new ProviderFailure("provider_origin_unbound",
                        "Provider request escaped its exact owner origin", false);
            }
            if (!ownerOrigin.getHost().equalsIgnoreCase(actual.getHost())
                    || effectivePort(ownerOrigin) != effectivePort(actual)) {
                throw new ProviderFailure("provider_origin_unbound",
                        "Provider request escaped its exact owner origin", false);
            }
            projectGate.authorize(ownerOrigin);
        };
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private ProviderOwnerConfig.ResolvedProvider resolveOwner(Invocation invocation)
            throws ProviderFailure {
        ProviderOwnerConfig config = roots == null
                ? ProviderOwnerConfig.loadDefault() : ProviderOwnerConfig.load(roots.providers());
        return config.resolve(invocation.originAlias(),
                invocation.providerId(), invocation.profile(), invocation.credentialId(),
                invocation.projectFingerprint());
    }

    private ProviderTransport transport(Invocation invocation, Runtime runtime,
            OwnerProviderCache.Acquisition acquisition) throws ProviderFailure {
        if (transportFactory != null) {
            return transportFactory.create(runtime.authority(), runtime.credentials(),
                    runtime.networkGate(), acquisition);
        }
        return new ProviderNetworkExecutor(runtime.authority(), runtime.credentials(),
                runtime.networkGate(), acquisition);
    }

    private static Invocation requireInvocation(String providerId, InvocationResolver resolver)
            throws ProviderFailure {
        Invocation invocation = resolver.resolve(providerId);
        if (invocation == null || !providerId.equals(invocation.providerId())) {
            throw new ProviderFailure("provider_policy_changed",
                    "Project provider policy changed before execution", false);
        }
        return invocation;
    }

    private static void requireSameInvocation(Invocation before, Invocation current)
            throws ProviderFailure {
        if (!before.providerId().equals(current.providerId())
                || !before.profile().equals(current.profile())
                || !before.originAlias().equals(current.originAlias())
                || !java.util.Objects.equals(before.credentialId(), current.credentialId())
                || !before.projectFingerprint().equals(current.projectFingerprint())
                || !before.cacheTtl().equals(current.cacheTtl())
                || before.cacheReadAllowed() != current.cacheReadAllowed()
                || !before.allowedOntologies().equals(current.allowedOntologies())
                || !before.allowedLanguages().equals(current.allowedLanguages())
                || before.maxResults() != current.maxResults()) {
            throw new ProviderFailure("provider_policy_changed",
                    "Project provider policy changed before publication", false);
        }
    }

    private ExternalTermProvider provider(String profile) throws ProviderFailure {
        return registry.require(profile);
    }

    private static void authorizeSearch(Invocation invocation, ProviderSearchRequest request)
            throws ProviderFailure {
        boolean ontologyAllowed = invocation.allowedOntologies().isEmpty()
                || !request.ontologies().isEmpty()
                && invocation.allowedOntologies().containsAll(request.ontologies());
        boolean languageAllowed = invocation.allowedLanguages().isEmpty()
                || invocation.allowedLanguages().contains(request.language());
        if (!ontologyAllowed || !languageAllowed || request.limit() > invocation.maxResults()) {
            throw new ProviderFailure("provider_policy_changed",
                    "Search request is outside the current provider policy", false);
        }
    }

    private static void authorizeInspect(Invocation invocation, ProviderInspectRequest request)
            throws ProviderFailure {
        boolean ontologyAllowed = invocation.allowedOntologies().isEmpty()
                || invocation.allowedOntologies().contains(request.ontology());
        boolean languageAllowed = invocation.allowedLanguages().isEmpty()
                || invocation.allowedLanguages().contains(request.language());
        if (!ontologyAllowed || !languageAllowed) {
            throw new ProviderFailure("provider_policy_changed",
                    "Inspection request is outside the current provider policy", false);
        }
    }

    private static ProviderPage publicPage(ProviderPage page) {
        return new ProviderPage(page.items(), page.total(), null, page.fetchedAt(), page.retries());
    }

    @Override
    public void close() {
        cursors.close();
    }

    @Override
    public int revokeClient(String clientId) {
        return cursors.revokeClient(clientId);
    }

    @Override
    public int revokeGrant(String clientId, String grantId) {
        return cursors.revokeGrant(clientId, grantId);
    }

    @Override
    public int clearWorkspace(String workspaceId) {
        return cursors.clearWorkspace(workspaceId);
    }

    private record Runtime(ProviderOwnerConfig.ResolvedProvider authority,
            OwnerCredentialStore credentials, OwnerProviderCache cache,
            ProviderNetworkExecutor.NetworkGate networkGate) { }

    interface ProviderCalls {
        ProviderCallSearch search(Invocation invocation, ProviderSearchRequest request)
                throws ProviderFailure;

        ProviderCallInspect inspect(Invocation invocation, ProviderInspectRequest request)
                throws ProviderFailure;
    }

    @FunctionalInterface
    interface FinalAuthority {
        void validate() throws ProviderFailure;
    }

    record RuntimeRoots(Path providers, Path credentials, Path cache) {
        RuntimeRoots {
            java.util.Objects.requireNonNull(providers, "providers");
            java.util.Objects.requireNonNull(credentials, "credentials");
            java.util.Objects.requireNonNull(cache, "cache");
        }
    }

    @FunctionalInterface
    interface TransportFactory {
        ProviderTransport create(ProviderOwnerConfig.ResolvedProvider authority,
                OwnerCredentialStore credentials, ProviderNetworkExecutor.NetworkGate networkGate,
                OwnerProviderCache.Acquisition acquisition) throws ProviderFailure;
    }

    record ProviderCallSearch(ProviderPage page, boolean cacheHit,
            FinalAuthority finalAuthority) {
        ProviderCallSearch {
            if (page == null || finalAuthority == null) {
                throw new IllegalArgumentException("provider search call is invalid");
            }
        }
    }

    record ProviderCallInspect(ProviderResult result, boolean cacheHit,
            FinalAuthority finalAuthority) {
        ProviderCallInspect {
            if (result == null || finalAuthority == null) {
                throw new IllegalArgumentException("provider inspect call is invalid");
            }
        }
    }
}
