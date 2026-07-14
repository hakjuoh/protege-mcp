package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.protege_mcp.tools.SparqlAuthoringTools.QueryInspection;

/**
 * Exercises the Protégé-free authoring helpers: CURIE compression (longest match, unsafe locals),
 * grounded example queries, and {@link SparqlAuthoringTools#inspect} across the read forms plus the
 * UPDATE / SERVICE / syntax-error / property-path / unknown-term cases that drive sparql_validate.
 */
class SparqlAuthoringToolsTest {

    private static final String EX = "http://example.org/";

    private static Map<String, String> prefixes() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ex:", EX);
        p.put("exsub:", EX + "sub/");
        p.put("rdf:", SparqlAuthoringTools.RDF_NS);
        p.put("rdfs:", SparqlAuthoringTools.RDFS_NS);
        p.put("owl:", SparqlAuthoringTools.OWL_NS);
        p.put("xsd:", SparqlAuthoringTools.XSD_NS);
        return p;
    }

    // ----------------------------------------------------------------- curie

    @Test
    void curieUsesTheLongestMatchingNamespace() {
        // both ex: and exsub: prefix the IRI; the longer (exsub:) wins.
        assertEquals("exsub:Thing", SparqlAuthoringTools.curie(EX + "sub/Thing", prefixes()));
        assertEquals("ex:Animal", SparqlAuthoringTools.curie(EX + "Animal", prefixes()));
    }

    @Test
    void curieIsNullWhenNoPrefixMatchesOrLocalIsUnsafe() {
        assertNull(SparqlAuthoringTools.curie("http://other.example/Thing", prefixes()),
                "no declared prefix covers this namespace");
        assertNull(SparqlAuthoringTools.curie(EX + "a/b", prefixes()),
                "a slash in the local part is not CURIE-safe");
        assertNull(SparqlAuthoringTools.curie(EX, prefixes()),
                "an empty local part has no CURIE");
    }

    @Test
    void curieOrIriFallsBackToAngleBracketedIri() {
        assertEquals("ex:Animal", SparqlAuthoringTools.curieOrIri(EX + "Animal", prefixes()));
        assertEquals("<http://other.example/X>",
                SparqlAuthoringTools.curieOrIri("http://other.example/X", prefixes()));
    }

    @Test
    void curieRejectsLeadingDashLocalNameWhichWouldNotParse() {
        // SPARQL PN_LOCAL forbids a leading '-', so emitting "ex:-foo" would fail to parse downstream;
        // fall back to the full <IRI>. A leading digit and a trailing dash ARE legal and kept.
        assertNull(SparqlAuthoringTools.curie(EX + "-foo", prefixes()));
        assertEquals("<" + EX + "-foo>", SparqlAuthoringTools.curieOrIri(EX + "-foo", prefixes()));
        assertEquals("ex:9foo", SparqlAuthoringTools.curie(EX + "9foo", prefixes()));
        assertEquals("ex:foo-", SparqlAuthoringTools.curie(EX + "foo-", prefixes()));
    }

    // ----------------------------------------------------------------- example queries

    @Test
    void exampleQueriesIncludeGenericFormsAndOmitMissingTerms() {
        List<Map<String, Object>> none = SparqlAuthoringTools.exampleQueries(null, null, null);
        // the three generic + the all-triples sample are always present
        assertEquals(4, none.size());

        List<Map<String, Object>> grounded =
                SparqlAuthoringTools.exampleQueries("ex:Animal", "ex:hasOwner", "ex:age");
        assertEquals(7, grounded.size());
        assertTrue(grounded.stream().anyMatch(e ->
                String.valueOf(e.get("query")).contains("?x a ex:Animal")));
        assertTrue(grounded.stream().anyMatch(e ->
                String.valueOf(e.get("query")).contains("ex:hasOwner ?o")));
    }

    // ----------------------------------------------------------------- inspect

    @Test
    void inspectReportsSelectFormVarsAndReferencedTerms() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?s ?o WHERE { ?s ex:hasOwner ?o . ?s a ex:Dog }", prefixes());
        assertTrue(ins.valid);
        assertEquals("SELECT", ins.queryType);
        assertTrue(ins.executable);
        assertFalse(ins.usesService);
        assertEquals(List.of("s", "o"), ins.vars);
        // rdf:type ('a') is built-in and filtered out; the ex: terms remain.
        assertTrue(ins.referencedIris.contains(EX + "hasOwner"));
        assertTrue(ins.referencedIris.contains(EX + "Dog"));
        assertFalse(ins.referencedIris.contains(SparqlAuthoringTools.RDF_NS + "type"),
                "built-in rdf/rdfs/owl/xsd terms must not be reported as referenced");
    }

    @Test
    void inspectResolvesDeclaredPrefixesWithoutOwnPrefixLines() {
        // No PREFIX line in the query: the ontology prefixes are auto-prepended, so it parses.
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "ASK { ex:Dog rdfs:subClassOf ex:Animal }", prefixes());
        assertTrue(ins.valid);
        assertEquals("ASK", ins.queryType);
        assertTrue(ins.executable);
        assertTrue(ins.referencedIris.contains(EX + "Animal"));
    }

    @Test
    void inspectCollectsPropertyPathPredicates() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?s WHERE { ?s ex:partOf+ / ex:locatedIn ?o }", prefixes());
        assertTrue(ins.valid);
        assertTrue(ins.referencedIris.contains(EX + "partOf"), "a + path predicate is collected");
        assertTrue(ins.referencedIris.contains(EX + "locatedIn"), "a sequence path predicate is collected");
    }

    @Test
    void inspectCollectsDescribeTargetsAndConstructTemplateTerms() {
        // A bare DESCRIBE compiles to no graph pattern; its target IRI must still be checked.
        QueryInspection describe = SparqlAuthoringTools.inspect("DESCRIBE ex:Typo", prefixes());
        assertTrue(describe.valid);
        assertEquals("DESCRIBE", describe.queryType);
        assertTrue(describe.referencedIris.contains(EX + "Typo"),
                "the DESCRIBE target IRI is collected");

        // A CONSTRUCT head term that never appears in the WHERE clause must still be checked.
        QueryInspection construct = SparqlAuthoringTools.inspect(
                "CONSTRUCT { ?s ex:mintedPred ex:Obj } WHERE { ?s ex:p ?o }", prefixes());
        assertEquals("CONSTRUCT", construct.queryType);
        assertTrue(construct.referencedIris.contains(EX + "mintedPred"),
                "a CONSTRUCT-template predicate is collected");
        assertTrue(construct.referencedIris.contains(EX + "Obj"));
        assertTrue(construct.referencedIris.contains(EX + "p"));
    }

    @Test
    void inspectCollectsValuesBlockIris() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?c WHERE { VALUES ?c { ex:Anmal } ?s a ?c }", prefixes());
        assertTrue(ins.valid);
        assertTrue(ins.referencedIris.contains(EX + "Anmal"),
                "an IRI bound in a VALUES block is collected (inline graph data)");
    }

    @Test
    void inspectTreatsPrefixOnlyBodyAsInvalidNotUpdate() {
        // Only prefix declarations: parses as an UpdateRequest with zero operations, which is not a
        // real UPDATE — report it as a syntax error rather than a phantom UPDATE.
        QueryInspection ins = SparqlAuthoringTools.inspect("PREFIX foo: <http://foo.example/>", prefixes());
        assertFalse(ins.valid);
        assertFalse(ins.isUpdate);
        assertNull(ins.queryType);
        assertTrue(ins.parseError != null && !ins.parseError.isEmpty());
    }

    @Test
    void inspectFlagsServiceAsNotExecutable() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?s WHERE { SERVICE <http://127.0.0.1:9/sparql> { ?s ?p ?o } }", prefixes());
        assertTrue(ins.valid, "a SERVICE query is syntactically valid SPARQL");
        assertTrue(ins.usesService);
        assertFalse(ins.executable, "sparql_query would reject SERVICE");
    }

    @Test
    void inspectRecognisesUpdateAsValidButNotExecutable() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "INSERT DATA { ex:x a ex:Animal }", prefixes());
        assertTrue(ins.valid, "an UPDATE is valid SPARQL");
        assertTrue(ins.isUpdate);
        assertEquals("UPDATE", ins.queryType);
        assertFalse(ins.executable, "sparql_query runs only read queries");
        assertNull(ins.parseError);
    }

    @Test
    void inspectReportsSyntaxErrorAsInvalidWithMessage() {
        QueryInspection ins = SparqlAuthoringTools.inspect("SELECT ?s WHERE { ?s ?p", prefixes());
        assertFalse(ins.valid);
        assertFalse(ins.isUpdate);
        assertFalse(ins.executable);
        assertNull(ins.queryType);
        assertTrue(ins.parseError != null && !ins.parseError.isEmpty(),
                "the engine's parse message is surfaced");
    }
}
