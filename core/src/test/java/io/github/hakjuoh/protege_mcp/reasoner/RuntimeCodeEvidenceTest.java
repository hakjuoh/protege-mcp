package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeCodeEvidenceTest {

    @TempDir
    Path temp;

    @Test
    void evidenceCacheRetriesUnknownAndCachesOnlySuccessfulEvidence() {
        AtomicInteger captures = new AtomicInteger();
        RuntimeCodeEvidence.Evidence expected = new RuntimeCodeEvidence.Evidence(
                "sha256:" + "a".repeat(64), List.of("scope/**"), 1);
        RuntimeCodeEvidence.EvidenceCache cache = new RuntimeCodeEvidence.EvidenceCache(type ->
                new RuntimeCodeEvidence.CachedEvidence(
                        captures.incrementAndGet() == 1
                                ? RuntimeCodeEvidence.Evidence.unknown() : expected,
                        List.of(), true));

        assertEquals(RuntimeCodeEvidence.Evidence.unknown(),
                cache.get(RuntimeCodeEvidenceTest.class).evidence());
        assertEquals(expected, cache.get(RuntimeCodeEvidenceTest.class).evidence());
        assertEquals(expected, cache.get(RuntimeCodeEvidenceTest.class).evidence());
        assertEquals(2, captures.get());
    }

    @Test
    void codeSourcePinRejectsSameSizeTamperingWithRestoredModificationTime()
            throws Exception {
        Path container = temp.resolve("reviewed.jar");
        byte[] reviewed = bytes(1, 2, 3, 4);
        byte[] tampered = bytes(4, 3, 2, 1);
        Files.write(container, reviewed);
        FileTime originalTime = Files.getLastModifiedTime(container);
        RuntimeCodeEvidence.CodeSourcePin original = RuntimeCodeEvidence.pin(
                RuntimeCodeEvidenceTest.class, container.toUri().toURL());

        Files.write(container, tampered);
        Files.setLastModifiedTime(container, originalTime);
        RuntimeCodeEvidence.CodeSourcePin changed = RuntimeCodeEvidence.pin(
                RuntimeCodeEvidenceTest.class, container.toUri().toURL());

        assertEquals(original.bytes(), changed.bytes());
        assertEquals(original.modifiedMillis(), changed.modifiedMillis());
        assertNotEquals(original.contentDigest(), changed.contentDigest());
        assertNotEquals(original, changed);
    }

    @Test
    void mixedDirectNestedAndMultiReleaseClassesAreAllAttestedDeterministically()
            throws Exception {
        byte[] nested = jar(Map.of("scope/Nested.class", bytes(3, 4, 5)));
        Map<String, byte[]> forward = new LinkedHashMap<>();
        forward.put("scope/Direct.class", bytes(1));
        forward.put("META-INF/versions/17/scope/Direct.class", bytes(2));
        forward.put("lib/nested.jar", nested);
        Map<String, byte[]> reverse = new LinkedHashMap<>();
        reverse.put("lib/nested.jar", nested);
        reverse.put("META-INF/versions/17/scope/Direct.class", bytes(2));
        reverse.put("scope/Direct.class", bytes(1));
        Path first = writeJar("first.jar", forward);
        Path second = writeJar("second.jar", reverse);

        RuntimeCodeEvidence.Evidence left = RuntimeCodeEvidence.captureLocations(
                List.of(first.toUri().toURL()), List.of("scope/"));
        RuntimeCodeEvidence.Evidence right = RuntimeCodeEvidence.captureLocations(
                List.of(second.toUri().toURL()), List.of("scope/"));
        Path changed = writeJar("changed.jar", Map.of(
                "scope/Direct.class", bytes(1),
                "META-INF/versions/17/scope/Direct.class", bytes(9),
                "lib/nested.jar", nested));
        RuntimeCodeEvidence.Evidence changedEvidence = RuntimeCodeEvidence.captureLocations(
                List.of(changed.toUri().toURL()), List.of("scope/"));

        assertEquals(3, left.classCount());
        assertEquals(left, right);
        assertNotEquals(left.digest(), changedEvidence.digest());
        assertTrue(left.digest().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void conflictingDuplicateClassAcrossNestedJarFailsClosed() throws Exception {
        byte[] nested = jar(Map.of("scope/Direct.class", bytes(2)));
        Path outer = writeJar("conflict.jar", Map.of(
                "scope/Direct.class", bytes(1), "lib/nested.jar", nested));

        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                List.of(outer.toUri().toURL()), List.of("scope/"));

        assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
    }

    @Test
    void oversizedClassFailsClosedWithoutPublishingPartialEvidence() throws Exception {
        Path outer = writeJar("oversized.jar", Map.of(
                "scope/Oversized.class", new byte[4 * 1024 * 1024 + 1]));

        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                List.of(outer.toUri().toURL()), List.of("scope/"));

        assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
    }

    @Test
    void explodedDirectoryIncludesMultiReleaseOverrides() throws Exception {
        Path direct = temp.resolve("classes/scope/Direct.class");
        Path override = temp.resolve("classes/META-INF/versions/17/scope/Direct.class");
        Files.createDirectories(direct.getParent());
        Files.createDirectories(override.getParent());
        Files.write(direct, bytes(1));
        Files.write(override, bytes(2));

        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                List.of(temp.resolve("classes").toUri().toURL()), List.of("scope/"));

        assertEquals(2, evidence.classCount());
        assertNotEquals("unknown", evidence.digest());
    }

    @Test
    void deadlineIsInclusiveAndTheFirstNanosecondAfterItDiscardsAllEvidence()
            throws Exception {
        Path outer = writeJar("timeout.jar", Map.of("scope/Direct.class", bytes(1)));
        AtomicLong exactCalls = new AtomicLong();
        long deadline = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1);
        RuntimeCodeEvidence.Evidence exact = RuntimeCodeEvidence.captureLocations(
                List.of(outer.toUri().toURL()), List.of("scope/"),
                () -> exactCalls.getAndIncrement() == 0 ? 0L : deadline, 1L);
        assertNotEquals(RuntimeCodeEvidence.Evidence.unknown(), exact);

        AtomicLong lateCalls = new AtomicLong();
        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                List.of(outer.toUri().toURL()), List.of("scope/"),
                () -> lateCalls.getAndIncrement() == 0 ? 0L : deadline + 1L, 1L);

        assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
    }

    @Test
    void everyGlobalByteAndCountLimitAcceptsTheBoundaryAndRejectsBoundaryPlusOne()
            throws Exception {
        Path twoClasses = writeJar("two-classes.jar", Map.of(
                "scope/One.class", bytes(1), "scope/Two.class", bytes(2)));
        long containerBytes = Files.size(twoClasses);

        assertCaptured(twoClasses, limits(2, 1_000_000, containerBytes,
                1, 2, 4));
        assertUnknown(twoClasses, limits(1, 1_000_000, containerBytes,
                1, 2, 4));
        assertUnknown(twoClasses, limits(2, 1_000_000, containerBytes - 1,
                1, 2, 4));
        assertUnknown(twoClasses, limits(2, 1_000_000, containerBytes,
                0, 2, 4));
        assertUnknown(twoClasses, limits(2, 1_000_000, containerBytes,
                1, 1, 4));
        assertUnknown(twoClasses, limits(2, 1_000_000, containerBytes,
                1, 2, 3));
    }

    @Test
    void nestedJarHeadersManifestAndEntriesShareTheNestedByteBudget() throws Exception {
        byte[] nested = jarWithManifest(Map.of("scope/Nested.class", bytes(7)));
        Path outer = writeJar("nested-budget.jar", Map.of("lib/nested.jar", nested));
        long containerBytes = Files.size(outer);

        assertCaptured(outer, limits(1, nested.length, containerBytes,
                1, 1, 3));
        assertUnknown(outer, limits(1, nested.length - 1L, containerBytes,
                1, 1, 3));
    }

    @Test
    void separateAxiomModuleLocationsAreAttestedAndAnImplementationOverrideFailsClosed()
            throws Exception {
        String apiClass = "org/apache/axiom/om/OMNode.class";
        String c14nClass = "org/apache/axiom/c14n/Canonicalizer.class";
        Path api = writeJar("axiom-api.jar", Map.of(apiClass, bytes(1)));
        Path c14n = writeJar("axiom-c14n.jar", Map.of(c14nClass, bytes(2)));
        Path override = writeJar("axiom-c14n-override.jar", Map.of(c14nClass, bytes(3)));
        RuntimeCodeEvidence.Limits limits = limits(2, 1_000_000,
                Math.max(Files.size(api), Files.size(c14n)), 1, 2, 8);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {api.toUri().toURL(), c14n.toUri().toURL()}, null)) {
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(api.toUri().toURL(), c14n.toUri().toURL()),
                    List.of("org/apache/axiom/"), System::nanoTime, limits,
                    Map.of("org/apache/axiom/", loader));
            assertNotEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
            assertEquals(2, evidence.classCount());
        }
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {api.toUri().toURL(), override.toUri().toURL()}, null)) {
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(api.toUri().toURL(), c14n.toUri().toURL()),
                    List.of("org/apache/axiom/"), System::nanoTime, limits,
                    Map.of("org/apache/axiom/", loader));
            assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        }
    }

    @Test
    void effectiveResourceVerificationRejectsNonlocalUrlsWithoutOpeningThem()
            throws Exception {
        Path reviewed = writeJar("local-reviewed.jar",
                Map.of("scope/Direct.class", bytes(1)));
        AtomicBoolean opened = new AtomicBoolean();
        URL remote = new URL(null, "http://attacker.invalid/scope/Direct.class",
                new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) throws IOException {
                        opened.set(true);
                        throw new IOException("remote fixture must not be opened");
                    }
                });
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return remote;
            }
        };

        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                List.of(reviewed.toUri().toURL()), List.of("scope/"), System::nanoTime,
                limits(1, 1_000_000, Files.size(reviewed), 1, 1, 2),
                Map.of("scope/", loader));

        assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        assertFalse(opened.get());
    }

    @Test
    void networkFileAuthoritiesAreRejectedBeforeAnyFilesystemProbe() throws Exception {
        Path reviewed = writeJar("network-authority-reviewed.jar",
                Map.of("scope/Direct.class", bytes(1)));
        RuntimeCodeEvidence.Limits limits = limits(1, 1_000_000,
                Files.size(reviewed), 1, 1, 2);
        List<URL> networkResources = List.of(
                new URL("file://attacker.invalid/share/scope/Direct.class"),
                new URL("jar:file://attacker.invalid/share/classes.jar!/scope/Direct.class"));

        for (URL networkResource : networkResources) {
            ClassLoader loader = new ClassLoader(null) {
                @Override
                public URL getResource(String name) {
                    return networkResource;
                }
            };
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(reviewed.toUri().toURL()), List.of("scope/"),
                    System::nanoTime, limits, Map.of("scope/", loader));
            assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        }
    }

    @Test
    void networkFileGuardRejectsEveryUncFormBeforeAnyFilesystemAccess() throws Exception {
        List<URL> urls = List.of(
                new URL("file://attacker.invalid/share/scope/Direct.class"),
                new URL("file:////attacker/share/scope/Direct.class"));

        for (URL resource : urls) {
            IOException failure = assertThrows(IOException.class,
                    () -> RuntimeCodeEvidence.localFilePath(resource));
            assertEquals("network file resources are unsupported", failure.getMessage());
        }
        List<java.net.URI> uris = List.of(
                java.net.URI.create("file://attacker.invalid/share/classes.jar"),
                java.net.URI.create("file:////attacker/share/classes.jar"));
        for (java.net.URI resource : uris) {
            IOException failure = assertThrows(IOException.class,
                    () -> RuntimeCodeEvidence.localFilePath(resource));
            assertEquals("network file resources are unsupported", failure.getMessage());
        }
        IOException backslash = assertThrows(IOException.class,
                () -> RuntimeCodeEvidence.rejectUncPath(
                        Path.of("\\\\attacker\\share\\scope\\Direct.class")));
        assertEquals("network file resources are unsupported", backslash.getMessage());
    }

    @Test
    void untrustedBundleHandlerIsRejectedBeforeItsConnectionIsOpened()
            throws Exception {
        Path reviewed = writeJar("untrusted-bundle-reviewed.jar",
                Map.of("scope/Direct.class", bytes(1)));
        AtomicBoolean opened = new AtomicBoolean();
        URL fakeBundle = new URL(null, "bundle://fixture/scope/Direct.class",
                new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) throws IOException {
                        opened.set(true);
                        throw new IOException("untrusted bundle fixture must not be opened");
                    }
                });
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return fakeBundle;
            }
        };

        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                List.of(reviewed.toUri().toURL()), List.of("scope/"), System::nanoTime,
                limits(1, 1_000_000, Files.size(reviewed), 1, 1, 2),
                Map.of("scope/", loader));

        assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        assertFalse(opened.get());
    }

    @Test
    void customJarHandlerIsIgnoredWhileItsLocalJarFieldsAreVerifiedDirectly()
            throws Exception {
        Path reviewed = writeJar("custom-jar-reviewed.jar",
                Map.of("scope/Direct.class", bytes(1)));
        AtomicBoolean opened = new AtomicBoolean();
        URL fakeJar = new URL(null,
                "jar:" + reviewed.toUri().toURL() + "!/scope/Direct.class",
                new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL url) throws IOException {
                        opened.set(true);
                        throw new IOException("custom JAR handler must not be opened");
                    }
                });
        ClassLoader loader = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return fakeJar;
            }
        };

        RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                List.of(reviewed.toUri().toURL()), List.of("scope/"), System::nanoTime,
                limits(1, 1_000_000, Files.size(reviewed), 1, 1, 2),
                Map.of("scope/", loader));

        assertNotEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        assertFalse(opened.get());
    }

    @Test
    void inactiveFutureMultiReleaseBytesCannotImpersonateTheEffectiveBaseClass()
            throws Exception {
        int future = Runtime.version().feature() + 1;
        String direct = "scope/Direct.class";
        Path reviewed = writeMultiReleaseJar("future-reviewed.jar", Map.of(
                direct, bytes(1),
                "META-INF/versions/" + future + "/" + direct, bytes(2)));
        Path override = writeJar("future-copy.jar", Map.of(direct, bytes(2)));
        RuntimeCodeEvidence.Limits limits = limits(2, 1_000_000,
                Files.size(reviewed), 1, 2, 6);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {override.toUri().toURL()}, null)) {
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(reviewed.toUri().toURL()), List.of("scope/"),
                    System::nanoTime, limits, Map.of("scope/", loader));
            assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        }
    }

    @Test
    void activeMultiReleaseClassIsSelectedExactlyForTheRunningJvm() throws Exception {
        int active = Runtime.version().feature();
        String direct = "scope/Direct.class";
        Path reviewed = writeMultiReleaseJar("active-reviewed.jar", Map.of(
                direct, bytes(1),
                "META-INF/versions/" + active + "/" + direct, bytes(2)));
        RuntimeCodeEvidence.Limits limits = limits(2, 1_000_000,
                Files.size(reviewed), 1, 2, 6);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {reviewed.toUri().toURL()}, null)) {
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(reviewed.toUri().toURL()), List.of("scope/"),
                    System::nanoTime, limits, Map.of("scope/", loader));
            assertNotEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        }
    }

    @Test
    void multiReleaseActivationStateParticipatesInTheEvidenceDigest() throws Exception {
        int current = Runtime.version().feature();
        String direct = "scope/Direct.class";
        Map<String, byte[]> entries = Map.of(
                direct, bytes(1),
                "META-INF/versions/" + current + "/" + direct, bytes(2));
        Path marked = writeMultiReleaseJar("marked-identity.jar", entries);
        Path unmarked = writeJar("unmarked-identity.jar", entries);

        RuntimeCodeEvidence.Evidence markedEvidence = RuntimeCodeEvidence.captureLocations(
                List.of(marked.toUri().toURL()), List.of("scope/"));
        RuntimeCodeEvidence.Evidence unmarkedEvidence = RuntimeCodeEvidence.captureLocations(
                List.of(unmarked.toUri().toURL()), List.of("scope/"));

        assertEquals(markedEvidence.classCount(), unmarkedEvidence.classCount());
        assertNotEquals(markedEvidence.digest(), unmarkedEvidence.digest());
    }

    @Test
    void unmarkedVersionDirectoryDoesNotOverrideTheEffectiveBaseClass() throws Exception {
        int current = Runtime.version().feature();
        String direct = "scope/Direct.class";
        Path reviewed = writeJar("unmarked-versions.jar", Map.of(
                direct, bytes(1),
                "META-INF/versions/" + current + "/" + direct, bytes(2)));
        RuntimeCodeEvidence.Limits limits = limits(2, 1_000_000,
                Files.size(reviewed), 1, 2, 4);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {reviewed.toUri().toURL()}, null)) {
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(reviewed.toUri().toURL()), List.of("scope/"),
                    System::nanoTime, limits, Map.of("scope/", loader));
            assertNotEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        }
    }

    @Test
    void oversizedEffectiveJarIsRejectedBeforeItsClassStreamIsOpened() throws Exception {
        String direct = "scope/Direct.class";
        Path reviewed = writeJar("bounded-reviewed.jar", Map.of(direct, bytes(1)));
        byte[] padding = new byte[16_384];
        new Random(7L).nextBytes(padding);
        Path oversized = writeJar("oversized-effective.jar", Map.of(
                direct, bytes(1), "padding.bin", padding));
        assertTrue(Files.size(oversized) > Files.size(reviewed));
        RuntimeCodeEvidence.Limits limits = limits(1, 1_000_000,
                Files.size(reviewed), 1, 1, 2);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {oversized.toUri().toURL()}, null)) {
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(reviewed.toUri().toURL()), List.of("scope/"),
                    System::nanoTime, limits, Map.of("scope/", loader));
            assertEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
        } finally {
            java.util.Arrays.fill(padding, (byte) 0);
        }
    }

    private void assertCaptured(Path jar, RuntimeCodeEvidence.Limits limits) throws Exception {
        assertNotEquals(RuntimeCodeEvidence.Evidence.unknown(),
                RuntimeCodeEvidence.captureLocations(List.of(jar.toUri().toURL()),
                        List.of("scope/"), System::nanoTime, limits));
    }

    private void assertUnknown(Path jar, RuntimeCodeEvidence.Limits limits) throws Exception {
        assertEquals(RuntimeCodeEvidence.Evidence.unknown(),
                RuntimeCodeEvidence.captureLocations(List.of(jar.toUri().toURL()),
                        List.of("scope/"), System::nanoTime, limits));
    }

    private static RuntimeCodeEvidence.Limits limits(long totalClassBytes,
            long nestedBytes, long containerBytes, int singleClassBytes,
            int classes, int entries) {
        return new RuntimeCodeEvidence.Limits(totalClassBytes, nestedBytes, containerBytes,
                singleClassBytes, classes, entries, 5_000L);
    }

    private Path writeJar(String name, Map<String, byte[]> entries) throws IOException {
        Path path = temp.resolve(name);
        Files.write(path, jar(entries));
        return path;
    }

    private Path writeMultiReleaseJar(String name, Map<String, byte[]> entries)
            throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        Path path = temp.resolve(name);
        Files.write(path, bytes.toByteArray());
        return path;
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] jarWithManifest(Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Fixture", "x".repeat(256));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int index = 0; index < values.length; index++) out[index] = (byte) values[index];
        return out;
    }
}
