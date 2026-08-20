package io.github.hakjuoh.protege_mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig;
import io.github.hakjuoh.protege_mcp.external.ProviderPolicyBindings;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.CapturedPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.PolicySourcePin;

/** Creates or atomically updates a project policy from explicit owner-UI registry selection. */
public final class ProjectPolicyRegistrySync {

    private ProjectPolicyRegistrySync() {
    }

    /** Returns whether applying the saved Preferences would change the portable policy. */
    public static boolean synchronizationRequired(ProjectPolicy policy,
            ProviderOwnerConfig owner) {
        return synchronizationStatus(policy, owner).required();
    }

    public static SynchronizationStatus synchronizationStatus(ProjectPolicy policy,
            ProviderOwnerConfig owner) {
        ProviderPolicyBindings.Synthesis synthesis =
                ProviderPolicyBindings.synthesize(policy, owner);
        return new SynchronizationStatus(synchronizationRequired(policy, synthesis),
                synthesis.omitted());
    }

    public static Preview preview(Path documentPath, String ontologyIri,
            List<String> installedReasoners, ProjectPolicy snapshot, ProviderOwnerConfig owner)
            throws IOException {
        List<WorkspaceDocument> activeDocument = documentPath != null
                && Files.isRegularFile(documentPath)
                ? List.of(new WorkspaceDocument(ontologyIri, documentPath)) : List.of();
        return preview(documentPath, ontologyIri, installedReasoners, snapshot, owner,
                activeDocument);
    }

    public static Preview preview(Path documentPath, String ontologyIri,
            List<String> installedReasoners, ProjectPolicy snapshot, ProviderOwnerConfig owner,
            List<WorkspaceDocument> loadedDocuments) throws IOException {
        List<WorkspaceDocument> documents = List.copyOf(loadedDocuments);
        Path target = target(documentPath, snapshot);
        if (snapshot != null && snapshot.loaded()) {
            if (!Files.exists(target)) throw changed();
            requireGoverningPolicy(target, documentPath, ontologyIri, installedReasoners);
            CapturedPolicy captured = capture(target);
            ProjectPolicy current = requireGoverningPolicy(target, documentPath, ontologyIri,
                    installedReasoners);
            if (!captured.isCurrent()) throw changed();
            ProviderPolicyBindings.Synthesis synthesis =
                    ProviderPolicyBindings.synthesize(current, owner);
            return new Preview(true, providerCount(current), synthesis.providers(),
                    synthesis.omitted(), target, hash(captured.bytes()),
                    synchronizationRequired(current, synthesis), current.version(), documents);
        }
        if (Files.exists(target) || governingPolicy(documentPath, ontologyIri,
                installedReasoners).loaded()) throw changed();
        ProviderPolicyBindings.Synthesis synthesis =
                ProviderPolicyBindings.synthesize(ProjectPolicy.notFound(), owner);
        return new Preview(false, 0, synthesis.providers(), synthesis.omitted(), target, null,
                true, 0, documents);
    }

    public static Result apply(Path documentPath, String ontologyIri,
            List<String> installedReasoners, Preview preview, ProviderOwnerConfig owner)
            throws IOException {
        if (documentPath == null || !Files.isRegularFile(documentPath)) {
            throw new IOException("Save the active ontology to a regular local file first.");
        }
        if (preview == null || preview.target() == null) {
            throw new IOException("Review the registry update before applying it.");
        }
        Path target = preview.target();
        ProjectPolicyTools.PolicyContext live = new ProjectPolicyTools.PolicyContext(
                documentPath, ontologyIri, List.copyOf(installedReasoners), null);
        synchronized (ProjectPolicyTools.policyWriteLock(target)) {
            if (preview.policyExists()) {
                if (!Files.exists(target)) throw changed();
                return update(target, live, owner, preview.sourceHash(),
                        preview.workspaceDocuments());
            }
            if (Files.exists(target) || governingPolicy(live).loaded()) throw changed();
            return create(target, live, owner, preview.workspaceDocuments());
        }
    }

