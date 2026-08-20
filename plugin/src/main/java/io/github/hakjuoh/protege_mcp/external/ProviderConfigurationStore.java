package io.github.hakjuoh.protege_mcp.external;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Rollback-coordinates owner provider metadata with its separate credential records. */
public final class ProviderConfigurationStore {

    private final Path configRoot;
    private final Path credentialRoot;
    private final BeforeMetadataCommit beforeMetadataCommit;
    private final boolean prepareDefaultRoots;

    public ProviderConfigurationStore() throws ProviderFailure {
        this(ProviderLocalPaths.providersLocation(), ProviderLocalPaths.credentialsLocation(),
                () -> { }, true);
    }

    ProviderConfigurationStore(Path configRoot, Path credentialRoot) {
        this(configRoot, credentialRoot, () -> { });
    }

    ProviderConfigurationStore(Path configRoot, Path credentialRoot,
            BeforeMetadataCommit beforeMetadataCommit) {
        this(configRoot, credentialRoot, beforeMetadataCommit, false);
    }

    private ProviderConfigurationStore(Path configRoot, Path credentialRoot,
            BeforeMetadataCommit beforeMetadataCommit, boolean prepareDefaultRoots) {
        this.configRoot = java.util.Objects.requireNonNull(configRoot, "configRoot");
        this.credentialRoot = java.util.Objects.requireNonNull(credentialRoot, "credentialRoot");
        this.beforeMetadataCommit = java.util.Objects.requireNonNull(beforeMetadataCommit,
                "beforeMetadataCommit");
        this.prepareDefaultRoots = prepareDefaultRoots;
    }

    public ProviderOwnerConfig load() throws ProviderFailure {
        if (!Files.exists(configRoot, LinkOption.NOFOLLOW_LINKS)) {
            return ProviderOwnerConfig.empty();
        }
        return ProviderOwnerConfig.load(configRoot);
    }

    /**
     * Saves one complete configuration. Pending secret values are cloned immediately and wiped on
     * every exit; callers remain responsible for wiping their own arrays.
     */
    public void save(ProviderOwnerConfig updated, Map<String, byte[]> pendingSecrets)
            throws ProviderFailure {
        java.util.Objects.requireNonNull(updated, "updated");
        if (prepareDefaultRoots) {
            Path preparedConfig = ProviderLocalPaths.providers();
            Path preparedCredentials = ProviderLocalPaths.credentials();
            if (!preparedConfig.equals(configRoot) || !preparedCredentials.equals(credentialRoot)) {
                throw new ProviderFailure("provider_store_invalid",
                        "Owner provider store location changed", false);
            }
        }
        Map<String, byte[]> secrets = copySecrets(updated, pendingSecrets);
        try {
            OwnerOnlyFiles.withLock(configRoot, "configuration.lock", () -> {
                saveLocked(updated, secrets);
                return null;
            });
            ProviderConfigurationEvents.fireChanged();
        } finally {
            secrets.values().forEach(value -> Arrays.fill(value, (byte) 0));
            secrets.clear();
        }
    }

    private void saveLocked(ProviderOwnerConfig updated, Map<String, byte[]> pendingSecrets)
            throws ProviderFailure {
        boolean previousExists = OwnerOnlyFiles.exists(configRoot, ProviderOwnerConfig.FILE_NAME);
        ProviderOwnerConfig previous = ProviderOwnerConfig.load(configRoot);
        OwnerCredentialStore credentials = new OwnerCredentialStore(credentialRoot);
        Set<String> removed = new LinkedHashSet<>(previous.credentials().keySet());
        removed.removeAll(updated.credentials().keySet());
        Set<String> affected = new LinkedHashSet<>(removed);
        affected.addAll(pendingSecrets.keySet());
        Map<String, CredentialSnapshot> snapshots = snapshots(credentials, affected);
        try {
            for (Map.Entry<String, byte[]> entry : pendingSecrets.entrySet()) {
                credentials.rotate(entry.getKey(), entry.getValue());
            }
            for (String id : removed) credentials.delete(id);
            beforeMetadataCommit.run();
            ProviderOwnerConfig.save(configRoot, updated);
        } catch (ProviderFailure failure) {
            boolean restored = restore(credentials, snapshots);
            try {
                if (previousExists) {
                    ProviderOwnerConfig.save(configRoot, previous);
                } else {
                    OwnerOnlyFiles.delete(configRoot, ProviderOwnerConfig.FILE_NAME);
                }
            } catch (ProviderFailure rollbackFailure) {
                restored = false;
            }
            throw new ProviderFailure("provider_configuration_save_failed",
                    restored ? "Owner provider configuration was not changed"
                            : "Owner provider configuration outcome requires manual verification",
                    false);
        } finally {
            snapshots.values().forEach(CredentialSnapshot::clear);
            snapshots.clear();
        }
    }

    private static Map<String, byte[]> copySecrets(ProviderOwnerConfig updated,
            Map<String, byte[]> pendingSecrets) throws ProviderFailure {
        Map<String, byte[]> result = new LinkedHashMap<>();
        if (pendingSecrets == null) return result;
        for (Map.Entry<String, byte[]> entry : pendingSecrets.entrySet()) {
            String id = entry.getKey();
            if (!updated.credentials().containsKey(id) || entry.getValue() == null
                    || result.putIfAbsent(id, entry.getValue().clone()) != null) {
                result.values().forEach(value -> Arrays.fill(value, (byte) 0));
                throw new ProviderFailure("provider_configuration_invalid",
                        "Pending credentials do not match owner provider configuration", false);
            }
        }
        return result;
    }

    private static Map<String, CredentialSnapshot> snapshots(OwnerCredentialStore store,
            Set<String> ids) throws ProviderFailure {
        Map<String, CredentialSnapshot> result = new LinkedHashMap<>();
        try {
            for (String id : ids) {
                try (OwnerCredentialStore.CredentialLease lease = store.open(id)) {
                    result.put(id, new CredentialSnapshot(true, lease.copySecret()));
                } catch (ProviderFailure failure) {
                    if (!failure.code().equals("provider_credential_missing")) throw failure;
                    result.put(id, new CredentialSnapshot(false, null));
                }
            }
            return result;
        } catch (ProviderFailure failure) {
            result.values().forEach(CredentialSnapshot::clear);
            throw failure;
        }
    }

    private static boolean restore(OwnerCredentialStore store,
            Map<String, CredentialSnapshot> snapshots) {
        boolean restored = true;
        for (Map.Entry<String, CredentialSnapshot> entry : snapshots.entrySet()) {
            try {
                if (entry.getValue().present()) {
                    store.rotate(entry.getKey(), entry.getValue().secret());
                } else {
                    store.delete(entry.getKey());
                }
            } catch (ProviderFailure failure) {
                restored = false;
            }
        }
        return restored;
    }

    private record CredentialSnapshot(boolean present, byte[] secret) {
        void clear() {
            if (secret != null) Arrays.fill(secret, (byte) 0);
        }
    }

    @FunctionalInterface
    interface BeforeMetadataCommit {
        void run() throws ProviderFailure;
    }
}
