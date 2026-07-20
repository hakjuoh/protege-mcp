package io.github.hakjuoh.protege_mcp.core.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class SyntacticImpactServiceTest {

    private static final String NS = "https://example.org/impact/";

    @Test
    void annotationSubjectAndIriValueParticipateSymmetrically() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory data = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create(NS));
        OWLClass subject = data.getOWLClass(IRI.create(NS + "Subject"));
        OWLClass value = data.getOWLClass(IRI.create(NS + "Value"));
        OWLClass neighbor = data.getOWLClass(IRI.create(NS + "Neighbor"));
        manager.addAxiom(ontology, data.getOWLSubClassOfAxiom(value, neighbor));
        OWLAnnotationAssertionAxiom delta = data.getOWLAnnotationAssertionAxiom(
                data.getRDFSSeeAlso(), subject.getIRI(), value.getIRI());

        SyntacticImpactService.Result result = SyntacticImpactService.analyze(ontology, ontology,
                List.of(delta), List.of(), false);

        assertEquals(List.of(data.getRDFSSeeAlso().getIRI().toString(), subject.getIRI().toString(),
                        value.getIRI().toString()),
                result.affectedIris());
        assertEquals(3, result.directlyAffectedMap(10).get("count"));
        assertTrue(result.referencingAxioms().contains(
                data.getOWLSubClassOfAxiom(value, neighbor)),
                "an annotation IRI value must expose axioms referencing the corresponding entity");
    }

    @Test
    void downstreamProbeDisclosesReachabilityBeyondTheDepthCap() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory data = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + "chain"));
        OWLClass a = cls(data, "A");
        OWLClass b = cls(data, "B");
        OWLClass c = cls(data, "C");
        OWLClass d = cls(data, "D");
        OWLClass e = cls(data, "E");
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(a, b));
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(b, c));
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(c, d));
        manager.addAxiom(ontology, data.getOWLEquivalentClassesAxiom(d, e));
        OWLAxiom delta = data.getOWLDeclarationAxiom(a);

        SyntacticImpactService.Result result = SyntacticImpactService.analyze(ontology, ontology,
                List.of(delta), List.of(), false);

        assertEquals(List.of(1, 2, 3),
                result.downstream().terms().stream()
                        .map(SyntacticImpactService.DownstreamTerm::depth).toList());
        assertTrue(result.downstream().searchTruncated());
        Map<String, Object> envelope = result.downstream().toMap(2);
        assertEquals(3, envelope.get("count"));
        assertEquals(1, envelope.get("truncated"));
        assertEquals(true, envelope.get("search_truncated"));
    }

    @Test
    void importLayeringAndDeprecationUseTheFullClosureEvenWhenDisplayScopeIsRootOnly()
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory data = manager.getOWLDataFactory();
        IRI importedIri = IRI.create(NS + "upstream");
        OWLOntology imported = manager.createOntology(importedIri);
        OWLOntology root = manager.createOntology(IRI.create(NS + "root"));
        manager.applyChange(new AddImport(root, data.getOWLImportsDeclaration(importedIri)));
        OWLClass upstream = cls(data, "Upstream");
        OWLClass local = cls(data, "Local");
        manager.addAxiom(imported, data.getOWLDeclarationAxiom(upstream));
        manager.addAxiom(imported, data.getOWLAnnotationAssertionAxiom(data.getOWLDeprecated(),
                upstream.getIRI(), data.getOWLLiteral(true)));
        OWLAxiom delta = data.getOWLSubClassOfAxiom(upstream, local);

        SyntacticImpactService.Result result = SyntacticImpactService.analyze(root, root,
                List.of(delta), List.of(), false);

        assertEquals(1, result.foreignReaxiomatization().size());
        assertEquals(upstream.getIRI().toString(),
                result.foreignReaxiomatization().get(0).subjectIri());
        assertEquals(List.of(upstream), result.deprecatedTerms());
    }

    @Test
    void compareHonorsImportScopeWithoutChangingRootDeltaSemantics() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory data = manager.getOWLDataFactory();
        IRI leftImportIri = IRI.create(NS + "left-import");
        IRI rightImportIri = IRI.create(NS + "right-import");
        OWLOntology leftImport = manager.createOntology(leftImportIri);
        OWLOntology rightImport = manager.createOntology(rightImportIri);
        OWLOntology left = manager.createOntology(IRI.create(NS + "left"));
        OWLOntology right = manager.createOntology(IRI.create(NS + "right"));
        manager.applyChange(new AddImport(left, data.getOWLImportsDeclaration(leftImportIri)));
        manager.applyChange(new AddImport(right, data.getOWLImportsDeclaration(rightImportIri)));
        manager.addAxiom(leftImport, data.getOWLDeclarationAxiom(cls(data, "OldImported")));
        manager.addAxiom(rightImport, data.getOWLDeclarationAxiom(cls(data, "NewImported")));

        SyntacticImpactService.Result roots = SyntacticImpactService.compare(left, right, false);
        SyntacticImpactService.Result closures = SyntacticImpactService.compare(left, right, true);

        assertEquals(0, roots.addedCount());
        assertEquals(0, roots.removedCount());
        assertEquals(1, closures.addedCount());
        assertEquals(1, closures.removedCount());
    }

    @Test
    void resultCollectionsAreImmutableAndInputsFailClosed() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create(NS + "immutable"));
        SyntacticImpactService.Result result = SyntacticImpactService.analyze(ontology, ontology,
                List.of(), List.of(), false);

        assertFalse(result.downstream().searchTruncated());
        assertThrows(UnsupportedOperationException.class,
                () -> result.affectedIris().add(NS + "later"));
        assertThrows(IllegalArgumentException.class,
                () -> SyntacticImpactService.analyze(null, ontology, List.of(), List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> SyntacticImpactService.analyze(ontology, ontology,
                        java.util.Arrays.asList((OWLAxiom) null), List.of(), false));
    }

    private static OWLClass cls(OWLDataFactory data, String local) {
        return data.getOWLClass(IRI.create(NS + local));
    }
}
