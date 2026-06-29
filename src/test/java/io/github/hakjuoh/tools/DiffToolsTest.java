package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntologyManager;

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
}
