package io.github.hakjuoh.protege_mcp.external;

/** The only network capability exposed to provider adapters. */
@FunctionalInterface
public interface ProviderTransport {

    /** Executes one bounded read and maps every transport error to a content-free typed failure. */
    ProviderResponse get(ProviderRequest request) throws ProviderFailure;
}
