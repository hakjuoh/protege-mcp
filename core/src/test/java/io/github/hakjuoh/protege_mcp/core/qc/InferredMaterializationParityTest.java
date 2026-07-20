package io.github.hakjuoh.protege_mcp.core.qc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.AxiomAnnotations;
import org.semanticweb.owlapi.model.parameters.Imports;
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
import org.semanticweb.owlapi.util.InferredAxiomGenerator;
import org.semanticweb.owlapi.util.InferredClassAssertionAxiomGenerator;
import org.semanticweb.owlapi.util.InferredEquivalentClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredOntologyGenerator;
import org.semanticweb.owlapi.util.InferredPropertyAssertionGenerator;
import org.semanticweb.owlapi.util.InferredSubClassAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubDataPropertyAxiomGenerator;
import org.semanticweb.owlapi.util.InferredSubObjectPropertyAxiomGenerator;

/**
 * Differential parity harness for the budgeted inference materialization.
 *
 * <p>Every 0.7.1 review round found a defect in a rewrite of this subsystem, always in the same
 * class: divergence from the {@code InferredOntologyGenerator.fillOntology} semantics that shipped
 * through 0.7.0 (exception-containment granularity, budget bookkeeping across failed generators).
 * This harness pins the parity theorem directly instead of pinning single scenarios: for EVERY
 * reasoner-behavior scenario in the matrix, the within-budget snapshot must contain exactly the
 * axioms the 0.7.0 reference path materializes, and — the one deliberate improvement over 0.7.0 —
 * a note must be present exactly when an enumeration failed, where the reference skipped silently.
 *
 * <p>Any future rewrite of the materialization path must keep this suite green; extend the
 * scenario matrix rather than weakening the equality assertion.
 */
class InferredMaterializationParityTest {

    private static final String NS = "https://example.org/parity#";

    /** One reasoner method that fails in this scenario ("none" = fully answering reasoner). */
    private static final List<String> SCENARIOS = List.of(
            "none",
            "isSatisfiable",
            "getSuperClasses",
            "getEquivalentClasses",
            "getTypes",
            "getSuperObjectProperties",
            "getSuperDataProperties",
            "getObjectPropertyValues",
            "getDataPropertyValues");

    @Test
    void withinBudgetSnapshotsMatchTheOwlapiReferenceForEveryReasonerBehavior() throws Exception {
        for (String throwing : SCENARIOS) {
            Fixture fixture = fixture();
            OWLReasoner reference = stubReasoner(fixture, throwing);
            OWLReasoner budgeted = stubReasoner(fixture, throwing);
            try {
                Set<OWLAxiom> expected = referenceMaterialization(fixture, reference);
                RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(
                        fixture.ontology.getOntologyID(), Set.of(fixture.ontology), Map.of(),
                        budgeted, true);

                assertEquals(expected, snapshot.ontology().getAxioms(),
                        "scenario '" + throwing + "': the budgeted path must materialize exactly "
                                + "the axioms OWLAPI's 0.7.0 reference path materialized");
                if ("none".equals(throwing)) {
                    assertNull(snapshot.note(),
                            "a fully answering reasoner within budget must produce no note");
                } else {
                    assertNotNull(snapshot.note(),
                            "scenario '" + throwing + "': a failed enumeration must be disclosed "
                                    + "in the note (0.7.0 skipped silently; disclosure is the one "
                                    + "deliberate improvement)");
                }
            } finally {
                reference.dispose();
                budgeted.dispose();
            }
        }
    }

