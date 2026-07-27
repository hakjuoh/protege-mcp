package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extended-ACL half of the owner-only guarantee, which only macOS has: the JVM cannot read an ACL
 * there, so the store reads one with a process and remembers what it read against the moment the kernel
 * last changed that inode. These tests are about what the remembering may never hide.
 */
class OwnerOnlyFilesAclTest {

    @TempDir
    Path temporary;

    @BeforeEach
    void onlyWhereAclsAreReadByProcess() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("mac"), "extended ACLs are read by process only on macOS");
    }

    @Test
    void anAclGrantedOnAFileAfterItWasReadCleanIsStillRefused() throws Exception {
        Path root = temporary.resolve("granted-file");
        byte[] value = "owner-only".getBytes(StandardCharsets.US_ASCII);
        OwnerOnlyFiles.write(root, "config.json", value);
        assertArrayEquals(value, OwnerOnlyFiles.read(root, "config.json", 1_024));
        assertTrue(OwnerOnlyFiles.exists(root, "config.json"));

        grantEveryone(OwnerOnlyFiles.prepareDirectory(root).resolve("config.json"));

        assertEquals("provider_store_invalid", assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.read(root, "config.json", 1_024)).code());
        assertEquals("provider_store_invalid", assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.exists(root, "config.json")).code());
        assertEquals("provider_store_write_failed", assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.write(root, "config.json", value)).code());
    }

    @Test
    void anAclGrantedOnTheStoreDirectoryAfterItWasReadCleanIsStillRefused() throws Exception {
        Path root = temporary.resolve("granted-directory");
        byte[] value = "owner-only".getBytes(StandardCharsets.US_ASCII);
        OwnerOnlyFiles.write(root, "config.json", value);
        Path prepared = OwnerOnlyFiles.prepareDirectory(root);

        grantEveryone(prepared);

        assertEquals("provider_store_invalid", assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.read(root, "config.json", 1_024)).code());
        assertEquals("provider_store_invalid", assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.prepareDirectory(root)).code());
    }

    @Test
    void aStoreDirectoryCarryingAnAclThatWasNeverReadIsRefused() throws Exception {
        Path root = ownerOnlyDirectory("never-read");
        grantEveryone(root);

        // The first reading of this directory is one that covers its parent too, and it comes back unclean.
        // A reading that answers for more than one path is no verdict on any of them, so the directory is
        // read again on its own — and that is the reading that has to refuse it.
        assertEquals("provider_store_invalid", assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.prepareDirectory(root)).code());
    }

    @Test
    void aFileCarryingAnAclInAStoreDirectoryReadForTheFirstTimeIsRefused() throws Exception {
        Path root = ownerOnlyDirectory("fresh-store");
        Path file = Files.createFile(root.resolve("config.json"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        grantEveryone(file);

        // The directory is clean and read for the first time here, which is a reading that also covers the
        // paths around it. What it establishes is only ever about the paths it named: this file was not one
        // of them, so it is still read on its own and refused.
        assertEquals("provider_store_invalid", assertThrows(ProviderFailure.class,
                () -> OwnerOnlyFiles.read(root, "config.json", 1_024)).code());
    }

    @Test
    void aStoreCreatedUnderAnInheritingParentIsStrippedRatherThanRefused() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("inheriting"));
        run("/bin/chmod", "+a", "group:everyone allow read,file_inherit,directory_inherit",
                parent.toString());
        assertTrue(hasAcl(parent), "the parent must carry the entry the store has to strip");

        Path root = OwnerOnlyFiles.prepareDirectory(parent.resolve("providers"));

        assertFalse(hasAcl(root), "the inherited entry must have been stripped");
        byte[] value = "owner-only".getBytes(StandardCharsets.US_ASCII);
        OwnerOnlyFiles.write(root, "config.json", value);
        assertArrayEquals(value, OwnerOnlyFiles.read(root, "config.json", 1_024));
        assertFalse(hasAcl(root.resolve("config.json")));
    }

    private Path ownerOnlyDirectory(String name) throws IOException {
        return Files.createDirectory(temporary.resolve(name), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
    }

    private static void grantEveryone(Path path) throws Exception {
        run("/bin/chmod", "+a", "group:everyone allow read", path.toString());
        assertTrue(hasAcl(path), "the grant must be on the path the store is about to read");
    }

    private static boolean hasAcl(Path path) throws Exception {
        assertTrue(Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        Process listing = new ProcessBuilder(List.of("/bin/ls", "-lde", "--", path.toString()))
                .redirectErrorStream(true).start();
        String output = new String(listing.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(listing.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, listing.exitValue(), output);
        return output.stripTrailing().contains("\n");
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }
}
