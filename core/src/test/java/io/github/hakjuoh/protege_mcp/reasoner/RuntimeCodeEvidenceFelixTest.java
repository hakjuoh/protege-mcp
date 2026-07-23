package io.github.hakjuoh.protege_mcp.reasoner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.felix.framework.FrameworkFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.wiring.BundleWiring;

class RuntimeCodeEvidenceFelixTest {

    @TempDir
    Path temp;

    @Test
    void protegeFelixBundleResourceResolvesThroughItsBoundedLocalArtifact()
            throws Exception {
        Path bundlePath = writeFixtureBundle();
        Framework framework = new FrameworkFactory().newFramework(Map.of(
                Constants.FRAMEWORK_STORAGE, temp.resolve("felix-cache").toString(),
                Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT,
                "felix.log.level", "0"));
        try {
            framework.start();
            Bundle bundle = framework.getBundleContext()
                    .installBundle(bundlePath.toUri().toString());
            bundle.start();
            BundleWiring wiring = bundle.adapt(BundleWiring.class);
            assertNotNull(wiring);
            ClassLoader loader = wiring.getClassLoader();
            URL resource = loader.getResource("scope/Direct.class");
            assertNotNull(resource);
            assertEquals("bundle", resource.getProtocol());

            RuntimeCodeEvidence.Limits limits = new RuntimeCodeEvidence.Limits(
                    24L * 1024 * 1024, 64L * 1024 * 1024,
                    128L * 1024 * 1024, 4 * 1024 * 1024,
                    6_000, 150_000, 5_000L);
            RuntimeCodeEvidence.Evidence evidence = RuntimeCodeEvidence.captureLocations(
                    List.of(bundlePath.toUri().toURL()), List.of("scope/"),
                    System::nanoTime, limits, Map.of("scope/", loader));

            assertNotEquals(RuntimeCodeEvidence.Evidence.unknown(), evidence);
            assertEquals(1, evidence.classCount());
        } finally {
            framework.stop();
            framework.waitForStop(5_000L);
        }
    }

    private Path writeFixtureBundle() throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "runtime.evidence.fixture");
        attributes.putValue(Constants.BUNDLE_VERSION, "1.0.0");
        attributes.putValue(Constants.BUNDLE_CLASSPATH, ".,nested.jar");
        ByteArrayOutputStream nestedBytes = new ByteArrayOutputStream();
        try (JarOutputStream nested = new JarOutputStream(nestedBytes)) {
            nested.putNextEntry(new JarEntry("scope/Direct.class"));
            nested.write(new byte[] {1, 2, 3});
            nested.closeEntry();
        }
        Path bundle = temp.resolve("runtime-evidence-fixture.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(bundle), manifest)) {
            jar.putNextEntry(new JarEntry("nested.jar"));
            jar.write(nestedBytes.toByteArray());
            jar.closeEntry();
        }
        return bundle;
    }
}
