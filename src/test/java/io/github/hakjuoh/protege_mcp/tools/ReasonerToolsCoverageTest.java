package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Supplementary, method-level coverage for {@link ReasonerTools}. There is no pre-existing test for
 * this class, so this file targets the pure / data-transforming {@code private static} helpers that do
 * NOT need the live Protégé reasoner runtime: {@code explanationsSchema}, {@code intProp},
 * {@code isolatedClosure}, {@code explanationsJson}, {@code structuralExplanation} and
 * {@code relatedAssertedAxioms}. Reasoner-dependent paths ({@code specs}, {@code requireReasoner})
 * are out of scope (they need a live reasoner/Protégé session).
 *
 * <p>The helpers are private, so they are invoked via reflection. {@link FakeModelManager} supplies a
 * headless {@link OWLModelManager} for the helpers that render axioms.
 */
class ReasonerToolsCoverageTest {

    private static final String NS = "http://example.org/rt#";

    // ---------------------------------------------------------------- reflection plumbing

    private static Method method(String name, Class<?>... params) {
        try {
            Method m = ReasonerTools.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no such private method " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(String name, Class<?>[] params, Object... args) throws Throwable {
        try {
            return (T) method(name, params).invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static OWLClass cls(OWLDataFactory df, String local) {
        return df.getOWLClass(IRI.create(NS + local));
    }

    private static OWLOntology ontology(OWLOntologyManager m, String iri)
            throws OWLOntologyCreationException {
        return m.createOntology(IRI.create(iri));
    }

    // ================================================================ intProp

    @Test
    void intPropBuildsIntegerTypedPropertyMap() throws Throwable {
        Map<String, Object> p = invoke("intProp", new Class<?>[] { String.class }, "how many");
        assertEquals("integer", p.get("type"), "type is 'integer'");
        assertEquals("how many", p.get("description"), "description is echoed verbatim");
        assertEquals(2, p.size(), "only type + description keys");
    }

    @Test
    void intPropAcceptsNullDescription() throws Throwable {
        Map<String, Object> p = invoke("intProp", new Class<?>[] { String.class }, (Object) null);
        assertEquals("integer", p.get("type"), "type still integer with null desc");
        assertTrue(p.containsKey("description"), "description key present even when null");
        assertNull(p.get("description"), "null description stored as null");
    }

    @Test
    void intPropAcceptsEmptyDescription() throws Throwable {
        Map<String, Object> p = invoke("intProp", new Class<?>[] { String.class }, "");
        assertEquals("", p.get("description"), "empty description preserved");
    }

    @Test
    void intPropPreservesSpecialCharacters() throws Throwable {
        String desc = "límite ≤ 3 — \"quoted\" & <tag>\n";
        Map<String, Object> p = invoke("intProp", new Class<?>[] { String.class }, desc);
        assertEquals(desc, p.get("description"), "special chars preserved");
    }

    // ================================================================ explanationsSchema

    @Test
    void explanationsSchemaAddsMaxAndTimeoutIntegerProps() throws Throwable {
        Map<String, Object> schema = invoke("explanationsSchema", new Class<?>[] {});
        assertTrue(schema.containsKey("properties"), "schema has a properties block");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        assertTrue(props.containsKey("max"), "explanation-search 'max' knob present");
        assertTrue(props.containsKey("timeout_ms"), "explanation-search 'timeout_ms' knob present");

        @SuppressWarnings("unchecked")
        Map<String, Object> max = (Map<String, Object>) props.get("max");
        assertEquals("integer", max.get("type"), "'max' is integer-typed");
        assertTrue(max.get("description") instanceof String && !((String) max.get("description")).isEmpty(),
                "'max' has a non-empty description");

        @SuppressWarnings("unchecked")
        Map<String, Object> timeout = (Map<String, Object>) props.get("timeout_ms");
        assertEquals("integer", timeout.get("type"), "'timeout_ms' is integer-typed");
        assertTrue(timeout.get("description") instanceof String
                        && !((String) timeout.get("description")).isEmpty(),
                "'timeout_ms' has a non-empty description");
    }

    @Test
    void explanationsSchemaKeepsBaseAxiomProperties() throws Throwable {
        // The base Axioms.schema() properties (e.g. axiom_type) must survive the augmentation.
        Map<String, Object> base = Axioms.schema();
        @SuppressWarnings("unchecked")
        Map<String, Object> baseProps = (Map<String, Object>) base.get("properties");

        Map<String, Object> schema = invoke("explanationsSchema", new Class<?>[] {});
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        for (String key : baseProps.keySet()) {
            assertTrue(props.containsKey(key), "base axiom property retained: " + key);
        }
        assertTrue(props.size() >= baseProps.size() + 2,
                "augmented schema has at least the base props plus max + timeout_ms");
    }

    // ================================================================ isolatedClosure

    @Test
    void isolatedClosureCopiesSingleOntologyAxiomsIntoPrivateManager() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ontology(m, "http://example.org/rt-single");
        OWLDataFactory df = m.getOWLDataFactory();
        OWLAxiom a1 = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        OWLAxiom a2 = df.getOWLSubClassOfAxiom(cls(df, "Cat"), cls(df, "Animal"));
        m.addAxiom(o, a1);
        m.addAxiom(o, a2);

        OWLOntology copy = invoke("isolatedClosure", new Class<?>[] { OWLOntology.class }, o);
        assertTrue(copy.containsAxiom(a1), "copy has a1");
        assertTrue(copy.containsAxiom(a2), "copy has a2");
        assertEquals(o.getAxiomCount(), copy.getAxiomCount(), "same axiom count");
    }

    @Test
    void isolatedClosureUsesAPrivateManagerDistinctFromSource() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ontology(m, "http://example.org/rt-priv");
        OWLDataFactory df = m.getOWLDataFactory();
        m.addAxiom(o, df.getOWLDeclarationAxiom(cls(df, "Thing1")));

        OWLOntology copy = invoke("isolatedClosure", new Class<?>[] { OWLOntology.class }, o);
        assertFalse(copy.getOWLOntologyManager() == m,
                "the copy lives in a throwaway manager, not the source manager");
    }

    @Test
    void isolatedClosureOnEmptyOntologyReturnsEmptyCopy() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ontology(m, "http://example.org/rt-empty");

        OWLOntology copy = invoke("isolatedClosure", new Class<?>[] { OWLOntology.class }, o);
        assertEquals(0, copy.getAxiomCount(), "empty in, empty out");
    }

