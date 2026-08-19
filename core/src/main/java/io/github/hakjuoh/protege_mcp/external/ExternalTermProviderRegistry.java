package io.github.hakjuoh.protege_mcp.external;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Registry of supported {@link ExternalTermProvider} implementations keyed by profile name.
 * Provides fail-closed lookup for unconfigured or unknown profiles.
 */
public final class ExternalTermProviderRegistry {

    private static final ExternalTermProviderRegistry DEFAULT = new ExternalTermProviderRegistry(Map.of(
            Ols4Provider.PROFILE, new Ols4Provider(),
            OntoPortalProvider.PROFILE, new OntoPortalProvider()));

    private final Map<String, ExternalTermProvider> providers;

    public ExternalTermProviderRegistry(Map<String, ExternalTermProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        Map<String, ExternalTermProvider> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ExternalTermProvider> entry : providers.entrySet()) {
            String profile = entry.getKey();
            ExternalTermProvider provider = entry.getValue();
            if (profile == null || profile.isBlank() || provider == null) {
                throw new IllegalArgumentException("profile and provider must not be null or blank");
            }
            if (!profile.equals(provider.profile())) {
                throw new IllegalArgumentException("profile key '" + profile
                        + "' does not match provider profile '" + provider.profile() + "'");
            }
            copy.put(profile, provider);
        }
        this.providers = Collections.unmodifiableMap(copy);
    }

    public static ExternalTermProviderRegistry defaultRegistry() {
        return DEFAULT;
    }

    public static ExternalTermProviderRegistry of(ExternalTermProvider... providers) {
        Map<String, ExternalTermProvider> map = new LinkedHashMap<>();
        for (ExternalTermProvider provider : providers) {
            if (provider == null) {
                throw new IllegalArgumentException("provider must not be null");
            }
            map.put(provider.profile(), provider);
        }
        return new ExternalTermProviderRegistry(map);
    }

    public ExternalTermProvider require(String profile) throws ProviderFailure {
        if (profile == null || profile.isBlank()) {
            throw new ProviderFailure("provider_profile_unsupported",
                    "External provider profile is not registered", false);
        }
        ExternalTermProvider provider = providers.get(profile);
        if (provider == null) {
            throw new ProviderFailure("provider_profile_unsupported",
                    "External provider profile is not registered", false);
        }
        return provider;
    }

    public boolean supports(String profile) {
        return profile != null && providers.containsKey(profile);
    }

    public Set<String> supportedProfiles() {
        return providers.keySet();
    }
}
