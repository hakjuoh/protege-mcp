package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Method-level tests for {@link WriteTools}' Protégé-free helpers. The mutating helpers
 * ({@code applyAxiom}, {@code applyBatch}, {@code totalAxioms}, {@code saveOntology}) call
 * {@code applyChange(s)} / {@code getOntologies()} / {@code save()} on the manager, which the shared
 * {@link FakeModelManager} does not implement — so this test carries a small {@link #mutatingManager}
 * proxy that delegates those to a real in-memory {@link OWLOntologyManager} while mirroring the rest.
 * The GUI/entity-factory paths (the injected {@code WriteConfirmer} dialog, {@code createViaFactory})
 * stay out of scope.
 */
class WriteToolsTest {

    private static final String NS = "http://example.org/w#";

    // ------------------------------------------------------------------ fixtures / reflection

    private OWLOntology emptyOntology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        return m.createOntology(IRI.create("http://example.org/w"));
    }

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private static Method priv(String name, Class<?>... params) throws NoSuchMethodException {
        Method m = WriteTools.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    /** Unwrap the InvocationTargetException so assertThrows sees the real cause. */
    private static Object invoke(Method m, Object... args) throws Throwable {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    /**
     * A manager proxy that behaves like {@link FakeModelManager} for reads/rendering/finder but also
     * implements the write-through methods the mutating helpers need by delegating to the ontology's
     * real {@link OWLOntologyManager}. Renders any entity by its IRI short form.
     */
    private static OWLModelManager mutatingManager(OWLOntology ontology) {
        return (OWLModelManager) Proxy.newProxyInstance(
                WriteToolsTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class },
                new MutatingHandler(ontology));
    }

    private static final class MutatingHandler implements InvocationHandler {
        private final OWLOntology ontology;
        private final OWLModelManager delegate;

        MutatingHandler(OWLOntology ontology) {
            this.ontology = ontology;
            this.delegate = FakeModelManager.over(ontology);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "applyChange":
                    ontology.getOWLOntologyManager().applyChange((OWLOntologyChange) args[0]);
                    return null;
                case "applyChanges":
                    ontology.getOWLOntologyManager()
                            .applyChanges((List<? extends OWLOntologyChange>) args[0]);
                    return null;
                case "getOntologies":
                    return ontology.getOWLOntologyManager().getOntologies();
                case "save":
                    if (args != null && args.length == 1) {
                        ontology.getOWLOntologyManager().saveOntology((OWLOntology) args[0]);
                    } else {
                        ontology.getOWLOntologyManager().saveOntology(ontology);
                    }
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    // Everything else (data factory, active ontology, finder, rendering, manager) is
                    // handled exactly like the shared FakeModelManager.
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
            }
        }
    }

    /** Declare a class in the ontology so it is "known" (not minted) to newEntities checks. */
    private OWLClass declaredClass(OWLOntology o, String name) {
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass c = cls(df, name);
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(c));
        return c;
    }

    // ================================================================== applyAxiom

    @Test
    void applyAxiomAppliesWhenNotStrictAndNoMintedEntities() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass a = declaredClass(o, "Dog");
        OWLClass b = declaredClass(o, "Animal");
        OWLModelManager mm = mutatingManager(o);
        OWLAxiom ax = df.getOWLSubClassOfAxiom(a, b);

        CallToolResult r = (CallToolResult) invoke(
                priv("applyAxiom", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, boolean.class),
                mm, o, ax, false);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, m.get("applied"), "declared operands: axiom applied");
        assertTrue(o.containsAxiom(ax), "axiom is now in the ontology");
        assertFalse(m.containsKey("new_entities"), "no minted entities → no new_entities key");
        assertFalse(m.containsKey("note"), "present axiom carries no note");
    }

    @Test
    void applyAxiomReportsMintedNewEntitiesWhenNotStrict() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        // Neither class is declared → both are minted by the add.
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        OWLModelManager mm = mutatingManager(o);

        CallToolResult r = (CallToolResult) invoke(
                priv("applyAxiom", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, boolean.class),
                mm, o, ax, false);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, m.get("applied"), "non-strict add still applies");
        assertTrue(m.containsKey("new_entities"), "minted entities are reported");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ne = (List<Map<String, Object>>) m.get("new_entities");
        assertEquals(2, ne.size(), "both undeclared classes are new");
    }

    @Test
    void applyAxiomStrictRefusesWhenMintingAndDoesNotApply() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        OWLModelManager mm = mutatingManager(o);

        CallToolResult r = (CallToolResult) invoke(
                priv("applyAxiom", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, boolean.class),
                mm, o, ax, true);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, r.isError(), "strict + minting is an error result");
        assertTrue(String.valueOf(m.get("error")).contains("strict"), "error mentions strict");
        assertFalse(o.containsAxiom(ax), "nothing was applied under strict refusal");
    }

    @Test
    void applyAxiomStrictAppliesWhenNoMinting() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass a = declaredClass(o, "Dog");
        OWLClass b = declaredClass(o, "Animal");
        OWLAxiom ax = df.getOWLSubClassOfAxiom(a, b);
        OWLModelManager mm = mutatingManager(o);

        CallToolResult r = (CallToolResult) invoke(
                priv("applyAxiom", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, boolean.class),
                mm, o, ax, true);
        assertEquals(Boolean.FALSE, r.isError(), "strict with declared operands succeeds");
        assertTrue(o.containsAxiom(ax), "axiom applied");
    }

    // ================================================================== applied

    @Test
    void appliedPresentAxiomHasNoNote() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass a = declaredClass(o, "Dog");
        OWLClass b = declaredClass(o, "Animal");
        OWLAxiom ax = df.getOWLSubClassOfAxiom(a, b);
        o.getOWLOntologyManager().addAxiom(o, ax);
        OWLModelManager mm = mutatingManager(o);

        CallToolResult r = (CallToolResult) invoke(
                priv("applied", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, Set.class),
                mm, o, ax, Collections.emptySet());
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, m.get("applied"), "present axiom → applied=true");
        assertFalse(m.containsKey("note"), "present axiom → no note");
        assertFalse(m.containsKey("new_entities"), "empty minted set → no new_entities");
    }

    @Test
    void appliedAbsentAxiomHasNoEffectNote() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        // ax is not added to the ontology → containsAxiom is false.
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        OWLModelManager mm = mutatingManager(o);

        CallToolResult r = (CallToolResult) invoke(
                priv("applied", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, Set.class),
                mm, o, ax, Collections.emptySet());
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.FALSE, m.get("applied"), "absent axiom → applied=false");
        assertTrue(String.valueOf(m.get("note")).contains("No effect"), "absent axiom carries a note");
    }

    @Test
    void appliedNullMintedSetOmitsNewEntities() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass a = declaredClass(o, "Dog");
        OWLClass b = declaredClass(o, "Animal");
        OWLAxiom ax = df.getOWLSubClassOfAxiom(a, b);
        o.getOWLOntologyManager().addAxiom(o, ax);
        OWLModelManager mm = mutatingManager(o);

        CallToolResult r = (CallToolResult) invoke(
                priv("applied", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, Set.class),
                mm, o, ax, null);
        assertFalse(structured(r).containsKey("new_entities"), "null minted → no new_entities key");
    }

    @Test
    void appliedNonEmptyMintedListsEachEntity() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass a = declaredClass(o, "Dog");
        OWLClass b = declaredClass(o, "Animal");
        OWLAxiom ax = df.getOWLSubClassOfAxiom(a, b);
        o.getOWLOntologyManager().addAxiom(o, ax);
        OWLModelManager mm = mutatingManager(o);
        Set<OWLEntity> minted = new LinkedHashSet<>(Arrays.asList(a, b));

        CallToolResult r = (CallToolResult) invoke(
                priv("applied", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, Set.class),
                mm, o, ax, minted);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ne = (List<Map<String, Object>>) structured(r).get("new_entities");
        assertNotNull(ne, "new_entities present for a non-empty minted set");
        assertEquals(2, ne.size(), "one entry per minted entity");
        assertTrue(ne.get(0).containsKey("iri"), "each entry is an entityJson with an iri");
    }

    // ================================================================== mintError / renderMinted

    @Test
    void mintErrorRendersEachMintedEntity() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLModelManager mm = mutatingManager(o);
        Set<OWLEntity> minted = new LinkedHashSet<>();
        minted.add(cls(df, "Dog"));

        CallToolResult r = (CallToolResult) invoke(
                priv("mintError", OWLModelManager.class, Set.class), mm, minted);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, r.isError(), "mintError is an error result");
        String err = String.valueOf(m.get("error"));
        assertTrue(err.contains("Dog"), "error names the minted entity's short form");
        assertTrue(err.contains(NS + "Dog"), "error includes the full IRI");
    }

    @Test
    void renderMintedEmptySetIsEmptyString() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        // renderMinted moved to the shared EntityRendering helper (deduped across write/curation tools).
        String s = EntityRendering.renderMinted(mm, Collections.emptySet());
        assertEquals("", s, "no entities → empty rendering");
    }

    @Test
    void renderMintedSingleEntityFormatsNameAngleIri() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLModelManager mm = mutatingManager(o);
        Set<OWLEntity> one = Collections.singleton(cls(df, "Dog"));
        String s = EntityRendering.renderMinted(mm, one);
        assertEquals("Dog <" + NS + "Dog>", s, "single entity: 'name <iri>'");
    }

    @Test
    void renderMintedMultipleEntitiesCommaSeparated() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLModelManager mm = mutatingManager(o);
        Set<OWLEntity> two = new LinkedHashSet<>();
        two.add(cls(df, "Dog"));
        two.add(cls(df, "Cat"));
        String s = EntityRendering.renderMinted(mm, two);
        assertEquals("Dog <" + NS + "Dog>, Cat <" + NS + "Cat>", s, "comma-space separated list");
    }

    // ================================================================== totalAxioms

    @Test
    void totalAxiomsCountsAllLoadedOntologies() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass a = cls(df, "Dog");
        OWLClass b = cls(df, "Animal");
        OWLOntologyManager om = o.getOWLOntologyManager();
        om.addAxiom(o, df.getOWLDeclarationAxiom(a));
        om.addAxiom(o, df.getOWLDeclarationAxiom(b));
        om.addAxiom(o, df.getOWLSubClassOfAxiom(a, b));
        OWLModelManager mm = mutatingManager(o);

        long n = (long) invoke(priv("totalAxioms", OWLModelManager.class), mm);
        assertEquals(3L, n, "three asserted axioms are counted");
    }

    @Test
    void totalAxiomsEmptyOntologyIsZero() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        long n = (long) invoke(priv("totalAxioms", OWLModelManager.class), mm);
        assertEquals(0L, n, "an empty ontology has zero axioms");
    }

    // ================================================================== withStrict

    @Test
    void withStrictAddsStrictBooleanPropertyInPlace() throws Throwable {
        Map<String, Object> schema = Axioms.schema();
        Object result = invoke(priv("withStrict", Map.class), schema);
        assertTrue(result == schema, "schema is mutated and returned in place");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("strict"), "a 'strict' property is added");
        @SuppressWarnings("unchecked")
        Map<String, Object> strict = (Map<String, Object>) props.get("strict");
        assertEquals("boolean", strict.get("type"), "strict is a boolean property");
    }

    // ================================================================== applyChangesSchema

    @Test
    void applyChangesSchemaHasOperationsAndStrict() throws Throwable {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) invoke(priv("applyChangesSchema"));
        assertEquals("object", schema.get("type"), "the schema is an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("operations"), "operations[] from PreviewTools is present");
        assertTrue(props.containsKey("strict"), "the strict flag is added");
        @SuppressWarnings("unchecked")
        Map<String, Object> strict = (Map<String, Object>) props.get("strict");
        assertEquals("boolean", strict.get("type"), "strict is boolean");
        assertEquals(WriteTools.STRICT_DESC, strict.get("description"), "strict uses STRICT_DESC");
        // 0.4.0: the reasoner-verification knobs.
        assertTrue(props.containsKey("verify"), "the verify enum is added");
        @SuppressWarnings("unchecked")
        Map<String, Object> verify = (Map<String, Object>) props.get("verify");
        assertEquals("string", verify.get("type"), "verify is a string enum arg");
        assertTrue(props.containsKey("timeout_ms"), "the verify timeout is added");
    }

    // ================================================================== applyBatch

    private CallToolResult callApplyBatch(OWLModelManager mm, List<Map<String, Object>> ops,
            boolean strict) throws Throwable {
        return (CallToolResult) invoke(
                priv("applyBatch", OWLModelManager.class, List.class, boolean.class), mm, ops, strict);
    }

    private Map<String, Object> op(String axiomType, String op, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("axiom_type", axiomType);
        if (op != null) {
            m.put("op", op);
        }
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(CallToolResult r) {
        return (Map<String, Object>) structured(r).get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(CallToolResult r) {
        return (List<Map<String, Object>>) structured(r).get("operations");
    }

    @Test
    void applyBatchSingleAddAppliesAndSetsSingleUndo() throws Throwable {
        OWLOntology o = emptyOntology();
        declaredClass(o, "Dog");
        declaredClass(o, "Animal");
        OWLModelManager mm = mutatingManager(o);
        List<Map<String, Object>> ops = Collections.singletonList(
                op("subclass_of", "add", "sub", NS + "Dog", "super", NS + "Animal"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("added"), "one add applied");
        assertEquals(Boolean.TRUE, s.get("single_undo"), "changes were applied → single_undo true");
        assertEquals(Boolean.TRUE, rows(r).get(0).get("applied"), "row reports applied");
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"))),
                "the axiom is in the ontology");
    }

    @Test
    void applyBatchRemovePresentAxiom() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        OWLClass animal = declaredClass(o, "Animal");
        o.getOWLOntologyManager().addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));
        OWLModelManager mm = mutatingManager(o);
        List<Map<String, Object>> ops = Collections.singletonList(
                op("subclass_of", "remove", "sub", NS + "Dog", "super", NS + "Animal"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("removed"), "one remove applied");
        assertEquals(Boolean.TRUE, rows(r).get(0).get("removed"), "row reports removed");
        assertFalse(o.containsAxiom(df.getOWLSubClassOfAxiom(dog, animal)), "axiom is gone");
    }

    @Test
    void applyBatchRemoveAbsentAxiomIsNoOp() throws Throwable {
        OWLOntology o = emptyOntology();
        declaredClass(o, "Dog");
        declaredClass(o, "Animal");
        OWLModelManager mm = mutatingManager(o);
        List<Map<String, Object>> ops = Collections.singletonList(
                op("subclass_of", "remove", "sub", NS + "Dog", "super", NS + "Animal"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("no_ops"), "removing an absent axiom is a no-op");
        assertEquals(Boolean.FALSE, rows(r).get(0).get("removed"), "row reports not removed");
        assertEquals(Boolean.FALSE, s.get("single_undo"), "nothing applied → single_undo false");
    }

    @Test
    void applyBatchStrictAddThatWouldMintRecordsError() throws Throwable {
        OWLOntology o = emptyOntology();
        // Dog is not declared → a strict declaration add would mint it. 'declaration' resolves the
        // IRI directly (no Protégé expression checker), so this exercises the strict branch under the
        // headless fake.
        OWLModelManager mm = mutatingManager(o);
        List<Map<String, Object>> ops = Collections.singletonList(
                op("declaration", "add", "entity", NS + "Dog", "entity_type", "class"));

        CallToolResult r = callApplyBatch(mm, ops, true);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("errors"), "strict minting is an error");
        assertTrue(String.valueOf(rows(r).get(0).get("error")).contains("strict"),
                "row error mentions strict");
        assertEquals(Boolean.FALSE, s.get("single_undo"), "nothing applied");
    }

    @Test
    void applyBatchDuplicateAddIsAlreadyPresentNoOp() throws Throwable {
        OWLOntology o = emptyOntology();
        declaredClass(o, "Dog");
        declaredClass(o, "Animal");
        OWLModelManager mm = mutatingManager(o);
        Map<String, Object> add = op("subclass_of", "add", "sub", NS + "Dog", "super", NS + "Animal");
        // The same add twice in one batch: the second is a no-op via simAdded tracking.
        List<Map<String, Object>> ops = Arrays.asList(add, op("subclass_of", "add",
                "sub", NS + "Dog", "super", NS + "Animal"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("added"), "only the first add takes effect");
        assertEquals(1, s.get("no_ops"), "the duplicate is a no-op");
        Map<String, Object> secondRow = rows(r).get(1);
        assertEquals(Boolean.TRUE, secondRow.get("applied"), "duplicate reports applied=true");
        assertEquals("already present", secondRow.get("note"), "with an 'already present' note");
    }

    @Test
    void applyBatchMixedAddAndRemoveCountsCorrectly() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        OWLClass animal = declaredClass(o, "Animal");
        OWLClass pet = declaredClass(o, "Pet");
        o.getOWLOntologyManager().addAxiom(o, df.getOWLSubClassOfAxiom(dog, pet));
        OWLModelManager mm = mutatingManager(o);
        List<Map<String, Object>> ops = Arrays.asList(
                op("subclass_of", "add", "sub", NS + "Dog", "super", NS + "Animal"),
                op("subclass_of", "remove", "sub", NS + "Dog", "super", NS + "Pet"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("added"), "one add");
        assertEquals(1, s.get("removed"), "one remove");
        assertEquals(2, s.get("operations"), "two operations total");
        assertEquals(Boolean.TRUE, s.get("single_undo"), "batch applied together");
    }

    @Test
    void applyBatchBuildFailureRecordsPerOperationError() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        // subclass_of with no 'sub'/'super' → Axioms.build throws ToolArgException.
        List<Map<String, Object>> ops = Collections.singletonList(op("subclass_of", "add"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("errors"), "the build failure is counted");
        assertNotNull(rows(r).get(0).get("error"), "the row carries the error message");
    }

    @Test
    void applyBatchUnsupportedOpRecordsError() throws Throwable {
        OWLOntology o = emptyOntology();
        declaredClass(o, "Dog");
        declaredClass(o, "Animal");
        OWLModelManager mm = mutatingManager(o);
        List<Map<String, Object>> ops = Collections.singletonList(
                op("subclass_of", "toggle", "sub", NS + "Dog", "super", NS + "Animal"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        Map<String, Object> s = summary(r);
        assertEquals(1, s.get("errors"), "an unknown op is an error");
        assertTrue(String.valueOf(rows(r).get(0).get("error")).contains("Unsupported op"),
                "row error names the bad op");
    }

    @Test
    void applyBatchDefaultsMissingOpToAdd() throws Throwable {
        OWLOntology o = emptyOntology();
        declaredClass(o, "Dog");
        declaredClass(o, "Animal");
        OWLModelManager mm = mutatingManager(o);
        // No 'op' key → defaults to add.
        List<Map<String, Object>> ops = Collections.singletonList(
                op("subclass_of", null, "sub", NS + "Dog", "super", NS + "Animal"));

        CallToolResult r = callApplyBatch(mm, ops, false);
        assertEquals("add", rows(r).get(0).get("op"), "missing op defaults to add");
        assertEquals(1, summary(r).get("added"), "the default-add is applied");
    }

    // ================================================================== newEntitiesAfterSimulatedAdds

    @SuppressWarnings("unchecked")
    private Set<OWLEntity> callAfterSims(Set<OWLOntology> closure, Set<OWLAxiom> simAdded, OWLAxiom ax)
            throws Throwable {
        return (Set<OWLEntity>) invoke(
                priv("newEntitiesAfterSimulatedAdds", Set.class, Set.class, OWLAxiom.class),
                closure, simAdded, ax);
    }

    @Test
    void afterSimulatedAddsReturnsBrandNewEntities() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLAxiom ax = df.getOWLSubClassOfAxiom(cls(df, "Dog"), cls(df, "Animal"));
        Set<OWLEntity> out = callAfterSims(o.getImportsClosure(),
                Collections.<OWLAxiom>emptySet(), ax);
        assertEquals(2, out.size(), "both undeclared classes are new");
    }

    @Test
    void afterSimulatedAddsExcludesEntitiesFromEarlierBatchAdds() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        // Simulate an earlier add that already introduces Dog + Animal.
        OWLAxiom earlier = df.getOWLDeclarationAxiom(dog);
        OWLAxiom declAnimal = df.getOWLDeclarationAxiom(animal);
        Set<OWLAxiom> sim = new LinkedHashSet<>(Arrays.asList(earlier, declAnimal));
        OWLAxiom ax = df.getOWLSubClassOfAxiom(dog, animal);
        Set<OWLEntity> out = callAfterSims(o.getImportsClosure(), sim, ax);
        assertTrue(out.isEmpty(), "entities introduced by earlier batch adds are not counted new again");
    }

    @Test
    void afterSimulatedAddsEmptyWhenNoNewEntities() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        OWLClass animal = declaredClass(o, "Animal");
        OWLAxiom ax = df.getOWLSubClassOfAxiom(dog, animal);
        Set<OWLEntity> out = callAfterSims(o.getImportsClosure(),
                Collections.<OWLAxiom>emptySet(), ax);
        assertTrue(out.isEmpty(), "all operands already declared → nothing new");
    }

    // ================================================================== newEntitiesIntroducedByAxioms

    @SuppressWarnings("unchecked")
    private Set<OWLEntity> callIntroduced(Set<OWLOntology> closure, Set<OWLAxiom> axioms)
            throws Throwable {
        return (Set<OWLEntity>) invoke(
                priv("newEntitiesIntroducedByAxioms", Set.class, Set.class), closure, axioms);
    }

    @Test
    void introducedByAxiomsEmptySetIsEmpty() throws Throwable {
        OWLOntology o = emptyOntology();
        Set<OWLEntity> out = callIntroduced(o.getImportsClosure(), Collections.<OWLAxiom>emptySet());
        assertTrue(out.isEmpty(), "no axioms → no new entities");
    }

    @Test
    void introducedByAxiomsDeduplicatesAcrossAxioms() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        // Two axioms sharing 'Dog' → Dog counted once.
        Set<OWLAxiom> axioms = new LinkedHashSet<>(Arrays.asList(
                df.getOWLSubClassOfAxiom(dog, animal), df.getOWLSubClassOfAxiom(dog, pet)));
        Set<OWLEntity> out = callIntroduced(o.getImportsClosure(), axioms);
        assertEquals(3, out.size(), "Dog, Animal, Pet — Dog is not double counted");
    }

    // ================================================================== isFileDocument

    private boolean callIsFileDocument(IRI iri) throws Throwable {
        return (boolean) invoke(priv("isFileDocument", IRI.class), iri);
    }

    @Test
    void isFileDocumentNullIsFalse() throws Throwable {
        assertFalse(callIsFileDocument(null), "null IRI is not a file document");
    }

    @Test
    void isFileDocumentFileSchemeIsTrue() throws Throwable {
        assertTrue(callIsFileDocument(IRI.create(new File("/tmp/x.ttl"))),
                "a file: IRI is a file document");
    }

    @Test
    void isFileDocumentHttpSchemeIsFalse() throws Throwable {
        assertFalse(callIsFileDocument(IRI.create("http://example.org/x")),
                "an http: IRI is not a file document");
    }

    // ================================================================== formatForPath

    private OWLDocumentFormat callFormatForPath(String path, OWLDocumentFormat current) throws Throwable {
        return (OWLDocumentFormat) invoke(
                priv("formatForPath", String.class, OWLDocumentFormat.class), path, current);
    }

    @Test
    void formatForPathTtl() throws Throwable {
        assertInstanceOf(TurtleDocumentFormat.class, callFormatForPath("/tmp/a.ttl", null));
    }

    @Test
    void formatForPathTurtleUpperCase() throws Throwable {
        assertInstanceOf(TurtleDocumentFormat.class, callFormatForPath("/tmp/A.TURTLE", null),
                "extension matching is case-insensitive");
    }

    @Test
    void formatForPathOmn() throws Throwable {
        assertInstanceOf(ManchesterSyntaxDocumentFormat.class, callFormatForPath("/tmp/a.omn", null));
    }

    @Test
    void formatForPathOfnAndFss() throws Throwable {
        assertInstanceOf(FunctionalSyntaxDocumentFormat.class, callFormatForPath("/tmp/a.ofn", null));
        assertInstanceOf(FunctionalSyntaxDocumentFormat.class, callFormatForPath("/tmp/a.fss", null));
    }

    @Test
    void formatForPathOwx() throws Throwable {
        assertInstanceOf(OWLXMLDocumentFormat.class, callFormatForPath("/tmp/a.owx", null));
    }

    @Test
    void formatForPathOwlRdfXml() throws Throwable {
        assertInstanceOf(RDFXMLDocumentFormat.class, callFormatForPath("/tmp/a.owl", null));
        assertInstanceOf(RDFXMLDocumentFormat.class, callFormatForPath("/tmp/a.rdf", null));
        assertInstanceOf(RDFXMLDocumentFormat.class, callFormatForPath("/tmp/a.xml", null));
    }

    @Test
    void formatForPathUnknownFallsBackToCurrent() throws Throwable {
        OWLDocumentFormat current = new TurtleDocumentFormat();
        assertTrue(callFormatForPath("/tmp/a.unknown", current) == current,
                "an unrecognized extension keeps the current format");
    }

    @Test
    void formatForPathUnknownNoCurrentDefaultsToRdfXml() throws Throwable {
        assertInstanceOf(RDFXMLDocumentFormat.class, callFormatForPath("/tmp/a.unknown", null),
                "unrecognized extension with no current format → RDF/XML");
    }

    // ================================================================== isLabelChangeFor

    private boolean callIsLabelChangeFor(OWLOntologyChange change, OWLEntity e) throws Throwable {
        return (boolean) invoke(
                priv("isLabelChangeFor", OWLOntologyChange.class, OWLEntity.class), change, e);
    }

    @Test
    void isLabelChangeForAddedLabelOnEntityIsTrue() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        AddAxiom change = new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Dog")));
        assertTrue(callIsLabelChangeFor(change, dog), "an rdfs:label add on the entity matches");
    }

    @Test
    void isLabelChangeForNonLabelAnnotationIsFalse() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        AddAxiom change = new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSComment(), dog.getIRI(), df.getOWLLiteral("hi")));
        assertFalse(callIsLabelChangeFor(change, dog), "a comment is not a label change");
    }

    @Test
    void isLabelChangeForRemoveAxiomIsFalse() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        RemoveAxiom change = new RemoveAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Dog")));
        assertFalse(callIsLabelChangeFor(change, dog), "only AddAxiom counts as an auto-label add");
    }

    @Test
    void isLabelChangeForNonAnnotationAxiomIsFalse() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        AddAxiom change = new AddAxiom(o, df.getOWLDeclarationAxiom(dog));
        assertFalse(callIsLabelChangeFor(change, dog), "a declaration axiom is not a label assertion");
    }

    @Test
    void isLabelChangeForDifferentSubjectIsFalse() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        OWLClass cat = cls(df, "Cat");
        AddAxiom change = new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), cat.getIRI(), df.getOWLLiteral("Cat")));
        assertFalse(callIsLabelChangeFor(change, dog), "label on a different subject does not match");
    }

    // ================================================================== label

    private OWLLiteral callLabel(OWLDataFactory df, String text, String lang) throws Throwable {
        return (OWLLiteral) invoke(
                priv("label", OWLDataFactory.class, String.class, String.class), df, text, lang);
    }

    @Test
    void labelNullLangIsPlainLiteral() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLLiteral lit = callLabel(df, "Dog", null);
        assertEquals("Dog", lit.getLiteral(), "text is preserved");
        assertFalse(lit.hasLang(), "no language tag when lang is null");
    }

    @Test
    void labelWithLangIsTagged() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLLiteral lit = callLabel(df, "Dog", "en-US");
        assertEquals("Dog", lit.getLiteral(), "text is preserved");
        assertTrue(lit.hasLang(), "language tag present");
        assertEquals("en-us", lit.getLang(), "OWL API lower-cases the tag");
    }

    // ================================================================== joinNamespace

    private String callJoinNamespace(String namespace, String name) throws Throwable {
        return (String) invoke(priv("joinNamespace", String.class, String.class), namespace, name);
    }

    @Test
    void joinNamespaceEmptyNamespaceReturnsTrimmedName() throws Throwable {
        assertEquals("Dog", callJoinNamespace("", "  Dog "), "empty namespace → trimmed local name");
    }

    @Test
    void joinNamespaceEndingSlashHashColonHasNoExtraSeparator() throws Throwable {
        assertEquals("http://x/Dog", callJoinNamespace("http://x/", "Dog"), "slash: no extra separator");
        assertEquals("http://x#Dog", callJoinNamespace("http://x#", "Dog"), "hash: no extra separator");
        assertEquals("ns:Dog", callJoinNamespace("ns:", "Dog"), "colon: no extra separator");
    }

    @Test
    void joinNamespaceOtherCharInsertsSlash() throws Throwable {
        assertEquals("http://x/Dog", callJoinNamespace("http://x", "Dog"),
                "a non-delimiter last char inserts a '/'");
    }

    @Test
    void joinNamespaceStripsInternalAndSurroundingSpaces() throws Throwable {
        assertEquals("http://x/BigDog", callJoinNamespace("http://x/", " Big Dog "),
                "leading/trailing trimmed and internal spaces removed");
    }

    // ================================================================== entityAtIri

    private OWLEntity callEntityAtIri(OWLDataFactory df, String type, IRI iri) throws Throwable {
        return (OWLEntity) invoke(
                priv("entityAtIri", OWLDataFactory.class, String.class, IRI.class), df, type, iri);
    }

    @Test
    void entityAtIriBuildsEachTypedEntity() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        IRI iri = IRI.create(NS + "X");
        assertInstanceOf(OWLClass.class, callEntityAtIri(df, "class", iri));
        assertInstanceOf(OWLObjectProperty.class, callEntityAtIri(df, "object_property", iri));
        assertInstanceOf(OWLDataProperty.class, callEntityAtIri(df, "data_property", iri));
        assertInstanceOf(OWLAnnotationProperty.class, callEntityAtIri(df, "annotation_property", iri));
        assertInstanceOf(OWLNamedIndividual.class, callEntityAtIri(df, "individual", iri));
        assertInstanceOf(OWLDatatype.class, callEntityAtIri(df, "datatype", iri));
    }

    @Test
    void entityAtIriUnknownTypeThrows() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        IRI iri = IRI.create(NS + "X");
        assertThrows(ToolArgException.class,
                () -> callEntityAtIri(df, "widget", iri), "an unknown entity type is rejected");
    }

    // ================================================================== createEntity (full-IRI path)

    @SuppressWarnings("unchecked")
    private OWLEntity callCreateEntity(OWLModelManager mm, String type, Map<String, Object> a,
            List<OWLOntologyChange> changes) throws Throwable {
        return (OWLEntity) invoke(
                priv("createEntity", OWLModelManager.class, String.class, Map.class, List.class),
                mm, type, a, changes);
    }

    @Test
    void createEntityWithFullIriDeclaresAndLabels() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "Dog");
        a.put("iri", NS + "Dog");
        List<OWLOntologyChange> changes = new ArrayList<>();

        OWLEntity e = callCreateEntity(mm, "class", a, changes);
        assertInstanceOf(OWLClass.class, e, "a class is created");
        assertEquals(NS + "Dog", e.getIRI().toString(), "at the requested full IRI");
        assertTrue(changes.contains(new AddAxiom(o, df.getOWLDeclarationAxiom(e))),
                "a declaration axiom is queued");
        assertTrue(changes.contains(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), e.getIRI(), df.getOWLLiteral("Dog")))),
                "a default rdfs:label from 'name' is queued");
    }

    @Test
    void createEntityWithNamespaceMintsIriFromNamespacePlusName() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "Dog");
        a.put("namespace", "http://ns.example/");
        List<OWLOntologyChange> changes = new ArrayList<>();

        OWLEntity e = callCreateEntity(mm, "class", a, changes);
        assertEquals("http://ns.example/Dog", e.getIRI().toString(),
                "IRI = namespace + name when only a namespace is given");
    }

    @Test
    void createEntityNoLabelSkipsLabelAxiom() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "Dog");
        a.put("iri", NS + "Dog");
        a.put("no_label", true);
        List<OWLOntologyChange> changes = new ArrayList<>();

        OWLEntity e = callCreateEntity(mm, "class", a, changes);
        assertEquals(1, changes.size(), "only the declaration is queued when no_label is set");
        assertTrue(changes.contains(new AddAxiom(o, df.getOWLDeclarationAxiom(e))),
                "the single change is the declaration");
    }

    @Test
    void createEntityCustomLabelAndLangUsed() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "Dog");
        a.put("iri", NS + "Dog");
        a.put("label", "Perro");
        a.put("label_lang", "es");
        List<OWLOntologyChange> changes = new ArrayList<>();

        OWLEntity e = callCreateEntity(mm, "class", a, changes);
        assertTrue(changes.contains(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), e.getIRI(), df.getOWLLiteral("Perro", "es")))),
                "the custom label text and language tag are used");
    }

    @Test
    void createEntityUnknownTypeAtIriThrows() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "Dog");
        a.put("iri", NS + "Dog");
        List<OWLOntologyChange> changes = new ArrayList<>();
        assertThrows(ToolArgException.class,
                () -> callCreateEntity(mm, "widget", a, changes),
                "an unknown entity_type on the IRI path throws");
    }

    // ================================================================== saveOntology / saveOrThrow

    private CallToolResult callSaveOntology(OWLModelManager mm, String path) throws Throwable {
        return (CallToolResult) invoke(
                priv("saveOntology", OWLModelManager.class, String.class), mm, path);
    }

    @Test
    void saveOntologyWithPathWritesFileAndReportsFormat() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        Path dir = Files.createTempDirectory("write-tools-save");
        File out = new File(dir.toFile(), "pets.ttl");

        CallToolResult r = callSaveOntology(mm, out.getPath());
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, m.get("saved"), "save succeeds");
        assertEquals(out.getAbsoluteFile().toString(), m.get("path"), "the absolute path is reported");
        assertEquals("TurtleDocumentFormat", m.get("format"), ".ttl → Turtle");
        assertTrue(out.isFile(), "the file was actually written");
    }

    @Test
    void saveOntologyWithPathCreatesMissingParentDirs() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        Path base = Files.createTempDirectory("write-tools-save-nested");
        File out = new File(base.toFile(), "a/b/c/pets.owl");
        assertFalse(out.getParentFile().exists(), "parent dirs do not exist yet");

        CallToolResult r = callSaveOntology(mm, out.getPath());
        assertEquals(Boolean.FALSE, r.isError(), "save-as creates the parent tree");
        assertTrue(out.isFile(), "the nested file was written");
    }

    @Test
    void saveOntologyNoPathNeverSavedIsError() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        // Fresh ontology: its document IRI is the (non-file) ontology IRI.
        CallToolResult r = callSaveOntology(mm, null);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, r.isError(), "an untitled ontology cannot be saved without a path");
        assertTrue(String.valueOf(m.get("error")).contains("not been saved"),
                "error explains the ontology has no file yet");
    }

    @Test
    void saveOntologyNoPathAfterSaveAsReusesBoundFile() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        Path dir = Files.createTempDirectory("write-tools-save-reuse");
        File out = new File(dir.toFile(), "pets.owl");
        // First save-as binds the document IRI to a file: URI.
        callSaveOntology(mm, out.getPath());

        CallToolResult r = callSaveOntology(mm, null);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, m.get("saved"), "an argument-less save now works");
        assertEquals(out.getAbsoluteFile().toString(), m.get("path"),
                "it writes to the previously bound file");
    }
}
