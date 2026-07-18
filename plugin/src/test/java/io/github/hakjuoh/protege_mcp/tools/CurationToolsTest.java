package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import org.protege.editor.owl.model.classexpression.OWLExpressionParserException;
import org.protege.editor.owl.ui.clsdescriptioneditor.OWLExpressionChecker;
import org.protege.editor.owl.ui.clsdescriptioneditor.OWLExpressionCheckerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Exercises the Protégé-free change builders behind the curation macros (create_term / deprecate /
 * move_class) on hand-built ontologies, plus the 0.4.3 batch cores ({@code createTermsBatch} /
 * {@code createPropertiesBatch} / {@code applyBatchCurationData}) over {@link FakeModelManager}
 * (which routes {@code applyChanges} to the real in-memory manager) and the create_terms /
 * create_properties tool specs (verify / timeout_ms).
 */
class CurationToolsTest {

    private static final String NS = "http://example.org/cur#";

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private OWLOntology ont(OWLOntologyManager m) throws OWLOntologyCreationException {
        return m.createOntology(IRI.create("http://example.org/cur"));
    }

    // ---------------------------------------------------------------- create_term

    @Test
    void termAxiomsBuildParentEquivalentAndDefinition() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");

        List<OWLOntologyChange> changes = CurationTools.termAxioms(df, o, dog,
                Collections.singletonList(animal), Collections.singletonList(pet),
                df.getRDFSComment(), "A domestic dog.", "en", Collections.emptySet());

