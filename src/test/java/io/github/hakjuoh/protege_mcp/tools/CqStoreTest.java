package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Conformance + edge-case tests for the competency-question stores (F3): every store round-trips the
 * normalised model (proving {@code run} is convention-agnostic), isolates malformed input, honours the
 * manifest version/unknown-key contract, and the {@link CqStores} selection precedence holds. Ends with a
 * headless add → run → remove pipeline over a real in-memory ontology.
 */
class CqStoreTest {

    private OWLOntologyManager m;
    private OWLOntology o;

    /** A model manager whose active ontology is saved to {@code dir/cq.owl} (so a document folder exists). */
    private OWLModelManager saved(File dir) throws OWLOntologyCreationException {
        m = OWLManager.createOWLOntologyManager();
        o = m.createOntology(IRI.create("http://example.org/cq"));
        m.setOntologyDocumentIRI(o, IRI.create(new File(dir, "cq.owl").toURI()));
        return FakeModelManager.over(o);
    }

    private CqContext ctx(File dir) throws OWLOntologyCreationException {
        return CqContext.of(saved(dir));
    }

    private static CompetencyQuestion cq(String id, Expectation exp) {
        CompetencyQuestion q = new CompetencyQuestion();
        q.id = id;
        q.text = "Question " + id;
        q.type = "Validating";
        q.query = "SELECT ?s WHERE { ?s a ?t }";
        q.expected = exp;
        q.includeInferred = false;
        q.tags = new ArrayList<>(Arrays.asList("alpha", "beta"));
        return q;
    }

    // ================================================================== conformance

    @Test
    void robotDirConforms(@TempDir File dir) throws Exception {
        conformance(new RobotSparqlDirStore(), ctx(dir));
    }

    @Test
    void manifestConforms(@TempDir File dir) throws Exception {
        conformance(new SidecarManifestStore(), ctx(dir));
    }

    @Test
    void annotationsConforms(@TempDir File dir) throws Exception {
        conformance(new OntologyAnnotationsStore(), ctx(dir));
    }

    /** The shared round-trip every store must satisfy — the normalised model survives store I/O. */
    private void conformance(CqStore store, CqContext c) {
        assertFalse(store.detect(c), store.conventionId() + " detects nothing before any write");

        store.upsert(c, cq("CQ-1", Expectation.nonEmpty()));
        assertTrue(store.detect(c), "detected after the first write");
        CqStore.LoadResult lr = store.load(c);
        assertEquals(1, lr.ok.size());
        CompetencyQuestion loaded = lr.ok.get(0);
        assertEquals("CQ-1", loaded.id);
        assertEquals("SELECT ?s WHERE { ?s a ?t }", loaded.query);
        assertEquals(Expectation.Kind.NON_EMPTY, loaded.expected.kind);
        assertEquals(store.conventionId(), loaded.convention, "load tags the resolving convention");
        assertFalse(loaded.includeInferred, "include_inferred round-trips");
        assertTrue(loaded.tags.contains("alpha"), "tags round-trip");

        // Update by id (upsert semantics): still one, now COUNT.
        store.upsert(c, cq("CQ-1", Expectation.count(">=", 3)));
        assertEquals(1, store.load(c).ok.size(), "upsert replaces by id, not appends");
        assertEquals(Expectation.Kind.COUNT, store.load(c).ok.get(0).expected.kind);

        store.upsert(c, cq("CQ-2", Expectation.empty()));
        assertEquals(2, store.load(c).ok.size());

        assertTrue(store.remove(c, "CQ-1"));
        assertEquals(1, store.load(c).ok.size());
        assertFalse(store.remove(c, "CQ-1"), "removing a missing id is a no-op, not an error");
    }

    // ================================================================== replace-by-id (robot dir)