    private static Result update(Path target, ProjectPolicyTools.PolicyContext live,
            ProviderOwnerConfig owner, String expectedHash,
            List<WorkspaceDocument> previewDocuments) throws IOException {
        CapturedPolicy captured = capture(target);
        byte[] source = captured.bytes();
        if (expectedHash == null || !MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                hash(source).getBytes(StandardCharsets.US_ASCII))) {
            throw changed();
        }
        requireGoverningPolicy(target, live);
        ProjectPolicy current = ProjectPolicyLoader.load(target, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
        ProviderPolicyBindings.Synthesis synthesis =
                ProviderPolicyBindings.synthesize(current, owner);
        Map<String, Object> patch = patch(current.version(), synthesis.providers(),
                initialWorkspace(current.projectRoot(), previewDocuments));
        String yaml = ProjectPolicyPatcher.apply(
                new String(source, StandardCharsets.UTF_8), patch);
        byte[] candidateBytes = yaml.getBytes(StandardCharsets.UTF_8);
        ProjectPolicy candidate = ProjectPolicyTools.validateCandidate(target, candidateBytes, live);
        requireValid(candidate);
        requireScopedCredentialsRemainBound(candidate, owner);
        if (!captured.isCurrent()) throw changed();
        byte[] currentBytes = ProjectPolicyLoader.captureStablePolicy(
                ProjectPolicyLoader.pinCanonicalPolicy(target.toAbsolutePath().normalize(),
                        ProjectPolicyLoader.canonicalProjectAnchor(target))).bytes();
        if (!Arrays.equals(source, currentBytes)) throw changed();
        requireGoverningPolicy(target, live);
        ProjectPolicyTools.atomicWrite(target, candidateBytes, true);
        ProjectPolicy applied = ProjectPolicyLoader.load(target, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
        requireValid(applied);
        return new Result(false, target, applied, synthesis.omitted());
    }

    private static Result create(Path target, ProjectPolicyTools.PolicyContext live,
            ProviderOwnerConfig owner, List<WorkspaceDocument> loadedDocuments) throws IOException {
        ProjectPolicyScaffold.Scaffold scaffold;
        try {
            scaffold = ProjectPolicyScaffold.prepare(target, live.documentPath(),
                    live.activeOntologyIri(), live.installedReasoners(), null,
                    ProjectPolicyTemplate.GENERAL, null, 3);
        } catch (ToolArgException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
        ProviderPolicyBindings.Synthesis synthesis =
                ProviderPolicyBindings.synthesize(ProjectPolicy.notFound(), owner);
        String yaml = ProjectPolicyPatcher.apply(
                new String(scaffold.policyBytes(), StandardCharsets.UTF_8),
                patch(0, synthesis.providers(), initialWorkspace(scaffold.projectRoot(),
                        loadedDocuments)));
        byte[] candidateBytes = yaml.getBytes(StandardCharsets.UTF_8);
        boolean metadataCreated = false;
        try {
            if (!Files.exists(scaffold.metadataPath())) {
                metadataCreated = ProjectPolicyTools.createMetadata(
                        scaffold.metadataPath(), scaffold.metadataBytes());
            }
            ProjectPolicy candidate = ProjectPolicyTools.validateCandidate(
                    target, candidateBytes, live);
            requireValid(candidate);
            requireScopedCredentialsRemainBound(candidate, owner);
            if (governingPolicy(live).loaded()) throw changed();
            ProjectPolicyTools.atomicWrite(target, candidateBytes, false);
        } catch (FileAlreadyExistsException concurrent) {
            ProjectPolicyTools.rollbackCreatedMetadata(scaffold.metadataPath(), metadataCreated,
                    scaffold.metadataBytes());
            throw changed();
        } catch (IOException | RuntimeException failure) {
            ProjectPolicyTools.rollbackCreatedMetadata(scaffold.metadataPath(), metadataCreated,
                    scaffold.metadataBytes());
            throw failure;
        }
        ProjectPolicy applied = ProjectPolicyLoader.load(target, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
        requireValid(applied);
        return new Result(true, target, applied, synthesis.omitted());
    }

    private static Map<String, Object> patch(int version,
            List<Map<String, Object>> providers, Map<String, Object> initialWorkspace) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (version != 3) {
            result.put("version", 3);
            result.put("workspace", initialWorkspace);
        }
        result.put("external_terms", Map.of("providers", providers));
        return result;
    }

    private static boolean synchronizationRequired(ProjectPolicy policy,
            ProviderPolicyBindings.Synthesis synthesis) {
        return policy == null || !policy.loaded() || policy.version() != 3
                || !sameProviders(providers(policy), synthesis.providers());
    }

    private static boolean sameProviders(List<Map<String, Object>> current,
            List<Map<String, Object>> configured) {
        if (current.size() != configured.size()) return false;
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> provider : current) {
            Object id = provider.get("id");
            if (!(id instanceof String value) || byId.putIfAbsent(value, provider) != null) {
                return false;
            }
        }
        for (Map<String, Object> provider : configured) {
            Object id = provider.get("id");
            if (!(id instanceof String value) || !provider.equals(byId.remove(value))) {
                return false;
            }
        }
        return byId.isEmpty();
    }

    /**
     * Build the first v3 workspace from the saved ontologies currently loaded in Protégé. Files
     * outside the policy root remain External Ontologies and are deliberately not made members.
     */
    private static Map<String, Object> initialWorkspace(Path projectRoot,
            List<WorkspaceDocument> loadedDocuments)
            throws IOException {
        Path root = projectRoot.toRealPath();
        Map<String, LinkedHashSet<String>> byOntology = new LinkedHashMap<>();
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (WorkspaceDocument candidate : loadedDocuments) {
            if (candidate == null || candidate.path() == null
                    || !Files.isRegularFile(candidate.path())) continue;
            Path file = candidate.path().toRealPath();
            if (!file.startsWith(root)) continue;
            String relative = root.relativize(file).toString()
                    .replace(java.io.File.separatorChar, '/');
            files.add(relative);
            if (candidate.ontologyIri() != null && !candidate.ontologyIri().isBlank()) {
                byOntology.computeIfAbsent(candidate.ontologyIri(), ignored -> new LinkedHashSet<>())
                        .add(relative);
            }
        }
        List<String> sortedFiles = files.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<Map<String, Object>> ontologies = new ArrayList<>();
        byOntology.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> ontologies.add(Map.of(
                        "iri", entry.getKey(),
                        "documents", entry.getValue().stream()
                                .sorted(String.CASE_INSENSITIVE_ORDER).toList())));
        Map<String, Object> workspace = new LinkedHashMap<>();
        workspace.put("files", sortedFiles);
        if (!ontologies.isEmpty()) workspace.put("ontologies", List.copyOf(ontologies));
        return workspace;
    }

