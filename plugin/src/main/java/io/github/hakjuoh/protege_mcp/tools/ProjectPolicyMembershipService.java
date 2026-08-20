package io.github.hakjuoh.protege_mcp.tools;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Edits v3 workspace membership under the shared in-process policy lock, validates the candidate,
 * checks source bytes immediately before an atomic replacement, and preserves file permissions.
 * Writers in another process cannot participate in the JVM lock; the byte check detects ordinary
 * external changes but, like any check-then-rename filesystem protocol, is not a cross-process CAS.
 */
public final class ProjectPolicyMembershipService {

    private ProjectPolicyMembershipService() { }

    public static void setMembership(ProjectPolicy policy, Path file, String ontologyIri,
            boolean included) throws IOException {
        if (policy == null || !policy.loaded() || policy.path() == null || policy.projectRoot() == null) {
            throw new IllegalArgumentException("A loaded project policy is required.");
        }
        synchronized (ProjectPolicyTools.policyWriteLock(policy.path())) {
            setMembershipLocked(policy, file, ontologyIri, included);
        }
    }

    private static void setMembershipLocked(ProjectPolicy policy, Path file, String ontologyIri,
            boolean included) throws IOException {
        if (policy.version() < 3) {
            throw new IllegalArgumentException("Workspace membership requires project policy v3.");
        }
        if (!policy.valid()) {
            throw new IllegalArgumentException("Workspace membership cannot edit an invalid project policy.");
        }
        Path root = policy.projectRoot().toRealPath();
        Path target = file.toRealPath();
        if (!Files.isRegularFile(target) || !target.startsWith(root)) {
            throw new IllegalArgumentException("Only regular files inside the project can be managed.");
        }
        String relative = root.relativize(target).toString().replace('\\', '/');
        byte[] before = Files.readAllBytes(policy.path());
        ProjectPolicy current = ProjectPolicyLoader.load(policy.path(), target);
        if (!current.valid() || !policy.digest().equals(current.digest())) {
            throw new IOException("Project policy changed before membership could be updated.");
        }
        Map<String, Object> workspace = workspace(current);
        LinkedHashSet<String> files = new LinkedHashSet<>(strings(workspace.get("files")));
        if (included) files.add(relative); else files.remove(relative);
        List<String> sortedFiles = files.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        workspace.put("files", sortedFiles);
        updateBindings(workspace, relative, ontologyIri, included);

        String patched = ProjectPolicyPatcher.apply(new String(before, StandardCharsets.UTF_8),
                Map.of("workspace", workspace));
        byte[] after = patched.getBytes(StandardCharsets.UTF_8);
        if (after.length > ProjectPolicyLoader.MAX_POLICY_BYTES) {
            throw new IOException("Updated project policy is too large.");
        }
        replaceAtomically(policy.path(), target, before, after);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workspace(ProjectPolicy policy) {
        Object value = policy.effective().get("workspace");
        Map<String, Object> copy = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> copy.put(String.valueOf(key), nested));
        }
        return copy;
    }

    private static void updateBindings(Map<String, Object> workspace, String path,
            String ontologyIri, boolean included) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        Object raw = workspace.get("ontologies");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> binding = new LinkedHashMap<>();
                    map.forEach((key, value) -> binding.put(String.valueOf(key), value));
                    bindings.add(binding);
                }
            }
        }
        for (Map<String, Object> binding : bindings) {
            LinkedHashSet<String> documents = new LinkedHashSet<>(strings(binding.get("documents")));
            if (included && ontologyIri != null && ontologyIri.equals(binding.get("iri"))) {
                documents.add(path);
            } else if (!included) {
                documents.remove(path);
            }
            binding.put("documents", documents.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        }
        bindings.removeIf(binding -> strings(binding.get("documents")).isEmpty());
        if (included && ontologyIri != null && bindings.stream().noneMatch(
                binding -> ontologyIri.equals(binding.get("iri")))) {
            bindings.add(new LinkedHashMap<>(Map.of("iri", ontologyIri,
                    "documents", List.of(path))));
        }
        bindings.sort(Comparator.comparing(binding -> String.valueOf(binding.get("iri")),
                String.CASE_INSENSITIVE_ORDER));
        workspace.put("ontologies", bindings);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) if (item instanceof String string) result.add(string);
        return result;
    }

    private static void replaceAtomically(Path target, Path projectDocument, byte[] expected,
            byte[] replacement)
            throws IOException {
        if (!java.util.Arrays.equals(expected, Files.readAllBytes(target))) {
            throw new IOException("Project policy changed before membership could be updated.");
        }
        Path temp = Files.createTempFile(target.getParent(), ".project-membership-", ".yaml");
        try {
            Set<PosixFilePermission> permissions = null;
            try { permissions = Files.getPosixFilePermissions(target); }
            catch (UnsupportedOperationException ignored) { }
            Files.write(temp, replacement);
            if (permissions != null) Files.setPosixFilePermissions(temp, permissions);
            ProjectPolicy candidate = ProjectPolicyLoader.load(temp, projectDocument);
            if (!candidate.valid()) {
                throw new IOException("Updated project policy is invalid: " + candidate.issues());
            }
            if (!java.util.Arrays.equals(expected, Files.readAllBytes(target))) {
                throw new IOException("Project policy changed while membership was being updated.");
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("The project filesystem does not support atomic policy updates.",
                        unsupported);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