    @Test
    void robotDirUpsertReplacesByHeaderIdNotFilename(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        File cqs = new File(dir, "cqs");
        assertTrue(cqs.mkdirs());
        // Externally-authored ROBOT file: filename stem 'scoping', but the header id is 'CQ-3'.
        Files.write(new File(cqs, "scoping.rq").toPath(),
                "# id: CQ-3\n\nSELECT ?s WHERE { ?s a ?t }".getBytes(StandardCharsets.UTF_8));
        RobotSparqlDirStore store = new RobotSparqlDirStore();
        assertEquals(1, store.load(c).ok.size());

        store.upsert(c, cq("CQ-3", Expectation.count(">=", 1)));   // update the SAME id
        assertEquals(1, store.load(c).ok.size(), "replace-by-id must not create a duplicate");
        assertEquals(Expectation.Kind.COUNT, store.load(c).ok.get(0).expected.kind);
        assertFalse(new File(cqs, "CQ-3.rq").exists(), "no new CQ-3.rq — the original file was updated");
        assertTrue(new File(cqs, "scoping.rq").isFile(), "the original file remains, updated in place");
    }

    @Test
    void robotDirDistinctIdsThatSanitizeAlikeDoNotClobber(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        RobotSparqlDirStore store = new RobotSparqlDirStore();
        store.upsert(c, cq("CQ 1", Expectation.nonEmpty()));   // sanitizes to CQ_1
        store.upsert(c, cq("CQ/1", Expectation.empty()));      // also sanitizes to CQ_1
        assertEquals(2, store.load(c).ok.size(),
                "two distinct ids that sanitize to the same stem must not overwrite each other");
    }

    // ================================================================== malformed isolation

    @Test
    void robotDirSkipsMalformedRqButLoadsTheRest(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        new RobotSparqlDirStore().upsert(c, cq("Good", Expectation.nonEmpty()));
        File cqs = new File(dir, "cqs");
        Files.write(new File(cqs, "bad.rq").toPath(),
                "# id: bad\n\n".getBytes(StandardCharsets.UTF_8));   // header but no query body
        CqStore.LoadResult lr = new RobotSparqlDirStore().load(c);
        assertEquals(1, lr.ok.size(), "the well-formed .rq still loads");
        assertEquals(1, lr.skipped.size(), "the empty-query .rq is skipped, not fatal");
    }

    @Test
    void manifestSkipsMalformedEntry(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        File manifest = new File(dir, "cq-cqs.json");
        Files.write(manifest.toPath(), ("{\"version\":1,\"questions\":["
                + "{\"id\":\"ok\",\"query\":\"SELECT ?s WHERE {?s a ?t}\"},"
                + "{\"id\":\"bad\"}]}").getBytes(StandardCharsets.UTF_8));   // 'bad' has no query
        CqStore.LoadResult lr = new SidecarManifestStore().load(c);
        assertEquals(1, lr.ok.size());
        assertEquals(1, lr.skipped.size());
    }

    @Test
    void manifestRefusesNewerMajorVersionOnLoad(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        File manifest = new File(dir, "cq-cqs.json");
        Files.write(manifest.toPath(),
                "{\"version\":2,\"questions\":[]}".getBytes(StandardCharsets.UTF_8));
        CqStore.LoadResult lr = new SidecarManifestStore().load(c);
        assertTrue(lr.ok.isEmpty());
        assertEquals(1, lr.skipped.size(), "a newer major version is refused, not silently truncated");
    }

    @Test
    void manifestPreservesUnknownKeysOnRoundTrip(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        File manifest = new File(dir, "cq-cqs.json");
        Files.write(manifest.toPath(), ("{\"version\":1,\"projectNote\":\"keep me\",\"questions\":["
                + "{\"id\":\"CQ-9\",\"query\":\"SELECT ?s WHERE {?s a ?t}\",\"custom\":42}]}")
                .getBytes(StandardCharsets.UTF_8));
        // Upsert a NEW question; unknown top-level + unknown per-question keys must survive.
        new SidecarManifestStore().upsert(c, cq("CQ-10", Expectation.nonEmpty()));
        String json = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        assertTrue(json.contains("projectNote"), "unknown top-level key preserved");
        assertTrue(json.contains("keep me"));
        assertTrue(json.contains("custom"), "unknown per-question key preserved");
    }

