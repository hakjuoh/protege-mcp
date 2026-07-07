package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Exercises the edit-versioned {@link SparqlSnapshotCache}: the get/store/staleness contract and the
 * separate asserted/inferred slots (directly), and the primary invalidation path — an ontology change
 * bumping the version so a stored snapshot is no longer served — via {@link FakeModelManager} (whose
 * real backing ontology manager fires the change listener the cache installs). The model-manager-event
 * path (classification, active-ontology switch) is EDT/Protégé-only and covered by the live smoke test.
 */
class SparqlSnapshotCacheTest {

    private static final Map<String, String> PREFIXES =
            Collections.singletonMap("ex:", "http://example.org/");
    private static final byte[] TURTLE = "@prefix ex: <http://example.org/> .".getBytes();

    @Test
    void storeThenGetReturnsTheEntryAtTheCurrentVersion() {
        SparqlSnapshotCache cache = new SparqlSnapshotCache();
        assertNull(cache.get(false), "a fresh cache is empty");
        SparqlSnapshotCache.Entry stored = cache.store(false, cache.version(), TURTLE, PREFIXES, null);
        SparqlSnapshotCache.Entry got = cache.get(false);
        assertNotNull(got, "an entry stamped with the current version is served");
        assertSame(stored, got);
        assertSame(TURTLE, got.turtle);
    }

    @Test
    void anEntryWhoseStampIsNotTheCurrentVersionIsStale() {
        SparqlSnapshotCache cache = new SparqlSnapshotCache();
        // Stamp it with a version that isn't the live one → treated as stale (never served).
        cache.store(false, cache.version() + 5, TURTLE, PREFIXES, null);
        assertNull(cache.get(false), "a stamp != current version is a miss");
    }

    @Test
    void invalidateForcesTheNextQueryToRebuild() {
        // set_prefix mutates the format's prefix map with no listener event → it calls invalidate().
        SparqlSnapshotCache cache = new SparqlSnapshotCache();
        cache.store(false, cache.version(), TURTLE, PREFIXES, null);
        assertNotNull(cache.get(false), "served before invalidation");
        cache.invalidate();
        assertNull(cache.get(false), "invalidate() advances the version so the stored entry is stale");
    }

    @Test
    void assertedAndInferredOccupySeparateSlots() {
        SparqlSnapshotCache cache = new SparqlSnapshotCache();
        cache.store(false, cache.version(), TURTLE, PREFIXES, null);
        assertNotNull(cache.get(false), "asserted slot populated");
        assertNull(cache.get(true), "inferred slot is independent and still empty");
        cache.store(true, cache.version(), TURTLE, PREFIXES, "note");
        assertNotNull(cache.get(true), "inferred slot populated");
        assertEquals("note", cache.get(true).note, "the inferred slot keeps its own note");
    }

    @Test
    void anOntologyChangeBumpsTheVersionAndInvalidatesAStoredSnapshot()
            throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology ont = m.createOntology(IRI.create("http://example.org/cache"));
        OWLModelManager mm = FakeModelManager.over(ont);

        SparqlSnapshotCache cache = new SparqlSnapshotCache();
        cache.installIfNeeded(mm);
        long v0 = cache.version();
        cache.store(false, v0, TURTLE, PREFIXES, null);
        assertNotNull(cache.get(false), "the snapshot is served before any edit");

        // A real change to the backing ontology fires the change listener the cache installed.
        m.addAxiom(ont, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create("http://example.org/A"))));

        assertTrue(cache.version() > v0, "an ontology change bumps the version");
        assertNull(cache.get(false), "the pre-edit snapshot is no longer served");
    }

    @Test
    void installIsIdempotent() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology ont = m.createOntology(IRI.create("http://example.org/cache2"));
        OWLModelManager mm = FakeModelManager.over(ont);

        SparqlSnapshotCache cache = new SparqlSnapshotCache();
        cache.installIfNeeded(mm);
        cache.installIfNeeded(mm);   // second call must not add a second change listener

        long v0 = cache.version();
        m.addAxiom(ont, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create("http://example.org/B"))));
        assertEquals(v0 + 1, cache.version(), "one change bumps the version exactly once (no dup listener)");
    }

    @Test
    void disposeRemovesListenersAndClearsEntries() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology ont = m.createOntology(IRI.create("http://example.org/cache3"));
        OWLModelManager mm = FakeModelManager.over(ont);

        SparqlSnapshotCache cache = new SparqlSnapshotCache();
        cache.installIfNeeded(mm);
        cache.store(false, cache.version(), TURTLE, PREFIXES, null);
        cache.dispose();

        assertNull(cache.get(false), "dispose clears cached snapshots");
        long vAfter = cache.version();
        m.addAxiom(ont, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create("http://example.org/C"))));
        assertEquals(vAfter, cache.version(), "after dispose the change listener no longer bumps");
    }
}
