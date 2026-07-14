package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Supplementary coverage for {@link SparqlTools} that fills the gaps left by
 * {@link SparqlToolsTest}: the DESCRIBE form and CONSTRUCT/DESCRIBE truncation, empty/omitted-binding
 * SELECT results, blank-node and plain-string literal rendering, the {@link SparqlTools#withPrefixes}
 * skip/skip-null/empty-map branches, {@link SparqlTools#buildSnapshotOntology} anonymous-ID and
 * multi-ontology-closure behaviour, syntax-error and unsupported-form parse errors, and the
 * timeout<=0 path plus context-classloader restoration.
 */
class SparqlToolsCoverageTest {

    private static final String EX = "http://example.org/";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final long TIMEOUT = 60_000L;

    private static Map<String, String> prefixes() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ex:", EX);
        p.put("rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        p.put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        p.put("owl:", "http://www.w3.org/2002/07/owl#");
        p.put("xsd:", XSD);
        return p;
    }

    private static OWLOntology ontology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create(EX + "onto"));
        OWLClass animal = df.getOWLClass(IRI.create(EX + "Animal"));
        OWLClass dog = df.getOWLClass(IRI.create(EX + "Dog"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));
        OWLNamedIndividual rex = df.getOWLNamedIndividual(IRI.create(EX + "rex"));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(dog, rex));
        // a plain (untyped, no-lang) string literal — should render value-only (no lang/datatype)
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), rex.getIRI(),
                df.getOWLLiteral("plain")));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bindings(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("bindings");
    }

    // ---------------------------------------------------------------- execute: result forms

    @Test
    void describeReturnsTurtleGraphWithTripleCount() throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "DESCRIBE ex:rex", 1000, TIMEOUT);
        assertEquals("DESCRIBE", r.get("query_type"), "DESCRIBE must be reported as the query_type");
        assertTrue(r.get("count") instanceof Long, "graph count is a long triple count");
        assertTrue((Long) r.get("count") >= 1L, "rex has at least one asserted triple to describe");
        assertTrue(String.valueOf(r.get("turtle")).contains("rex"),
                "the describe graph should mention the rex subject");
        assertFalse(r.containsKey("truncated"), "a small graph must not be flagged truncated");
    }

    @Test
    void selectWithNoMatchesReturnsZeroCountEmptyBindingsAndVars()
            throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:NoSuchClass }", 1000, TIMEOUT);
        assertEquals("SELECT", r.get("query_type"));
        assertEquals(0, r.get("count"), "an empty result set reports count 0");
        assertTrue(bindings(r).isEmpty(), "no rows means an empty bindings list");
        assertEquals(List.of("s"), r.get("vars"), "the projected vars survive an empty result");
        assertFalse(r.containsKey("truncated"), "an empty result is not truncated");
    }

    @Test
    void selectOmitsVariablesWithNoBindingFromTheRow() throws OWLOntologyCreationException {
        // ?missing is projected but never bound (OPTIONAL that never matches), so it must be
        // absent from the row map rather than present with a null value.
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?s ?missing WHERE { ?s rdfs:subClassOf ex:Animal "
                        + "OPTIONAL { ?s ex:noSuchProp ?missing } }", 1000, TIMEOUT);
        assertEquals(1, r.get("count"));
        Map<String, Object> row = bindings(r).get(0);
        assertTrue(row.containsKey("s"), "the bound variable is present");
        assertFalse(row.containsKey("missing"), "an unbound projected variable is omitted from the row");
    }

    @Test
    void blankNodeRendersAsBnodeWithLabel() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create(EX + "bn"));
        // an anonymous individual produces a blank node in the RDF rendering
        OWLClass thing = df.getOWLClass(IRI.create(EX + "Thing"));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(thing,
                df.getOWLAnonymousIndividual()));
        Map<String, Object> r = SparqlTools.execute(o, prefixes(),
                "SELECT ?s WHERE { ?s a ex:Thing }", 1000, TIMEOUT);
        assertEquals(1, r.get("count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) bindings(r).get(0).get("s");
        assertEquals("bnode", s.get("type"), "an anonymous individual is a blank node");
        assertNotNull(s.get("value"), "a blank node reports its label string as value");
    }

    @Test
    void plainStringLiteralReportsValueOnly() throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?c WHERE { ex:rex rdfs:comment ?c }", 1000, TIMEOUT);
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) bindings(r).get(0).get("c");
        assertEquals("literal", c.get("type"));
        assertEquals("plain", c.get("value"));
        assertFalse(c.containsKey("lang"), "a plain string carries no lang tag");
        assertFalse(c.containsKey("datatype"), "xsd:string is the implicit default and is omitted");
    }

    @Test
    void constructTruncationSortsCapsAndFlagsShown() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create(EX + "manyC"));
        OWLClass animal = df.getOWLClass(IRI.create(EX + "Animal"));
        for (String name : new String[] {"Dog", "Cat", "Fish"}) {
            m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(EX + name)), animal));
        }
        Map<String, Object> r = SparqlTools.execute(o, prefixes(),
                "CONSTRUCT { ?s a ex:Animal } WHERE { ?s rdfs:subClassOf ex:Animal }", 2, TIMEOUT);
        assertEquals("CONSTRUCT", r.get("query_type"));
        assertEquals(3L, r.get("count"), "count reports the full graph size, not the shown subset");
        assertEquals(Boolean.TRUE, r.get("truncated"), "an over-limit graph is flagged truncated");
        assertEquals(2, r.get("shown"), "shown reports the limit that was applied");
    }

    @Test
    void constructTruncationIsDeterministicAcrossRuns() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create(EX + "det"));
        OWLClass animal = df.getOWLClass(IRI.create(EX + "Animal"));
        for (String name : new String[] {"Dog", "Cat", "Fish", "Bird", "Ant"}) {
            m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(EX + name)), animal));
        }
        String q = "CONSTRUCT { ?s a ex:Animal } WHERE { ?s rdfs:subClassOf ex:Animal }";
        Map<String, Object> a = SparqlTools.execute(o, prefixes(), q, 2, TIMEOUT);
        Map<String, Object> b = SparqlTools.execute(o, prefixes(), q, 2, TIMEOUT);
        assertEquals(a.get("turtle"), b.get("turtle"),
                "the truncated subset must be sorted so it is stable run-to-run");
    }

    // ---------------------------------------------------------------- execute: parse / error branches

    @Test
    void syntaxErrorIsWrappedWithCapabilityNote() throws OWLOntologyCreationException {
        OWLOntology o = ontology();
        ToolArgException ex = assertThrows(ToolArgException.class, () -> SparqlTools.execute(o,
                prefixes(), "SELECT ?s WHERE { ?s ?p", 1000, TIMEOUT),
                "an unclosed group must fail to parse");
        assertTrue(ex.getMessage().contains("SPARQL query error"),
                "the wrapped message should lead with the engine error");
        assertTrue(ex.getMessage().contains("SELECT, ASK, CONSTRUCT, or DESCRIBE"),
                "the wrapped message should state the read-only capability note");
    }

    @Test
    void nonQueryTextIsRejectedAsAParseError() throws OWLOntologyCreationException {
        OWLOntology o = ontology();
        assertThrows(ToolArgException.class, () -> SparqlTools.execute(o, prefixes(),
                "this is not sparql at all", 1000, TIMEOUT),
                "garbage input must be rejected at the parse stage");
    }

    @Test
    void nestedServiceInsideOptionalIsRejected() throws OWLOntologyCreationException {
        OWLOntology o = ontology();
        assertThrows(ToolArgException.class, () -> SparqlTools.execute(o, prefixes(),
                "SELECT ?s WHERE { ?s a ex:Animal "
                        + "OPTIONAL { SERVICE <http://127.0.0.1:9/sparql> { ?s ?p ?o } } }",
                1000, TIMEOUT),
                "a SERVICE clause nested in OPTIONAL must still be rejected");
    }

    // ---------------------------------------------------------------- execute: timeout / classloader

    @Test
    void nonPositiveTimeoutStillExecutesWithoutAnArqTimeout() throws OWLOntologyCreationException {
        // timeoutMs <= 0 skips qe.setTimeout; the query must still run and return results.
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, 0L);
        assertEquals(1, r.get("count"), "a zero timeout must not disable the query, only the timeout");
    }

    @Test
    void negativeTimeoutIsIgnored() throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, -5L);
        assertEquals(1, r.get("count"));
    }

    @Test
    void contextClassloaderIsRestoredAfterSuccess() throws OWLOntologyCreationException {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, TIMEOUT);
        assertSame(before, Thread.currentThread().getContextClassLoader(),
                "the thread-context classloader must be restored to its prior value on success");
    }

    @Test
    void contextClassloaderIsRestoredAfterParseFailure() throws OWLOntologyCreationException {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        OWLOntology o = ontology();
        assertThrows(ToolArgException.class, () -> SparqlTools.execute(o, prefixes(),
                "SELECT ?s WHERE { ?s ?p", 1000, TIMEOUT));
        assertSame(before, Thread.currentThread().getContextClassLoader(),
                "the finally block must restore the classloader even when the query fails");
    }

    // ---------------------------------------------------------------- withPrefixes branches

    @Test
    void withPrefixesReturnsQueryUnchangedForNullMap() {
        String q = "SELECT * WHERE { ?s ?p ?o }";
        assertSame(q, SparqlTools.withPrefixes(q, null),
                "a null prefix map returns the exact same query reference");
    }

    @Test
    void withPrefixesReturnsQueryUnchangedForEmptyMap() {
        String q = "SELECT * WHERE { ?s ?p ?o }";
        assertSame(q, SparqlTools.withPrefixes(q, new LinkedHashMap<>()),
                "an empty prefix map returns the exact same query reference");
    }

    @Test
    void withPrefixesSkipsNullNameNullNamespaceAndEmptyNamespace() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(null, EX);                 // null name -> skipped
        p.put("nullns:", null);          // null namespace -> skipped
        p.put("empty:", "");             // empty namespace -> skipped
        p.put("ex:", EX);                // the only valid one
        String out = SparqlTools.withPrefixes("SELECT * WHERE { ?s a ex:Animal }", p);
        assertTrue(out.contains("PREFIX ex: <" + EX + ">"), "the valid prefix is emitted");
        assertFalse(out.contains("nullns:"), "a null-namespace prefix is skipped");
        assertFalse(out.contains("empty:"), "an empty-namespace prefix is skipped");
    }

    @Test
    void withPrefixesReturnsBodyUnchangedWhenEveryEntryIsSkipped() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("bad:", "");   // skipped -> prologue stays empty -> body returned unchanged
        String q = "SELECT * WHERE { ?s ?p ?o }";
        assertEquals(q, SparqlTools.withPrefixes(q, p),
                "when no prefix produces a line, the original body is returned");
    }

    @Test
    void withPrefixesEmitsEveryValidPrefixInInsertionOrder() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("a:", "http://a/");
        p.put("b:", "http://b/");
        String out = SparqlTools.withPrefixes("SELECT * {}", p);
        int ai = out.indexOf("PREFIX a: <http://a/>");
        int bi = out.indexOf("PREFIX b: <http://b/>");
        assertTrue(ai >= 0 && bi >= 0, "both prefixes are emitted");
        assertTrue(ai < bi, "prefixes are emitted in the map's insertion order");
    }

    // ---------------------------------------------------------------- buildSnapshotOntology

    @Test
    void snapshotWithAnonymousIdCreatesAnAnonymousOntology() throws OWLOntologyCreationException {
        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, new OWLOntologyID(),
                java.util.Set.of());
        assertTrue(iso.getOntologyID().isAnonymous(),
                "an anonymous active id yields an anonymous snapshot");
    }

    @Test
    void snapshotWithNullIdCreatesAnAnonymousOntology() throws OWLOntologyCreationException {
        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, null, java.util.Set.of());
        assertTrue(iso.getOntologyID().isAnonymous(),
                "a null active id is treated like an anonymous one");
    }

    @Test
    void snapshotOverEmptyClosureHasNoAxioms() throws OWLOntologyCreationException {
        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv,
                new OWLOntologyID(com.google.common.base.Optional.of(IRI.create(EX + "e")),
                        com.google.common.base.Optional.absent()),
                java.util.Set.of());
        assertTrue(iso.getAxioms().isEmpty(), "an empty closure produces no axioms");
        assertEquals(IRI.create(EX + "e"), iso.getOntologyID().getOntologyIRI().orNull(),
                "the supplied non-anonymous id is preserved");
    }

    @Test
    void snapshotMergesAxiomsAndAnnotationsFromEveryClosureOntology()
            throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology a = m.createOntology(IRI.create(EX + "a"));
        OWLOntology b = m.createOntology(IRI.create(EX + "b"));
        m.addAxiom(a, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(EX + "Foo"))));
        m.applyChange(new AddOntologyAnnotation(a,
                df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("a-note"))));
        OWLObjectProperty rel = df.getOWLObjectProperty(IRI.create(EX + "rel"));
        m.addAxiom(b, df.getOWLDeclarationAxiom(rel));
        m.applyChange(new AddOntologyAnnotation(b,
                df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("b-label"))));

        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, a.getOntologyID(),
                new java.util.LinkedHashSet<>(java.util.Arrays.asList(a, b)));

        assertTrue(iso.containsClassInSignature(IRI.create(EX + "Foo")),
                "axioms from the first closure ontology are merged");
        assertTrue(iso.containsObjectPropertyInSignature(IRI.create(EX + "rel")),
                "axioms from the second closure ontology are merged");
        assertEquals(2, iso.getAnnotations().size(),
                "the ontology-level annotations from both closure ontologies are copied");
    }

    // ---------------------------------------------------------------- empty ontology query

    @Test
    void emptyOntologyYieldsEmptySelectResult() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology(IRI.create(EX + "blank"));
        Map<String, Object> r = SparqlTools.execute(o, prefixes(),
                "SELECT ?s WHERE { ?s a ex:Animal }", 1000, TIMEOUT);
        assertEquals(0, r.get("count"), "querying an empty ontology returns no rows");
    }

    @Test
    void askOverEmptyOntologyIsFalse() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology(IRI.create(EX + "blank2"));
        Map<String, Object> r = SparqlTools.execute(o, prefixes(),
                "ASK { ?s a ex:Animal }", 1000, TIMEOUT);
        assertEquals(Boolean.FALSE, r.get("boolean"), "ASK over an empty ontology is false");
    }
}
