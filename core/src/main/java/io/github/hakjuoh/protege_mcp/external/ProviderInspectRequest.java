package io.github.hakjuoh.protege_mcp.external;

import java.net.URI;
import java.util.Locale;

/** Provider-neutral direct term-inspection input. */
public record ProviderInspectRequest(String providerId, String ontology, String iri,
        String language) {

    public ProviderInspectRequest {
        providerId = ProviderSearchRequest.identifier(providerId, "provider_id");
        ontology = ProviderSearchRequest.identifier(ontology, "ontology");
        ProviderFailure.requireText(iri, "iri", 4_096);
        URI parsed;
        try {
            parsed = URI.create(iri);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("iri must be absolute", invalid);
        }
        if (!parsed.isAbsolute() || parsed.getScheme() == null || parsed.getScheme().length() < 2) {
            throw new IllegalArgumentException("iri must be absolute");
        }
        language = language == null || language.isBlank() ? "en"
                : ProviderSearchRequest.identifier(language.toLowerCase(Locale.ROOT), "language");
    }
}
