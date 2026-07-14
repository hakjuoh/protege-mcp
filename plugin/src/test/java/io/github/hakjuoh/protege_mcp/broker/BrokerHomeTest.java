package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File-system contract of {@code ~/.protege-mcp}: secret minting, state round-trip, permissions. */
class BrokerHomeTest {

    @TempDir
    Path tmp;

    private BrokerHome home() {
        return new BrokerHome(tmp.resolve("home"));
    }

    @Test
    void ensureDirSecretMintsOnceAndIsStable() throws Exception {
        BrokerHome home = home();
        String first = home.ensureDirSecret();
        String second = home.ensureDirSecret();
        assertEquals(first, second, "the directory secret must be minted once and re-read after");
        assertFalse(first.isEmpty());
    }

    @Test
    void secretAndStateAreOwnerOnlyOnPosix() throws Exception {
        BrokerHome home = home();
        home.ensureDirSecret();
        home.writeState(new BrokerState(123, 5001, "1.0", 42));
        if (!Files.getFileStore(home.dir()).supportsFileAttributeView("posix")) {
            return; // permission assertions are POSIX-only
        }
        Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(home.dir());
        assertFalse(dirPerms.stream().anyMatch(p -> p.name().startsWith("GROUP")
                        || p.name().startsWith("OTHERS")),
                "the broker home must be private to the user: " + dirPerms);
        Set<PosixFilePermission> filePerms = Files.getPosixFilePermissions(home.secretFile());
        assertFalse(filePerms.stream().anyMatch(p -> p.name().startsWith("GROUP")
                        || p.name().startsWith("OTHERS")),
                "the secret is the same-user trust anchor and must be 0600: " + filePerms);
    }

    @Test
    void stateRoundTripsThroughJson() throws Exception {
        BrokerHome home = home();
        home.writeState(new BrokerState(4242, 8123, "0.5.0", 99));
        Optional<BrokerState> read = home.readState();
        assertTrue(read.isPresent());
        assertEquals(4242, read.get().pid);
        assertEquals(8123, read.get().port);
        assertEquals("0.5.0", read.get().version);
        assertEquals("http://127.0.0.1:8123", read.get().baseUrl());
    }

    @Test
    void readStateIsEmptyWhenAbsentOrCorrupt() throws Exception {
        BrokerHome home = home();
        assertTrue(home.readState().isEmpty(), "no state file yet");
        home.ensureDir();
        Files.writeString(home.stateFile(), "{not json");
        assertTrue(home.readState().isEmpty(), "corrupt state reads as no-broker, not an exception");
        Files.writeString(home.stateFile(), "{\"pid\":1}");
        assertTrue(home.readState().isEmpty(), "a state without a port is unusable → treated as absent");
    }

    @Test
    void deleteStateIfOwnedByRemovesOnlyTheOwnersFile() throws Exception {
        BrokerHome home = home();
        home.writeState(new BrokerState(111, 5001, "1.0", 1));
        home.deleteStateIfOwnedBy(222);
        assertTrue(home.readState().isPresent(), "a successor's state must not be deleted");
        home.deleteStateIfOwnedBy(111);
        assertTrue(home.readState().isEmpty(), "the owner cleans up its own state");
    }
}