        assertTrue(contains(changes, df.getOWLSubClassOfAxiom(dog, animal)), "parent SubClassOf added");
        assertTrue(contains(changes, df.getOWLEquivalentClassesAxiom(dog, pet)), "equivalent-class added");
        assertTrue(contains(changes, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSComment(), dog.getIRI(), df.getOWLLiteral("A domestic dog.", "en"))),
                "definition annotation added");
    }

    // ---------------------------------------------------------------- deprecate_entity

    @Test
    void deprecationAddsDeprecatedFlagAndReplacedBy() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");
        IRI replacement = IRI.create(NS + "NewTerm");
        OWLAnnotationProperty replacedByProp = df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY);
        OWLAnnotationProperty deprecated =
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI());

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, replacement,
                replacedByProp, Collections.emptySet());
        assertTrue(contains(changes, df.getOWLAnnotationAssertionAxiom(
                deprecated, old.getIRI(), df.getOWLLiteral(true))), "owl:deprecated true added");
        assertTrue(contains(changes, df.getOWLAnnotationAssertionAxiom(
                replacedByProp, old.getIRI(), replacement)), "term-replaced-by pointer added");
    }

    @Test
    void reDeprecatingIsANoOp() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass old = cls(df, "OldTerm");
        OWLAnnotationProperty deprecated =
                df.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI());
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(deprecated, old.getIRI(), df.getOWLLiteral(true)));

        List<OWLOntologyChange> changes = CurationTools.deprecationChanges(df, o, old, null,
                df.getOWLAnnotationProperty(CurationTools.TERM_REPLACED_BY), Collections.emptySet());
        assertTrue(changes.isEmpty(), "already-deprecated term with no new annotations yields no changes");
    }

    // ---------------------------------------------------------------- move_class

    @Test
    void moveReplacesNamedParentButKeepsRestrictionSupers() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        OWLObjectProperty hasPart = df.getOWLObjectProperty(IRI.create(NS + "hasPart"));
        OWLAxiom namedParent = df.getOWLSubClassOfAxiom(dog, animal);
        OWLAxiom restrictionParent = df.getOWLSubClassOfAxiom(dog,
                df.getOWLObjectSomeValuesFrom(hasPart, cls(df, "Leg")));
        m.addAxiom(o, namedParent);
        m.addAxiom(o, restrictionParent);

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, pet, false);
        assertTrue(changes.contains(new RemoveAxiom(o, namedParent)), "the named parent is removed");
        assertFalse(changes.contains(new RemoveAxiom(o, restrictionParent)),
                "the anonymous restriction superclass is preserved");
        assertTrue(contains(changes, df.getOWLSubClassOfAxiom(dog, pet)), "the new parent is added");
    }

    @Test
    void moveWithKeepOthersOnlyAdds() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLClass pet = cls(df, "Pet");
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, pet, true);
        assertEquals(1, changes.size(), "keep_other_parents adds without removing");
        assertTrue(changes.get(0) instanceof AddAxiom);
        assertTrue(contains(changes, df.getOWLSubClassOfAxiom(dog, pet)));
    }

    @Test
    void detachToRootRemovesNamedParentsOnly() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass dog = cls(df, "Dog");
        OWLClass animal = cls(df, "Animal");
        OWLAxiom namedParent = df.getOWLSubClassOfAxiom(dog, animal);
        m.addAxiom(o, namedParent);

        List<OWLOntologyChange> changes = CurationTools.moveClassChanges(df, o, dog, null, false);
        assertEquals(Collections.singletonList(new RemoveAxiom(o, namedParent)), changes,
                "detaching to a root removes the named parent and adds nothing");
    }

    // ---------------------------------------------------------------- characteristics

    @Test
    void characteristicNamesMapToTheRightAxioms() {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        for (String c : Arrays.asList("functional", "inverse_functional", "transitive", "symmetric",
                "asymmetric", "reflexive", "irreflexive")) {
            assertTrue(CurationTools.objectCharacteristicAxiom(df, c, p) instanceof OWLAxiom,
                    c + " maps to an axiom");
        }
        assertThrows(ToolArgException.class,
                () -> CurationTools.objectCharacteristicAxiom(df, "bogus", p));
        assertThrows(ToolArgException.class, () -> CurationTools.dataCharacteristicAxiom(df, "transitive",
                df.getOWLDataProperty(IRI.create(NS + "d"))), "a data property cannot be transitive");
    }

    // ---------------------------------------------------------------- createTermsBatch (batch core)

    @Test
    void createTermsBatchReturnsPayloadMapAndAppliesEveryTerm() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLClass animal = cls(df, "Animal");
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> dog = term("Dog", NS + "Dog");
        dog.put("parents", Collections.singletonList(NS + "Animal"));
        List<Map<String, Object>> terms = Arrays.asList(dog, term("Cat", NS + "Cat"));

        Object out = CurationTools.createTermsBatch(mm, terms, false, null, null);
        assertFalse(out instanceof CallToolResult,
                "the batch core returns the raw payload map, not a CallToolResult");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) out;
        assertEquals(2, payload.get("count"), "both terms are counted as created");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) payload.get("created");
        assertEquals(2, created.size(), "one created entry per term");
        assertEquals(NS + "Dog", created.get(0).get("iri"), "created entries keep the batch order");
        assertEquals(5, payload.get("applied"), "two declarations + two labels + one parent axiom");
        assertFalse(payload.containsKey("new_entities"), "declared operands mint nothing");
        assertTrue(o.isDeclared(cls(df, "Dog")), "Dog was declared");
        assertTrue(o.isDeclared(cls(df, "Cat")), "Cat was declared");
        assertTrue(o.containsAxiom(df.getOWLSubClassOfAxiom(cls(df, "Dog"), animal)),
                "Dog's parent SubClassOf was asserted");
        assertTrue(o.containsAxiom(df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), IRI.create(NS + "Dog"), df.getOWLLiteral("Dog"))),
                "Dog's default rdfs:label was asserted");
        assertTrue(o.containsAxiom(df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), IRI.create(NS + "Cat"), df.getOWLLiteral("Cat"))),
                "Cat's default rdfs:label was asserted");
    }

    @Test
    void createTermsBatchStrictRefusesUndeclaredParentAndAppliesNothing()
            throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLModelManager mm = checkerRejectingManager(o);

        Map<String, Object> dog = term("Dog", NS + "Dog");
        dog.put("parents", Collections.singletonList(NS + "GhostParent"));   // declared nowhere
        long before = o.getAxiomCount();

        Object out = CurationTools.createTermsBatch(mm, Collections.singletonList(dog),
                true, null, null);
        assertTrue(out instanceof CallToolResult, "a strict refusal comes back as a CallToolResult");
        CallToolResult r = (CallToolResult) out;
        assertEquals(Boolean.TRUE, r.isError(), "strict + a would-be-minted parent is an error");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.structuredContent();
        assertTrue(String.valueOf(body.get("error")).contains("Refusing to apply (strict)"),
                "the refusal names the strict guard: " + body.get("error"));
        assertEquals(before, o.getAxiomCount(), "nothing was applied under the strict refusal");
        assertFalse(o.isDeclared(cls(df, "Dog")),
                "not even the term's own declaration was applied");
    }

    @Test
    void createTermsBatchMalformedSecondTermAbortsTheWholeBatch() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLModelManager mm = FakeModelManager.over(o);
        List<Map<String, Object>> terms = Arrays.asList(
                term("Dog", NS + "Dog"),
                new LinkedHashMap<String, Object>());   // neither 'name' nor 'iri'
        long before = o.getAxiomCount();

        ToolArgException e = assertThrows(ToolArgException.class,
                () -> CurationTools.createTermsBatch(mm, terms, false, null, null));
        assertTrue(e.getMessage().contains("terms[1]"),
                "the error names the offending item: " + e.getMessage());
        assertTrue(e.getMessage().contains("Nothing was applied"), "the error confirms atomicity");
        assertEquals(before, o.getAxiomCount(), "the valid first term was NOT applied either");
        assertFalse(o.isDeclared(cls(df, "Dog")), "the first term's class was not declared");
    }

    @Test
    void createTermsBatchRejectsDuplicateIrisWithinOneBatch() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = ont(m);
        OWLModelManager mm = FakeModelManager.over(o);
        List<Map<String, Object>> terms = Arrays.asList(
                term("First", NS + "Dupe"), term("Second", NS + "Dupe"));
        long before = o.getAxiomCount();

        ToolArgException e = assertThrows(ToolArgException.class,
                () -> CurationTools.createTermsBatch(mm, terms, false, null, null));
        assertTrue(e.getMessage().contains("duplicates an earlier"),
                "two specs at one IRI are rejected: " + e.getMessage());
        assertTrue(e.getMessage().contains("terms[1]"),
                "the collision is attributed to the second spec");
        assertEquals(before, o.getAxiomCount(), "the collision aborts the whole batch");
    }

    // ---------------------------------------------------------------- createPropertiesBatch (batch core)

    @Test
    void createPropertiesBatchDeclaresTypedPropertiesWithLabels() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = ont(m);
        OWLModelManager mm = FakeModelManager.over(o);
        Map<String, Object> hasAge = term("hasAge", NS + "hasAge");
        hasAge.put("property_type", "data");
        List<Map<String, Object>> specs = Arrays.asList(term("hasPart", NS + "hasPart"), hasAge);

        Object out = CurationTools.createPropertiesBatch(mm, specs, false, null, null, null);
        assertFalse(out instanceof CallToolResult,
                "the batch core returns the raw payload map, not a CallToolResult");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) out;
        assertEquals(2, payload.get("count"), "both properties are counted as created");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> created = (List<Map<String, Object>>) payload.get("created");
        assertEquals(2, created.size(), "one created entry per property");
        assertTrue(o.isDeclared(df.getOWLObjectProperty(IRI.create(NS + "hasPart"))),
                "the default property_type declares an OBJECT property");
        assertTrue(o.isDeclared(df.getOWLDataProperty(IRI.create(NS + "hasAge"))),
                "property_type=data declares a DATA property");
        assertTrue(o.containsAxiom(df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), IRI.create(NS + "hasPart"), df.getOWLLiteral("hasPart"))),
                "hasPart's default rdfs:label was asserted");
        assertTrue(o.containsAxiom(df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), IRI.create(NS + "hasAge"), df.getOWLLiteral("hasAge"))),
                "hasAge's default rdfs:label was asserted");
    }

    // ---------------------------------------------------------------- applyBatchCuration wrapper

    @Test
    void applyBatchCurationWrapsThePayloadMapAsANonErrorResult() throws OWLOntologyCreationException {
        // Two identical fixtures: the raw payload from applyBatchCurationData on one must equal the
        // wrapper's structured content on the other (same {created, count, applied} shape).
        OWLOntologyManager m1 = OWLManager.createOWLOntologyManager();
        OWLDataFactory df1 = m1.getOWLDataFactory();
        OWLOntology o1 = ont(m1);
        OWLOntologyManager m2 = OWLManager.createOWLOntologyManager();
        OWLDataFactory df2 = m2.getOWLDataFactory();
        OWLOntology o2 = ont(m2);

        Object data = CurationTools.applyBatchCurationData(FakeModelManager.over(o1), o1,
                dogDeclarationChanges(o1, df1), false, dogSet(df1),
                Collections.singletonList(cls(df1, "Dog")));
        CallToolResult r = CurationTools.applyBatchCuration(FakeModelManager.over(o2), o2,
                dogDeclarationChanges(o2, df2), false, dogSet(df2),
                Collections.singletonList(cls(df2, "Dog")));

        assertTrue(data instanceof Map, "the data variant returns the raw payload map");
        assertEquals(Boolean.FALSE, r.isError(), "the wrapper reports success as a non-error result");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) data;
        assertEquals(new LinkedHashSet<>(Arrays.asList("created", "count", "applied")),
                payload.keySet(), "the payload shape is {created, count, applied}");
        assertEquals(payload, r.structuredContent(), "the wrapper's structured map IS the payload");
    }

    // ---------------------------------------------------------------- tool specs (verify / timeout_ms)

    @Test
    void createTermsSpecDeclaresVerifyAndTimeout() {
        SyncToolSpecification s = specByName("create_terms");
        Map<String, Object> props = properties(s);
        assertEquals("string", type(props, "verify"), "verify is a string arg");
        assertEquals("integer", type(props, "timeout_ms"), "timeout_ms is an integer arg");
        assertFalse(description(props, "verify").isBlank(), "verify is documented");
        assertFalse(description(props, "timeout_ms").isBlank(), "timeout_ms is documented");
        assertTrue(s.tool().description().contains("verify=report|rollback"),
                "the tool description advertises verify=report|rollback");
    }

    @Test
    void createPropertiesSpecDeclaresVerifyAndTimeout() {
        SyncToolSpecification s = specByName("create_properties");
        Map<String, Object> props = properties(s);
        assertEquals("string", type(props, "verify"), "verify is a string arg");
        assertEquals("integer", type(props, "timeout_ms"), "timeout_ms is an integer arg");
        Map<String, Object> termProps = properties(specByName("create_terms"));
        assertEquals(description(termProps, "verify"), description(props, "verify"),
                "both batch tools share verify wording in the catalog");
        assertEquals(description(termProps, "timeout_ms"), description(props, "timeout_ms"),
                "both batch tools share timeout wording in the catalog");
        assertTrue(s.tool().description().contains("verify=report|rollback"),
                "the tool description advertises verify=report|rollback");
    }

    // ---------------------------------------------------------------- harness helpers

    /** A minimal term/property spec map: {name, iri} (either may be null to omit it). */
    private Map<String, Object> term(String name, String iri) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (name != null) {
            t.put("name", name);
        }
        if (iri != null) {
            t.put("iri", iri);
        }
        return t;
    }

    /** The change pair a one-term batch produces: Declaration(Dog) + its default rdfs:label. */
    private List<OWLOntologyChange> dogDeclarationChanges(OWLOntology o, OWLDataFactory df) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(o, df.getOWLDeclarationAxiom(cls(df, "Dog"))));
        changes.add(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(
                df.getRDFSLabel(), IRI.create(NS + "Dog"), df.getOWLLiteral("Dog"))));
        return changes;
    }

    private Set<OWLEntity> dogSet(OWLDataFactory df) {
        return new LinkedHashSet<>(Collections.<OWLEntity>singletonList(cls(df, "Dog")));
    }

    /** A class-expression checker that rejects every input, like Protege's on an unknown name. */
    private static final OWLExpressionChecker<OWLClassExpression> REJECTING_CHECKER =
            new OWLExpressionChecker<OWLClassExpression>() {
                @Override
                public void check(String text) throws OWLExpressionParserException {
                    throw new OWLExpressionParserException(
                            new RuntimeException("headless: no live expression checker"));
                }

                @Override
                public OWLClassExpression createObject(String text) throws OWLExpressionParserException {
                    throw new OWLExpressionParserException(
                            new RuntimeException("headless: no live expression checker"));
                }
            };

    /**
     * A manager proxy for the batch cores: {@link FakeModelManager} for everything it implements
     * (reads, finder, rendering, applyChanges), plus a {@code getOWLExpressionCheckerFactory} whose
     * class-expression checker always throws {@link OWLExpressionParserException} — the way Protégé's
     * checker rejects an unresolvable operand — so {@code resolveClassExpression} exercises its
     * full-IRI minting fallback headless (mirrors the MutatingHandler pattern in WriteToolsTest).
     */
    private static OWLModelManager checkerRejectingManager(OWLOntology ontology) {
        OWLModelManager delegate = FakeModelManager.over(ontology);
        OWLExpressionCheckerFactory factory = (OWLExpressionCheckerFactory) Proxy.newProxyInstance(
                CurationToolsTest.class.getClassLoader(),
                new Class<?>[] { OWLExpressionCheckerFactory.class },
                (proxy, method, args) -> {
                    if ("getOWLClassExpressionChecker".equals(method.getName())) {
                        return REJECTING_CHECKER;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (OWLModelManager) Proxy.newProxyInstance(
                CurationToolsTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOWLExpressionCheckerFactory":
                            return factory;
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

    /** Build CurationTools' specs via the registry, as ReadToolsTest does for ReadTools. */
    private static List<SyncToolSpecification> curationToolSpecs() {
        ToolRegistry registry = new ToolRegistry();
        CurationTools.register(registry, new ToolContext(null, null));
        return registry.build();
    }

    private static SyncToolSpecification specByName(String name) {
        for (SyncToolSpecification s : curationToolSpecs()) {
            if (s.tool().name().equals(name)) {
                return s;
            }
        }
        throw new AssertionError("no tool named " + name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(SyncToolSpecification s) {
        Object p = s.tool().inputSchema().get("properties");
        assertNotNull(p, s.tool().name() + " declares a properties block");
        return (Map<String, Object>) p;
    }

    @SuppressWarnings("unchecked")
    private static String type(Map<String, Object> props, String name) {
        Object prop = props.get(name);
        assertNotNull(prop, "property '" + name + "' is declared");
        return (String) ((Map<String, Object>) prop).get("type");
    }

    @SuppressWarnings("unchecked")
    private static String description(Map<String, Object> props, String name) {
        Object prop = props.get(name);
        assertNotNull(prop, "property '" + name + "' is declared");
        return (String) ((Map<String, Object>) prop).get("description");
    }

    private boolean contains(List<OWLOntologyChange> changes, OWLAxiom ax) {
        for (OWLOntologyChange ch : changes) {
            if (ch instanceof AddAxiom && ((AddAxiom) ch).getAxiom().equals(ax)) {
                return true;
            }
        }
        return false;
    }
}
