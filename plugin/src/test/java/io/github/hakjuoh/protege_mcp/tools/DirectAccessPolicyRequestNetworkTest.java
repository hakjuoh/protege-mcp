package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

/**
 * The request-level {@code network=deny|allow} composition matrix (ADR 0005 decision 3):
 * request × policy(none/allow/deny/invalid) × capability × local-admin profile × compatibility
 * preference, pinning the effective permission AND the denial-source attribution for every
 * combination. {@code allow} must be a byte-level no-op everywhere; {@code deny} always denies but
 * never repaints an administrative denial as the caller's own.
 */
class DirectAccessPolicyRequestNetworkTest {

    private static final URI REMOTE = URI.create("https://example.org/o.ttl");

    @Test
    void requestDenyDeniesAnOtherwiseOpenPolicyWithRequestAttribution(@TempDir Path temp)
            throws Exception {
        DirectAccessPolicy.Rules open = rules(policy(temp, "allow", "allow", "[]"),
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));
        assertTrue(open.importNetworkRule().allowed(), "sanity: the base policy is open");
        assertEquals(DirectAccessPolicy.DenialSource.NONE, open.importNetworkRule().denialSource());

        DirectAccessPolicy.Rules denied = open.withRequestNetwork("deny");
        DirectAccessPolicy.NetworkRule rule = denied.importNetworkRule();
        assertFalse(rule.allowed());
        assertEquals(DirectAccessPolicy.DenialSource.REQUEST, rule.denialSource());
        assertFalse(rule.permits(REMOTE));