    private static Path target(Path documentPath, ProjectPolicy snapshot) throws IOException {
        if (snapshot != null && snapshot.loaded() && snapshot.path() != null) {
            return snapshot.path().toAbsolutePath().normalize();
        }
        if (documentPath == null) throw new IOException("Save the active ontology first.");
        Path parent = documentPath.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Ontology document has no parent folder.");
        return parent.resolve(ProjectPolicyLoader.DEFAULT_RELATIVE_PATH);
    }

    private static CapturedPolicy capture(Path target) throws IOException {
        PolicySourcePin pin = ProjectPolicyLoader.pinCanonicalPolicy(
                target.toAbsolutePath().normalize(),
                ProjectPolicyLoader.canonicalProjectAnchor(target));
        return ProjectPolicyLoader.captureStablePolicy(pin);
    }

    private static ProjectPolicy requireGoverningPolicy(Path expected,
            ProjectPolicyTools.PolicyContext live) throws IOException {
        return requireGoverningPolicy(expected, live.documentPath(),
                live.activeOntologyIri(), live.installedReasoners());
    }

    private static ProjectPolicy requireGoverningPolicy(Path expected, Path documentPath,
            String ontologyIri, List<String> installedReasoners) throws IOException {
        ProjectPolicy governing = governingPolicy(documentPath, ontologyIri, installedReasoners);
        if (!governing.loaded() || governing.path() == null
                || !Files.isSameFile(expected, governing.path())) {
            throw changed();
        }
        return governing;
    }

