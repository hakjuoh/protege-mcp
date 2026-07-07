package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Tests for the F2 fix in {@link EntitySearch}: a query that grounds to a term via a label the substring
 * finder missed (e.g. the spaced "natural language definition" vs the camelCase rendering) now surfaces
 * that term in {@code items} — so a non-null {@code best_match} is no longer reported alongside an empty
 * result set — but only when the term's type satisfies the search's type filter.
 */
class EntitySearchBestMatchTest {

    private static final String NS = "http://example.org/f2b#";

    private OWLOntologyManager m;
    private OWLDataFactory df;
    private OWLOntology o;

    private OWLClass labelled(String local, String label) {
        OWLClass c = df.getOWLClass(IRI.create(NS + local));
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral(label, "en")));
        return c;
    }

    private OWLModelManager fixture() throws OWLOntologyCreationException {
        m = OWLManager.createOWLOntologyManager();
        df = m.getOWLDataFactory();
        o = m.createOntology(IRI.create("http://example.org/f2b"));
        return FakeModelManager.over(o);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("items");
    }

    @Test
    void bestMatchIsSurfacedInItemsWhenTheTypeAllows() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass nld = labelled("naturalLanguageDefinition", "natural language definition");
        // The finder found nothing (spaced query vs camelCase rendering), but best_match grounds via label.
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "natural language definition",
                Collections.emptySet(), 0, 50, "class");
        assertEquals(nld.getIRI().toString(), r.get("best_match"));
        assertEquals(1, items(r).size(), "best_match is now reflected in items (F2)");
        assertEquals(nld.getIRI().toString(), items(r).get(0).get("iri"));
        assertEquals("exact", items(r).get(0).get("match_kind"));
    }

    @Test
    void bestMatchOfAnotherTypeIsNotInjectedUnderATypeFilter() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass nld = labelled("naturalLanguageDefinition", "natural language definition");
        // Searching for an annotation_property must not list a class, even though best_match resolves to it.
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "natural language definition",
                Collections.emptySet(), 0, 50, "annotation_property");
        assertTrue(items(r).isEmpty(), "a class is not injected into an annotation_property search");
        assertEquals(nld.getIRI().toString(), r.get("best_match"), "best_match is still reported as context");
    }
}
