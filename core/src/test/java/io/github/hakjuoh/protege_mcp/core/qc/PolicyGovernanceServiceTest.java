package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class PolicyGovernanceServiceTest {

    private static final IRI TERM = IRI.create("https://example.org/Term");
    private static final IRI REPLACEMENT = IRI.create("https://example.org/Replacement");
    private static final IRI LABEL = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
    private static final IRI DEFINITION = IRI.create("http://www.w3.org/2004/02/skos/core#definition");
    private static final IRI STATUS = IRI.create("https://example.org/status");
    private static final IRI REPLACED_BY = IRI.create("https://example.org/replacedBy");

    @Test
    void detectsLanguageDefinitionAndLifecycleFailuresWithStableAttribution() throws Exception {
        Fixture fixture = fixture();
        OWLClass term = fixture.data.getOWLClass(TERM);
        fixture.manager.addAxiom(fixture.ontology, fixture.data.getOWLDeclarationAxiom(term));
        add(fixture, term, LABEL, fixture.data.getOWLLiteral("One", "en"));
        add(fixture, term, LABEL, fixture.data.getOWLLiteral("Two", "en"));
        add(fixture, term, DEFINITION, fixture.data.getOWLLiteral("TODO"));
        add(fixture, term, STATUS, fixture.data.getOWLLiteral("retired"));
        add(fixture, term, REPLACED_BY, IRI.create("https://example.org/Missing"));

        List<Map<String, Object>> checks = PolicyGovernanceService.checks(
                fixture.ontology, Set.of(fixture.ontology), rules(List.of()),
                LocalDate.of(2026, 7, 13), 25);

        assertEquals(1, count(checks, "annotation.label.language"));
        assertEquals(1, count(checks, "annotation.label.cardinality"));
        assertEquals(1, count(checks, "annotation.definition.placeholder"));
        assertEquals(1, count(checks, "lifecycle.status.allowed"));
        assertEquals(1, count(checks, "lifecycle.replacement.dangling"));
        Map<String, Object> dangling = check(checks, "lifecycle.replacement.dangling");
        assertTrue(String.valueOf(dangling.get("identity_digest")).startsWith("sha256:"));
        assertEquals(1, ((List<?>) dangling.get(PolicyGovernanceService.ATTRIBUTION_KEY)).size());
    }

    @Test
    void appliesScopedWaiversAndTreatsExpiryAsInclusive() throws Exception {
        Fixture fixture = fixture();
        OWLClass term = fixture.data.getOWLClass(TERM);
        OWLClass replacement = fixture.data.getOWLClass(REPLACEMENT);
        fixture.manager.addAxiom(fixture.ontology, fixture.data.getOWLDeclarationAxiom(term));
        fixture.manager.addAxiom(fixture.ontology, fixture.data.getOWLDeclarationAxiom(replacement));
        LocalDate today = LocalDate.of(2026, 7, 15);
        List<PolicyGovernanceService.Waiver> waivers = List.of(
                new PolicyGovernanceService.Waiver("annotation.definition.required", TERM.toString(),
                        "migration window", "team", today),
                new PolicyGovernanceService.Waiver("annotation.label.language", null,
                        "expired", "team", today.minusDays(1)));

        List<Map<String, Object>> checks = PolicyGovernanceService.checks(
                fixture.ontology, Set.of(fixture.ontology), rules(waivers), today, 25);

        Map<String, Object> definitions = check(checks, "annotation.definition.required");
        assertEquals(1, definitions.get("count"));
        assertEquals(1, definitions.get("waived_count"));
        assertEquals(1, count(checks, "validation.waiver.expired"));
    }

    @Test
    void rejectsMismatchedSnapshotsButPreservesLegacySampleBounds() throws Exception {
        Fixture fixture = fixture();
        OWLOntology other = fixture.manager.createOntology(IRI.create("https://example.org/other"));
        OWLClass term = fixture.data.getOWLClass(TERM);
        fixture.manager.addAxiom(fixture.ontology, fixture.data.getOWLDeclarationAxiom(term));

        assertThrows(IllegalArgumentException.class, () -> PolicyGovernanceService.checks(
                fixture.ontology, Set.of(other), rules(List.of()), LocalDate.now(), 25));
        List<Map<String, Object>> negative = PolicyGovernanceService.checks(
                fixture.ontology, Set.of(fixture.ontology), rules(List.of()), LocalDate.now(), -1);
        assertEquals(List.of(), check(negative, "annotation.label.language").get("examples"));
        List<Map<String, Object>> large = PolicyGovernanceService.checks(
                fixture.ontology, Set.of(fixture.ontology), rules(List.of()), LocalDate.now(), 20_000);
        assertEquals(1, count(large, "annotation.label.language"));
    }

    private static PolicyGovernanceService.Rules rules(
            List<PolicyGovernanceService.Waiver> waivers) {
        return new PolicyGovernanceService.Rules(List.of(LABEL), Set.of("en", "fr"), true,
                List.of(DEFINITION), true, Set.of("en"), STATUS,
                Set.of("active", "deprecated"), Set.of("deprecated"),
                List.of(REPLACED_BY), true, waivers);
    }

    private static void add(Fixture fixture, OWLClass subject, IRI property,
            OWLAnnotationValue value) {
        fixture.manager.addAxiom(fixture.ontology,
                fixture.data.getOWLAnnotationAssertionAxiom(
                        fixture.data.getOWLAnnotationProperty(property), subject.getIRI(), value));
    }

    private static int count(List<Map<String, Object>> checks, String id) {
        return ((Number) check(checks, id).get("count")).intValue();
    }

    private static Map<String, Object> check(List<Map<String, Object>> checks, String id) {
        return checks.stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow();
    }

    private static Fixture fixture() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return new Fixture(manager, manager.createOntology(IRI.create("https://example.org/o")),
                manager.getOWLDataFactory());
    }

    private record Fixture(OWLOntologyManager manager, OWLOntology ontology,
            OWLDataFactory data) { }
}
