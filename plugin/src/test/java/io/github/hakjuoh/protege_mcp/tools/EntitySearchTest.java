package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
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

    private OWLClass lexical(String local, String property, String value, String lang) {
        OWLClass c = df.getOWLClass(IRI.create(NS + local));
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        var p = df.getOWLAnnotationProperty(IRI.create(property));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(p, c.getIRI(),
                lang == null ? df.getOWLLiteral(value) : df.getOWLLiteral(value, lang)));
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

    @Test
    void pagedVariantWindowsRankedResultsAndReportsNextOffset() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass a = labelled("Alpha", "Alpha", null);
        OWLClass b = labelled("Alpine", "Alpine", null);
        OWLClass c = labelled("Altimeter", "Altimeter", null);
        // offset=1, limit=1 over three fuzzy-tied "Al" matches ranked by display: Alpha, Alpine, Altimeter.
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Al", set(a, b, c), 1, 1);
        assertEquals(3, r.get("count"), "count is the full ranked size");
        assertEquals(1, r.get("offset"));
        assertEquals(1, r.get("returned"));
        assertEquals(2, r.get("next_offset"), "1 shown from offset 1 of 3 → more remain");
        assertFalse(r.containsKey("truncated"), "the paged variant uses next_offset, not truncated");
        assertEquals(b.getIRI().toString(), items(r).get(0).get("iri"), "offset 1 is the second-ranked hit");
    }

    @Test
    void pagedVariantZeroLimitDoesNotEmitASelfReferentialCursor() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass a = labelled("Alpha", "Alpha", null);
        OWLClass b = labelled("Alpine", "Alpine", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Al", set(a, b), 0, 0);
        assertEquals(2, r.get("count"));
        assertEquals(0, r.get("returned"));
        assertFalse(r.containsKey("next_offset"),
                "a zero-limit page must not advertise next_offset == offset (an infinite-loop cursor)");
    }

    @Test
    void pagedVariantLastPageHasNoNextOffset() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass a = labelled("Alpha", "Alpha", null);
        OWLClass b = labelled("Alpine", "Alpine", null);
        Map<String, Object> r = EntitySearch.enrichedSearch(mm, "Al", set(a, b), 1, 50);
        assertFalse(r.containsKey("next_offset"), "the final page omits next_offset");
        assertEquals(1, r.get("returned"), "one hit remains from offset 1 of 2");
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

    @Test
    void synonymHitIsDiscoveredButOnlyOfferedForReview() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass term = lexical("X_001",
                "http://www.w3.org/2004/02/skos/core#altLabel", "Neurocyte", "en");

        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "Neurocyte", Collections.emptySet(), 0, 50, "class");
        Map<String, Object> hit = items(result).get(0);
        assertEquals(term.getIRI().toString(), hit.get("iri"));
        assertEquals("synonym", hit.get("match_source"));
        assertEquals("en", hit.get("language"));
        assertEquals(Boolean.TRUE, hit.get("needs_review"));
        assertNull(result.get("best_match"), "a synonym is not a guaranteed exact ground");
        assertTrue((Boolean) result.get("would_mint"),
                "the create-name path would still mint; clients must review reuse_candidate first");
        assertNotNull(result.get("reuse_candidate"));
    }

    @Test
    void preferredSkosLabelCanGroundAndExplainsItsSource() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass term = lexical("X_002",
                "http://www.w3.org/2004/02/skos/core#prefLabel", "Cardiomyocyte", "en");
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "Cardiomyocyte", Collections.emptySet(), 0, 50, "class");
        Map<String, Object> hit = items(result).get(0);
        assertEquals("preferred_label", hit.get("match_source"));
        assertEquals(term.getIRI().toString(), result.get("best_match"));
        assertFalse((Boolean) result.get("would_mint"));
        assertTrue(hit.containsKey("normalization"));
        assertTrue(hit.containsKey("score_explanation"));
    }

    @Test
    void sharedSynonymReportsDeterministicCollisionInsteadOfGrounding() throws Exception {
        OWLModelManager mm = fixture();
        String alt = "http://www.w3.org/2004/02/skos/core#altLabel";
        lexical("X_010", alt, "SharedSynonym", "en");
        lexical("X_011", alt, "SharedSynonym", "en");
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "SharedSynonym", Collections.emptySet(), 0, 50, "class");
        assertEquals(2, items(result).size());
        assertEquals(1, ((List<?>) result.get("collisions")).size());
        assertTrue(items(result).stream().allMatch(item -> Boolean.TRUE.equals(item.get("collision"))));
        assertNull(result.get("best_match"));
        assertTrue((Boolean) result.get("would_mint"),
                "synonym-only ambiguity must remain review-only and cannot suppress minting");
        assertFalse((Boolean) result.get("mint_blocked_by_collision"));
    }

    @Test
    void unpagedSearchAlsoDiscoversLexicalForms() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass term = lexical("X_020",
                "http://www.w3.org/2004/02/skos/core#altLabel", "Astrocytic cell", "en");
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "Astrocytic cell", Collections.emptySet(), 50);
        assertEquals(term.getIRI().toString(), items(result).get(0).get("iri"));
        assertEquals("synonym", items(result).get(0).get("match_source"));
    }

    @Test
    void iriEvidenceRemainsCaseSensitive() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass term = labelled("CaseSensitive", "Unrelated label", null);
        Map<String, Object> result = EntitySearch.enrichedSearch(mm,
                term.getIRI().toString().toLowerCase(java.util.Locale.ROOT), set(term), 50);
        assertFalse("exact".equals(items(result).get(0).get("match_kind")),
                "a differently-cased IRI must not be represented as an exact IRI match");
        assertNull(result.get("best_match"));
    }

    @Test
    void synonymCollisionWarnsWithoutOverridingARealLocalNameGround() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass grounded = labelled("SharedName", "Other label", null);
        lexical("X_021", "http://www.w3.org/2004/02/skos/core#altLabel", "SharedName", "en");
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "SharedName", set(grounded), 0, 50, "class");
        assertEquals(grounded.getIRI().toString(), result.get("best_match"));
        assertFalse((Boolean) result.get("would_mint"));
        assertEquals(1, ((List<?>) result.get("collisions")).size(),
                "the collision remains visible for review without changing resolution semantics");
    }

    @Test
    void relatedSynonymsRankBelowExactSynonyms() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass exact = lexical("X_030",
                "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym", "Neural cell", "en");
        OWLClass related = lexical("X_031",
                "http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym", "Neural cell", "en");
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "Neural cell", Collections.emptySet(), 0, 50, "class");
        assertEquals(exact.getIRI().toString(), items(result).get(0).get("iri"));
        assertEquals(100, byIri(items(result), exact).get("score"));
        assertEquals(70, byIri(items(result), related).get("score"));
        assertEquals("related_synonym", byIri(items(result), related).get("match_source"));
    }

    @Test
    void lexicalIndexCacheReusesAndInvalidatesByRevisionSettingsAndModel() throws Exception {
        OWLModelManager first = fixture();
        labelled("Cached", "Cached term", null);
        EntitySearch.Cache cache = new EntitySearch.Cache();
        EntitySearch.Settings defaults = EntitySearch.Settings.defaults();

        EntitySearch.enrichedSearch(first, "Cached", Collections.emptySet(), 0, 50, "class",
                defaults, cache, 7L);
        EntitySearch.enrichedSearch(first, "Cached", Collections.emptySet(), 0, 50, "class",
                defaults, cache, 7L);
        assertEquals(1, cache.builds(), "unchanged workspace searches reuse the lexical index");

        EntitySearch.enrichedSearch(first, "Cached", Collections.emptySet(), 0, 50, "class",
                defaults, cache, 8L);
        assertEquals(2, cache.builds(), "a workspace revision change invalidates the index");

        EntitySearch.Settings labelsOnly = new EntitySearch.Settings(
                Set.of(df.getRDFSLabel().getIRI()), Set.of());
        EntitySearch.enrichedSearch(first, "Cached", Collections.emptySet(), 0, 50, "class",
                labelsOnly, cache, 8L);
        assertEquals(3, cache.builds(), "a lexical policy change invalidates the index");

        OWLModelManager second = fixture();
        EntitySearch.enrichedSearch(second, "Cached", Collections.emptySet(), 0, 50, "class",
                labelsOnly, cache, 8L);
        assertEquals(4, cache.builds(), "a different model manager cannot reuse another window's index");
    }

    @Test
    void punnedPreferredLabelGroundsOnceByIri() throws Exception {
        OWLModelManager mm = fixture();
        IRI iri = IRI.create(NS + "Punned");
        OWLClass cls = df.getOWLClass(iri);
        OWLNamedIndividual individual = df.getOWLNamedIndividual(iri);
        m.addAxiom(o, df.getOWLDeclarationAxiom(cls));
        m.addAxiom(o, df.getOWLDeclarationAxiom(individual));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri,
                df.getOWLLiteral("PunnedAlias", "en")));
        Set<OWLEntity> punned = new LinkedHashSet<>(List.of(cls, individual));

        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "PunnedAlias", punned, 0, 50, "all");
        assertEquals(iri.toString(), result.get("best_match"));
        assertFalse((Boolean) result.get("would_mint"));
        assertFalse((Boolean) result.get("mint_blocked_by_collision"));
    }

    @Test
    void distinctPreferredLabelCollisionBlocksMintWithoutChoosingAWinner() throws Exception {
        OWLModelManager mm = fixture();
        String preferred = "http://www.w3.org/2004/02/skos/core#prefLabel";
        lexical("X_040", preferred, "SharedPreferred", "en");
        lexical("X_041", preferred, "SharedPreferred", "en");

        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "SharedPreferred", Collections.emptySet(), 0, 50, "class");
        assertNull(result.get("best_match"));
        assertFalse((Boolean) result.get("would_mint"),
                "an exact preferred-name collision must not encourage a third duplicate");
        assertTrue((Boolean) result.get("mint_blocked_by_collision"));
    }

    @Test
    void evidenceTieUsesDeterministicLanguageOrder() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass term = lexical("X_050",
                "http://www.w3.org/2004/02/skos/core#altLabel", "StableAlias", "en");
        var alt = df.getOWLAnnotationProperty(
                IRI.create("http://www.w3.org/2004/02/skos/core#altLabel"));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(alt, term.getIRI(),
                df.getOWLLiteral("StableAlias", "de")));
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "StableAlias", Collections.emptySet(), 0, 50, "class");
        assertEquals("de", items(result).get(0).get("language"));
    }

    @Test
    void lexicalCandidateInjectionIsBoundedAndReportsTheOmittedCount() throws Exception {
        OWLModelManager mm = fixture();
        String alt = "http://www.w3.org/2004/02/skos/core#altLabel";
        int total = EntitySearch.MAX_LEXICAL_CANDIDATES + 5;
        for (int i = 0; i < total; i++) {
            lexical("Bulk_" + i, alt, "Bulk term " + i, "en");
        }
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "Bulk", Collections.emptySet(), 0, 10, "class");
        assertEquals(EntitySearch.MAX_LEXICAL_CANDIDATES, result.get("count"));
        assertEquals(total, result.get("lexical_match_count"));
        assertEquals(5, result.get("lexical_candidates_truncated"));
    }

    @Test
    void wildcardFinderHitDoesNotTreatSynonymAsAnExactGround() throws Exception {
        OWLModelManager mm = fixture();
        OWLClass term = lexical("Wildcard",
                "http://www.w3.org/2004/02/skos/core#altLabel", "Neuron", "en");
        EntitySearch.Settings settings = new EntitySearch.Settings(Set.of(), Set.of(IRI.create(
                "http://www.w3.org/2004/02/skos/core#altLabel")), List.of("en"), List.of());
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "Neu*", set(term), 0, 50, "class", settings);
        assertEquals("fuzzy", items(result).get(0).get("match_kind"));
        assertEquals(Boolean.TRUE, items(result).get(0).get("needs_review"));
        assertEquals(0, ((Map<?, ?>) items(result).get(0)
                .get("score_explanation")).get("language_adjustment"));
        assertNull(result.get("best_match"));
    }

    @Test
    void combiningMarksOnlyQueryCannotFloodLexicalCandidatesOrMint() throws Exception {
        OWLModelManager mm = fixture();
        lexical("X_060", "http://www.w3.org/2004/02/skos/core#altLabel", "Neuron", "en");
        Map<String, Object> result = EntitySearch.enrichedSearch(
                mm, "\u0301", Collections.emptySet(), 0, 50, "class");
        assertEquals(0, result.get("count"));
        assertFalse((Boolean) result.get("would_mint"));
        assertFalse(result.containsKey("lexical_candidates_truncated"));
    }

    @Test
    void policyLanguageOrderChangesScoresWithoutChangingGrounding() throws Exception {
        OWLModelManager mm = fixture();
        String alt = "http://www.w3.org/2004/02/skos/core#altLabel";
        OWLClass english = lexical("English", alt, "SharedAlias", "en-US");
        OWLClass german = lexical("German", alt, "SharedAlias", "de");
        OWLClass korean = lexical("Korean", alt, "SharedAlias", "ko");
        EntitySearch.Settings settings = new EntitySearch.Settings(Set.of(),
                Set.of(IRI.create(alt)), List.of("en"), List.of("de"));

        Map<String, Object> result = EntitySearch.enrichedSearch(mm, "SharedAlias",
                Collections.emptySet(), 0, 50, "class", settings);

        assertEquals(109, byIri(items(result), english).get("score"));
        assertEquals(104, byIri(items(result), german).get("score"));
        assertEquals(91, byIri(items(result), korean).get("score"));
        assertEquals(9, ((Map<?, ?>) byIri(items(result), english)
                .get("score_explanation")).get("language_adjustment"));
        assertNull(result.get("best_match"), "language ranking cannot promote a synonym to a ground");
        assertTrue((Boolean) result.get("would_mint"));
    }

    @Test
    void languageAdjustmentNeverInvertsMatchTiers() throws Exception {
        OWLModelManager mm = fixture();
        String alt = "http://www.w3.org/2004/02/skos/core#altLabel";
        OWLClass exactUnlisted = lexical("Exact", alt, "Neuro", "ko");
        OWLClass preferredPrefix = lexical("Prefix", alt, "Neuron", "en");
        EntitySearch.Settings settings = new EntitySearch.Settings(Set.of(),
                Set.of(IRI.create(alt)), List.of("en"), List.of("de"));

        Map<String, Object> result = EntitySearch.enrichedSearch(mm, "Neuro",
                Collections.emptySet(), 0, 50, "class", settings);

        assertEquals(exactUnlisted.getIRI().toString(), items(result).get(0).get("iri"));
        assertEquals(91, byIri(items(result), exactUnlisted).get("score"));
        assertEquals(89, byIri(items(result), preferredPrefix).get("score"));
    }

    @Test
    void projectSpecificSynonymPropertyIsActuallySearched() throws Exception {
        OWLModelManager mm = fixture();
        String custom = "https://example.org/annotations/projectAlias";
        OWLClass term = lexical("ProjectTerm", custom, "Project-specific alias", "en");
        EntitySearch.Settings settings = new EntitySearch.Settings(Set.of(),
                Set.of(IRI.create(custom)), List.of("en"), List.of());

        Map<String, Object> result = EntitySearch.enrichedSearch(mm, "Project-specific alias",
                Collections.emptySet(), 0, 50, "class", settings);

        assertEquals(term.getIRI().toString(), items(result).get(0).get("iri"));
        assertEquals(custom, items(result).get(0).get("matched_property"));
        assertEquals("synonym", items(result).get(0).get("match_source"));
    }

    @Test
    void programmaticSettingsCannotBypassThePolicyLanguageBound() {
        assertThrows(IllegalArgumentException.class, () -> new EntitySearch.Settings(Set.of(), Set.of(),
                List.of("en", "de", "fr", "ko", "ja"), List.of()));
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
