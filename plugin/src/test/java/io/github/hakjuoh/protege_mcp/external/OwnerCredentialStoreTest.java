package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerCredentialStoreTest {

    @TempDir
    Path temporary;

    @Test
    void rotationIsAtomicGenerationalAndLeaseIsRedacted() throws Exception {
        Path root = temporary.resolve("credentials");
        OwnerCredentialStore store = new OwnerCredentialStore(root);
        byte[] first = "canary-first-secret".getBytes(StandardCharsets.US_ASCII);
        assertEquals(1, store.rotate("ols-token", first));
        String firstScope;
        try (OwnerCredentialStore.CredentialLease lease = store.open("OLS-token")) {
            assertEquals(1, lease.generation());
            byte[] copy = lease.copySecret();
            assertArrayEquals(first, copy);
            Arrays.fill(copy, (byte) 0);
            assertFalse(lease.toString().contains("canary-first-secret"));
            assertTrue(lease.toString().contains("redacted=true"));
            firstScope = lease.scopeFingerprint();
        }

        byte[] second = "canary-second-secret".getBytes(StandardCharsets.US_ASCII);
        assertEquals(2, store.rotate("ols-token", second));
        OwnerCredentialStore.CredentialLease lease = store.open("ols-token");
        assertEquals(2, lease.generation());
        String oldScope = lease.scopeFingerprint();
        assertFalse(firstScope.equals(oldScope));
        assertArrayEquals(second, lease.copySecret());
        lease.close();
        assertEquals("provider_credential_closed",
                assertThrows(ProviderFailure.class, lease::copySecret).code());

        store.delete("ols-token");
        assertEquals("provider_credential_missing",
                assertThrows(ProviderFailure.class, () -> store.open("ols-token")).code());
        assertEquals(1, store.rotate("ols-token", first));
        try (OwnerCredentialStore.CredentialLease recreated = store.open("ols-token")) {
            assertEquals(1, recreated.generation());
            assertFalse(oldScope.equals(recreated.scopeFingerprint()));
        }
    }

    @Test
    void malformedSecretsTamperingTraversalAndSymlinksFailClosed() throws Exception {
        Path root = temporary.resolve("credentials");
        OwnerCredentialStore store = new OwnerCredentialStore(root);
        assertEquals("provider_credential_invalid", assertThrows(ProviderFailure.class,
                () -> store.rotate("token", "bad\r\nsecret".getBytes(StandardCharsets.US_ASCII))).code());
        assertEquals("provider_credential_invalid", assertThrows(ProviderFailure.class,
                () -> store.rotate("../token", new byte[] {'x'})).code());

        store.rotate("token", "secret-canary".getBytes(StandardCharsets.US_ASCII));
        Path file = root.resolve("token.cred");
        byte[] tampered = Files.readAllBytes(file);
        tampered[12] ^= 1;
        Files.write(file, tampered);
        ProviderFailure invalid = assertThrows(ProviderFailure.class, () -> store.open("token"));
        assertEquals("provider_credential_invalid", invalid.code());
        assertFalse(invalid.getMessage().contains("secret-canary"));

        Files.delete(file);
        Path outside = temporary.resolve("outside.cred");
        Files.write(outside, new byte[] {1});
        try {
            Files.createSymbolicLink(file, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        assertEquals("provider_store_invalid",
                assertThrows(ProviderFailure.class, () -> store.open("token")).code());
    }

    @Test
    void credentialIdentityAndCrossInstanceRotationAreSerialized() throws Exception {
        Path root = temporary.resolve("credentials");
        OwnerCredentialStore first = new OwnerCredentialStore(root);
        OwnerCredentialStore second = new OwnerCredentialStore(root);
        first.rotate("token", "first-secret".getBytes(StandardCharsets.US_ASCII));

        byte[] copied = Files.readAllBytes(root.resolve("token.cred"));
        OwnerOnlyFiles.write(root, "other.cred", copied);
        Arrays.fill(copied, (byte) 0);
        assertEquals("provider_credential_invalid",
                assertThrows(ProviderFailure.class, () -> first.open("other")).code());

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                start.await();
                return first.rotate("token", "second-secret".getBytes(StandardCharsets.US_ASCII));
            });
            var two = executor.submit(() -> {
                start.await();
                return second.rotate("token", "third-secret".getBytes(StandardCharsets.US_ASCII));
            });
            start.countDown();
            assertEquals(Set.of(2L, 3L), Set.of(one.get(), two.get()));
        } finally {
            executor.shutdownNow();
        }
        try (OwnerCredentialStore.CredentialLease lease = first.open("token")) {
            assertEquals(3, lease.generation());
        }
    }

    @Test
    void oversizedRecordsAndBusyOrSymlinkedLocksFailClosed() throws Exception {
        Path root = temporary.resolve("credentials");
        OwnerCredentialStore store = new OwnerCredentialStore(root);
        OwnerOnlyFiles.write(root, "large.cred", new byte[9 * 1_024]);
        assertEquals("provider_store_invalid",
                assertThrows(ProviderFailure.class, () -> store.open("large")).code());

        store.rotate("token", "secret".getBytes(StandardCharsets.US_ASCII));
        Path lockPath = root.resolve("credentials.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            assertEquals("provider_store_write_failed", assertThrows(ProviderFailure.class,
                    () -> store.rotate("token", "replacement".getBytes(StandardCharsets.US_ASCII)))
                    .code());
        }

        Files.delete(lockPath);
        Path outside = temporary.resolve("outside.lock");
        Files.write(outside, new byte[] {0});
        try {
            Files.createSymbolicLink(lockPath, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        assertEquals("provider_store_write_failed", assertThrows(ProviderFailure.class,
                () -> store.rotate("token", "replacement".getBytes(StandardCharsets.US_ASCII)))
                .code());
    }

    @Test
    void crashRemnantsAreSweptAndPostCommitFailuresAreOutcomeUnknown() throws Exception {
        Path root = OwnerOnlyFiles.prepareDirectory(temporary.resolve("crash-state"));
        Path temporaryRecord = root.resolve(".provider-orphan.tmp");
        Path tombstone = root.resolve(".token.cred.deleted-orphan");
        Files.write(temporaryRecord, "secret-one".getBytes(StandardCharsets.US_ASCII));
        Files.write(tombstone, "secret-two".getBytes(StandardCharsets.US_ASCII));
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            var ownerOnly = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(temporaryRecord, ownerOnly);
            Files.setPosixFilePermissions(tombstone, ownerOnly);
        }
        new OwnerCredentialStore(root);
        assertFalse(Files.exists(temporaryRecord));
        assertFalse(Files.exists(tombstone));

        byte[] state = "durable-state".getBytes(StandardCharsets.US_ASCII);
        ProviderFailure write = assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.write(root, "state.bin", state,
                        (operation, target) -> { throw new java.io.IOException("injected"); }));
        assertEquals("provider_store_outcome_unknown", write.code());
        assertArrayEquals(state, OwnerOnlyFiles.read(root, "state.bin", 100));

        ProviderFailure delete = assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.delete(root, "state.bin",
                        (operation, target) -> { throw new java.io.IOException("injected"); }));
        assertEquals("provider_store_outcome_unknown", delete.code());
        assertFalse(Files.exists(root.resolve("state.bin")));
        OwnerOnlyFiles.withLock(root, "credentials.lock", () -> null);
        try (var files = Files.list(root)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".deleted-")));
        }
    }

    @Test
    void jvmLockAcquisitionAlsoHonorsTheDeadline() throws Exception {
        Path root = OwnerOnlyFiles.prepareDirectory(temporary.resolve("jvm-lock"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> OwnerOnlyFiles.withLock(root, "shared.lock", () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ProviderFailure("provider_store_write_failed",
                            "test lock interrupted", false);
                }
                return null;
            }));
            if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                holder.get();
                throw new AssertionError("lock holder did not enter");
            }
            assertEquals("provider_store_write_failed", assertThrows(ProviderFailure.class,
                    () -> OwnerOnlyFiles.withLock(root, "shared.lock", () -> null)).code());
            release.countDown();
            holder.get();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
