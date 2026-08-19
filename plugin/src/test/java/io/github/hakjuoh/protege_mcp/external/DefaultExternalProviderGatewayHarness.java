package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-package production-composition seam used only by external-tool integration tests. */
public final class DefaultExternalProviderGatewayHarness {

    private DefaultExternalProviderGatewayHarness() { }

    public static void rotateCredential(Path credentials, String credentialId, byte[] secret)
            throws ProviderFailure {
        new OwnerCredentialStore(credentials).rotate(credentialId, secret);
    }

    public static ExternalProviderGateway create(Path providers, Path credentials, Path cache,
            AtomicInteger calls, AtomicReference<URI> target) {
        return new DefaultExternalProviderGateway(new ProviderCursorStore(),
                new DefaultExternalProviderGateway.RuntimeRoots(providers, credentials, cache),
                (authority, store, gate, acquisition) -> request -> {
                    gate.authorize(authority.origin().origin());
                    URI requestTarget = URI.create(authority.origin().origin().toASCIIString()
                            + request.relativePath());
                    acquisition.recordSuccess(authority, authority.cacheScopeFingerprint(null),
                            requestTarget);
                    calls.incrementAndGet();
                    target.set(requestTarget);
                    String body = """
                            {"response":{"numFound":1,"start":0,"docs":[{
                              "iri":"https://example.org/EFO_1","ontology_name":"efo",
                              "label":"Cell","type":"class"}]}}
                            """;
                    return new ProviderResponse(body.getBytes(StandardCharsets.UTF_8),
                            requestTarget, Clock.systemUTC().instant(), 0);
                }, Clock.systemUTC());
    }

    public static ExternalProviderGateway createOntoPortal(Path providers, Path credentials,
            Path cache, AtomicInteger calls, AtomicReference<URI> target,
            AtomicReference<byte[]> observedSecret) {
        return new DefaultExternalProviderGateway(new ProviderCursorStore(),
                new DefaultExternalProviderGateway.RuntimeRoots(providers, credentials, cache),
                (authority, store, gate, acquisition) -> request -> {
                    gate.authorize(authority.origin().origin());
                    URI requestTarget = URI.create(authority.origin().origin().toASCIIString()
                            + request.relativePath());
                    try (OwnerCredentialStore.CredentialLease lease =
                            store.open(authority.credential().id())) {
                        observedSecret.set(lease.copySecret());
                        acquisition.recordSuccess(authority,
                                authority.cacheScopeFingerprint(lease), requestTarget);
                    }
                    calls.incrementAndGet();
                    target.set(requestTarget);
                    String body = """
                            {"page":1,"pageCount":1,"totalCount":1,"nextPage":null,
                             "collection":[{"@id":"https://example.org/EFO_1",
                             "prefLabel":"Cell","links":{"ontology":
                             "https://registry.owner-selected.example/ontologies/EFO"}}]}
                            """;
                    return new ProviderResponse(body.getBytes(StandardCharsets.UTF_8),
                            requestTarget, Clock.systemUTC().instant(), 0);
                }, Clock.systemUTC());
    }
}
