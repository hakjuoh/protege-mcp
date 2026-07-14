package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class PolicyGovernanceTest {

    private static final IRI TERM = IRI.create("https://example.org/Term");
    private static final IRI REPLACEMENT = IRI.create("https://example.org/Replacement");
    private static final IRI LABEL = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
    private static final IRI DEFINITION = IRI.create("http://www.w3.org/2004/02/skos/core#definition");
    private static final IRI STATUS = IRI.create("https://example.org/status");
    private static final IRI REPLACED_BY = IRI.create("https://example.org/replacedBy");

    @Test
    void detectsLanguageCardinalityPlaceholderDatatypeAndLifecycleFailures() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/o"));
        OWLClass term = df.getOWLClass(TERM);
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(term));
        add(manager, ontology, df, term, LABEL, df.getOWLLiteral("One", "en"));
        add(manager, ontology, df, term, LABEL, df.getOWLLiteral("Two", "en"));
        add(manager, ontology, df, term, DEFINITION, df.getOWLLiteral("TODO"));
        add(manager, ontology, df, term, STATUS, df.getOWLLiteral("retired"));
        add(manager, ontology, df, term, REPLACED_BY, IRI.create("https://example.org/Missing"));

        List<Map<String, Object>> checks = PolicyGovernance.checks(ontology, Set.of(ontology),
                rules(List.of()), LocalDate.of(2026, 7, 13), 25);

        assertEquals(1, count(checks, "annotation.label.language"));
        assertEquals(1, count(checks, "annotation.label.cardinality"));
        assertEquals(1, count(checks, "annotation.definition.placeholder"));
        assertEquals(1, count(checks, "lifecycle.status.allowed"));
        assertEquals(1, count(checks, "lifecycle.replacement.dangling"));
    }

    @Test
    void activeFocusWaiverSuppressesAndRemainsVisibleWhileExpiredWaiverIsFinding() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/o"));
        OWLClass term = df.getOWLClass(TERM);
        OWLClass replacement = df.getOWLClass(REPLACEMENT);
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(term));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(replacement));
        add(manager, ontology, df, term, STATUS, df.getOWLLiteral("deprecated"));
        List<PolicyGovernance.Waiver> waivers = List.of(
                new PolicyGovernance.Waiver("annotation.definition.required", TERM.toString(),
                        "migration window", "ontology-team", LocalDate.of(2026, 8, 1)),
                new PolicyGovernance.Waiver("annotation.label.language", null,
                        "old waiver", "ontology-team", LocalDate.of(2026, 1, 1)));

        List<Map<String, Object>> checks = PolicyGovernance.checks(ontology, Set.of(ontology),
                rules(waivers), LocalDate.of(2026, 7, 13), 25);
        Map<String, Object> required = check(checks, "annotation.definition.required");
        assertEquals(1, required.get("count")); // replacement still has no definition
        assertEquals(1, required.get("waived_count"));
        assertTrue(required.containsKey("waivers"));
        assertEquals(1, count(checks, "validation.waiver.expired"));
    }

    private static PolicyGovernance.Rules rules(List<PolicyGovernance.Waiver> waivers) {
        return new PolicyGovernance.Rules(List.of(LABEL), Set.of("en", "fr"), true,
                List.of(DEFINITION), true, Set.of("en"), STATUS,
                Set.of("active", "deprecated"), Set.of("deprecated"), List.of(REPLACED_BY), true, waivers);
    }

    private static void add(OWLOntologyManager manager, OWLOntology ontology, OWLDataFactory df,
            OWLClass subject, IRI property, org.semanticweb.owlapi.model.OWLAnnotationValue value) {
        OWLAnnotationProperty p = df.getOWLAnnotationProperty(property);
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(p, subject.getIRI(), value));
    }

    private static int count(List<Map<String, Object>> checks, String id) {
        return ((Number) check(checks, id).get("count")).intValue();
    }

    private static Map<String, Object> check(List<Map<String, Object>> checks, String id) {
        return checks.stream().filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow();
    }
}
