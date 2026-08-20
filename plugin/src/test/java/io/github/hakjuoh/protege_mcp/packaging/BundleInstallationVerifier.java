package io.github.hakjuoh.protege_mcp.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.felix.framework.FrameworkFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;

/** Package-phase smoke test for the exact OSGi bundle Maven is about to publish. */
public final class BundleInstallationVerifier {

    private BundleInstallationVerifier() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one bundle path");
        }
        Path bundle = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(bundle)) {
            throw new IllegalStateException("Bundle artifact does not exist: " + bundle);
        }

        verify(bundle);
    }

    static void verify(Path bundle) throws Exception {
        verify(bundle, Files.createTempDirectory("protege-mcp-felix-"));
    }

    static void verify(Path bundle, Path storage) throws Exception {
        Framework framework = null;
        Exception verificationFailure = null;
        try {
            Map<String, String> configuration = new HashMap<>();
            configuration.put(Constants.FRAMEWORK_STORAGE, storage.toString());
            configuration.put(Constants.FRAMEWORK_STORAGE_CLEAN,
                    Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
            framework = new FrameworkFactory().newFramework(configuration);
            framework.init();
            Bundle installed = framework.getBundleContext()
                    .installBundle(bundle.toUri().toString());
            if (!"io.github.hakjuoh.protege-mcp".equals(installed.getSymbolicName())) {
                throw new IllegalStateException("Unexpected bundle symbolic name: "
                        + installed.getSymbolicName());
            }
        } catch (Exception failure) {
            verificationFailure = failure;
            throw failure;
        } finally {
            Exception cleanupFailure = stopAndDelete(framework, storage);
            if (cleanupFailure != null) {
                if (verificationFailure != null) verificationFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private static Exception stopAndDelete(Framework framework, Path storage) {
        Exception failure = null;
        if (framework != null) {
            try {
                framework.stop();
                FrameworkEvent stopped = framework.waitForStop(5_000);
                if (stopped.getType() == FrameworkEvent.WAIT_TIMEDOUT) {
                    throw new IllegalStateException("Felix did not stop within 5 seconds");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
            } catch (Exception stopFailure) {
                failure = stopFailure;
            }
        }
        try {
            deleteTree(storage);
        } catch (IOException deleteFailure) {
            if (failure == null) failure = deleteFailure;
            else failure.addSuppressed(deleteFailure);
        }
        return failure;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
