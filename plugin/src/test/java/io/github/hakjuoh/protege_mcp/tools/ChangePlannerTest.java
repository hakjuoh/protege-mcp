package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;

class ChangePlannerTest {

    @Test
    void planningNeverMutatesLiveOntologyAndInjectsDeclarations() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/change"));
        var df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/change#Child"))));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/change#Parent"))));
        int before = ontology.getAxiomCount();
        Map<String, Object> add = subclass("http://example.org/change#Child",
                "http://example.org/change#Parent", "add");

        ChangePlanner.Plan plan = ChangePlanner.plan(FakeModelManager.over(ontology), List.of(add), false);

        assertEquals(before, ontology.getAxiomCount(), "a preview planner must not touch the live ontology");
        assertTrue(plan.committable(), plan.errors().toString());
        assertEquals(1, plan.changes().size());
        assertTrue(plan.changes().stream().allMatch(c -> c.operation() == NormalizedChange.Operation.ADD));
    }

    @Test
    void verifiedApplyPlanningIsNotBoundByThePreviewCap() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/cap"));
        var df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/cap#A"))));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/cap#B"))));
        Map<String, Object> add = subclass("http://example.org/cap#A",
                "http://example.org/cap#B", "add");
        List<Map<String, Object>> operations = new java.util.ArrayList<>();
        for (int i = 0; i <= ChangePlanner.MAX_OPERATIONS; i++) {
            operations.add(add);
        }

        // apply_changes accepted arbitrarily sized batches before the 0.6.1 verify migration; the
        // cap protects only retained preview entries.
        ChangePlanner.Plan plan = ChangePlanner.planForApply(FakeModelManager.over(ontology),
                operations, false);
        assertTrue(plan.committable(), plan.errors().toString());

        ToolArgException capped = org.junit.jupiter.api.Assertions.assertThrows(
                ToolArgException.class,
                () -> ChangePlanner.plan(FakeModelManager.over(ontology), operations, false));
        assertTrue(capped.getMessage().contains("maximum"), capped.getMessage());
    }

    @Test
    void addThenRemoveCancelsToAnEmptyExactDelta() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/cancel"));
        var df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/cancel#A"))));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                df.getOWLClass(IRI.create("http://example.org/cancel#B"))));
        int before = ontology.getAxiomCount();
        Map<String, Object> add = subclass("http://example.org/cancel#A",
                "http://example.org/cancel#B", "add");
        Map<String, Object> remove = subclass("http://example.org/cancel#A",
                "http://example.org/cancel#B", "remove");

        ChangePlanner.Plan plan = ChangePlanner.plan(FakeModelManager.over(ontology),
                List.of(add, remove), false);

        assertTrue(plan.committable(), plan.errors().toString());
        assertTrue(plan.changes().isEmpty());
        assertEquals(before, ontology.getAxiomCount());
    }

    @Test
    void oneMalformedOperationMakesTheWholePlanNonCommittable() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/error"));
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("op", "explode");
        bad.put("axiom_type", "subclass_of");

        ChangePlanner.Plan plan = ChangePlanner.plan(FakeModelManager.over(ontology), List.of(bad), false);

        assertFalse(plan.committable());
        assertEquals(1, plan.errors().size());
        assertEquals(0, ontology.getAxiomCount());
    }

    @Test
    void applyCompatibilityPlanIncludesDeclarationsForValidOperationsBesideAnError()
            throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/partial"));
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("op", "explode");
        bad.put("axiom_type", "subclass_of");
        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("op", "add");
        valid.put("axiom_type", "annotation_assertion");
        valid.put("subject", "http://example.org/partial#A");
        valid.put("property", "http://example.org/partial#note");
        valid.put("value", "valid beside an error");

        ChangePlanner.Plan preview = ChangePlanner.plan(FakeModelManager.over(ontology),
                List.of(bad, valid), false);
        ChangePlanner.Plan apply = ChangePlanner.planForApply(FakeModelManager.over(ontology),
                List.of(bad, valid), false);

        assertFalse(apply.errors().isEmpty());
        assertEquals(1, preview.changes().size(), "an uncommittable preview keeps its old shape");
        assertEquals(2, apply.changes().size(), apply.errors()::toString);
        assertEquals(0, ontology.getAxiomCount(), "both planners are isolated from the live model");
    }

    @Test
    void strictModeRejectsMintedOperandsWithoutCachingAnApplicableDelta() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.createOntology(IRI.create("http://example.org/strict"));
        ChangePlanner.Plan plan = ChangePlanner.plan(FakeModelManager.over(ontology), List.of(
                subclass("http://example.org/strict#A", "http://example.org/strict#B", "add")), true);
        assertFalse(plan.committable());
        assertFalse(plan.errors().isEmpty());
        assertEquals(0, ontology.getAxiomCount());
    }

    private static Map<String, Object> subclass(String sub, String sup, String op) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("op", op);
        row.put("axiom_type", "subclass_of");
        row.put("sub", sub);
        row.put("super", sup);
        return row;
    }
}
