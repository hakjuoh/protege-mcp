package io.github.hakjuoh.protege_mcp.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderOwnerConfigTest {

    @TempDir
    Path temporary;

    @Test
    void resolvesExactOriginAndCredentialScopeWithoutSecretMaterial() throws Exception {
        Path root = temporary.resolve("providers");
        write(root, """
                {"version":1,"origins":[
                  {"alias":"ebi","profile":"ols4","origin":"https://www.ebi.ac.uk/ols4"},
                  {"alias":"test","profile":"ols4","origin":"https://127.0.0.1:9443/ols4",
                   "test_only_loopback":true}
                ],"credentials":[
                  {"id":"ols-token","provider_id":"ebi-ols","origin_alias":"ebi",
                   "scheme":"bearer","project_fingerprint":"sha256:project"}
                ]}
                """);

        ProviderOwnerConfig config = ProviderOwnerConfig.load(root);
        ProviderOwnerConfig.ResolvedProvider resolved = config.resolve(
                "ebi", "ebi-ols", "ols4", "ols-token", "sha256:project");
        assertEquals("https://www.ebi.ac.uk/ols4", resolved.origin().origin().toString());
        assertEquals("Authorization", resolved.credential().header());
        assertEquals(ProviderOwnerConfig.AuthScheme.BEARER, resolved.credential().scheme());
        assertTrue(resolved.toString().contains("ols-token"));
        assertNull(config.resolve("ebi", "ebi-ols", "ols4", null, "sha256:project")
                .credential());

        ProviderFailure wrongProvider = assertThrows(ProviderFailure.class,
                () -> config.resolve("ebi", "other", "ols4", "ols-token", "sha256:project"));
        assertEquals("provider_credential_unbound", wrongProvider.code());
        ProviderFailure wrongProject = assertThrows(ProviderFailure.class,
                () -> config.resolve("ebi", "ebi-ols", "ols4", "ols-token", "other"));
        assertEquals("provider_credential_unbound", wrongProject.code());
        ProviderFailure wrongProfile = assertThrows(ProviderFailure.class,
                () -> config.resolve("ebi", "ebi-ols", "fake", null, "sha256:project"));
        assertEquals("provider_origin_unbound", wrongProfile.code());

        Path apiRoot = temporary.resolve("api-key");
        write(apiRoot, """
                {"version":1,"origins":[{"alias":"x","profile":"ols4",
                 "origin":"https://example.org/ols4"}],"credentials":[
                 {"id":"c","provider_id":"x","origin_alias":"x","scheme":"api_key"}]}
                """);
        assertEquals("X-Api-Key", ProviderOwnerConfig.load(apiRoot)
                .resolve("x", "x", "ols4", "c", "project").credential().header());
    }

    @Test
    void malformedOriginsHeadersAndUnknownFieldsFailClosed() throws Exception {
        assertInvalid("""
                {"version":1,"origins":[{"alias":"x","profile":"ols4",
                 "origin":"http://example.org/ols4"}],"credentials":[]}
                """);
        assertInvalid("""
                {"version":1,"origins":[{"alias":"x","profile":"ols4",
                 "origin":"https://example.org/ols4","test_only_loopback":true}],"credentials":[]}
                """);
        assertInvalid("""
                {"version":1,"origins":[{"alias":"x","profile":"ols4",
                 "origin":"https://example.org/ols4"}],"credentials":[
                 {"id":"c","provider_id":"x","origin_alias":"x","scheme":"api_key",
                  "header":"Host"}]}
                """);
        assertInvalid("""
                {"version":1,"origins":[{"alias":"x","profile":"ols4",
                 "origin":"https://example.org/ols4"}],"credentials":[
                 {"id":"c","provider_id":"x","origin_alias":"x","scheme":"api_key",
                  "header":"Transfer-Encoding"}]}
                """);
        assertInvalid("""
                {"version":1,"origins":[],"credentials":[],"secret":"must-not-be-accepted"}
                """);
    }

    @Test
    void symlinksAndNonOwnerPermissionsAreRejected() throws Exception {
        Path root = OwnerOnlyFiles.prepareDirectory(temporary.resolve("providers"));
        Path outside = temporary.resolve("outside.json");
        Files.writeString(outside, "{\"version\":1,\"origins\":[],\"credentials\":[]}");
        try {
            Files.createSymbolicLink(root.resolve(ProviderOwnerConfig.FILE_NAME), outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        ProviderFailure symlink = assertThrows(ProviderFailure.class,
                () -> ProviderOwnerConfig.load(root));
        assertEquals("provider_store_invalid", symlink.code());
        Files.delete(root.resolve(ProviderOwnerConfig.FILE_NAME));

        write(root, "{\"version\":1,\"origins\":[],\"credentials\":[]}");
        Path config = root.resolve(ProviderOwnerConfig.FILE_NAME);
        var attributes = Files.getFileAttributeView(config,
                java.nio.file.attribute.UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes != null) {
            attributes.write("protege-mcp-test",
                    java.nio.ByteBuffer.wrap(new byte[] {1}));
            assertEquals(0, ProviderOwnerConfig.load(root).origins().size());
            attributes.delete("protege-mcp-test");
        }
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(config, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
            ProviderFailure permissions = assertThrows(ProviderFailure.class,
                    () -> ProviderOwnerConfig.load(root));
            assertEquals("provider_store_invalid", permissions.code());
            assertTrue(Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS));
        }

        Path realParent = OwnerOnlyFiles.prepareDirectory(temporary.resolve("real-parent"));
        OwnerOnlyFiles.prepareDirectory(realParent.resolve("providers"));
        Path parentAlias = temporary.resolve("parent-alias");
        try {
            Files.createSymbolicLink(parentAlias, realParent);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        assertEquals("provider_store_invalid", assertThrows(ProviderFailure.class,
                () -> ProviderOwnerConfig.load(parentAlias.resolve("providers"))).code());

        if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
            Path broad = Files.createDirectory(temporary.resolve("broad-store"));
            Files.setPosixFilePermissions(broad,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---"));
            assertEquals("provider_store_invalid",
                    assertThrows(ProviderFailure.class, () -> ProviderOwnerConfig.load(broad)).code());
        }
    }

    @Test
    void oversizedConfigurationIsRejectedBeforeParsing() throws Exception {
        Path root = temporary.resolve("oversized");
        OwnerOnlyFiles.write(root, ProviderOwnerConfig.FILE_NAME,
                new byte[ProviderOwnerConfig.MAX_BYTES + 1]);
        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> ProviderOwnerConfig.load(root));
        assertEquals("provider_store_invalid", failure.code());
    }

    @Test
    void completeOwnerBindingParticipatesInCacheScope() throws Exception {
        String template = """
                {"version":1,"origins":[{"alias":"x","profile":"ols4",
                 "origin":"https://example.org/ols4"}],"credentials":[
                 {"id":"c","provider_id":"x","origin_alias":"x","scheme":"api_key",
                  "header":"%s","project_fingerprint":"project-a"}]}
                """;
        Path firstRoot = temporary.resolve("binding-one");
        Path secondRoot = temporary.resolve("binding-two");
        write(firstRoot, template.formatted("X-First-Key"));
        write(secondRoot, template.formatted("X-Second-Key"));
        ProviderOwnerConfig.ResolvedProvider first = ProviderOwnerConfig.load(firstRoot)
                .resolve("x", "x", "ols4", "c", "project-a");
        ProviderOwnerConfig.ResolvedProvider second = ProviderOwnerConfig.load(secondRoot)
                .resolve("x", "x", "ols4", "c", "project-a");
        OwnerCredentialStore store = new OwnerCredentialStore(temporary.resolve("binding-secret"));
        store.rotate("c", "secret".getBytes(StandardCharsets.US_ASCII));
        try (OwnerCredentialStore.CredentialLease lease = store.open("c")) {
            assertTrue(first.cacheScopeFingerprint(lease).matches("sha256:[0-9a-f]{64}"));
            assertTrue(!first.cacheScopeFingerprint(lease)
                    .equals(second.cacheScopeFingerprint(lease)));

            ProviderOwnerConfig config = ProviderOwnerConfig.load(firstRoot);
            ProviderOwnerConfig.ResolvedProvider anonymousA = config.resolve(
                    "x", "x", "ols4", null, "project-a");
            ProviderOwnerConfig.ResolvedProvider anonymousB = config.resolve(
                    "x", "x", "ols4", null, "project-b");
            assertTrue(!anonymousA.cacheScopeFingerprint(null)
                    .equals(anonymousB.cacheScopeFingerprint(null)));

            Path unrestrictedRoot = temporary.resolve("binding-unrestricted");
            write(unrestrictedRoot, """
                    {"version":1,"origins":[{"alias":"x","profile":"ols4",
                     "origin":"https://example.org/ols4"}],"credentials":[
                     {"id":"c","provider_id":"x","origin_alias":"x","scheme":"api_key"}]}
                    """);
            ProviderOwnerConfig unrestricted = ProviderOwnerConfig.load(unrestrictedRoot);
            assertTrue(!unrestricted.resolve("x", "x", "ols4", "c", "project-a")
                    .cacheScopeFingerprint(lease).equals(unrestricted.resolve(
                            "x", "x", "ols4", "c", "project-b")
                            .cacheScopeFingerprint(lease)));
        }
    }

    @Test
    void fallbackChannelOpenAtomicallyRejectsSymlinks() throws Exception {
        Path outside = temporary.resolve("outside-channel");
        Files.writeString(outside, "value");
        Path link = temporary.resolve("channel-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("symbolic links unavailable");
        }
        assertThrows(java.io.IOException.class, () -> OwnerOnlyFiles.openNoFollow(
                link, java.util.Set.of(java.nio.file.StandardOpenOption.READ)));
    }

    private void assertInvalid(String json) throws Exception {
        Path root = temporary.resolve("invalid-" + Math.abs(json.hashCode()));
        write(root, json);
        ProviderFailure failure = assertThrows(ProviderFailure.class,
                () -> ProviderOwnerConfig.load(root));
        assertEquals("provider_configuration_invalid", failure.code());
        assertNull(failure.getCause());
        assertTrue(!failure.getMessage().contains("secret"));
    }

    private static void write(Path root, String json) throws ProviderFailure {
        OwnerOnlyFiles.write(root, ProviderOwnerConfig.FILE_NAME,
                json.getBytes(StandardCharsets.UTF_8));
    }
}
