package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Method-level tests for {@link ReadTools}.
 *
 * <p>The seven tool handlers registered by {@link ReadTools#register} route through
 * {@code ToolContext.access().compute(...)}, whose concrete {@code OntologyAccess} needs a live
 * {@code OWLEditorKit}/EDT — so the handlers cannot be driven headless. They are therefore covered
 * <em>structurally</em> (name / description / input-schema shape), which is all {@code specs()}
 * itself produces without touching the collaborators.
 *
 * <p>The pure private helpers ({@code logicalAxiomCount}, {@code increment}, {@code ontologyLabel},
 * {@code signature}, {@code summarize}, and the whole-signature branch of {@code search}) are driven
 * directly by reflection over in-memory OWL API ontologies and the shared {@link FakeModelManager}.
 * The finder-backed branches of {@code search} (non-blank fragment → {@code getMatchingOWL*}) are
 * intentionally skipped: {@code FakeModelManager}'s finder only implements
 * {@code getEntities(IRI)}/{@code getOWLEntity(String)} and throws for the {@code getMatchingOWL*}
 * calls, so those paths need the live Protégé entity finder.
 */
class ReadToolsTest {

    private static final String NS = "http://example.org/read#";

    // ------------------------------------------------------------------ fixtures / reflection

    private OWLOntologyManager mgr() {
        return OWLManager.createOWLOntologyManager();
    }

    private OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    private static Method priv(String name, Class<?>... params) throws NoSuchMethodException {
        Method m = ReadTools.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Set<? extends OWLEntity> callSearch(OWLModelManager mm, String query, String type)
            throws Exception {
        return (Set<? extends OWLEntity>) priv("search", OWLModelManager.class, String.class,
                String.class).invoke(null, mm, query, type);
    }

    @SuppressWarnings("unchecked")
    private static Set<? extends OWLEntity> callSignature(OWLModelManager mm, String type)
            throws Exception {
        return (Set<? extends OWLEntity>) priv("signature", OWLModelManager.class, String.class)
                .invoke(null, mm, type);
    }

    private static int callLogicalAxiomCount(Set<OWLAxiom> axioms) throws Exception {
        return (int) priv("logicalAxiomCount", Set.class).invoke(null, axioms);
    }

    @SuppressWarnings("unchecked")
    private static void callIncrement(Map<String, Integer> counts, String key) throws Exception {
        priv("increment", Map.class, String.class).invoke(null, counts, key);
    }

    private static String callOntologyLabel(OWLOntology o) throws Exception {
        return (String) priv("ontologyLabel", OWLOntology.class).invoke(null, o);
    }

    private static CallToolResult callSummarize(OWLOntology active, boolean includeImports, int limit)
            throws Exception {
        return (CallToolResult) priv("summarize", OWLOntology.class, boolean.class, int.class)
                .invoke(null, active, includeImports, limit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    // ================================================================== specs() — structural

    @Test
    void specsReturnsNonNullList() {
        List<SyncToolSpecification> specs = readToolSpecs();
        assertNotNull(specs, "specs() must never return null");
    }

    @Test
    void specsReturnsExactlySevenNamedTools() {
        List<SyncToolSpecification> specs = readToolSpecs();
        assertEquals(7, specs.size(), "ReadTools exposes seven read tools");
        List<String> names = new ArrayList<>();
        for (SyncToolSpecification s : specs) {
            names.add(s.tool().name());
        }
        assertEquals(List.of("list_ontologies", "get_active_ontology", "summarize_ontology",
                "list_classes", "search_entities", "get_entity", "get_axioms_for_entity"), names,
                "tool names and order match the source");
    }

    @Test
    void everySpecHasNonNullDescriptionSchemaAndHandler() {
        for (SyncToolSpecification s : readToolSpecs()) {
            Tool t = s.tool();
            assertNotNull(t.description(), t.name() + " has a description");
            assertFalse(t.description().isEmpty(), t.name() + " description is non-empty");
            assertNotNull(t.inputSchema(), t.name() + " has an input schema");
            assertEquals("object", t.inputSchema().get("type"), t.name() + " schema is an object");
            assertNotNull(s.callHandler(), t.name() + " has a call handler");
        }
    }

    @Test
    void listOntologiesUsesEmptySchema() {
        Tool t = specByName("list_ontologies").tool();
        assertEquals(Tools.emptySchema(), t.inputSchema(), "list_ontologies takes no arguments");
        assertFalse(t.inputSchema().containsKey("properties"),
                "empty schema declares no properties");
    }

    @Test
    void getActiveOntologyUsesEmptySchema() {
        assertEquals(Tools.emptySchema(), specByName("get_active_ontology").tool().inputSchema(),
                "get_active_ontology takes no arguments");
    }

    @Test
    void summarizeOntologySchemaDeclaresIncludeImportsAndLimit() {
        Map<String, Object> props = properties(specByName("summarize_ontology"));
        assertEquals("boolean", type(props, "include_imports"), "include_imports is a boolean");
        assertEquals("integer", type(props, "limit"), "limit is an integer");
        assertFalse(required(specByName("summarize_ontology")).contains("include_imports"),
                "include_imports is optional");
    }

    @Test
    void listClassesSchemaDeclaresLimitOnly() {
        Map<String, Object> props = properties(specByName("list_classes"));
        assertEquals("integer", type(props, "limit"), "limit is an integer");
        assertEquals(1, props.size(), "list_classes only declares limit");
    }

    @Test
    void searchEntitiesSchemaRequiresQueryAndDeclaresTypeAndLimit() {
        SyncToolSpecification s = specByName("search_entities");
        Map<String, Object> props = properties(s);
        assertEquals("string", type(props, "query"), "query is a string");
        assertEquals("string", type(props, "type"), "type is a string");
        assertEquals("integer", type(props, "limit"), "limit is an integer");
        assertTrue(required(s).contains("query"), "query is required");
        assertFalse(required(s).contains("type"), "type is optional");
    }

    @Test
    void getEntitySchemaRequiresEntity() {
        SyncToolSpecification s = specByName("get_entity");
        assertEquals("string", type(properties(s), "entity"), "entity is a string");
        assertTrue(required(s).contains("entity"), "entity is required");
    }

    @Test
    void getAxiomsForEntitySchemaRequiresEntityAndDeclaresImportsAndLimit() {
        SyncToolSpecification s = specByName("get_axioms_for_entity");
        Map<String, Object> props = properties(s);
        assertEquals("string", type(props, "entity"), "entity is a string");
        assertEquals("boolean", type(props, "include_imports"), "include_imports is a boolean");
        assertEquals("integer", type(props, "limit"), "limit is an integer");
        assertTrue(required(s).contains("entity"), "entity is required");
    }

    // ================================================================== logicalAxiomCount

    @Test
    void logicalAxiomCountOfEmptySetIsZero() throws Exception {
        assertEquals(0, callLogicalAxiomCount(new LinkedHashSet<>()), "empty set has no logical axioms");
    }

    @Test
    void logicalAxiomCountCountsOnlyLogicalAxioms() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        axioms.add(df.getOWLDeclarationAxiom(a));   // non-logical
        axioms.add(df.getOWLDeclarationAxiom(b));   // non-logical
        axioms.add(df.getOWLSubClassOfAxiom(a, b)); // logical
        assertEquals(1, callLogicalAxiomCount(axioms), "only the SubClassOf axiom is logical");
    }

    @Test
    void logicalAxiomCountOfOnlyDeclarationsIsZero() throws Exception {
        OWLDataFactory df = mgr().getOWLDataFactory();
        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        axioms.add(df.getOWLDeclarationAxiom(cls(df, "A")));
        assertEquals(0, callLogicalAxiomCount(axioms), "declarations are not logical axioms");
    }

    // ================================================================== increment

    @Test
    void incrementAddsMissingKeyWithValueOne() throws Exception {
        Map<String, Integer> counts = new TreeMap<>();
        callIncrement(counts, "x");
        assertEquals(Integer.valueOf(1), counts.get("x"), "a fresh key starts at 1");
    }

    @Test
    void incrementBumpsExistingKey() throws Exception {
        Map<String, Integer> counts = new TreeMap<>();
        callIncrement(counts, "x");
        callIncrement(counts, "x");
        callIncrement(counts, "x");
        assertEquals(Integer.valueOf(3), counts.get("x"), "repeated increments accumulate");
    }

    @Test
    void incrementTreatsAnExplicitNullValueAsAbsent() throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("x", null);
        callIncrement(counts, "x");
        assertEquals(Integer.valueOf(1), counts.get("x"),
                "a null mapping is treated as absent (set to 1, not NPE)");
    }

    @Test
    void incrementWorksAcrossMapImplementations() throws Exception {
        for (Map<String, Integer> counts : List.of(new TreeMap<String, Integer>(),
                new LinkedHashMap<String, Integer>(), new HashMap<String, Integer>())) {
            callIncrement(counts, "k");
            callIncrement(counts, "k");
            assertEquals(Integer.valueOf(2), counts.get("k"),
                    counts.getClass().getSimpleName() + " accumulates correctly");
        }
    }

    // ================================================================== ontologyLabel

    @Test
    void ontologyLabelOfAnonymousOntology() throws Exception {
        OWLOntology anon = mgr().createOntology(); // no IRI => anonymous
        assertTrue(anon.getOntologyID().isAnonymous(), "created ontology is anonymous");
        assertEquals("(anonymous ontology)", callOntologyLabel(anon),
                "anonymous ontologies report a fixed label");
    }

    @Test
    void ontologyLabelOfNamedOntologyReturnsItsIri() throws Exception {
        String iri = "http://example.org/read-named";
        OWLOntology o = mgr().createOntology(IRI.create(iri));
        assertEquals(iri, callOntologyLabel(o), "a named ontology reports its IRI string");
    }

    // ================================================================== signature

    @Test
    void signatureNullTypeReturnsFullSignature() throws Exception {
        OWLOntology o = populated();
        OWLModelManager mm = FakeModelManager.over(o);
        assertEquals(o.getSignature(), callSignature(mm, null),
                "a null type returns the whole signature");
    }

    @Test
    void signatureAllTypeReturnsFullSignature() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getSignature(), callSignature(FakeModelManager.over(o), "all"),
                "type=all returns the whole signature");
    }

    @Test
    void signatureClassTypeReturnsClasses() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getClassesInSignature(), callSignature(FakeModelManager.over(o), "class"));
        assertEquals(o.getClassesInSignature(), callSignature(FakeModelManager.over(o), "classes"),
                "the plural alias resolves the same");
    }

    @Test
    void signatureObjectPropertyType() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getObjectPropertiesInSignature(),
                callSignature(FakeModelManager.over(o), "object_property"));
        assertEquals(o.getObjectPropertiesInSignature(),
                callSignature(FakeModelManager.over(o), "objectproperty"));
    }

    @Test
    void signatureDataPropertyType() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getDataPropertiesInSignature(),
                callSignature(FakeModelManager.over(o), "data_property"));
        assertEquals(o.getDataPropertiesInSignature(),
                callSignature(FakeModelManager.over(o), "dataproperty"));
    }

    @Test
    void signatureAnnotationPropertyType() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getAnnotationPropertiesInSignature(),
                callSignature(FakeModelManager.over(o), "annotation_property"));
        assertEquals(o.getAnnotationPropertiesInSignature(),
                callSignature(FakeModelManager.over(o), "annotationproperty"));
    }

    @Test
    void signatureIndividualType() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getIndividualsInSignature(),
                callSignature(FakeModelManager.over(o), "individual"));
        assertEquals(o.getIndividualsInSignature(),
                callSignature(FakeModelManager.over(o), "individuals"));
    }

    @Test
    void signatureDatatypeType() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getDatatypesInSignature(),
                callSignature(FakeModelManager.over(o), "datatype"));
        assertEquals(o.getDatatypesInSignature(),
                callSignature(FakeModelManager.over(o), "datatypes"));
    }

    @Test
    void signatureTypeIsCaseInsensitive() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getClassesInSignature(), callSignature(FakeModelManager.over(o), "CLASS"),
                "type is lower-cased before matching");
    }

    @Test
    void signatureUnknownTypeDefaultsToFullSignature() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getSignature(), callSignature(FakeModelManager.over(o), "bogus"),
                "an unrecognised type falls back to the whole signature");
    }

    @Test
    void signatureOfEmptyOntologyIsEmptyForEveryType() throws Exception {
        OWLOntology empty = mgr().createOntology(IRI.create("http://example.org/empty"));
        OWLModelManager mm = FakeModelManager.over(empty);
        for (String t : List.of("all", "class", "object_property", "data_property",
                "annotation_property", "individual", "datatype")) {
            assertTrue(callSignature(mm, t).isEmpty(), "empty ontology has no " + t + " entities");
        }
    }

    // ================================================================== search (whole-signature branch)

    @Test
    void searchNullQueryEnumeratesWholeSignature() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getSignature(), callSearch(FakeModelManager.over(o), null, null),
                "a null query lists the active ontology's whole signature");
    }

    @Test
    void searchEmptyQueryEnumeratesWholeSignature() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getSignature(), callSearch(FakeModelManager.over(o), "", "all"),
                "an empty query lists the whole signature");
    }

    @Test
    void searchWhitespaceQueryEnumeratesWholeSignature() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getSignature(), callSearch(FakeModelManager.over(o), "   ", null),
                "a blank (trimmed-empty) query lists the whole signature");
    }

    @Test
    void searchWildcardOnlyQueryEnumeratesWholeSignature() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getSignature(), callSearch(FakeModelManager.over(o), "*", null),
                "a single '*' lists the whole signature");
        assertEquals(o.getClassesInSignature(), callSearch(FakeModelManager.over(o), "***", "class"),
                "a wildcard-only query still honours the type filter");
    }

    @Test
    void searchWildcardOnlyQueryIsNarrowedByType() throws Exception {
        OWLOntology o = populated();
        assertEquals(o.getIndividualsInSignature(),
                callSearch(FakeModelManager.over(o), "*", "individual"),
                "a wildcard-only query narrows to the requested entity kind");
    }

    @Test
    void searchNonBlankFragmentTouchesTheFinderAndIsUnsupportedByTheFake() throws Exception {
        OWLOntology o = populated();
        // A real fragment ('neuron') wraps to '*neuron*' and hits getMatchingOWLEntities on the
        // finder, which the headless FakeModelManager does not implement — proving this path is
        // finder-backed (live-Protégé only), not the whole-signature branch.
        InvocationTargetException wrapped = assertThrows(InvocationTargetException.class,
                () -> callSearch(FakeModelManager.over(o), "neuron", "all"),
                "a non-blank fragment routes to the entity finder");
        assertTrue(wrapped.getCause() instanceof UnsupportedOperationException,
                "the fake finder rejects getMatchingOWL* calls");
    }

    // ================================================================== summarize

    @Test
    void summarizeActiveScopeReportsCountsForOneOntology() throws Exception {
        OWLOntology o = populated();
        Map<String, Object> body = structured(callSummarize(o, false, 80));
        assertEquals("active", body.get("scope"), "includeImports=false reports the active scope");
        assertEquals(1, body.get("ontologies"), "a lone ontology counts as one");
        assertEquals(o.getAxioms().size(), body.get("axioms"), "total axiom count is reported");
        assertEquals(o.getSignature().size(), body.get("signature_entities"),
                "signature size is reported");
    }

    @Test
    void summarizeReportsLogicalAxiomCountAndEntityTypes() throws Exception {
        OWLOntology o = populated();
        Map<String, Object> body = structured(callSummarize(o, false, 80));
        int expectedLogical = callLogicalAxiomCount(new LinkedHashSet<>(o.getAxioms()));
        assertEquals(expectedLogical, body.get("logical_axioms"),
                "logical_axioms matches logicalAxiomCount over the same axioms");
        @SuppressWarnings("unchecked")
        Map<String, Integer> entityTypes = (Map<String, Integer>) body.get("entity_types");
        assertNotNull(entityTypes, "entity_types map is present");
        assertTrue(entityTypes.containsKey("Class"), "classes are tallied by entity-type name");
    }

    @Test
    void summarizeCountsOntologyAnnotationsAndImportDeclarations() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/annotated"));
        m.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o,
                df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("an ontology comment"))));
        Map<String, Object> body = structured(callSummarize(o, false, 80));
        assertEquals(1, body.get("ontology_annotations"), "the ontology annotation is counted");
        assertEquals(0, body.get("import_declarations"), "no imports were declared");
    }

    @Test
    void summarizeLimitZeroShowsNoAxiomTypesAndReportsTruncation() throws Exception {
        OWLOntology o = populated();
        Map<String, Object> body = structured(callSummarize(o, false, 0));
        @SuppressWarnings("unchecked")
        Map<String, Integer> shown = (Map<String, Integer>) body.get("axiom_types");
        assertTrue(shown.isEmpty(), "limit=0 shows no axiom-type rows");
        assertTrue(((Number) body.get("axiom_types_truncated")).intValue() > 0,
                "the dropped rows are reported as truncated");
    }

    @Test
    void summarizeLimitOneShowsAtMostOneAxiomType() throws Exception {
        OWLOntology o = populated();
        Map<String, Object> body = structured(callSummarize(o, false, 1));
        @SuppressWarnings("unchecked")
        Map<String, Integer> shown = (Map<String, Integer>) body.get("axiom_types");
        assertTrue(shown.size() <= 1, "limit=1 shows at most one axiom-type row");
    }

    @Test
    void summarizeNegativeLimitIsClampedToZeroRows() throws Exception {
        OWLOntology o = populated();
        Map<String, Object> body = structured(callSummarize(o, false, -5));
        @SuppressWarnings("unchecked")
        Map<String, Integer> shown = (Map<String, Integer>) body.get("axiom_types");
        assertTrue(shown.isEmpty(), "a negative limit is clamped to zero (Math.max(0, limit))");
    }

    @Test
    void summarizeLargeLimitShowsAllAxiomTypesWithoutTruncationField() throws Exception {
        OWLOntology o = populated();
        Map<String, Object> body = structured(callSummarize(o, false, 10_000));
        assertFalse(body.containsKey("axiom_types_truncated"),
                "when nothing is dropped there is no truncation field");
    }

    @Test
    void summarizeImportsClosureScopeCoversImportedOntology() throws Exception {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology imported = m.createOntology(IRI.create("http://example.org/imported"));
        OWLClass importedClass = df.getOWLClass(IRI.create("http://example.org/imported#Thing"));
        m.addAxiom(imported, df.getOWLDeclarationAxiom(importedClass));

        OWLOntology active = m.createOntology(IRI.create("http://example.org/importer"));
        m.applyChange(new org.semanticweb.owlapi.model.AddImport(active,
                df.getOWLImportsDeclaration(IRI.create("http://example.org/imported"))));
        OWLClass localClass = df.getOWLClass(IRI.create("http://example.org/importer#Local"));
        m.addAxiom(active, df.getOWLDeclarationAxiom(localClass));

        Map<String, Object> closure = structured(callSummarize(active, true, 80));
        assertEquals("imports_closure", closure.get("scope"), "includeImports=true reports the closure");
        assertEquals(2, closure.get("ontologies"), "the closure spans importer + imported");
        assertEquals(1, closure.get("import_declarations"), "the single import declaration is counted");

        Map<String, Object> activeOnly = structured(callSummarize(active, false, 80));
        assertTrue(((Number) closure.get("signature_entities")).intValue()
                        >= ((Number) activeOnly.get("signature_entities")).intValue(),
                "the closure signature is at least as large as the active-only signature");
    }

    // ------------------------------------------------------------------ helpers

    /** An ontology carrying at least one entity of every kind and several axiom types. */
    private OWLOntology populated() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/read"));
        OWLClass animal = cls(df, "Animal");
        OWLClass dog = cls(df, "Dog");
        OWLObjectProperty hasOwner = df.getOWLObjectProperty(IRI.create(NS + "hasOwner"));
        OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "age"));
        OWLNamedIndividual rex = df.getOWLNamedIndividual(IRI.create(NS + "rex"));
        OWLDatatype dt = df.getOWLDatatype(IRI.create(NS + "PositiveInt"));

        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dog));
        m.addAxiom(o, df.getOWLDeclarationAxiom(hasOwner));
        m.addAxiom(o, df.getOWLDeclarationAxiom(age));
        m.addAxiom(o, df.getOWLDeclarationAxiom(rex));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dt));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(dog, rex));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), dog.getIRI(),
                df.getOWLLiteral("Dog", "en")));
        return o;
    }

    /** Build ReadTools' specs via the registry the catalog uses (ReadTools now registers, not returns). */
    private static List<SyncToolSpecification> readToolSpecs() {
        ToolRegistry registry = new ToolRegistry();
        ReadTools.register(registry, new ToolContext(null, null));
        return registry.build();
    }

    private SyncToolSpecification specByName(String name) {
        for (SyncToolSpecification s : readToolSpecs()) {
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
    private static List<String> required(SyncToolSpecification s) {
        Object r = s.tool().inputSchema().get("required");
        return r == null ? List.of() : (List<String>) r;
    }
}
