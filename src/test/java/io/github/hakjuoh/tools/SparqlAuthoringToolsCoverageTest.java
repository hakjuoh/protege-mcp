package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hakjuoh.tools.SparqlAuthoringTools.QueryInspection;

/**
 * Supplementary coverage for the Protégé-free authoring helpers, filling the gaps left by
 * {@link SparqlAuthoringToolsTest}: the {@link SparqlAuthoringTools#isBuiltin} namespace check,
 * edge cases of {@link SparqlAuthoringTools#curie}/{@code curieOrIri} (null args, empty-namespace
 * entries, dot boundaries, safe punctuation), the exact shape/partial-null combinations of
 * {@link SparqlAuthoringTools#exampleQueries}, and additional {@link SparqlAuthoringTools#inspect}
 * forms (CONSTRUCT/DESCRIBE executability, negated property sets, whitespace/empty bodies, terms
 * used only as objects, and multi-var projection order).
 *
 * <p>The schema()/validate()/specs() pipelines and the private ref/termRef renderers need the live
 * Protégé runtime (plan {@code avoidMethods}) and are intentionally out of scope here.
 */
class SparqlAuthoringToolsCoverageTest {

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

    // ----------------------------------------------------------------- isBuiltin

    @Test
    void isBuiltinTrueForRdfRdfsOwlXsdNamespaces() {
        assertTrue(SparqlAuthoringTools.isBuiltin(SparqlAuthoringTools.RDF_NS + "type"),
                "rdf: namespace is built-in");
        assertTrue(SparqlAuthoringTools.isBuiltin(SparqlAuthoringTools.RDFS_NS + "subClassOf"),
                "rdfs: namespace is built-in");
        assertTrue(SparqlAuthoringTools.isBuiltin(SparqlAuthoringTools.OWL_NS + "Class"),
                "owl: namespace is built-in");
        assertTrue(SparqlAuthoringTools.isBuiltin(SparqlAuthoringTools.XSD_NS + "string"),
                "xsd: namespace is built-in");
    }

    @Test
    void isBuiltinFalseForDomainAndEmptyIri() {
        assertFalse(SparqlAuthoringTools.isBuiltin(EX + "Animal"), "a domain IRI is not built-in");
        assertFalse(SparqlAuthoringTools.isBuiltin(""), "the empty string is not built-in");
        // A near-miss that merely mentions rdf but does not start with the namespace.
        assertFalse(SparqlAuthoringTools.isBuiltin("http://example.org/rdf-syntax#x"),
                "an IRI that only contains but does not start with a built-in namespace is not built-in");
    }

    @Test
    void isBuiltinThrowsOnNull() {
        assertThrows(NullPointerException.class, () -> SparqlAuthoringTools.isBuiltin(null),
                "isBuiltin(null) dereferences the string and throws NPE");
    }

    // ----------------------------------------------------------------- curie edge cases

    @Test
    void curieReturnsNullForNullIriOrNullPrefixes() {
        assertNull(SparqlAuthoringTools.curie(null, prefixes()), "null IRI yields no CURIE");
        assertNull(SparqlAuthoringTools.curie(EX + "Animal", null), "null prefix map yields no CURIE");
    }

