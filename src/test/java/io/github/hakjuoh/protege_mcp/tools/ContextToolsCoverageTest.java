package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * Supplementary method-level coverage for {@link ContextTools} private helpers that the existing
 * {@code ContextToolsTest} (rootClasses, isDeprecated happy paths) does NOT touch. Private helpers
 * are reached by reflection; each takes only OWLAPI objects plus a {@link FakeModelManager} proxy
 * for rendering, so all paths here are deterministic and headless.
 */
class ContextToolsCoverageTest {

    private static final String NS = "http://example.org/ctx#";

    // ------------------------------------------------------------------ fixtures / reflection

    private OWLOntologyManager mgr() {
        return OWLManager.createOWLOntologyManager();
    }

    private OWLOntology newOntology(OWLOntologyManager m) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/ctx"));
    }

    private OWLOntology newOntology(OWLOntologyManager m, String localName)
            throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/ctx/" + localName));
    }

    private IRI iri(String local) {
        return IRI.create(NS + local);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method mth = ContextTools.class.getDeclaredMethod(name, types);
            mth.setAccessible(true);
            return (T) mth.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ rootClasses (gaps)

    @Test
    void rootClassesEmptyOntologyIsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o = newOntology(m);
        assertTrue(ContextTools.rootClasses(o).isEmpty(), "no classes -> no roots");
    }

    @Test
    void rootClassesIgnoresAnonymousSuperclassOnly() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLClass c = df.getOWLClass(iri("Restricted"));
        OWLObjectProperty p = df.getOWLObjectProperty(iri("rel"));
        OWLClass filler = df.getOWLClass(iri("Filler"));
        // anonymous superclass: some rel Filler
        OWLClassExpression anon = df.getOWLObjectSomeValuesFrom(p, filler);
        m.addAxiom(o, df.getOWLDeclarationAxiom(c));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(c, anon));

        Set<OWLClass> roots = ContextTools.rootClasses(o);
        assertTrue(roots.contains(c), "class with only an anonymous superclass is still a root");
    }

    @Test
    void rootClassesFirstNamedSuperMarksNonRoot() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLClass a = df.getOWLClass(iri("A"));
        OWLClass b = df.getOWLClass(iri("B"));
        OWLClass child = df.getOWLClass(iri("Child"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(child, a));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(child, b));

        Set<OWLClass> roots = ContextTools.rootClasses(o);
        assertFalse(roots.contains(child), "child with two named supers is not a root");
        assertTrue(roots.contains(a), "A is a root");
        assertTrue(roots.contains(b), "B is a root");
    }

    @Test
    void rootClassesExcludesOwlThingAndNothing() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        // reference owl:Thing and owl:Nothing in the signature
        OWLClass sub = df.getOWLClass(iri("Sub"));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(df.getOWLNothing(), sub));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(sub, df.getOWLThing()));

        Set<OWLClass> roots = ContextTools.rootClasses(o);
        assertFalse(roots.contains(df.getOWLThing()), "owl:Thing excluded");
        assertFalse(roots.contains(df.getOWLNothing()), "owl:Nothing excluded");
        assertTrue(roots.contains(sub), "Sub (only owl:Thing super) is a root");
    }

    // ------------------------------------------------------------------ isDeprecated (gaps)

    private Set<OWLOntology> singleton(OWLOntology o) {
        return Collections.singleton(o);
    }

    private void addDeprecated(OWLOntologyManager m, OWLOntology o, OWLEntity e, OWLLiteral value) {
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI()),
                e.getIRI(), value));
    }

    @Test
    void isDeprecatedFalseValueReturnsFalse() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLClass c = df.getOWLClass(iri("C"));
        addDeprecated(m, o, c, df.getOWLLiteral(false));
        assertFalse(ContextTools.isDeprecated(c, singleton(o)), "owl:deprecated false -> not deprecated");
    }

    @Test
    void isDeprecatedNonBooleanValueSwallowedReturnsFalse() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLClass c = df.getOWLClass(iri("C"));
        // a string literal is not a boolean -> parseBoolean throws, caught -> false
        addDeprecated(m, o, c, df.getOWLLiteral("not-a-bool"));
        assertFalse(ContextTools.isDeprecated(c, singleton(o)), "non-boolean owl:deprecated -> false");
    }

    @Test
    void isDeprecatedNonLiteralValueSkipped() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLClass c = df.getOWLClass(iri("C"));
        // owl:deprecated whose value is an IRI (not a literal) -> asLiteral absent -> skipped
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI()),
                c.getIRI(), iri("SomeIri")));
        assertFalse(ContextTools.isDeprecated(c, singleton(o)), "non-literal owl:deprecated -> false");
    }

    @Test
    void isDeprecatedIgnoresNonDeprecationAnnotations() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLClass c = df.getOWLClass(iri("C"));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), c.getIRI(), df.getOWLLiteral("A label")));
        addDeprecated(m, o, c, df.getOWLLiteral(true));
        assertTrue(ContextTools.isDeprecated(c, singleton(o)),
                "deprecated true survives alongside a label annotation");
    }

    @Test
    void isDeprecatedAnyOntologyInScope() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = newOntology(m, "one");
        OWLOntology o2 = newOntology(m, "two");
        OWLClass c = df.getOWLClass(iri("C"));
        // deprecation only in the second ontology of the scope
        addDeprecated(m, o2, c, df.getOWLLiteral(true));
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        assertTrue(ContextTools.isDeprecated(c, scope), "any ontology in scope being deprecated wins");
    }

    @Test
    void isDeprecatedEmptyScopeIsFalse() {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass c = df.getOWLClass(iri("C"));
        assertFalse(ContextTools.isDeprecated(c, Collections.emptySet()), "empty scope -> false");
    }

    // ------------------------------------------------------------------ has

    @Test
    @SuppressWarnings("unchecked")
    void hasEmptyScopeFalse() {
        Predicate<OWLOntology> always = o -> true;
        boolean r = invoke("has", new Class<?>[] { Set.class, Predicate.class },
                Collections.emptySet(), always);
        assertFalse(r, "empty scope -> false even with an always-true predicate");
    }

    @Test
    void hasSingleTrue() throws OWLOntologyCreationException {
        OWLOntology o = newOntology(mgr());
        boolean r = invoke("has", new Class<?>[] { Set.class, Predicate.class },
                singleton(o), (Predicate<OWLOntology>) x -> true);
        assertTrue(r, "predicate true on the single ontology -> true");
    }

    @Test
    void hasSingleFalse() throws OWLOntologyCreationException {
        OWLOntology o = newOntology(mgr());
        boolean r = invoke("has", new Class<?>[] { Set.class, Predicate.class },
                singleton(o), (Predicate<OWLOntology>) x -> false);
        assertFalse(r, "predicate false on the single ontology -> false");
    }

    @Test
    void hasLaterOntologyMatches() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o1 = newOntology(m, "a");
        OWLOntology o2 = newOntology(m, "b");
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        boolean r = invoke("has", new Class<?>[] { Set.class, Predicate.class },
                scope, (Predicate<OWLOntology>) x -> x == o2);
        assertTrue(r, "matching only the later ontology still returns true");
    }

    @Test
    void hasNoneMatch() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o1 = newOntology(m, "a");
        OWLOntology o2 = newOntology(m, "b");
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        boolean r = invoke("has", new Class<?>[] { Set.class, Predicate.class },
                scope, (Predicate<OWLOntology>) x -> false);
        assertFalse(r, "no ontology matches -> false");
    }

    // ------------------------------------------------------------------ literalString

    private String literalString(OWLLiteral lit) {
        return invoke("literalString", new Class<?>[] { OWLLiteral.class }, lit);
    }

    @Test
    void literalStringLangTagged() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        assertEquals("hello@en", literalString(df.getOWLLiteral("hello", "en")),
                "lang-tagged literal renders value@lang");
    }

    @Test
    void literalStringPlain() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        assertEquals("plain", literalString(df.getOWLLiteral("plain")),
                "plain literal renders bare text");
    }

    @Test
    void literalStringTypedInteger() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        assertEquals("42", literalString(df.getOWLLiteral(42)),
                "typed xsd:integer renders bare integer text");
    }

    @Test
    void literalStringEmpty() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        assertEquals("", literalString(df.getOWLLiteral("")), "empty string literal -> empty");
    }

    @Test
    void literalStringSpecialChars() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        assertEquals("a\tb\n<x>", literalString(df.getOWLLiteral("a\tb\n<x>")),
                "special characters preserved verbatim");
    }

    // ------------------------------------------------------------------ memberTieBreak

    private String memberTieBreak(OWLObject o) {
        return invoke("memberTieBreak", new Class<?>[] { OWLObject.class }, o);
    }

    @Test
    void memberTieBreakClass() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        OWLClass c = df.getOWLClass(iri("C"));
        assertEquals("Class " + NS + "C", memberTieBreak(c), "class tie-break is 'Class <iri>'");
    }

    @Test
    void memberTieBreakObjectProperty() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        assertEquals("ObjectProperty " + NS + "p", memberTieBreak(p),
                "object property tie-break is 'ObjectProperty <iri>'");
    }

    @Test
    void memberTieBreakNonEntityUsesToString() {
        OWLDataFactory df = mgr().getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        OWLClass filler = df.getOWLClass(iri("F"));
        OWLClassExpression anon = df.getOWLObjectSomeValuesFrom(p, filler);
        assertEquals(anon.toString(), memberTieBreak(anon),
                "non-entity OWL object falls back to toString()");
    }

    // ------------------------------------------------------------------ byString

    @Test
    @SuppressWarnings("unchecked")
    void byStringComparatorOrdersByRendering() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        Comparator<OWLObject> cmp = invoke("byString",
                new Class<?>[] { OWLModelManager.class }, mm);
        OWLNamedIndividual apple = df.getOWLNamedIndividual(iri("Apple"));
        OWLNamedIndividual banana = df.getOWLNamedIndividual(iri("Banana"));
        assertTrue(cmp.compare(apple, banana) < 0, "Apple sorts before Banana by rendering");
        assertTrue(cmp.compare(banana, apple) > 0, "Banana sorts after Apple");
        assertEquals(0, cmp.compare(apple, apple), "same rendering -> 0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void byStringComparatorWorksInTreeSet() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        Comparator<OWLObject> cmp = invoke("byString",
                new Class<?>[] { OWLModelManager.class }, mm);
        TreeSet<OWLObject> set = new TreeSet<>(cmp);
        set.add(df.getOWLNamedIndividual(iri("Zebra")));
        set.add(df.getOWLNamedIndividual(iri("Aardvark")));
        assertEquals("Aardvark", FakeModelManager.shortForm(
                ((OWLNamedIndividual) set.first()).getIRI()), "TreeSet sorts by rendering");
    }

    // ------------------------------------------------------------------ members

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members(OWLModelManager mm, Collection<? extends OWLObject> objs,
            int limit) {
        return invoke("members", new Class<?>[] { OWLModelManager.class, Collection.class, int.class },
                mm, objs, limit);
    }

    @Test
    void membersEmpty() {
        OWLModelManager mm = FakeModelManager.empty();
        assertTrue(members(mm, Collections.emptyList(), 50).isEmpty(), "empty collection -> empty list");
    }

    @Test
    void membersEntityShape() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass c = df.getOWLClass(iri("Thing1"));
        List<Map<String, Object>> out = members(mm, Collections.singletonList(c), 50);
        assertEquals(1, out.size(), "one entity -> one row");
        Map<String, Object> row = out.get(0);
        assertEquals(NS + "Thing1", row.get("iri"), "iri present");
        assertEquals("Thing1", row.get("display"), "display is short form (FakeModelManager rendering)");
        assertEquals("Class", row.get("type"), "type is entity-type name");
        assertFalse(row.containsKey("anonymous"), "named entity has no anonymous flag");
    }

    @Test
    void membersAnonymousExpressionShape() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(iri("rel"));
        OWLClass filler = df.getOWLClass(iri("F"));
        OWLClassExpression anon = df.getOWLObjectSomeValuesFrom(p, filler);
        List<Map<String, Object>> out = members(mm, Collections.singletonList(anon), 50);
        assertEquals(1, out.size(), "one expression -> one row");
        Map<String, Object> row = out.get(0);
        assertEquals(Boolean.TRUE, row.get("anonymous"), "anonymous expression flagged");
        assertTrue(row.containsKey("expression"), "anonymous row carries an expression string");
        assertFalse(row.containsKey("iri"), "anonymous row has no iri");
    }

    @Test
    void membersMixedEntitiesAndExpressions() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass c = df.getOWLClass(iri("Named"));
        OWLObjectProperty p = df.getOWLObjectProperty(iri("rel"));
        OWLClassExpression anon = df.getOWLObjectSomeValuesFrom(p, df.getOWLClass(iri("F")));
        List<Map<String, Object>> out = members(mm, java.util.Arrays.asList(c, anon), 50);
        assertEquals(2, out.size(), "both a named entity and an expression appear");
        boolean hasNamed = out.stream().anyMatch(r -> r.containsKey("iri"));
        boolean hasAnon = out.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("anonymous")));
        assertTrue(hasNamed && hasAnon, "one named row and one anonymous row");
    }

    @Test
    void membersSortedCaseInsensitiveByRendering() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        // rendering (short form) is "apple", "Banana", "cherry" -> case-insensitive sort
        OWLClass banana = df.getOWLClass(iri("Banana"));
        OWLClass apple = df.getOWLClass(iri("apple"));
        OWLClass cherry = df.getOWLClass(iri("cherry"));
        List<Map<String, Object>> out = members(mm, java.util.Arrays.asList(banana, cherry, apple), 50);
        assertEquals("apple", out.get(0).get("display"), "apple sorts first (case-insensitive)");
        assertEquals("Banana", out.get(1).get("display"), "Banana second");
        assertEquals("cherry", out.get(2).get("display"), "cherry third");
    }

    @Test
    void membersTieBreakSameRenderingByType() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        // same short form "Foo" for a class and an object property -> tie-break by type name
        OWLClass c = df.getOWLClass(iri("Foo"));
        OWLObjectProperty p = df.getOWLObjectProperty(iri("Foo"));
        List<Map<String, Object>> out = members(mm, java.util.Arrays.asList(p, c), 50);
        // "Class Foo" < "ObjectProperty Foo" -> Class comes first
        assertEquals("Class", out.get(0).get("type"), "Class sorts before ObjectProperty on tie");
        assertEquals("ObjectProperty", out.get(1).get("type"), "ObjectProperty second");
    }

    @Test
    void membersNegativeLimitYieldsEmpty() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass c = df.getOWLClass(iri("X"));
        assertTrue(members(mm, Collections.singletonList(c), -5).isEmpty(),
                "negative limit -> max(0,limit)=0 -> empty");
    }

    @Test
    void membersZeroLimitYieldsEmpty() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLClass c = df.getOWLClass(iri("X"));
        assertTrue(members(mm, Collections.singletonList(c), 0).isEmpty(), "limit 0 -> empty");
    }

    @Test
    void membersLimitCaps() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        List<OWLObject> objs = java.util.Arrays.asList(
                df.getOWLClass(iri("A")), df.getOWLClass(iri("B")), df.getOWLClass(iri("C")));
        List<Map<String, Object>> out = members(mm, objs, 2);
        assertEquals(2, out.size(), "output capped at limit");
        assertEquals("A", out.get(0).get("display"), "kept the first two sorted items");
        assertEquals("B", out.get(1).get("display"), "kept the first two sorted items");
    }

    @Test
    void membersLimitAboveSizeReturnsAll() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        List<OWLObject> objs = java.util.Arrays.asList(
                df.getOWLClass(iri("A")), df.getOWLClass(iri("B")));
        assertEquals(2, members(mm, objs, 100).size(), "limit above size returns all");
    }

    // ------------------------------------------------------------------ renderings

    @SuppressWarnings("unchecked")
    private List<String> renderings(OWLModelManager mm, Collection<? extends OWLObject> objs, int limit) {
        return invoke("renderings", new Class<?>[] { OWLModelManager.class, Collection.class, int.class },
                mm, objs, limit);
    }

    @Test
    void renderingsEmpty() {
        assertTrue(renderings(FakeModelManager.empty(), Collections.emptyList(), 50).isEmpty(),
                "empty collection -> empty renderings");
    }

    @Test
    void renderingsSingle() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        List<String> out = renderings(mm, Collections.singletonList(df.getOWLClass(iri("Solo"))), 50);
        assertEquals(Collections.singletonList("Solo"), out, "single object -> single rendering");
    }

    @Test
    void renderingsSortedAndDeduped() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        // two distinct entities render to the same short form "Dup" -> TreeSet dedupes to one
        OWLClass c = df.getOWLClass(iri("Dup"));
        OWLObjectProperty p = df.getOWLObjectProperty(iri("Dup"));
        OWLClass alpha = df.getOWLClass(iri("Alpha"));
        List<String> out = renderings(mm, java.util.Arrays.asList(c, p, alpha), 50);
        assertEquals(java.util.Arrays.asList("Alpha", "Dup"), out,
                "sorted TreeSet with duplicate rendering collapsed");
    }

    @Test
    void renderingsLimitCaps() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        List<OWLObject> objs = java.util.Arrays.asList(
                df.getOWLClass(iri("A")), df.getOWLClass(iri("B")), df.getOWLClass(iri("C")));
        assertEquals(java.util.Arrays.asList("A", "B"), renderings(mm, objs, 2), "capped at limit");
    }

    @Test
    void renderingsNegativeLimitEmpty() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        assertTrue(renderings(mm, Collections.singletonList(df.getOWLClass(iri("X"))), -1).isEmpty(),
                "negative limit -> empty");
    }

    @Test
    void renderingsLimitAtOrAboveSizeReturnsAll() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        List<OWLObject> objs = java.util.Arrays.asList(
                df.getOWLClass(iri("A")), df.getOWLClass(iri("B")));
        assertEquals(2, renderings(mm, objs, 2).size(), "limit == size returns all");
    }

    // ------------------------------------------------------------------ annotations

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> annotations(OWLModelManager mm, OWLEntity e, Set<OWLOntology> scope) {
        return invoke("annotations", new Class<?>[] { OWLModelManager.class, OWLEntity.class, Set.class },
                mm, e, scope);
    }

    @Test
    void annotationsNoneEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = m.getOWLDataFactory().getOWLClass(iri("C"));
        m.addAxiom(o, m.getOWLDataFactory().getOWLDeclarationAxiom(c));
        assertTrue(annotations(mm, c, singleton(o)).isEmpty(), "no annotations -> empty list");
    }

    @Test
    void annotationsSingle() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = df.getOWLClass(iri("C"));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), c.getIRI(), df.getOWLLiteral("Label A", "en")));
        List<Map<String, Object>> out = annotations(mm, c, singleton(o));
        assertEquals(1, out.size(), "one annotation -> one map");
        Map<String, Object> row = out.get(0);
        assertEquals("Label A", row.get("value"), "value present (Tools.annotationJson shape)");
        assertEquals("en", row.get("lang"), "lang tag surfaced by annotationJson");
        assertTrue(row.containsKey("property_iri"), "annotationJson includes property_iri");
    }

    @Test
    void annotationsMergedAcrossScope() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = newOntology(m, "one");
        OWLOntology o2 = newOntology(m, "two");
        OWLModelManager mm = FakeModelManager.over(o1);
        OWLClass c = df.getOWLClass(iri("C"));
        m.addAxiom(o1, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), c.getIRI(),
                df.getOWLLiteral("in one")));
        m.addAxiom(o2, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), c.getIRI(),
                df.getOWLLiteral("in two")));
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        assertEquals(2, annotations(mm, c, scope).size(), "annotations from both ontologies merged");
    }

    // ------------------------------------------------------------------ instances

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> instances(OWLModelManager mm, OWLClass c, Set<OWLOntology> scope,
            int limit) {
        return invoke("instances",
                new Class<?>[] { OWLModelManager.class, OWLClass.class, Set.class, int.class },
                mm, c, scope, limit);
    }

    @Test
    void instancesNoneEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = m.getOWLDataFactory().getOWLClass(iri("C"));
        m.addAxiom(o, m.getOWLDataFactory().getOWLDeclarationAxiom(c));
        assertTrue(instances(mm, c, singleton(o), 50).isEmpty(), "class with no instances -> empty");
    }

    @Test
    void instancesNamedIndividual() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = df.getOWLClass(iri("Person"));
        OWLNamedIndividual alice = df.getOWLNamedIndividual(iri("Alice"));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(c, alice));
        List<Map<String, Object>> out = instances(mm, c, singleton(o), 50);
        assertEquals(1, out.size(), "one named instance");
        assertEquals(NS + "Alice", out.get(0).get("iri"), "iri of the instance");
        assertEquals("NamedIndividual", out.get(0).get("type"), "type is NamedIndividual");
    }

    @Test
    void instancesExcludesAnonymous() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = df.getOWLClass(iri("Person"));
        OWLAnonymousIndividual anon = df.getOWLAnonymousIndividual();
        m.addAxiom(o, df.getOWLClassAssertionAxiom(c, anon));
        assertTrue(instances(mm, c, singleton(o), 50).isEmpty(),
                "anonymous individuals are not reported");
    }

    @Test
    void instancesMergedAcrossScope() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = newOntology(m, "one");
        OWLOntology o2 = newOntology(m, "two");
        OWLModelManager mm = FakeModelManager.over(o1);
        OWLClass c = df.getOWLClass(iri("Person"));
        m.addAxiom(o1, df.getOWLClassAssertionAxiom(c, df.getOWLNamedIndividual(iri("Alice"))));
        m.addAxiom(o2, df.getOWLClassAssertionAxiom(c, df.getOWLNamedIndividual(iri("Bob"))));
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        assertEquals(2, instances(mm, c, scope, 50).size(), "instances merged across scope");
    }

    @Test
    void instancesLimitCaps() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = df.getOWLClass(iri("Person"));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(c, df.getOWLNamedIndividual(iri("A"))));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(c, df.getOWLNamedIndividual(iri("B"))));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(c, df.getOWLNamedIndividual(iri("C"))));
        assertEquals(2, instances(mm, c, singleton(o), 2).size(), "instances capped at limit");
    }

    @Test
    void instancesNegativeLimitEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLClass c = df.getOWLClass(iri("Person"));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(c, df.getOWLNamedIndividual(iri("A"))));
        assertTrue(instances(mm, c, singleton(o), -1).isEmpty(), "negative limit -> empty");
    }

    // ------------------------------------------------------------------ objectPropertyValues

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectPropertyValues(OWLModelManager mm, OWLNamedIndividual ind,
            Set<OWLOntology> scope, int limit) {
        return invoke("objectPropertyValues",
                new Class<?>[] { OWLModelManager.class, OWLNamedIndividual.class, Set.class, int.class },
                mm, ind, scope, limit);
    }

    @Test
    void objectPropertyValuesNoneEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(ind));
        assertTrue(objectPropertyValues(mm, ind, singleton(o), 50).isEmpty(),
                "no object property assertions -> empty");
    }

    @Test
    void objectPropertyValuesSingle() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLObjectProperty knows = df.getOWLObjectProperty(iri("knows"));
        OWLNamedIndividual friend = df.getOWLNamedIndividual(iri("Friend"));
        m.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(knows, ind, friend));
        List<Map<String, Object>> out = objectPropertyValues(mm, ind, singleton(o), 50);
        assertEquals(1, out.size(), "one property row");
        assertEquals("knows", out.get(0).get("property"), "property rendered by short form");
        assertEquals(Collections.singletonList("Friend"), out.get(0).get("values"), "one value");
    }

    @Test
    void objectPropertyValuesMergedMultipleValuesSorted() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLObjectProperty knows = df.getOWLObjectProperty(iri("knows"));
        m.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(knows, ind,
                df.getOWLNamedIndividual(iri("Zoe"))));
        m.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(knows, ind,
                df.getOWLNamedIndividual(iri("Ann"))));
        List<Map<String, Object>> out = objectPropertyValues(mm, ind, singleton(o), 50);
        assertEquals(1, out.size(), "same property merged into one row");
        assertEquals(java.util.Arrays.asList("Ann", "Zoe"), out.get(0).get("values"),
                "values sorted by rendering via byString comparator");
    }

    @Test
    void objectPropertyValuesLimitCapsValues() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLObjectProperty knows = df.getOWLObjectProperty(iri("knows"));
        m.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(knows, ind,
                df.getOWLNamedIndividual(iri("A"))));
        m.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(knows, ind,
                df.getOWLNamedIndividual(iri("B"))));
        m.addAxiom(o, df.getOWLObjectPropertyAssertionAxiom(knows, ind,
                df.getOWLNamedIndividual(iri("C"))));
        List<Map<String, Object>> out = objectPropertyValues(mm, ind, singleton(o), 2);
        assertEquals(java.util.Arrays.asList("A", "B"), out.get(0).get("values"),
                "values per property capped at limit");
    }

    @Test
    void objectPropertyValuesMergedAcrossScope() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = newOntology(m, "one");
        OWLOntology o2 = newOntology(m, "two");
        OWLModelManager mm = FakeModelManager.over(o1);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLObjectProperty knows = df.getOWLObjectProperty(iri("knows"));
        m.addAxiom(o1, df.getOWLObjectPropertyAssertionAxiom(knows, ind,
                df.getOWLNamedIndividual(iri("Ann"))));
        m.addAxiom(o2, df.getOWLObjectPropertyAssertionAxiom(knows, ind,
                df.getOWLNamedIndividual(iri("Bob"))));
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        List<Map<String, Object>> out = objectPropertyValues(mm, ind, scope, 50);
        assertEquals(1, out.size(), "same property merged across scope");
        assertEquals(java.util.Arrays.asList("Ann", "Bob"), out.get(0).get("values"),
                "values from both ontologies merged and sorted");
    }

    // ------------------------------------------------------------------ dataPropertyValues

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataPropertyValues(OWLModelManager mm, OWLNamedIndividual ind,
            Set<OWLOntology> scope, int limit) {
        return invoke("dataPropertyValues",
                new Class<?>[] { OWLModelManager.class, OWLNamedIndividual.class, Set.class, int.class },
                mm, ind, scope, limit);
    }

    @Test
    void dataPropertyValuesNoneEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(ind));
        assertTrue(dataPropertyValues(mm, ind, singleton(o), 50).isEmpty(),
                "no data property assertions -> empty");
    }

    @Test
    void dataPropertyValuesSinglePlain() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLDataProperty name = df.getOWLDataProperty(iri("name"));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(name, ind, df.getOWLLiteral("Alice")));
        List<Map<String, Object>> out = dataPropertyValues(mm, ind, singleton(o), 50);
        assertEquals(1, out.size(), "one data property row");
        assertEquals("name", out.get(0).get("property"), "property rendered by short form");
        assertEquals(Collections.singletonList("Alice"), out.get(0).get("values"), "plain literal value");
    }

    @Test
    void dataPropertyValuesLangTaggedPreserved() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLDataProperty label = df.getOWLDataProperty(iri("label"));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(label, ind, df.getOWLLiteral("Bonjour", "fr")));
        List<Map<String, Object>> out = dataPropertyValues(mm, ind, singleton(o), 50);
        assertEquals(Collections.singletonList("Bonjour@fr"), out.get(0).get("values"),
                "lang-tagged literal preserved as value@lang");
    }

    @Test
    void dataPropertyValuesTypedInteger() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLDataProperty age = df.getOWLDataProperty(iri("age"));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(age, ind,
                df.getOWLLiteral("30", OWL2Datatype.XSD_INTEGER)));
        List<Map<String, Object>> out = dataPropertyValues(mm, ind, singleton(o), 50);
        assertEquals(Collections.singletonList("30"), out.get(0).get("values"),
                "typed literal rendered as bare lexical value");
    }

    @Test
    void dataPropertyValuesMergedMultiple() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLDataProperty nick = df.getOWLDataProperty(iri("nick"));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(nick, ind, df.getOWLLiteral("x")));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(nick, ind, df.getOWLLiteral("y")));
        List<Map<String, Object>> out = dataPropertyValues(mm, ind, singleton(o), 50);
        assertEquals(1, out.size(), "same property merged into one row");
        List<String> values = (List<String>) out.get(0).get("values");
        assertEquals(2, values.size(), "both values present");
        assertTrue(values.contains("x") && values.contains("y"), "both literals retained");
    }

    @Test
    void dataPropertyValuesLimitCaps() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLModelManager mm = FakeModelManager.over(o);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLDataProperty nick = df.getOWLDataProperty(iri("nick"));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(nick, ind, df.getOWLLiteral("a")));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(nick, ind, df.getOWLLiteral("b")));
        m.addAxiom(o, df.getOWLDataPropertyAssertionAxiom(nick, ind, df.getOWLLiteral("c")));
        List<Map<String, Object>> out = dataPropertyValues(mm, ind, singleton(o), 2);
        List<String> values = (List<String>) out.get(0).get("values");
        assertEquals(2, values.size(), "values capped at limit via subList");
    }

    @Test
    void dataPropertyValuesMergedAcrossScope() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = newOntology(m, "one");
        OWLOntology o2 = newOntology(m, "two");
        OWLModelManager mm = FakeModelManager.over(o1);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(iri("Ind"));
        OWLDataProperty nick = df.getOWLDataProperty(iri("nick"));
        m.addAxiom(o1, df.getOWLDataPropertyAssertionAxiom(nick, ind, df.getOWLLiteral("first")));
        m.addAxiom(o2, df.getOWLDataPropertyAssertionAxiom(nick, ind, df.getOWLLiteral("second")));
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        List<Map<String, Object>> out = dataPropertyValues(mm, ind, scope, 50);
        assertEquals(1, out.size(), "same property merged across scope");
        List<String> values = (List<String>) out.get(0).get("values");
        assertEquals(2, values.size(), "values from both ontologies merged");
    }

    // ------------------------------------------------------------------ objectCharacteristics

    @SuppressWarnings("unchecked")
    private List<String> objectCharacteristics(OWLObjectProperty p, Set<OWLOntology> scope) {
        return invoke("objectCharacteristics",
                new Class<?>[] { OWLObjectProperty.class, Set.class }, p, scope);
    }

    @Test
    void objectCharacteristicsNoneEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(p));
        assertTrue(objectCharacteristics(p, singleton(o)).isEmpty(), "no characteristics -> empty");
    }

    @Test
    void objectCharacteristicsFunctional() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLFunctionalObjectPropertyAxiom(p));
        assertEquals(Collections.singletonList("functional"), objectCharacteristics(p, singleton(o)),
                "functional characteristic reported");
    }

    @Test
    void objectCharacteristicsInverseFunctional() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLInverseFunctionalObjectPropertyAxiom(p));
        assertEquals(Collections.singletonList("inverse_functional"),
                objectCharacteristics(p, singleton(o)), "inverse_functional reported");
    }

    @Test
    void objectCharacteristicsTransitive() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLTransitiveObjectPropertyAxiom(p));
        assertEquals(Collections.singletonList("transitive"), objectCharacteristics(p, singleton(o)),
                "transitive reported");
    }

    @Test
    void objectCharacteristicsSymmetric() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLSymmetricObjectPropertyAxiom(p));
        assertEquals(Collections.singletonList("symmetric"), objectCharacteristics(p, singleton(o)),
                "symmetric reported");
    }

    @Test
    void objectCharacteristicsAsymmetric() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLAsymmetricObjectPropertyAxiom(p));
        assertEquals(Collections.singletonList("asymmetric"), objectCharacteristics(p, singleton(o)),
                "asymmetric reported");
    }

    @Test
    void objectCharacteristicsReflexive() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLReflexiveObjectPropertyAxiom(p));
        assertEquals(Collections.singletonList("reflexive"), objectCharacteristics(p, singleton(o)),
                "reflexive reported");
    }

    @Test
    void objectCharacteristicsIrreflexive() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o, df.getOWLIrreflexiveObjectPropertyAxiom(p));
        assertEquals(Collections.singletonList("irreflexive"), objectCharacteristics(p, singleton(o)),
                "irreflexive reported");
    }

    @Test
    void objectCharacteristicsMultipleInCodedOrder() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = newOntology(m);
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        // add in a shuffled order; output must follow the coded order
        m.addAxiom(o, df.getOWLSymmetricObjectPropertyAxiom(p));
        m.addAxiom(o, df.getOWLTransitiveObjectPropertyAxiom(p));
        m.addAxiom(o, df.getOWLFunctionalObjectPropertyAxiom(p));
        m.addAxiom(o, df.getOWLReflexiveObjectPropertyAxiom(p));
        assertEquals(java.util.Arrays.asList("functional", "transitive", "symmetric", "reflexive"),
                objectCharacteristics(p, singleton(o)),
                "characteristics emitted in coded order regardless of axiom insertion order");
    }

    @Test
    void objectCharacteristicsAcrossScopeIsOr() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o1 = newOntology(m, "one");
        OWLOntology o2 = newOntology(m, "two");
        OWLObjectProperty p = df.getOWLObjectProperty(iri("p"));
        m.addAxiom(o1, df.getOWLFunctionalObjectPropertyAxiom(p));
        m.addAxiom(o2, df.getOWLTransitiveObjectPropertyAxiom(p));
        Set<OWLOntology> scope = new LinkedHashSet<>();
        scope.add(o1);
        scope.add(o2);
        assertEquals(java.util.Arrays.asList("functional", "transitive"),
                objectCharacteristics(p, scope), "characteristics OR-ed across scope");
    }
}
