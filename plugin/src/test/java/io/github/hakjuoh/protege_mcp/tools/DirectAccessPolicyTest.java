package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

class DirectAccessPolicyTest {

    @Test
    void policyRelativePathsRootAtProjectAndSymlinkEscapesFailClosed(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.ttl"), "secret");
        ProjectPolicy policy = policy(project, false, "deny", "[]");
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ,
                        DirectAccessPolicy.PROJECT_WRITE)));

        assertEquals(project.toRealPath().resolve("quality/shapes.ttl"),
                rules.readPath("quality/shapes.ttl"));
        try {
            Files.createSymbolicLink(project.resolve("escape"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e.getMessage());
        }
        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> rules.readPath("escape/secret.ttl"));
        assertTrue(denied.getMessage().contains("outside project_root"));
    }

    @Test
    void externalPathNeedsBothPolicyOptInAndExternalCapability(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Path external = temp.resolve("external.ttl");
        Files.writeString(external, "x");
        ProjectPolicy policy = policy(project, true, "deny", "[]");
        DirectAccessPolicy.Rules withoutCapability = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ)));
        assertThrows(ToolArgException.class, () -> withoutCapability.readPath(external.toString()));

        DirectAccessPolicy.Rules allowed = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.EXTERNAL)));
        assertEquals(external.toRealPath(), allowed.readPath(external.toString()));
    }

    @Test
    void networkDefaultsImportsOverrideAndHostAllowlistAreEnforced(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        ProjectPolicy policy = policy(project, true, "allow", "[raw.githubusercontent.com]");
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK)));

        rules.authorizeNetwork(URI.create("https://raw.githubusercontent.com/x/y/main/o.ttl"), false);
        assertThrows(ToolArgException.class,
                () -> rules.authorizeNetwork(URI.create("https://example.org/o.ttl"), false));
        DirectAccessPolicy.NetworkRule imports = rules.importNetworkRule();
        assertFalse(imports.allowed(), "imports.network=deny overrides network.default=allow");
        assertFalse(imports.permits(URI.create("https://raw.githubusercontent.com/x")));
        assertFalse(imports.followRedirects(), "an active host allowlist must not follow unchecked redirects");
    }

    @Test
    void nestedJarUrisCannotTurnNetworkAuthorityIntoFilesystemAuthority(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        ProjectPolicy policy = policy(project, true, "allow", "[]");
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK)));
        URI nestedLocal = URI.create("jar:" + temp.resolve("outside.jar").toUri() + "!/ontology.ttl");

        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> rules.authorizeSource(nestedLocal.toString()));

        assertTrue(denied.getMessage().contains("Nested jar:"));
        assertFalse(rules.importNetworkRule().permits(nestedLocal));
    }

    @Test
    void localImportsCannotEscapeTheProjectThroughAFileIri(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path inside = project.resolve("inside.ttl");
        Path outside = temp.resolve("outside.ttl");
        Files.writeString(inside, "inside");
        Files.writeString(outside, "outside");
        ProjectPolicy policy = policy(project, false, "deny", "[]");
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ)));
        DirectAccessPolicy.NetworkRule imports = rules.importNetworkRule();

        assertEquals(null, imports.fileImportDenial(inside.toUri()));
        assertTrue(imports.fileImportDenial(outside.toUri()).contains("outside project_root"));
    }

    @Test
    void disablingTheNoPolicyCompatibilityModeRejectsEvenLocalAdminPaths(@TempDir Path temp) {
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(ProjectPolicy.notFound(),
                principal(Set.of(DirectAccessPolicy.LOCAL_ADMIN)), false);

        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> rules.readPath(temp.resolve("ontology.ttl").toString()));

        assertTrue(denied.getMessage().contains("disabled until a project policy is loaded"));
    }

    @Test
    void noPolicyNetworkDenialBlamesTheCompatibilityPreferenceNotAPhantomPolicy() {
        // No policy is loaded and the no-policy compatibility switch is off. There is no
        // network.default/imports.network to blame, so the denial must attribute the real cause (the
        // compatibility preference) rather than citing a phantom policy that does not exist.
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(ProjectPolicy.notFound(),
                principal(Set.of(DirectAccessPolicy.LOCAL_ADMIN, DirectAccessPolicy.NETWORK)), false);

        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> rules.authorizeNetwork(URI.create("https://example.org/o.ttl"), false));

        assertFalse(denied.getMessage().contains("network.default=deny"),
                () -> "must not cite a policy that does not exist: " + denied.getMessage());
        assertTrue(denied.getMessage().contains("no project policy is loaded"), denied.getMessage());
    }

    @Test
    void projectReadAloneCannotBootstrapAnArbitraryNoPolicyFilesystemRoot(@TempDir Path temp) {
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(ProjectPolicy.notFound(),
                principal(Set.of(DirectAccessPolicy.PROJECT_READ)), true);

        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> rules.readPath(temp.resolve("attacker-selected-project.yaml").toString()));

        assertTrue(denied.getMessage().contains("local-admin compatibility profile"));
    }

    @Test
    void writePathPinsNewTargetsUnderTheCanonicalRootAndRefusesEscapes(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        ProjectPolicy policy = policy(project, false, "deny", "[]");
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ,
                        DirectAccessPolicy.PROJECT_WRITE)));

        assertEquals(project.toRealPath().resolve("quality/new-shapes.ttl"),
                rules.writePath("quality/new-shapes.ttl"),
                "a not-yet-existing in-root write target must resolve under the canonical root");
        ToolArgException relative = assertThrows(ToolArgException.class,
                () -> rules.writePath("../escaped.ttl"));
        assertTrue(relative.getMessage().contains("outside project_root"), relative.getMessage());
        try {
            Files.createSymbolicLink(project.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e.getMessage());
        }
        ToolArgException symlinked = assertThrows(ToolArgException.class,
                () -> rules.writePath("link/new.ttl"),
                "a new write target behind a symlinked directory must be pinned to its real path");
        assertTrue(symlinked.getMessage().contains("outside project_root"), symlinked.getMessage());
    }

    @Test
    void writesRequireTheWriteCapabilityEvenInsideTheProject(@TempDir Path temp) throws Exception {
        ProjectPolicy policy = policy(temp.resolve("project"), false, "deny", "[]");
        DirectAccessPolicy.Rules readOnly = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ)));

        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> readOnly.writePath("quality/shapes.ttl"));

        assertTrue(denied.getMessage().contains(
                "require capability " + DirectAccessPolicy.PROJECT_WRITE), denied.getMessage());
    }

    @Test
    void derivedImplicitPathsSkipOnlyTheCompatibilityOptInNeverPolicyContainment(@TempDir Path temp)
            throws Exception {
        // With NO policy and the compatibility mode disabled, a caller-selected path stays refused
        // while a target derived from the open document (the argument-less save_ontology) authorizes.
        Path derived = temp.resolve("doc/ontology.ttl");
        DirectAccessPolicy.Rules noPolicy = new DirectAccessPolicy.Rules(ProjectPolicy.notFound(),
                principal(Set.of(DirectAccessPolicy.PROJECT_READ,
                        DirectAccessPolicy.PROJECT_WRITE)), false);
        assertEquals(temp.toRealPath().resolve("doc/ontology.ttl"),
                noPolicy.implicitPath(derived, true),
                "a derived save target confers no caller-selected authority to opt into");
        ToolArgException read = assertThrows(ToolArgException.class,
                () -> noPolicy.readPath(derived.toString()));
        assertTrue(read.getMessage().contains("disabled until a project policy is loaded"),
                read.getMessage());
        ToolArgException write = assertThrows(ToolArgException.class,
                () -> noPolicy.writePath(derived.toString()));
        assertTrue(write.getMessage().contains("disabled until a project policy is loaded"),
                write.getMessage());

        // With a loaded policy the derived target still gets containment: outside stays refused.
        Path project = temp.resolve("project");
        ProjectPolicy policy = policy(project, false, "deny", "[]");
        DirectAccessPolicy.Rules confined = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ,
                        DirectAccessPolicy.PROJECT_WRITE)));
        assertEquals(project.toRealPath().resolve("ontology.ttl"),
                confined.implicitPath(project.resolve("ontology.ttl"), true));
        ToolArgException escape = assertThrows(ToolArgException.class,
                () -> confined.implicitPath(temp.resolve("outside-derived.ttl"), true));
        assertTrue(escape.getMessage().contains("outside project_root"), escape.getMessage());
    }

    @Test
    void policyExternalOptOutOverridesExternalAndAdminCapabilities(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        Path external = temp.resolve("external.ttl");
        Files.writeString(external, "x");
        ProjectPolicy policy = policy(project, false, "deny", "[]");

        DirectAccessPolicy.Rules externalCapability = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.EXTERNAL)));
        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> externalCapability.readPath(external.toString()),
                "filesystem:external must not override the policy's allow_external_paths=false");
        assertTrue(denied.getMessage().contains("allow_external_paths is false"),
                denied.getMessage());

        DirectAccessPolicy.Rules admin = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.LOCAL_ADMIN)));
        ToolArgException adminDenied = assertThrows(ToolArgException.class,
                () -> admin.readPath(external.toString()),
                "local:admin must not override the policy's allow_external_paths=false");
        assertTrue(adminDenied.getMessage().contains("allow_external_paths is false"),
                adminDenied.getMessage());
    }

    @Test
    void loadedButInvalidPolicyFailsClosedAtUseTimeNotAtResolveTime(@TempDir Path temp)
            throws Exception {
        Path project = temp.resolve("project");
        Path path = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(path,
                ProjectPolicyFixtures.minimalPolicy("access", "https://example.org/access")
                        + "modules:\n  - ontology_iri: https://example.org/missing\n"
                        + "    path: missing.rdf\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        assertTrue(policy.loaded());
        assertFalse(policy.valid(), "a module file that does not exist must invalidate the policy");
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                principal(Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.PROJECT_WRITE,
                        DirectAccessPolicy.NETWORK, DirectAccessPolicy.EXTERNAL)));

        ToolArgException read = assertThrows(ToolArgException.class, () -> rules.readPath("x.ttl"));
        assertTrue(read.getMessage().contains("project policy is invalid"));
        ToolArgException write = assertThrows(ToolArgException.class, () -> rules.writePath("x.ttl"));
        assertTrue(write.getMessage().contains("project policy is invalid"));
        ToolArgException source = assertThrows(ToolArgException.class,
                () -> rules.authorizeSource("https://example.org/o.ttl"));
        assertTrue(source.getMessage().contains("project policy is invalid"));
        DirectAccessPolicy.NetworkRule imports = rules.importNetworkRule();
        assertFalse(imports.allowed(), "an invalid policy must deny import fetches without throwing");
        assertFalse(imports.permits(URI.create("https://example.org/o.ttl")));
        Path inside = project.resolve("inside.ttl");
        Files.writeString(inside, "x");
        assertTrue(imports.fileImportDenial(inside.toUri()).contains("project policy is invalid"));
    }

    private static ProjectPolicy policy(Path project, boolean external, String network,
            String hosts) throws Exception {
        Path path = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(path,
                ProjectPolicyFixtures.minimalPolicy("access", "https://example.org/access")
                        + "filesystem:\n  allow_external_paths: " + external + "\n"
                        + "network:\n  default: " + network + "\n  allowed_hosts: " + hosts + "\n"
                        + "imports:\n  network: deny\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return policy;
    }

    private static AuthenticatedPrincipal principal(Set<String> capabilities) {
        return new AuthenticatedPrincipal(1, "test", "test-client", "Test",
                capabilities, null);
    }
}
