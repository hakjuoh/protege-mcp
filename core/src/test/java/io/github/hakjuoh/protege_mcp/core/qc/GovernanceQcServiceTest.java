package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class GovernanceQcServiceTest {

    @Test
    void evaluatesIriAnnotationsAndImportLayeringWithStableIdentities() throws Exception {
        Fixture fixture = fixture();
        OWLClass local = fixture.data.getOWLClass(IRI.create("https://wrong.example/Local"));
        OWLClass upstream = fixture.data.getOWLClass(IRI.create("https://upstream.example/Term"));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(local));
        fixture.manager.addAxiom(fixture.imported, fixture.data.getOWLDeclarationAxiom(upstream));
        fixture.manager.addAxiom(fixture.root,
                fixture.data.getOWLSubClassOfAxiom(upstream, local));

        GovernanceQcService.Result result = GovernanceQcService.evaluate(
                fixture.root, Set.of(fixture.root, fixture.imported),
                Pattern.compile("https://example\\.org/.*"), List.of("https://example.org/"),
                List.of("rdfs:label"), true, PolicyGovernanceService.Rules.empty(),
                LocalDate.of(2026, 7, 19), List.of(), 25,
                GovernanceQcService.Presentation.canonical());

        assertEquals(QcStageVerdict.WARNING, result.execution().verdict());
        assertEquals(1, count(result, "iri_policy"));
        assertEquals(1, count(result, "required_annotations"));
        assertEquals(1, count(result, "import_layering"));
        assertEquals(3, result.gatingIdentities().size());
        checks(result).forEach(row -> assertFalse(
                row.containsKey(PolicyGovernanceService.ATTRIBUTION_KEY)));
    }

    @Test
    void importOnlyEntitiesAreContextRatherThanLocallyOwnedTerms() throws Exception {
        Fixture fixture = fixture();
        OWLClass local = fixture.data.getOWLClass(IRI.create("https://example.org/Local"));
        OWLClass upstream = fixture.data.getOWLClass(IRI.create("https://upstream.example/Term"));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(local));
        fixture.manager.addAxiom(fixture.imported, fixture.data.getOWLDeclarationAxiom(upstream));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLSubClassOfAxiom(local, upstream));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLAnnotationAssertionAxiom(
                fixture.data.getRDFSLabel(), local.getIRI(), fixture.data.getOWLLiteral("Local")));

        GovernanceQcService.Result result = GovernanceQcService.evaluate(
                fixture.root, Set.of(fixture.root, fixture.imported), null,
                List.of("https://example.org/"), List.of("rdfs:label"), true,
                PolicyGovernanceService.Rules.empty(), LocalDate.of(2026, 7, 19),
                List.of(), 25, GovernanceQcService.Presentation.canonical());

        assertEquals(0, count(result, "iri_policy"));
        assertEquals(0, count(result, "required_annotations"));
        assertEquals(0, count(result, "import_layering"));
        assertEquals(QcStageVerdict.PASS, result.execution().verdict());
    }

    @Test
    void mergesProjectRowsWithoutMutatingTheirAttributionChannel() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", "module_owned_namespace");
        project.put("severity", "error");
        project.put("count", 1);
        project.put(PolicyGovernanceService.ATTRIBUTION_KEY, List.of("module|member"));

        GovernanceQcService.Result result = GovernanceQcService.evaluate(
                fixture.root, Set.of(fixture.root, fixture.imported), null, List.of(), List.of(),
                false, PolicyGovernanceService.Rules.empty(), LocalDate.of(2026, 7, 19),
                List.of(project), 25, GovernanceQcService.Presentation.canonical());

        assertEquals(QcStageVerdict.FAIL, result.execution().verdict());
        assertEquals(Set.of("module|member"), result.gatingIdentities());
        assertTrue(project.containsKey(PolicyGovernanceService.ATTRIBUTION_KEY));
        assertFalse(checks(result).get(0).containsKey(PolicyGovernanceService.ATTRIBUTION_KEY));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checks(GovernanceQcService.Result result) {
        return (List<Map<String, Object>>) result.execution().details().get("checks");
    }

    private static int count(GovernanceQcService.Result result, String id) {
        return ((Number) checks(result).stream().filter(row -> id.equals(row.get("id")))
                .findFirst().orElseThrow().get("count")).intValue();
    }

    private static Fixture fixture() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        IRI importedIri = IRI.create("https://upstream.example/ontology");
        OWLOntology imported = manager.createOntology(importedIri);
        manager.applyChange(new AddImport(root, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(importedIri)));
        return new Fixture(manager, root, imported, manager.getOWLDataFactory());
    }

    private record Fixture(OWLOntologyManager manager, OWLOntology root,
            OWLOntology imported, org.semanticweb.owlapi.model.OWLDataFactory data) { }
}
