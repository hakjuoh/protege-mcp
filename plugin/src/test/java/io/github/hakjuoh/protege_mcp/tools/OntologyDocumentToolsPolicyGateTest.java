package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

/**
 * The policy-aware import gate on {@link OntologyDocumentTools}' OWN loaders — {@code fetch}
 * (behind {@code load_ontology}) and {@code load} (behind {@code merge_ontology_document}) — with a
 * RESTRICTIVE {@code NetworkRule}, not the allow-all compatibility overloads the other document
 * tests reach. DiffToolsTest covers the shared blocker through {@code DiffTools.loadDocument};
 * these tests pin the same enforcement through the two production tool loaders.
 */
class OntologyDocumentToolsPolicyGateTest {

    @Test
    void forbiddenRemoteImportsFailBothProductionLoadersBeforeNetworkDereference(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ;
                    owl:imports <https://network-must-not-be-used.invalid/import.ttl> .
                """);
        DirectAccessPolicy.Rules rules = rules(project,
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));

        for (Method loader : loaders()) {
            Throwable denied = assertThrows(Throwable.class,
                    () -> invoke(loader, root.toString(), 1_000, MissingImportsMode.WARN,
                            List.of(), rules.importNetworkRule()));
            assertInstanceOf(ToolArgException.class, denied);
            assertTrue(denied.getMessage().contains("imports.network=deny"), denied.getMessage());
            assertTrue(denied.getMessage().contains("network-must-not-be-used.invalid"),
                    denied.getMessage());
        }
    }

    @Test
    void catalogMappingTargetsOutsideTheProjectAreRefusedWhileInRootTargetsResolve(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/catalog-alias";
        Path outside = temp.resolve("outside.ttl");
        Files.writeString(outside, ontology(imported));
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        Path catalog = project.resolve("catalog-v001.xml");
        Files.writeString(catalog, catalog(imported, "../outside.ttl"));
        DirectAccessPolicy.Rules rules = rules(project,
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));

        for (Method loader : loaders()) {
            Throwable denied = assertThrows(Throwable.class,
                    () -> invoke(loader, root.toString(), 1_000, MissingImportsMode.WARN,
                            List.of(), rules.importNetworkRule()));
            assertInstanceOf(ToolArgException.class, denied);
            assertTrue(denied.getMessage().contains("mapping target"), denied.getMessage());
            assertTrue(denied.getMessage().contains("outside project_root"), denied.getMessage());
        }

        // The very same gate accepts an in-root mapping target — strict mode proves the import
        // really resolved through the catalog (the remote fetch itself stays denied).
        Files.writeString(project.resolve("imported.ttl"), ontology(imported));
        Files.writeString(catalog, catalog(imported, "imported.ttl"));
        for (Method loader : loaders()) {
            assertDoesNotThrow(() -> invoke(loader, root.toString(), 1_000,
                    MissingImportsMode.ERROR, List.of(), rules.importNetworkRule()),
                    "an in-project catalog target must load offline under the restrictive rule");
        }
    }

    @Test
    void workspaceMappingTargetsOutsideTheProjectAreRefusedWhileInRootTargetsResolve(
            @TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/workspace-imported";
        Path outside = temp.resolve("workspace-outside.ttl");
        Files.writeString(outside, ontology(imported));
        Path root = project.resolve("module.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        DirectAccessPolicy.Rules rules = rules(project,
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));
        List<OntologyDocumentTools.ImportMapping> escaping = List.of(
                new OntologyDocumentTools.ImportMapping(IRI.create(imported),
                        IRI.create(outside.toUri())));

        for (Method loader : loaders()) {
            Throwable denied = assertThrows(Throwable.class,
                    () -> invoke(loader, root.toString(), 1_000, MissingImportsMode.WARN,
                            escaping, rules.importNetworkRule()));
            assertInstanceOf(ToolArgException.class, denied);
            assertTrue(denied.getMessage().contains("mapping target"), denied.getMessage());
            assertTrue(denied.getMessage().contains("outside project_root"), denied.getMessage());
        }

        Path inRoot = project.resolve("workspace-imported.ttl");
        Files.writeString(inRoot, ontology(imported));
        List<OntologyDocumentTools.ImportMapping> contained = List.of(
                new OntologyDocumentTools.ImportMapping(IRI.create(imported),
                        IRI.create(inRoot.toUri())));
        for (Method loader : loaders()) {
            assertDoesNotThrow(() -> invoke(loader, root.toString(), 1_000,
                    MissingImportsMode.ERROR, contained, rules.importNetworkRule()),
                    "an in-project workspace mapping must load offline under the restrictive rule");
        }
    }

    @Test
    void remoteCatalogDelegationIsNotDereferencedUnderNetworkDeny(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/via-catalog-delegation";
        Files.writeString(project.resolve("imported.ttl"), ontology(imported));
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));

        // A loopback HTTP catalog server that records every hit; the gate must never dereference it.
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = ("<?xml version=\"1.0\"?><catalog "
                    + "xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                    + "<uri name=\"" + imported + "\" uri=\"file://" + project.resolve("imported.ttl")
                    + "\"/></catalog>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DirectAccessPolicy.Rules rules = rules(project,
                    Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));
            // Every element type the resolver dereferences (nextCatalog + delegateURI), plus a
            // DOCTYPE-prefixed catalog that a stricter scan parser would reject while the resolver still
            // parses and follows it. None may reach the loopback server under network=deny.
            String remote = "http://127.0.0.1:" + port + "/catalog-v001.xml";
            List<String> vectors = List.of(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                            + "<nextCatalog catalog=\"" + remote + "\"/></catalog>\n",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                            + "<delegateURI uriStartString=\"\" catalog=\"" + remote + "\"/></catalog>\n",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE catalog>\n"
                            + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                            + "<nextCatalog catalog=\"" + remote + "\"/></catalog>\n",
                    // A remote external DTD the resolver's own parser would fetch (our scan must refuse
                    // the whole catalog rather than let the resolver reparse and dereference it).
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<!DOCTYPE catalog SYSTEM \"http://127.0.0.1:" + port + "/evil.dtd\">\n"
                            + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                            + "<uri name=\"" + imported + "\" uri=\"imported.ttl\"/></catalog>\n",
                    // An INTERNAL-subset external parameter entity the resolver's unhardened parser
                    // fetches at construction (the DOCTYPE has no external system id, so a system-id-only
                    // check would miss it — refusing any DOCTYPE closes it).
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                            + "<!DOCTYPE catalog [ <!ENTITY % r SYSTEM \"http://127.0.0.1:" + port
                            + "/evil.dtd\"> %r; ]>\n"
                            + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                            + "<uri name=\"" + imported + "\" uri=\"imported.ttl\"/></catalog>\n");

            for (String catalogXml : vectors) {
                hits.set(0);
                Files.writeString(project.resolve("catalog-v001.xml"), catalogXml);
                for (Method loader : loaders()) {
                    // The import cannot resolve (the remote delegation is refused, no local mapping is
                    // installed), so the load fails closed — but the loopback catalog must NOT be fetched.
                    try {
                        invoke(loader, root.toString(), 2_000, MissingImportsMode.WARN,
                                List.of(), rules.importNetworkRule());
                    } catch (Throwable failClosed) {
                        // Expected: resolution fails; the security property is the absence of a fetch.
                    }
                }
                assertEquals(0, hits.get(),
                        () -> "a remote catalog delegation must not be dereferenced under network=deny: "
                                + catalogXml);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aSelfReferentialLocalCatalogDoesNotOverflowTheResolver(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/cyclic-catalog";
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        // A catalog whose only nextCatalog points at itself: the raw resolver would recurse until it
        // overflows. The offline scan detects the cycle and refuses the delegate, so the load fails
        // closed WITHOUT a StackOverflowError.
        Files.writeString(project.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <nextCatalog catalog="catalog-v001.xml"/>
                </catalog>
                """);
        DirectAccessPolicy.Rules rules = rules(project,
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));

        for (Method loader : loaders()) {
            try {
                invoke(loader, root.toString(), 2_000, MissingImportsMode.WARN,
                        List.of(), rules.importNetworkRule());
            } catch (StackOverflowError overflow) {
                throw new AssertionError("a self-referential catalog must not overflow the resolver",
                        overflow);
            } catch (Throwable failClosed) {
                // A normal fail-closed (unresolved import) is fine; only an overflow is a defect.
            }
        }
    }

    @Test
    void aRemoteCatalogDelegationIsRefusedEvenWhenItsHostIsAllowlisted(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/allowlisted-delegation";
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));

        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\"/>"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            // network=allow with 127.0.0.1 explicitly allowlisted — a DIRECT remote import would be
            // permitted, but a catalog DELEGATION to that same host is still refused, because the
            // resolver's catalog fetch follows redirects and external DTDs the allowlist cannot bound.
            DirectAccessPolicy.Rules rules = allowlistRules(project, "127.0.0.1");
            Files.writeString(project.resolve("catalog-v001.xml"), ("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                      <nextCatalog catalog="http://127.0.0.1:%d/catalog-v001.xml"/>
                    </catalog>
                    """).formatted(port));

            for (Method loader : loaders()) {
                try {
                    invoke(loader, root.toString(), 2_000, MissingImportsMode.WARN,
                            List.of(), rules.importNetworkRule());
                } catch (Throwable failClosed) {
                    // fine
                }
            }
            assertEquals(0, hits.get(),
                    "a remote catalog delegation must be refused even to an allowlisted host");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aSymlinkedCatalogEscapingTheProjectIsRefused(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/via-symlinked-catalog";
        Files.writeString(project.resolve("imported.ttl"), ontology(imported));
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        // An OUT-OF-PROJECT catalog that maps the import to the in-project file; only the catalog FILE
        // is outside the project (the mapping target is inside), isolating the catalog-file read.
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("catalog-v001.xml"),
                catalog(imported, project.resolve("imported.ttl").toUri().toString()));
        Path link = project.resolve("catalog-v001.xml");
        try {
            Files.createSymbolicLink(link, outside.resolve("catalog-v001.xml"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e.getMessage());
        }
        DirectAccessPolicy.Rules rules = rules(project,
                Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK));

        for (Method loader : loaders()) {
            // The catalog file symlinks OUT of project_root (allow_external_paths=false), so it is
            // refused unread and the import cannot resolve through it — the load fails closed.
            Throwable denied = assertThrows(Throwable.class,
                    () -> invoke(loader, root.toString(), 2_000, MissingImportsMode.ERROR,
                            List.of(), rules.importNetworkRule()));
            assertInstanceOf(ToolArgException.class, denied);
        }

        // Control: the SAME catalog content as a REAL in-project file resolves the import offline.
        Files.delete(link);
        Files.writeString(project.resolve("catalog-v001.xml"), catalog(imported, "imported.ttl"));
        for (Method loader : loaders()) {
            assertDoesNotThrow(() -> invoke(loader, root.toString(), 2_000, MissingImportsMode.ERROR,
                    List.of(), rules.importNetworkRule()),
                    "an in-project catalog file must still resolve the import offline");
        }
    }

    @Test
    void aLocalDelegationCatalogStillResolvesWhenTheNetworkIsUnrestricted(@TempDir Path temp)
            throws Exception {
        // Under a fully-open network (network=allow, no host allowlist), the strict catalog gate must
        // NOT fire: a folder catalog that delegates via a LOCAL <nextCatalog> to an in-project
        // sub-catalog must still resolve the import offline (no false refusal). The strict gate only
        // applies under a network-restricted policy.
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/via-local-delegation";
        Files.writeString(project.resolve("imported.ttl"), ontology(imported));
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        Files.writeString(project.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <nextCatalog catalog="sub/catalog-v001.xml"/>
                </catalog>
                """);
        Files.createDirectories(project.resolve("sub"));
        Files.writeString(project.resolve("sub/catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <uri name="%s" uri="../imported.ttl"/>
                </catalog>
                """.formatted(imported));
        DirectAccessPolicy.Rules rules = openNetworkRules(project);

        for (Method loader : loaders()) {
            assertDoesNotThrow(() -> invoke(loader, root.toString(), 2_000, MissingImportsMode.ERROR,
                    List.of(), rules.importNetworkRule()),
                    "a local-delegation catalog must resolve offline when the network is unrestricted");
        }
    }

    @Test
    void aDoctypeCatalogIsRefusedUnderAConfinedFilesystemEvenWithOpenNetwork(@TempDir Path temp)
            throws Exception {
        // network=allow (open) but allow_external_paths=false: a catalog DOCTYPE would let the
        // resolver's unhardened parser read a local file (XXE, a filesystem-containment violation) and
        // exfiltrate it over the open network. The gate must fire on the FILESYSTEM axis and refuse the
        // catalog, so the (remote) external DTD is never fetched.
        Path project = Files.createDirectories(temp.resolve("project"));
        String imported = "https://example.org/via-doctype";
        Files.writeString(project.resolve("imported.ttl"), ontology(imported));
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "<!ENTITY x \"\">".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Files.writeString(project.resolve("catalog-v001.xml"), "<?xml version=\"1.0\"?>\n"
                    + "<!DOCTYPE catalog SYSTEM \"http://127.0.0.1:" + port + "/evil.dtd\">\n"
                    + "<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">"
                    + "<uri name=\"" + imported + "\" uri=\"imported.ttl\"/></catalog>\n");
            DirectAccessPolicy.Rules rules = networkOpenFilesystemConfinedRules(project);
            for (Method loader : loaders()) {
                try {
                    invoke(loader, root.toString(), 2_000, MissingImportsMode.WARN,
                            List.of(), rules.importNetworkRule());
                } catch (Throwable failClosed) {
                    // fine
                }
            }
            assertEquals(0, hits.get(),
                    "a DOCTYPE catalog must be refused under a confined filesystem, so its DTD is not read");
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A valid loaded policy: network open to any host, but the filesystem confined to the project. */
    private static DirectAccessPolicy.Rules networkOpenFilesystemConfinedRules(Path project)
            throws Exception {
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("document-gate-fsconfined", "https://example.org/root")
                        + "filesystem:\n  allow_external_paths: false\n"
                        + "network:\n  default: allow\n"
                        + "imports:\n  network: allow\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return new DirectAccessPolicy.Rules(policy,
                new AuthenticatedPrincipal(1, "test", "test-client", "Test",
                        Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK), null));
    }

    /** A valid loaded policy with BOTH axes fully open — any network host and external paths allowed. */
    private static DirectAccessPolicy.Rules openNetworkRules(Path project) throws Exception {
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("document-gate-open", "https://example.org/root")
                        + "filesystem:\n  allow_external_paths: true\n"
                        + "network:\n  default: allow\n"
                        + "imports:\n  network: allow\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return new DirectAccessPolicy.Rules(policy,
                new AuthenticatedPrincipal(1, "test", "test-client", "Test",
                        Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK), null));
    }

    /** A valid loaded policy that allows the network but only to {@code allowedHost}. */
    private static DirectAccessPolicy.Rules allowlistRules(Path project, String allowedHost)
            throws Exception {
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("document-gate-allow", "https://example.org/root")
                        + "filesystem:\n  allow_external_paths: false\n"
                        + "network:\n  default: allow\n  allowed_hosts: [" + allowedHost + "]\n"
                        + "imports:\n  network: allow\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return new DirectAccessPolicy.Rules(policy,
                new AuthenticatedPrincipal(1, "test", "test-client", "Test",
                        Set.of(DirectAccessPolicy.PROJECT_READ, DirectAccessPolicy.NETWORK), null));
    }

    /** A valid loaded policy that denies networks and outside-project paths outright. */
    private static DirectAccessPolicy.Rules rules(Path project, Set<String> capabilities)
            throws Exception {
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("document-gate", "https://example.org/root")
                        + "filesystem:\n  allow_external_paths: false\n"
                        + "network:\n  default: deny\n"
                        + "imports:\n  network: deny\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        assertTrue(policy.valid(), () -> policy.issues().toString());
        return new DirectAccessPolicy.Rules(policy,
                new AuthenticatedPrincipal(1, "test", "test-client", "Test", capabilities, null));
    }

    /** Both policy-aware production loaders: fetch (load_ontology) and load (merge document). */
    private static List<Method> loaders() throws Exception {
        List<Method> out = new ArrayList<>();
        for (String name : List.of("fetch", "load")) {
            Method method = OntologyDocumentTools.class.getDeclaredMethod(name, String.class,
                    int.class, MissingImportsMode.class, List.class,
                    DirectAccessPolicy.NetworkRule.class);
            method.setAccessible(true);
            out.add(method);
        }
        return out;
    }

    private static Object invoke(Method method, Object... args) throws Throwable {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static String ontology(String iri) {
        return """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <%s> a owl:Ontology .
                """.formatted(iri);
    }

    private static String catalog(String imported, String target) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <uri name="%s" uri="%s"/>
                </catalog>
                """.formatted(imported, target);
    }
}
