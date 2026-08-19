package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ProviderConnectionProbeTest {

    @Test
    void pendingOntoPortalCredentialAndBoundedLivenessRequestReachProductionTransportBoundary()
            throws Exception {
        byte[] supplied = "owner-secret".getBytes(StandardCharsets.US_ASCII);
        byte[] callerCopy = supplied.clone();
        AtomicReference<ProviderRequest> observedRequest = new AtomicReference<>();
        AtomicReference<byte[]> observedSecret = new AtomicReference<>();
        ProviderConnectionProbe probe = new ProviderConnectionProbe((authority, credentials) -> {
            assertEquals("ontoportal", authority.origin().profile());
            assertEquals("disease-provider", authority.providerId());
            assertEquals("project-a", authority.projectFingerprint());
            try (OwnerCredentialStore.CredentialLease lease = credentials.open("ncbo-key")) {
                observedSecret.set(lease.copySecret());
            }
            return request -> {
                observedRequest.set(request);
                return new ProviderResponse("{}".getBytes(StandardCharsets.UTF_8),
                        URI.create("https://data.bioontology.org/ontologies"),
                        Instant.parse("2026-08-18T00:00:00Z"), 1);
            };
        });
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin("ncbo", "ontoportal",
                URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig.CredentialBinding credential =
                new ProviderOwnerConfig.CredentialBinding("ncbo-key", "disease-provider", "ncbo",
                        ProviderOwnerConfig.AuthScheme.ONTOPORTAL_API_KEY,
                        "Authorization", "project-a");

        ProviderConnectionProbe.ProbeResult result = probe.probe(origin, credential, supplied);

        assertArrayEquals(callerCopy, supplied);
        assertArrayEquals(callerCopy, observedSecret.get());
        assertEquals("/ontologies", observedRequest.get().relativePath());
        assertEquals(Map.of("pagesize", "1", "format", "json", "include", "acronym",
                "include_views", "false", "display_context", "false",
                "display_links", "false"), observedRequest.get().query());
        assertEquals("https://data.bioontology.org/ontologies", result.sourceUrl());
        assertEquals(1, result.retries());
        java.util.Arrays.fill(observedSecret.get(), (byte) 0);
        java.util.Arrays.fill(callerCopy, (byte) 0);
        java.util.Arrays.fill(supplied, (byte) 0);
    }

    @Test
    void unsupportedProfileFailsBeforeTransportCreation() throws Exception {
        ProviderConnectionProbe probe = new ProviderConnectionProbe((authority, credentials) -> {
            throw new AssertionError("transport must not be created");
        });
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin("future", "future",
                URI.create("https://example.org"));

        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> probe.probe(origin, null, null));

        assertEquals("provider_profile_unsupported", failure.code());
        assertNull(failure.getCause());
    }

    @Test
    void queryApiKeyProbeReachesProductionTransportWithoutLeakingEvidenceUrl()
            throws Exception {
        AtomicReference<URI> target = new AtomicReference<>();
        ProviderConnectionProbe probe = new ProviderConnectionProbe((authority, credentials) ->
                new ProviderNetworkExecutor(authority, credentials, ignored -> { },
                        host -> new InetAddress[] { InetAddress.getByName("93.184.216.34") },
                        (requestTarget, headers, addresses) -> {
                            target.set(requestTarget);
                            return new ProviderNetworkExecutor.RawResponse(200, Map.of(),
                                    "{}".getBytes(StandardCharsets.UTF_8));
                        }, Clock.systemUTC(), ignored -> { }, null));
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig.CredentialBinding credential =
                new ProviderOwnerConfig.CredentialBinding("ncbo-key", "disease-provider", "ncbo",
                        ProviderOwnerConfig.AuthScheme.QUERY_API_KEY,
                        null, "apikey", "project-a");
        byte[] secret = "owner-secret".getBytes(StandardCharsets.US_ASCII);

        ProviderConnectionProbe.ProbeResult result = probe.probe(origin, credential, secret);

        assertTrue(java.util.Arrays.asList(target.get().getRawQuery().split("&"))
                .contains("apikey=owner-secret"));
        assertEquals("https://data.bioontology.org/ontologies", result.sourceUrl());
        java.util.Arrays.fill(secret, (byte) 0);
    }
}