    @Test
    void everyInjectedInferenceIsGenuinelyInferredOnly() throws Exception {
        // Guards the harness itself against the asserted-witness vacuity bug found twice in
        // earlier rounds: if an injected axiom were also asserted, the parity assertion could
        // pass while materialization silently produced nothing.
        Fixture fixture = fixture();
        OWLDataFactory data = fixture.data;
        for (OWLAxiom injected : List.of(
                data.getOWLSubClassOfAxiom(fixture.a, fixture.c),
                data.getOWLEquivalentClassesAxiom(fixture.a, fixture.eqA),
                data.getOWLClassAssertionAxiom(fixture.extraType, fixture.alice),
                data.getOWLSubObjectPropertyOfAxiom(fixture.op, fixture.opSup),
                data.getOWLSubDataPropertyOfAxiom(fixture.dp, fixture.dpSup),
                data.getOWLObjectPropertyAssertionAxiom(fixture.op, fixture.alice, fixture.bob),
                data.getOWLDataPropertyAssertionAxiom(fixture.dp, fixture.alice,
                        data.getOWLLiteral("v")))) {
            assertTrue(!fixture.ontology.containsAxiom(injected, Imports.INCLUDED,
                    AxiomAnnotations.IGNORE_AXIOM_ANNOTATIONS),
                    "parity witness must be inferred-only: " + injected);
        }
        OWLReasoner reasoner = stubReasoner(fixture, "none");
        try {
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(
                    fixture.ontology.getOntologyID(), Set.of(fixture.ontology), Map.of(),
                    reasoner, true);
            assertTrue(snapshot.ontology().containsAxiom(
                    fixture.data.getOWLSubClassOfAxiom(fixture.a, fixture.c)),
                    "the fully answering scenario must actually materialize the injected axioms");
        } finally {
            reasoner.dispose();
        }
    }

    @Test
    void inferredAxiomsAlreadyAssertedWithAnnotationsRemainSingleAxioms() throws Exception {
        Fixture fixture = fixture();
        var annotation = fixture.data.getOWLAnnotation(fixture.data.getRDFSLabel(),
                fixture.data.getOWLLiteral("curated assertion"));
        var annotated = fixture.data.getOWLSubClassOfAxiom(fixture.a, fixture.c,
                Set.of(annotation));
        fixture.ontology.getOWLOntologyManager().addAxiom(fixture.ontology, annotated);
        OWLReasoner reference = stubReasoner(fixture, "none");
        OWLReasoner budgeted = stubReasoner(fixture, "none");
        try {
            Set<OWLAxiom> expected = referenceMaterialization(fixture, reference);
            RdfQueryService.Snapshot snapshot = RdfQueryService.snapshot(
                    fixture.ontology.getOntologyID(), Set.of(fixture.ontology), Map.of(),
                    budgeted, true);

            assertEquals(expected, snapshot.ontology().getAxioms(),
                    "OWLAPI fillOntology ignores annotations when deciding whether an inferred "
                            + "axiom already exists; the budgeted path must not add an unannotated "
                            + "duplicate");
            assertTrue(snapshot.ontology().containsAxiom(annotated));
        } finally {
            reference.dispose();
            budgeted.dispose();
        }
    }

    // ------------------------------------------------------------------ reference path

