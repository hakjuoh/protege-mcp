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

/** Verifies the shared OntoPortal adapter against the AgroPortal preset contract. */
class AgroPortalPresetCompatibilityTest {

    private static final Instant FETCHED = Instant.parse("2026-07-20T01:00:00Z");

    @Test
    void searchMapsAgronomyTermsAndOntologyAcronym() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            return response("/search", """
                    {
                      "page": 1,
                      "pageCount": 1,
                      "totalCount": 1,
                      "nextPage": null,
                      "collection": [
                        {
                          "@id": "http://aims.fao.org/aos/agrovoc/c_1234",
                          "@type": "http://www.w3.org/2002/07/owl#Class",
                          "prefLabel": "maize",
                          "synonym": ["corn", "Zea mays"],
                          "definition": ["A tall annual cereal grass bearing kernels on large ears."],
                          "obsolete": false,
                          "links": {
                            "ontology": "https://data.agroportal.eu/ontologies/AGROVOC"
                          }
                        }
                      ]
                    }
                    """);
        };

        ProviderPage page = new OntoPortalProvider().search(new ProviderSearchRequest(
                "agro", "maize", List.of("agrovoc"), "en", 10, null), transport);

        assertEquals(1, page.items().size());
        assertEquals(1, page.total());
        assertNull(page.continuation());
        assertEquals("ontoportal", page.items().get(0).profile());
        assertEquals("agrovoc", page.items().get(0).sourceOntology());
        assertEquals("exact_label", page.items().get(0).matchExplanation());
        assertEquals("http://aims.fao.org/aos/agrovoc/c_1234", page.items().get(0).entityIri());
        assertEquals("maize", page.items().get(0).labels().get(0).value());
        assertEquals(2, page.items().get(0).synonyms().size());
        assertEquals(1, requests.size());
        assertEquals("/search", requests.get(0).relativePath());
        assertEquals("AGROVOC", requests.get(0).query().get("ontologies"));
        assertEquals("10", requests.get(0).query().get("pagesize"));
        assertFalse(requests.get(0).query().containsKey("include"));
        assertEquals("json", requests.get(0).query().get("format"));
        assertEquals("false", requests.get(0).query().get("include_views"));
        assertEquals("false", requests.get(0).query().get("display_context"));
        assertEquals("true", requests.get(0).query().get("display_links"));
    }

    @Test
    void inspectRetrievesAgroPortalClassAndOntology() throws Exception {
        List<ProviderRequest> requests = new ArrayList<>();
        ProviderTransport transport = request -> {
            requests.add(request);
            if (request.relativePath().equals("/ontologies/AGROVOC")) {
                return response("/ontologies/AGROVOC", """
                        {
                          "acronym": "AGROVOC",
                          "name": "AGROVOC Multilingual Agricultural Thesaurus",
                          "@id": "https://data.agroportal.eu/ontologies/AGROVOC",
                          "links": {"latest_submission":"https://data.agroportal.eu/ontologies/AGROVOC/latest_submission"}
                        }
                        """);
            }
            if (request.relativePath().endsWith("/latest_submission")) {
                return response(request.relativePath(), """
                        {"submissionId":17,"version":"2026-06",
                         "released":"2026-06-01T00:00:00Z",
                         "hasLicense":"https://creativecommons.org/licenses/by/4.0/",
                         "uri":"http://aims.fao.org/aos/agrovoc",
                         "links":{"ontology":"https://data.agroportal.eu/ontologies/AGROVOC"}}
                        """);
            }
            return response(request.relativePath(), """
                    {
                      "@id": "http://aims.fao.org/aos/agrovoc/c_1234",
                      "@type": "http://www.w3.org/2002/07/owl#Class",
                      "prefLabel": "maize",
                      "synonym": ["corn"],
                      "definition": ["Cereal grass"],
                      "obsolete": false,
                      "links": {
                        "ontology": "https://data.agroportal.eu/ontologies/AGROVOC"
                      }
                    }
                    """);
        };

        ProviderResult result = new OntoPortalProvider().inspect(new ProviderInspectRequest(
                "agro", "agrovoc", "http://aims.fao.org/aos/agrovoc/c_1234", "en"), transport);

        assertEquals("2026-06", result.providerVersion());
        assertEquals("https://creativecommons.org/licenses/by/4.0/", result.license());
        assertEquals("AGROVOC Multilingual Agricultural Thesaurus", result.provenance());
        assertFalse(result.deprecated());
        assertNull(result.replacedBy());
        assertEquals("ontoportal", result.profile());
        assertEquals("agrovoc", result.sourceOntology());
        assertEquals(List.of(
                "/ontologies/AGROVOC/classes/http%3A%2F%2Faims.fao.org%2Faos%2Fagrovoc%2Fc_1234",
                "/ontologies/AGROVOC",
                "/ontologies/AGROVOC/latest_submission"),
                requests.stream().map(ProviderRequest::relativePath).toList());
        assertTrue(requests.stream().allMatch(request ->
                "json".equals(request.query().get("format"))
                        && "false".equals(request.query().get("display_context"))
                        && "true".equals(request.query().get("display_links"))));
        assertTrue(requests.stream().noneMatch(request -> request.query().containsKey("include")));
        assertFalse(requests.stream().anyMatch(request ->
                request.query().containsKey("download_format")));
    }

    private static ProviderResponse response(String path, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new ProviderResponse(bytes, URI.create("https://data.agroportal.eu" + path),
                FETCHED, 0);
    }
}
