package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.server.HeadlessAccess;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Coverage for the {@code verify_ontology} tool wrapper {@link VerifyOntologyTools} — the arg-parsing,
 * schema, and result-shaping glue that {@code InvariantsTest} (the engine) does not exercise.
 *
 * <p>The structural half drives {@link VerifyOntologyTools#register} through a {@link ToolRegistry} and
 * asserts the tool name/description/input-schema (no handler is invoked at registration). The behavioural
 * half drives the private {@code verify(ctx, args)} end-to-end over a headless {@link HeadlessAccess}
 * ({@code compute} runs inline against a {@link FakeModelManager}) — including the two just-fixed
 * contracts at the tool boundary: an {@code include_inferred} invariant with no reasoner and a
 * CONSTRUCT/DESCRIBE invariant both fail closed, never a silent pass.
 */
class VerifyOntologyToolsTest {

    private static final String NS = "http://example.org/verify#";

    // ------------------------------------------------------------------ fixtures

    /** Foo (no label) + Bar (labelled), the classic F4 fixture. No document IRI needed (no store I/O). */
    private static OWLModelManager ontology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/verify"));
        OWLClass foo = df.getOWLClass(IRI.create(NS + "Foo"));
        OWLClass bar = df.getOWLClass(IRI.create(NS + "Bar"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(foo));
        m.addAxiom(o, df.getOWLDeclarationAxiom(bar));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), bar.getIRI(),
                df.getOWLLiteral("Bar")));
        return FakeModelManager.over(o);
    }

    private static ToolContext headlessCtx() throws OWLOntologyCreationException {
        return new ToolContext(HeadlessAccess.over(ontology()), null);
    }

    /** Invoke the private static verify(ToolContext, Map) directly. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> verify(ToolContext ctx, Map<String, Object> args) throws Exception {
        Method m = VerifyOntologyTools.class.getDeclaredMethod("verify", ToolContext.class, Map.class);
        m.setAccessible(true);
        CallToolResult r = (CallToolResult) m.invoke(null, ctx, args);
        return (Map<String, Object>) r.structuredContent();
    }

    private static boolean isError(Map<String, Object> structured) {
        return structured.containsKey("error");
    }

    private static Map<String, Object> query(String sparql) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("sparql", sparql);
        return q;
    }

    private static Map<String, Object> args(Object queries) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (queries != null) {
            a.put("queries", queries);
        }
        return a;
    }

    // ------------------------------------------------------------------ structural (register / schema)

    private static SyncToolSpecification onlySpec() {
        ToolRegistry registry = new ToolRegistry();
        VerifyOntologyTools.register(registry, new ToolContext(null, null));
        List<SyncToolSpecification> specs = registry.build();
        assertEquals(1, specs.size(), "verify_ontology registers exactly one tool");
        return specs.get(0);
    }

    @Test
    void registersVerifyOntologyToolWithSelectAskDescription() {
        SyncToolSpecification spec = onlySpec();
        assertEquals("verify_ontology", spec.tool().name());
        String desc = spec.tool().description();
        assertNotNull(desc);
        assertTrue(desc.contains("SELECT") && desc.contains("ASK"),
                "the description states the SELECT/ASK invariant form");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaDeclaresQueriesArrayAndGateKnobs() {
        Map<String, Object> schema = onlySpec().tool().inputSchema();
        assertEquals("object", schema.get("type"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.keySet().containsAll(
                List.of("queries", "profile", "fail_on", "limit", "timeout_ms")),
                "all documented knobs are present: " + props.keySet());

        Map<String, Object> queries = (Map<String, Object>) props.get("queries");
        assertEquals("array", queries.get("type"));
        Map<String, Object> item = (Map<String, Object>) queries.get("items");
        assertEquals("object", item.get("type"));
        assertEquals(List.of("sparql"), item.get("required"), "each invariant requires a sparql");
        assertEquals(Boolean.FALSE, item.get("additionalProperties"));
        Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
        assertTrue(itemProps.keySet().containsAll(
                List.of("sparql", "id", "message", "severity", "include_inferred")),
                "the per-invariant fields are present: " + itemProps.keySet());
    }

    // ------------------------------------------------------------------ behaviour (verify e2e)

    @Test
    void emptyQueriesIsAGuidedError() throws Exception {
        // Neither the missing-key nor the empty-array form dereferences ctx before erroring.
        Map<String, Object> missing = verify(new ToolContext(null, null), args(null));
        assertTrue(isError(missing) && String.valueOf(missing.get("error")).contains("queries"));
        Map<String, Object> empty = verify(new ToolContext(null, null), args(new ArrayList<>()));
        assertTrue(isError(empty) && String.valueOf(empty.get("error")).contains("queries"));
    }

    @Test
    void selectInvariantFlagsViolationAndFailsGate() throws Exception {
        // Foo has no rdfs:label → this forbidden-pattern SELECT returns it → a violation at error severity.
        Map<String, Object> r = verify(headlessCtx(), args(List.of(query(
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }"))));
        assertFalse(isError(r));
        assertEquals(1, r.get("violations"));
        assertEquals("fail", r.get("gate"), "an error violation trips the default fail_on=error");
    }

    @Test
    void cleanOntologyPassesGate() throws Exception {
        Map<String, Object> r = verify(headlessCtx(), args(List.of(query(
                "SELECT ?i WHERE { ?i a owl:NamedIndividual }"))));   // none exist → no violation
        assertEquals(0, r.get("violations"));
        assertEquals("pass", r.get("gate"));
    }

    @Test
    void includeInferredInvariantWithNoReasonerFailsClosed() throws Exception {
        Map<String, Object> q = query("SELECT ?c WHERE { ?c a owl:Class }");
        q.put("include_inferred", true);   // needs a classified reasoner the fake has none of
        Map<String, Object> r = verify(headlessCtx(), args(List.of(q)));
        assertEquals(0, r.get("violations"), "an un-runnable inferred check is not a violation");
        assertEquals(1, r.get("errors"), "it is surfaced as a check that could not run");
        assertEquals("fail", r.get("gate"),
                "verify_ontology never degrades an include_inferred check to asserted-only and passes");
    }

    @Test
    void constructInvariantIsRejectedFailClosed() throws Exception {
        Map<String, Object> r = verify(headlessCtx(), args(List.of(query(
                "CONSTRUCT { ?c a owl:Class } WHERE { ?c a owl:Class }"))));
        assertEquals(0, r.get("violations"), "a CONSTRUCT is not treated as violation evidence");
        assertEquals(1, r.get("errors"));
        assertEquals("fail", r.get("gate"), "a graph-producing invariant is rejected fail-closed");
    }

    @Test
    void reservedProfileArgAddsANoteAndStillRuns() throws Exception {
        Map<String, Object> a = args(List.of(query("ASK { ?s ?p ?o }")));
        a.put("profile", "my-invariant-set");
        Map<String, Object> r = verify(headlessCtx(), a);
        assertTrue(r.containsKey("note"), "a persisted 'profile' request is answered with a reserved note");
        assertTrue(String.valueOf(r.get("note")).contains("reserved"));
        assertTrue(r.containsKey("gate"), "the inline queries still run alongside the note");
    }

    @Test
    void failOnNoneNeverGatesEvenWithAViolation() throws Exception {
        Map<String, Object> a = args(List.of(query(
                "SELECT ?c WHERE { ?c a owl:Class . FILTER NOT EXISTS { ?c rdfs:label ?l } }")));
        a.put("fail_on", "none");
        Map<String, Object> r = verify(headlessCtx(), a);
        assertEquals(1, r.get("violations"), "the violation is still reported");
        assertEquals("pass", r.get("gate"), "fail_on=none never gates");
    }
}
