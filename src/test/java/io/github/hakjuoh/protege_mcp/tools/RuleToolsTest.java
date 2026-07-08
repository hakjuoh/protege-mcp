package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;

/**
 * The fidelity-critical SWRL variable handling: a {@code ?name} argument must reconstruct the rule
 * variable's exact IRI (the reason structured atoms — not {@code ?x} text — are the right primitive),
 * and a rule built that way round-trips through OWL API with its named variables preserved.
 */
class RuleToolsTest {

    private static final String IOF_VAR = "https://spec.industrialontologies.org/ontology/rule/variable/";
    private static final String IOF_CONSTR = "https://spec.industrialontologies.org/ontology/construct/";

    @Test
    void variableNameExpandsAgainstNamespace() {
        assertEquals(IRI.create(IOF_VAR + "process1"), RuleTools.variableIri("?process1", IOF_VAR));
    }

    @Test
    void absoluteVariableIriIsUsedExactly() {
        assertEquals(IRI.create(IOF_VAR + "process1"),
                RuleTools.variableIri("?" + IOF_VAR + "process1", "urn:swrl#"));
    }

    @Test
    void variableNamespaceDefaultsWhenAbsent() {
        assertEquals(IRI.create(RuleTools.DEFAULT_VARIABLE_NS + "x"), RuleTools.variableIri("?x", null));
    }

    @Test
    void defaultVariableNamespaceIsSchemeValid() {
        // Regression for the FIBO-recon finding: the old default urn:swrl# minted variables like
        // urn:swrl#p, an invalid URN (urn:swrl has no NID:NSS colon) that made every Turtle/SPARQL
        // serialization log "Bad IRI ... SCHEME_PATTERN_MATCH_FAILED". A valid URN needs urn:<NID>:<NSS>.
        assertEquals("urn:swrl:var#", RuleTools.DEFAULT_VARIABLE_NS);
        String iri = RuleTools.variableIri("?p", null).toString();
        assertEquals("urn:swrl:var#p", iri);
        assertTrue(iri.startsWith("urn:"), "a URN scheme");
        String urnBody = iri.substring("urn:".length(), iri.indexOf('#'));   // "swrl:var"
        assertTrue(urnBody.contains(":"),
                "a valid URN carries NID:NSS (the old urn:swrl# had no colon before the fragment): " + iri);
    }

    @Test
    void nonVariableArgumentsAreNotVariables() {
        assertNull(RuleTools.variableIri("SomeIndividual", IOF_VAR));
        assertNull(RuleTools.variableIri("", IOF_VAR));
        assertNull(RuleTools.variableIri(null, IOF_VAR));
    }

    @Test
    void namedVariableRuleRoundTripsThroughOwlApi() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/rules"));

        SWRLVariable p1 = df.getSWRLVariable(RuleTools.variableIri("?process1", IOF_VAR));
        SWRLVariable p2 = df.getSWRLVariable(RuleTools.variableIri("?process2", IOF_VAR));
        OWLObjectProperty before = df.getOWLObjectProperty(IRI.create(IOF_CONSTR + "before"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLObjectPropertyAtom(before, p1, p2)));
        m.addAxiom(o, rule);

        assertTrue(o.containsAxiom(rule), "rule is asserted");
        Set<SWRLVariable> vars = rule.getVariables();
        assertTrue(vars.stream().anyMatch(v -> v.getIRI().equals(IRI.create(IOF_VAR + "process1"))),
                "named variable IRI iof-var:process1 is preserved, not collapsed to an anonymous ?x");
        assertFalse(vars.isEmpty());
    }

    @Test
    void predicateRefPrefersIriThenFallsBackToName() {
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("predicate", "before");
        both.put("predicate_iri", IOF_CONSTR + "before");
        assertEquals(IOF_CONSTR + "before", RuleTools.predicateRef(both),
                "predicate_iri wins, so list_rules output replays verbatim across ontologies");

        Map<String, Object> nameOnly = new LinkedHashMap<>();
        nameOnly.put("predicate", "before");
        assertEquals("before", RuleTools.predicateRef(nameOnly), "falls back to the display name");

        assertThrows(ToolArgException.class, () -> RuleTools.predicateRef(new LinkedHashMap<>()),
                "neither predicate nor predicate_iri present is a clear argument error");
    }

    @Test
    void builtinAndDataLiteralArgsPreserveTypeAndLang() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();

        OWLDatatype xsdInteger = df.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#integer"));
        Object typed = RuleTools.dArgJson(df.getSWRLLiteralArgument(df.getOWLLiteral("18", xsdInteger)));
        assertInstanceOf(Map.class, typed, "a typed literal keeps its datatype as {value, datatype}");
        assertEquals("18", ((Map<?, ?>) typed).get("value"));
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", ((Map<?, ?>) typed).get("datatype"));

        Object lang = RuleTools.dArgJson(df.getSWRLLiteralArgument(df.getOWLLiteral("hola", "es")));
        assertInstanceOf(Map.class, lang, "a lang literal keeps its tag as {value, lang}");
        assertEquals("es", ((Map<?, ?>) lang).get("lang"));

        Object plain = RuleTools.dArgJson(df.getSWRLLiteralArgument(df.getOWLLiteral("x")));
        assertInstanceOf(String.class, plain, "a plain xsd:string literal stays a bare string");
        assertEquals("x", plain);

        Object var = RuleTools.dArgJson(df.getSWRLVariable(IRI.create("urn:swrl#n")));
        assertEquals("?urn:swrl#n", var, "a variable is emitted as ?<IRI>");

        // A plain literal whose lexical value starts with '?' must be wrapped so replay keeps it a
        // literal (a bare "?foo" string would otherwise be parsed back as a variable).
        Object qLiteral = RuleTools.dArgJson(df.getSWRLLiteralArgument(df.getOWLLiteral("?foo")));
        assertInstanceOf(Map.class, qLiteral, "a plain literal starting with ? is wrapped {value}");
        assertEquals("?foo", ((Map<?, ?>) qLiteral).get("value"));
    }
}