        // The root document is denied with an explicit error, never a partial load.
        ToolArgException root = assertThrows(ToolArgException.class,
                () -> denied.authorizeNetwork(REMOTE, false));
        assertEquals("Network access is denied by the request argument network=deny.",
                root.getMessage());
        ToolArgException source = assertThrows(ToolArgException.class,
                () -> denied.authorizeSource(REMOTE.toString()));
        assertTrue(source.getMessage().contains("request argument network=deny"),
                source.getMessage());
    }

    @Test
    void requestAllowIsANoOpEverywhere(@TempDir Path temp) throws Exception {
        DirectAccessPolicy.Rules openPolicy = rules(policy(temp.resolve("open"), "allow", "allow",
                "[]"), Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));
        // Strongest possible no-op pin: allow (any case) and an absent/blank value return the SAME
        // rules instance, so nothing downstream can behave differently.
        assertSame(openPolicy, openPolicy.withRequestNetwork("allow"));
        assertSame(openPolicy, openPolicy.withRequestNetwork("ALLOW"));
        assertSame(openPolicy, openPolicy.withRequestNetwork(null));
        assertSame(openPolicy, openPolicy.withRequestNetwork("  "));

        // allow never overrides a policy deny — the released policy attribution stays byte-identical.
        DirectAccessPolicy.Rules denyPolicy = rules(policy(temp.resolve("deny"), "deny", "deny",
                "[]"), Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK))
                .withRequestNetwork("allow");
        assertEquals(DirectAccessPolicy.DenialSource.POLICY,
                denyPolicy.importNetworkRule().denialSource());
        ToolArgException imports = assertThrows(ToolArgException.class,
                () -> denyPolicy.authorizeNetwork(REMOTE, true));
        assertEquals("Network access is denied by the effective project policy "
                + "(imports.network=deny).", imports.getMessage());
        ToolArgException direct = assertThrows(ToolArgException.class,
                () -> denyPolicy.authorizeNetwork(REMOTE, false));
        assertEquals("Network access is denied by the effective project policy "
                + "(network.default=deny).", direct.getMessage());

        // allow never overrides a missing capability.
        DirectAccessPolicy.Rules noCapability = rules(policy(temp.resolve("cap"), "allow", "allow",
                "[]"), Set.of(DirectAccessPolicy.PROJECT_READ)).withRequestNetwork("allow");
        assertEquals(DirectAccessPolicy.DenialSource.CAPABILITY,
                noCapability.importNetworkRule().denialSource());
        ToolArgException capability = assertThrows(ToolArgException.class,
                () -> noCapability.authorizeNetwork(REMOTE, false));
        assertEquals("Network document access requires capability "
                + DirectAccessPolicy.NETWORK + ".", capability.getMessage());
    }

    @Test
    void administrativeDenialsOutrankTheRequestArgumentInAttribution(@TempDir Path temp)
            throws Exception {
        // policy deny + request deny: removing the request argument would not help, so the policy
        // remains the attributed source.
        DirectAccessPolicy.Rules policyDeny = rules(policy(temp.resolve("deny"), "deny", "deny",
                "[]"), Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK))
                .withRequestNetwork("deny");
        assertEquals(DirectAccessPolicy.DenialSource.POLICY,
                policyDeny.importNetworkRule().denialSource());

        // missing capability + request deny: the capability denial stands.
        DirectAccessPolicy.Rules noCapability = rules(policy(temp.resolve("cap"), "allow", "allow",
                "[]"), Set.of(DirectAccessPolicy.PROJECT_READ)).withRequestNetwork("deny");
        assertEquals(DirectAccessPolicy.DenialSource.CAPABILITY,
                noCapability.importNetworkRule().denialSource());

        // invalid policy + any request: fail-closed with the corrected invalid-policy source — the
        // released blocker misattributed this to a missing capability.
        DirectAccessPolicy.Rules invalid = rules(invalidPolicy(temp.resolve("invalid")),
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));
        for (DirectAccessPolicy.Rules variant : new DirectAccessPolicy.Rules[] {
                invalid, invalid.withRequestNetwork("deny"), invalid.withRequestNetwork("allow")}) {
            DirectAccessPolicy.NetworkRule rule = variant.importNetworkRule();
            assertFalse(rule.allowed());
            assertFalse(rule.capabilityAllowed(), "released fail-closed shape is preserved");
            assertEquals(DirectAccessPolicy.DenialSource.INVALID_POLICY, rule.denialSource());
        }
    }

    @Test
    void noPolicyMatrixAttributesCompatibilityAndCapabilityDenials() {
        // Unrestricted local-admin profile: base allowed; request deny is the only denial.
        DirectAccessPolicy.Rules unrestricted = new DirectAccessPolicy.Rules(
                ProjectPolicy.notFound(), principal(Set.of(DirectAccessPolicy.LOCAL_ADMIN)), true);
        assertTrue(unrestricted.importNetworkRule().allowed());
        assertEquals(DirectAccessPolicy.DenialSource.NONE,
                unrestricted.importNetworkRule().denialSource());
        // ... and network=allow is the documented no-op affirmation, not an error.
        assertSame(unrestricted, unrestricted.withRequestNetwork("allow"));
        assertEquals(DirectAccessPolicy.DenialSource.REQUEST,
                unrestricted.withRequestNetwork("deny").importNetworkRule().denialSource());

        // Compatibility preference off: the denial names the compatibility state, never a phantom
        // policy — with or without a request argument.
        DirectAccessPolicy.Rules restricted = new DirectAccessPolicy.Rules(
                ProjectPolicy.notFound(),
                principal(Set.of(DirectAccessPolicy.LOCAL_ADMIN, DirectAccessPolicy.NETWORK)),
                false);
        assertEquals(DirectAccessPolicy.DenialSource.COMPATIBILITY_PREFERENCE,
                restricted.importNetworkRule().denialSource());
        assertEquals(DirectAccessPolicy.DenialSource.COMPATIBILITY_PREFERENCE,
                restricted.withRequestNetwork("allow").importNetworkRule().denialSource());
        ToolArgException denied = assertThrows(ToolArgException.class,
                () -> restricted.withRequestNetwork("allow").authorizeNetwork(REMOTE, false));
        assertTrue(denied.getMessage().contains("no project policy is loaded"), denied.getMessage());
        assertFalse(denied.getMessage().contains("network.default=deny"), denied.getMessage());

        // A non-admin principal with only network:access: the local-admin profile is what is
        // missing, so the compatibility source applies even with the preference on.
        DirectAccessPolicy.Rules nonAdmin = new DirectAccessPolicy.Rules(ProjectPolicy.notFound(),
                principal(Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK)),
                true);
        assertEquals(DirectAccessPolicy.DenialSource.COMPATIBILITY_PREFERENCE,
                nonAdmin.importNetworkRule().denialSource());

        // No network:access at all: the capability denial wins (released precedence).
        DirectAccessPolicy.Rules noCapability = new DirectAccessPolicy.Rules(
                ProjectPolicy.notFound(), principal(Set.of(DirectAccessPolicy.PROJECT_READ)), true);
        assertEquals(DirectAccessPolicy.DenialSource.CAPABILITY,
                noCapability.importNetworkRule().denialSource());
    }

    @Test
    void requestDenyOverAnAllowlistedBaseKeepsThePolicyFlavoredAttribution(@TempDir Path temp)
            throws Exception {
        // The allowlist restricted the posture with or without the request (the catalog gate was
        // already engaged), so the composed denial must carry ALLOWED_HOSTS, never REQUEST.
        DirectAccessPolicy.Rules denied = rules(policy(temp, "allow", "allow", "[example.org]"),
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK))
                .withRequestNetwork("deny");
        DirectAccessPolicy.NetworkRule composed = denied.importNetworkRule();
        assertFalse(composed.allowed());
        assertEquals(DirectAccessPolicy.DenialSource.ALLOWED_HOSTS, composed.denialSource());

        // Direct path stays truthful per URI: an off-list host keeps the released allowlist
        // verdict; an on-list host is denied only by the caller's own request.
        ToolArgException offList = assertThrows(ToolArgException.class,
                () -> denied.authorizeNetwork(URI.create("https://other.example/o.ttl"), false));
        assertEquals("Network host 'other.example' is not present in network.allowed_hosts.",
                offList.getMessage());
        ToolArgException onList = assertThrows(ToolArgException.class,
                () -> denied.authorizeNetwork(URI.create("https://example.org/o.ttl"), false));
        assertEquals("Network access is denied by the request argument network=deny.",
                onList.getMessage());
    }

    @Test
    void invalidRequestValuesAreRejectedStrictly(@TempDir Path temp) throws Exception {
        DirectAccessPolicy.Rules rules = rules(policy(temp, "allow", "allow", "[]"),
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));
        for (String bad : new String[] {"denied", "block", "true", "no"}) {
            ToolArgException rejected = assertThrows(ToolArgException.class,
                    () -> rules.withRequestNetwork(bad));
            assertTrue(rejected.getMessage().contains("Invalid network"), rejected.getMessage());
            assertThrows(ToolArgException.class,
                    () -> DirectAccessPolicy.requestNetworkDenies(bad));
        }
        assertTrue(DirectAccessPolicy.requestNetworkDenies("Deny"));
        assertFalse(DirectAccessPolicy.requestNetworkDenies("Allow"));
        assertFalse(DirectAccessPolicy.requestNetworkDenies(null));
    }

    @Test
    void redirectCouplingIsUnchangedByTheRequestArgument(@TempDir Path temp) throws Exception {
        DirectAccessPolicy.Rules allowlisted = rules(policy(temp.resolve("hosts"), "allow", "allow",
                "[example.org]"), Set.of(DirectAccessPolicy.PROJECT_READ,
                        DirectAccessPolicy.NETWORK));
        assertFalse(allowlisted.importNetworkRule().followRedirects(),
                "sanity: a non-empty allowlist disables redirects");
        DirectAccessPolicy.NetworkRule requestDenied =
                allowlisted.withRequestNetwork("deny").importNetworkRule();
        assertFalse(requestDenied.followRedirects(),
                "a request deny must not re-enable redirect following");
        assertFalse(requestDenied.allowed());
        assertEquals(Set.of("example.org"), requestDenied.allowedHosts(),
                "the composed rule keeps the allowlist for transparency");

        DirectAccessPolicy.Rules open = rules(policy(temp.resolve("open"), "allow", "allow", "[]"),
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));
        assertTrue(open.withRequestNetwork("deny").importNetworkRule().followRedirects(),
                "no allowlist: the redirect flag stays as released (the rule is denied anyway)");
    }

    // ------------------------------------------------------------------ fixtures

    private static ProjectPolicy policy(Path project, String networkDefault, String importsNetwork,
            String hosts) throws Exception {
        Path path = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(path,
                ProjectPolicyFixtures.minimalPolicy("request-network", "https://example.org/access")
                        + "filesystem:\n  allow_external_paths: false\n"
                        + "network:\n  default: " + networkDefault + "\n  allowed_hosts: " + hosts
                        + "\n"
                        + "imports:\n  network: " + importsNetwork + "\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return policy;
    }

    private static ProjectPolicy invalidPolicy(Path project) throws Exception {
        Path path = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(path,
                ProjectPolicyFixtures.minimalPolicy("request-network-invalid",
                        "https://example.org/access")
                        + "modules:\n  - ontology_iri: https://example.org/missing\n"
                        + "    path: missing.rdf\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(path, null);
        assertTrue(policy.loaded());
        assertFalse(policy.valid(), "a module file that does not exist must invalidate the policy");
        return policy;
    }

    private static DirectAccessPolicy.Rules rules(ProjectPolicy policy, Set<String> capabilities) {
        return new DirectAccessPolicy.Rules(policy, principal(capabilities));
    }

    private static AuthenticatedPrincipal principal(Set<String> capabilities) {
        return new AuthenticatedPrincipal(1, "test", "test-client", "Test", capabilities, null);
    }
}
