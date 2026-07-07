package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Exercises the paginated windows {@link EntityJson#entityPage} / {@link EntityJson#axiomPage}: the
 * {@code count/offset/returned/next_offset} contract, the stable sort, and the offset-past-the-end edge.
 */
class EntityJsonPageTest {

    private static final String NS = "http://example.org/pg#";

    private OWLModelManager mmWith(OWLClass... classes) throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/pg"));
        for (OWLClass c : classes) {
            m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        }
        return FakeModelManager.over(o);
    }

    private OWLClass cls(String name) {
        return OWLManager.getOWLDataFactory().getOWLClass(IRI.create(NS + name));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void entityPageWindowsSortedEntitiesAndReportsNextOffset() throws OWLOntologyCreationException {
        OWLClass alpha = cls("Alpha");
        OWLClass bravo = cls("Bravo");
        OWLClass charlie = cls("Charlie");
        OWLClass delta = cls("Delta");
        OWLModelManager mm = mmWith(delta, bravo, alpha, charlie);   // insertion order shuffled
        Set<OWLClass> all = new LinkedHashSet<>(Arrays.asList(delta, bravo, alpha, charlie));

        Map<String, Object> page = EntityJson.entityPage(mm, all, 1, 2);
        assertEquals(4, page.get("count"), "count is the full size");
        assertEquals(1, page.get("offset"));
        assertEquals(2, page.get("returned"));
        assertEquals(3, page.get("next_offset"), "1 + 2 shown < 4 → more remain");
        // Sorted by display (short form): Alpha, Bravo, Charlie, Delta → window [1,3) = Bravo, Charlie.
        assertEquals("Bravo", items(page).get(0).get("display"));
        assertEquals("Charlie", items(page).get(1).get("display"));
    }

    @Test
    void entityPageIsStableAcrossPageBoundaries() throws OWLOntologyCreationException {
        OWLClass a = cls("Alpha");
        OWLClass b = cls("Bravo");
        OWLClass c = cls("Charlie");
        OWLModelManager mm = mmWith(a, b, c);
        Set<OWLClass> all = new LinkedHashSet<>(Arrays.asList(a, b, c));

        List<Object> paged = new ArrayList<>();
        Map<String, Object> p0 = EntityJson.entityPage(mm, all, 0, 2);
        for (Map<String, Object> it : items(p0)) {
            paged.add(it.get("display"));
        }
        Map<String, Object> p1 = EntityJson.entityPage(mm, all, (int) p0.get("next_offset"), 2);
        for (Map<String, Object> it : items(p1)) {
            paged.add(it.get("display"));
        }
        assertEquals(Arrays.asList("Alpha", "Bravo", "Charlie"), paged,
                "paging through covers every entity exactly once, in order");
        assertFalse(p1.containsKey("next_offset"), "the last page has no next_offset");
    }

    @Test
    void entityPageOffsetPastEndIsAnEmptyPageWithTheTrueCount() throws OWLOntologyCreationException {
        OWLClass a = cls("Alpha");
        OWLModelManager mm = mmWith(a);
        Map<String, Object> page = EntityJson.entityPage(mm, new LinkedHashSet<>(Arrays.asList(a)), 5, 10);
        assertEquals(1, page.get("count"));
        assertEquals(1, page.get("offset"), "offset is clamped to the size");
        assertEquals(0, page.get("returned"));
        assertTrue(items(page).isEmpty());
        assertFalse(page.containsKey("next_offset"));
    }

    @Test
    void zeroLimitReturnsNothingAndDoesNotEmitASelfReferentialCursor()
            throws OWLOntologyCreationException {
        OWLClass a = cls("Alpha");
        OWLClass b = cls("Bravo");
        OWLModelManager mm = mmWith(a, b);
        Map<String, Object> page = EntityJson.entityPage(mm, new LinkedHashSet<>(Arrays.asList(a, b)), 0, 0);
        assertEquals(2, page.get("count"));
        assertEquals(0, page.get("returned"));
        assertTrue(items(page).isEmpty());
        assertFalse(page.containsKey("next_offset"),
                "a zero-limit page must not advertise next_offset == offset (an infinite-loop cursor)");
    }

    @Test
    void nearMaxIntLimitWithOffsetDoesNotOverflowTheWindow() throws OWLOntologyCreationException {
        OWLClass a = cls("Alpha");
        OWLClass b = cls("Bravo");
        OWLClass c = cls("Charlie");
        OWLModelManager mm = mmWith(a, b, c);
        // offset=1 + a 'return everything' sentinel: off+max must not overflow to a negative window end.
        Map<String, Object> page = EntityJson.entityPage(mm,
                new LinkedHashSet<>(Arrays.asList(a, b, c)), 1, Integer.MAX_VALUE);
        assertEquals(3, page.get("count"));
        assertEquals(2, page.get("returned"), "the two remaining entities are returned, not dropped");
        assertFalse(page.containsKey("next_offset"), "the whole tail fit, so no next page");
    }

    @Test
    void entityPageSortIsLocaleIndependent() throws OWLOntologyCreationException {
        // Turkish locale folds capital 'I' to a dotless 'ı' (U+0131), which sorts AFTER lowercase 'i';
        // under Locale.ROOT "india" < "iron" so India sorts first regardless of the JVM default locale.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            OWLClass india = cls("India");
            OWLClass iron = cls("iron");
            OWLModelManager mm = mmWith(iron, india);
            Map<String, Object> page = EntityJson.entityPage(mm,
                    new LinkedHashSet<>(Arrays.asList(iron, india)), 0, 10);
            assertEquals("India", items(page).get(0).get("display"),
                    "the case-fold is Locale.ROOT, so the page order does not depend on the tr locale");
            assertEquals("iron", items(page).get(1).get("display"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void axiomPageWindowsAndPagesDeterministically() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/pg"));
        OWLClass animal = cls("Animal");
        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        for (String n : new String[] {"Dog", "Cat", "Fish", "Bird"}) {
            axioms.add(df.getOWLSubClassOfAxiom(cls(n), animal));
        }
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> p0 = EntityJson.axiomPage(mm, axioms, 0, 2);
        assertEquals(4, p0.get("count"));
        assertEquals(2, p0.get("returned"));
        assertEquals(2, p0.get("next_offset"));
        Map<String, Object> p1 = EntityJson.axiomPage(mm, axioms, 2, 2);
        assertEquals(2, p1.get("returned"));
        assertFalse(p1.containsKey("next_offset"));
        // The two pages together are the four distinct axioms, no repeats.
        Set<Object> renderings = new LinkedHashSet<>();
        for (Map<String, Object> it : items(p0)) {
            renderings.add(it.get("rendering"));
        }
        for (Map<String, Object> it : items(p1)) {
            renderings.add(it.get("rendering"));
        }
        assertEquals(4, renderings.size(), "paging yields four distinct axioms with no overlap");
    }
}
