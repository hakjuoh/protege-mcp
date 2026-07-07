package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Exercises the Jena-backed SPARQL execution core against a hand-built ontology (no running Protégé):
 * the read query forms, literal datatype/lang fidelity, prefix auto-prepend (additive, case-safe),
 * row capping, and the UPDATE/SERVICE rejection that keeps editing on the write-tool path and the
 * engine off the network.
 */
class SparqlToolsTest {

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

    /** Animal ⊐ Dog ⊐ rex; Dog rdfs:label "Dog"@en; rex ex:age 3 (xsd:integer). */
    private static OWLOntology ontology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create(EX + "onto"));
        OWLClass animal = df.getOWLClass(IRI.create(EX + "Animal"));
        OWLClass dog = df.getOWLClass(IRI.create(EX + "Dog"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), dog.getIRI(),
                df.getOWLLiteral("Dog", "en")));
        OWLNamedIndividual rex = df.getOWLNamedIndividual(IRI.create(EX + "rex"));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(dog, rex));
        OWLDataProperty age = df.getOWLDataProperty(IRI.create(EX + "age"));
        OWLDatatype xsdInteger = df.getOWLDatatype(IRI.create(XSD + "integer"));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(age, rex, df.getOWLLiteral("3", xsdInteger)));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bindings(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("bindings");
    }

    @Test
    void selectResolvesOntologyPrefixesAndReturnsUriBindings() throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, TIMEOUT);
        assertEquals("SELECT", r.get("query_type"));
        assertEquals(1, r.get("count"));
        Map<String, Object> binding = bindings(r).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) binding.get("s");
        assertEquals("uri", s.get("type"));
        assertEquals(EX + "Dog", s.get("value"));
    }

    @Test
    void askIsTrueForAnAssertedTripleAndFalseOtherwise() throws OWLOntologyCreationException {
        Map<String, Object> yes = SparqlTools.execute(ontology(), prefixes(),
                "ASK { ex:Dog rdfs:subClassOf ex:Animal }", 1000, TIMEOUT);
        assertEquals("ASK", yes.get("query_type"));
        assertEquals(Boolean.TRUE, yes.get("boolean"));

        Map<String, Object> no = SparqlTools.execute(ontology(), prefixes(),
                "ASK { ex:Animal rdfs:subClassOf ex:Dog }", 1000, TIMEOUT);
        assertEquals(Boolean.FALSE, no.get("boolean"));
    }

    @Test
    void langTaggedLiteralKeepsItsTag() throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?l WHERE { ex:Dog rdfs:label ?l }", 1000, TIMEOUT);
        @SuppressWarnings("unchecked")
        Map<String, Object> l = (Map<String, Object>) bindings(r).get(0).get("l");
        assertEquals("literal", l.get("type"));
        assertEquals("Dog", l.get("value"));
        assertEquals("en", l.get("lang"));
        assertFalse(l.containsKey("datatype"), "a lang literal must not also report a datatype");
    }

    @Test
    void typedLiteralKeepsItsDatatype() throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "SELECT ?a WHERE { ex:rex ex:age ?a }", 1000, TIMEOUT);
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) bindings(r).get(0).get("a");
        assertEquals("3", a.get("value"));
        assertEquals(XSD + "integer", a.get("datatype"));
        assertFalse(a.containsKey("lang"));
    }

    @Test
    void constructReturnsTurtleGraph() throws OWLOntologyCreationException {
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "CONSTRUCT { ?s a ex:Animal } WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, TIMEOUT);
        assertEquals("CONSTRUCT", r.get("query_type"));
        assertEquals(1L, r.get("count"));
        assertTrue(String.valueOf(r.get("turtle")).contains("Dog"),
                "the constructed graph should mention the Dog subject");
    }

    @Test
    void limitCapsRowsAndFlagsTruncation() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create(EX + "many"));
        OWLClass animal = df.getOWLClass(IRI.create(EX + "Animal"));
        for (String name : new String[] {"Dog", "Cat", "Fish"}) {
            m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(EX + name)), animal));
        }
        Map<String, Object> r = SparqlTools.execute(o, prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1, TIMEOUT);
        assertEquals(1, r.get("count"));
        assertEquals(Boolean.TRUE, r.get("truncated"));
    }

    @Test
    void sparqlUpdateIsRejected() throws OWLOntologyCreationException {
        OWLOntology o = ontology();
        assertThrows(ToolArgException.class, () -> SparqlTools.execute(o, prefixes(),
                "INSERT DATA { <" + EX + "x> a <" + EX + "Animal> }", 1000, TIMEOUT),
                "SPARQL UPDATE must be rejected — edits go through the write tools");
    }

    @Test
    void serviceClauseIsRejected() throws OWLOntologyCreationException {
        OWLOntology o = ontology();
        assertThrows(ToolArgException.class, () -> SparqlTools.execute(o, prefixes(),
                "SELECT ?s WHERE { SERVICE <http://127.0.0.1:9/sparql> { ?s ?p ?o } }", 1000, TIMEOUT),
                "SPARQL SERVICE must be rejected so the engine never reaches the network");
    }

    @Test
    void differentlyCasedDeclaredPrefixStillResolvesTheOntologyPrefix()
            throws OWLOntologyCreationException {
        // The query declares an unrelated 'EX:'; the ontology's case-sensitive 'ex:' must still be
        // prepended (additive prefix handling) so 'ex:Animal' resolves rather than failing to parse.
        Map<String, Object> r = SparqlTools.execute(ontology(), prefixes(),
                "PREFIX EX: <http://unrelated.example/>\n"
                        + "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, TIMEOUT);
        assertEquals(1, r.get("count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) bindings(r).get(0).get("s");
        assertEquals(EX + "Dog", s.get("value"));
    }

    @Test
    void snapshotPreservesOntologyIriAndHeaderAnnotationsForQuerying()
            throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI ontIri = IRI.create(EX + "myOnt");
        OWLOntology src = m.createOntology(ontIri);
        // an ontology-level annotation — NOT an axiom, so getAxioms() alone would drop it
        m.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(src,
                df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("Header note", "en"))));
        m.addAxiom(src, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(EX + "Foo"))));

        OWLOntologyManager priv = OWLManager.createOWLOntologyManager();
        OWLOntology iso = SparqlTools.buildSnapshotOntology(priv, src.getOntologyID(),
                src.getImportsClosure());
        assertEquals(ontIri, iso.getOntologyID().getOntologyIRI().orNull(),
                "the snapshot must keep the real ontology IRI, not a synthetic one");
        assertTrue(iso.getAnnotations().stream()
                        .anyMatch(a -> a.getProperty().isComment()),
                "the ontology-level annotation must be carried into the snapshot");

        // and both must be visible to SPARQL
        Map<String, Object> r = SparqlTools.execute(iso, prefixes(),
                "SELECT ?o ?c WHERE { ?o a owl:Ontology ; rdfs:comment ?c }", 10, TIMEOUT);
        assertEquals(1, r.get("count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) bindings(r).get(0).get("o");
        assertEquals(EX + "myOnt", o.get("value"));
        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) bindings(r).get(0).get("c");
        assertEquals("Header note", c.get("value"));
    }

    @Test
    void executeTurtleOverCachedBytesMatchesExecuteOverTheOntology()
            throws OWLOntologyCreationException {
        // The cache fast path serialises once (toTurtleBytes) then re-parses per query (executeTurtle);
        // it must return exactly what execute(ontology, ...) does from a fresh snapshot.
        OWLOntology o = ontology();
        byte[] turtle = SparqlTools.toTurtleBytes(o);
        Map<String, Object> viaBytes = SparqlTools.executeTurtle(turtle, prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, TIMEOUT);
        Map<String, Object> viaOntology = SparqlTools.execute(o, prefixes(),
                "SELECT ?s WHERE { ?s rdfs:subClassOf ex:Animal }", 1000, TIMEOUT);
        assertEquals(viaOntology.get("count"), viaBytes.get("count"),
                "the cached-bytes path returns the same rows as a fresh snapshot");
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) bindings(viaBytes).get(0).get("s");
        assertEquals(EX + "Dog", s.get("value"));
    }

    @Test
    void withPrefixesIsAdditiveForEverySuppliedNamespace() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ex:", EX);
        String out = SparqlTools.withPrefixes("SELECT * WHERE { ?s a ex:Animal }", p);
        assertTrue(out.startsWith("PREFIX ex: <" + EX + ">"),
                "a supplied prefix should be prepended to the query prologue");
        assertTrue(out.endsWith("SELECT * WHERE { ?s a ex:Animal }"),
                "the original query body should be preserved after the prologue");
    }
}
