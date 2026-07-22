package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class Ols4ProviderTest {

    private static final Instant FETCHED = Instant.parse("2026-07-20T01:00:00Z");

    @Test
    void searchMapsEvidenceRankingAndContinuation() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            return response("/ols4/api/search", """
                    {"response":{"numFound":4,"start":0,"docs":[
                      {"iri":"https://example.org/EFO_1","ontology_name":"efo",
                       "label":"Apoptosis","type":"class","description":["death"],
                       "synonym":["programmed cell death"],"is_obsolete":false},
                      {"iri":"https://example.org/GO_1","ontology_name":"go",
                       "label":"apoptotic process","type":"class"}
                    ]}}
                    """);
        };

        ProviderPage page = new Ols4Provider().search(new ProviderSearchRequest(
                "ebi-ols", "apoptosis", List.of("efo", "go"), "en", 2, null), transport);

        assertEquals(2, page.items().size());
        assertEquals(4, page.total());
        assertTrue(page.continuation().startsWith("v1.2."));
        assertEquals("exact_label", page.items().get(0).matchExplanation());
        assertEquals(1.0, page.items().get(0).score());
        assertEquals("https://example.org/EFO_1", page.items().get(0).entityIri());
        assertFalse(page.items().get(0).sourceUrl().toString().contains("apoptosis"));
        assertTrue(page.items().get(0).sourceUrl().toString()
                .contains("https%253A%252F%252Fexample.org%252FEFO_1"),
                page.items().get(0).sourceUrl()::toString);
        assertEquals("/api/search", requests.get(0).relativePath());
        assertEquals("efo,go", requests.get(0).query().get("ontology"));
        assertEquals("2", requests.get(0).query().get("rows"));
    }

    @Test
    void searchDeduplicatesAcrossPagesAndPreservesGlobalProviderOrder() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            if ("0".equals(request.query().get("start"))) {
                return response("/ols4/api/search", """
                        {"response":{"numFound":4,"start":0,"docs":[
                          {"iri":"https://example.org/EFO_1","ontology_name":"efo","label":"First"},
                          {"iri":"https://example.org/EFO_2","ontology_name":"efo","label":"Second"}
                        ]}}
                        """);
            }
            return response("/ols4/api/search", """
                    {"response":{"numFound":4,"start":2,"docs":[
                      {"iri":"https://example.org/EFO_1","ontology_name":"efo","label":"Duplicate"},
                      {"iri":"https://example.org/EFO_3","ontology_name":"efo","label":"Third"}
                    ]}}
                    """);
        };
        Ols4Provider provider = new Ols4Provider();
        ProviderPage first = provider.search(new ProviderSearchRequest(
                "ebi-ols", "term", List.of("efo"), "fr", 2, null), transport);
        ProviderPage second = provider.search(new ProviderSearchRequest(
                "ebi-ols", "term", List.of("efo"), "fr", 2, first.continuation()), transport);

        assertEquals(List.of("https://example.org/EFO_1", "https://example.org/EFO_2"),
                first.items().stream().map(ProviderResult::entityIri).toList());
        assertEquals(List.of("https://example.org/EFO_3"),
                second.items().stream().map(ProviderResult::entityIri).toList());
        assertEquals("fr", first.items().get(0).labels().get(0).language());
        assertEquals("fr", requests.get(0).query().get("lang"));
        assertEquals("2", requests.get(1).query().get("start"));
        assertNull(second.continuation());

        ProviderFailure rebound = assertThrows(ProviderFailure.class,
                () -> provider.search(new ProviderSearchRequest(
                                "ebi-ols", "different", List.of("efo"), "fr", 2,
                                first.continuation()),
                        request -> { throw new AssertionError("rebound cursor must fail before I/O"); }));
        assertEquals("provider_cursor_invalid", rebound.code());
    }

    @Test
    void inspectCombinesTermAndOntologyMetadata() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            if (request.relativePath().equals("/api/ontologies/efo")) {
                return response("/ols4/api/ontologies/efo", """
                        {"ontologyId":"efo","updated":"2026-07-20T00:39:50.254296746","version":"3.92.0",
                         "config":{"versionIri":"https://example.org/efo/3.92.0",
                                   "license":"https://example.org/license"}}
                        """, 2);
            }
            return response("/ols4" + request.relativePath(), """
                    {"iri":"https://example.org/EFO_1","lang":"en","description":["death"],
                     "synonyms":["programmed cell death"],"label":"Apoptosis",
                     "ontology_name":"efo","ontology_iri":"https://example.org/efo.owl",
                     "is_obsolete":true,"term_replaced_by":["https://example.org/EFO_2"]}
                    """, 1);
        };

        ProviderResult result = new Ols4Provider().inspect(new ProviderInspectRequest(
                "ebi-ols", "efo", "https://example.org/EFO_1", "en"), transport);

        assertEquals("3.92.0", result.providerVersion());
        assertEquals("https://example.org/license", result.license());
        assertEquals("https://example.org/efo/3.92.0", result.provenance());
        assertTrue(result.deprecated());
        assertEquals("https://example.org/EFO_2", result.replacedBy());
        assertEquals(Instant.parse("2026-07-20T00:39:50.254296746Z"),
                result.providerTimestamp());
        assertEquals(3, result.retries());
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).relativePath().contains("https%253A%252F%252F"));
    }

    @Test
    void searchAcceptsRealisticSynonymVolumeAndRejectsFilterOrOffsetDrift() throws Exception {
        String synonyms = java.util.stream.IntStream.range(0, 144)
                .mapToObj(index -> "\"synonym " + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String body = "{\"response\":{\"numFound\":1,\"start\":0,\"docs\":[{"
                + "\"iri\":\"https://example.org/GO_1\",\"ontology_name\":\"go\","
                + "\"label\":\"Process\",\"synonym\":[" + synonyms + "]}]}}";
        ProviderPage page = new Ols4Provider().search(new ProviderSearchRequest(
                "ols", "process", List.of("go"), "en", 10, null),
                request -> response("/ols4/api/search", body));
        assertEquals(144, page.items().get(0).synonyms().size());

        ProviderFailure escaped = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().search(new ProviderSearchRequest(
                                "ols", "process", List.of("efo"), "en", 10, null),
                        request -> response("/ols4/api/search", body)));
        assertEquals("provider_response_invalid", escaped.code());

        ProviderFailure offset = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().search(new ProviderSearchRequest(
                                "ols", "process", List.of(), "en", 10, null),
                        request -> response("/ols4/api/search",
                                "{\"response\":{\"numFound\":0,\"start\":1,\"docs\":[]}}")));
        assertEquals("provider_response_invalid", offset.code());

        ProviderFailure fractional = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().search(new ProviderSearchRequest(
                                "ols", "process", List.of(), "en", 10, null),
                        request -> response("/ols4/api/search",
                                "{\"response\":{\"numFound\":1.5,\"start\":0,\"docs\":[]}}")));
        assertEquals("provider_response_invalid", fractional.code());
    }

    @Test
    void responseTextBoundsCountUnicodeCodePoints() throws Exception {
        String astral = "\uD83D\uDE00";
        String accepted = "{\"response\":{\"numFound\":1,\"start\":0,\"docs\":[{"
                + "\"iri\":\"https://example.org/EFO_1\",\"ontology_name\":\"efo\","
                + "\"label\":\"" + astral.repeat(4_096) + "\"}]}}";
        ProviderPage page = new Ols4Provider().search(new ProviderSearchRequest(
                "ols", "term", List.of("efo"), "en", 1, null),
                request -> response("/ols4/api/search", accepted));
        assertEquals(4_096, page.items().get(0).labels().get(0).value()
                .codePointCount(0, page.items().get(0).labels().get(0).value().length()));

        String rejected = accepted.replace(astral.repeat(4_096), astral.repeat(4_097));
        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().search(new ProviderSearchRequest(
                        "ols", "term", List.of("efo"), "en", 1, null),
                        request -> response("/ols4/api/search", rejected)));
        assertEquals("provider_response_invalid", failure.code());
    }

    @Test
    void malformedOrMismatchedResponsesFailAtomically() {
        Ols4Provider provider = new Ols4Provider();
        ProviderFailure malformed = assertThrows(ProviderFailure.class,
                () -> provider.search(new ProviderSearchRequest(
                                "ols", "term", List.of(), "en", 10, null),
                        request -> response("/ols4/api/search", "{not-json")));
        assertEquals("provider_response_invalid", malformed.code());
        assertNull(malformed.getCause());

        ProviderFailure duplicateKey = assertThrows(ProviderFailure.class,
                () -> provider.search(new ProviderSearchRequest(
                                "ols", "term", List.of(), "en", 10, null),
                        request -> response("/ols4/api/search",
                                "{\"response\":{},\"response\":{}}")));
        assertEquals("provider_response_invalid", duplicateKey.code());
        assertNull(duplicateKey.getCause());

        ProviderFailure cursor = assertThrows(ProviderFailure.class,
                () -> provider.search(new ProviderSearchRequest(
                                "ols", "term", List.of(), "en", 10, "v1.1.not_base64!"),
                        request -> { throw new AssertionError("invalid cursor must fail before I/O"); }));
        assertEquals("provider_cursor_invalid", cursor.code());

        ProviderTransport mismatch = request -> request.relativePath().equals("/api/ontologies/efo")
                ? response("/ols4/api/ontologies/efo", "{}")
                : response("/ols4" + request.relativePath(), """
                        {"iri":"https://example.org/other","label":"Wrong",
                         "ontology_name":"efo","is_obsolete":false}
                        """);
        ProviderFailure wrong = assertThrows(ProviderFailure.class,
                () -> provider.inspect(new ProviderInspectRequest(
                        "ols", "efo", "https://example.org/EFO_1", "en"), mismatch));
        assertEquals("provider_response_invalid", wrong.code());
    }

    @Test
    void mismatchedOntologyMetadataAndExcessiveTermIriFailWithTypedErrors() {
        ProviderTransport mismatchedMetadata = request -> request.relativePath()
                .equals("/api/ontologies/efo")
                ? response("/ols4/api/ontologies/efo", "{\"ontologyId\":\"go\"}")
                : response("/ols4" + request.relativePath(), """
                        {"iri":"https://example.org/EFO_1","label":"Term",
                         "ontology_name":"efo","is_obsolete":false}
                        """);
        ProviderFailure mismatch = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().inspect(new ProviderInspectRequest(
                        "ols", "efo", "https://example.org/EFO_1", "en"), mismatchedMetadata));
        assertEquals("provider_response_invalid", mismatch.code());

        ProviderTransport invalidLanguage = request -> request.relativePath()
                .equals("/api/ontologies/efo")
                ? response("/ols4/api/ontologies/efo", "{\"ontologyId\":\"efo\"}")
                : response("/ols4" + request.relativePath(), """
                        {"iri":"https://example.org/EFO_1","label":"Term","lang":"en_US",
                         "ontology_name":"efo","is_obsolete":false}
                        """);
        ProviderFailure language = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().inspect(new ProviderInspectRequest(
                        "ols", "efo", "https://example.org/EFO_1", "en"), invalidLanguage));
        assertEquals("provider_response_invalid", language.code());

        ProviderTransport invalidIriMetadata = request -> request.relativePath()
                .equals("/api/ontologies/efo")
                ? response("/ols4/api/ontologies/efo", "{\"ontologyId\":\"efo\"}")
                : response("/ols4" + request.relativePath(), """
                        {"iri":"https://example.org/EFO_1","label":"Term",
                         "ontology_name":"efo","ontology_iri":"not-an-iri",
                         "term_replaced_by":"also-not-an-iri","is_obsolete":false}
                        """);
        ProviderFailure invalidIri = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().inspect(new ProviderInspectRequest(
                        "ols", "efo", "https://example.org/EFO_1", "en"), invalidIriMetadata));
        assertEquals("provider_response_invalid", invalidIri.code());

        String iri = "https://example.org/" + "\u00FC".repeat(4_000);
        ProviderFailure tooLarge = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().inspect(new ProviderInspectRequest(
                                "ols", "efo", iri, "en"),
                        request -> { throw new AssertionError("oversized path must fail before I/O"); }));
        assertEquals("provider_request_invalid", tooLarge.code());
    }

    @Test
    void malformedNestedMetadataAndBlankReplacementFailClosed() {
        ProviderTransport badConfig = request -> request.relativePath()
                .equals("/api/ontologies/efo")
                ? response("/ols4/api/ontologies/efo",
                        "{\"ontologyId\":\"efo\",\"config\":\"invalid\"}")
                : response("/ols4" + request.relativePath(), """
                        {"iri":"https://example.org/EFO_1","label":"Term",
                         "ontology_name":"efo","is_obsolete":false}
                        """);
        ProviderFailure config = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().inspect(new ProviderInspectRequest(
                        "ols", "efo", "https://example.org/EFO_1", "en"), badConfig));
        assertEquals("provider_response_invalid", config.code());

        ProviderTransport blankReplacement = request -> request.relativePath()
                .equals("/api/ontologies/efo")
                ? response("/ols4/api/ontologies/efo", "{\"ontologyId\":\"efo\"}")
                : response("/ols4" + request.relativePath(), """
                        {"iri":"https://example.org/EFO_1","label":"Term",
                         "ontology_name":"efo","term_replaced_by":" ",
                         "is_obsolete":true}
                        """);
        ProviderFailure replacement = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().inspect(new ProviderInspectRequest(
                        "ols", "efo", "https://example.org/EFO_1", "en"), blankReplacement));
        assertEquals("provider_response_invalid", replacement.code());
    }

    @Test
    void jsonTokenAmplificationIsRejectedWithoutRetainingVendorCause() {
        String body = "{\"response\":{\"numFound\":0,\"start\":0,\"docs\":["
                + "0,".repeat(250_001) + "0]}}";
        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> new Ols4Provider().search(new ProviderSearchRequest(
                                "ols", "term", List.of(), "en", 10, null),
                        request -> response("/ols4/api/search", body)));
        assertEquals("provider_response_invalid", failure.code());
        assertNull(failure.getCause());
    }

    private static ProviderResponse response(String path, String body) {
        return response(path, body, 0);
    }

    private static ProviderResponse response(String path, String body, int retries) {
        return new ProviderResponse(body.getBytes(StandardCharsets.UTF_8),
                URI.create("https://www.ebi.ac.uk" + path), FETCHED, retries);
    }
}
