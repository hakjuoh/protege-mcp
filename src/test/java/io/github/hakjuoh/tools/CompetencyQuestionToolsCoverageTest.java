package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.server.HeadlessAccess;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Coverage for the competency-question tool wrapper {@link CompetencyQuestionTools} — the arg-parsing,
 * store selection, aggregation, and result-shaping glue that {@code CqStoreTest}/{@code CqRunnerTest}
 * (the engine + stores) do not exercise.
 *
 * <p>Structural half: {@link CompetencyQuestionTools#register} contributes the four tools with the
 * documented names/schemas. Behavioural half: the private {@code listCq}/{@code runCq}/{@code removeCq}
 * plus the pure helpers {@code build}/{@code nextId}/{@code describeTarget} are driven directly (a
 * headless {@link HeadlessAccess} lets the {@code ctx.access().compute(...)} handlers run inline). The
 * consent-gated write success paths of {@code add}/{@code remove} are intentionally left to the live
 * legs — {@code checkWriteAllowed} reads Protégé preferences — so only the pre-consent branches
 * (nothing-found, multiple-store disambiguation) are asserted here.
 */
class CompetencyQuestionToolsCoverageTest {

    private static final String NS = "http://example.org/cqtool#";

    private OWLOntologyManager m;
    private OWLOntology o;

    /** A model manager whose active ontology is saved to {@code dir/cq.owl} (so file conventions resolve). */
    private OWLModelManager saved(File dir) throws OWLOntologyCreationException {
        m = OWLManager.createOWLOntologyManager();
        o = m.createOntology(IRI.create("http://example.org/cqtool"));
        m.setOntologyDocumentIRI(o, IRI.create(new File(dir, "cq.owl").toURI()));
        return FakeModelManager.over(o);
    }

    private static CompetencyQuestion cq(String id, String query, Expectation exp) {
        CompetencyQuestion q = new CompetencyQuestion();
        q.id = id;
        q.query = query;
        q.expected = exp;
        q.includeInferred = false;   // keep runs off the (absent) reasoner path — deterministic
        return q;
    }

    // ------------------------------------------------------------------ reflection helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> listCq(OWLModelManager mm) throws Exception {
        Method m = CompetencyQuestionTools.class.getDeclaredMethod("listCq", OWLModelManager.class);
        m.setAccessible(true);
        return (Map<String, Object>) ((CallToolResult) m.invoke(null, mm)).structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runCq(ToolContext ctx, Map<String, Object> args) throws Exception {
        Method m = CompetencyQuestionTools.class.getDeclaredMethod("runCq", ToolContext.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) ((CallToolResult) m.invoke(null, ctx, args)).structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> removeCq(ToolContext ctx, Map<String, Object> args) throws Exception {
        Method m = CompetencyQuestionTools.class.getDeclaredMethod("removeCq", ToolContext.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) ((CallToolResult) m.invoke(null, ctx, args)).structuredContent();
    }

    private static CompetencyQuestion build(Map<String, Object> a, String query, CqStore store,
            CqContext c) throws Exception {
        Method m = CompetencyQuestionTools.class.getDeclaredMethod("build", Map.class, String.class,
                CqStore.class, CqContext.class);
        m.setAccessible(true);
        return (CompetencyQuestion) m.invoke(null, a, query, store, c);
    }

    private static String nextId(CqStore store, CqContext c) throws Exception {
        Method m = CompetencyQuestionTools.class.getDeclaredMethod("nextId", CqStore.class, CqContext.class);
        m.setAccessible(true);
        return (String) m.invoke(null, store, c);
    }

    private static String describeTarget(CqStore store, CqContext c) throws Exception {
        Method m = CompetencyQuestionTools.class.getDeclaredMethod("describeTarget", CqStore.class,
                CqContext.class);
        m.setAccessible(true);
        return (String) m.invoke(null, store, c);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            a.put((String) kv[i], kv[i + 1]);
        }
        return a;
    }

    // ------------------------------------------------------------------ structural (register)

    @Test
    void registersTheFourCompetencyQuestionTools() {
        ToolRegistry registry = new ToolRegistry();
        CompetencyQuestionTools.register(registry, new ToolContext(null, null));
        List<SyncToolSpecification> specs = registry.build();
        Set<String> names = specs.stream().map(s -> s.tool().name()).collect(Collectors.toSet());
        assertEquals(Set.of("add_competency_question", "list_competency_questions",
                "remove_competency_question", "run_competency_questions"), names);
        for (SyncToolSpecification s : specs) {
            assertFalse(s.tool().description() == null || s.tool().description().isBlank(),
                    s.tool().name() + " has a description");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void addSchemaRequiresQueryAndOffersConventionAndExpected() {
        ToolRegistry registry = new ToolRegistry();
        CompetencyQuestionTools.register(registry, new ToolContext(null, null));
        Map<String, Object> add = registry.build().stream()
                .filter(s -> s.tool().name().equals("add_competency_question"))
                .findFirst().orElseThrow().tool().inputSchema();
        assertEquals(List.of("query"), add.get("required"), "only the query is mandatory");
        Map<String, Object> props = (Map<String, Object>) add.get("properties");
        assertTrue(props.keySet().containsAll(
                List.of("id", "query", "text", "expected", "convention", "include_inferred", "tags")),
                "the documented add fields are present: " + props.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeSchemaRequiresIdAndRunSchemaOffersFilters() {
        ToolRegistry registry = new ToolRegistry();
        CompetencyQuestionTools.register(registry, new ToolContext(null, null));
        List<SyncToolSpecification> specs = registry.build();
        Map<String, Object> remove = specs.stream()
                .filter(s -> s.tool().name().equals("remove_competency_question"))
                .findFirst().orElseThrow().tool().inputSchema();
        assertEquals(List.of("id"), remove.get("required"));

        Map<String, Object> run = specs.stream()
                .filter(s -> s.tool().name().equals("run_competency_questions"))
                .findFirst().orElseThrow().tool().inputSchema();
        Map<String, Object> runProps = (Map<String, Object>) run.get("properties");
        assertTrue(runProps.keySet().containsAll(
                List.of("ids", "convention", "limit", "timeout_ms", "fail_on")),
                "run offers the documented filters/knobs: " + runProps.keySet());
    }

    // ------------------------------------------------------------------ listCq aggregation

    @Test
    void listEmptyWhenNoStoreDetected(@TempDir File dir) throws Exception {
        Map<String, Object> r = listCq(saved(dir));
        assertEquals(0, r.get("count"));
        assertTrue(((List<?>) r.get("conventions_found")).isEmpty());
        assertTrue(((List<?>) r.get("competency_questions")).isEmpty());
        assertTrue(((List<?>) r.get("skipped")).isEmpty());
    }

    @Test
    void listOneRobotDirStore(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        CqContext c = CqContext.of(mm);
        new RobotSparqlDirStore().upsert(c, cq("CQ-1", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        Map<String, Object> r = listCq(mm);
        assertEquals(1, r.get("count"));
        assertEquals(List.of(Cq.CONV_ROBOT), r.get("conventions_found"));
    }

    @Test
    void listSpansMultipleConventions(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        CqContext c = CqContext.of(mm);
        new RobotSparqlDirStore().upsert(c, cq("CQ-1", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        new SidecarManifestStore().upsert(c, cq("CQ-2", "SELECT ?s WHERE { ?s a ?t }", Expectation.empty()));
        Map<String, Object> r = listCq(mm);
        assertEquals(2, r.get("count"));
        assertTrue(((List<?>) r.get("conventions_found")).containsAll(
                List.of(Cq.CONV_ROBOT, Cq.CONV_MANIFEST)));
    }

    @Test
    void listIsolatesAMalformedEntry(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        CqContext c = CqContext.of(mm);
        new RobotSparqlDirStore().upsert(c, cq("Good", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        Files.write(new File(new File(dir, "cqs"), "bad.rq").toPath(),
                "# id: bad\n\n".getBytes(StandardCharsets.UTF_8));   // header, no query body
        Map<String, Object> r = listCq(mm);
        assertEquals(1, r.get("count"), "the well-formed CQ still lists");
        assertEquals(1, ((List<?>) r.get("skipped")).size(), "the malformed .rq is skipped, not fatal");
    }

    // ------------------------------------------------------------------ runCq (headless e2e)

    @Test
    void runWithNoQuestionsIsAGuidedPass(@TempDir File dir) throws Exception {
        ToolContext ctx = new ToolContext(HeadlessAccess.over(saved(dir)), null);
        Map<String, Object> r = runCq(ctx, args());
        assertEquals(0, r.get("total"));
        assertEquals("pass", r.get("gate"));
        assertTrue(String.valueOf(r.get("note")).contains("No competency questions matched"));
    }

    @Test
    void runReportsAPassingRequirement(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        addSubclass();   // Dog ⊑ Animal
        CqContext c = CqContext.of(mm);
        new RobotSparqlDirStore().upsert(c, cq("subclass", "SELECT ?s WHERE { ?s "
                + "<http://www.w3.org/2000/01/rdf-schema#subClassOf> <" + NS + "Animal> }",
                Expectation.nonEmpty()));
        Map<String, Object> r = runCq(new ToolContext(HeadlessAccess.over(mm), null), args());
        assertEquals(1, r.get("total"));
        assertEquals(1, r.get("passed"));
        assertEquals("pass", r.get("gate"));
    }

    @Test
    void runGatesFailOnAnyWhenARequirementBreaks(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        addSubclass();   // Dog ⊑ Animal exists, so an EMPTY expectation fails
        CqContext c = CqContext.of(mm);
        new RobotSparqlDirStore().upsert(c, cq("must-be-empty", "SELECT ?s WHERE { ?s "
                + "<http://www.w3.org/2000/01/rdf-schema#subClassOf> <" + NS + "Animal> }",
                Expectation.empty()));
        Map<String, Object> r = runCq(new ToolContext(HeadlessAccess.over(mm), null),
                args("fail_on", "any"));
        assertEquals(1, r.get("failed"));
        assertEquals("fail", r.get("gate"));
    }

    @Test
    void runFiltersByIds(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        CqContext c = CqContext.of(mm);
        new RobotSparqlDirStore().upsert(c, cq("keep", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        new RobotSparqlDirStore().upsert(c, cq("drop", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        Map<String, Object> r = runCq(new ToolContext(HeadlessAccess.over(mm), null),
                args("ids", List.of("keep")));
        assertEquals(1, r.get("total"), "only the id-matched CQ runs");
    }

    // ------------------------------------------------------------------ removeCq (pre-consent branches)

    @Test
    void removeMissingIdIsAGuidedError(@TempDir File dir) throws Exception {
        ToolContext ctx = new ToolContext(HeadlessAccess.over(saved(dir)), null);
        Map<String, Object> r = removeCq(ctx, args("id", "nope"));
        assertTrue(r.containsKey("error"));
        assertTrue(String.valueOf(r.get("error")).contains("No competency question"));
    }

    @Test
    void removeAmbiguousIdAcrossStoresRequiresConvention(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        CqContext c = CqContext.of(mm);
        new RobotSparqlDirStore().upsert(c, cq("dupe", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        new SidecarManifestStore().upsert(c, cq("dupe", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        Map<String, Object> r = removeCq(new ToolContext(HeadlessAccess.over(mm), null), args("id", "dupe"));
        assertTrue(r.containsKey("error"));
        assertTrue(String.valueOf(r.get("error")).contains("several conventions"),
                "the same id in two stores forces an explicit convention");
    }

    // ------------------------------------------------------------------ build / nextId / describeTarget

    @Test
    void buildFillsDefaultsAndMintsAnId(@TempDir File dir) throws Exception {
        CqContext c = CqContext.of(saved(dir));
        RobotSparqlDirStore store = new RobotSparqlDirStore();
        CompetencyQuestion cq = build(args("text", "Which animals?"), "SELECT ?s WHERE { ?s a ?t }",
                store, c);
        assertEquals("SELECT ?s WHERE { ?s a ?t }", cq.query);
        assertEquals("Which animals?", cq.text);
        assertEquals("sparql", cq.queryLang, "query_lang defaults to sparql");
        assertTrue(cq.includeInferred, "include_inferred defaults to true");
        assertEquals(Expectation.Kind.NON_EMPTY, cq.expected.kind, "expected defaults to nonEmpty");
        assertEquals(Cq.CONV_ROBOT, cq.convention, "the resolving store tags the convention");
        assertEquals("CQ-1", cq.id, "an id is minted when omitted");
    }

    @Test
    void nextIdWalksTheHighestNumericSuffix(@TempDir File dir) throws Exception {
        OWLModelManager mm = saved(dir);
        CqContext c = CqContext.of(mm);
        RobotSparqlDirStore store = new RobotSparqlDirStore();
        assertEquals("CQ-1", nextId(store, c), "empty store starts at CQ-1");
        store.upsert(c, cq("CQ-3", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));
        store.upsert(c, cq("CQ-note", "SELECT ?s WHERE { ?s a ?t }", Expectation.nonEmpty()));  // non-numeric
        assertEquals("CQ-4", nextId(store, c), "the highest numeric suffix wins; non-numeric ignored");
    }

    @Test
    void describeTargetNamesEachConvention(@TempDir File dir) throws Exception {
        CqContext c = CqContext.of(saved(dir));
        assertTrue(describeTarget(new RobotSparqlDirStore(), c).contains("cqs"),
                "robot-sparql-dir points at the cqs/ folder");
        assertTrue(describeTarget(new SidecarManifestStore(), c).contains("cqs.json"),
                "sidecar-manifest points at the <basename>-cqs.json file");
        assertEquals("the active ontology (annotations)",
                describeTarget(new OntologyAnnotationsStore(), c));
    }

    // ------------------------------------------------------------------ fixtures

    /** Add Dog ⊑ Animal to the already-{@code saved} ontology {@code o}. */
    private void addSubclass() {
        OWLDataFactory df = m.getOWLDataFactory();
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(animal));
        m.addAxiom(o, df.getOWLDeclarationAxiom(dog));
        m.addAxiom(o, df.getOWLSubClassOfAxiom(dog, animal));
    }
}
