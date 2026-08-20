package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.external.ProviderOwnerConfig;
import io.github.hakjuoh.protege_mcp.external.ProviderPolicyBindings;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;

class ProjectPolicyRegistrySyncTest {

    @Test
    void upgradesV2AndInitializesWorkspaceFromEveryLoadedProjectOntology(
            @TempDir Path directory, @TempDir Path externalDirectory) throws Exception {
        Path ontology = Files.writeString(directory.resolve("ontology.ttl"), "");
        Path module = Files.writeString(directory.resolve("module.rdf"), "");
        Path external = Files.writeString(externalDirectory.resolve("external.owl"), "");
        Path policyPath = directory.resolve(".protege-mcp/project.yaml");
        ProjectPolicyScaffold.Scaffold scaffold = ProjectPolicyScaffold.prepare(policyPath,
                ontology, "https://example.org/root", List.of(), null,
                ProjectPolicyTemplate.GENERAL, null, 2);
        Files.createDirectories(policyPath.getParent());
        Files.write(policyPath, scaffold.policyBytes());
        Files.write(scaffold.metadataPath(), scaffold.metadataBytes());
        ProjectPolicy v2 = io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                policyPath, ontology);
        assertTrue(v2.valid(), v2.issues().toString());

        List<ProjectPolicyRegistrySync.WorkspaceDocument> loaded = List.of(
                new ProjectPolicyRegistrySync.WorkspaceDocument(
                        "https://example.org/root", ontology),
                new ProjectPolicyRegistrySync.WorkspaceDocument(
                        "https://example.org/module", module),
                new ProjectPolicyRegistrySync.WorkspaceDocument(
                        "https://example.org/external", external));
        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/root", List.of(), v2,
                ProviderOwnerConfig.empty(), loaded);
        assertTrue(preview.synchronizationRequired());
        assertEquals(2, preview.sourceVersion());

        ProjectPolicy migrated = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/root", List.of(), preview,
                ProviderOwnerConfig.empty()).policy();
        assertEquals(3, migrated.version());
        Map<String, Object> workspace = object(migrated.effective().get("workspace"));
        assertEquals(List.of("module.rdf", "ontology.ttl"), workspace.get("files"));
        assertTrue(String.valueOf(workspace.get("ontologies"))
                .contains("https://example.org/module"));
        assertFalse(String.valueOf(workspace.get("files")).contains("external.owl"));
    }

    @Test
    void upgradesV1DirectlyToV3(@TempDir Path directory) throws Exception {
        Path ontology = Files.writeString(directory.resolve("ontology.ttl"), "");
        Path module = Files.writeString(directory.resolve("module.rdf"), "");
        ProjectPolicy v1 = scaffoldPolicy(directory, ontology, "https://example.org/root", 1);

        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/root", List.of(), v1,
                ProviderOwnerConfig.empty(), List.of(
                        new ProjectPolicyRegistrySync.WorkspaceDocument(
                                "https://example.org/root", ontology),
                        new ProjectPolicyRegistrySync.WorkspaceDocument(
                                "https://example.org/module", module)));
        ProjectPolicy migrated = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/root", List.of(), preview,
                ProviderOwnerConfig.empty()).policy();

        assertEquals(3, migrated.version());
        assertEquals(List.of("module.rdf", "ontology.ttl"),
                object(migrated.effective().get("workspace")).get("files"));
    }

    @Test
    void externalOrUnsavedActiveOntologyIsNeverBoundToThePolicyAnchor(
            @TempDir Path directory, @TempDir Path externalDirectory) throws Exception {
        Path root = Files.writeString(directory.resolve("root.owl"), "");
        Path external = Files.writeString(externalDirectory.resolve("external.owl"), "");
        ProjectPolicy v2 = scaffoldPolicy(directory, root, "https://example.org/root", 2);

        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(
                root, "https://example.org/external-active", List.of(), v2,
                ProviderOwnerConfig.empty(), List.of(
                        new ProjectPolicyRegistrySync.WorkspaceDocument(
                                "https://example.org/root", root),
                        new ProjectPolicyRegistrySync.WorkspaceDocument(
                                "https://example.org/external-active", external)));
        ProjectPolicy migrated = ProjectPolicyRegistrySync.apply(root,
                "https://example.org/external-active", List.of(), preview,
                ProviderOwnerConfig.empty()).policy();
        String workspace = String.valueOf(migrated.effective().get("workspace"));

        assertTrue(workspace.contains("https://example.org/root"));
        assertFalse(workspace.contains("https://example.org/external-active"));
        assertFalse(workspace.contains("external.owl"));

        ProjectPolicy v2Again = scaffoldPolicy(directory, root, "https://example.org/root", 2);
        ProjectPolicyRegistrySync.Preview unsaved = ProjectPolicyRegistrySync.preview(
                root, "https://example.org/unsaved-active", List.of(), v2Again,
                ProviderOwnerConfig.empty(), List.of(
                        new ProjectPolicyRegistrySync.WorkspaceDocument(
                                "https://example.org/root", root)));
        ProjectPolicy migratedUnsaved = ProjectPolicyRegistrySync.apply(root,
                "https://example.org/unsaved-active", List.of(), unsaved,
                ProviderOwnerConfig.empty()).policy();
        assertFalse(String.valueOf(migratedUnsaved.effective().get("workspace"))
                .contains("https://example.org/unsaved-active"));
    }

    @Test
    void oneOntologyIriCanBindMultipleProjectDocuments(@TempDir Path directory)
            throws Exception {
        Path first = Files.writeString(directory.resolve("first.owl"), "");
        Path second = Files.writeString(directory.resolve("second.ttl"), "");
        ProjectPolicy v2 = scaffoldPolicy(directory, first, "https://example.org/shared", 2);
        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(
                first, "https://example.org/shared", List.of(), v2,
                ProviderOwnerConfig.empty(), List.of(
                        new ProjectPolicyRegistrySync.WorkspaceDocument(
                                "https://example.org/shared", first),
                        new ProjectPolicyRegistrySync.WorkspaceDocument(
                                "https://example.org/shared", second)));

        ProjectPolicy migrated = ProjectPolicyRegistrySync.apply(first,
                "https://example.org/shared", List.of(), preview,
                ProviderOwnerConfig.empty()).policy();
        String ontologies = String.valueOf(
                object(migrated.effective().get("workspace")).get("ontologies"));
        assertTrue(ontologies.contains("first.owl"));
        assertTrue(ontologies.contains("second.ttl"));
    }

    @Test
    void synchronizingExistingV3PreservesCustomWorkspace(@TempDir Path directory)
            throws Exception {
        Path ontology = Files.writeString(directory.resolve("ontology.ttl"), "");
        Path manual = Files.writeString(directory.resolve("manual.rdf"), "");
        ProjectPolicy base = scaffoldPolicy(directory, ontology, "https://example.org/root", 3);
        String customized = ProjectPolicyPatcher.apply(Files.readString(base.path()), Map.of(
                "workspace", Map.of(
                        "files", List.of("ontology.ttl", "manual.rdf"),
                        "ontologies", List.of(Map.of(
                                "iri", "https://example.org/custom",
                                "documents", List.of("manual.rdf"))))));
        Files.writeString(base.path(), customized);
        ProjectPolicy custom = io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                base.path(), ontology);
        assertTrue(custom.valid(), custom.issues().toString());
        Object workspaceBefore = custom.effective().get("workspace");
        ProviderOwnerConfig owner = owner("ebi", "ols4", "https://www.ebi.ac.uk/ols4");

        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/root", List.of(), custom, owner,
                List.of(new ProjectPolicyRegistrySync.WorkspaceDocument(
                        "https://example.org/root", ontology)));
        ProjectPolicy updated = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/root", List.of(), preview, owner).policy();

        assertEquals(workspaceBefore, updated.effective().get("workspace"));
        assertTrue(Files.isRegularFile(manual));
    }

    @Test
    void createsUpdatesAndClearsRegistryPolicyWithoutAnAssistantTurn(@TempDir Path directory)
            throws Exception {
        Path ontology = directory.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/test> a owl:Ontology .\n");
        ProviderOwnerConfig owner = owner("ebi", "ols4", "https://www.ebi.ac.uk/ols4");

        ProjectPolicyRegistrySync.Preview before = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/test", List.of(), ProjectPolicy.notFound(), owner);
        assertFalse(before.policyExists());
        assertEquals(1, before.providers().size());
        assertTrue(before.synchronizationRequired());

        ProjectPolicyRegistrySync.Result created = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/test", List.of(), before, owner);
        assertTrue(created.created());
        assertTrue(created.policy().valid());
        assertEquals(3, created.policy().version());
        assertEquals("ebi", provider(created.policy()).get("origin_alias"));
        assertTrue(Files.exists(directory.resolve("ro-crate-metadata.json")));
        String authored = Files.readString(created.path());
        assertTrue(authored.contains("# Protégé MCP project policy"));
        assertFalse(authored.contains("https://www.ebi.ac.uk/ols4"));

        ProjectPolicyRegistrySync.Preview unchanged = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/test", List.of(), created.policy(), owner);
        assertFalse(unchanged.synchronizationRequired());

        ProviderOwnerConfig movedEndpoint = owner(
                "ebi", "ols4", "https://example.org/private-ols");
        ProjectPolicyRegistrySync.Preview updatePreview = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/test", List.of(), created.policy(), movedEndpoint);
        assertFalse(updatePreview.synchronizationRequired(),
                "owner-local endpoint changes do not alter the portable policy binding");

        ProjectPolicyRegistrySync.Preview clearPreview = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/test", List.of(), created.policy(),
                ProviderOwnerConfig.empty());
        assertTrue(clearPreview.synchronizationRequired());
        ProjectPolicyRegistrySync.Result cleared = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/test", List.of(), clearPreview, ProviderOwnerConfig.empty());
        assertTrue(cleared.policy().valid());
        assertTrue(providers(cleared.policy()).isEmpty());
    }

    @Test
    void missingOwnerBindingIsAWarningAndOntoPortalWithoutCredentialIsSkipped(
            @TempDir Path directory) throws Exception {
        Path ontology = directory.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/test> a owl:Ontology .\n");
        ProviderOwnerConfig ebi = owner("ebi", "ols4", "https://www.ebi.ac.uk/ols4");
        ProjectPolicyRegistrySync.Preview createPreview = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/test", List.of(), ProjectPolicy.notFound(), ebi);
        ProjectPolicy policy = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/test", List.of(), createPreview, ebi).policy();

        var warnings = ProviderPolicyBindings.warnings(policy, ProviderOwnerConfig.empty());
        assertEquals(1, warnings.size());
        assertEquals("warning", warnings.get(0).severity());
        assertEquals("provider_origin_unbound", warnings.get(0).code());

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("errors", new ArrayList<Map<String, Object>>());
        validation.put("warnings", new ArrayList<Map<String, Object>>());
        ProjectPolicyTools.appendOwnerProviderWarnings(
                validation, policy, ProviderOwnerConfig.empty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> publicWarnings =
                (List<Map<String, Object>>) validation.get("warnings");
        assertEquals("provider_origin_unbound", publicWarnings.get(0).get("code"));

        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(ontology,
                "https://example.org/test", List.of(), policy,
                owner("bioportal", "ontoportal", "https://data.bioontology.org"));
        assertTrue(preview.providers().isEmpty());
        assertEquals(1, preview.omitted().size());
    }

    @Test
    void doesNotPublishCredentialsScopedToAnotherProject(@TempDir Path directory)
            throws Exception {
        Path ontology = directory.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/test> a owl:Ontology .\n");
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "private-ols", "ols4", URI.create("https://example.org/ols"));
        ProviderOwnerConfig.CredentialBinding credential =
                new ProviderOwnerConfig.CredentialBinding("private-key", "private-ols",
                        "private-ols", ProviderOwnerConfig.AuthScheme.BEARER,
                        "Authorization", "sha256:another-project");
        ProviderOwnerConfig owner = ProviderOwnerConfig.of(Map.of(origin.alias(), origin),
                Map.of(credential.id(), credential));

        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(
                ontology, "https://example.org/test", List.of(), ProjectPolicy.notFound(), owner);

        assertTrue(preview.providers().isEmpty());
        assertEquals(List.of("private-ols (no credential binding applies to this project)"),
                preview.omitted());
    }

    @Test
    void rejectsPolicyChangedAfterPreview(@TempDir Path directory) throws Exception {
        Path ontology = ontology(directory);
        ProviderOwnerConfig owner = owner("ebi", "ols4", "https://www.ebi.ac.uk/ols4");
        ProjectPolicyRegistrySync.Preview create = ProjectPolicyRegistrySync.preview(ontology,
                "https://example.org/test", List.of(), ProjectPolicy.notFound(), owner);
        ProjectPolicy policy = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/test", List.of(), create, owner).policy();
        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(ontology,
                "https://example.org/test", List.of(), policy, ProviderOwnerConfig.empty());

        Files.writeString(policy.path(), Files.readString(policy.path()) + "\n# concurrent edit\n");
        String concurrentlyEdited = Files.readString(policy.path());

        assertThrows(java.io.IOException.class, () -> ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/test", List.of(), preview, ProviderOwnerConfig.empty()));
        assertEquals(concurrentlyEdited, Files.readString(policy.path()));
    }

    @Test
    void rejectsUpdateThatWouldInvalidateProjectScopedCredential(@TempDir Path directory)
            throws Exception {
        Path ontology = ontology(directory);
        ProviderOwnerConfig.OriginBinding ebi = ProviderOwnerConfig.bindOrigin(
                "ebi", "ols4", URI.create("https://www.ebi.ac.uk/ols4"));
        ProviderOwnerConfig.CredentialBinding unscoped = credential(
                "ebi-key", "ebi", "ebi", null);
        ProviderOwnerConfig initialOwner = ProviderOwnerConfig.of(Map.of("ebi", ebi),
                Map.of(unscoped.id(), unscoped));
        ProjectPolicyRegistrySync.Preview create = ProjectPolicyRegistrySync.preview(ontology,
                "https://example.org/test", List.of(), ProjectPolicy.notFound(), initialOwner);
        ProjectPolicy policy = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/test", List.of(), create, initialOwner).policy();
        String source = Files.readString(policy.path());

        ProviderOwnerConfig.OriginBinding publicOls = ProviderOwnerConfig.bindOrigin(
                "public", "ols4", URI.create("https://example.org/public-ols"));
        ProviderOwnerConfig.CredentialBinding scoped = credential("ebi-key", "ebi", "ebi",
                ProviderPolicyBindings.projectFingerprint(policy));
        ProviderOwnerConfig changedOwner = ProviderOwnerConfig.of(
                Map.of("ebi", ebi, "public", publicOls), Map.of(scoped.id(), scoped));
        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(ontology,
                "https://example.org/test", List.of(), policy, changedOwner);

        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> ProjectPolicyRegistrySync.apply(ontology, "https://example.org/test",
                        List.of(), preview, changedOwner));
        assertTrue(failure.getMessage().contains("would no longer match"));
        assertEquals(source, Files.readString(policy.path()));
    }

    @Test
    void rejectsAmbiguousAllProjectCredentials(@TempDir Path directory) throws Exception {
        Path ontology = ontology(directory);
        ProviderOwnerConfig.OriginBinding origin = ProviderOwnerConfig.bindOrigin(
                "ebi", "ols4", URI.create("https://www.ebi.ac.uk/ols4"));
        ProviderOwnerConfig.CredentialBinding first = credential("first", "ebi", "ebi", null);
        ProviderOwnerConfig.CredentialBinding second = credential("second", "ebi", "ebi", null);
        ProviderOwnerConfig owner = ProviderOwnerConfig.of(Map.of("ebi", origin),
                Map.of(first.id(), first, second.id(), second));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ProjectPolicyRegistrySync.preview(ontology, "https://example.org/test",
                        List.of(), ProjectPolicy.notFound(), owner));
        assertTrue(failure.getMessage().contains("multiple all-project credentials"));
    }

    @Test
    void providerOrderAloneDoesNotRequireSynchronization(@TempDir Path directory)
            throws Exception {
        Path ontology = ontology(directory);
        ProviderOwnerConfig.OriginBinding first = ProviderOwnerConfig.bindOrigin(
                "first", "ols4", URI.create("https://example.org/first"));
        ProviderOwnerConfig.OriginBinding second = ProviderOwnerConfig.bindOrigin(
                "second", "ols4", URI.create("https://example.org/second"));
        Map<String, ProviderOwnerConfig.OriginBinding> originalOrder = new LinkedHashMap<>();
        originalOrder.put(first.alias(), first);
        originalOrder.put(second.alias(), second);
        ProviderOwnerConfig original = ProviderOwnerConfig.of(originalOrder, Map.of());
        ProjectPolicyRegistrySync.Preview create = ProjectPolicyRegistrySync.preview(ontology,
                "https://example.org/test", List.of(), ProjectPolicy.notFound(), original);
        ProjectPolicy policy = ProjectPolicyRegistrySync.apply(ontology,
                "https://example.org/test", List.of(), create, original).policy();

        Map<String, ProviderOwnerConfig.OriginBinding> reversedOrder = new LinkedHashMap<>();
        reversedOrder.put(second.alias(), second);
        reversedOrder.put(first.alias(), first);
        ProviderOwnerConfig reversed = ProviderOwnerConfig.of(reversedOrder, Map.of());

        assertFalse(ProjectPolicyRegistrySync.synchronizationRequired(policy, reversed));

        ProviderOwnerConfig.OriginBinding privateOrigin = ProviderOwnerConfig.bindOrigin(
                "private", "ols4", URI.create("https://example.org/private"));
        reversedOrder.put(privateOrigin.alias(), privateOrigin);
        ProviderOwnerConfig.CredentialBinding otherProject = credential(
                "private-key", "private", "private", "sha256:another-project");
        ProviderOwnerConfig withSkippedBinding = ProviderOwnerConfig.of(reversedOrder,
                Map.of(otherProject.id(), otherProject));
        ProjectPolicyRegistrySync.SynchronizationStatus status =
                ProjectPolicyRegistrySync.synchronizationStatus(policy, withSkippedBinding);
        assertFalse(status.required());
        assertEquals(1, status.omitted().size());
    }

    @Test
    void rejectsWhenANearerPolicyBecomesGoverning(@TempDir Path directory) throws Exception {
        Path rootOntology = ontology(directory);
        ProviderOwnerConfig owner = owner("ebi", "ols4", "https://www.ebi.ac.uk/ols4");
        ProjectPolicyRegistrySync.Preview create = ProjectPolicyRegistrySync.preview(rootOntology,
                "https://example.org/test", List.of(), ProjectPolicy.notFound(), owner);
        ProjectPolicy ancestor = ProjectPolicyRegistrySync.apply(rootOntology,
                "https://example.org/test", List.of(), create, owner).policy();
        Path nested = directory.resolve("nested");
        Files.createDirectories(nested);
        Path nestedOntology = ontology(nested);
        ProjectPolicy inherited = io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                null, nestedOntology, "https://example.org/test", List.of());
        assertEquals(ancestor.path(), inherited.path());

        Path nearerDirectory = nested.resolve(".protege-mcp");
        Files.createDirectories(nearerDirectory);
        Path nearerPolicy = nearerDirectory.resolve("project.yaml");
        Files.copy(ancestor.path(), nearerPolicy);
        Files.copy(directory.resolve("ro-crate-metadata.json"),
                nested.resolve("ro-crate-metadata.json"));
        assertThrows(java.io.IOException.class, () -> ProjectPolicyRegistrySync.preview(
                nestedOntology, "https://example.org/test", List.of(), inherited, owner));

        Files.delete(nearerPolicy);
        ProjectPolicyRegistrySync.Preview preview = ProjectPolicyRegistrySync.preview(
                nestedOntology, "https://example.org/test", List.of(), inherited,
                ProviderOwnerConfig.empty());
        String ancestorSource = Files.readString(ancestor.path());
        Files.copy(ancestor.path(), nearerPolicy);

        assertThrows(java.io.IOException.class, () -> ProjectPolicyRegistrySync.apply(
                nestedOntology, "https://example.org/test", List.of(), preview,
                ProviderOwnerConfig.empty()));
        assertEquals(ancestorSource, Files.readString(ancestor.path()));
    }

    private static Path ontology(Path directory) throws Exception {
        Path ontology = directory.resolve("ontology.ttl");
        Files.writeString(ontology, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "<https://example.org/test> a owl:Ontology .\n");
        return ontology;
    }

    private static ProjectPolicy scaffoldPolicy(Path directory, Path ontology,
            String ontologyIri, int version) throws Exception {
        Path policyPath = directory.resolve(".protege-mcp/project.yaml");
        ProjectPolicyScaffold.Scaffold scaffold = ProjectPolicyScaffold.prepare(policyPath,
                ontology, ontologyIri, List.of(), null,
                ProjectPolicyTemplate.GENERAL, null, version);
        Files.createDirectories(policyPath.getParent());
        Files.write(policyPath, scaffold.policyBytes());
        Files.write(scaffold.metadataPath(), scaffold.metadataBytes());
        ProjectPolicy policy = io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader.load(
                policyPath, ontology);
        assertTrue(policy.valid(), policy.issues().toString());
        return policy;
    }

    private static ProviderOwnerConfig.CredentialBinding credential(String id,
            String providerId, String alias, String fingerprint) {
        return new ProviderOwnerConfig.CredentialBinding(id, providerId, alias,
                ProviderOwnerConfig.AuthScheme.BEARER, "Authorization", fingerprint);
    }

    private static ProviderOwnerConfig owner(String alias, String profile, String origin) {
        ProviderOwnerConfig.OriginBinding binding = ProviderOwnerConfig.bindOrigin(
                alias, profile, URI.create(origin));
        return ProviderOwnerConfig.of(Map.of(alias, binding), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providers(ProjectPolicy policy) {
        return (List<Map<String, Object>>) ((Map<String, Object>) policy.effective()
                .get("external_terms")).get("providers");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> provider(ProjectPolicy policy) {
        return providers(policy).get(0);
    }
}