    private static ProjectPolicy governingPolicy(ProjectPolicyTools.PolicyContext live) {
        return governingPolicy(live.documentPath(), live.activeOntologyIri(),
                live.installedReasoners());
    }

    private static ProjectPolicy governingPolicy(Path documentPath, String ontologyIri,
            List<String> installedReasoners) {
        return ProjectPolicyLoader.load(null, documentPath, ontologyIri,
                List.copyOf(installedReasoners));
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder("sha256:");
            for (byte value : digest) {
                int unsigned = value & 0xff;
                result.append(Character.forDigit(unsigned >>> 4, 16));
                result.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireScopedCredentialsRemainBound(ProjectPolicy candidate,
            ProviderOwnerConfig owner) throws IOException {
        String fingerprint = ProviderPolicyBindings.projectFingerprint(candidate);
        for (Map<String, Object> provider : providers(candidate)) {
            Object credentialValue = provider.get("credential_id");
            if (!(credentialValue instanceof String credentialId)) continue;
            ProviderOwnerConfig.CredentialBinding credential =
                    owner.credentials().get(credentialId);
            if (credential != null && credential.projectFingerprint() != null
                    && !credential.projectFingerprint().equals(fingerprint)) {
                throw new IOException("Project-scoped credential '" + credentialId
                        + "' would no longer match after this policy update. Keep its existing "
                        + "provider row unchanged, or change the credential scope in Preferences "
                        + "before applying registries.");
            }
        }
    }

    private static void requireValid(ProjectPolicy policy) throws IOException {
        if (policy.valid()) return;
        String detail = policy.issues().isEmpty() ? "unknown validation error"
                : policy.issues().get(0).code() + ": " + policy.issues().get(0).message();
        throw new IOException("The generated policy was not written because it is invalid ("
                + detail + ").");
    }

    @SuppressWarnings("unchecked")
    private static int providerCount(ProjectPolicy policy) {
        return providers(policy).size();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providers(ProjectPolicy policy) {
        if (policy == null) return List.of();
        Object external = policy.effective().get("external_terms");
        if (!(external instanceof Map<?, ?> map)) return List.of();
        Object providers = ((Map<String, Object>) map).get("providers");
        return providers instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }

    private static IOException changed() {
        return new IOException("The project policy changed while registry updates were being "
                + "prepared. Review the current file and try again.");
    }

    public record Preview(boolean policyExists, int currentProviderCount,
            List<Map<String, Object>> providers, List<String> omitted, Path target,
            String sourceHash, boolean synchronizationRequired, int sourceVersion,
            List<WorkspaceDocument> workspaceDocuments) {
        public Preview(boolean policyExists, int currentProviderCount,
                List<Map<String, Object>> providers, List<String> omitted) {
            this(policyExists, currentProviderCount, providers, omitted, null, null, true, 0,
                    List.of());
        }
        public Preview {
            providers = List.copyOf(providers);
            omitted = List.copyOf(omitted);
            workspaceDocuments = List.copyOf(workspaceDocuments);
        }
    }

    /** One saved ontology/document binding captured from the live Protégé workspace. */
    public record WorkspaceDocument(String ontologyIri, Path path) { }

    public record Result(boolean created, Path path, ProjectPolicy policy, List<String> omitted) {
        public Result {
            omitted = List.copyOf(omitted);
        }
    }

    public record SynchronizationStatus(boolean required, List<String> omitted) {
        public SynchronizationStatus {
            omitted = List.copyOf(omitted);
        }
    }
}
