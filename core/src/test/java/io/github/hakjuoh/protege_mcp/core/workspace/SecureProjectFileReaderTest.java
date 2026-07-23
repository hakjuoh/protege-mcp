package io.github.hakjuoh.protege_mcp.core.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.core.release.ArtifactStore;

class SecureProjectFileReaderTest {

    @Test
    void capturesImmutableVerifiedBytes(@TempDir Path project) throws Exception {
        Path source = project.resolve("right.owl");
        byte[] expected = "ontology".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, expected);

        SecureProjectFileReader.Captured captured =
                SecureProjectFileReader.capture(project, source, 1024);
        byte[] first = captured.bytes();
        first[0] = 0;

        assertArrayEquals(expected, captured.bytes());
        assertEquals(expected.length, captured.size());
        assertEquals(ArtifactStore.sha256(expected), captured.sha256());
    }

    @Test
    void rejectsOversizedAndEscapingTargets(@TempDir Path project)
            throws Exception {
        Path source = project.resolve("large.owl");
        Files.write(source, new byte[17]);
        Path outside = Files.createTempFile("protege-mcp-outside-", ".owl");
        try {
            assertThrows(IOException.class,
                    () -> SecureProjectFileReader.capture(project, source, 16));
            assertThrows(IOException.class,
                    () -> SecureProjectFileReader.capture(project, outside, 1024));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsSymlinkTargetAndSymlinkedParent(@TempDir Path project)
            throws Exception {
        Path outsideDirectory =
                Files.createTempDirectory("protege-mcp-reader-outside-");
        try {
            Path outside = outsideDirectory.resolve("outside.owl");
            Files.writeString(outside, "outside");
            Path targetLink = project.resolve("target-link.owl");
            Path parentLink = project.resolve("parent-link");
            try {
                Files.createSymbolicLink(targetLink, outside);
                Files.createSymbolicLink(parentLink, outsideDirectory);
            } catch (UnsupportedOperationException | IOException unavailable) {
                assumeTrue(false, "symbolic links are unavailable: " + unavailable.getClass()
                        .getSimpleName());
            }

            assertThrows(IOException.class,
                    () -> SecureProjectFileReader.capture(
                            project, targetLink, 1024));
            assertThrows(IOException.class,
                    () -> SecureProjectFileReader.capture(
                            project, parentLink.resolve("outside.owl"), 1024));
        } finally {
            Files.deleteIfExists(outsideDirectory.resolve("outside.owl"));
            Files.deleteIfExists(outsideDirectory);
        }
    }
}
