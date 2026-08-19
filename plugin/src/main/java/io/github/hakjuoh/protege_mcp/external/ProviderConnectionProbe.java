package io.github.hakjuoh.protege_mcp.external;

import java.util.Arrays;
import java.util.Map;

/** Safe owner-initiated liveness probe using the production pinned HTTPS transport. */
public final class ProviderConnectionProbe {

    private final TransportFactory transportFactory;

    public ProviderConnectionProbe() {
        this((authority, credentials) -> new ProviderNetworkExecutor(authority, credentials,
                ignored -> { }));
    }

    ProviderConnectionProbe(TransportFactory transportFactory) {
        this.transportFactory = java.util.Objects.requireNonNull(transportFactory,
                "transportFactory");
    }

    public ProbeResult probe(ProviderOwnerConfig.OriginBinding origin,
            ProviderOwnerConfig.CredentialBinding credential, byte[] pendingSecret)
            throws ProviderFailure {
        if (origin == null || credential != null
                && !credential.originAlias().equals(origin.alias())) {
            throw new ProviderFailure("provider_configuration_invalid",
                    "Probe binding is invalid", false);
        }
        byte[] secret = pendingSecret == null ? null : pendingSecret.clone();
        try {
            ProviderRequest request = probeRequest(origin.profile());
            ProviderOwnerConfig config = ProviderOwnerConfig.of(Map.of(origin.alias(), origin),
                    credential == null ? Map.of() : Map.of(credential.id(), credential));
            String providerId = credential == null ? origin.alias() : credential.providerId();
            String projectFingerprint = credential != null
                    && credential.projectFingerprint() != null
                            ? credential.projectFingerprint() : "owner-connection-probe";
            ProviderOwnerConfig.ResolvedProvider authority = config.resolve(origin.alias(),
                    providerId, origin.profile(), credential == null ? null : credential.id(),
                    projectFingerprint);
            ProviderNetworkExecutor.CredentialSource credentialSource = null;
            if (credential != null) {
                credentialSource = secret == null
                        ? new OwnerCredentialStore()
                        : id -> OwnerCredentialStore.ephemeral(id, secret);
            }
            ProviderTransport transport = transportFactory.create(authority, credentialSource);
            ProviderResponse response = transport.get(request);
            return new ProbeResult(response.sourceUrl().toString(), response.retries());
        } finally {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    private static ProviderRequest probeRequest(String profile) throws ProviderFailure {
        try {
            return switch (profile) {
                case Ols4Provider.PROFILE -> new ProviderRequest("/api", Map.of());
                case OntoPortalProvider.PROFILE ->
                    new ProviderRequest("/ontologies", Map.of(
                            "pagesize", "1", "format", "json", "include", "acronym",
                            "include_views", "false", "display_context", "false",
                            "display_links", "false"));
                default -> throw new ProviderFailure("provider_profile_unsupported",
                        "Provider profile is not supported by the connection probe", false);
            };
        } catch (IllegalArgumentException invalid) {
            throw new ProviderFailure("provider_request_invalid",
                    "Provider probe request is invalid", false);
        }
    }

    public record ProbeResult(String sourceUrl, int retries) {
        public ProbeResult {
            if (sourceUrl == null || sourceUrl.isBlank() || retries < 0) {
                throw new IllegalArgumentException("probe result is invalid");
            }
        }
    }

    @FunctionalInterface
    interface TransportFactory {
        ProviderTransport create(ProviderOwnerConfig.ResolvedProvider authority,
                ProviderNetworkExecutor.CredentialSource credentials) throws ProviderFailure;
    }
}
