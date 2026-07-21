package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/** Content-keyed, model-thread-free resolver for {@code entity_search} policy settings. */
final class EntitySearchPolicyCache {

    private final CaptureInterlock beforeCapture;
    private Key cachedKey;
    private EntitySearch.Settings cachedSettings = EntitySearch.Settings.defaults();
    private int loads;

    EntitySearchPolicyCache() {
        this(() -> { });
    }

    EntitySearchPolicyCache(CaptureInterlock beforeCapture) {
        this.beforeCapture = java.util.Objects.requireNonNull(beforeCapture, "beforeCapture");
    }

    synchronized EntitySearch.Settings resolve(ProjectPolicyTools.PolicyContext live) {
        DiscoveredPolicy discovered = discoverSafely(live.documentPath());
        if (discovered == null) {
            clear();
            return EntitySearch.Settings.defaults();
        }
        ProjectPolicyLoader.PolicySourcePin sourcePin = discovered.sourcePin();
        Path policyPath = sourcePin.source();
        ProjectPolicyLoader.CapturedPolicy captured;
        byte[] bytes;
        try {
            beforeCapture.run();
            captured = ProjectPolicyLoader.captureStablePolicy(sourcePin);
            bytes = captured.bytes();
        } catch (IOException | RuntimeException unsafeOrUnreadable) {
            clear();
            return EntitySearch.Settings.defaults();
        }
        Key key = new Key(policyPath, sha256(bytes),
                live.activeOntologyIri(), List.copyOf(live.installedReasoners()));
        if (key.equals(cachedKey) && captured.isCurrent()) return cachedSettings;

        ProjectPolicy policy = ProjectPolicyLoader.loadCaptured(captured,
                live.activeOntologyIri(), live.installedReasoners(), false);
        cachedSettings = EntitySearch.Settings.from(policy);
        cachedKey = key;
        loads++;
        return cachedSettings;
    }

    synchronized void clear() {
        cachedKey = null;
        cachedSettings = EntitySearch.Settings.defaults();
    }

    synchronized int loads() {
        return loads;
    }

    private static DiscoveredPolicy discoverSafely(Path ontologyDocument) {
        if (ontologyDocument == null) return null;
        Path start = Files.isDirectory(ontologyDocument) ? ontologyDocument
                : ontologyDocument.toAbsolutePath().normalize().getParent();
        for (Path current = start; current != null; current = current.getParent()) {
            Path candidate = current.resolve(ProjectPolicyLoader.DEFAULT_RELATIVE_PATH);
            if (Files.isSymbolicLink(candidate)) return null;
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Path trustedRoot = current.toRealPath();
                    Path real = candidate.toRealPath();
                    return real.startsWith(trustedRoot)
                            ? new DiscoveredPolicy(ProjectPolicyLoader.pinCanonicalPolicy(
                                    real, trustedRoot)) : null;
                } catch (IOException unresolved) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) {
                out.append(Character.forDigit((b >>> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Key(Path source, String sha256, String activeOntologyIri,
            List<String> installedReasoners) { }

    private record DiscoveredPolicy(ProjectPolicyLoader.PolicySourcePin sourcePin) { }

    @FunctionalInterface
    interface CaptureInterlock {
        void run() throws IOException;
    }
}
