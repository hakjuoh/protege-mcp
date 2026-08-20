package io.github.hakjuoh.protege_mcp.packaging;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

class BundleInstallationVerifierTest {

    @Test
    void rejectsTheDuplicateImportThatPreventsProtegeFromInstallingABundle(
            @TempDir Path directory) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");
        attributes.putValue(Constants.BUNDLE_SYMBOLICNAME, "io.github.hakjuoh.protege-mcp");
        attributes.putValue(Constants.IMPORT_PACKAGE,
                "org.protege.editor.owl.ui.renderer,org.protege.editor.owl.ui.renderer");
        Path invalid = directory.resolve("invalid-bundle.jar");
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(invalid), manifest)) {
            // A manifest-only bundle is sufficient to exercise Felix's installation parser.
        }

        Path frameworkStorage = directory.resolve("felix-storage");
        BundleException failure = assertThrows(BundleException.class,
                () -> BundleInstallationVerifier.verify(invalid, frameworkStorage));
        assertTrue(failure.getMessage().contains("Duplicate import"), failure::getMessage);
        assertTrue(Files.notExists(frameworkStorage),
                "the isolated framework must stop before its storage is deleted");
    }
}
