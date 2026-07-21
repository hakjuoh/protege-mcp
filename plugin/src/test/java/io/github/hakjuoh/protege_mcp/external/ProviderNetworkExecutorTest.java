package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderNetworkExecutorTest {

    @TempDir
    Path temporary;

    @Test
    void exactOriginPinnedDnsCredentialAndSanitizedSourceAreEnforced() throws Exception {
        Fixture fixture = fixture(true, false);
        List<URI> targets = new ArrayList<>();
        ProviderNetworkExecutor.HttpEngine engine = (target, headers, addresses) -> {
            targets.add(target);
            assertEquals("Bearer canary-secret", headers.get("Authorization"));
            assertEquals("93.184.216.34", addresses[0].getHostAddress());
            return response(200, "{\"ok\":true}");
        };
        AtomicInteger gates = new AtomicInteger();
        ProviderNetworkExecutor executor = fixture.executor(
                origin -> gates.incrementAndGet(), host -> publicAddress(), engine, delay -> { });

        ProviderResponse result = executor.get(new ProviderRequest("/api/search",
                Map.of("z", "last value", "q", "sensitive query")));

        assertEquals(1, gates.get());
        assertEquals("https://example.org/ols4/api/search?q=sensitive%20query&z=last%20value",
                targets.get(0).toString());
        assertEquals("https://example.org/ols4/api/search", result.sourceUrl().toString());
        assertEquals(0, result.retries());
        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), result.body());
    }

    @Test
    void privateAndChangedDnsAnswersFailBeforeConnectAndEveryRetryIsReauthorized()
            throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger engineCalls = new AtomicInteger();
        ProviderNetworkExecutor.HttpEngine engine = (target, headers, addresses) -> {
            engineCalls.incrementAndGet();
            throw new IOException("first attempt fails");
        };
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger gates = new AtomicInteger();
        ProviderNetworkExecutor executor = fixture.executor(origin -> gates.incrementAndGet(), host ->
                resolutions.getAndIncrement() == 0 ? publicAddress() : privateAddress(), engine,
                delay -> { });

        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> executor.get(new ProviderRequest("/api/search", Map.of("q", "term"))));
        assertEquals("provider_address_refused", failure.code());
        assertEquals(2, gates.get());
        assertEquals(2, resolutions.get());
        assertEquals(1, engineCalls.get());

        ProviderNetworkExecutor refused = fixture.executor(origin -> { }, host -> privateAddress(),
                (target, headers, addresses) -> { throw new AssertionError("must not connect"); },
                delay -> { });
        assertEquals("provider_address_refused", assertThrows(ProviderFailure.class,
                () -> refused.get(new ProviderRequest("/api/search", Map.of()))).code());
    }

    @Test
    void retryAfterIsCappedAndRetriesAreDisclosed() throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger calls = new AtomicInteger();
        ProviderNetworkExecutor.HttpEngine engine = (target, headers, addresses) -> switch (
                calls.getAndIncrement()) {
            case 0 -> throw new IOException("transient");
            case 1 -> new ProviderNetworkExecutor.RawResponse(429,
                    Map.of("Retry-After", "99"), new byte[0]);
            default -> response(200, "{}");
        };
        List<Duration> sleeps = new ArrayList<>();
        AtomicInteger gates = new AtomicInteger();
        ProviderNetworkExecutor executor = fixture.executor(origin -> gates.incrementAndGet(),
                host -> publicAddress(), engine, sleeps::add);

        ProviderResponse result = executor.get(new ProviderRequest("/api/search", Map.of()));
        assertEquals(2, result.retries());
        assertEquals(3, calls.get());
        assertEquals(3, gates.get());
        assertEquals(List.of(Duration.ofMillis(250), Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void redirectsStatusesAndSecretEchoesBecomeContentFreeTypedFailures() throws Exception {
        Fixture credentialed = fixture(true, false);
        ProviderNetworkExecutor redirect = credentialed.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> new ProviderNetworkExecutor.RawResponse(
                        302, Map.of("Location", "https://attacker.example/token"), new byte[0]),
                delay -> { });
        ProviderFailure redirectFailure = assertThrows(ProviderFailure.class,
                () -> redirect.get(new ProviderRequest("/api", Map.of("q", "private-query"))));
        assertEquals("provider_redirect_refused", redirectFailure.code());
        assertFalse(redirectFailure.toString().contains("attacker"));
        assertFalse(redirectFailure.toString().contains("private-query"));

        ProviderNetworkExecutor echo = credentialed.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> response(200, "vendor canary-secret echo"),
                delay -> { });
        ProviderFailure redaction = assertThrows(ProviderFailure.class,
                () -> echo.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_redaction_failed", redaction.code());
        assertFalse(redaction.toString().contains("canary-secret"));
        assertNull(redaction.getCause());

        Fixture anonymous = fixture(false, false);
        ProviderNetworkExecutor missing = anonymous.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> response(404, "secret vendor error"), delay -> { });
        ProviderFailure notFound = assertThrows(ProviderFailure.class,
                () -> missing.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_term_not_found", notFound.code());
        assertFalse(notFound.toString().contains("vendor"));
    }

    @Test
    void loopbackWorksOnlyForExplicitTestBindingAndBodiesRemainBounded() throws Exception {
        Fixture loopback = fixture(false, true);
        ProviderNetworkExecutor allowed = loopback.executor(origin -> { }, host -> loopbackAddress(),
                (target, headers, addresses) -> response(200, "{}"), delay -> { });
        assertTrue(allowed.get(new ProviderRequest("/api", Map.of())).body().length > 0);

        Fixture publicBinding = fixture(false, false);
        ProviderNetworkExecutor refused = publicBinding.executor(origin -> { }, host -> loopbackAddress(),
                (target, headers, addresses) -> response(200, "{}"), delay -> { });
        assertEquals("provider_address_refused", assertThrows(ProviderFailure.class,
                () -> refused.get(new ProviderRequest("/api", Map.of()))).code());

        assertThrows(IllegalArgumentException.class, () -> new ProviderNetworkExecutor.RawResponse(
                200, Map.of(), new byte[ProviderResponse.MAX_BODY_BYTES + 1]));
    }

    @Test
    void transportExceptionTextAndCausesNeverCrossTheBoundary() throws Exception {
        Fixture fixture = fixture(true, false);
        ProviderNetworkExecutor executor = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    throw new IOException("canary-secret " + target);
                }, delay -> { });
        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> executor.get(new ProviderRequest("/api", Map.of("q", "secret-query"))));
        assertEquals("provider_transport_failed", failure.code());
        assertTrue(failure.retryable());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("canary-secret"));
        assertFalse(failure.toString().contains("secret-query"));
    }

    @Test
    void dnsFailuresAreRetriedButPolicyDenialsAndInvalidAnswersAreNot() throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger gates = new AtomicInteger();
        ProviderNetworkExecutor recovered = fixture.executor(origin -> gates.incrementAndGet(), host -> {
            if (resolutions.getAndIncrement() < 2) throw new IOException("resolver detail");
            return publicAddress();
        }, (target, headers, addresses) -> response(200, "{}"), delay -> { });
        assertEquals(2, recovered.get(new ProviderRequest("/api", Map.of())).retries());
        assertEquals(3, resolutions.get());
        assertEquals(3, gates.get());

        AtomicInteger engineCalls = new AtomicInteger();
        ProviderNetworkExecutor exhausted = fixture.executor(origin -> { }, host -> {
            throw new IOException("sensitive resolver detail");
        }, (target, headers, addresses) -> {
            engineCalls.incrementAndGet();
            return response(200, "{}");
        }, delay -> { });
        ProviderFailure dns = assertThrows(ProviderFailure.class,
                () -> exhausted.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_dns_failed", dns.code());
        assertEquals(3, dns.details().get("attempts"));
        assertFalse(dns.toString().contains("sensitive"));
        assertEquals(0, engineCalls.get());

        AtomicInteger forbiddenResolution = new AtomicInteger();
        ProviderNetworkExecutor denied = fixture.executor(origin -> {
            throw new ProviderFailure("provider_network_denied", "Provider egress denied", false);
        }, host -> {
            forbiddenResolution.incrementAndGet();
            return publicAddress();
        }, (target, headers, addresses) -> response(200, "{}"), delay -> { });
        assertEquals("provider_network_denied", assertThrows(ProviderFailure.class,
                () -> denied.get(new ProviderRequest("/api", Map.of()))).code());
        assertEquals(0, forbiddenResolution.get());
    }

    @Test
    void addressSetsAreAllOrNothingAndBounded() throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger engineCalls = new AtomicInteger();
        ProviderNetworkExecutor.HttpEngine engine = (target, headers, addresses) -> {
            engineCalls.incrementAndGet();
            return response(200, "{}");
        };
        InetAddress[] mixed = {publicAddress()[0], privateAddress()[0]};
        ProviderNetworkExecutor mixedExecutor = fixture.executor(origin -> { }, host -> mixed,
                engine, delay -> { });
        assertEquals("provider_address_refused", assertThrows(ProviderFailure.class,
                () -> mixedExecutor.get(new ProviderRequest("/api", Map.of()))).code());

        InetAddress[] tooMany = new InetAddress[17];
        java.util.Arrays.fill(tooMany, publicAddress()[0]);
        ProviderNetworkExecutor oversized = fixture.executor(origin -> { }, host -> tooMany,
                engine, delay -> { });
        assertEquals("provider_address_refused", assertThrows(ProviderFailure.class,
                () -> oversized.get(new ProviderRequest("/api", Map.of()))).code());
        assertEquals(0, engineCalls.get());
    }

    @Test
    void terminalStatusRetryRulesAndInterruptedBackoffAreStable() throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger unauthorizedCalls = new AtomicInteger();
        ProviderNetworkExecutor unauthorized = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    unauthorizedCalls.incrementAndGet();
                    return response(401, "vendor detail");
                }, delay -> { });
        ProviderFailure auth = assertThrows(ProviderFailure.class,
                () -> unauthorized.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_authorization_failed", auth.code());
        assertEquals(1, unauthorizedCalls.get());

        AtomicInteger unavailableCalls = new AtomicInteger();
        ProviderNetworkExecutor unavailable = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    unavailableCalls.incrementAndGet();
                    return response(503, "vendor detail");
                }, delay -> { });
        ProviderFailure server = assertThrows(ProviderFailure.class,
                () -> unavailable.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_http_error", server.code());
        assertTrue(server.retryable());
        assertEquals(3, server.details().get("attempts"));
        assertEquals(3, unavailableCalls.get());

        ProviderNetworkExecutor interrupted = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> new ProviderNetworkExecutor.RawResponse(429,
                        Map.of("Retry-After", "1"), new byte[0]),
                delay -> { throw new InterruptedException(); });
        try {
            ProviderFailure failure = assertThrows(ProviderFailure.class,
                    () -> interrupted.get(new ProviderRequest("/api", Map.of())));
            assertEquals("provider_retry_interrupted", failure.code());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void longSourcePathsAndAmbiguousResponseHeadersFailClosed() throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger calls = new AtomicInteger();
        ProviderNetworkExecutor executor = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    calls.incrementAndGet();
                    return response(200, "{}");
                }, delay -> { });
        String path = "/" + "a".repeat(ProviderRequest.MAX_PATH_LENGTH - 1);
        ProviderFailure invalid = assertThrows(ProviderFailure.class,
                () -> executor.get(new ProviderRequest(path, Map.of())));
        assertEquals("provider_request_invalid", invalid.code());
        assertEquals(0, calls.get());

        assertThrows(IllegalArgumentException.class,
                () -> new ProviderNetworkExecutor.RawResponse(200,
                        Map.of("Retry-After", "1", "retry-after", "2"), new byte[0]));
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        ProviderNetworkExecutor.RawResponse response = new ProviderNetworkExecutor.RawResponse(
                200, Map.of("Retry-After", "1"), body);
        body[0] = 'x';
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), response.body());
    }

    @Test
    void anonymousRedirectsAreReauthorizedRepinnedBoundedAndSanitized() throws Exception {
        Fixture fixture = fixture(false, false);
        List<URI> targets = new ArrayList<>();
        List<URI> gates = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ProviderNetworkExecutor executor = fixture.executor(gates::add, host -> publicAddress(),
                (target, headers, addresses) -> {
                    targets.add(target);
                    assertFalse(headers.containsKey("Authorization"));
                    if (calls.getAndIncrement() == 0) {
                        return new ProviderNetworkExecutor.RawResponse(302,
                                Map.of("Location", "https://redirect.example/new?q=opaque"),
                                new byte[0]);
                    }
                    return response(200, "{}");
                }, delay -> { });

        ProviderResponse response = executor.get(new ProviderRequest("/api", Map.of("q", "term")));
        assertEquals(List.of(URI.create("https://example.org"),
                URI.create("https://redirect.example")), gates);
        assertEquals("https://redirect.example/new?q=opaque", targets.get(1).toString());
        assertEquals("https://redirect.example/new", response.sourceUrl().toString());
        assertEquals(2, calls.get());

        AtomicInteger loopCalls = new AtomicInteger();
        ProviderNetworkExecutor loop = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    loopCalls.incrementAndGet();
                    return new ProviderNetworkExecutor.RawResponse(302,
                            Map.of("location", "/again"), new byte[0]);
                }, delay -> { });
        assertEquals("provider_redirect_refused", assertThrows(ProviderFailure.class,
                () -> loop.get(new ProviderRequest("/api", Map.of()))).code());
        assertEquals(ProviderNetworkExecutor.MAX_REDIRECTS + 1, loopCalls.get());
    }

    @Test
    void unsafeRedirectsAndTaintedGateFailuresFailClosed() throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger calls = new AtomicInteger();
        ProviderNetworkExecutor privateRedirect = fixture.executor(origin -> { }, host ->
                host.equals("example.org") ? publicAddress() : privateAddress(),
                (target, headers, addresses) -> {
                    calls.incrementAndGet();
                    return new ProviderNetworkExecutor.RawResponse(302,
                            Map.of("location", "https://internal.example/secret"), new byte[0]);
                }, delay -> { });
        assertEquals("provider_address_refused", assertThrows(ProviderFailure.class,
                () -> privateRedirect.get(new ProviderRequest("/api", Map.of()))).code());
        assertEquals(1, calls.get());

        ProviderNetworkExecutor cleartext = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> new ProviderNetworkExecutor.RawResponse(302,
                        Map.of("location", "http://example.org/insecure"), new byte[0]),
                delay -> { });
        assertEquals("provider_redirect_refused", assertThrows(ProviderFailure.class,
                () -> cleartext.get(new ProviderRequest("/api", Map.of()))).code());

        for (ProviderNetworkExecutor.NetworkGate gate : List.<ProviderNetworkExecutor.NetworkGate>of(
                origin -> { throw new RuntimeException("secret gate path"); },
                origin -> { throw new ProviderFailure("provider_gate_raw",
                        "secret gate path", true, Map.of("secret", "value"),
                        new IOException("cause")); })) {
            ProviderNetworkExecutor denied = fixture.executor(gate, host -> publicAddress(),
                    (target, headers, addresses) -> response(200, "{}"), delay -> { });
            ProviderFailure failure = assertThrows(ProviderFailure.class,
                    () -> denied.get(new ProviderRequest("/api", Map.of())));
            assertEquals("provider_network_denied", failure.code());
            assertFalse(failure.retryable());
            assertTrue(failure.details().isEmpty());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("secret"));
        }
    }

    @Test
    void everyRetryReopensCredentialGenerationAndDeletionAborts() throws Exception {
        Fixture rotated = fixture(true, false);
        AtomicInteger calls = new AtomicInteger();
        List<String> authorization = new ArrayList<>();
        ProviderNetworkExecutor executor = rotated.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    authorization.add(headers.get("Authorization"));
                    if (calls.getAndIncrement() == 0) throw new IOException("retry");
                    return response(200, "old canary-secret echoed after rotation");
                }, delay -> {
                    try {
                        rotated.credentials.rotate("token",
                                "replacement-secret".getBytes(StandardCharsets.US_ASCII));
                    } catch (ProviderFailure failure) {
                        throw new AssertionError(failure);
                    }
                });
        ProviderFailure oldEcho = assertThrows(ProviderFailure.class,
                () -> executor.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_redaction_failed", oldEcho.code());
        assertFalse(oldEcho.toString().contains("canary-secret"));
        assertEquals(List.of("Bearer canary-secret", "Bearer replacement-secret"), authorization);

        Fixture deleted = fixture(true, false);
        AtomicInteger deletedCalls = new AtomicInteger();
        ProviderNetworkExecutor deletedExecutor = deleted.executor(origin -> { },
                host -> publicAddress(), (target, headers, addresses) -> {
                    deletedCalls.incrementAndGet();
                    throw new IOException("retry");
                }, delay -> {
                    try {
                        deleted.credentials.delete("token");
                    } catch (ProviderFailure failure) {
                        throw new AssertionError(failure);
                    }
                });
        ProviderFailure missing = assertThrows(ProviderFailure.class,
                () -> deletedExecutor.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_credential_missing", missing.code());
        assertEquals(1, deletedCalls.get());
    }

    @Test
    void malformedResponsesFailOnceAndDateRetryAfterIsCapped() throws Exception {
        Fixture fixture = fixture(false, false);
        AtomicInteger invalidCalls = new AtomicInteger();
        ProviderNetworkExecutor invalid = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    invalidCalls.incrementAndGet();
                    throw new ProviderNetworkExecutor.InvalidResponseException();
                }, delay -> { });
        ProviderFailure malformed = assertThrows(ProviderFailure.class,
                () -> invalid.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_response_invalid", malformed.code());
        assertFalse(malformed.retryable());
        assertEquals(1, invalidCalls.get());

        AtomicInteger dateCalls = new AtomicInteger();
        List<Duration> sleeps = new ArrayList<>();
        ProviderNetworkExecutor dated = fixture.executor(origin -> { }, host -> publicAddress(),
                (target, headers, addresses) -> dateCalls.getAndIncrement() == 0
                        ? new ProviderNetworkExecutor.RawResponse(429,
                                Map.of("Retry-After", "Tue, 21 Jul 2026 00:00:30 GMT"),
                                new byte[0])
                        : response(200, "{}"), sleeps::add);
        dated.get(new ProviderRequest("/api", Map.of()));
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void canarySearchRemainsLinearForRepeatedPrefixesAtPublishedBounds() {
        byte[] body = new byte[ProviderResponse.MAX_BODY_BYTES];
        java.util.Arrays.fill(body, (byte) 'a');
        byte[] secret = new byte[8 * 1_024];
        java.util.Arrays.fill(secret, (byte) 'a');
        secret[secret.length - 1] = 'b';
        assertTimeout(Duration.ofSeconds(2),
                () -> assertFalse(ProviderNetworkExecutor.containsCanary(body, secret)));
        secret[secret.length - 1] = 'a';
        assertTrue(ProviderNetworkExecutor.containsCanary(body, secret));
    }

    private Fixture fixture(boolean credential, boolean loopback) throws Exception {
        Path configRoot = temporary.resolve("config-" + credential + "-" + loopback);
        String origin = loopback ? "https://127.0.0.1:9443/ols4" : "https://example.org/ols4";
        String credentialJson = credential ? """
                ,"credentials":[{"id":"token","provider_id":"provider",
                 "origin_alias":"origin","scheme":"bearer"}]
                """ : ",\"credentials\":[]";
        String json = "{\"version\":1,\"origins\":[{\"alias\":\"origin\","
                + "\"profile\":\"ols4\",\"origin\":\"" + origin + "\""
                + (loopback ? ",\"test_only_loopback\":true" : "") + "}]"
                + credentialJson + "}";
        OwnerOnlyFiles.write(configRoot, ProviderOwnerConfig.FILE_NAME,
                json.getBytes(StandardCharsets.UTF_8));
        ProviderOwnerConfig.ResolvedProvider authority = ProviderOwnerConfig.load(configRoot)
                .resolve("origin", "provider", "ols4", credential ? "token" : null,
                        "project-fingerprint");
        OwnerCredentialStore store = new OwnerCredentialStore(
                temporary.resolve("credentials-" + credential + "-" + loopback));
        if (credential) store.rotate("token", "canary-secret".getBytes(StandardCharsets.US_ASCII));
        return new Fixture(authority, store);
    }

    private static ProviderNetworkExecutor.RawResponse response(int status, String body) {
        return new ProviderNetworkExecutor.RawResponse(status, Map.of(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static InetAddress[] publicAddress() throws java.net.UnknownHostException {
        return new InetAddress[] {InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34})};
    }

    private static InetAddress[] privateAddress() throws java.net.UnknownHostException {
        return new InetAddress[] {InetAddress.getByAddress(new byte[] {(byte) 192, (byte) 168, 1, 2})};
    }

    private static InetAddress[] loopbackAddress() throws java.net.UnknownHostException {
        return new InetAddress[] {InetAddress.getByAddress(new byte[] {127, 0, 0, 1})};
    }

    private record Fixture(ProviderOwnerConfig.ResolvedProvider authority,
            OwnerCredentialStore credentials) {
        ProviderNetworkExecutor executor(ProviderNetworkExecutor.NetworkGate gate,
                ProviderNetworkExecutor.AddressResolver resolver,
                ProviderNetworkExecutor.HttpEngine engine,
                ProviderNetworkExecutor.Sleeper sleeper) {
            return new ProviderNetworkExecutor(authority, credentials, gate, resolver, engine,
                    Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC), sleeper);
        }
    }
}
