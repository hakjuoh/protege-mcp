package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

class StructuralQcServiceTest {

    @Test
    void appliesDisabledRulesSeverityOverridesAndInfoSurfacing() throws Exception {
        Fixture fixture = fixture();
        var unlabeled = fixture.data.getOWLClass(IRI.create("https://example.org/test#Unlabeled"));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(unlabeled));

        StructuralQcService.Result warning = StructuralQcService.evaluate(
                fixture.root, Set.of(fixture.root), Set.of(), Map.of(), true);
        StructuralQcService.Result error = StructuralQcService.evaluate(
                fixture.root, Set.of(fixture.root), Set.of(),
                Map.of("missing_label", "error"), true);
        StructuralQcService.Result disabled = StructuralQcService.evaluate(
                fixture.root, Set.of(fixture.root), Set.of("missing_label"), Map.of(), false);

        assertEquals(QcStageVerdict.WARNING, warning.execution().verdict());
        assertEquals(QcStageVerdict.FAIL, error.execution().verdict());
        assertEquals(QcStageVerdict.PASS, disabled.execution().verdict());
        assertTrue(warning.gatingIdentities().stream()
                .anyMatch(value -> value.startsWith("missing_label|")));
    }

    @Test
    void findsCyclesAndProducesDeterministicIdentityEvidence() throws Exception {
        Fixture fixture = fixture();
        var a = fixture.data.getOWLClass(IRI.create("https://example.org/test#A"));
        var b = fixture.data.getOWLClass(IRI.create("https://example.org/test#B"));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(a));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(b));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLSubClassOfAxiom(a, b));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLSubClassOfAxiom(b, a));
        label(fixture, a, "A");
        label(fixture, b, "B");

        StructuralQcService.Result result = StructuralQcService.evaluate(
                fixture.root, Set.of(fixture.root), Set.of(), Map.of(), true);

        assertEquals(QcStageVerdict.WARNING, result.execution().verdict());
        Map<String, Object> cycle = checks(result).stream()
                .filter(row -> "subclass_cycle".equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals(2, cycle.get("count"));
        assertTrue(String.valueOf(cycle.get("identity_digest")).startsWith("sha256:"));
    }

    @Test
    void doesNotDemandLabelsFromEntitiesOwnedOnlyByImports() throws Exception {
        Fixture fixture = fixture();
        IRI importIri = IRI.create("https://example.org/upstream");
        OWLOntology imported = fixture.manager.createOntology(importIri);
        var upstream = fixture.data.getOWLClass(IRI.create(importIri + "#Upstream"));
        var local = fixture.data.getOWLClass(IRI.create("https://example.org/test#Local"));
        fixture.manager.addAxiom(imported, fixture.data.getOWLDeclarationAxiom(upstream));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(local));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLSubClassOfAxiom(local, upstream));
        label(fixture, local, "Local");
        fixture.manager.applyChange(new AddImport(fixture.root,
                fixture.data.getOWLImportsDeclaration(importIri)));

        StructuralQcService.Result result = StructuralQcService.evaluate(
                fixture.root, Set.of(fixture.root, imported), Set.of(), Map.of(), true);

        Map<String, Object> missing = checks(result).stream()
                .filter(row -> "missing_label".equals(row.get("id"))).findFirst().orElse(null);
        assertNull(missing);
    }

    @Test
    void recognizesBooleanDeprecatedAssertionsThroughTheSharedImpactRule() throws Exception {
        Fixture fixture = fixture();
        var old = fixture.data.getOWLClass(IRI.create("https://example.org/test#Old"));
        var current = fixture.data.getOWLClass(IRI.create("https://example.org/test#Current"));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(old));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLDeclarationAxiom(current));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLSubClassOfAxiom(current, old));
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLAnnotationAssertionAxiom(
                fixture.data.getOWLDeprecated(), old.getIRI(), fixture.data.getOWLLiteral(true)));

        StructuralQcService.Result result = StructuralQcService.evaluate(
                fixture.root, Set.of(fixture.root), Set.of(), Map.of(), true);

        Map<String, Object> deprecated = checks(result).stream()
                .filter(row -> "deprecated_in_use".equals(row.get("id"))).findFirst().orElseThrow();
        assertEquals(1, deprecated.get("count"));
        assertEquals(QcStageVerdict.WARNING, result.execution().verdict());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checks(StructuralQcService.Result result) {
        return (List<Map<String, Object>>) result.execution().details().get("checks");
    }

    private static void label(Fixture fixture, org.semanticweb.owlapi.model.OWLEntity entity,
            String value) {
        fixture.manager.addAxiom(fixture.root, fixture.data.getOWLAnnotationAssertionAxiom(
                fixture.data.getRDFSLabel(), entity.getIRI(), fixture.data.getOWLLiteral(value, "en")));
    }

    private static Fixture fixture() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/test"));
        return new Fixture(manager, root, manager.getOWLDataFactory());
    }

    private record Fixture(org.semanticweb.owlapi.model.OWLOntologyManager manager,
            OWLOntology root, org.semanticweb.owlapi.model.OWLDataFactory data) {
    }
}
