package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Ols4ProviderNetworkIntegrationTest {

    @TempDir
    Path temporary;

    @Test
    void adapterRunsThroughPinnedTlsExecutorForSearchRetryAndInspection() throws Exception {
        try (ProviderTlsFixture tls = new ProviderTlsFixture("127.0.0.1")) {
            tls.server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            tls.server.enqueue(json("""
                    {"response":{"numFound":1,"start":0,"docs":[
                      {"iri":"https://example.org/EFO_1","ontology_name":"efo",
                       "label":"Cell death","type":"class","synonym":["apoptosis"]}
                    ]}}
                    """));
            tls.server.enqueue(json("""
                    {"iri":"https://example.org/EFO_1","lang":"en",
                     "label":"Cell death","ontology_name":"efo","type":"class",
                     "ontology_iri":"https://example.org/efo.owl"}
                    """));
            tls.server.enqueue(json("""
                    {"ontologyId":"efo","version":"3.92.0",
                     "config":{"versionIri":"https://example.org/efo/3.92.0",
                               "license":"https://example.org/license"}}
                    """));

            Path configRoot = temporary.resolve("config");
            String origin = tls.uri("/ols4").toString();
            String config = "{\"version\":1,\"origins\":[{\"alias\":\"origin\","
                    + "\"profile\":\"ols4\",\"origin\":\"" + origin + "\","
                    + "\"test_only_loopback\":true}],\"credentials\":[]}";
            OwnerOnlyFiles.write(configRoot, ProviderOwnerConfig.FILE_NAME,
                    config.getBytes(StandardCharsets.UTF_8));
            ProviderOwnerConfig.ResolvedProvider authority = ProviderOwnerConfig.load(configRoot)
                    .resolve("origin", "ebi-ols", "ols4", null, "project-fingerprint");
            List<URI> gates = new ArrayList<>();
            List<Duration> sleeps = new ArrayList<>();
            ProviderNetworkExecutor executor = new ProviderNetworkExecutor(authority,
                    new OwnerCredentialStore(temporary.resolve("credentials")), gates::add,
                    host -> new InetAddress[] {InetAddress.getLoopbackAddress()}, tls.engine,
                    Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                    sleeps::add);
            Ols4Provider provider = new Ols4Provider();

            ProviderPage page = provider.search(new ProviderSearchRequest(
                    "ebi-ols", "cell death", List.of("efo"), "en", 10, null), executor);
            ProviderResult inspected = provider.inspect(new ProviderInspectRequest(
                    "ebi-ols", "efo", "https://example.org/EFO_1", "en"), executor);

            assertEquals(1, page.items().size());
            assertEquals(1, page.retries());
            assertEquals(List.of(Duration.ofMillis(250)), sleeps);
            assertFalse(page.items().get(0).sourceUrl().toString().contains("cell"));
            assertEquals("3.92.0", inspected.providerVersion());
            assertEquals("https://example.org/license", inspected.license());
            assertEquals("https://example.org/efo/3.92.0", inspected.provenance());
            assertTrue(inspected.resultFingerprint().startsWith("sha256:"));
            assertFalse(inspected.sourceUrl().toString().contains("?"));

            RecordedRequest firstSearch = tls.server.takeRequest();
            RecordedRequest retriedSearch = tls.server.takeRequest();
            RecordedRequest term = tls.server.takeRequest();
            RecordedRequest ontology = tls.server.takeRequest();
            assertEquals("cell death", firstSearch.getRequestUrl().queryParameter("q"));
            assertEquals("cell death", retriedSearch.getRequestUrl().queryParameter("q"));
            assertEquals("efo", retriedSearch.getRequestUrl().queryParameter("ontology"));
            assertTrue(term.getPath().contains("https%253A%252F%252Fexample.org%252FEFO_1"),
                    term::getPath);
            assertEquals("/ols4/api/ontologies/efo?lang=en", ontology.getPath());
            assertEquals(4, gates.size());
            URI networkOrigin = URI.create("https://127.0.0.1:" + tls.server.getPort());
            assertTrue(gates.stream().allMatch(value -> value.equals(networkOrigin)));
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body);
    }
}
