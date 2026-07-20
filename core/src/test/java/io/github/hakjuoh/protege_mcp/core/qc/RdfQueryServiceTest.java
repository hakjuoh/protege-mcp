package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNode;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNode;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNode;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLObjectPropertyNode;
import org.semanticweb.owlapi.reasoner.impl.OWLObjectPropertyNodeSet;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

class RdfQueryServiceTest {

    @Test
    void executesSelectAndAskWithStableBindingShapes() throws Exception {
        OWLOntology ontology = ontology();
        var data = ontology.getOWLOntologyManager().getOWLDataFactory();
        var entity = data.getOWLClass(IRI.create("https://example.org/test#Thing"));
        ontology.getOWLOntologyManager().addAxiom(ontology, data.getOWLDeclarationAxiom(entity));
        ontology.getOWLOntologyManager().addAxiom(ontology,
                data.getOWLAnnotationAssertionAxiom(data.getRDFSLabel(), entity.getIRI(),
                        data.getOWLLiteral("Thing", "en")));

        Map<String, Object> select = RdfQueryService.execute(ontology,
                Map.of("ex:", "https://example.org/test#",
                        "rdfs:", "http://www.w3.org/2000/01/rdf-schema#"),
                "SELECT ?label WHERE { ex:Thing rdfs:label ?label }", 10, 10_000);
        Map<String, Object> ask = RdfQueryService.execute(ontology, Map.of(),
                "ASK { <https://example.org/test#Thing> a <http://www.w3.org/2002/07/owl#Class> }",
                10, 10_000);

        assertEquals("SELECT", select.get("query_type"));
        assertEquals(1, select.get("count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cell = (Map<String, Object>) ((Map<?, ?>)
                ((List<?>) select.get("bindings")).get(0)).get("label");
        assertEquals(Map.of("type", "literal", "value", "Thing", "lang", "en"), cell);
        assertEquals(Boolean.TRUE, ask.get("boolean"));
    }

    @Test
    void rejectsServiceAndRestoresTheCallingThreadClassLoader() throws Exception {
        OWLOntology ontology = ontology();
        ClassLoader marker = new ClassLoader() { };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(marker);
        try {
            RdfQueryService.QueryException error = assertThrows(
                    RdfQueryService.QueryException.class,
                    () -> RdfQueryService.execute(ontology, Map.of(),
                            "SELECT * WHERE { SERVICE <https://example.org/sparql> { ?s ?p ?o } }",
                            10, 10_000));
            assertTrue(error.getMessage().contains("SERVICE is not allowed"));
            assertSame(marker, thread.getContextClassLoader());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void capsSelectRowsWithoutEnumeratingTheRemainder() throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create("https://example.org/test#A"))));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(
                data.getOWLClass(IRI.create("https://example.org/test#B"))));

        Map<String, Object> result = RdfQueryService.execute(ontology, Map.of(),
                "SELECT ?c WHERE { ?c a <http://www.w3.org/2002/07/owl#Class> } ORDER BY ?c",
                1, 10_000);

        assertEquals(1, result.get("count"));
        assertEquals(Boolean.TRUE, result.get("truncated"));
    }

    @Test
    void snapshotPreservesTheRootIdentityClosureAndOntologyAnnotations() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        OWLOntology root = manager.createOntology(IRI.create("https://example.org/root"));
        IRI importIri = IRI.create("https://example.org/imported");
        OWLOntology imported = manager.createOntology(importIri);
        manager.applyChange(new AddImport(root, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(importIri)));
        manager.addAxiom(imported, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(IRI.create(importIri + "#Term"))));
        manager.applyChange(new AddOntologyAnnotation(imported,
                manager.getOWLDataFactory().getOWLAnnotation(
                        manager.getOWLDataFactory().getRDFSLabel(),
                        manager.getOWLDataFactory().getOWLLiteral("Imported"))));

        RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(root.getOntologyID(),
                Set.of(root, imported), Map.of("ex:", "https://example.org/"), null, false);

        assertEquals(root.getOntologyID(), snapshot.ontology().getOntologyID());
        assertEquals(1, snapshot.ontology().getAnnotations().size());
        assertTrue(snapshot.ontology().containsClassInSignature(IRI.create(importIri + "#Term")));
        assertEquals(Map.of("ex:", "https://example.org/"), snapshot.prefixes());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.prefixes().put("bad:", "https://bad.example/"));
    }

    @Test
    void malformedQueriesFailWithoutMutatingTheOntology() throws Exception {
        OWLOntology ontology = ontology();
        int before = ontology.getAxiomCount();

        assertThrows(RdfQueryService.QueryException.class,
                () -> RdfQueryService.execute(ontology, Map.of(), "SELECT WHERE {", 10, 10_000));

        assertEquals(before, ontology.getAxiomCount());
        assertFalse(RdfQueryService.withPrefixes("ASK {}", Map.of()).startsWith("PREFIX"));
    }

    @Test
    void materializationPreflightKeepsEveryCategoryForBoundedGraphs() {
        RdfQueryService.MaterializationPlan plan = RdfQueryService.materializationPlan(
                100, 100, 10, 10);
        RdfQueryService.MaterializationPlan terminology = RdfQueryService.materializationPlan(
                50_000, 150, 100, 0);
        RdfQueryService.MaterializationPlan sparseAbox = RdfQueryService.materializationPlan(
                10, 1_000, 11, 0);

        assertTrue(plan.classHierarchy());
        assertTrue(plan.classAssertions());
        assertTrue(plan.propertyHierarchy());
        assertTrue(plan.propertyAssertions());
        assertEquals(null, plan.note());
        assertTrue(terminology.classHierarchy(),
                "direct hierarchy query work must not be estimated as the class Cartesian product");
        assertTrue(terminology.propertyAssertions(),
                "graphs admitted by the 0.7.0 individual/property query bound stay admitted");
        assertTrue(sparseAbox.propertyAssertions(),
                "ordinary ABoxes must not be rejected by a theoretical n²·p result estimate; the "
                        + "actual result count is bounded during materialization instead");
    }

    @Test
    void materializationPreflightBoundsEachInferenceCategoryIndependently() {
        RdfQueryService.MaterializationPlan classHierarchy = RdfQueryService.materializationPlan(
                125_001, 1, 1, 0);
        RdfQueryService.MaterializationPlan classAssertions = RdfQueryService.materializationPlan(
                1, 250_001, 0, 0);
        RdfQueryService.MaterializationPlan propertyHierarchy = RdfQueryService.materializationPlan(
                1, 0, 250_001, 0);
        RdfQueryService.MaterializationPlan propertyQueries = RdfQueryService.materializationPlan(
                1, 2_000, 200, 0);

        assertFalse(classHierarchy.classHierarchy());
        assertTrue(classHierarchy.classAssertions());
        assertFalse(classAssertions.classAssertions());
        assertTrue(classAssertions.classHierarchy());
        assertFalse(propertyHierarchy.propertyHierarchy());
        assertFalse(propertyQueries.propertyAssertions());
        assertTrue(propertyQueries.note().contains("query estimate 400000"));
    }

    @Test
    void materializationPreflightSaturatesHostileCardinalitiesWithoutOverflow() {
        RdfQueryService.MaterializationPlan plan = RdfQueryService.materializationPlan(
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

        assertFalse(plan.classHierarchy());
        assertFalse(plan.classAssertions());
        assertFalse(plan.propertyHierarchy());
        assertFalse(plan.propertyAssertions());
        assertTrue(plan.note().contains(">=" + Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> RdfQueryService.materializationPlan(-1, 0, 0, 0));
    }

    @Test
    void inferredSnapshotReportsCategoriesSkippedByTheMemoryPreflight() throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        for (int i = 0; i < 2_600; i++) {
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(data.getOWLNamedIndividual(
                    IRI.create("https://example.org/test#i" + i))));
        }
        for (int i = 0; i < 100; i++) {
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(data.getOWLObjectProperty(
                    IRI.create("https://example.org/test#p" + i))));
        }
        var reasoner = new StructuralReasonerFactory().createReasoner(ontology);
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true);

            assertTrue(snapshot.note().contains("property assertions (query estimate 260000)"));
            assertTrue(snapshot.note().contains("Targeted reasoner tools remain available"));
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void inferredSnapshotMaterializesInferredOnlyPropertyAssertionsForOrdinaryAboxes()
            throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        var knows = data.getOWLObjectProperty(IRI.create("https://example.org/test#knows"));
        var alice = data.getOWLNamedIndividual(IRI.create("https://example.org/test#alice"));
        var bob = data.getOWLNamedIndividual(IRI.create("https://example.org/test#bob"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(knows));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(alice));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(bob));
        assertFalse(ontology.containsAxiom(
                data.getOWLObjectPropertyAssertionAxiom(knows, alice, bob)),
                "the pinned assertion must be inferred-only, never asserted");
        var reasoner = inferringOneValue(new StructuralReasonerFactory().createReasoner(ontology),
                alice, knows, bob);
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true);

            assertEquals(null, snapshot.note());
            Map<String, Object> ask = RdfQueryService.execute(snapshot.ontology(),
                    snapshot.prefixes(),
                    "ASK { <https://example.org/test#alice> <https://example.org/test#knows> "
                            + "<https://example.org/test#bob> }", 10, 10_000);
            assertEquals(Boolean.TRUE, ask.get("boolean"),
                    "an inferred-only property assertion must land in the snapshot");
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void inferredSnapshotContainsAReasonerThatCannotEnumeratePropertyValues() throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        var thing = data.getOWLClass(IRI.create("https://example.org/test#Thing"));
        var subThing = data.getOWLClass(IRI.create("https://example.org/test#SubThing"));
        var knows = data.getOWLObjectProperty(IRI.create("https://example.org/test#knows"));
        var alice = data.getOWLNamedIndividual(IRI.create("https://example.org/test#alice"));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(subThing, thing));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(subThing, alice));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(knows));
        assertFalse(ontology.containsAxiom(data.getOWLClassAssertionAxiom(thing, alice)),
                "the surviving-category witness must be inferred-only, never asserted");
        var reasoner = refusingPropertyValues(
                new StructuralReasonerFactory().createReasoner(ontology));
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true);

            assertTrue(snapshot.note().contains("property assertions"),
                    "an ELK-style unsupported query drops only its own category, with a note");
            assertTrue(snapshot.note().contains("UnsupportedOperationException"));
            Map<String, Object> ask = RdfQueryService.execute(snapshot.ontology(),
                    snapshot.prefixes(),
                    "ASK { <https://example.org/test#alice> a <https://example.org/test#Thing> }",
                    10, 10_000);
            assertEquals(Boolean.TRUE, ask.get("boolean"),
                    "the other inferred categories must survive the contained failure");
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void inferredSnapshotReportsTheTruncationNoteWhenTheActualCountExceedsTheBudget()
            throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        var knows = data.getOWLObjectProperty(IRI.create("https://example.org/test#knows"));
        for (int i = 0; i < 4; i++) {
            var subject = data.getOWLNamedIndividual(IRI.create("https://example.org/test#s" + i));
            var object = data.getOWLNamedIndividual(IRI.create("https://example.org/test#o" + i));
            manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(
                    knows, subject, object));
        }
        var reasoner = new StructuralReasonerFactory().createReasoner(ontology);
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true, 2);

            assertTrue(snapshot.note().contains(
                    "property assertions (more than 2 inferred assertions)"),
                    "the truncation must reach Snapshot.note() so QC caveats can gate on it");
            assertTrue(snapshot.note().contains("Targeted reasoner tools remain available"));
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void actualResultBudgetsDropEveryHighFanoutCategoryWithoutPartialAxioms()
            throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        OWLClass a = data.getOWLClass(IRI.create("https://example.org/test#A"));
        OWLClass b = data.getOWLClass(IRI.create("https://example.org/test#B"));
        OWLClass c = data.getOWLClass(IRI.create("https://example.org/test#C"));
        OWLClass d = data.getOWLClass(IRI.create("https://example.org/test#D"));
        OWLNamedIndividual alice = data.getOWLNamedIndividual(
                IRI.create("https://example.org/test#alice"));
        OWLDataProperty p = data.getOWLDataProperty(IRI.create("https://example.org/test#p"));
        OWLDataProperty q = data.getOWLDataProperty(IRI.create("https://example.org/test#q"));
        OWLDataProperty r = data.getOWLDataProperty(IRI.create("https://example.org/test#r"));
        OWLDataProperty s = data.getOWLDataProperty(IRI.create("https://example.org/test#s"));
        for (var entity : List.of(a, b, c, d, alice, p, q, r, s)) {
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(entity));
        }
        OWLReasoner delegate = new StructuralReasonerFactory().createReasoner(ontology);
        OWLReasoner reasoner = highFanout(delegate, a, Set.of(b, c, d), alice,
                Set.of(a, b, c), p, Set.of(q, r, s));
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true,
                    new RdfQueryService.MaterializationBudgets(2, 2, 2, 100));

            assertTrue(snapshot.note().contains(
                    "subclass/equivalent-class hierarchy (more than 2 inferred axioms)"),
                    snapshot.note());
            assertTrue(snapshot.note().contains(
                    "class assertions (more than 2 inferred axioms)"), snapshot.note());
            assertTrue(snapshot.note().contains(
                    "property hierarchy (more than 2 inferred axioms)"), snapshot.note());
            assertFalse(snapshot.ontology().containsAxiom(data.getOWLSubClassOfAxiom(a, b)),
                    "an over-budget category must be dropped atomically");
            assertFalse(snapshot.ontology().containsAxiom(
                    data.getOWLClassAssertionAxiom(a, alice)),
                    "an over-budget category must not retain its first results");
            assertFalse(snapshot.ontology().containsAxiom(
                    data.getOWLSubDataPropertyOfAxiom(p, q)),
                    "property hierarchy truncation must not leave a partial hierarchy");
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void anEarlierCategoryFailureDoesNotPreventLaterInferenceCategories()
            throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        OWLClass thing = data.getOWLClass(IRI.create("https://example.org/test#Thing"));
        OWLClass subThing = data.getOWLClass(IRI.create("https://example.org/test#SubThing"));
        OWLNamedIndividual alice = data.getOWLNamedIndividual(
                IRI.create("https://example.org/test#alice"));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(subThing, thing));
        manager.addAxiom(ontology, data.getOWLClassAssertionAxiom(subThing, alice));
        assertFalse(ontology.containsAxiom(data.getOWLClassAssertionAxiom(thing, alice)),
                "the later-category witness must be inferred-only");
        OWLReasoner delegate = new StructuralReasonerFactory().createReasoner(ontology);
        OWLReasoner reasoner = (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                OWLReasoner.class.getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, arguments) -> {
                    if ("isSatisfiable".equals(method.getName())) {
                        throw new UnsupportedOperationException("class hierarchy refused");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true);

            assertTrue(snapshot.note().contains("subclass/equivalent-class hierarchy"),
                    snapshot.note());
            assertTrue(snapshot.note().contains("UnsupportedOperationException"), snapshot.note());
            assertTrue(snapshot.ontology().containsAxiom(
                    data.getOWLClassAssertionAxiom(thing, alice)),
                    "an earlier category failure must not prevent a later category from completing");
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void anUnanswerableGeneratorKeepsItsSiblingGeneratorsAnsweredAxioms() throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        var op = data.getOWLObjectProperty(IRI.create("https://example.org/test#op"));
        var sup = data.getOWLObjectProperty(IRI.create("https://example.org/test#sup"));
        var dp = data.getOWLDataProperty(IRI.create("https://example.org/test#dp"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(op));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(dp));
        assertFalse(ontology.containsAxiom(data.getOWLSubObjectPropertyOfAxiom(op, sup)),
                "the surviving witness must be inferred-only, never asserted");
        OWLReasoner delegate = new StructuralReasonerFactory().createReasoner(ontology);
        // ELK-style split: object-property hierarchy answered, data-property hierarchy refused.
        OWLReasoner reasoner = propertyHierarchyStub(delegate, op, Set.of(sup), null, Set.of());
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true);

            assertTrue(snapshot.ontology().containsAxiom(
                    data.getOWLSubObjectPropertyOfAxiom(op, sup)),
                    "axioms the reasoner answered must survive an unanswerable sibling generator, "
                            + "exactly as 0.7.0's per-generator containment kept them");
            assertTrue(snapshot.note().contains("property hierarchy"), snapshot.note());
            assertTrue(snapshot.note().contains("Sub data properties"), snapshot.note());
            assertTrue(snapshot.note().contains("UnsupportedOperationException"), snapshot.note());
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void aBudgetOverflowInAnyGeneratorDropsTheWholeCategoryAtomically() throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        var op = data.getOWLObjectProperty(IRI.create("https://example.org/test#op"));
        var sup = data.getOWLObjectProperty(IRI.create("https://example.org/test#sup"));
        var dp = data.getOWLDataProperty(IRI.create("https://example.org/test#dp"));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(op));
        manager.addAxiom(ontology, data.getOWLDeclarationAxiom(dp));
        OWLReasoner delegate = new StructuralReasonerFactory().createReasoner(ontology);
        // The object half answers one super under budget; the data half's fanout then overflows
        // the shared category budget.
        OWLReasoner reasoner = propertyHierarchyStub(delegate, op, Set.of(sup), dp, Set.of(
                data.getOWLDataProperty(IRI.create("https://example.org/test#d1")),
                data.getOWLDataProperty(IRI.create("https://example.org/test#d2")),
                data.getOWLDataProperty(IRI.create("https://example.org/test#d3")),
                data.getOWLDataProperty(IRI.create("https://example.org/test#d4")),
                data.getOWLDataProperty(IRI.create("https://example.org/test#d5"))));
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true,
                    new RdfQueryService.MaterializationBudgets(10, 10, 3, 100));

            assertFalse(snapshot.ontology().containsAxiom(
                    data.getOWLSubObjectPropertyOfAxiom(op, sup)),
                    "a category that exceeds its result budget must be dropped atomically, "
                            + "including axioms its first generator already answered");
            assertTrue(snapshot.note().contains(
                    "property hierarchy (more than 3 inferred axioms)"), snapshot.note());
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void aFailedGeneratorDoesNotSpendTheSuccessfulSiblingsResultBudget() throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        var op1 = data.getOWLObjectProperty(IRI.create("https://example.org/test#op1"));
        var op2 = data.getOWLObjectProperty(IRI.create("https://example.org/test#op2"));
        var objectSuper = data.getOWLObjectProperty(
                IRI.create("https://example.org/test#object-super"));
        var dp = data.getOWLDataProperty(IRI.create("https://example.org/test#dp"));
        var d1 = data.getOWLDataProperty(IRI.create("https://example.org/test#d1"));
        var d2 = data.getOWLDataProperty(IRI.create("https://example.org/test#d2"));
        for (var entity : List.of(op1, op2, dp)) {
            manager.addAxiom(ontology, data.getOWLDeclarationAxiom(entity));
        }
        OWLReasoner delegate = new StructuralReasonerFactory().createReasoner(ontology);
        var objectQueries = new java.util.concurrent.atomic.AtomicInteger();
        OWLReasoner reasoner = (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                OWLReasoner.class.getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, arguments) -> {
                    if ("getSuperObjectProperties".equals(method.getName())) {
                        if (objectQueries.getAndIncrement() == 0) {
                            return new OWLObjectPropertyNodeSet(
                                    new OWLObjectPropertyNode(objectSuper));
                        }
                        throw new UnsupportedOperationException(
                                "object hierarchy failed after a partial answer");
                    }
                    if ("getSuperDataProperties".equals(method.getName())
                            && dp.equals(arguments[0])) {
                        OWLDataPropertyNodeSet supers = new OWLDataPropertyNodeSet();
                        supers.addNode(new OWLDataPropertyNode(d1));
                        supers.addNode(new OWLDataPropertyNode(d2));
                        return supers;
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(ontology.getOntologyID(),
                    Set.of(ontology), Map.of(), reasoner, true,
                    new RdfQueryService.MaterializationBudgets(10, 10, 2, 100));

            assertTrue(snapshot.ontology().containsAxiom(
                    data.getOWLSubDataPropertyOfAxiom(dp, d1)),
                    "discarded partial results from a failed generator must not consume the "
                            + "successful sibling's result budget");
            assertTrue(snapshot.ontology().containsAxiom(
                    data.getOWLSubDataPropertyOfAxiom(dp, d2)));
            assertTrue(snapshot.note().contains("Sub object properties"), snapshot.note());
            assertFalse(snapshot.note().contains("more than 2 inferred axioms"), snapshot.note());
        } finally {
            reasoner.dispose();
        }
    }

    /**
     * Wraps a reasoner so {@code objectSubject} gains the given inferred object-property supers,
     * while data-property hierarchy queries either answer {@code dataSupers} for
     * {@code dataSubject} or (when {@code dataSubject} is null) fail like ELK's unsupported
     * data-property methods.
     */
    private static OWLReasoner propertyHierarchyStub(OWLReasoner delegate,
            OWLObjectProperty objectSubject, Set<OWLObjectProperty> objectSupers,
            org.semanticweb.owlapi.model.OWLDataProperty dataSubject,
            Set<org.semanticweb.owlapi.model.OWLDataProperty> dataSupers) {
        return (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                OWLReasoner.class.getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, arguments) -> {
                    if ("getSuperObjectProperties".equals(method.getName())
                            && objectSubject.equals(arguments[0])) {
                        OWLObjectPropertyNodeSet supers = new OWLObjectPropertyNodeSet();
                        for (OWLObjectProperty superProperty : objectSupers) {
                            supers.addNode(new OWLObjectPropertyNode(superProperty));
                        }
                        return supers;
                    }
                    if ("getSuperDataProperties".equals(method.getName())) {
                        if (dataSubject == null) {
                            throw new UnsupportedOperationException(
                                    "OWL API reasoner method is not implemented: "
                                            + method.getName());
                        }
                        if (dataSubject.equals(arguments[0])) {
                            OWLDataPropertyNodeSet supers = new OWLDataPropertyNodeSet();
                            for (var superProperty : dataSupers) {
                                supers.addNode(new OWLDataPropertyNode(superProperty));
                            }
                            return supers;
                        }
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @Test
    void propertyAssertionMaterializationDropsTheCategoryOnceTheActualCountExceedsTheBudget()
            throws Exception {
        OWLOntology ontology = ontology();
        var manager = ontology.getOWLOntologyManager();
        var data = manager.getOWLDataFactory();
        var knows = data.getOWLObjectProperty(IRI.create("https://example.org/test#knows"));
        for (int i = 0; i < 4; i++) {
            var subject = data.getOWLNamedIndividual(IRI.create("https://example.org/test#s" + i));
            var object = data.getOWLNamedIndividual(IRI.create("https://example.org/test#o" + i));
            manager.addAxiom(ontology, data.getOWLObjectPropertyAssertionAxiom(
                    knows, subject, object));
        }
        var isolatedManager = OWLManager.createOWLOntologyManager();
        OWLOntology isolated = RdfQueryService.buildSnapshotOntology(
                isolatedManager, ontology.getOntologyID(), Set.of(ontology));
        long assertedAxioms = isolated.getAxiomCount();
        var reasoner = new StructuralReasonerFactory().createReasoner(ontology);
        try {
            String truncation = RdfQueryService.materializePropertyAssertions(
                    isolatedManager, isolated, reasoner, 2);

            assertTrue(truncation.contains("property assertions (more than 2 inferred assertions)"));
            assertTrue(truncation.contains("Targeted reasoner tools remain available"));
            assertEquals(assertedAxioms, isolated.getAxiomCount(),
                    "a truncated category must be dropped entirely, never partially materialized");
        } finally {
            reasoner.dispose();
        }
    }

    /** Returns deliberately dense single-query results for every bounded inference category. */
    private static OWLReasoner highFanout(OWLReasoner delegate, OWLClass subject,
            Set<OWLClass> superClasses, OWLNamedIndividual individual, Set<OWLClass> types,
            OWLDataProperty property, Set<OWLDataProperty> superProperties) {
        return (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                OWLReasoner.class.getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, arguments) -> {
                    if ("getSuperClasses".equals(method.getName())
                            && subject.equals(arguments[0])) {
                        return new OWLClassNodeSet(superClasses.stream()
                                .map(OWLClassNode::new).collect(java.util.stream.Collectors.toSet()));
                    }
                    if ("getTypes".equals(method.getName())
                            && individual.equals(arguments[0])) {
                        return new OWLClassNodeSet(types.stream()
                                .map(OWLClassNode::new).collect(java.util.stream.Collectors.toSet()));
                    }
                    if ("getSuperDataProperties".equals(method.getName())
                            && property.equals(arguments[0])) {
                        return new OWLDataPropertyNodeSet(superProperties.stream()
                                .map(OWLDataPropertyNode::new)
                                .collect(java.util.stream.Collectors.toSet()));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** Wraps a reasoner so one extra object-property value is "inferred" for the given subject. */
    private static OWLReasoner inferringOneValue(OWLReasoner delegate, OWLNamedIndividual subject,
            OWLObjectProperty property, OWLNamedIndividual value) {
        return (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                OWLReasoner.class.getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if ("getObjectPropertyValues".equals(method.getName())
                                && subject.equals(arguments[0]) && property.equals(arguments[1])) {
                            OWLNamedIndividualNodeSet merged = new OWLNamedIndividualNodeSet();
                            for (var node : ((NodeSet<?>) result).getNodes()) {
                                merged.addNode(new OWLNamedIndividualNode(
                                        (OWLNamedIndividual) node.getRepresentativeElement()));
                            }
                            merged.addNode(new OWLNamedIndividualNode(value));
                            return merged;
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** Wraps a reasoner so property-value queries fail like ELK's unsupported operations. */
    private static OWLReasoner refusingPropertyValues(OWLReasoner delegate) {
        return (OWLReasoner) java.lang.reflect.Proxy.newProxyInstance(
                OWLReasoner.class.getClassLoader(), new Class<?>[] {OWLReasoner.class},
                (proxy, method, arguments) -> {
                    if ("getObjectPropertyValues".equals(method.getName())
                            || "getDataPropertyValues".equals(method.getName())) {
                        throw new UnsupportedOperationException(
                                "OWL API reasoner method is not implemented: "
                                        + method.getName());
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static OWLOntology ontology() throws Exception {
        return OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("https://example.org/test"));
    }
}
