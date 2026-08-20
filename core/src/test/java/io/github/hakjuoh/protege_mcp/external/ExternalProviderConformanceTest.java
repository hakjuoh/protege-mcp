package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Runs the same normalized evidence contract against every released external profile. */
class ExternalProviderConformanceTest {

    private static final Instant FETCHED = Instant.parse("2026-08-18T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    void capturedFixturesConformAcrossSearchInspectPaginationAndFailures(ProfileCase profile)
            throws Exception {
        JsonNode manifest = JSON.readTree(fixture(profile, "manifest.json"));
        assertEquals(profile.provider().profile(), manifest.path("profile").asText());
        assertFalse(manifest.path("network_required").asBoolean(true));
        assertFalse(manifest.path("api_profile").asText().isBlank());
        assertTrue(manifest.path("source_commit").asText("").matches("[0-9a-f]{40}")
                || profile.ols4());

        ProviderSearchRequest request = new ProviderSearchRequest("provider", profile.query(),
                List.of(profile.ontology()), "en", 1, null);
        ProviderPage first = profile.provider().search(request, transport(profile));
        ProviderPage second = profile.provider().search(new ProviderSearchRequest("provider",
                profile.query(), List.of(profile.ontology()), "en", 1, first.continuation()),
                transport(profile));

        assertEquals(2, first.total());
        assertNotNull(first.continuation());
        assertEquals(profile.firstLabel(), first.items().get(0).labels().get(0).value());
        assertEquals(profile.firstIri(), first.items().get(0).entityIri());
        assertEquals(profile.secondIri(), second.items().get(0).entityIri());
        assertNull(second.continuation());
        String expectedLanguage = profile.ols4() ? "en" : "und";
        assertEquals(expectedLanguage, first.items().get(0).labels().get(0).language());

        ProviderResult inspected = profile.provider().inspect(new ProviderInspectRequest(
                "provider", profile.ontology(), profile.firstIri(), "en"), transport(profile));
        assertEquals(profile.version(), inspected.providerVersion());
        assertEquals(profile.license(), inspected.license());
        assertTrue(inspected.deprecated());
        assertEquals(profile.secondIri(), inspected.replacedBy());
        assertNotNull(inspected.provenance());

        ProviderFailure malformed = assertThrows(ProviderFailure.class,
                () -> profile.provider().search(request, ignored -> response(profile,
                        "/search", fixture(profile, "malformed.json"))));
        assertEquals("provider_response_invalid", malformed.code());
        assertNull(malformed.getCause());

    }

    private static ProviderTransport transport(ProfileCase profile) {
        return request -> {
            if (!profile.ols4()) {
                assertFalse(request.query().containsKey("include"),
                        "OntoPortal rejects obsolete and links in include, while its default "
                                + "representation supplies the complete reviewed fields");
            }
            String fixture;
            if (request.relativePath().contains("/terms/")
                    || request.relativePath().contains("/classes/")) {
                fixture = "term.json";
            } else if (request.relativePath().endsWith("/latest_submission")) {
                fixture = "latest-submission.json";
            } else if (request.relativePath().toUpperCase(java.util.Locale.ROOT).contains(
                    "/ONTOLOGIES/" + profile.ontology().toUpperCase(java.util.Locale.ROOT))) {
                fixture = "ontology.json";
            } else {
                String position = profile.ols4() ? request.query().get("start")
                        : request.query().get("page");
                fixture = firstPage(profile, position) ? "search.json" : "search-page-2.json";
            }
            return response(profile, request.relativePath(), fixture(profile, fixture));
        };
    }

    private static boolean firstPage(ProfileCase profile, String position) {
        return profile.ols4() ? "0".equals(position) : "1".equals(position);
    }

    private static ProviderResponse response(ProfileCase profile, String path, String body) {
        return new ProviderResponse(body.getBytes(StandardCharsets.UTF_8),
                URI.create(profile.origin() + path), FETCHED, 0);
    }

    private static String fixture(ProfileCase profile, String name) throws ProviderFailure {
        String resource = "/external/" + profile.fixtureRoot() + "/" + name;
        try (var stream = ExternalProviderConformanceTest.class.getResourceAsStream(resource)) {
            if (stream == null) throw new ProviderFailure("provider_fixture_missing",
                    "Checked-in provider fixture is missing", false);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new ProviderFailure("provider_fixture_invalid",
                    "Checked-in provider fixture cannot be read", false);
        }
    }

    private static Stream<ProfileCase> profiles() {
        return Stream.of(
                new ProfileCase("OLS4", new Ols4Provider(), "ols4/documented-2026-08-18",
                        "https://www.ebi.ac.uk/ols4", true, "term", "test", "Term",
                        "https://example.org/T1", "https://example.org/T2", "1.0",
                        "https://example.org/license"),
                new ProfileCase("BioPortal preset", new OntoPortalProvider(),
                        "bioportal/documented-2026-08-18",
                        "https://data.bioontology.org", false, "term", "test", "Term",
                        "https://example.org/T1", "https://example.org/T2", "1.0",
                        "https://example.org/license"),
                new ProfileCase("AgroPortal preset", new OntoPortalProvider(),
                        "agroportal/documented-2026-08-18",
                        "https://data.agroportal.eu", false, "maize", "agrovoc", "maize",
                        "http://aims.fao.org/aos/agrovoc/c_12332",
                        "http://aims.fao.org/aos/agrovoc/c_12333", "2026-08",
                        "https://creativecommons.org/licenses/by/4.0/"));
    }

    private record ProfileCase(String name, ExternalTermProvider provider, String fixtureRoot,
            String origin, boolean ols4, String query, String ontology, String firstLabel,
            String firstIri, String secondIri, String version, String license) {
        @Override public String toString() { return name; }
    }
}