    /** The exact generator stack the plugin shipped through 0.7.0. */
    private static Set<OWLAxiom> referenceMaterialization(Fixture fixture, OWLReasoner reasoner) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology target = RdfQueryService.buildSnapshotOntology(manager,
                fixture.ontology.getOntologyID(), Set.of(fixture.ontology));
        List<InferredAxiomGenerator<? extends OWLAxiom>> generators = List.of(
                new InferredSubClassAxiomGenerator(),
                new InferredEquivalentClassAxiomGenerator(),
                new InferredClassAssertionAxiomGenerator(),
                new InferredSubObjectPropertyAxiomGenerator(),
                new InferredSubDataPropertyAxiomGenerator(),
                new InferredPropertyAssertionGenerator());
        new InferredOntologyGenerator(reasoner, generators)
                .fillOntology(manager.getOWLDataFactory(), target);
        return target.getAxioms();
    }

    // ------------------------------------------------------------------ fixture + stub

    private static final class Fixture {
        OWLOntology ontology;
        OWLDataFactory data;
        OWLClass a;
        OWLClass b;
        OWLClass c;
        OWLClass eqA;
        OWLClass extraType;
        OWLNamedIndividual alice;
        OWLNamedIndividual bob;
        OWLObjectProperty op;
        OWLObjectProperty opSup;
        OWLDataProperty dp;
        OWLDataProperty dpSup;
    }

    private static Fixture fixture() throws Exception {
        Fixture fixture = new Fixture();
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        fixture.ontology = manager.createOntology(IRI.create(NS.substring(0, NS.length() - 1)));
        fixture.data = manager.getOWLDataFactory();
        OWLDataFactory data = fixture.data;
        fixture.a = data.getOWLClass(IRI.create(NS + "A"));
        fixture.b = data.getOWLClass(IRI.create(NS + "B"));
        fixture.c = data.getOWLClass(IRI.create(NS + "C"));
        fixture.eqA = data.getOWLClass(IRI.create(NS + "EqA"));
        fixture.extraType = data.getOWLClass(IRI.create(NS + "ExtraType"));
        fixture.alice = data.getOWLNamedIndividual(IRI.create(NS + "alice"));
        fixture.bob = data.getOWLNamedIndividual(IRI.create(NS + "bob"));
        fixture.op = data.getOWLObjectProperty(IRI.create(NS + "op"));
        fixture.opSup = data.getOWLObjectProperty(IRI.create(NS + "opSup"));
        fixture.dp = data.getOWLDataProperty(IRI.create(NS + "dp"));
        fixture.dpSup = data.getOWLDataProperty(IRI.create(NS + "dpSup"));
        manager.addAxiom(fixture.ontology, data.getOWLSubClassOfAxiom(fixture.a, fixture.b));
        manager.addAxiom(fixture.ontology, data.getOWLClassAssertionAxiom(fixture.a, fixture.alice));
        manager.addAxiom(fixture.ontology, data.getOWLDeclarationAxiom(fixture.bob));
        manager.addAxiom(fixture.ontology, data.getOWLDeclarationAxiom(fixture.op));
        manager.addAxiom(fixture.ontology, data.getOWLDeclarationAxiom(fixture.dp));
        return fixture;
    }

    /**
     * A reasoner that answers one inferred-only result per category and fails exactly the
     * scenario's method, so both materialization paths see identical behavior.
     */
    private static OWLReasoner stubReasoner(Fixture fixture, String throwing) {
        OWLReasoner delegate = new StructuralReasonerFactory().createReasoner(fixture.ontology);
        return (OWLReasoner) Proxy.newProxyInstance(OWLReasoner.class.getClassLoader(),
                new Class<?>[] {OWLReasoner.class}, (proxy, method, arguments) -> {
                    String name = method.getName();
                    if (name.equals(throwing)) {
                        throw new UnsupportedOperationException(
                                "OWL API reasoner method is not implemented: " + name);
                    }
                    switch (name) {
                        case "getSuperClasses":
                            if (fixture.a.equals(arguments[0])) {
                                return new OWLClassNodeSet(new OWLClassNode(fixture.c));
                            }
                            break;
                        case "getEquivalentClasses":
                            if (fixture.a.equals(arguments[0])) {
                                return new OWLClassNode(Set.of(fixture.a, fixture.eqA));
                            }
                            break;
                        case "getTypes":
                            if (fixture.alice.equals(arguments[0])) {
                                return new OWLClassNodeSet(new OWLClassNode(fixture.extraType));
                            }
                            break;
                        case "getSuperObjectProperties":
                            if (fixture.op.equals(arguments[0])) {
                                OWLObjectPropertyNodeSet supers = new OWLObjectPropertyNodeSet();
                                supers.addNode(new OWLObjectPropertyNode(fixture.opSup));
                                return supers;
                            }
                            break;
                        case "getSuperDataProperties":
                            if (fixture.dp.equals(arguments[0])) {
                                OWLDataPropertyNodeSet supers = new OWLDataPropertyNodeSet();
                                supers.addNode(new OWLDataPropertyNode(fixture.dpSup));
                                return supers;
                            }
                            break;
                        case "getObjectPropertyValues":
                            if (fixture.alice.equals(arguments[0])
                                    && fixture.op.equals(arguments[1])) {
                                OWLNamedIndividualNodeSet values = new OWLNamedIndividualNodeSet();
                                values.addNode(new OWLNamedIndividualNode(fixture.bob));
                                return values;
                            }
                            break;
                        case "getDataPropertyValues":
                            if (fixture.alice.equals(arguments[0])
                                    && fixture.dp.equals(arguments[1])) {
                                return Set.of(fixture.data.getOWLLiteral("v"));
                            }
                            break;
                        default:
                            break;
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