    @Test
    void curieSkipsPrefixEntriesWithNullOrEmptyNamespace() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("bad:", "");        // empty namespace must be skipped, not matched by startsWith("")
        p.put("nullns:", null);   // null namespace must be skipped without NPE
        p.put("ex:", EX);
        assertEquals("ex:Animal", SparqlAuthoringTools.curie(EX + "Animal", p),
                "empty/null-namespace prefix entries are ignored; the real prefix still matches");
    }

    @Test
    void curieReturnsNullWhenLocalPartStartsWithDot() {
        assertNull(SparqlAuthoringTools.curie(EX + ".foo", prefixes()),
                "a local name beginning with '.' is not CURIE-safe");
    }

    @Test
    void curieReturnsNullWhenLocalPartEndsWithDot() {
        assertNull(SparqlAuthoringTools.curie(EX + "foo.", prefixes()),
                "a local name ending with '.' is not CURIE-safe");
    }

    @Test
    void curieKeepsInteriorDotUnderscoreAndDigits() {
        assertEquals("ex:foo.bar", SparqlAuthoringTools.curie(EX + "foo.bar", prefixes()),
                "an interior dot is CURIE-safe");
        assertEquals("ex:foo_bar", SparqlAuthoringTools.curie(EX + "foo_bar", prefixes()),
                "an underscore is CURIE-safe");
        assertEquals("ex:a1_b-c.d", SparqlAuthoringTools.curie(EX + "a1_b-c.d", prefixes()),
                "a mix of digits, underscore, interior dash and interior dot is CURIE-safe");
    }

    @Test
    void curieRejectsSpaceAndHashInLocalPart() {
        assertNull(SparqlAuthoringTools.curie(EX + "a b", prefixes()),
                "a space is not CURIE-safe");
        assertNull(SparqlAuthoringTools.curie(EX + "a#b", prefixes()),
                "a hash is not CURIE-safe");
    }

    @Test
    void curieOrIriWrapsUnmatchedNamespaceInAngleBrackets() {
        assertEquals("<http://nowhere.example/T>",
                SparqlAuthoringTools.curieOrIri("http://nowhere.example/T", prefixes()),
                "no declared prefix covers this namespace, so the full IRI is angle-bracketed");
    }

    @Test
    void curieOrIriWrapsUnsafeLocalPartInAngleBrackets() {
        // The ex: prefix matches, but the local part has a slash — CURIE is unsafe, so fall back.
        assertEquals("<" + EX + "a/b>",
                SparqlAuthoringTools.curieOrIri(EX + "a/b", prefixes()),
                "a matching namespace with an unsafe local part still falls back to <IRI>");
    }

    // ----------------------------------------------------------------- exampleQueries shape

    @Test
    void exampleQueriesEachEntryHasNonEmptyTitleAndQuery() {
        for (Map<String, Object> ex : SparqlAuthoringTools.exampleQueries(null, null, null)) {
            assertTrue(ex.containsKey("title"), "every example has a title key");
            assertTrue(ex.containsKey("query"), "every example has a query key");
            assertFalse(String.valueOf(ex.get("title")).isEmpty(), "title is non-empty");
            assertFalse(String.valueOf(ex.get("query")).isEmpty(), "query is non-empty");
        }
    }

    @Test
    void exampleQueriesOmitsOnlyTheInstanceExampleWhenClassRefNull() {
        // objProp + dataProp present, class absent -> 4 generic + 2 grounded = 6.
        List<Map<String, Object>> ex =
                SparqlAuthoringTools.exampleQueries(null, "ex:hasOwner", "ex:age");
        assertEquals(6, ex.size(), "the class instance example is omitted, the two property ones remain");
        assertFalse(ex.stream().anyMatch(e -> String.valueOf(e.get("query")).contains(" a ex:")),
                "no 'instances of class' example is present");
        assertTrue(ex.stream().anyMatch(e -> String.valueOf(e.get("query")).contains("ex:hasOwner ?o")));
        assertTrue(ex.stream().anyMatch(e -> String.valueOf(e.get("query")).contains("ex:age ?value")));
    }

    @Test
    void exampleQueriesOmitsOnlyTheObjectPropertyExampleWhenObjRefNull() {
        List<Map<String, Object>> ex =
                SparqlAuthoringTools.exampleQueries("ex:Animal", null, "ex:age");
        assertEquals(6, ex.size(), "only the object-property example is omitted");
        assertTrue(ex.stream().anyMatch(e -> String.valueOf(e.get("query")).contains("?x a ex:Animal")));
        assertFalse(ex.stream().anyMatch(e -> String.valueOf(e.get("title")).startsWith("Subject/object")),
                "the object-property example title is absent");
    }

    @Test
    void exampleQueriesOmitsOnlyTheDataPropertyExampleWhenDataRefNull() {
        List<Map<String, Object>> ex =
                SparqlAuthoringTools.exampleQueries("ex:Animal", "ex:hasOwner", null);
        assertEquals(6, ex.size(), "only the data-property example is omitted");
        assertFalse(ex.stream().anyMatch(e -> String.valueOf(e.get("title")).startsWith("Values of")),
                "the data-property example title is absent");
    }

    @Test
    void exampleQueriesEmbedFullIriRefsVerbatim() {
        // A ref that is a full <IRI> (no CURIE available) is embedded verbatim into the query text.
        String iriRef = "<" + EX + "Animal>";
        List<Map<String, Object>> ex = SparqlAuthoringTools.exampleQueries(iriRef, null, null);
        assertTrue(ex.stream().anyMatch(e ->
                        String.valueOf(e.get("query")).contains("?x a " + iriRef)),
                "the <IRI> form of the class ref is embedded into the instances example");
    }

    @Test
    void exampleQueriesGenericFormsAreAlwaysPresentEvenWithAllRefs() {
        List<Map<String, Object>> ex =
                SparqlAuthoringTools.exampleQueries("ex:Animal", "ex:hasOwner", "ex:age");
        assertTrue(ex.stream().anyMatch(e ->
                "List all named classes".equals(e.get("title"))), "generic class-listing example present");
        assertTrue(ex.stream().anyMatch(e ->
                "Sample of all triples".equals(e.get("title"))), "generic all-triples example present");
    }

    // ----------------------------------------------------------------- inspect extra forms

    @Test
    void inspectConstructIsExecutableWithoutService() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "CONSTRUCT { ?s ex:p ?o } WHERE { ?s ex:p ?o }", prefixes());
        assertTrue(ins.valid);
        assertEquals("CONSTRUCT", ins.queryType);
        assertTrue(ins.executable, "a read-form CONSTRUCT with no SERVICE is executable");
        assertFalse(ins.isUpdate);
        assertNull(ins.parseError);
    }

    @Test
    void inspectDescribeIsExecutableWithoutService() {
        QueryInspection ins = SparqlAuthoringTools.inspect("DESCRIBE ex:Animal", prefixes());
        assertTrue(ins.valid);
        assertEquals("DESCRIBE", ins.queryType);
        assertTrue(ins.executable, "a DESCRIBE with no SERVICE is executable");
        assertTrue(ins.vars.isEmpty(), "vars are only populated for SELECT");
    }

    @Test
    void inspectSelectVarsPreserveProjectionOrder() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?zebra ?apple ?mango WHERE { ?zebra ex:p ?apple . ?apple ex:q ?mango }",
                prefixes());
        assertEquals(List.of("zebra", "apple", "mango"), ins.vars,
                "projected vars are reported in the query's own order, not sorted");
    }

    @Test
    void inspectCollectsIriUsedOnlyAsObject() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?s WHERE { ?s a ex:OnlyObjectTerm }", prefixes());
        assertTrue(ins.valid);
        assertTrue(ins.referencedIris.contains(EX + "OnlyObjectTerm"),
                "an IRI appearing only in object position is still collected");
    }

    @Test
    void inspectCollectsNegatedPropertySetPathPredicates() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?s ?o WHERE { ?s !(ex:skip1|ex:skip2) ?o }", prefixes());
        assertTrue(ins.valid, "a negated property set is valid SPARQL");
        assertTrue(ins.referencedIris.contains(EX + "skip1"),
                "predicates inside a negated property set are collected");
        assertTrue(ins.referencedIris.contains(EX + "skip2"),
                "all predicates of a negated property set are collected");
    }

    @Test
    void inspectDedupesRepeatedReferencedIri() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?s ?o WHERE { ?s ex:rel ?o . ?o ex:rel ?s }", prefixes());
        long count = ins.referencedIris.stream().filter((EX + "rel")::equals).count();
        assertEquals(1L, count, "a predicate used twice is reported once (collected into a set)");
    }

    @Test
    void inspectEmptyBodyIsInvalidNotUpdate() {
        QueryInspection ins = SparqlAuthoringTools.inspect("", prefixes());
        assertFalse(ins.valid, "an empty query body does not parse");
        assertFalse(ins.isUpdate, "an empty body is not a (zero-op) UPDATE");
        assertNull(ins.queryType);
    }

    @Test
    void inspectWhitespaceOnlyBodyIsInvalidNotUpdate() {
        QueryInspection ins = SparqlAuthoringTools.inspect("   \n\t  ", prefixes());
        assertFalse(ins.valid, "a whitespace-only body does not parse");
        assertFalse(ins.isUpdate);
        assertNull(ins.queryType);
    }

    @Test
    void inspectCommentOnlyBodyIsInvalidNotUpdate() {
        QueryInspection ins = SparqlAuthoringTools.inspect("# just a comment\n", prefixes());
        assertFalse(ins.valid, "a comment-only body parses as a zero-op UpdateRequest, reported invalid");
        assertFalse(ins.isUpdate, "a zero-operation UpdateRequest is not treated as a real UPDATE");
    }

    @Test
    void inspectDeleteWhereIsRecognisedAsUpdate() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "DELETE WHERE { ?s ex:gone ?o }", prefixes());
        assertTrue(ins.valid, "an UPDATE is valid SPARQL");
        assertTrue(ins.isUpdate);
        assertEquals("UPDATE", ins.queryType);
        assertFalse(ins.executable, "sparql_query never runs UPDATEs");
    }

    @Test
    void inspectWorksWithNullPrefixMapWhenQueryDeclaresItsOwn() {
        // withPrefixes must tolerate a null prefix map; a self-contained query still parses.
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "PREFIX foo: <http://foo.example/>\nSELECT ?s WHERE { ?s a foo:Thing }", null);
        assertTrue(ins.valid, "a fully self-prefixed query parses even with no ontology prefixes");
        assertEquals("SELECT", ins.queryType);
        assertTrue(ins.referencedIris.contains("http://foo.example/Thing"),
                "the query's own-prefixed term is collected");
    }

    @Test
    void inspectWorksWithEmptyPrefixMap() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "SELECT ?s WHERE { ?s <" + EX + "p> ?o }", Collections.emptyMap());
        assertTrue(ins.valid, "a query using only full <IRI>s parses with no ontology prefixes");
        assertTrue(ins.referencedIris.contains(EX + "p"));
    }

    @Test
    void inspectAskWithServiceIsValidButNotExecutable() {
        QueryInspection ins = SparqlAuthoringTools.inspect(
                "ASK { SERVICE <http://127.0.0.1:9/sparql> { ?s ?p ?o } }", prefixes());
        assertTrue(ins.valid, "an ASK containing SERVICE is syntactically valid");
        assertEquals("ASK", ins.queryType);
        assertTrue(ins.usesService);
        assertFalse(ins.executable, "SERVICE makes even an ASK non-executable for sparql_query");
    }
}
