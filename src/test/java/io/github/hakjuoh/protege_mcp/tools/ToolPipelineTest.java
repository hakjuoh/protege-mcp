package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.profiles.Profiles;

/**
 * A headless, CI-runnable smoke test of the whole authoring flow across tool boundaries — the
 * automated stand-in for a live-Protégé load → edit → validate → govern → diff → SPARQL round-trip.
 * It drives the pure tool cores end-to-end over an in-memory ontology (no Protégé/OSGi/GUI): a
 * curation edit is applied, then validated, governed, diffed against the pre-edit state, and queried
 * with SPARQL — so a regression that only shows up when the tools are combined is caught here rather
 * than only in a manual session. (The live GUI leg is the checklist in docs/smoke-test.md.)
 */
class ToolPipelineTest {

    private static final String NS = "http://example.org/pipe#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private void declareLabeled(OWLOntologyManager m, OWLDataFactory df, OWLOntology o, OWLClass c,
            String label) {
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral(label)));
    }

    @Test
    void editValidateGovernDiffQueryRoundTrips() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/pipe"));

        // --- load: a small base ontology.
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        declareLabeled(m, df, o, animal, "Animal");
        declareLabeled(m, df, o, pet, "Pet");
        Set<OWLAxiom> before = o.getAxioms();

        // --- edit: create_term Dog ⊑ Animal, ≡ Pet, with a definition (via the curation change builder).
        OWLClass dog = cls(df, "Dog");
        declareLabeled(m, df, o, dog, "Dog");
        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.singletonList(animal), Collections.singletonList(pet),
                df.getRDFSComment(), "A domestic dog.", "en", Collections.emptySet());
        m.applyChanges(changes);
        Set<OWLAxiom> after = o.getAxioms();

        // --- diff: the edit shows up as only-in-right axioms (the round-trip safety net).
        DiffTools.Diff d = DiffTools.diff(before, after);
        assertTrue(d.onlyLeft.isEmpty(), "the edit only adds axioms");
        assertTrue(d.onlyRight.contains(df.getOWLSubClassOfAxiom(dog, animal)),
                "diff surfaces the new parent axiom");
        assertTrue(d.onlyRight.contains(df.getOWLEquivalentClassesAxiom(dog, pet)),
                "diff surfaces the new equivalence");

        // --- validate: no structural smells introduced (Dog is labelled, in-hierarchy, no cycle).
        List<ValidationTools.Finding> findings = ValidationTools.analyze(Collections.singleton(o));
        assertEquals(0, finding(findings, "subclass_cycle").count(), "no cycle");
        assertEquals(0, finding(findings, "self_subclass").count(), "no self-subclass");
        assertFalse(hasEntity(finding(findings, "missing_label"), dog.getIRI()), "Dog is labelled");

        // --- govern: the ontology stays in OWL 2 DL and has no import-layering violations (no imports).
        OWLOntology flat = GovernanceTools.flatten(o.getImportsClosure());
        assertTrue((boolean) GovernanceTools.profileCheck(Profiles.OWL2_DL, "DL", flat, 25).get("in_profile"),
                "still OWL 2 DL after the edit");
        assertTrue(GovernanceTools.foreignDeclaredEntities(o).isEmpty(),
                "a standalone module owns all its terms");

        // --- query: SPARQL sees the new subclass edge over the edited model.
        Map<String, String> prefixes = new LinkedHashMap<>();
        prefixes.put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        prefixes.put("ex:", NS);
        Map<String, Object> result = SparqlTools.execute(o, prefixes,
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 100, 30_000);
        assertEquals("SELECT", result.get("query_type"));
        assertTrue(bindingsMention(result, dog.getIRI().toString()),
                "SPARQL returns Dog as a subclass of Animal");
    }

    private ValidationTools.Finding finding(List<ValidationTools.Finding> findings, String id) {
        return findings.stream().filter(f -> f.id.equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no finding for " + id));
    }

    private boolean hasEntity(ValidationTools.Finding f, IRI iri) {
        for (Object e : f.entities) {
            if (((org.semanticweb.owlapi.model.OWLEntity) e).getIRI().equals(iri)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean bindingsMention(Map<String, Object> selectResult, String iri) {
        Object bindings = selectResult.get("bindings");
        if (!(bindings instanceof List)) {
            return false;
        }
        for (Object row : (List<Object>) bindings) {
            for (Object cell : ((Map<String, Object>) row).values()) {
                if (iri.equals(((Map<String, Object>) cell).get("value"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
