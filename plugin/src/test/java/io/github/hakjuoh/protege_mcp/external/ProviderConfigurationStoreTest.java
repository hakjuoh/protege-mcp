package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderConfigurationStoreTest {

    @TempDir Path temporary;

    @Test
    void roundTripPreservesMultipleProjectScopedCredentialsAndRotatesExplicitSecrets()
            throws Exception {
        Path configRoot = temporary.resolve("providers");
        Path credentialRoot = temporary.resolve("credentials");
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", java.net.URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig.CredentialBinding first = new ProviderOwnerConfig.CredentialBinding(
                "team-a", "disease-provider", "ncbo",
                ProviderOwnerConfig.AuthScheme.ONTOPORTAL_API_KEY, "Authorization", "project-a");
        ProviderOwnerConfig.CredentialBinding second = new ProviderOwnerConfig.CredentialBinding(
                "team-b", "disease-provider", "ncbo",
                ProviderOwnerConfig.AuthScheme.ONTOPORTAL_API_KEY, "Authorization", "project-b");
        ProviderOwnerConfig initial = ProviderOwnerConfig.of(Map.of(origin.alias(), origin),
                Map.of(first.id(), first, second.id(), second));
        ProviderOwnerConfig.save(configRoot, initial);
        OwnerCredentialStore secrets = new OwnerCredentialStore(credentialRoot);
        secrets.rotate(first.id(), "secret-a".getBytes(StandardCharsets.US_ASCII));
        secrets.rotate(second.id(), "secret-b".getBytes(StandardCharsets.US_ASCII));
        ProviderConfigurationStore store = new ProviderConfigurationStore(configRoot, credentialRoot);

        store.save(store.load(), Map.of());

        ProviderOwnerConfig unchanged = store.load();
        assertEquals(first, unchanged.credentials().get(first.id()));
        assertEquals(second, unchanged.credentials().get(second.id()));
        try (OwnerCredentialStore.CredentialLease lease = secrets.open(first.id())) {
            assertArrayEquals("secret-a".getBytes(StandardCharsets.US_ASCII), lease.copySecret());
        }

        ProviderOwnerConfig updated = ProviderOwnerConfig.of(Map.of(origin.alias(), origin),
                Map.of(second.id(), second));
        store.save(updated, Map.of(second.id(),
                "replacement-b".getBytes(StandardCharsets.US_ASCII)));

        assertEquals(updated.credentials(), store.load().credentials());
        assertEquals("provider_credential_missing", assertThrows(ProviderFailure.class,
                () -> secrets.open(first.id())).code());
        try (OwnerCredentialStore.CredentialLease lease = secrets.open(second.id())) {
            assertArrayEquals("replacement-b".getBytes(StandardCharsets.US_ASCII),
                    lease.copySecret());
        }
    }

    @Test
    void pendingSecretMustBelongToTheSavedConfiguration() throws Exception {
        ProviderConfigurationStore store = new ProviderConfigurationStore(
                temporary.resolve("invalid-providers"), temporary.resolve("invalid-credentials"));
        ProviderFailure failure = assertThrows(ProviderFailure.class, () -> store.save(
                ProviderOwnerConfig.empty(), Map.of("orphan", new byte[] {'x'})));
        assertEquals("provider_configuration_invalid", failure.code());
        assertEquals(0, store.load().credentials().size());
    }

    @Test
    void failedMetadataCommitRestoresCredentialsAndAbsentConfiguration() throws Exception {
        Path configRoot = temporary.resolve("rollback-providers");
        Path credentialRoot = temporary.resolve("rollback-credentials");
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ncbo", "ontoportal", java.net.URI.create("https://data.bioontology.org"));
        ProviderOwnerConfig.CredentialBinding credential =
                new ProviderOwnerConfig.CredentialBinding("team-a", "disease-provider", "ncbo",
                        ProviderOwnerConfig.AuthScheme.ONTOPORTAL_API_KEY,
                        "Authorization", null);
        ProviderOwnerConfig updated = ProviderOwnerConfig.of(Map.of(origin.alias(), origin),
                Map.of(credential.id(), credential));
        ProviderConfigurationStore store = new ProviderConfigurationStore(configRoot, credentialRoot,
                () -> { throw new ProviderFailure("injected_failure", "Injected failure", false); });

        ProviderFailure failure = assertThrows(ProviderFailure.class, () -> store.save(updated,
                Map.of(credential.id(), "new-secret".getBytes(StandardCharsets.US_ASCII))));

        assertEquals("provider_configuration_save_failed", failure.code());
        assertFalse(OwnerOnlyFiles.exists(configRoot, ProviderOwnerConfig.FILE_NAME));
        OwnerCredentialStore credentials = new OwnerCredentialStore(credentialRoot);
        assertEquals("provider_credential_missing", assertThrows(ProviderFailure.class,
                () -> credentials.open(credential.id())).code());
    }
}
