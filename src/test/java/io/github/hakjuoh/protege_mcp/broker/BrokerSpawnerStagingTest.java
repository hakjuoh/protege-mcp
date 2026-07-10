package io.github.hakjuoh.protege_mcp.broker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classpath staging contract of {@link BrokerSpawner}: the broker runs from content-named copies
 * under {@code ~/.protege-mcp/jars}, never from the plugin jar in Protégé's plugins directory (an
 * open handle there would block plugin updates on Windows for as long as the broker lives).
 */
class BrokerSpawnerStagingTest {

    @TempDir
    Path tmp;

    private Path jarsDir() {
        return tmp.resolve("home").resolve("jars");
    }

    private Path sourceJar(String name, String content) throws Exception {
        Path jar = tmp.resolve(name);
        Files.writeString(jar, content, StandardCharsets.UTF_8);
        return jar;
    }

    @Test
    void stagesAJarAsAContentNamedCopy() throws Exception {
        Path source = sourceJar("protege-mcp-0.5.0.jar", "bundle-bytes");
        Path staged = BrokerSpawner.stageJar(jarsDir(), source);
        assertNotEquals(source, staged, "the broker must not launch from the original jar");
        assertEquals(jarsDir(), staged.getParent());
        assertTrue(staged.getFileName().toString().matches("protege-mcp-0\\.5\\.0-[0-9a-f]{12}\\.jar"),
                "the copy's name must embed a content hash: " + staged.getFileName());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(staged));
    }

    @Test
    void reusesAnExistingCopyWithoutRewritingIt() throws Exception {
        Path source = sourceJar("plugin.jar", "same-bytes");
        Path first = BrokerSpawner.stageJar(jarsDir(), source);
        // Same length, different bytes: reuse goes by name (content hash) + size and must never
        // rewrite an existing copy in place — a running old broker may hold it open on Windows.
        Files.writeString(first, "SAME-BYTES", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_000_000L));
        Path second = BrokerSpawner.stageJar(jarsDir(), source);
        assertEquals(first, second);
        assertEquals("SAME-BYTES", Files.readString(second, StandardCharsets.UTF_8),
                "an existing same-size copy is reused as-is, never rewritten in place");
        assertTrue(Files.getLastModifiedTime(second).toMillis() > 1_000_000L,
                "reuse must re-arm the sweep age gate by refreshing the copy's timestamp");
    }

    @Test
    void repairsACorruptCopyWhoseSizeDiffers() throws Exception {
        Path source = sourceJar("plugin.jar", "full-build-bytes");
        Path copy = BrokerSpawner.stageJar(jarsDir(), source);
        Files.writeString(copy, "torn", StandardCharsets.UTF_8);
        Path repaired = BrokerSpawner.stageJar(jarsDir(), source);
        assertEquals(copy, repaired);
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(repaired),
                "a truncated copy (crash mid non-atomic move) must be re-staged, not reused");
    }

    @Test
    void aPreExistingReusedCopySurvivesTheSweep() throws Exception {
        Path plugin = sourceJar("plugin.jar", "plugin-bytes");
        Path slf4j = sourceJar("slf4j-api.jar", "slf4j-bytes");
        Path oldCopy = BrokerSpawner.stageJar(jarsDir(), plugin);
        Files.setLastModifiedTime(oldCopy, FileTime.fromMillis(0));
        List<Path> staged = BrokerSpawner.stageClasspath(jarsDir(), List.of(plugin, slf4j));
        assertEquals(oldCopy, staged.get(0),
                "a restart long after the last spawn must reuse the aged copy");
        assertTrue(Files.exists(oldCopy), "a copy this very launch is about to exec is never swept");
    }

    @Test
    void rebuiltJarWithSameNameGetsAFreshCopy() throws Exception {
        Path oldBuild = sourceJar("plugin.jar", "old-build-bytes");
        Path oldCopy = BrokerSpawner.stageJar(jarsDir(), oldBuild);
        Files.writeString(oldBuild, "new-build-bytes", StandardCharsets.UTF_8);
        Path newCopy = BrokerSpawner.stageJar(jarsDir(), oldBuild);
        assertNotEquals(oldCopy, newCopy,
                "same version string but different bytes (local vs CI build) must never share a copy");
        assertTrue(Files.exists(oldCopy), "a copy a running old broker may use is not replaced");
    }

    @Test
    void directoryClasspathEntriesAreLeftAlone() throws Exception {
        Path classesDir = Files.createDirectories(tmp.resolve("target").resolve("classes"));
        assertEquals(classesDir, BrokerSpawner.stageJar(jarsDir(), classesDir),
                "dev/test classpaths are directories — there is nothing to stage");
        assertFalse(Files.exists(jarsDir()));
    }

    @Test
    void fallsBackToTheOriginalJarWhenStagingIsImpossible() throws Exception {
        Path source = sourceJar("plugin.jar", "bytes");
        Path fileWhereDirMustGo = sourceJar("not-a-dir", "occupied");
        Path staged = BrokerSpawner.stageJar(fileWhereDirMustGo.resolve("jars"), source);
        assertEquals(source, staged, "staging failure degrades to the original jar, never to no broker");
    }

    @Test
    void allEntriesFallBackInOrderWhenTheJarsDirIsImpossible() throws Exception {
        Path plugin = sourceJar("plugin.jar", "p");
        Path slf4j = sourceJar("slf4j-api.jar", "s");
        Path impossible = sourceJar("occupied", "not a dir").resolve("jars");
        assertEquals(List.of(plugin, slf4j),
                BrokerSpawner.stageClasspath(impossible, List.of(plugin, slf4j)),
                "full fallback keeps the original classpath, in order, and the sweep must not throw");
    }

    @Test
    void publishAcceptsASiblingsIdenticalCopyWhenTheMoveCannotHappen() throws Exception {
        Files.createDirectories(jarsDir());
        Path target = jarsDir().resolve("plugin-abc.jar");
        Files.writeString(target, "sibling-bytes", StandardCharsets.UTF_8);
        Path goneTmp = jarsDir().resolve("plugin-abc.jar.tmp-1");
        BrokerSpawner.publishStagedCopy(goneTmp, target, Files.size(target));
        assertEquals("sibling-bytes", Files.readString(target, StandardCharsets.UTF_8),
                "an expected-size target left by a sibling is accepted when our move fails");
    }

    @Test
    void publishFailsWhenTheMoveCannotHappenAndTheTargetIsWrong() throws Exception {
        Files.createDirectories(jarsDir());
        Path target = jarsDir().resolve("plugin-abc.jar");
        Files.writeString(target, "torn", StandardCharsets.UTF_8);
        Path goneTmp = jarsDir().resolve("plugin-abc.jar.tmp-1");
        assertThrows(IOException.class,
                () -> BrokerSpawner.publishStagedCopy(goneTmp, target, 999),
                "a failed publish over a wrong-size target must surface so stageJar falls back");
    }

    @Test
    void stageClasspathStagesEveryEntryAndSweepsOldLeftovers() throws Exception {
        Path plugin = sourceJar("plugin.jar", "plugin-bytes");
        Path slf4j = sourceJar("slf4j-api.jar", "slf4j-bytes");
        Files.createDirectories(jarsDir());
        Path staleCopy = jarsDir().resolve("plugin-000000000000.jar");
        Path staleTmp = jarsDir().resolve("plugin-000000000000.jar.tmp-999");
        Path freshForeign = jarsDir().resolve("plugin-111111111111.jar");
        Files.writeString(staleCopy, "old");
        Files.writeString(staleTmp, "torn");
        Files.writeString(freshForeign, "sibling-just-staged-me");
        Files.setLastModifiedTime(staleCopy, FileTime.fromMillis(0));
        Files.setLastModifiedTime(staleTmp, FileTime.fromMillis(0));

        List<Path> staged = BrokerSpawner.stageClasspath(jarsDir(), List.of(plugin, slf4j));

        assertEquals(2, staged.size());
        assertTrue(staged.stream().allMatch(p -> p.getParent().equals(jarsDir())));
        assertTrue(staged.get(0).getFileName().toString().startsWith("plugin-"),
                "the classpath order (plugin first) must survive staging");
        assertTrue(staged.get(1).getFileName().toString().startsWith("slf4j-api-"));
        assertFalse(Files.exists(staleCopy), "old unused copies are swept");
        assertFalse(Files.exists(staleTmp), "orphaned temp files from crashed spawns are swept");
        assertTrue(Files.exists(freshForeign),
                "young copies survive the sweep — a concurrent sibling spawn may be about to exec them");
        for (Path p : staged) {
            assertTrue(Files.exists(p));
        }
    }

    @Test
    void concurrentStagingOfIdenticalContentConverges() throws Exception {
        Path source = sourceJar("plugin.jar", "raced-bytes");
        Path[] results = new Path[2];
        Thread a = new Thread(() -> results[0] = BrokerSpawner.stageJar(jarsDir(), source));
        Thread b = new Thread(() -> results[1] = BrokerSpawner.stageJar(jarsDir(), source));
        a.start();
        b.start();
        a.join();
        b.join();
        assertEquals(results[0], results[1], "identical bytes hash to one copy for every racer");
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(results[0]));
        try (var entries = Files.newDirectoryStream(jarsDir())) {
            for (Path entry : entries) {
                assertFalse(entry.getFileName().toString().contains(".tmp-"),
                        "no temp file may survive a staging race: " + entry);
            }
        }
    }
}
