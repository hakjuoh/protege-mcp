package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.profiles.Profiles;

/**
 * Exercises the pure governance checks (profile mapping/conformance, module-ownership subject
 * extraction, foreign-declaration detection) on hand-built ontologies — no Protégé / OWLModelManager.
 */
class GovernanceToolsTest {

    private static final String NS = "http://example.org/gov#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    // ---------------------------------------------------------------- profile name mapping

    @Test
    void profileNamesNormaliseAndMap() {
        assertEquals("DL", GovernanceTools.normalizeProfile(null));      // default
        assertEquals("DL", GovernanceTools.normalizeProfile("dl"));
        assertEquals("EL", GovernanceTools.normalizeProfile("OWL2_EL"));
        assertEquals("QL", GovernanceTools.normalizeProfile("OWL2 QL"));
        assertEquals("RL", GovernanceTools.normalizeProfile("rl"));
        assertEquals("none", GovernanceTools.normalizeProfile("Full"));
        assertEquals("none", GovernanceTools.normalizeProfile("none"));

        assertSame(Profiles.OWL2_DL, GovernanceTools.profileFor("DL"));
        assertSame(Profiles.OWL2_EL, GovernanceTools.profileFor("EL"));
        assertSame(Profiles.OWL2_QL, GovernanceTools.profileFor("QL"));
        assertSame(Profiles.OWL2_RL, GovernanceTools.profileFor("RL"));
        assertNull(GovernanceTools.profileFor("none"));
    }

    // ---------------------------------------------------------------- OWL 2 profile conformance

    @Test
    void profileCheckFlagsAnAxiomThatLeavesEl() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/prof"));
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLClass c = cls(df, "C");
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        m.addAxiom(o, df.getOWLDeclarationAxiom(b));
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        // A UNION is expressible in OWL 2 DL but NOT in OWL 2 EL (all classes declared → DL-clean).
        m.addAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLObjectUnionOf(b, c)));

        OWLOntology flat = GovernanceTools.flatten(o.getOntologyID(), o.getImportsClosure());
        assertTrue((boolean) GovernanceTools.profileCheck(Profiles.OWL2_DL, "DL", flat, 25)
                .get("in_profile"), "the union is fine in DL");
        assertFalse((boolean) GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, 25)
                .get("in_profile"), "the union leaves EL");
        assertTrue((int) GovernanceTools.profileCheck(Profiles.OWL2_EL, "EL", flat, 25)
                .get("count") > 0, "at least one EL violation is reported");
    }

    // ---------------------------------------------------------------- module ownership / import layering

    @Test
    void subjectEntitiesAreTheCharacterisedTermsNotObjects() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass sub = cls(df, "Sub");
        OWLClass sup = cls(df, "Sup");
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));

        // SubClassOf(Sub, Sup): the subject is Sub, not Sup (which is only referenced).
        Set<OWLEntity> s1 = GovernanceTools.subjectEntities(df.getOWLSubClassOfAxiom(sub, sup));
        assertTrue(s1.contains(sub), "the subclass is a subject");
        assertFalse(s1.contains(sup), "the referenced superclass is NOT a subject");

        // A property domain axiom's subject is the property.
        Set<OWLEntity> s2 = GovernanceTools.subjectEntities(df.getOWLObjectPropertyDomainAxiom(p, sub));
        assertTrue(s2.contains(p), "the property is the subject of its domain axiom");
        assertFalse(s2.contains(sub), "the domain class is not the subject");

        // A property chain characterises its SUPER property: asserting a chain for an imported
        // super-property (e.g. an upstream hasGrandparent) is an import-layering violation, not a
        // mere reference — so the super-property, not the chain links, is the subject.
        OWLObjectProperty q = df.getOWLObjectProperty(IRI.create(NS + "q"));
        OWLObjectProperty grand = df.getOWLObjectProperty(IRI.create(NS + "grand"));
        Set<OWLEntity> s3 = GovernanceTools.subjectEntities(
                df.getOWLSubPropertyChainOfAxiom(java.util.Arrays.asList(p, q), grand));
        assertTrue(s3.contains(grand), "the chain's super-property is the subject");
        assertFalse(s3.contains(p), "a chain link is only referenced, not a subject");

        // A class assertion (ABox) has no TBox subject — asserting facts about a local individual that
        // references an upstream class is not a layering violation.
        assertTrue(GovernanceTools.subjectEntities(df.getOWLClassAssertionAxiom(
                sup, df.getOWLNamedIndividual(IRI.create(NS + "i")))).isEmpty(),
                "class assertions contribute no subject");
    }

    @Test
    void foreignDeclaredEntitiesAreImportedButNotLocallyDeclared() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass upstream = df.getOWLClass(IRI.create("http://example.org/base#Upstream"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(upstream));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(baseIri)));
        OWLClass local = cls(df, "Local");
        m.addAxiom(active, df.getOWLDeclarationAxiom(local));

        Set<OWLEntity> foreign = GovernanceTools.foreignDeclaredEntities(active);
        assertTrue(foreign.contains(upstream), "Upstream is declared only in the import");
        assertFalse(foreign.contains(local), "the locally-declared term is not foreign");
    }

    @Test
    void ownershipDistinguishesReAxiomatisingUpstreamFromMerelyReferencingIt()
            throws OWLOntologyCreationException {
        // base owns Upstream; active declares Local and (bad) re-axiomatises Upstream ⊑ Local.
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI baseIri = IRI.create("http://example.org/base");
        OWLOntology base = m.createOntology(baseIri);
        OWLClass upstream = df.getOWLClass(IRI.create("http://example.org/base#Upstream"));
        m.addAxiom(base, df.getOWLDeclarationAxiom(upstream));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/active"));
        m.applyChange(new AddImport(active, df.getOWLImportsDeclaration(baseIri)));
        OWLClass local = cls(df, "Local");
        m.addAxiom(active, df.getOWLDeclarationAxiom(local));
        m.addAxiom(active, df.getOWLSubClassOfAxiom(local, upstream));       // OK: references upstream
        m.addAxiom(active, df.getOWLSubClassOfAxiom(upstream, local));       // BAD: re-axiomatises upstream

        Set<OWLEntity> foreign = GovernanceTools.foreignDeclaredEntities(active);
        boolean flagged = false;
        boolean falsePositive = false;
        for (org.semanticweb.owlapi.model.OWLAxiom ax : active.getLogicalAxioms()) {
            boolean hitsForeignSubject = false;
            for (OWLEntity subj : GovernanceTools.subjectEntities(ax)) {
                if (foreign.contains(subj)) {
                    hitsForeignSubject = true;
                }
            }
            if (ax.equals(df.getOWLSubClassOfAxiom(upstream, local))) {
                flagged = hitsForeignSubject;
            }
            if (ax.equals(df.getOWLSubClassOfAxiom(local, upstream))) {
                falsePositive = hitsForeignSubject;
            }
        }
        assertTrue(flagged, "SubClassOf(Upstream, Local) modifies an imported term → layering violation");
        assertFalse(falsePositive, "SubClassOf(Local, Upstream) only references the import → not flagged");
    }
}
