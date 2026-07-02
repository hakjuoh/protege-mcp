package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Headless tests for the F2 grounding-aware ranking + mint prediction ({@link EntitySearch}), driven over
 * a {@code FakeModelManager} whose finder resolves by IRI / short form and whose renderer is the short
 * form. Covers ranking order + tiers, the multi-language-label / local-name exact match, diacritic
 * folding, and the {@code would_mint} carve-outs (full IRI, Manchester expression, existing term).
 */
class EntitySearchTest {

    private static final String NS = "http://example.org/f2#";

    private OWLOntologyManager m;
    private OWLDataFactory df;
    private OWLOntology o;

    private OWLClass labelled(String local, String label, String lang) {
        OWLClass c = df.getOWLClass(IRI.create(NS + local));
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                lang == null ? df.getOWLLiteral(label) : df.getOWLLiteral(label, lang)));
        return c;
    }

    private OWLModelManager fixture() throws OWLOntologyCreationException {
        m = OWLManager.createOWLOntologyManager();
        df = m.getOWLDataFactory();
        o = m.createOntology(IRI.create("http://example.org/f2"));
        return FakeModelManager.over(o);
    }

    @SafeVarargs
    private static Set<OWLClass> set(OWLClass... cs) {
        return new LinkedHashSet<>(java.util.Arrays.asList(cs));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("items");
    }

    // ------------------------------------------------------------------ ranking

    @Test
    void exactBeatsPrefixBeatsSubstring() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass neuron = labelled("Neuron", "Neuron", null);          // exact for "neuron"
        OWLClass neuroblast = labelled("Neuroblast", "Neuroblast", null); // prefix for "neuron"? no → for "neuro"
        OWLClass interneuron = labelled("Interneuron", "Interneuron", null); // substring for "neuron"

        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Neuron",
                set(interneuron, neuroblast, neuron), 50);
        List<Map<String, Object>> items = items(r);
        assertEquals(neuron.getIRI().toString(), items.get(0).get("iri"), "exact match ranks first");
        assertEquals("exact", items.get(0).get("match_kind"));
        assertEquals(100, items.get(0).get("score"));
        // Interneuron contains 'neuron' as a substring; Neuroblast does not match 'neuron' at all.
        assertEquals("substring", byIri(items, interneuron).get("match_kind"));
        assertEquals("fuzzy", byIri(items, neuroblast).get("match_kind"),
                "Neuroblast shares no 'neuron' substring → fuzzy");
    }

    @Test
    void prefixTierForALeadingFragment() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass neuroblast = labelled("Neuroblast", "Neuroblast", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Neuro", set(neuroblast), 50);
        assertEquals("prefix", items(r).get(0).get("match_kind"), "'Neuro' is a prefix of 'Neuroblast'");
    }

    @Test
    void tieBreaksDeterministicallyByDisplayThenIri() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass zebra = labelled("Zebra", "Zebra", null);
        OWLClass apple = labelled("Apple", "Apple", null);
        // Neither matches 'xyz' → both FUZZY (same score); order must be stable by display then IRI.
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "xyz", set(zebra, apple), 50);
        List<Map<String, Object>> items = items(r);
        assertEquals(apple.getIRI().toString(), items.get(0).get("iri"), "Apple sorts before Zebra");
        assertEquals(zebra.getIRI().toString(), items.get(1).get("iri"));
    }

    @Test
    void limitCapsItemsButKeepsFullCount() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass a = labelled("Alpha", "Alpha", null);
        OWLClass b = labelled("Alpine", "Alpine", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Al", set(a, b), 1);
        assertEquals(2, r.get("count"), "count is the full match size");
        assertEquals(1, items(r).size(), "items are capped at limit");
        assertEquals(1, r.get("truncated"), "truncated reports how many were omitted");
    }

    // ------------------------------------------------------------------ would_mint

    @Test
    void wouldMintTrueForNovelSingleTerm() throws Exception {
        OWLModelManager mm = fixture();
        labelled("Person", "Person", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Glia", Collections.emptySet(), 50);
        assertTrue((boolean) r.get("would_mint"), "a term that resolves to nothing would mint a new entity");
        assertNull(r.get("best_match"));
    }

    @Test
    void wouldMintFalseForExistingTermByRendering() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass person = labelled("Person", "Person", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Person", set(person), 50);
        assertFalse((boolean) r.get("would_mint"), "an existing term must not read as mintable");
        assertEquals(person.getIRI().toString(), r.get("best_match"));
    }

    @Test
    void wouldMintFalseForForeignLanguageLabelEvenWhenFinderMisses() throws Exception {
        OWLModelManager mm = fixture();
        // Short form 'X_001' differs from the @de label 'Neuron'; the finder (rendering=short form) does
        // not surface it, so matches is empty — yet the label-index scan must still ground the query.
        OWLClass x = labelled("X_001", "Neuron", "de");
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Neuron", Collections.emptySet(), 50);
        assertFalse((boolean) r.get("would_mint"),
                "a @de-only label must ground under an @en config (no false mint)");
        assertEquals(x.getIRI().toString(), r.get("best_match"));
    }

    @Test
    void exactMatchFoldsDiacritics() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass cafe = labelled("Cafe", "Café", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "cafe", set(cafe), 50);
        assertFalse((boolean) r.get("would_mint"), "'cafe' grounds to the 'Café' label (diacritic-folded)");
        assertEquals(cafe.getIRI().toString(), r.get("best_match"));
        assertEquals("exact", items(r).get(0).get("match_kind"));
    }

    @Test
    void wouldMintFalseForFullIriInput() throws Exception {
        OWLModelManager mm = fixture();
        Map<String, Object> r = EntitySearch.enrichedSearch(mm,
                "http://example.org/f2#Unknown", Collections.emptySet(), 50);
        assertFalse((boolean) r.get("would_mint"), "a full IRI is a reference, never flagged as a mint");
    }

    @Test
    void wouldMintFalseForManchesterExpression() throws Exception {
        OWLModelManager mm = fixture();
        labelled("Person", "Person", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "hasOwner some Person",
                Collections.emptySet(), 50);
        assertFalse((boolean) r.get("would_mint"), "a multi-word / Manchester expression is not a mint term");
    }

    @Test
    void wildcardQueryIsFuzzyAndNotMint() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass a = labelled("Alpha", "Alpha", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "*", set(a), 50);
        assertEquals("fuzzy", items(r).get(0).get("match_kind"), "a wildcard-only query cannot rank tiers");
        assertFalse((boolean) r.get("would_mint"), "a wildcard-only query is not a mint candidate");
    }

    // ------------------------------------------------------------------ predicates

    @Test
    void isPlainTermCarveOuts() {
        assertTrue(EntitySearch.isPlainTerm("Neuron"));
        assertFalse(EntitySearch.isPlainTerm("two words"));
        assertFalse(EntitySearch.isPlainTerm("hasP some C"));
        assertFalse(EntitySearch.isPlainTerm("http://x/Y"));
        assertFalse(EntitySearch.isPlainTerm("neu*ron"));
        assertFalse(EntitySearch.isPlainTerm("  "));
    }

    private static Map<String, Object> byIri(List<Map<String, Object>> items, OWLClass c) {
        for (Map<String, Object> item : items) {
            if (c.getIRI().toString().equals(item.get("iri"))) {
                return item;
            }
        }
        throw new AssertionError("no item for " + c);
    }
}
