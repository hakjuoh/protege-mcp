package io.github.hakjuoh.protege_mcp.external;

/** Provider-neutral direct term-inspection input. */
public record ProviderInspectRequest(String providerId, String ontology, String iri,
        String language) {

    public ProviderInspectRequest {
        providerId = ProviderSearchRequest.identifier(providerId, "provider_id");
        ontology = ProviderSearchRequest.identifier(ontology, "ontology");
        iri = ProviderValues.absoluteIri(iri, "iri", 4_096);
        language = ProviderSearchRequest.language(language);
    }
}
