package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLDArgument;
import org.semanticweb.owlapi.model.SWRLIArgument;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;

/**
 * Supplementary coverage for {@link RuleTools} that the fidelity-focused {@code RuleToolsTest} leaves
 * open: the package-visible {@link RuleTools#ruleJson} SWRL→structured serializer (and the seven
 * {@code atomJson} instanceof branches it drives), {@link RuleTools#renderRule}, the
 * {@code variableIri} empty-name error branch, and additional {@code dArgJson}/{@code predicateRef}
 * edges. Rules are hand-built with {@link OWLDataFactory} and rendered through the headless
 * {@link FakeModelManager}, so no Protégé runtime is needed.
 */
class RuleToolsCoverageTest {

    private static final String NS = "http://example.org/rule#";
    private static final String VAR_NS = "urn:swrl#";

    private OWLOntologyManager mgr() {
        return OWLManager.createOWLOntologyManager();
    }

    private SWRLVariable var(OWLDataFactory df, String name) {
        return df.getSWRLVariable(IRI.create(VAR_NS + name));
    }

    // ------------------------------------------------------------------ variableIri error branch

    @Test
    void emptyVariableNameAfterQuestionMarkThrows() {
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> RuleTools.variableIri("?", VAR_NS),
                "a bare '?' has no variable name and is an argument error");
        assertTrue(ex.getMessage().contains("Empty variable name"),
                "message names the empty-variable problem: " + ex.getMessage());
    }

    @Test
    void whitespaceOnlyVariableNameThrows() {
        assertThrows(ToolArgException.class, () -> RuleTools.variableIri("?   ", VAR_NS),
                "a '?' followed by only whitespace trims to empty and is an error");
    }

    @Test
    void variableNameIsTrimmedBeforeExpansion() {
        assertEquals(IRI.create(VAR_NS + "x"), RuleTools.variableIri("?  x  ", VAR_NS),
                "surrounding whitespace around the name is trimmed before namespace expansion");
    }

    // ------------------------------------------------------------------ predicateRef edges

    @Test
    void predicateRefIgnoresEmptyPredicateIriAndFallsBack() {
        // optString returns null for an empty-string value, so predicate_iri="" falls through to the
        // display-name predicate rather than being used as an (empty) IRI.
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("predicate_iri", "");
        spec.put("predicate", "before");
        assertEquals("before", RuleTools.predicateRef(spec),
                "an empty predicate_iri is treated as absent and the name is used");
    }

    // ------------------------------------------------------------------ dArgJson additional edges

    @Test
    void dArgJsonPlainStringNotStartingWithQuestionMarkStaysBareString() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        Object out = RuleTools.dArgJson(df.getSWRLLiteralArgument(df.getOWLLiteral("hello")));
        assertInstanceOf(String.class, out, "an ordinary plain literal is a bare String");
        assertEquals("hello", out);
    }

    @Test
    void dArgJsonEmptyStringLiteralStaysBareString() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        Object out = RuleTools.dArgJson(df.getSWRLLiteralArgument(df.getOWLLiteral("")));
        assertInstanceOf(String.class, out, "an empty plain literal does not start with '?', so stays a String");
        assertEquals("", out);
    }

    @Test
    void dArgJsonVariableEmittedAsQuestionMarkIri() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        Object out = RuleTools.dArgJson(df.getSWRLVariable(IRI.create(NS + "n")));
        assertEquals("?" + NS + "n", out, "a d-variable is emitted as ?<IRI>");
    }

    // ------------------------------------------------------------------ renderRule

    @Test
    void renderRuleReturnsNonEmptyRenderingForRule() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/rr"));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLObjectProperty before = df.getOWLObjectProperty(IRI.create(NS + "before"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLObjectPropertyAtom(before, var(df, "x"), var(df, "y"))));

        String rendering = RuleTools.renderRule(mm, rule);
        assertNotNull(rendering, "renderRule never returns null");
        assertFalse(rendering.isEmpty(), "renderRule falls back to a non-empty rendering (rule.toString())");
        assertEquals(Tools.renderAxiom(mm, rule), rendering, "renderRule delegates to Tools.renderAxiom");
    }

    // ------------------------------------------------------------------ ruleJson: index handling

    @Test
    void ruleJsonOmitsNegativeIndexButKeepsNonNegative() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/idx"));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLObjectProperty before = df.getOWLObjectProperty(IRI.create(NS + "before"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLObjectPropertyAtom(before, var(df, "x"), var(df, "y"))));

        Map<String, Object> neg = RuleTools.ruleJson(mm, rule, -1);
        assertFalse(neg.containsKey("index"), "index=-1 is omitted from the JSON");

        Map<String, Object> pos = RuleTools.ruleJson(mm, rule, 3);
        assertEquals(3, pos.get("index"), "a non-negative index is emitted");

        Map<String, Object> zero = RuleTools.ruleJson(mm, rule, 0);
        assertEquals(0, zero.get("index"), "index=0 is a valid emitted index (boundary)");
    }

    @Test
    void ruleJsonHasBodyHeadVariablesAndRenderingKeys() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/keys"));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLObjectPropertyAtom(p, var(df, "x"), var(df, "y"))));

        Map<String, Object> json = RuleTools.ruleJson(mm, rule, -1);
        assertTrue(json.containsKey("body"), "always has a body list");
        assertTrue(json.containsKey("head"), "always has a head list");
        assertTrue(json.containsKey("variables"), "always has a variables list");
        assertTrue(json.containsKey("rendering"), "always has a rendering string");
        assertFalse(json.containsKey("annotations"), "no annotations key when the rule has none");
    }

    @Test
    void ruleJsonEmptyBodyIsAnEmptyList() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/emptybody"));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLClassAtom(c, var(df, "x"))));

        Map<String, Object> json = RuleTools.ruleJson(mm, rule, -1);
        assertEquals(Collections.emptyList(), json.get("body"), "an empty body serialises as an empty list");
        assertEquals(1, ((List<?>) json.get("head")).size(), "the head atom is present");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ruleJsonCollectsVariableIris() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/vars"));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLObjectPropertyAtom(p, var(df, "x"), var(df, "y"))));

        List<String> vars = (List<String>) RuleTools.ruleJson(mm, rule, -1).get("variables");
        assertTrue(vars.contains(VAR_NS + "x"), "variable x is listed by full IRI string");
        assertTrue(vars.contains(VAR_NS + "y"), "variable y is listed by full IRI string");
        assertEquals(2, vars.size(), "exactly the two rule variables are listed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ruleJsonEmitsAnnotationsWhenPresent() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/ann"));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        OWLAnnotation label = df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral("My Rule"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLClassAtom(c, var(df, "x"))),
                Collections.singleton(label));

        Map<String, Object> json = RuleTools.ruleJson(mm, rule, -1);
        assertTrue(json.containsKey("annotations"), "a rule with annotations emits the annotations key");
        List<Map<String, Object>> anns = (List<Map<String, Object>>) json.get("annotations");
        assertEquals(1, anns.size(), "exactly one rule-level annotation");
        assertEquals("My Rule", anns.get(0).get("value"), "the rdfs:label value round-trips");
    }

    // ------------------------------------------------------------------ atomJson via ruleJson: 7 branches

    private OWLClass declaredClass(OWLOntologyManager m, OWLOntology o, OWLDataFactory df, String name) {
        OWLClass c = df.getOWLClass(IRI.create(NS + name));
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        return c;
    }

    private Map<String, Object> onlyHeadAtomJson(OWLModelManager mm, OWLDataFactory df, SWRLAtom head) {
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(), Collections.singleton(head));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> heads = (List<Map<String, Object>>) RuleTools.ruleJson(mm, rule, -1).get("head");
        assertEquals(1, heads.size(), "exactly one head atom serialised");
        return heads.get(0);
    }

    @Test
    void atomJsonClassAtomEmitsTypePredicateAndArg() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cls"));
        OWLClass c = declaredClass(m, o, df, "Widget");
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> a = onlyHeadAtomJson(mm, df, df.getSWRLClassAtom(c, var(df, "x")));
        assertEquals("class", a.get("type"));
        assertEquals(NS + "Widget", a.get("predicate_iri"), "a named class exposes its predicate_iri");
        assertEquals("?" + VAR_NS + "x", a.get("arg1"), "the class argument is the variable as ?<IRI>");
    }

    @Test
    void atomJsonObjectPropertyAtomEmitsBothArgs() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/op"));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "before"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(p));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> a = onlyHeadAtomJson(mm, df,
                df.getSWRLObjectPropertyAtom(p, var(df, "x"), var(df, "y")));
        assertEquals("object_property", a.get("type"));
        assertEquals(NS + "before", a.get("predicate_iri"));
        assertEquals("?" + VAR_NS + "x", a.get("arg1"));
        assertEquals("?" + VAR_NS + "y", a.get("arg2"));
    }

    @Test
    void atomJsonDataPropertyAtomWithVariableSecondArgEmitsArg2() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/dpvar"));
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "hasAge"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dp));
        OWLModelManager mm = FakeModelManager.over(o);

        SWRLDArgument v = df.getSWRLVariable(IRI.create(VAR_NS + "age"));
        Map<String, Object> a = onlyHeadAtomJson(mm, df,
                df.getSWRLDataPropertyAtom(dp, var(df, "x"), v));
        assertEquals("data_property", a.get("type"));
        assertEquals(NS + "hasAge", a.get("predicate_iri"));
        assertEquals("?" + VAR_NS + "x", a.get("arg1"));
        assertEquals("?" + VAR_NS + "age", a.get("arg2"), "a variable second arg is emitted as arg2 (not value)");
        assertFalse(a.containsKey("value"), "no literal value key for a variable second argument");
    }

    @Test
    void atomJsonDataPropertyAtomWithPlainLiteralEmitsValueNoDatatype() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/dplit"));
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "hasName"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dp));
        OWLModelManager mm = FakeModelManager.over(o);

        SWRLDArgument lit = df.getSWRLLiteralArgument(df.getOWLLiteral("Rex"));
        Map<String, Object> a = onlyHeadAtomJson(mm, df,
                df.getSWRLDataPropertyAtom(dp, var(df, "x"), lit));
        assertEquals("data_property", a.get("type"));
        assertEquals("Rex", a.get("value"), "a plain literal second arg is emitted as value");
        assertFalse(a.containsKey("arg2"), "no arg2 key for a literal second argument");
        assertFalse(a.containsKey("datatype"), "a plain xsd:string literal carries no datatype");
        assertFalse(a.containsKey("lang"), "a plain literal carries no lang tag");
    }

    @Test
    void atomJsonDataPropertyAtomWithTypedLiteralEmitsDatatype() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/dptyped"));
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "hasAge"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dp));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLDatatype xsdInt = df.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#integer"));
        SWRLDArgument lit = df.getSWRLLiteralArgument(df.getOWLLiteral("7", xsdInt));
        Map<String, Object> a = onlyHeadAtomJson(mm, df,
                df.getSWRLDataPropertyAtom(dp, var(df, "x"), lit));
        assertEquals("7", a.get("value"));
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", a.get("datatype"),
                "a typed literal keeps its datatype so it round-trips");
        assertFalse(a.containsKey("lang"));
    }

    @Test
    void atomJsonDataPropertyAtomWithLangLiteralEmitsLang() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/dplang"));
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "hasName"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dp));
        OWLModelManager mm = FakeModelManager.over(o);

        SWRLDArgument lit = df.getSWRLLiteralArgument(df.getOWLLiteral("hola", "es"));
        Map<String, Object> a = onlyHeadAtomJson(mm, df,
                df.getSWRLDataPropertyAtom(dp, var(df, "x"), lit));
        assertEquals("hola", a.get("value"));
        assertEquals("es", a.get("lang"), "a lang literal keeps its tag");
        assertFalse(a.containsKey("datatype"), "a lang literal is not also given a datatype");
    }

    @Test
    void atomJsonSameIndividualAtomEmitsSameAs() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/same"));
        OWLModelManager mm = FakeModelManager.over(o);

        SWRLIArgument a1 = var(df, "a");
        SWRLIArgument a2 = var(df, "b");
        Map<String, Object> a = onlyHeadAtomJson(mm, df, df.getSWRLSameIndividualAtom(a1, a2));
        assertEquals("same_as", a.get("type"), "a same-individual atom serialises as same_as");
        assertEquals("?" + VAR_NS + "a", a.get("arg1"));
        assertEquals("?" + VAR_NS + "b", a.get("arg2"));
    }

    @Test
    void atomJsonDifferentIndividualsAtomEmitsDifferentFrom() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/diff"));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> a = onlyHeadAtomJson(mm, df,
                df.getSWRLDifferentIndividualsAtom(var(df, "a"), var(df, "b")));
        assertEquals("different_from", a.get("type"), "a different-individuals atom serialises as different_from");
        assertEquals("?" + VAR_NS + "a", a.get("arg1"));
        assertEquals("?" + VAR_NS + "b", a.get("arg2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void atomJsonBuiltinAtomEmitsBuiltinAndArgs() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/builtin"));
        OWLModelManager mm = FakeModelManager.over(o);

        IRI greaterThan = IRI.create("http://www.w3.org/2003/11/swrlb#greaterThan");
        OWLDatatype xsdInt = df.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#integer"));
        List<SWRLDArgument> args = java.util.Arrays.asList(
                df.getSWRLVariable(IRI.create(VAR_NS + "age")),
                df.getSWRLLiteralArgument(df.getOWLLiteral("18", xsdInt)));
        SWRLAtom builtin = df.getSWRLBuiltInAtom(greaterThan, args);

        Map<String, Object> a = onlyHeadAtomJson(mm, df, builtin);
        assertEquals("builtin", a.get("type"));
        assertEquals(greaterThan.toString(), a.get("builtin"), "the builtin IRI is reported as a string");
        List<Object> outArgs = (List<Object>) a.get("args");
        assertEquals(2, outArgs.size(), "both builtin d-arguments are serialised");
        assertEquals("?" + VAR_NS + "age", outArgs.get(0), "the variable arg is emitted as ?<IRI>");
        assertInstanceOf(Map.class, outArgs.get(1), "the typed literal arg becomes {value, datatype}");
        assertEquals("18", ((Map<?, ?>) outArgs.get(1)).get("value"));
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", ((Map<?, ?>) outArgs.get(1)).get("datatype"));
    }

    @Test
    void atomJsonIndividualArgumentUsesIndividualIri() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/indiv"));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "knows"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(p));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLNamedIndividual alice = df.getOWLNamedIndividual(IRI.create(NS + "Alice"));
        SWRLIArgument a1 = df.getSWRLIndividualArgument(alice);
        Map<String, Object> a = onlyHeadAtomJson(mm, df,
                df.getSWRLObjectPropertyAtom(p, a1, var(df, "y")));
        assertEquals(NS + "Alice", a.get("arg1"), "an individual argument is emitted by its IRI (no ? prefix)");
        assertEquals("?" + VAR_NS + "y", a.get("arg2"));
    }

    // ------------------------------------------------------------------ round-trip: ruleJson emits IRIs the builder replays

    @Test
    @SuppressWarnings("unchecked")
    void ruleJsonPredicateIriRoundTripsVerbatim() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/rt"));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "before"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(p));
        OWLModelManager mm = FakeModelManager.over(o);

        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLObjectPropertyAtom(p, var(df, "x"), var(df, "y"))));
        Map<String, Object> json = RuleTools.ruleJson(mm, rule, 0);
        List<Map<String, Object>> heads = (List<Map<String, Object>>) json.get("head");
        // The emitted predicate_iri is the exact operand predicateRef would consume on replay.
        assertEquals(NS + "before", RuleTools.predicateRef(heads.get(0)),
                "the serialised atom's predicate_iri replays through predicateRef verbatim");
    }

    // ------------------------------------------------------------------ ruleJson insertion order of body/head

    @Test
    @SuppressWarnings("unchecked")
    void ruleJsonSerialisesMultipleHeadAtoms() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/multi"));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLClass c1 = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass c2 = df.getOWLClass(IRI.create(NS + "B"));
        Set<SWRLAtom> head = new LinkedHashSet<>();
        head.add(df.getSWRLClassAtom(c1, var(df, "x")));
        head.add(df.getSWRLClassAtom(c2, var(df, "x")));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(), head);

        List<Map<String, Object>> heads = (List<Map<String, Object>>) RuleTools.ruleJson(mm, rule, -1).get("head");
        assertEquals(2, heads.size(), "both head atoms are serialised");
        for (Map<String, Object> a : heads) {
            assertEquals("class", a.get("type"), "each is a class atom");
        }
    }

    // ------------------------------------------------------------------ FakeModelManager rejects unsupported paths

    @Test
    void fakeModelManagerRejectsRendererOnlyCalls() {
        OWLModelManager mm = FakeModelManager.empty();
        // Sanity guard: strating into an unsupported Protégé-runtime path (expression checker) fails
        // loudly, which is why Manchester class-expression atom-building is out of scope for these tests.
        assertThrows(UnsupportedOperationException.class,
                () -> mm.getOWLExpressionCheckerFactory(),
                "the fake refuses expression-checker access rather than silently returning null");
    }

    @Test
    void renderRuleIsStableAcrossCalls() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/stable"));
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = df.getOWLClass(IRI.create(NS + "C"));
        SWRLRule rule = df.getSWRLRule(Collections.emptySet(),
                Collections.singleton(df.getSWRLClassAtom(c, var(df, "x"))));
        assertSame(RuleTools.renderRule(mm, rule).getClass(), String.class);
        assertEquals(RuleTools.renderRule(mm, rule), RuleTools.renderRule(mm, rule),
                "rendering is deterministic across calls");
    }

    @Test
    void variableIriNonAbsoluteNameExpandsAgainstDefaultWhenNsNull() {
        assertEquals(IRI.create(RuleTools.DEFAULT_VARIABLE_NS + "foo"),
                RuleTools.variableIri("?foo", null),
                "a null namespace falls back to the DEFAULT_VARIABLE_NS constant");
        assertNull(RuleTools.variableIri("plainName", null),
                "a non-? argument is never a variable regardless of namespace");
    }
}
