package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.profiles.Profiles;

/**
 * Tests for the F6 owned/imported partitioning of the OWL 2 profile check ({@link
 * GovernanceTools#profileCheck}). A module that merely IMPORTS a non-conformant upstream must see its own
 * axioms reported as in-profile ({@code owned_in_profile}=true, {@code count}=0) with the inherited
 * violations surfaced only as {@code imported_violations} context — the fix for BFO's 200 violations
 * swamping a clean module's profile status.
 */
class GovernanceProfileOwnedTest {

    private static final String NS = "http://example.org/g6#";

    private OWLOntologyManager m;
    private OWLDataFactory df;
    private OWLOntology o;
    private OWLAxiom ownedViolation;
    private OWLAxiom importedViolation;

    private OWLClass cls(String n) {
        OWLClass c = df.getOWLClass(IRI.create(NS + n));
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        return c;
    }

    private OWLOntology fixture() throws OWLOntologyCreationException {
        m = OWLManager.createOWLOntologyManager();
        df = m.getOWLDataFactory();
        o = m.createOntology(IRI.create("http://example.org/g6"));
        OWLClass a = cls("A");
        OWLClass b = cls("B");
        OWLClass c = cls("C");
        OWLClass d = cls("D");
        OWLClass e = cls("E");
        OWLClass f = cls("F");
        // ObjectUnionOf leaves OWL 2 EL (but is valid DL), giving one predictable violation per axiom.
        ownedViolation = df.getOWLSubClassOfAxiom(a, df.getOWLObjectUnionOf(b, c));
        importedViolation = df.getOWLSubClassOfAxiom(d, df.getOWLObjectUnionOf(e, f));
        m.addAxiom(o, ownedViolation);
        m.addAxiom(o, importedViolation);
        return GovernanceTools.flatten(o.getImportsClosure());
    }

    @Test
    void ownedViolationFailsOwnedConformanceAndImportedIsContext() throws Exception {
        OWLOntology flat = fixture();
        Map<String, Object> r = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat,
                Set.of(ownedViolation), 25);
        assertFalse((boolean) r.get("in_profile"), "the whole closure is not EL");
        assertFalse((boolean) r.get("owned_in_profile"), "the owned axiom leaves EL");
        assertTrue(((Number) r.get("count")).intValue() >= 1, "the owned violation is counted");
        assertTrue(((Number) r.get("imported_violations")).intValue() >= 1, "the other is inherited context");
    }

    @Test
    void cleanModuleOverDirtyImportIsOwnedInProfile() throws Exception {
        OWLOntology flat = fixture();
        // Nothing owned → the module's own axioms conform, even though the closure does not (the F6 point).
        Map<String, Object> r = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat,
                Collections.emptySet(), 25);
        assertFalse((boolean) r.get("in_profile"), "the closure still has the (imported) violations");
        assertTrue((boolean) r.get("owned_in_profile"), "the owned scope is clean");
        assertEquals(0, ((Number) r.get("count")).intValue());
        assertTrue(((Number) r.get("imported_violations")).intValue() >= 2, "both violations are inherited");
    }

    @Test
    void backCompatOverloadTreatsEveryAxiomAsOwned() throws Exception {
        OWLOntology flat = fixture();
        Map<String, Object> r = GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, 25);
        assertFalse((boolean) r.get("owned_in_profile"));
        assertTrue(((Number) r.get("count")).intValue() >= 2, "no owned set → all violations are owned");
    }
}
