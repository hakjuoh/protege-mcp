package io.github.hakjuoh.protege_mcp.external;

import java.time.Duration;
import java.util.List;

/** Server-scoped provider execution boundary used by the public tool adapter. */
public interface ExternalProviderGateway extends AutoCloseable {

    SearchOutcome search(ProviderSessionScope scope, ProviderSearchRequest initialRequest,
            String cursor, InvocationResolver resolver) throws ProviderFailure;

    InspectOutcome inspect(ProviderInspectRequest request, InvocationResolver resolver)
            throws ProviderFailure;

    int revokeClient(String clientId);

    int revokeGrant(String clientId, String grantId);

    int clearWorkspace(String workspaceId);

    @Override
    void close();

    @FunctionalInterface
    interface InvocationResolver {
        Invocation resolve(String providerId) throws ProviderFailure;
    }

    record Invocation(String providerId, String profile, String originAlias,
            String credentialId, String projectFingerprint, Duration cacheTtl,
            boolean cacheReadAllowed, List<String> allowedOntologies,
            List<String> allowedLanguages, int maxResults,
            OwnerProviderCache.ProjectGate projectGate,
            ProviderNetworkExecutor.NetworkGate networkGate) {
        public Invocation {
            if (providerId == null || profile == null || originAlias == null
                    || projectFingerprint == null || projectFingerprint.isBlank()
                    || cacheTtl == null || cacheTtl.isNegative()
                    || cacheTtl.compareTo(Duration.ofHours(24)) > 0
                    || allowedOntologies == null || allowedOntologies.size() > 64
                    || allowedLanguages == null || allowedLanguages.size() > 16
                    || maxResults < 1 || maxResults > 100
                    || projectGate == null || networkGate == null) {
                throw new IllegalArgumentException("provider invocation is invalid");
            }
            allowedOntologies = List.copyOf(allowedOntologies);
            allowedLanguages = List.copyOf(allowedLanguages);
        }

        boolean cacheWriteAllowed() {
            return cacheReadAllowed && !cacheTtl.isZero();
        }
    }

    record SearchOutcome(String providerId, String profile, ProviderPage page,
            String nextCursor, boolean cacheHit) {
        public SearchOutcome {
            if (providerId == null || profile == null || page == null
                    || page.continuation() != null) {
                throw new IllegalArgumentException("public provider page is invalid");
            }
        }
    }

    record InspectOutcome(ProviderResult result, boolean cacheHit) {
        public InspectOutcome {
            if (result == null) throw new IllegalArgumentException("provider result is required");
        }
    }
}