    @Test
    void annotationsSkipsNonJsonAnnotation(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        CqContext c = CqContext.of(mm);
        OntologyAnnotationsStore store = new OntologyAnnotationsStore();
        store.upsert(c, cq("Good", Expectation.nonEmpty()));
        // A second CQ annotation whose literal is not JSON.
        OWLDataFactory df = m.getOWLDataFactory();
        m.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o,
                df.getOWLAnnotation(df.getOWLAnnotationProperty(IRI.create(Cq.ANNOTATION_IRI)),
                        df.getOWLLiteral("not json"))));
        CqStore.LoadResult lr = store.load(c);
        assertEquals(1, lr.ok.size());
        assertEquals(1, lr.skipped.size());
    }

    // ================================================================== selection precedence

    @Test
    void selectDefaultsToRobotDirWhenSavedAndNothingDetected(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        assertEquals(Cq.CONV_ROBOT, CqStores.selectForWrite(c, null).conventionId());
    }

    @Test
    void selectFallsBackToAnnotationsWhenUnsaved() throws Exception {
        // Never-saved ontology → no document folder → the file conventions cannot resolve a path.
        OWLOntologyManager mm2 = OWLManager.createOWLOntologyManager();
        OWLOntology onto = mm2.createOntology(IRI.create("http://example.org/unsaved"));
        CqContext c = CqContext.of(FakeModelManager.over(onto));
        assertEquals(Cq.CONV_ANNOTATIONS, CqStores.selectForWrite(c, null).conventionId());
    }

    @Test
    void explicitConventionWins(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        assertEquals(Cq.CONV_MANIFEST,
                CqStores.selectForWrite(c, Cq.CONV_MANIFEST).conventionId());
    }

    @Test
    void multipleDetectedRequiresExplicitConvention(@TempDir File dir) throws Exception {
        CqContext c = ctx(dir);
        new RobotSparqlDirStore().upsert(c, cq("CQ-1", Expectation.nonEmpty()));
        new SidecarManifestStore().upsert(c, cq("CQ-2", Expectation.nonEmpty()));
        assertThrows(ToolArgException.class, () -> CqStores.selectForWrite(c, null),
                "two present conventions must force an explicit choice");
    }

    @Test
    void explicitFileConventionRefusedWhenUnsaved() throws Exception {
        OWLOntologyManager mm2 = OWLManager.createOWLOntologyManager();
        OWLOntology onto = mm2.createOntology(IRI.create("http://example.org/unsaved"));
        CqContext c = CqContext.of(FakeModelManager.over(onto));
        assertThrows(ToolArgException.class, () -> CqStores.selectForWrite(c, Cq.CONV_ROBOT),
                "a file convention needs a saved document");
    }

    // ================================================================== headless run pipeline

    @Test
    void addRunRemovePipeline(@TempDir File dir) throws Exception {
        // Build a small classified-free ontology: Dog ⊑ Animal, both labelled.
        OWLModelManager mm = saved(dir);
        OWLDataFactory df = m.getOWLDataFactory();
        IRI animal = IRI.create("http://example.org/cq#Animal");
        IRI dog = IRI.create("http://example.org/cq#Dog");
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(animal)));
        m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(dog)));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(dog), df.getOWLClass(animal)));

        CqContext c = CqContext.of(mm);
        CompetencyQuestion q = new CompetencyQuestion();
        q.id = "subclasses-exist";
        q.query = "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> "
                + "<http://example.org/cq#Animal> }";
        q.expected = Expectation.nonEmpty();
        q.includeInferred = false;
        new RobotSparqlDirStore().upsert(c, q);

        // Load + snapshot + run — the convention-agnostic path.
        List<CompetencyQuestion> loaded = new RobotSparqlDirStore().load(c).ok;
        assertEquals(1, loaded.size());
        SuiteSnapshot snap = SuiteSnapshot.capture(mm, false);
        Map<String, Object> result = CqRunner.run(snap, loaded, 1000, 30_000, CqRunner.FAIL_ON_ANY);
        assertEquals(1, result.get("passed"), "Dog ⊑ Animal makes the CQ pass");
        assertEquals("pass", result.get("gate"));

        // Now demand EMPTY → the same data fails, and fail_on=any gates.
        loaded.get(0).expected = Expectation.empty();
        Map<String, Object> failing = CqRunner.run(snap, loaded, 1000, 30_000, CqRunner.FAIL_ON_ANY);
        assertEquals(1, failing.get("failed"));
        assertEquals("fail", failing.get("gate"));

        assertTrue(new RobotSparqlDirStore().remove(c, "subclasses-exist"));
        assertTrue(new RobotSparqlDirStore().load(c).ok.isEmpty());
    }
}
