package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Supplementary method-level coverage for {@link DiffTools} that fills the gaps left by
 * {@link DiffToolsTest} (which only exercises the two-partition happy path of {@code diff}).
 *
 * <p>Adds: edge cases of the pure {@code diff} set arithmetic (empty / single-side / order), the
 * {@link DiffTools.Diff} record contract, the private {@code collect} closure/logical-only matrix,
 * {@code labelOf} across all its branches, the {@code loadDocumentAxioms} success + error wrapping,
 * and the full private {@code diff(OWLModelManager, ...)} orchestration driven by the headless
 * {@link FakeModelManager} (identical round-trip, only-in-left/right counts, and the missing-operand
 * error branches). All Protégé-runtime-only reflection targets are reached through
 * {@code FakeModelManager}, whose {@code getRendering} falls back to {@code toString} for axioms.
 */
class DiffToolsCoverageTest {

    private static final String NS = "http://example.org/diff#";

    // ---------------------------------------------------------------- reflection helpers

    private static Method priv(String name, Class<?>... params) throws Exception {
        Method m = DiffTools.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Set<OWLAxiom> collect(OWLOntology o, boolean closure, boolean logicalOnly)
            throws Exception {
        return (Set<OWLAxiom>) priv("collect", OWLOntology.class, boolean.class, boolean.class)
                .invoke(null, o, closure, logicalOnly);
    }

    private static String labelOf(OWLOntologyID id) throws Exception {
        return (String) priv("labelOf", OWLOntologyID.class).invoke(null, id);
    }

    @SuppressWarnings("unchecked")
    private static Set<OWLAxiom> loadDocumentAxioms(String source, boolean closure, boolean logicalOnly)
            throws Throwable {
        try {
            return (Set<OWLAxiom>) priv("loadDocumentAxioms", String.class, boolean.class, boolean.class)
                    .invoke(null, source, closure, logicalOnly);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static CallToolResult callDiff(OWLModelManager mm, String leftRef, String rightRef,
            Set<OWLAxiom> preloadedRight, String rightLabel, boolean includeImports, boolean logicalOnly,
            int limit) throws Exception {
        Method m = priv("diff", OWLModelManager.class, String.class, String.class, Set.class,
                String.class, boolean.class, boolean.class, int.class);
        return (CallToolResult) m.invoke(null, mm, leftRef, rightRef, preloadedRight, rightLabel,
                includeImports, logicalOnly, limit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    private static OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    // ================================================================== diff(Set, Set)

    @Test
    void bothEmptySetsYieldEmptyDifferences() {
        DiffTools.Diff d = DiffTools.diff(new LinkedHashSet<>(), new LinkedHashSet<>());
        assertTrue(d.onlyLeft.isEmpty(), "no left-only axioms when both sides empty");
        assertTrue(d.onlyRight.isEmpty(), "no right-only axioms when both sides empty");
    }

    @Test
    void leftOnlyWhenRightEmpty() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLAxiom ax = df.getOWLDeclarationAxiom(cls(df, "A"));
        DiffTools.Diff d = DiffTools.diff(
                new LinkedHashSet<>(Arrays.asList(ax)), new LinkedHashSet<>());
        assertEquals(1, d.onlyLeft.size(), "the single left axiom is only-in-left");
        assertTrue(d.onlyLeft.contains(ax));
        assertTrue(d.onlyRight.isEmpty(), "nothing is only-in-right");
    }

    @Test
    void rightOnlyWhenLeftEmpty() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLAxiom ax = df.getOWLDeclarationAxiom(cls(df, "B"));
        DiffTools.Diff d = DiffTools.diff(
                new LinkedHashSet<>(), new LinkedHashSet<>(Arrays.asList(ax)));
        assertTrue(d.onlyLeft.isEmpty(), "nothing is only-in-left");
        assertEquals(1, d.onlyRight.size(), "the single right axiom is only-in-right");
        assertTrue(d.onlyRight.contains(ax));
    }

    @Test
    void sharedAxiomsAppearInNeitherDifference() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLAxiom shared1 = df.getOWLDeclarationAxiom(cls(df, "A"));
        OWLAxiom shared2 = df.getOWLSubClassOfAxiom(cls(df, "A"), df.getOWLThing());
        Set<OWLAxiom> left = new LinkedHashSet<>(Arrays.asList(shared1, shared2));
        Set<OWLAxiom> right = new LinkedHashSet<>(Arrays.asList(shared1, shared2));
        DiffTools.Diff d = DiffTools.diff(left, right);
        assertTrue(d.onlyLeft.isEmpty(), "shared axioms are not left differences");
        assertTrue(d.onlyRight.isEmpty(), "shared axioms are not right differences");
    }

    @Test
    void mixedCasePartitionsIntoThreeGroups() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLAxiom shared = df.getOWLDeclarationAxiom(a);
        OWLAxiom leftOnly = df.getOWLSubClassOfAxiom(a, cls(df, "L"));
        OWLAxiom rightOnly = df.getOWLSubClassOfAxiom(a, cls(df, "R"));
        Set<OWLAxiom> left = new LinkedHashSet<>(Arrays.asList(shared, leftOnly));
        Set<OWLAxiom> right = new LinkedHashSet<>(Arrays.asList(shared, rightOnly));
        DiffTools.Diff d = DiffTools.diff(left, right);
        assertEquals(1, d.onlyLeft.size(), "exactly one left-only axiom");
        assertTrue(d.onlyLeft.contains(leftOnly));
        assertEquals(1, d.onlyRight.size(), "exactly one right-only axiom");
        assertTrue(d.onlyRight.contains(rightOnly));
    }

    @Test
    void diffDoesNotMutateInputSets() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLAxiom l = df.getOWLDeclarationAxiom(cls(df, "A"));
        OWLAxiom r = df.getOWLDeclarationAxiom(cls(df, "B"));
        Set<OWLAxiom> left = new LinkedHashSet<>(Arrays.asList(l));
        Set<OWLAxiom> right = new LinkedHashSet<>(Arrays.asList(r));
        DiffTools.diff(left, right);
        assertEquals(1, left.size(), "diff must copy, not mutate, the left input");
        assertEquals(1, right.size(), "diff must copy, not mutate, the right input");
    }

