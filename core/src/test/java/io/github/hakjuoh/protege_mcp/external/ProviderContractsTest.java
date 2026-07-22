package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProviderContractsTest {

    @Test
    void requestsAreNormalizedBoundedAndCannotSelectAnOrigin() {
        ProviderSearchRequest request = new ProviderSearchRequest("OLS", "  A\u00a0  term  ",
                List.of("EFO", "efo"), "EN", 25, null);
        assertEquals("ols", request.providerId());
        assertEquals("A term", request.query());
        assertEquals(List.of("efo"), request.ontologies());
        assertEquals("en", request.language());

        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRequest("https://attacker.example/api", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRequest("//attacker.example/api", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRequest("/api/../secret", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRequest("/api/%2e%2e/secret", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRequest("/api\\secret", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderRequest("/api\nsecret", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderSearchRequest("ols", "q", List.of(), "en", 101, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderInspectRequest("ols", "efo", "relative", "en"));
        String highSurrogate = String.valueOf((char) 0xd800);
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderSearchRequest("ols", highSurrogate, List.of(), "en", 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderInspectRequest("ols", "efo",
                        "https://example.org/" + highSurrogate, "en"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderSearchRequest("ols", "\uFDFA".repeat(30),
                        List.of(), "en", 10, null));
        String maximumLanguage = "en-" + "abcdefgh-".repeat(6) + "abcdefg";
        String excessiveLanguage = "en-" + "abcdefgh-".repeat(6) + "abcdefgh";
        assertEquals(maximumLanguage, new ProviderSearchRequest("ols", "q", List.of(),
                maximumLanguage, 10, null).language());
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderSearchRequest("ols", "q", List.of(),
                        excessiveLanguage, 10, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderResult.LocalizedText("value", "en_US"));
        String astral = "\uD83D\uDE00";
        assertEquals(512, new ProviderSearchRequest("ols", astral.repeat(512),
                List.of(), "en", 10, null).query().codePointCount(0, 1_024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderSearchRequest("ols", astral.repeat(513),
                        List.of(), "en", 10, null));
        assertThrows(IllegalArgumentException.class, () -> new ProviderResponse(
                new byte[ProviderResponse.MAX_BODY_BYTES + 1],
                URI.create("https://example.org/ols4/api/search"), Instant.EPOCH, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProviderResponse(
                new byte[0], URI.create("https:opaque"), Instant.EPOCH, 0));
    }

    @Test
    void responseBytesAndResultCollectionsAreDefensivelyImmutable() {
        byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProviderResponse response = new ProviderResponse(bytes,
                URI.create("https://example.org/ols4/api/search"), Instant.EPOCH, 0);
        bytes[0] = 'x';
        assertArrayEquals("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), response.body());
        byte[] returned = response.body();
        returned[0] = 'x';
        assertArrayEquals("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), response.body());

        ProviderResult result = result("Term", 0.8);
        assertThrows(UnsupportedOperationException.class,
                () -> result.labels().add(new ProviderResult.LocalizedText("x", "en")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.toJson().put("secret", "value"));
    }

    @Test
    void fingerprintsCoverAllProposalRelevantEvidence() {
        ProviderResult first = result("Term", 0.8);
        ProviderResult same = result("Term", 0.8);
        ProviderResult changed = result("Term", 0.7);
        assertEquals(first.resultFingerprint(), same.resultFingerprint());
        assertEquals("sha256:4a2ac267791a2e72154d7fc2f6076d456c4f59ed150157c3282b53511965773a",
                first.resultFingerprint());
        assertNotEquals(first.resultFingerprint(), changed.resultFingerprint());
        assertEquals(first.termFingerprint(), changed.termFingerprint(),
                "ranking score is acquisition evidence, not stable term content");
        assertNotEquals(first.termFingerprint(), result("Changed", 0.8).termFingerprint());
        assertTrue(first.resultFingerprint().matches("sha256:[0-9a-f]{64}"));
        assertTrue(first.termFingerprint().matches("sha256:[0-9a-f]{64}"));
        ProviderResult laterAcquisition = ProviderResult.create(
                first.providerId(), first.profile(), first.sourceOntology(),
                first.sourceOntologyIri(), first.entityIri(), first.entityType(), first.labels(),
                first.synonyms(), first.descriptions(), first.license(), first.provenance(),
                first.matchExplanation(), first.score(), first.providerVersion(),
                first.providerTimestamp().plusSeconds(60), first.sourceUrl(), 1,
                first.deprecated(), first.replacedBy());
        assertEquals(first.termFingerprint(), laterAcquisition.termFingerprint());
        assertNotEquals(first.resultFingerprint(), laterAcquisition.resultFingerprint());
        ProviderResult embeddedSeparator = result(List.of("a\u0001b"));
        ProviderResult separateValues = result(List.of("a", "b"));
        assertNotEquals(embeddedSeparator.resultFingerprint(), separateValues.resultFingerprint());
        assertThrows(IllegalArgumentException.class, () -> new ProviderResult(
                first.providerId(), first.profile(), first.sourceOntology(),
                first.sourceOntologyIri(), first.entityIri(), first.entityType(), first.labels(),
                first.synonyms(), first.descriptions(), first.license(), first.provenance(),
                first.matchExplanation(), first.score(), first.providerVersion(),
                first.providerTimestamp(), first.sourceUrl(), first.retries(), first.deprecated(),
                first.replacedBy(), "sha256:" + "0".repeat(64)));
        assertNull(first.sourceUrl().getRawQuery());
    }

    @Test
    void malformedUnicodeCannotCollapseDistinctProviderEvidenceFingerprints() {
        String high = String.valueOf((char) 0xd800);
        String low = String.valueOf((char) 0xdc00);
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderResult.LocalizedText(high, "en"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderResult.LocalizedText(low, "en"));
        assertThrows(IllegalArgumentException.class, () -> result(List.of(high)));

        ProviderResult value = result("Term", 0.8);
        assertThrows(IllegalArgumentException.class, () -> ProviderResult.create(
                value.providerId(), value.profile(), value.sourceOntology(),
                value.sourceOntologyIri(), value.entityIri(), value.entityType(), value.labels(),
                value.synonyms(), value.descriptions(), high, value.provenance(),
                value.matchExplanation(), value.score(), value.providerVersion(),
                value.providerTimestamp(), value.sourceUrl(), value.retries(), value.deprecated(),
                value.replacedBy()));
        assertThrows(IllegalArgumentException.class, () -> ProviderResult.create(
                value.providerId(), value.profile(), value.sourceOntology(),
                value.sourceOntologyIri(), value.entityIri(), value.entityType(), value.labels(),
                value.synonyms(), value.descriptions(), value.license(), value.provenance(), low,
                value.score(), value.providerVersion(), value.providerTimestamp(), value.sourceUrl(),
                value.retries(), value.deprecated(), value.replacedBy()));
        assertThrows(IllegalArgumentException.class, () -> ProviderResult.create(
                value.providerId(), value.profile(), value.sourceOntology(),
                value.sourceOntologyIri(), value.entityIri(), value.entityType(), value.labels(),
                value.synonyms(), value.descriptions(), value.license(), value.provenance(),
                value.matchExplanation(), value.score(), value.providerVersion(),
                value.providerTimestamp(), URI.create("https://example.org/" + high),
                value.retries(), value.deprecated(), value.replacedBy()));
        assertThrows(IllegalArgumentException.class, () -> ProviderResult.create(
                value.providerId(), value.profile(), value.sourceOntology(),
                value.sourceOntologyIri(), value.entityIri(), value.entityType(), value.labels(),
                value.synonyms(), value.descriptions(), value.license(), value.provenance(),
                value.matchExplanation(), value.score(), value.providerVersion(),
                value.providerTimestamp(), URI.create("https://example.org/" + low),
                value.retries(), value.deprecated(), value.replacedBy()));
    }

    @Test
    void providerControlledTextBoundsCountUnicodeCodePoints() {
        String astral = "\uD83D\uDE00";
        ProviderResult base = result("Term", 0.8);
        ProviderResult accepted = ProviderResult.create(
                base.providerId(), base.profile(), base.sourceOntology(),
                base.sourceOntologyIri(), base.entityIri(), base.entityType(),
                List.of(new ProviderResult.LocalizedText(astral.repeat(4_096), "en")),
                base.synonyms(), List.of(astral.repeat(8_192)), astral.repeat(4_096),
                astral.repeat(4_096), base.matchExplanation(), base.score(),
                astral.repeat(512), base.providerTimestamp(), base.sourceUrl(), base.retries(),
                base.deprecated(), base.replacedBy());
        assertEquals(4_096, accepted.labels().get(0).value()
                .codePointCount(0, accepted.labels().get(0).value().length()));
        assertThrows(IllegalArgumentException.class, () -> ProviderResult.create(
                base.providerId(), base.profile(), base.sourceOntology(),
                base.sourceOntologyIri(), base.entityIri(), base.entityType(), base.labels(),
                base.synonyms(), base.descriptions(), base.license(), base.provenance(),
                base.matchExplanation(), base.score(), astral.repeat(513),
                base.providerTimestamp(), base.sourceUrl(), base.retries(), base.deprecated(),
                base.replacedBy()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderResult.LocalizedText(astral.repeat(4_097), "en"));
    }

    @Test
    void pagesChooseDuplicateEvidenceDeterministicallyBeforePagination() {
        ProviderResult higher = result("Term", 0.9);
        ProviderResult lower = result("Changed", 0.8);
        ProviderPage page = new ProviderPage(List.of(lower, higher), 2, null, Instant.EPOCH, 0);
        assertEquals(List.of(higher), page.items());
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderPage(List.of(), (long) Integer.MAX_VALUE + 1,
                        null, Instant.EPOCH, 0));
    }

    static ProviderResult result(String label, double score) {
        return ProviderResult.create("fake", "fake", "efo", "https://example.org/efo.owl",
                "https://example.org/EFO_1", "class",
                List.of(new ProviderResult.LocalizedText(label, "en")),
                List.of(new ProviderResult.LocalizedText("Alternative", "en")),
                List.of("A description"), "https://example.org/license",
                "https://example.org/efo/1", "provider_rank:0", score, "1.0.0",
                Instant.parse("2026-07-20T00:00:00Z"),
                URI.create("https://example.org/ols4/api/ontologies/efo/terms/x"), 0, false, null);
    }

    private static ProviderResult result(List<String> descriptions) {
        ProviderResult value = result("Term", 0.8);
        return ProviderResult.create(value.providerId(), value.profile(), value.sourceOntology(),
                value.sourceOntologyIri(), value.entityIri(), value.entityType(), value.labels(),
                value.synonyms(), descriptions, value.license(), value.provenance(),
                value.matchExplanation(), value.score(), value.providerVersion(),
                value.providerTimestamp(), value.sourceUrl(), value.retries(), value.deprecated(),
                value.replacedBy());
    }
}
