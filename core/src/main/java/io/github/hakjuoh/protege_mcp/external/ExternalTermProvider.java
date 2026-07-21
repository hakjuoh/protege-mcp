package io.github.hakjuoh.protege_mcp.external;

/** Provider SPI. Implementations see only normalized inputs and a restricted read transport. */
public interface ExternalTermProvider {

    String profile();

    ProviderPage search(ProviderSearchRequest request, ProviderTransport transport) throws ProviderFailure;

    ProviderResult inspect(ProviderInspectRequest request, ProviderTransport transport)
            throws ProviderFailure;
}