    @Test
    void isolatedClosureUnionsImportedOntologyAxioms() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI importedIri = IRI.create("http://example.org/rt-imported");
        OWLOntology imported = m.createOntology(importedIri);
        OWLAxiom importedAx = df.getOWLSubClassOfAxiom(cls(df, "Puppy"), cls(df, "Dog"));
        m.addAxiom(imported, importedAx);

        OWLOntology active = ontology(m, "http://example.org/rt-active");
        OWLAxiom activeAx = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        m.addAxiom(active, activeAx);
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(importedIri)));

        assertTrue(active.getImportsClosure().contains(imported), "import wired up");

        OWLOntology copy = invoke("isolatedClosure", new Class<?>[] { OWLOntology.class }, active);
        assertTrue(copy.containsAxiom(activeAx), "copy has active axiom");
        assertTrue(copy.containsAxiom(importedAx), "copy also has the imported axiom (union of closure)");
    }

    // ================================================================ explanationsJson

    @Test
    void explanationsJsonEmptySetReportsNotEntailed() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        Set<Set<OWLAxiom>> explanations = new LinkedHashSet<>();

        Map<String, Object> out = invoke("explanationsJson",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, Set.class },
                mm, ax, explanations);

        assertEquals(Boolean.FALSE, out.get("entailed"), "no explanations => not entailed");
        assertEquals(0, out.get("justification_count"), "count zero");
        assertTrue(((List<?>) out.get("justifications")).isEmpty(), "no justification entries");
        assertTrue(out.containsKey("axiom"), "axiom echoed");
    }

    @Test
    void explanationsJsonSingleJustificationReportsSizeAndAxioms() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom target = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        OWLAxiom j1 = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Mammal"));
        Set<OWLAxiom> just = new LinkedHashSet<>();
        just.add(j1);
        Set<Set<OWLAxiom>> explanations = new LinkedHashSet<>();
        explanations.add(just);

        Map<String, Object> out = invoke("explanationsJson",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, Set.class },
                mm, target, explanations);

        assertEquals(Boolean.TRUE, out.get("entailed"), "non-empty => entailed");
        assertEquals(1, out.get("justification_count"), "one justification");
        List<?> justs = (List<?>) out.get("justifications");
        assertEquals(1, justs.size(), "one justification entry");
        @SuppressWarnings("unchecked")
        Map<String, Object> j = (Map<String, Object>) justs.get(0);
        assertEquals(1, j.get("size"), "justification size 1");
        assertEquals(1, ((List<?>) j.get("axioms")).size(), "one axiom rendered");
    }

    @Test
    void explanationsJsonSortsAxiomsByRenderingWithinAJustification() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom target = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        // Two axioms; use a HashSet so insertion order is not the sort order we rely on.
        OWLAxiom axZ = df.getOWLDeclarationAxiom(cls(df, "Zebra"));
        OWLAxiom axA = df.getOWLDeclarationAxiom(cls(df, "Aardvark"));
        Set<OWLAxiom> just = new HashSet<>();
        just.add(axZ);
        just.add(axA);
        Set<Set<OWLAxiom>> explanations = new LinkedHashSet<>();
        explanations.add(just);

        Map<String, Object> out = invoke("explanationsJson",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, Set.class },
                mm, target, explanations);

        List<?> justs = (List<?>) out.get("justifications");
        @SuppressWarnings("unchecked")
        Map<String, Object> j = (Map<String, Object>) justs.get(0);
        assertEquals(2, j.get("size"), "size counts both axioms");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> axioms = (List<Map<String, Object>>) j.get("axioms");
        String r0 = String.valueOf(axioms.get(0).get("rendering"));
        String r1 = String.valueOf(axioms.get(1).get("rendering"));
        assertTrue(r0.compareTo(r1) <= 0, "axioms sorted ascending by rendering: " + r0 + " vs " + r1);
    }

    @Test
    void explanationsJsonHandlesMultipleJustifications() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom target = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));

        Set<OWLAxiom> justOne = new LinkedHashSet<>();
        justOne.add(df.getOWLDeclarationAxiom(cls(df, "A")));
        Set<OWLAxiom> justTwo = new LinkedHashSet<>();
        justTwo.add(df.getOWLDeclarationAxiom(cls(df, "B")));
        justTwo.add(df.getOWLDeclarationAxiom(cls(df, "C")));
        Set<Set<OWLAxiom>> explanations = new LinkedHashSet<>();
        explanations.add(justOne);
        explanations.add(justTwo);

        Map<String, Object> out = invoke("explanationsJson",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, Set.class },
                mm, target, explanations);

        assertEquals(2, out.get("justification_count"), "two justifications");
        List<?> justs = (List<?>) out.get("justifications");
        assertEquals(2, justs.size(), "two justification entries");
        int totalSizes = 0;
        for (Object jo : justs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> j = (Map<String, Object>) jo;
            totalSizes += (Integer) j.get("size");
        }
        assertEquals(3, totalSizes, "1 + 2 axioms across the two justifications");
    }

    @Test
    void explanationsJsonExposesExpectedTopLevelKeys() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom target = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        Map<String, Object> out = invoke("explanationsJson",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, Set.class },
                mm, target, new LinkedHashSet<Set<OWLAxiom>>());
        for (String k : new String[] { "axiom", "entailed", "justification_count", "justifications" }) {
            assertTrue(out.containsKey(k), "top-level key present: " + k);
        }
    }

    // ================================================================ structuralExplanation

    @Test
    void structuralExplanationNotEntailedOmitsRelatedAxioms() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));

        Map<String, Object> out = invoke("structuralExplanation",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, String.class, boolean.class },
                mm, ax, "subproperty_of", false);

        assertEquals(Boolean.FALSE, out.get("entailed"), "not entailed");
        assertEquals(Boolean.FALSE, out.get("justification_available"), "no minimal justification");
        assertFalse(out.containsKey("related_axioms"), "no related_axioms when not entailed");
        assertTrue(String.valueOf(out.get("note")).contains("NOT entailed"),
                "note explains there is nothing to justify");
    }

    @Test
    void structuralExplanationEntailedIncludesRelatedAxioms() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/rt-struct");
        OWLObjectProperty hasParent = df.getOWLObjectProperty(IRI.create(NS + "hasParent"));
        OWLObjectProperty hasAncestor = df.getOWLObjectProperty(IRI.create(NS + "hasAncestor"));
        // A logical axiom referencing hasParent, so relatedAssertedAxioms finds it.
        OWLAxiom subProp = df.getOWLSubObjectPropertyOfAxiom(hasParent, hasAncestor);
        m.addAxiom(o, subProp);

        OWLModelManager mm = FakeModelManager.over(o);
        OWLAxiom ax = df.getOWLSubObjectPropertyOfAxiom(hasParent, hasAncestor);

        Map<String, Object> out = invoke("structuralExplanation",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, String.class, boolean.class },
                mm, ax, "subproperty_of", true);

        assertEquals(Boolean.TRUE, out.get("entailed"), "entailed");
        assertEquals(Boolean.FALSE, out.get("justification_available"), "still no minimal justification");
        assertTrue(out.containsKey("related_axioms"), "related_axioms present when entailed");
        @SuppressWarnings("unchecked")
        Map<String, Object> related = (Map<String, Object>) out.get("related_axioms");
        assertTrue(((Number) related.get("count")).intValue() >= 1,
                "at least the referencing sub-property axiom is collected");
    }

    @Test
    void structuralExplanationNoteMentionsAxiomType() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));

        Map<String, Object> out = invoke("structuralExplanation",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, String.class, boolean.class },
                mm, ax, "functional_property", false);

        String note = String.valueOf(out.get("note"));
        assertTrue(note.contains("functional_property"), "note mentions the offending axiom_type");
        assertTrue(note.contains("subclass_of"), "note lists the supported axiom types");
    }

    @Test
    void structuralExplanationExposesExpectedKeys() throws Throwable {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = mm.getOWLDataFactory();
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        Map<String, Object> out = invoke("structuralExplanation",
                new Class<?>[] { OWLModelManager.class, OWLAxiom.class, String.class, boolean.class },
                mm, ax, "subproperty_of", false);
        for (String k : new String[] { "axiom", "entailed", "justification_available", "note" }) {
            assertTrue(out.containsKey(k), "key present: " + k);
        }
    }

    // ================================================================ relatedAssertedAxioms

    @Test
    void relatedAssertedAxiomsEmptyOntologyReturnsEmpty() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/rt-rel-empty");
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));

        Set<OWLAxiom> out = invoke("relatedAssertedAxioms",
                new Class<?>[] { OWLOntology.class, OWLAxiom.class }, o, ax);
        assertTrue(out.isEmpty(), "nothing referenced in an empty ontology");
    }

    @Test
    void relatedAssertedAxiomsReturnsReferencingLogicalAxioms() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/rt-rel");
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass mammal = cls(df, "Mammal");
        OWLAxiom ref = df.getOWLSubClassOfAxiom(dog, mammal); // references Dog
        m.addAxiom(o, ref);

        OWLAxiom query = df.getOWLSubClassOfAxiom(dog, animal); // signature includes Dog
        Set<OWLAxiom> out = invoke("relatedAssertedAxioms",
                new Class<?>[] { OWLOntology.class, OWLAxiom.class }, o, query);
        assertTrue(out.contains(ref), "logical axiom referencing Dog is collected");
    }

    @Test
    void relatedAssertedAxiomsFiltersOutNonLogicalAxioms() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/rt-rel-nonlogical");
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        // Declaration axioms are NOT logical axioms — must be filtered out.
        OWLAxiom decl = df.getOWLDeclarationAxiom(dog);
        m.addAxiom(o, decl);
        // An annotation assertion referencing Dog is likewise non-logical.
        OWLAxiom ann = df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), dog.getIRI(),
                df.getOWLLiteral("a dog"));
        m.addAxiom(o, ann);

        OWLAxiom query = df.getOWLSubClassOfAxiom(dog, animal);
        Set<OWLAxiom> out = invoke("relatedAssertedAxioms",
                new Class<?>[] { OWLOntology.class, OWLAxiom.class }, o, query);
        assertFalse(out.contains(decl), "declaration axiom filtered out (not logical)");
        assertFalse(out.contains(ann), "annotation assertion filtered out (not logical)");
        assertTrue(out.isEmpty(), "only non-logical referencing axioms exist => empty result");
    }

    @Test
    void relatedAssertedAxiomsSkipsBuiltinEntities() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/rt-rel-builtin");
        OWLClass dog = cls(df, "Dog");
        // A logical axiom that mentions owl:Thing (built-in) and Dog.
        OWLAxiom subThing = df.getOWLSubClassOfAxiom(dog, df.getOWLThing());
        m.addAxiom(o, subThing);

        // Query axiom whose only non-builtin entity is owl:Thing's counterpart: use owl:Nothing subclass
        // referencing only built-ins so the loop skips them. SubClassOf(Nothing, Thing) has only builtins.
        OWLAxiom builtinsOnly = df.getOWLSubClassOfAxiom(df.getOWLNothing(), df.getOWLThing());
        Set<OWLAxiom> out = invoke("relatedAssertedAxioms",
                new Class<?>[] { OWLOntology.class, OWLAxiom.class }, o, builtinsOnly);
        assertTrue(out.isEmpty(), "built-in entities are skipped, so nothing is collected");
    }

    @Test
    void relatedAssertedAxiomsSearchesImportsClosure() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        IRI importedIri = IRI.create("http://example.org/rt-rel-imported");
        OWLOntology imported = m.createOntology(importedIri);
        OWLClass dog = cls(df, "Dog");
        OWLAxiom importedRef = df.getOWLSubClassOfAxiom(dog, cls(df, "Mammal"));
        m.addAxiom(imported, importedRef);

        OWLOntology active = ontology(m, "http://example.org/rt-rel-active");
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(importedIri)));

        OWLAxiom query = df.getOWLSubClassOfAxiom(dog, cls(df, "Animal"));
        Set<OWLAxiom> out = invoke("relatedAssertedAxioms",
                new Class<?>[] { OWLOntology.class, OWLAxiom.class }, active, query);
        assertTrue(out.contains(importedRef),
                "referencing axiom in an imported ontology is found via the imports closure");
    }

    @Test
    void relatedAssertedAxiomsCapsCollectionAtLimit() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/rt-rel-cap");
        OWLClass dog = cls(df, "Dog");
        // Add far more than RELATED_AXIOM_CAP (500) distinct logical axioms referencing Dog.
        for (int i = 0; i < 700; i++) {
            m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, cls(df, "Super" + i)));
        }
        OWLAxiom query = df.getOWLSubClassOfAxiom(dog, cls(df, "Animal"));
        Set<OWLAxiom> out = invoke("relatedAssertedAxioms",
                new Class<?>[] { OWLOntology.class, OWLAxiom.class }, o, query);
        assertEquals(500, out.size(), "collection capped at RELATED_AXIOM_CAP (500)");
    }

    @Test
    void relatedAssertedAxiomsWithUnreferencedEntitiesReturnsEmpty() throws Throwable {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ontology(m, "http://example.org/rt-rel-unref");
        // Ontology has an axiom about unrelated entities.
        m.addAxiom(o, df.getOWLSubClassOfAxiom(cls(df, "Cat"), cls(df, "Feline")));

        OWLAxiom query = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        Set<OWLAxiom> out = invoke("relatedAssertedAxioms",
                new Class<?>[] { OWLOntology.class, OWLAxiom.class }, o, query);
        assertTrue(out.isEmpty(), "no axiom references the query entities => empty");
    }

    // ================================================================ EXPLAINABLE_AXIOM_TYPES constant

    @Test
    void explainableAxiomTypesConstantHoldsExpectedMembers() throws Throwable {
        java.lang.reflect.Field f = ReasonerTools.class.getDeclaredField("EXPLAINABLE_AXIOM_TYPES");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> types = (Set<String>) f.get(null);
        assertTrue(types.contains("subclass_of"), "subclass_of is explainable");
        assertTrue(types.contains("class_assertion"), "class_assertion is explainable");
        assertTrue(types.contains("same_individual"), "same_individual is explainable");
        assertFalse(types.contains("subproperty_of"), "property-hierarchy is NOT in the explainable set");
        assertFalse(types.contains("functional_property"),
                "property-characteristics are NOT in the explainable set");
    }
}
