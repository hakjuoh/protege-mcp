package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.policy.ProjectPolicy;
import io.github.hakjuoh.protege_mcp.policy.ProjectPolicyLoader;
import io.github.hakjuoh.protege_mcp.server.AuthenticatedPrincipal;
import io.github.hakjuoh.protege_mcp.testing.ProjectPolicyFixtures;

/** {@link DiffTools#diff} — the pure axiom-set partition behind diff_ontologies / round-trip checks. */
class DiffToolsTest {

    private static final String NS = "http://example.org/test#";

    @Test
    void partitionsAxiomsOnlyOnEachSide() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        OWLAxiom shared = df.getOWLDeclarationAxiom(a);
        OWLAxiom ab = df.getOWLSubClassOfAxiom(a, b);
        OWLAxiom ac = df.getOWLSubClassOfAxiom(a, c);

        Set<OWLAxiom> left = new LinkedHashSet<>(Arrays.asList(shared, ab));
        Set<OWLAxiom> right = new LinkedHashSet<>(Arrays.asList(shared, ac));

        DiffTools.Diff d = DiffTools.diff(left, right);
        assertTrue(d.onlyLeft.contains(ab), "A⊑B is only on the left");
        assertFalse(d.onlyLeft.contains(shared), "the shared declaration is not a difference");
        assertTrue(d.onlyRight.contains(ac), "A⊑C is only on the right");
        assertFalse(d.onlyRight.contains(ab));
    }

    @Test
    void identicalSetsHaveNoDifference() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        Set<OWLAxiom> left = new LinkedHashSet<>(Arrays.asList(
                df.getOWLDeclarationAxiom(a), df.getOWLSubClassOfAxiom(a, df.getOWLThing())));
        DiffTools.Diff d = DiffTools.diff(left, new LinkedHashSet<>(left));
        assertTrue(d.onlyLeft.isEmpty() && d.onlyRight.isEmpty(),
                "identical axiom sets diff to empty (a faithful round-trip)");
    }

    @Test
    void comparisonDocumentParserErrorsAreBounded(@TempDir Path temp) throws Exception {
        Path bad = temp.resolve("bad.txt");
        Files.writeString(bad, "not an ontology");

        ToolArgException error = org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> DiffTools.loadDocument(bad.toString(), java.util.List.of(),
                        new java.util.ArrayList<>()));

        assertTrue(error.getMessage().contains("registered parsers"), error.getMessage());
        assertFalse(error.getMessage().contains("Detailed logs:"), error.getMessage());
        assertTrue(error.getMessage().length() < 2_000);
    }

    @Test
    void forbiddenRemoteImportsFailBeforeNetworkDereference(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ;
                    owl:imports <https://network-must-not-be-used.invalid/import.ttl> .
                """);
        DirectAccessPolicy.NetworkRule offline = new DirectAccessPolicy.NetworkRule(
                false, Set.of(), false, true);

        ToolArgException denied = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class, () -> DiffTools.loadDocument(root.toString(),
                        java.util.List.of(), new java.util.ArrayList<>(), offline));

        assertTrue(denied.getMessage().contains("imports.network=deny"), denied.getMessage());
        assertTrue(denied.getMessage().contains("network-must-not-be-used.invalid"),
                denied.getMessage());
    }

    @Test
    void unusedUnsafeSiblingCatalogDoesNotBlockComparisonDocument(@TempDir Path temp)
            throws Exception {
        Path root = temp.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology .
                """);
        Files.writeString(temp.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <nextCatalog catalog="https://network-must-not-be-used.invalid/catalog.xml"/>
                </catalog>
                """);
        DirectAccessPolicy.NetworkRule offline = new DirectAccessPolicy.NetworkRule(
                false, Set.of(), false, true);

        assertDoesNotThrow(() -> DiffTools.loadDocument(root.toString(), java.util.List.of(),
                new java.util.ArrayList<>(), offline));
    }

    @Test
    void refusedSiblingCatalogReportsTheSameDiagnosticAsTheOtherLoadersForAnUnresolvedImport(
            @TempDir Path temp) throws Exception {
        // Same posture as load_ontology/merge_ontology_document: an unresolvable URN next to a
        // refused catalog must surface the structured folder-catalog diagnostic, not a raw OWLAPI
        // factory error.
        Path root = temp.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ;
                    owl:imports <urn:example:catalog-only-import> .
                """);
        Files.writeString(temp.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <nextCatalog catalog="https://network-must-not-be-used.invalid/catalog.xml"/>
                </catalog>
                """);
        DirectAccessPolicy.NetworkRule offline = new DirectAccessPolicy.NetworkRule(
                false, Set.of(), false, true);

        ToolArgException denied = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class, () -> DiffTools.loadDocument(root.toString(),
                        java.util.List.of(), new java.util.ArrayList<>(), offline));
        assertTrue(denied.getMessage().contains("folder catalog"), denied.getMessage());
        assertTrue(denied.getMessage().contains("catalog-only-import"), denied.getMessage());
    }

    @Test
    void unloadableSchemeImportWithoutACatalogRefusalIsReportedNotFatal(@TempDir Path temp)
            throws Exception {
        // No sibling catalog at all: the unsupported-scheme import must not abort the comparison
        // load, but it MUST be reported so the diff can surface the truncated right closure.
        Path root = temp.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ;
                    owl:imports <urn:example:missing> .
                """);
        DirectAccessPolicy.NetworkRule offline = new DirectAccessPolicy.NetworkRule(
                false, Set.of(), false, true);

        java.util.List<String> unresolved = new java.util.ArrayList<>();
        assertDoesNotThrow(() -> DiffTools.loadDocument(root.toString(), java.util.List.of(),
                unresolved, offline));
        assertTrue(unresolved.contains("urn:example:missing"), unresolved.toString());
    }

    @Test
    void refusedSiblingCatalogFailsAComparisonImportThatResolvesThroughAnotherChannel(
            @TempDir Path temp) throws Exception {
        String imported = "https://example.org/catalog-pinned";
        Path root = temp.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        Files.writeString(temp.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <nextCatalog catalog="https://network-must-not-be-used.invalid/catalog.xml"/>
                </catalog>
                """);
        Path mapped = temp.resolve("workspace-imported.ttl");
        Files.writeString(mapped, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <%s> a owl:Ontology .
                """.formatted(imported));
        DirectAccessPolicy.NetworkRule offline = new DirectAccessPolicy.NetworkRule(
                false, Set.of(), false, true);

        ToolArgException denied = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class, () -> DiffTools.loadDocument(root.toString(),
                        java.util.List.of(new OntologyDocumentTools.ImportMapping(
                                IRI.create(imported), IRI.create(mapped.toUri()))),
                        new java.util.ArrayList<>(), offline));
        assertTrue(denied.getMessage().contains("had to resolve without the folder catalog"),
                denied.getMessage());
    }

    @Test
    void forbiddenLocalImportsFailBeforeOutsideProjectDereference(@TempDir Path temp)
            throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = temp.resolve("outside.ttl");
        Files.writeString(outside, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/outside> a owl:Ontology .
                """);
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ;
                    owl:imports <%s> .
                """.formatted(outside.toUri()));
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("local-import", "https://example.org/root")
                        + "filesystem:\n  allow_external_paths: false\n"
                        + "imports:\n  network: deny\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                new AuthenticatedPrincipal(1, "test", "test", "Test",
                        Set.of(DirectAccessPolicy.PROJECT_READ), null));
        ToolArgException denied = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class, () -> DiffTools.loadDocument(root.toString(),
                        java.util.List.of(), new java.util.ArrayList<>(),
                        rules.importNetworkRule()));

        assertTrue(denied.getMessage().contains("local import path is outside"),
                denied.getMessage());
        assertTrue(denied.getMessage().contains("outside project_root"), denied.getMessage());
    }

    @Test
    void siblingCatalogCannotMapAnImportOutsideTheProject(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = temp.resolve("outside.ttl");
        Files.writeString(outside, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/catalog-outside> a owl:Ontology .
                """);
        String imported = "https://example.org/catalog-alias";
        Path root = project.resolve("root.ttl");
        Files.writeString(root, """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <https://example.org/root> a owl:Ontology ; owl:imports <%s> .
                """.formatted(imported));
        Files.writeString(project.resolve("catalog-v001.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <catalog xmlns="urn:oasis:names:tc:entity:xmlns:xml:catalog">
                  <uri name="%s" uri="../outside.ttl"/>
                </catalog>
                """.formatted(imported));
        Path policyPath = project.resolve(".protege-mcp/project.yaml");
        ProjectPolicyFixtures.writePolicy(policyPath,
                ProjectPolicyFixtures.minimalPolicy("catalog-import", "https://example.org/root")
                        + "filesystem:\n  allow_external_paths: false\n"
                        + "imports:\n  network: deny\n"
                        + "validation:\n  required_stages: [structural]\n");
        ProjectPolicy policy = ProjectPolicyLoader.load(policyPath, null);
        DirectAccessPolicy.Rules rules = new DirectAccessPolicy.Rules(policy,
                new AuthenticatedPrincipal(1, "test", "test", "Test",
                        Set.of(DirectAccessPolicy.PROJECT_READ), null));
        IRI mapped = new org.protege.xmlcatalog.owlapi.XMLCatalogIRIMapper(
                project.resolve("catalog-v001.xml").toFile()).getDocumentIRI(IRI.create(imported));
        assertTrue(mapped != null, "the adversarial catalog entry must resolve in the real mapper");
        assertTrue(rules.importNetworkRule().fileImportDenial(mapped.toURI()) != null,
                "the resolved catalog target must be outside project_root");

        ToolArgException denied = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class, () -> DiffTools.loadDocument(root.toString(),
                        java.util.List.of(), new java.util.ArrayList<>(),
                        rules.importNetworkRule()));

        assertTrue(denied.getMessage().contains("mapping target"), denied.getMessage());
        assertTrue(denied.getMessage().contains("outside project_root"), denied.getMessage());
        assertTrue(denied.getMessage().contains(imported), denied.getMessage());
    }
}
