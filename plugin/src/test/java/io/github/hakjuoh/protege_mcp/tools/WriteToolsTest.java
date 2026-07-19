package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.history.UndoManagerListener;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
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
    void formatForPathObo() throws Throwable {
        assertInstanceOf(org.semanticweb.owlapi.formats.OBODocumentFormat.class,
                callFormatForPath("/tmp/a.obo", null));
        assertInstanceOf(org.semanticweb.owlapi.formats.OBODocumentFormat.class,
                callFormatForPath("/tmp/A.OBO", null), "extension matching is case-insensitive");
    }

    @Test
    void formatForPathUnknownExtensionIsAnError() throws Throwable {
        // 0.4.3: an unrecognized extension used to silently fall back to the current format (or
        // RDF/XML) — saving pets.obo as RDF/XML. It is now a hard error naming the supported set.
        ToolArgException e = assertThrows(ToolArgException.class,
                () -> callFormatForPath("/tmp/a.unknown", new TurtleDocumentFormat()));
        assertTrue(e.getMessage().contains("Unrecognized ontology file extension '.unknown'"),
                "names the offending extension: " + e.getMessage());
        assertTrue(e.getMessage().contains(".obo"), "lists the supported extensions");
    }

    @Test
    void formatForPathNoExtensionFallsBackToCurrent() throws Throwable {
        OWLDocumentFormat current = new TurtleDocumentFormat();
        assertTrue(callFormatForPath("/tmp/ontology-file", current) == current,
                "a path with no extension keeps the current format");
    }

    @Test
    void formatForPathNoExtensionNoCurrentDefaultsToRdfXml() throws Throwable {
        assertInstanceOf(RDFXMLDocumentFormat.class, callFormatForPath("/tmp/ontology-file", null),
                "no extension and no current format → RDF/XML");
    }

    @Test
    void formatForPathDotFileHasNoExtension() throws Throwable {
        OWLDocumentFormat current = new TurtleDocumentFormat();
        assertTrue(callFormatForPath("/tmp/.hidden", current) == current,
                "a dotfile name is not an extension");
        assertTrue(callFormatForPath("/tmp/trailing.", current) == current,
                "a trailing dot is not an extension");
    }

    @Test
    void formatForPathDottedDirectoryDoesNotCountAsExtension() throws Throwable {
        OWLDocumentFormat current = new TurtleDocumentFormat();
        assertTrue(callFormatForPath("/tmp/v1.2/ontology", current) == current,
                "only the file NAME's extension counts, not a dot in a directory");
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

    private CallToolResult callSaveOntology(OWLModelManager mm, String path, String onLossy)
            throws Throwable {
        return (CallToolResult) invoke(
                priv("saveOntology", OWLModelManager.class, String.class, String.class, Map.class),
                mm, path, onLossy, null);
    }

    /** A single-label class in an OBO-friendly ontology (round-trips through the OBO frame model). */
    private OWLOntology oboFriendlyOntology() throws Exception {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        o.getOWLOntologyManager().addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Dog", "en")));
        return o;
    }

    // ---- 0.7.0 M3R format safeguards (lossy warnings / OBO report / on_lossy) ----

    @Test
    void plainTurtleSaveHasNoLossyWarningAndKeepsTheReleasedShape() throws Throwable {
        OWLOntology o = emptyOntology();
        declaredClass(o, "Dog");
        OWLModelManager mm = mutatingManager(o);
        File out = new File(Files.createTempDirectory("save-lossless").toFile(), "pets.ttl");

        Map<String, Object> m = structured(callSaveOntology(mm, out.getPath()));
        assertEquals(Boolean.TRUE, m.get("saved"));
        assertEquals("TurtleDocumentFormat", m.get("format"));
        assertFalse(m.containsKey("lossy_format_warning"), "a lossless format must not warn");
        assertFalse(m.containsKey("obo_compatibility"), "non-OBO save carries no OBO report");
    }

    @Test
    void plainOboSaveOfCompatibleOntologyAttachesReportAndSavesWithoutWarning() throws Throwable {
        // OBO can represent a single-label term: warn mode (default) saves, attaches the compatible OBO
        // report, and adds NO lossy_format_warning.
        OWLModelManager mm = mutatingManager(oboFriendlyOntology());
        File out = new File(Files.createTempDirectory("save-obo-ok").toFile(), "pets.obo");

        Map<String, Object> m = structured(callSaveOntology(mm, out.getPath(), "warn"));
        assertEquals(Boolean.TRUE, m.get("saved"), () -> m.toString());
        assertTrue(out.isFile(), "a compatible OBO save writes the file");
        @SuppressWarnings("unchecked")
        Map<String, Object> obo = (Map<String, Object>) m.get("obo_compatibility");
        assertNotNull(obo, () -> "OBO save carries a compatibility report: " + m);
        assertEquals(Boolean.TRUE, obo.get("compatible"));
        assertFalse(m.containsKey("lossy_format_warning"), "a compatible OBO save must not warn");
    }

    @Test
    void plainOboSaveOfMultiLabelOntologyOnLossyFailReportsAndRefuses() throws Throwable {
        // OBO's frame model rejects a second rdfs:label. on_lossy=fail refuses before writing with the
        // obo_compatibility report attached and the target untouched.
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        o.getOWLOntologyManager().addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Dog", "en")));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Hund", "de")));
        OWLModelManager mm = mutatingManager(o);
        File out = new File(Files.createTempDirectory("save-obo-fail").toFile(), "pets.obo");

        Map<String, Object> m = structured(callSaveOntology(mm, out.getPath(), "fail"));
        assertEquals(Boolean.FALSE, m.get("saved"), () -> m.toString());
        assertEquals("lossy_format_refused", m.get("error_code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> obo = (Map<String, Object>) m.get("obo_compatibility");
        assertNotNull(obo, () -> "OBO save carries a compatibility report: " + m);
        assertEquals(Boolean.FALSE, obo.get("compatible"));
        assertTrue(String.valueOf(obo.get("issues")).contains("multiple_labels"), () -> obo.toString());
        assertFalse(out.exists(), "on_lossy=fail must not write the target");
    }

    @Test
    void invalidOnLossyValueIsARejectedToolArgument() throws Throwable {
        assertNull(WriteTools.normalizeOnLossy("nope"), "an unknown on_lossy value is rejected");
        assertEquals("warn", WriteTools.normalizeOnLossy(null), "missing on_lossy defaults to warn");
        assertEquals("fail", WriteTools.normalizeOnLossy("FAIL"), "value is case-insensitive");
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
    void saveOntologyRejectedExtensionLeavesNoDirectories() throws Throwable {
        // 0.4.3 review fix: argument validation (the unknown-extension error) must complete
        // before any filesystem effect — a rejected save-as used to leave the parent tree behind.
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        Path base = Files.createTempDirectory("write-tools-save-reject");
        File out = new File(base.toFile(), "x/y/pets.json");

        assertThrows(ToolArgException.class, () -> callSaveOntology(mm, out.getPath()));
        assertFalse(new File(base.toFile(), "x").exists(),
                "a rejected extension must not create the parent directory tree");
    }

    @Test
    void saveOntologyFailedSaveAsRestoresFormatAndDocumentBinding() throws Throwable {
        // 0.4.3 review fix: a save-as whose STORE fails (deterministic with OBO — its writer
        // rejects duplicate name clauses, i.e. two rdfs:labels on one entity) must not leave the
        // ontology bound to the broken format/path, or every later argument-less save (and the
        // GUI's File ▸ Save) would silently retry the failing target.
        OWLOntology o = emptyOntology();
        OWLOntologyManager om = o.getOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        om.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Dog", "en")));
        om.addAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), dog.getIRI(), df.getOWLLiteral("Hund", "de")));
        OWLModelManager mm = mutatingManager(o);
        Path dir = Files.createTempDirectory("write-tools-save-fail");
        File good = new File(dir.toFile(), "pets.ttl");
        assertEquals(Boolean.FALSE, callSaveOntology(mm, good.getPath()).isError(),
                "the Turtle save-as establishes a good binding");
        OWLDocumentFormat boundFormat = om.getOntologyFormat(o);
        IRI boundDoc = om.getOntologyDocumentIRI(o);

        File bad = new File(dir.toFile(), "pets.obo");
        ToolArgException e = assertThrows(ToolArgException.class,
                () -> callSaveOntology(mm, bad.getPath()));
        assertTrue(e.getMessage().contains("Save failed"),
                "the storage failure is surfaced: " + e.getMessage());
        assertSame(boundFormat, om.getOntologyFormat(o),
                "a failed save-as must not rebind the format");
        assertEquals(boundDoc, om.getOntologyDocumentIRI(o),
                "a failed save-as must not rebind the document IRI");
        assertEquals(Boolean.FALSE, callSaveOntology(mm, null).isError(),
                "the previous good target still works for argument-less saves");
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

    @Test
    void saveAsPreservesRegisteredPrefixes() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        // A prefix registered on the ontology's current format, exactly as set_prefix does.
        mm.getOWLOntologyManager().getOntologyFormat(o).asPrefixOWLOntologyFormat()
                .setPrefix("ex:", "http://example.org/w#");
        Path dir = Files.createTempDirectory("write-tools-save-prefix");
        File out = new File(dir.toFile(), "pets.ttl");

        callSaveOntology(mm, out.getPath());   // save-as installs a fresh TurtleDocumentFormat

        // In-memory: the prefix survives, so CURIE resolution keeps working after a save-as ...
        assertTrue(mm.getOWLOntologyManager().getOntologyFormat(o).asPrefixOWLOntologyFormat()
                        .getPrefixName2PrefixMap().containsKey("ex:"),
                "save-as must carry the registered prefixes into the new format (in memory)");
        // ... and on disk the Turtle header declares it.
        assertTrue(Files.readString(out.toPath()).contains("@prefix ex:"),
                "the written Turtle declares the custom prefix");
    }

    // ================================================================== F1: name optional when iri given

    @Test
    void localNameExtractsFragmentThenLastSegment() {
        assertEquals("Dog", WriteTools.localName("http://x#Dog"), "fragment after '#'");
        assertEquals("Dog", WriteTools.localName("http://x/y/Dog"), "segment after the last '/'");
        assertEquals("Dog", WriteTools.localName("Dog"), "a bare name is its own local part");
        // A trailing separator (a namespace IRI) yields the last segment, not the whole IRI.
        assertEquals("vocab", WriteTools.localName("http://x/vocab#"), "trailing '#' → last segment");
        assertEquals("vocab", WriteTools.localName("http://x/vocab/"), "trailing '/' → last segment");
        // An opaque IRI splits on the last ':'.
        assertEquals("Dog", WriteTools.localName("urn:example:Dog"), "opaque IRI → after the last ':'");
    }

    @Test
    void createEntityWithIriAndNoNameDerivesLocalNameLabel() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("iri", NS + "Dog");   // full IRI, no 'name'
        List<OWLOntologyChange> changes = new ArrayList<>();

        OWLEntity e = callCreateEntity(mm, "class", a, changes);
        assertEquals(NS + "Dog", e.getIRI().toString(), "the entity is created at the given IRI");
        assertTrue(changes.contains(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), e.getIRI(), df.getOWLLiteral("Dog")))),
                "F1: the default rdfs:label is derived from the IRI local part when 'name' is absent");
    }

    @Test
    void createEntityWithNeitherNameNorIriThrows() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = mutatingManager(o);
        Map<String, Object> a = new LinkedHashMap<>();   // neither 'name' nor 'iri'
        List<OWLOntologyChange> changes = new ArrayList<>();
        assertThrows(ToolArgException.class, () -> callCreateEntity(mm, "class", a, changes),
                "with neither a name nor an iri there is nothing to mint an IRI from");
    }

    // ================================================================== F3: declare used annotation properties

    private static final String SKOS_DEF = "http://www.w3.org/2004/02/skos/core#definition";

    @Test
    void declareUsedAnnotationPropertiesDeclaresNonBuiltinButNotBuiltin() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        OWLAnnotationProperty skosDef = df.getOWLAnnotationProperty(IRI.create(SKOS_DEF));
        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(skosDef, dog.getIRI(),
                df.getOWLLiteral("a domestic dog"))));
        changes.add(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), dog.getIRI(),
                df.getOWLLiteral("note"))));

        WriteTools.declareUsedAnnotationProperties(df, o, changes);

        assertTrue(changes.contains(new AddAxiom(o, df.getOWLDeclarationAxiom(skosDef))),
                "F3: an undeclared non-built-in annotation property is declared");
        assertFalse(changes.contains(new AddAxiom(o, df.getOWLDeclarationAxiom(df.getRDFSComment()))),
                "a built-in annotation property (rdfs:comment) is never declared");
    }

    @Test
    void declareUsedAnnotationPropertiesSkipsAlreadyDeclared() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        OWLAnnotationProperty skosDef = df.getOWLAnnotationProperty(IRI.create(SKOS_DEF));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(skosDef));   // already declared
        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(skosDef, dog.getIRI(),
                df.getOWLLiteral("x"))));

        WriteTools.declareUsedAnnotationProperties(df, o, changes);
        assertEquals(1, changes.size(), "an already-declared property gets no duplicate declaration");
    }

    @Test
    void declareUsedAnnotationPropertiesDeclaresPropertyUsedButUndeclaredInImport() throws Throwable {
        OWLOntologyManager om = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();
        OWLAnnotationProperty skosDef = df.getOWLAnnotationProperty(IRI.create(SKOS_DEF));
        // An imported ontology that USES skos:definition without declaring it: the property is then in
        // the closure SIGNATURE but isDeclared is false — the exact hole newEntities/declareMinted missed.
        IRI impIri = IRI.create("http://example.org/imp");
        OWLOntology imported = om.createOntology(impIri);
        om.addAxiom(imported, df.getOWLAnnotationAssertionAxiom(skosDef,
                IRI.create("http://example.org/imp#Thing"), df.getOWLLiteral("upstream use")));
        OWLOntology active = om.createOntology(IRI.create("http://example.org/active"));
        om.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(impIri)));
        assertTrue(active.getImportsClosure().stream().anyMatch(x -> x.containsEntityInSignature(skosDef)),
                "precondition: skos:definition is in the closure signature (via the import)");
        assertFalse(active.getImportsClosure().stream().anyMatch(x -> x.isDeclared(skosDef)),
                "precondition: skos:definition is not declared anywhere in the closure");

        OWLClass dog = df.getOWLClass(IRI.create("http://example.org/active#Dog"));
        om.addAxiom(active, df.getOWLDeclarationAxiom(dog));
        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(active, df.getOWLAnnotationAssertionAxiom(skosDef, dog.getIRI(),
                df.getOWLLiteral("a dog"))));

        WriteTools.declareUsedAnnotationProperties(df, active, changes);
        assertTrue(changes.contains(new AddAxiom(active, df.getOWLDeclarationAxiom(skosDef))),
                "F3: a property used-but-undeclared across the imports closure is declared locally, "
                        + "keeping the active ontology in OWL 2 DL");
    }

    @Test
    void applyAxiomDeclaresUsedAnnotationProperty() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = declaredClass(o, "Dog");
        OWLAnnotationProperty skosDef = df.getOWLAnnotationProperty(IRI.create(SKOS_DEF));
        OWLModelManager mm = mutatingManager(o);
        OWLAxiom ax = df.getOWLAnnotationAssertionAxiom(skosDef, dog.getIRI(), df.getOWLLiteral("a dog"));

        invoke(priv("applyAxiom", OWLModelManager.class, OWLOntology.class, OWLAxiom.class, boolean.class),
                mm, o, ax, false);
        assertTrue(o.isDeclared(skosDef),
                "F3 wiring: the write path declares the annotation property it introduces");
    }

    // ================================================================== F6: OSGi-safe OWLManager creation

    @Test
    void owlManagersCreateReturnsUsableManager() throws Exception {
        OWLOntologyManager m = OwlManagers.create();
        assertNotNull(m, "a manager is returned");
        assertNotNull(m.createOntology(IRI.create("http://example.org/f6")), "it can create an ontology");
    }

    @Test
    void owlManagersCreateConcurrentReturnsUsableManager() throws Exception {
        OWLOntologyManager m = OwlManagers.createConcurrent();
        assertNotNull(m, "a concurrent manager is returned");
        assertNotNull(m.createOntology(IRI.create("http://example.org/f6c")), "it can create an ontology");
    }

    @Test
    void owlManagersRestoresContextClassLoader() {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        OwlManagers.create();
        assertTrue(Thread.currentThread().getContextClassLoader() == before,
                "the thread context classloader is restored after creating a manager");
    }

    // ================================================================== saveAllDirty (0.4.3 save all=true)

    /**
     * A {@link MutatingHandler}-backed manager that also answers {@code getDirtyOntologies()} with a
     * scripted set — the real dirty tracking lives in Protégé's model manager, so the save-all path
     * needs it stubbed to be testable headless. Saves still write through the real OWL API manager.
     */
    private static OWLModelManager dirtyManager(OWLOntology ontology, Set<OWLOntology> dirty) {
        MutatingHandler mutating = new MutatingHandler(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(
                WriteToolsTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class },
                (proxy, method, args) -> "getDirtyOntologies".equals(method.getName())
                        ? dirty
                        : mutating.invoke(proxy, method, args));
    }

    private CallToolResult callSaveAllDirty(OWLModelManager mm) throws Throwable {
        return (CallToolResult) invoke(priv("saveAllDirty", OWLModelManager.class), mm);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(CallToolResult r, String key) {
        return (List<Map<String, Object>>) structured(r).get(key);
    }

    @Test
    void saveAllDirtyNothingDirtyReportsNothingToSave() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = dirtyManager(o, Collections.emptySet());

        CallToolResult r = callSaveAllDirty(mm);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.FALSE, r.isError(), "an empty dirty set is not an error");
        assertTrue(listOf(r, "saved").isEmpty(), "nothing was saved");
        assertTrue(listOf(r, "skipped").isEmpty(), "nothing was skipped");
        assertTrue(String.valueOf(m.get("message")).contains("Nothing to save"),
                "the message says there is nothing to save");
    }

    @Test
    void saveAllDirtyWritesFileBackedOntologyAndReportsPath() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLOntologyManager om = o.getOWLOntologyManager();
        Path dir = Files.createTempDirectory("write-tools-save-all");
        File out = new File(dir.toFile(), "pets.owl");
        om.setOntologyDocumentIRI(o, IRI.create(out));
        OWLModelManager mm = dirtyManager(o, Collections.singleton(o));

        CallToolResult r = callSaveAllDirty(mm);
        Map<String, Object> m = structured(r);
        List<Map<String, Object>> saved = listOf(r, "saved");
        assertEquals(1, saved.size(), "the one dirty file-backed ontology is saved");
        assertEquals("http://example.org/w", saved.get(0).get("ontology"),
                "the row names the ontology by its label");
        assertEquals(out.getAbsoluteFile().toString(), saved.get(0).get("path"),
                "the row carries the file path written to");
        assertTrue(listOf(r, "skipped").isEmpty(), "nothing was skipped");
        assertTrue(out.isFile(), "the ontology document was actually written to disk");
        assertEquals("1 of 1 modified ontology saved.", m.get("message"),
                "singular message for a single dirty ontology");
    }

    @Test
    void saveAllDirtySkipsOntologyWithoutFileDocument() throws Throwable {
        OWLOntology o = emptyOntology();
        // A fresh ontology's document IRI defaults to its (http:) ontology IRI — not a file.
        OWLModelManager mm = dirtyManager(o, Collections.singleton(o));

        CallToolResult r = callSaveAllDirty(mm);
        Map<String, Object> m = structured(r);
        assertTrue(listOf(r, "saved").isEmpty(), "nothing was saved");
        List<Map<String, Object>> skipped = listOf(r, "skipped");
        assertEquals(1, skipped.size(), "the web-document ontology is skipped, not failed");
        assertEquals("http://example.org/w", skipped.get(0).get("ontology"),
                "the skipped row names the ontology");
        assertTrue(String.valueOf(skipped.get(0).get("reason")).contains("no file document"),
                "the reason explains there is no file document to save to");
        assertEquals("0 of 1 modified ontology saved.", m.get("message"),
                "the message counts zero saves out of one dirty ontology");
    }

    // ================================================================== peekUndo (0.4.3 undo introspection)

    /** A scripted {@link HistoryManager}: a fixed undo stack; the mutators are unsupported. */
    private static final class ScriptedHistory implements HistoryManager {
        private final List<List<OWLOntologyChange>> stack;

        ScriptedHistory(List<List<OWLOntologyChange>> stack) {
            this.stack = stack;
        }

        @Override
        public void logChanges(List<? extends OWLOntologyChange> changes) {
            throw new UnsupportedOperationException("peek must not log changes");
        }

        @Override
        public boolean canUndo() {
            return !stack.isEmpty();
        }

        @Override
        public void undo() {
            throw new UnsupportedOperationException("peek must not undo");
        }

        @Override
        public boolean canRedo() {
            return false;
        }

        @Override
        public void redo() {
            throw new UnsupportedOperationException("peek must not redo");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("peek must not clear");
        }

        @Override
        public List<List<OWLOntologyChange>> getLoggedChanges() {
            return stack;
        }

        @Override
        public void addUndoManagerListener(UndoManagerListener listener) {
        }

        @Override
        public void removeUndoManagerListener(UndoManagerListener listener) {
        }
    }

    /**
     * A manager that answers {@code getHistoryManager()} with a scripted stack and delegates the rest
     * to the shared {@link FakeModelManager} (peekUndo only reads and renders, so no write-through is
     * needed) — the same delegation shape as {@link MutatingHandler}.
     */
    private static OWLModelManager historyManager(OWLOntology ontology,
            List<List<OWLOntologyChange>> stack) {
        OWLModelManager delegate = FakeModelManager.over(ontology);
        HistoryManager history = new ScriptedHistory(stack);
        return (OWLModelManager) Proxy.newProxyInstance(
                WriteToolsTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHistoryManager":
                            return history;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nextUndo(CallToolResult r) {
        return (Map<String, Object>) structured(r).get("next_undo");
    }

    @Test
    void peekUndoEmptyStackReportsNothingToUndo() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLModelManager mm = historyManager(o, Collections.emptyList());

        CallToolResult r = WriteTools.peekUndo(mm);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.FALSE, r.isError(), "peeking an empty stack is not an error");
        assertEquals(Boolean.TRUE, m.get("peek"), "the result is flagged as a peek");
        assertEquals(Boolean.FALSE, m.get("can_undo"), "nothing to undo");
        assertEquals(Boolean.FALSE, m.get("can_redo"), "nothing to redo");
        assertEquals(0, m.get("undo_depth"), "an empty stack has depth zero");
        assertTrue(String.valueOf(m.get("note")).contains("The undo stack is empty"),
                "the note explains the stack is empty");
        assertFalse(m.containsKey("next_undo"), "no next_undo key on an empty stack");
    }

    @Test
    void peekUndoRendersLastTransactionWithOpsInOrder() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        List<OWLOntologyChange> first = Collections.singletonList(
                new AddAxiom(o, df.getOWLDeclarationAxiom(dog)));
        List<OWLOntologyChange> last = Arrays.asList(
                new AddAxiom(o, df.getOWLSubClassOfAxiom(dog, animal)),
                new RemoveAxiom(o, df.getOWLDeclarationAxiom(animal)));
        OWLModelManager mm = historyManager(o, Arrays.asList(first, last));

        CallToolResult r = WriteTools.peekUndo(mm);
        Map<String, Object> m = structured(r);
        assertEquals(Boolean.TRUE, m.get("can_undo"), "a non-empty stack can undo");
        assertEquals(2, m.get("undo_depth"), "two logged transactions");
        Map<String, Object> next = nextUndo(r);
        assertNotNull(next, "next_undo is present");
        assertEquals(2, next.get("changes"), "the LAST transaction's change count");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sample = (List<Map<String, Object>>) next.get("sample");
        assertEquals(2, sample.size(), "both axiom changes are sampled");
        assertEquals("add", sample.get(0).get("op"), "the first change is the add");
        assertEquals("remove", sample.get(1).get("op"), "the second change is the remove");
        @SuppressWarnings("unchecked")
        Map<String, Object> axiom = (Map<String, Object>) sample.get(0).get("axiom");
        assertNotNull(axiom, "each sampled change carries an axiom map");
        assertEquals("SubClassOf", axiom.get("axiom_type"), "the axiom map has its type");
        assertNotNull(axiom.get("rendering"), "and a rendering");
        assertFalse(next.containsKey("non_axiom_changes"),
                "an all-axiom transaction has no non_axiom_changes key");
        assertTrue(String.valueOf(m.get("note")).contains("Nothing was undone"),
                "the note stresses that nothing was changed");
    }

    @Test
    void peekUndoCountsNonAxiomChangesWithoutRenderingThem() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        List<OWLOntologyChange> tx = Arrays.asList(
                new AddAxiom(o, df.getOWLDeclarationAxiom(cls(df, "Dog"))),
                new AddImport(o, df.getOWLImportsDeclaration(IRI.create("http://example.org/imp"))));
        OWLModelManager mm = historyManager(o, Collections.singletonList(tx));

        CallToolResult r = WriteTools.peekUndo(mm);
        Map<String, Object> next = nextUndo(r);
        assertEquals(2, next.get("changes"), "both changes are counted");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sample = (List<Map<String, Object>>) next.get("sample");
        assertEquals(1, sample.size(), "only the axiom change is rendered");
        assertEquals("add", sample.get(0).get("op"), "the rendered change is the axiom add");
        assertEquals(1, next.get("non_axiom_changes"),
                "the import change is counted under non_axiom_changes, not rendered");
    }

    @Test
    void peekUndoCapsSampleAtTwentyAxiomChanges() throws Throwable {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        List<OWLOntologyChange> tx = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tx.add(new AddAxiom(o, df.getOWLDeclarationAxiom(cls(df, "C" + i))));
        }
        OWLModelManager mm = historyManager(o, Collections.singletonList(tx));

        CallToolResult r = WriteTools.peekUndo(mm);
        Map<String, Object> next = nextUndo(r);
        assertEquals(25, next.get("changes"), "the true change count is reported uncapped");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sample = (List<Map<String, Object>>) next.get("sample");
        assertEquals(20, sample.size(), "the sample is capped at 20");
    }
}
