package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class FakeTermProviderTest {

    @Test
    void searchFiltersAndPagesWithoutUsingNetwork() throws Exception {
        ProviderResult first = ProviderContractsTest.result("Apoptosis", 1.0);
        ProviderResult second = ProviderResult.create("fake", "fake", "go", null,
                "https://example.org/GO_1", "class",
                List.of(new ProviderResult.LocalizedText("Apoptotic process", "en")), List.of(),
                List.of(), null, null, "label_contains_query", 0.8, "2",
                first.providerTimestamp(), first.sourceUrl(), 0, false, null);
        ProviderResult duplicate = ProviderContractsTest.result("Apoptosis", 0.5);
        FakeTermProvider provider = new FakeTermProvider(List.of(second, duplicate, first));
        ProviderTransport forbidden = request -> {
            throw new AssertionError("the in-process fake must not use network transport");
        };

        ProviderPage pageOne = provider.search(new ProviderSearchRequest(
                "fake", "apopt", List.of(), "en", 1, null), forbidden);
        assertEquals(1, pageOne.items().size());
        assertEquals(2, pageOne.total());
        assertEquals("efo", pageOne.items().get(0).sourceOntology());
        assertTrue(pageOne.continuation().startsWith("v1.1."));
        ProviderPage pageTwo = provider.search(new ProviderSearchRequest(
                "fake", "apopt", List.of(), "en", 1, pageOne.continuation()), forbidden);
        assertEquals(1, pageTwo.items().size());
        assertEquals(null, pageTwo.continuation());

        ProviderPage filtered = provider.search(new ProviderSearchRequest(
                "fake", "apopt", List.of("efo"), "en", 10, null), forbidden);
        assertEquals(List.of("efo"), filtered.items().stream()
                .map(ProviderResult::sourceOntology).toList());

        ProviderPage otherLanguage = provider.search(new ProviderSearchRequest(
                "fake", "apopt", List.of(), "fr", 10, null), forbidden);
        assertEquals(0, otherLanguage.total());

        ProviderFailure rebound = assertThrows(ProviderFailure.class,
                () -> provider.search(new ProviderSearchRequest(
                                "fake", "different", List.of(), "en", 1,
                                pageOne.continuation()), forbidden));
        assertEquals("provider_cursor_invalid", rebound.code());
    }

    @Test
    void inspectIsExactAndUnknownTermsAreTyped() throws Exception {
        ProviderResult result = ProviderContractsTest.result("Term", 1.0);
        FakeTermProvider provider = new FakeTermProvider(List.of(result));
        assertEquals(result, provider.inspect(new ProviderInspectRequest(
                "fake", "efo", result.entityIri(), "en"), request -> null));
        ProviderFailure missing = assertThrows(ProviderFailure.class,
                () -> provider.inspect(new ProviderInspectRequest(
                        "fake", "efo", "https://example.org/missing", "en"), request -> null));
        assertEquals("provider_term_not_found", missing.code());
    }
}