    @Test
    void onlyLeftPreservesInsertionOrder() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLClass a = cls(df, "A");
        // Names chosen so alphabetic order differs from insertion order.
        OWLAxiom third = df.getOWLSubClassOfAxiom(a, cls(df, "Zoo"));
        OWLAxiom first = df.getOWLSubClassOfAxiom(a, cls(df, "Apple"));
        OWLAxiom second = df.getOWLSubClassOfAxiom(a, cls(df, "Mango"));
        Set<OWLAxiom> left = new LinkedHashSet<>(Arrays.asList(third, first, second));
        DiffTools.Diff d = DiffTools.diff(left, new LinkedHashSet<>());
        List<OWLAxiom> ordered = new ArrayList<>(d.onlyLeft);
        assertEquals(Arrays.asList(third, first, second), ordered,
                "onlyLeft is a LinkedHashSet preserving insertion order");
    }

    // ================================================================== Diff class

    @Test
    void diffRecordExposesBothSidesAsFinalFields() {
        OWLDataFactory df = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        Set<OWLAxiom> l = new LinkedHashSet<>(Arrays.asList(df.getOWLDeclarationAxiom(cls(df, "A"))));
        Set<OWLAxiom> r = new LinkedHashSet<>(Arrays.asList(df.getOWLDeclarationAxiom(cls(df, "B"))));
        DiffTools.Diff d = new DiffTools.Diff(l, r);
        assertEquals(new ArrayList<>(l), new ArrayList<>(d.onlyLeft),
                "constructor exposes the onlyLeft axioms in order");
        assertEquals(new ArrayList<>(r), new ArrayList<>(d.onlyRight),
                "constructor exposes the onlyRight axioms in order");
    }

    // ================================================================== collect(...)

    @Test
    void collectEmptyOntologyReturnsEmpty() throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/c-empty"));
        assertTrue(collect(o, false, false).isEmpty(), "an empty ontology has no axioms");
    }

    @Test
    void collectAllAxiomsNoClosureNoLogicalOnly() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/c-all"));
        OWLClass a = cls(df, "A");
        m.applyChange(new AddAxiom(o, df.getOWLDeclarationAxiom(a)));
        m.applyChange(new AddAxiom(o, df.getOWLSubClassOfAxiom(a, df.getOWLThing())));
        Set<OWLAxiom> got = collect(o, false, false);
        assertEquals(2, got.size(), "both the declaration and the subclass axiom are collected");
    }

    @Test
    void collectLogicalOnlyExcludesDeclarations() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/c-logical"));
        OWLClass a = cls(df, "A");
        OWLAxiom decl = df.getOWLDeclarationAxiom(a);
        OWLAxiom sub = df.getOWLSubClassOfAxiom(a, df.getOWLThing());
        m.applyChange(new AddAxiom(o, decl));
        m.applyChange(new AddAxiom(o, sub));
        Set<OWLAxiom> got = collect(o, false, true);
        assertEquals(1, got.size(), "logical_only drops the declaration axiom");
        assertTrue(got.contains(sub), "the logical subclass axiom survives");
        assertFalse(got.contains(decl), "the declaration axiom is filtered out");
    }

    @Test
    void collectDeclarationsOnlyLogicalOnlyYieldsEmpty() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/c-decl"));
        m.applyChange(new AddAxiom(o, df.getOWLDeclarationAxiom(cls(df, "A"))));
        m.applyChange(new AddAxiom(o, df.getOWLDeclarationAxiom(cls(df, "B"))));
        assertTrue(collect(o, false, true).isEmpty(),
                "a declarations-only ontology has no logical axioms");
    }

    @Test
    void collectLogicalOnlyExcludesAnnotationAssertions() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/c-anno"));
        OWLClass a = cls(df, "A");
        OWLAxiom sub = df.getOWLSubClassOfAxiom(a, df.getOWLThing());
        OWLAnnotationProperty comment = df.getRDFSComment();
        OWLAxiom anno = df.getOWLAnnotationAssertionAxiom(comment, a.getIRI(),
                df.getOWLLiteral("hi"));
        m.applyChange(new AddAxiom(o, sub));
        m.applyChange(new AddAxiom(o, anno));
        Set<OWLAxiom> got = collect(o, false, true);
        assertTrue(got.contains(sub), "the logical subclass axiom is kept");
        assertFalse(got.contains(anno), "logical_only excludes annotation assertions");
    }

    @Test
    void collectClosureIncludesImportedOntologyAxioms() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI importedIri = IRI.create("http://example.org/c-imported");
        OWLOntology imported = m.createOntology(importedIri);
        OWLClass i = cls(df, "Imported");
        OWLAxiom importedAx = df.getOWLDeclarationAxiom(i);
        m.applyChange(new AddAxiom(imported, importedAx));

        OWLOntology root = m.createOntology(IRI.create("http://example.org/c-root"));
        OWLAxiom rootAx = df.getOWLDeclarationAxiom(cls(df, "Root"));
        m.applyChange(new AddAxiom(root, rootAx));
        m.applyChange(new AddImport(root, df.getOWLImportsDeclaration(importedIri)));

        Set<OWLAxiom> noClosure = collect(root, false, false);
        assertEquals(1, noClosure.size(), "without closure only the root's own axioms are collected");
        assertFalse(noClosure.contains(importedAx), "imported axiom absent without closure");

        Set<OWLAxiom> closure = collect(root, true, false);
        assertTrue(closure.contains(rootAx), "closure keeps the root axiom");
        assertTrue(closure.contains(importedAx), "closure pulls in the imported axiom");
    }

    // ================================================================== labelOf(...)

    @Test
    void labelOfAnonymousOntology() throws Exception {
        OWLOntology anon = OWLManager.createOWLOntologyManager().createOntology();
        assertTrue(anon.getOntologyID().isAnonymous(), "sanity: created without IRI is anonymous");
        assertEquals("(anonymous ontology)", labelOf(anon.getOntologyID()),
                "an anonymous ontology renders as the fixed placeholder");
    }

    @Test
    void labelOfOntologyIriOnly() throws Exception {
        OWLOntology o = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/lbl"));
        assertEquals("http://example.org/lbl", labelOf(o.getOntologyID()),
                "ontology IRI is rendered verbatim when there is no version IRI");
    }

    @Test
    void labelOfOntologyIriWithVersionIri() throws Exception {
        OWLOntologyID id = new OWLOntologyID(
                IRI.create("http://example.org/lbl"),
                IRI.create("http://example.org/lbl/1.0"));
        assertEquals("http://example.org/lbl version http://example.org/lbl/1.0", labelOf(id),
                "both IRIs render as 'ontologyIRI version versionIRI'");
    }

    // ================================================================== loadDocumentAxioms(...)

    @Test
    void loadDocumentAxiomsWrapsCreationFailureInToolArgException() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"),
                "diff-tools-does-not-exist-" + System.nanoTime() + ".owl");
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> loadDocumentAxioms(missing.toString(), false, false),
                "a non-existent/unloadable document must surface as a ToolArgException, not a raw error");
        assertNotNull(ex.getMessage(), "the ToolArgException carries a descriptive message");
    }

    @Test
    void loadDocumentAxiomsParsesTempFileAndAppliesLogicalOnly() throws Throwable {
        Path doc = Files.createTempFile("diff-tools-load", ".owl");
        try {
            Files.writeString(doc, RDF_XML_TWO_AXIOMS);
            Set<OWLAxiom> all = loadDocumentAxioms(doc.toString(), false, false);
            assertNotNull(all, "a well-formed document yields a non-null axiom set");
            assertFalse(all.isEmpty(), "the parsed document has axioms");

            Set<OWLAxiom> logical = loadDocumentAxioms(doc.toString(), false, true);
            assertTrue(logical.size() < all.size(),
                    "logical_only drops the declaration, leaving fewer axioms than the full set");
        } finally {
            Files.deleteIfExists(doc);
        }
    }

    /** Minimal RDF/XML with one declaration and one subclass axiom (2 axioms, 1 logical). */
    private static final String RDF_XML_TWO_AXIOMS =
            "<?xml version=\"1.0\"?>\n"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
            + "         xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n"
            + "         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n"
            + "         xml:base=\"http://example.org/doc\">\n"
            + "  <owl:Ontology rdf:about=\"http://example.org/doc\"/>\n"
            + "  <owl:Class rdf:about=\"http://example.org/doc#A\">\n"
            + "    <rdfs:subClassOf rdf:resource=\"http://www.w3.org/2002/07/owl#Thing\"/>\n"
            + "  </owl:Class>\n"
            + "</rdf:RDF>\n";

    // ================================================================== diff(OWLModelManager, ...)

    private OWLOntology ontologyWith(OWLOntologyManager m, String iri, OWLAxiom... axioms)
            throws OWLOntologyCreationException {
        OWLOntology o = m.createOntology(IRI.create(iri));
        for (OWLAxiom ax : axioms) {
            m.applyChange(new AddAxiom(o, ax));
        }
        return o;
    }

    // Note: the {@code leftRef != null} / {@code rightRef != null} branches route through
    // {@code findLoaded(mm, ...)} which calls {@code mm.getOntologies()} — a method the headless
    // FakeModelManager intentionally does not implement (it needs the live Protégé runtime). So the
    // reference-resolution + not-found error branches are runtime-only; the covered cases below drive
    // the active-left + preloaded-right path, which is fully headless.

    @Test
    void diffMmIdenticalRoundTripReportsIdenticalTrue() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLAxiom decl = df.getOWLDeclarationAxiom(a);
        OWLAxiom sub = df.getOWLSubClassOfAxiom(a, df.getOWLThing());
        OWLOntology left = ontologyWith(m, "http://example.org/left", decl, sub);
        OWLModelManager mm = FakeModelManager.over(left);

        // Preloaded right axioms identical to the (active) left ontology's axioms.
        Set<OWLAxiom> right = new LinkedHashSet<>(Arrays.asList(decl, sub));
        CallToolResult r = callDiff(mm, null, null, right, "right-doc", false, false, 50);

        assertFalse(r.isError(), "a well-formed identical diff is not an error");
        Map<String, Object> s = structured(r);
        assertEquals(Boolean.TRUE, s.get("identical"), "identical axiom sets report identical=true");
        assertEquals(2, s.get("left_axioms"), "left has both axioms");
        assertEquals(2, s.get("right_axioms"), "right has both axioms");
        assertEquals("right-doc", s.get("right"), "the supplied right label is echoed back");
        assertEquals("http://example.org/left", s.get("left"),
                "left is labelled by its ontology IRI");
    }

    @Test
    void diffMmDifferingSidesReportsCountsAndNotIdentical() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLAxiom shared = df.getOWLDeclarationAxiom(a);
        OWLAxiom leftOnly = df.getOWLSubClassOfAxiom(a, cls(df, "L"));
        OWLAxiom rightOnly = df.getOWLSubClassOfAxiom(a, cls(df, "R"));
        OWLOntology left = ontologyWith(m, "http://example.org/left2", shared, leftOnly);
        OWLModelManager mm = FakeModelManager.over(left);

        Set<OWLAxiom> right = new LinkedHashSet<>(Arrays.asList(shared, rightOnly));
        CallToolResult r = callDiff(mm, null, null, right, "right-doc", false, false, 50);

        assertFalse(r.isError());
        Map<String, Object> s = structured(r);
        assertEquals(Boolean.FALSE, s.get("identical"), "differing sides are not identical");
        assertEquals(1, s.get("common"), "the shared declaration is the one common axiom");
        assertEquals(Boolean.FALSE, s.get("include_imports"), "include_imports flag echoed");
        assertEquals(Boolean.FALSE, s.get("logical_only"), "logical_only flag echoed");
        assertEquals(1, onlyCount(s, "only_in_left"), "one axiom only in left");
        assertEquals(1, onlyCount(s, "only_in_right"), "one axiom only in right");
    }

    @Test
    void diffMmLogicalOnlyFlagIsEchoedAndFiltersCollectedLeftAxioms() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLAxiom decl = df.getOWLDeclarationAxiom(a);
        OWLAxiom sub = df.getOWLSubClassOfAxiom(a, df.getOWLThing());
        OWLOntology left = ontologyWith(m, "http://example.org/left3", decl, sub);
        OWLModelManager mm = FakeModelManager.over(left);

        // Right preloaded with only the logical axiom; with logical_only the left declaration is
        // filtered out too, so the two sides match.
        Set<OWLAxiom> right = new LinkedHashSet<>(Arrays.asList(sub));
        CallToolResult r = callDiff(mm, null, null, right, "right-doc", false, true, 50);

        assertFalse(r.isError());
        Map<String, Object> s = structured(r);
        assertEquals(Boolean.TRUE, s.get("logical_only"), "logical_only flag echoed as true");
        assertEquals(1, s.get("left_axioms"), "logical_only drops the left declaration axiom");
        assertEquals(Boolean.TRUE, s.get("identical"),
                "logical-only diff of the sole subclass axiom is identical");
    }

    @SuppressWarnings("unchecked")
    private static int onlyCount(Map<String, Object> structured, String key) {
        Object side = structured.get(key);
        assertTrue(side instanceof Map, key + " is a {count, items} map");
        return ((Number) ((Map<String, Object>) side).get("count")).intValue();
    }

}
