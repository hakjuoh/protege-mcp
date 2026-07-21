package io.github.hakjuoh.protege_mcp.external;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Canonical owner-home roots for provider configuration, credentials, and cache state. */
final class ProviderLocalPaths {

    private ProviderLocalPaths() { }

    static Path providers() throws ProviderFailure {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new ProviderFailure("provider_store_invalid",
                    "Owner home directory is unavailable", false);
        }
        try {
            Path realHome = Path.of(home).toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path application = OwnerOnlyFiles.prepareDirectory(realHome.resolve(".protege-mcp"));
            return OwnerOnlyFiles.prepareDirectory(application.resolve("providers"));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ProviderFailure("provider_store_invalid",
                    "Owner home directory is invalid", false);
        }
    }

    static Path credentials() throws ProviderFailure {
        return OwnerOnlyFiles.prepareDirectory(providers().resolve("credentials"));
    }

    static Path cache() throws ProviderFailure {
        return OwnerOnlyFiles.prepareDirectory(providers().resolve("cache"));
    }
}
