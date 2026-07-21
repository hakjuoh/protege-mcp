package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerProviderCacheTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void roundTripsTypedEvidenceWithoutPersistingQueriesOrCredentials() throws Exception {
        Fixture fixture = fixture("roundtrip", true, "project-a", 16, Duration.ofMinutes(5));
        List<URI> authorized = new ArrayList<>();
        OwnerProviderCache cache = fixture.cache(authorized::add);
        ProviderSearchRequest search = search("patient private query");
        ProviderInspectRequest inspect = inspect("http://example.org/EFO_0001");
        ProviderResult result = result("Visible label", List.of("curated description"), null, 1);
        ProviderPage page = page(result);

        assertTrue(putSearch(cache, fixture, fixture.authority(), search, page));
        assertTrue(putInspect(cache, fixture, fixture.authority(), inspect, result));
        assertEquals(page, cache.getSearch(fixture.authority(), search).orElseThrow());
        assertEquals(result, cache.getInspect(fixture.authority(), inspect).orElseThrow());
        assertTrue(authorized.stream().allMatch(uri -> uri.equals(URI.create("https://example.org"))));

        byte[] persisted = Files.readAllBytes(fixture.cacheRoot().resolve("responses.bin"));
        try {
            String text = new String(persisted, StandardCharsets.ISO_8859_1);
            assertFalse(text.contains("patient private query"));
            assertFalse(text.contains("owner-only-secret"));
        } finally {
            Arrays.fill(persisted, (byte) 0);
        }

        Fixture other = fixture("other-owner", true, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache otherCache = other.cache(uri -> { });
        assertTrue(putSearch(otherCache, other, other.authority(), search, page));
        byte[] first = Files.readAllBytes(fixture.cacheRoot().resolve("responses.bin"));
        byte[] second = Files.readAllBytes(other.cacheRoot().resolve("responses.bin"));
        try {
            assertFalse(Arrays.equals(first, second));
        } finally {
            Arrays.fill(first, (byte) 0);
            Arrays.fill(second, (byte) 0);
        }
    }

    @Test
    void partitionsByProjectAndCredentialGenerationAndFailsWhenCredentialDisappears()
            throws Exception {
        Fixture anonymousA = fixture("projects", false, "project-a", 16,
                Duration.ofMinutes(5));
        ProviderOwnerConfig.ResolvedProvider authorityB = anonymousA.resolve("project-b");
        OwnerProviderCache cacheA = anonymousA.cache(uri -> { });
        OwnerProviderCache cacheB = anonymousA.cacheForProject("project-b", uri -> { });
        ProviderSearchRequest request = search("shared query");
        ProviderPage page = page(result("Project A", List.of(), null, 0));

        assertTrue(putSearch(cacheA, anonymousA, anonymousA.authority(), request, page));
        assertTrue(cacheB.getSearch(authorityB, request).isEmpty());
        assertEquals(page, cacheA.getSearch(anonymousA.authority(), request).orElseThrow());

        Fixture credential = fixture("rotation", true, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache credentialCache = credential.cache(uri -> { });
        assertTrue(putSearch(credentialCache, credential, credential.authority(), request, page));
        assertTrue(credentialCache.getSearch(credential.authority(), request).isPresent());
        credential.credentials().rotate("token",
                "rotated-owner-secret".getBytes(StandardCharsets.US_ASCII));
        assertTrue(credentialCache.getSearch(credential.authority(), request).isEmpty());
        assertTrue(putSearch(credentialCache, credential, credential.authority(), request, page));
        credential.credentials().delete("token");
        ProviderFailure missing = assertThrows(ProviderFailure.class,
                () -> credentialCache.getSearch(credential.authority(), request));
        assertEquals("provider_credential_missing", missing.code());
    }

    @Test
    void acquisitionBindsTransportAndPublicationToOneCredentialGeneration() throws Exception {
        Fixture fixture = fixture("acquisition", true, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        ProviderOwnerConfig.ResolvedProvider authority = fixture.authority();
        ProviderSearchRequest staleRequest = search("stale query");
        OwnerProviderCache.Acquisition acquisition =
                cache.beginSearchAcquisition(authority, staleRequest);
        fixture.credentials().rotate("token",
                "rotated-before-fetch".getBytes(StandardCharsets.US_ASCII));
        AtomicBoolean called = new AtomicBoolean();
        ProviderNetworkExecutor executor = new ProviderNetworkExecutor(authority,
                fixture.credentials(), uri -> { }, host -> publicAddress(),
                (target, headers, addresses) -> {
                    called.set(true);
                    return new ProviderNetworkExecutor.RawResponse(200, Map.of(),
                            "{}".getBytes(StandardCharsets.UTF_8));
                }, fixture.clock(), duration -> { }, acquisition);

        ProviderFailure fetch = assertThrows(ProviderFailure.class,
                () -> executor.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_acquisition_stale", fetch.code());
        assertFalse(called.get());
        ProviderFailure publish = assertThrows(ProviderFailure.class,
                () -> cache.putSearch(authority, acquisition, staleRequest,
                        page(result("Visible", List.of(), null, 0))));
        assertEquals("provider_acquisition_stale", publish.code());
        assertTrue(cache.getSearch(authority, search("stale query")).isEmpty());

        ProviderSearchRequest currentRequest = search("current query");
        OwnerProviderCache.Acquisition current =
                acquireSearch(cache, fixture, authority, currentRequest);
        assertTrue(cache.putSearch(authority, current, currentRequest,
                page(result("Visible", List.of(), null, 0))));
        ProviderFailure reused = assertThrows(ProviderFailure.class,
                () -> cache.putSearch(authority, current, search("another query"),
                        page(result("Visible", List.of(), null, 0))));
        assertEquals("provider_acquisition_stale", reused.code());

        ProviderSearchRequest postFetchRequest = search("post fetch query");
        OwnerProviderCache.Acquisition afterFetch =
                acquireSearch(cache, fixture, authority, postFetchRequest);
        fixture.credentials().rotate("token",
                "rotated-after-fetch".getBytes(StandardCharsets.US_ASCII));
        ProviderFailure postFetchRotation = assertThrows(ProviderFailure.class,
                () -> cache.putSearch(authority, afterFetch, postFetchRequest,
                        page(result("Visible", List.of(), null, 0))));
        assertEquals("provider_acquisition_stale", postFetchRotation.code());
        assertTrue(cache.getSearch(authority, search("post fetch query")).isEmpty());

        Fixture policyFixture = fixture("acquisition-policy", false, "project-a", 16,
                Duration.ofMinutes(5));
        AtomicReference<String> policy = new AtomicReference<>("project-a");
        OwnerProviderCache policyCache = policyFixture.cacheWithPolicy(
                value -> policy.get(), uri -> { }, 16, 1_024 * 1_024);
        ProviderOwnerConfig.ResolvedProvider policyAuthority = policyFixture.authority();
        ProviderSearchRequest policyRequest = search("policy acquisition");
        OwnerProviderCache.Acquisition revoked =
                policyCache.beginSearchAcquisition(policyAuthority, policyRequest);
        policy.set("project-b");
        AtomicBoolean revokedCall = new AtomicBoolean();
        ProviderNetworkExecutor revokedExecutor = executor(policyFixture, policyAuthority, revoked,
                () -> revokedCall.set(true));
        ProviderFailure beforeNetwork = assertThrows(ProviderFailure.class,
                () -> revokedExecutor.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_policy_changed", beforeNetwork.code());
        assertFalse(revokedCall.get());

        policy.set("project-a");
        OwnerProviderCache.Acquisition revokedAfterResponse =
                policyCache.beginSearchAcquisition(policyAuthority, policyRequest);
        ProviderNetworkExecutor responseRace = executor(policyFixture, policyAuthority,
                revokedAfterResponse, () -> policy.set("project-b"));
        ProviderFailure afterNetwork = assertThrows(ProviderFailure.class,
                () -> responseRace.get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_policy_changed", afterNetwork.code());
        ProviderFailure rejectedPublication = assertThrows(ProviderFailure.class,
                () -> policyCache.putSearch(policyAuthority, revokedAfterResponse,
                        policyRequest, page(result("Visible", List.of(), null, 0))));
        assertEquals("provider_acquisition_stale", rejectedPublication.code());

        Fixture owner = fixture("acquisition-owner", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache ownerCache = owner.cache(uri -> { });
        ProviderOwnerConfig.ResolvedProvider ownerAuthority = owner.authority();
        OwnerProviderCache.Acquisition rebound =
                ownerCache.beginSearchAcquisition(ownerAuthority, search("owner acquisition"));
        owner.writeConfig("https://different.example/ols4");
        AtomicBoolean reboundCall = new AtomicBoolean();
        ProviderFailure reboundFailure = assertThrows(ProviderFailure.class,
                () -> executor(owner, ownerAuthority, rebound, () -> reboundCall.set(true))
                        .get(new ProviderRequest("/api", Map.of())));
        assertEquals("provider_authority_changed", reboundFailure.code());
        assertFalse(reboundCall.get());
    }

    @Test
    void acquisitionIsRequestAndKindBoundAndEveryPublicationAttemptConsumesIt()
            throws Exception {
        Fixture fixture = fixture("acquisition-request", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        ProviderOwnerConfig.ResolvedProvider authority = fixture.authority();
        ProviderSearchRequest first = search("first acquisition query");
        ProviderSearchRequest second = search("second acquisition query");
        ProviderPage page = page(result("Visible", List.of(), null, 0));

        OwnerProviderCache.Acquisition wrongRequest =
                acquireSearch(cache, fixture, authority, first);
        assertEquals("provider_acquisition_stale", assertThrows(ProviderFailure.class,
                () -> cache.putSearch(authority, wrongRequest, second, page)).code());
        assertEquals("provider_acquisition_stale", assertThrows(ProviderFailure.class,
                () -> cache.putSearch(authority, wrongRequest, first, page)).code());

        ProviderInspectRequest inspect = inspect("http://example.org/EFO_0001");
        OwnerProviderCache.Acquisition wrongKind =
                cache.beginInspectAcquisition(authority, inspect);
        markNetworkSuccess(fixture, authority, wrongKind);
        assertEquals("provider_acquisition_stale", assertThrows(ProviderFailure.class,
                () -> cache.putSearch(authority, wrongKind, first, page)).code());

        ProviderSearchRequest limitOne = new ProviderSearchRequest("provider", "bounded query",
                List.of("efo"), "en", 1, null);
        ProviderPage tooMany = new ProviderPage(List.of(
                resultFor("efo", "http://example.org/EFO_0001", "One"),
                resultFor("efo", "http://example.org/EFO_0002", "Two")),
                2, null, NOW, 0);
        assertEquals("provider_cache_invalid", assertThrows(ProviderFailure.class,
                () -> putSearch(cache, fixture, authority, limitOne, tooMany)).code());

        ProviderSearchRequest cursorRequest = search("cursor consumption query");
        OwnerProviderCache.Acquisition cursorAcquisition =
                acquireSearch(cache, fixture, authority, cursorRequest);
        ProviderPage nonterminal = new ProviderPage(List.of(page.items().get(0)), 2,
                "opaque-cursor", NOW, 0);
        assertFalse(cache.putSearch(authority, cursorAcquisition, cursorRequest, nonterminal));
        assertEquals("provider_acquisition_stale", assertThrows(ProviderFailure.class,
                () -> cache.putSearch(authority, cursorAcquisition, cursorRequest, page)).code());
    }

    @Test
    void enforcesLruEntryAndTtlBounds() throws Exception {
        Fixture fixture = fixture("lru", false, "project-a", 2, Duration.ofSeconds(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        ProviderSearchRequest one = search("query one");
        ProviderSearchRequest two = search("query two");
        ProviderSearchRequest three = search("query three");
        ProviderPage first = page(result("One", List.of(), null, 0));
        ProviderPage second = page(result("Two", List.of(), null, 0));
        ProviderPage third = page(result("Three", List.of(), null, 0));

        assertTrue(putSearch(cache, fixture, fixture.authority(), one, first));
        assertTrue(putSearch(cache, fixture, fixture.authority(), two, second));
        assertEquals(first, cache.getSearch(fixture.authority(), one).orElseThrow());
        assertTrue(putSearch(cache, fixture, fixture.authority(), three, third));
        assertTrue(cache.getSearch(fixture.authority(), two).isEmpty());
        assertTrue(cache.getSearch(fixture.authority(), one).isPresent());
        assertTrue(cache.getSearch(fixture.authority(), three).isPresent());

        fixture.clock().advance(Duration.ofSeconds(6));
        assertTrue(cache.getSearch(fixture.authority(), one).isEmpty());
        assertTrue(cache.getSearch(fixture.authority(), three).isEmpty());
    }

    @Test
    void rejectsCredentialShapedPayloadsAndStrictlyRevalidatesCodecModels() throws Exception {
        Fixture fixture = fixture("safety", true, "project-a", 16, Duration.ofMinutes(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        ProviderSearchRequest request = search("safe query");

        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("owner-only-secret", List.of(), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of("Bearer opaque-token"), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of("Negotiate opaque-token"), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of("DPoP opaque-token"), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of("Mutual opaque-token"), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of("HOBA opaque-token"), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of("vapid opaque-token"), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of("Cookie: SID=opaque"), null, 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), "https://license.example/?token=x", 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), "ftp://user:password@example.org/file", 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), "//alice:opaque@example.org/file", 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), "ftp://files.example/file?opaque=x", 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), "file:/path?opaque=x", 0))));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), "custom:/resource?opaque=x", 0))));
        ProviderSearchRequest sensitive = search("sensitive raw query");
        assertFalse(putSearch(cache, fixture, fixture.authority(), sensitive,
                page(result("sensitive raw query", List.of(), null, 0))));
        ProviderSearchRequest control = search("a\u0000b");
        assertFalse(putSearch(cache, fixture, fixture.authority(), control,
                page(result("a\u0000b", List.of(), null, 0))));

        String escapedSecret = "quote\"and\\slash";
        fixture.credentials().rotate("token", escapedSecret.getBytes(StandardCharsets.US_ASCII));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result(escapedSecret, List.of(), null, 0))));
        byte[] escapedJson = "{\"value\":\"quote\\\"and\\\\slash\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(ProviderNetworkExecutor.containsCanary(escapedJson,
                escapedSecret.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(ProviderNetworkExecutor.containsCanary(
                "{\"value\":\"\\u0071\\u0075\\u006f\\u0074\\u0065\"}"
                        .getBytes(StandardCharsets.UTF_8),
                "quote".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(ProviderNetworkExecutor.containsCanary(
                "{\"value\":\"a\\/b\"}".getBytes(StandardCharsets.UTF_8),
                "a/b".getBytes(StandardCharsets.US_ASCII)));
        fixture.credentials().rotate("token", "false".getBytes(StandardCharsets.US_ASCII));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), null, 0))));
        assertTrue(ProviderNetworkExecutor.containsCanary(
                "{\"value\":false}".getBytes(StandardCharsets.UTF_8),
                "false".getBytes(StandardCharsets.US_ASCII)));
        fixture.credentials().rotate("token", "nonmatching-secret"
                .getBytes(StandardCharsets.US_ASCII));
        assertFalse(putSearch(cache, fixture, fixture.authority(), search("false"),
                page(result("Visible", List.of(), null, 0))));
        fixture.credentials().rotate("token", "1".getBytes(StandardCharsets.US_ASCII));
        assertFalse(putSearch(cache, fixture, fixture.authority(), request,
                page(result("Visible", List.of(), null, 0))));
        StringBuilder exhausted = new StringBuilder("[");
        for (int index = 0; index < 250_001; index++) exhausted.append("\"x\",");
        exhausted.append("\"\\u0071\\u0075\\u006f\\u0074\\u0065\"]");
        byte[] exhaustedJson = exhausted.toString().getBytes(StandardCharsets.UTF_8);
        assertTimeout(Duration.ofSeconds(2), () -> assertTrue(
                ProviderNetworkExecutor.containsCanary(exhaustedJson,
                        "quote".getBytes(StandardCharsets.US_ASCII))));
        assertTrue(cache.getSearch(fixture.authority(), request).isEmpty());

        ProviderResult safe = result("Visible", List.of(), null, 0);
        byte[] encoded = ProviderCacheCodec.encodeResult(safe);
        String json = new String(encoded, StandardCharsets.UTF_8);
        byte[] unknown = json.replaceFirst("\\{", "{\"unknown\":true,")
                .getBytes(StandardCharsets.UTF_8);
        byte[] fingerprint = json.replace(safe.resultFingerprint(),
                "sha256:" + "0".repeat(64)).getBytes(StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> ProviderCacheCodec.decodeResult(unknown));
        assertThrows(java.io.IOException.class, () -> ProviderCacheCodec.decodeResult(fingerprint));
    }

    @Test
    void refusesVendorCursorsAndRequestMismatchesButCachesTerminalEmptyPages()
            throws Exception {
        Fixture fixture = fixture("semantic-binding", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        ProviderOwnerConfig.ResolvedProvider authority = fixture.authority();
        ProviderSearchRequest request = search("unmatched query");
        ProviderResult evidence = result("Visible", List.of(), null, 0);
        String vendorCursor = "opaque-vendor-cursor-with-request-digest";
        ProviderPage nonterminal = new ProviderPage(List.of(evidence), 2, vendorCursor, NOW, 0);
        assertFalse(putSearch(cache, fixture, authority, request, nonterminal));
        assertTrue(cache.getSearch(authority, request).isEmpty());
        if (Files.exists(fixture.cacheRoot().resolve("responses.bin"))) {
            assertFalse(Files.readString(fixture.cacheRoot().resolve("responses.bin"),
                    StandardCharsets.ISO_8859_1).contains(vendorCursor));
        }
        assertThrows(java.io.IOException.class,
                () -> ProviderCacheCodec.encodePage(nonterminal));

        ProviderResult wrongOntology = resultFor("bfo", "http://example.org/BFO_0001",
                "Visible");
        ProviderFailure searchMismatch = assertThrows(ProviderFailure.class,
                () -> putSearch(cache, fixture, authority, request, page(wrongOntology)));
        assertEquals("provider_cache_invalid", searchMismatch.code());
        ProviderResult wrongEntity = resultFor("efo", "http://example.org/EFO_9999", "Visible");
        ProviderFailure inspectMismatch = assertThrows(ProviderFailure.class,
                () -> putInspect(cache, fixture, authority,
                        inspect("http://example.org/EFO_0001"),
                        wrongEntity));
        assertEquals("provider_cache_invalid", inspectMismatch.code());

        ProviderPage empty = new ProviderPage(List.of(), 0, null, NOW, 0);
        assertTrue(putSearch(cache, fixture, authority, request, empty));
        OwnerProviderCache reopened = fixture.cache(uri -> { });
        assertEquals(empty, reopened.getSearch(authority, request).orElseThrow());
    }

    @Test
    void refusesCachingAnonymousCrossOriginRedirectEvidenceWithoutPersistentProvenance()
            throws Exception {
        Fixture fixture = fixture("redirect-provenance", false, "project-a", 16,
                Duration.ofMinutes(5));
        AtomicBoolean redirectAllowed = new AtomicBoolean(true);
        ProviderNetworkExecutor.NetworkGate gate = origin -> {
            if (origin.getHost().equals("redirect.example") && !redirectAllowed.get()) {
                throw new ProviderFailure("revoked", "redirect origin revoked", false);
            }
        };
        OwnerProviderCache cache = fixture.cache(gate);
        ProviderOwnerConfig.ResolvedProvider authority = fixture.authority();
        ProviderSearchRequest request = search("redirected empty query");
        OwnerProviderCache.Acquisition acquisition =
                cache.beginSearchAcquisition(authority, request);
        AtomicBoolean redirected = new AtomicBoolean();
        ProviderNetworkExecutor executor = new ProviderNetworkExecutor(authority,
                fixture.credentials(), gate, host -> publicAddress(),
                (target, headers, addresses) -> {
                    if (!redirected.getAndSet(true)) {
                        return new ProviderNetworkExecutor.RawResponse(302,
                                Map.of("Location", "https://redirect.example/api"), new byte[0]);
                    }
                    return new ProviderNetworkExecutor.RawResponse(200, Map.of(),
                            "{}".getBytes(StandardCharsets.UTF_8));
                }, fixture.clock(), duration -> { }, acquisition);
        executor.get(new ProviderRequest("/api", Map.of()));

        ProviderPage empty = new ProviderPage(List.of(), 0, null, NOW, 0);
        assertFalse(cache.putSearch(authority, acquisition, request, empty));
        redirectAllowed.set(false);
        assertTrue(cache.getSearch(authority, request).isEmpty());
        assertFalse(Files.exists(fixture.cacheRoot().resolve("responses.bin")));
    }

    @Test
    void discardsCorruptDataButFailsClosedForCorruptOwnerHmacKey() throws Exception {
        Fixture fixture = fixture("corruption", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        ProviderSearchRequest request = search("corrupt me");
        ProviderPage page = page(result("Visible", List.of(), null, 0));
        assertTrue(putSearch(cache, fixture, fixture.authority(), request, page));

        Path data = fixture.cacheRoot().resolve("responses.bin");
        byte[] damaged = Files.readAllBytes(data);
        damaged[damaged.length / 2] ^= 0x55;
        Files.write(data, damaged);
        Arrays.fill(damaged, (byte) 0);
        assertTrue(cache.getSearch(fixture.authority(), request).isEmpty());
        assertFalse(Files.exists(data));

        assertTrue(putSearch(cache, fixture, fixture.authority(), request, page));
        Path key = fixture.cacheRoot().resolve("query-hmac.key");
        damaged = Files.readAllBytes(key);
        damaged[damaged.length - 1] ^= 0x01;
        Files.write(key, damaged);
        Arrays.fill(damaged, (byte) 0);
        ProviderFailure invalid = assertThrows(ProviderFailure.class,
                () -> cache.getSearch(fixture.authority(), request));
        assertEquals("provider_cache_invalid", invalid.code());
    }

    @Test
    void enforcesByteAndArithmeticBoundsWithoutReplacingTheLastGoodState() throws Exception {
        Fixture bounded = fixture("byte-bound", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache cache = bounded.cacheWithLimits(uri -> { }, 16, 4_096);
        ProviderOwnerConfig.ResolvedProvider authority = bounded.authority();
        ProviderSearchRequest smallRequest = search("small query");
        ProviderPage small = page(result("Small", List.of(), null, 0));
        assertTrue(putSearch(cache, bounded, authority, smallRequest, small));
        ProviderPage oversized = page(result("Large", List.of("x".repeat(6_000)), null, 0));
        assertFalse(putSearch(cache, bounded, authority, search("large query"), oversized));
        assertEquals(small, cache.getSearch(authority, smallRequest).orElseThrow());

        Fixture counter = fixture("counter-bound", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache counterCache = counter.cache(uri -> { });
        OwnerOnlyFiles.write(counter.cacheRoot(), "responses.bin", emptyState(Long.MAX_VALUE));
        ProviderFailure exhausted = assertThrows(ProviderFailure.class,
                () -> putSearch(counterCache, counter, counter.authority(),
                        search("counter query"), small));
        assertEquals("provider_cache_invalid", exhausted.code());
        OwnerOnlyFiles.write(counter.cacheRoot(), "responses.bin", emptyState(0));
        assertTrue(counterCache.getSearch(counter.authority(), search("counter query")).isEmpty());
        assertFalse(Files.exists(counter.cacheRoot().resolve("responses.bin")));

        Fixture expiry = fixture("expiry-bound", false, "project-a", 16,
                Duration.ofSeconds(5));
        expiry.clock().set(Instant.ofEpochMilli(Long.MAX_VALUE - 1_000));
        ProviderFailure overflow = assertThrows(ProviderFailure.class,
                () -> putSearch(expiry.cache(uri -> { }), expiry, expiry.authority(),
                        search("expiry query"), small));
        assertEquals("provider_cache_invalid", overflow.code());
    }

    @Test
    void reloadsOwnerBindingAndSanitizesNetworkGateFailuresOnEveryAccess() throws Exception {
        Fixture fixture = fixture("authority", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        ProviderSearchRequest request = search("binding query");
        ProviderPage page = page(result("Visible", List.of(), null, 0));
        ProviderOwnerConfig.ResolvedProvider original = fixture.authority();
        assertTrue(putSearch(cache, fixture, original, request, page));

        fixture.writeConfig("https://different.example/ols4");
        ProviderFailure changed = assertThrows(ProviderFailure.class,
                () -> cache.getSearch(original, request));
        assertEquals("provider_authority_changed", changed.code());

        Fixture policy = fixture("policy", false, "project-a", 16,
                Duration.ofMinutes(5));
        AtomicReference<String> policyFingerprint = new AtomicReference<>("project-a");
        OwnerProviderCache policyCache = policy.cacheWithPolicy(
                authority -> policyFingerprint.get(), uri -> { }, 16, 1_024 * 1_024);
        ProviderSearchRequest policyRequest = search("policy query");
        assertTrue(putSearch(policyCache, policy, policy.authority(), policyRequest, page));
        policyFingerprint.set("project-b");
        ProviderFailure policyChanged = assertThrows(ProviderFailure.class,
                () -> policyCache.getSearch(policy.authority(), policyRequest));
        assertEquals("provider_policy_changed", policyChanged.code());

        Fixture denied = fixture("denied", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache deniedCache = denied.cache(uri -> {
            throw new ProviderFailure("gate_internal", "must-not-leak-gate-detail", false);
        });
        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> deniedCache.getSearch(denied.authority(), request));
        assertEquals("provider_network_denied", failure.code());
        assertFalse(failure.getMessage().contains("must-not-leak"));
        assertEquals(null, failure.getCause());
    }

    @Test
    void serializesConcurrentInstancesWithoutLostUpdates() throws Exception {
        Fixture fixture = fixture("concurrent", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache first = fixture.cache(uri -> { });
        OwnerProviderCache second = fixture.cache(uri -> { });
        ProviderSearchRequest one = search("parallel one");
        ProviderSearchRequest two = search("parallel two");
        ProviderPage pageOne = page(result("One", List.of(), null, 0));
        ProviderPage pageTwo = page(result("Two", List.of(), null, 0));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> writeOne = executor.submit(() -> {
                start.await();
                return putSearch(first, fixture, fixture.authority(), one, pageOne);
            });
            Future<Boolean> writeTwo = executor.submit(() -> {
                start.await();
                return putSearch(second, fixture, fixture.authority(), two, pageTwo);
            });
            start.countDown();
            assertTrue(writeOne.get(10, TimeUnit.SECONDS));
            assertTrue(writeTwo.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(pageOne, first.getSearch(fixture.authority(), one).orElseThrow());
        assertEquals(pageTwo, second.getSearch(fixture.authority(), two).orElseThrow());
    }

    @Test
    void rejectsCacheFileSymlinkInsteadOfFollowingIt() throws Exception {
        Fixture fixture = fixture("symlink", false, "project-a", 16,
                Duration.ofMinutes(5));
        OwnerProviderCache cache = fixture.cache(uri -> { });
        Path outside = temporary.resolve("outside-cache");
        Files.writeString(outside, "outside");
        try {
            Files.createSymbolicLink(fixture.cacheRoot().resolve("responses.bin"), outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> cache.getSearch(fixture.authority(), search("symlink query")));
        assertEquals("provider_store_invalid", failure.code());
        assertEquals("outside", Files.readString(outside));
    }

    private Fixture fixture(String name, boolean credential, String project, int maxEntries,
            Duration ttl) throws Exception {
        Path root = temporary.resolve(name);
        OwnerOnlyFiles.prepareDirectory(root);
        Path configRoot = root.resolve("config");
        Path cacheRoot = root.resolve("cache");
        OwnerCredentialStore credentials = new OwnerCredentialStore(root.resolve("credentials"));
        MutableClock clock = new MutableClock(NOW);
        Fixture fixture = new Fixture(configRoot, cacheRoot, credentials, clock,
                credential, project, maxEntries, ttl);
        fixture.writeConfig("https://example.org/ols4");
        if (credential) credentials.rotate("token",
                "owner-only-secret".getBytes(StandardCharsets.US_ASCII));
        return fixture;
    }

    private static ProviderSearchRequest search(String query) {
        return new ProviderSearchRequest("provider", query, List.of("efo"), "en", 20, null);
    }

    private static ProviderInspectRequest inspect(String iri) {
        return new ProviderInspectRequest("provider", "efo", iri, "en");
    }

    private static boolean putSearch(OwnerProviderCache cache, Fixture fixture,
            ProviderOwnerConfig.ResolvedProvider authority, ProviderSearchRequest request,
            ProviderPage page) throws ProviderFailure {
        OwnerProviderCache.Acquisition acquisition =
                acquireSearch(cache, fixture, authority, request);
        return cache.putSearch(authority, acquisition, request, page);
    }

    private static boolean putInspect(OwnerProviderCache cache, Fixture fixture,
            ProviderOwnerConfig.ResolvedProvider authority, ProviderInspectRequest request,
            ProviderResult result) throws ProviderFailure {
        OwnerProviderCache.Acquisition acquisition =
                cache.beginInspectAcquisition(authority, request);
        markNetworkSuccess(fixture, authority, acquisition);
        return cache.putInspect(authority, acquisition, request, result);
    }

    private static OwnerProviderCache.Acquisition acquireSearch(OwnerProviderCache cache,
            Fixture fixture, ProviderOwnerConfig.ResolvedProvider authority,
            ProviderSearchRequest request) throws ProviderFailure {
        OwnerProviderCache.Acquisition acquisition =
                cache.beginSearchAcquisition(authority, request);
        markNetworkSuccess(fixture, authority, acquisition);
        return acquisition;
    }

    private static void markNetworkSuccess(Fixture fixture,
            ProviderOwnerConfig.ResolvedProvider authority,
            OwnerProviderCache.Acquisition acquisition) throws ProviderFailure {
        ProviderNetworkExecutor executor = executor(fixture, authority, acquisition, () -> { });
        executor.get(new ProviderRequest("/cache-test", Map.of()));
    }

    private static ProviderNetworkExecutor executor(Fixture fixture,
            ProviderOwnerConfig.ResolvedProvider authority,
            OwnerProviderCache.Acquisition acquisition, Runnable onGet) {
        return new ProviderNetworkExecutor(authority, fixture.credentials(), uri -> { },
                host -> publicAddress(), (target, headers, addresses) -> {
                    onGet.run();
                    return new ProviderNetworkExecutor.RawResponse(200, Map.of(),
                            "{}".getBytes(StandardCharsets.UTF_8));
                }, fixture.clock(), duration -> { }, acquisition);
    }

    private static ProviderPage page(ProviderResult result) {
        return new ProviderPage(List.of(result), 1, null, NOW, result.retries());
    }

    private static ProviderResult result(String label, List<String> descriptions, String license,
            int retries) {
        return resultFor("efo", "http://example.org/EFO_0001", label, descriptions,
                license, retries);
    }

    private static ProviderResult resultFor(String ontology, String entityIri, String label) {
        return resultFor(ontology, entityIri, label, List.of(), null, 0);
    }

    private static ProviderResult resultFor(String ontology, String entityIri, String label,
            List<String> descriptions, String license, int retries) {
        return ProviderResult.create("provider", "ols4", ontology,
                "http://www.ebi.ac.uk/" + ontology, entityIri, "class",
                List.of(new ProviderResult.LocalizedText(label, "en")), List.of(), descriptions,
                license, "OLS4", "exact label", 1.0, "2026-07", NOW,
                URI.create("https://example.org/ols4/api/ontologies/efo/terms/EFO_0001"),
                retries, false, null);
    }

    private static InetAddress[] publicAddress() throws java.net.UnknownHostException {
        return new InetAddress[] {InetAddress.getByAddress(
                new byte[] {93, (byte) 184, (byte) 216, 34})};
    }

    private static byte[] emptyState(long nextAccess) throws Exception {
        byte[] content = ByteBuffer.allocate(8 + Long.BYTES + Integer.BYTES)
                .put("PMCPCHE1".getBytes(StandardCharsets.US_ASCII))
                .putLong(nextAccess).putInt(0).array();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        return ByteBuffer.allocate(content.length + digest.length).put(content).put(digest).array();
    }

    private record Fixture(Path configRoot, Path cacheRoot, OwnerCredentialStore credentials,
            MutableClock clock, boolean credential, String project, int maxEntries, Duration ttl) {

        ProviderOwnerConfig.ResolvedProvider authority() throws ProviderFailure {
            return resolve(project);
        }

        ProviderOwnerConfig.ResolvedProvider resolve(String selectedProject)
                throws ProviderFailure {
            return ProviderOwnerConfig.load(configRoot).resolve("origin", "provider", "ols4",
                    credential ? "token" : null, selectedProject);
        }

        OwnerProviderCache cache(ProviderNetworkExecutor.NetworkGate gate) throws ProviderFailure {
            return cacheForProject(project, gate);
        }

        OwnerProviderCache cacheForProject(String selectedProject,
                ProviderNetworkExecutor.NetworkGate gate) throws ProviderFailure {
            return new OwnerProviderCache(cacheRoot, credentials, () -> resolve(selectedProject),
                    authority -> authority.projectFingerprint(), gate, clock, ttl,
                    maxEntries, 1_024 * 1_024);
        }

        OwnerProviderCache cacheWithLimits(ProviderNetworkExecutor.NetworkGate gate,
                int selectedMaxEntries, int selectedMaxBytes) throws ProviderFailure {
            return cacheWithPolicy(authority -> authority.projectFingerprint(), gate,
                    selectedMaxEntries, selectedMaxBytes);
        }

        OwnerProviderCache cacheWithPolicy(OwnerProviderCache.ProjectGate selectedProjectGate,
                ProviderNetworkExecutor.NetworkGate gate, int selectedMaxEntries,
                int selectedMaxBytes) throws ProviderFailure {
            return new OwnerProviderCache(cacheRoot, credentials, () -> resolve(project),
                    selectedProjectGate, gate, clock, ttl, selectedMaxEntries, selectedMaxBytes);
        }

        void writeConfig(String origin) throws ProviderFailure {
            String credentialJson = credential ? """
                    ,"credentials":[{"id":"token","provider_id":"provider",
                     "origin_alias":"origin","scheme":"bearer"}]
                    """ : ",\"credentials\":[]";
            String json = "{\"version\":1,\"origins\":[{\"alias\":\"origin\","
                    + "\"profile\":\"ols4\",\"origin\":\"" + origin + "\"}]"
                    + credentialJson + "}";
            OwnerOnlyFiles.write(configRoot, ProviderOwnerConfig.FILE_NAME,
                    json.getBytes(StandardCharsets.UTF_8));
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

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC required");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
