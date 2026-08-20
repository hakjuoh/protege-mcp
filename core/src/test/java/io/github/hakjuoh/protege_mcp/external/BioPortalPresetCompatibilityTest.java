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

/** Verifies the shared OntoPortal adapter against the BioPortal preset contract. */
class BioPortalPresetCompatibilityTest {

    private static final Instant FETCHED = Instant.parse("2026-07-20T01:00:00Z");

    @Test
    void searchMapsEvidenceRankingAndContinuation() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            return response("/search", """
                    {
                      "page": 1,
                      "pageCount": 3,
                      "totalCount": 5,
                      "nextPage": 2,
                      "collection": [
                        {
                          "@id": "http://purl.obolibrary.org/obo/DOID_4",
                          "@type": "http://www.w3.org/2002/07/owl#Class",
                          "prefLabel": "disease",
                          "synonym": ["condition", "disorder"],
                          "definition": ["A disease is a disposition to undergo pathological processes."],
                          "obsolete": false,
                          "links": {
                            "ontology": "https://data.bioontology.org/ontologies/DOID"
                          }
                        },
                        {
                          "@id": "http://purl.obolibrary.org/obo/NCIT_C2991",
                          "@type": "http://www.w3.org/2002/07/owl#Class",
                          "prefLabel": "Disease or Disorder",
                          "obsolete": false,
                          "links": {
                            "ontology": "https://data.bioontology.org/ontologies/NCIT"
                          }
                        }
                      ]
                    }
                    """);
        };

        ProviderPage page = new OntoPortalProvider().search(new ProviderSearchRequest(
                "ncbo", "disease", List.of("doid", "ncit"), "en", 2, null), transport);

        assertEquals(2, page.items().size());
        assertEquals(5, page.total());
        assertTrue(page.continuation().startsWith("v1.2."));
        assertEquals("exact_label", page.items().get(0).matchExplanation());
        assertEquals(1.0, page.items().get(0).score());
        assertEquals("http://purl.obolibrary.org/obo/DOID_4", page.items().get(0).entityIri());
        assertEquals("doid", page.items().get(0).sourceOntology());
        assertTrue(page.items().get(0).sourceUrl().toString()
                .contains("/ontologies/DOID/classes/http%3A%2F%2Fpurl.obolibrary.org%2Fobo%2FDOID_4"));
        assertEquals("/search", requests.get(0).relativePath());
        assertEquals("DOID,NCIT", requests.get(0).query().get("ontologies"));
        assertEquals("2", requests.get(0).query().get("pagesize"));
        assertEquals("1", requests.get(0).query().get("page"));
        assertEquals("json", requests.get(0).query().get("format"));
        assertEquals("false", requests.get(0).query().get("include_views"));
        assertEquals("false", requests.get(0).query().get("display_context"));
        assertEquals("true", requests.get(0).query().get("display_links"));
    }

    @Test
    void searchDeduplicatesAcrossPagesAndPreservesOrder() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            if ("1".equals(request.query().get("page"))) {
                return response("/search", """
                        {
                          "page": 1,
                          "pageCount": 2,
                          "totalCount": 4,
                          "nextPage": 2,
                          "collection": [
                            {"@id":"http://example.org/T1","prefLabel":"First","links":{"ontology":"https://data.bioontology.org/ontologies/DOID"}},
                            {"@id":"http://example.org/T2","prefLabel":"Second","links":{"ontology":"https://data.bioontology.org/ontologies/DOID"}}
                          ]
                        }
                        """);
            }
            return response("/search", """
                    {
                      "page": 2,
                      "pageCount": 2,
                      "totalCount": 4,
                      "nextPage": null,
                      "collection": [
                        {"@id":"http://example.org/T1","prefLabel":"Duplicate","links":{"ontology":"https://data.bioontology.org/ontologies/DOID"}},
                        {"@id":"http://example.org/T3","prefLabel":"Third","links":{"ontology":"https://data.bioontology.org/ontologies/DOID"}}
                      ]
                    }
                    """);
        };

        OntoPortalProvider provider = new OntoPortalProvider();
        ProviderPage first = provider.search(new ProviderSearchRequest(
                "ncbo", "term", List.of("doid"), "en", 2, null), transport);
        ProviderPage second = provider.search(new ProviderSearchRequest(
                "ncbo", "term", List.of("doid"), "en", 2, first.continuation()), transport);

        assertEquals(List.of("http://example.org/T1", "http://example.org/T2"),
                first.items().stream().map(ProviderResult::entityIri).toList());
        assertEquals(List.of("http://example.org/T3"),
                second.items().stream().map(ProviderResult::entityIri).toList());
        assertEquals("2", requests.get(1).query().get("page"));
        assertNull(second.continuation());
    }

    @Test
    void searchAcceptsAProviderSidePageSizeClamp() throws Exception {
        ProviderPage page = new OntoPortalProvider().search(new ProviderSearchRequest(
                "ncbo", "term", List.of("doid"), "en", 10, null), request ->
                response("/search", """
                        {"page":1,"pageCount":2,"totalCount":2,"nextPage":2,
                         "collection":[
                           {"@id":"http://example.org/T1","prefLabel":"First",
                            "links":{"ontology":"https://data.bioontology.org/ontologies/DOID"}}
                         ]}
                        """));

        assertEquals(1, page.items().size());
        assertTrue(page.continuation().startsWith("v1.2."));
    }

    @Test
    void inspectCombinesTermAndOntologyMetadata() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            if (request.relativePath().equals("/ontologies/DOID")) {
                return response("/ontologies/DOID", """
                        {
                          "acronym": "DOID",
                          "name": "Human Disease Ontology",
                          "@id": "https://data.bioontology.org/ontologies/DOID",
                          "links": {"latest_submission":"https://data.bioontology.org/ontologies/DOID/latest_submission"}
                        }
                        """, 1);
            }
            if (request.relativePath().endsWith("/latest_submission")) {
                return response(request.relativePath(), """
                        {"submissionId":42,"version":"2026-05-01",
                         "released":"2026-05-01T02:00:00+02:00",
                         "hasLicense":"https://creativecommons.org/publicdomain/zero/1.0/",
                         "uri":"http://purl.obolibrary.org/obo/doid.owl",
                         "links":{"ontology":"https://data.bioontology.org/ontologies/DOID"}}
                        """, 0);
            }
            return response(request.relativePath(), """
                    {
                      "@id": "http://purl.obolibrary.org/obo/DOID_4",
                      "@type": "http://www.w3.org/2002/07/owl#Class",
                      "prefLabel": "disease",
                      "synonym": ["condition"],
                      "definition": ["A disease definition"],
                      "obsolete": true,
                      "properties": {
                        "http://purl.obolibrary.org/obo/IAO_0100001": ["http://purl.obolibrary.org/obo/DOID_999"]
                      },
                      "links": {
                        "ontology": "https://data.bioontology.org/ontologies/DOID"
                      }
                    }
                    """, 1);
        };

        ProviderResult result = new OntoPortalProvider().inspect(new ProviderInspectRequest(
                "ncbo", "doid", "http://purl.obolibrary.org/obo/DOID_4", "en"), transport);

        assertEquals("2026-05-01", result.providerVersion());
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", result.license());
        assertEquals("Human Disease Ontology", result.provenance());
        assertTrue(result.deprecated());
        assertEquals("http://purl.obolibrary.org/obo/DOID_999", result.replacedBy());
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), result.providerTimestamp());
        assertEquals(2, result.retries());
        assertEquals(3, requests.size());
        assertTrue(requests.stream().allMatch(request ->
                "json".equals(request.query().get("format"))
                        && "false".equals(request.query().get("display_context"))
                        && "true".equals(request.query().get("display_links"))));
        assertTrue(requests.stream().noneMatch(request -> request.query().containsKey("include")));
        assertFalse(requests.stream().anyMatch(request ->
                request.query().containsKey("download_format")));
    }

    @Test
    void searchRejectsFilterDriftOrMalformedResponse() {
        OntoPortalProvider provider = new OntoPortalProvider();

        ProviderFailure escaped = assertThrows(ProviderFailure.class,
                () -> provider.search(new ProviderSearchRequest(
                                "ncbo", "cell", List.of("ncit"), "en", 10, null),
                        request -> response("/search", """
                                {
                                  "page": 1,
                                  "pageCount": 1,
                                  "totalCount": 1,
                                  "nextPage": null,
                                  "collection": [
                                    {"@id":"http://example.org/C1","prefLabel":"Cell","links":{"ontology":"https://data.bioontology.org/ontologies/DOID"}}
                                  ]
                                }
                                """)));
        assertEquals("provider_response_invalid", escaped.code());
        assertTrue(escaped.getMessage().contains("escaped the requested ontology filter"));

        ProviderFailure malformed = assertThrows(ProviderFailure.class,
                () -> provider.search(new ProviderSearchRequest(
                                "ncbo", "cell", List.of("ncit"), "en", 10, null),
                        request -> response("/search", "{ invalid json }")));
        assertEquals("provider_response_invalid", malformed.code());
    }

    private static ProviderResponse response(String path, String json) {
        return response(path, json, 0);
    }

    private static ProviderResponse response(String path, String json, int retries) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new ProviderResponse(bytes, URI.create("https://data.bioontology.org" + path),
                FETCHED, retries);
    }
}
