package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

/** Content-keyed, model-thread-free resolver for {@code entity_search} policy settings. */
final class EntitySearchPolicyCache {

    private Key cachedKey;
    private EntitySearch.Settings cachedSettings = EntitySearch.Settings.defaults();
    private int loads;

    synchronized EntitySearch.Settings resolve(ProjectPolicyTools.PolicyContext live) {
        Path policyPath = discover(live.documentPath());
        if (policyPath == null) {
            clear();
            return EntitySearch.Settings.defaults();
        }
        byte[] bytes;
        try {
            long size = Files.size(policyPath);
            if (size > ProjectPolicyLoader.MAX_POLICY_BYTES) {
                clear();
                return EntitySearch.Settings.defaults();
            }
            try (InputStream input = Files.newInputStream(policyPath)) {
                bytes = input.readNBytes(Math.toIntExact(ProjectPolicyLoader.MAX_POLICY_BYTES + 1));
            }
            if (bytes.length > ProjectPolicyLoader.MAX_POLICY_BYTES) {
                clear();
                return EntitySearch.Settings.defaults();
            }
        } catch (IOException | RuntimeException unreadable) {
            clear();
            return EntitySearch.Settings.defaults();
        }

        Key key = new Key(policyPath.toAbsolutePath().normalize(), sha256(bytes),
                live.activeOntologyIri(), List.copyOf(live.installedReasoners()));
        if (key.equals(cachedKey)) return cachedSettings;

        ProjectPolicy policy = ProjectPolicyLoader.loadCaptured(policyPath, bytes,
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

    private static Path discover(Path ontologyDocument) {
        if (ontologyDocument == null) return null;
        Path start = Files.isDirectory(ontologyDocument) ? ontologyDocument
                : ontologyDocument.toAbsolutePath().normalize().getParent();
        for (Path current = start; current != null; current = current.getParent()) {
            Path candidate = current.resolve(ProjectPolicyLoader.DEFAULT_RELATIVE_PATH);
            if (Files.isRegularFile(candidate)) return candidate;
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
}
