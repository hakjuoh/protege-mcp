package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Method-level tests for the private {@code merge_ontology_document} core
 * {@code OntologyDocumentTools.apply(mm, loaded, replaceActive, copyOntologyId, preview)}, focused
 * on the 0.4.3 {@code preview} mode: a preview computes the same change list as a real merge but
 * must NOT call {@code applyChanges}, reporting {@code would_copy}/{@code would_remove}/
 * {@code already_present_axioms}/{@code total_changes} instead of the post-apply
 * {@code copied}/{@code removed}/{@code active} counts.
 *
 * <p>{@code LoadedOntology} and {@code apply}/{@code load} are private, so both are reached
 * reflectively (same idiom as {@link WriteToolsTest}). The manager is the shared
 * {@link FakeModelManager}, which routes {@code applyChange(s)} to the backing OWL API manager —
 * so the preview=false control actually mutates the in-memory active ontology, no extra
 * mutating proxy needed.
 */
class OntologyDocumentToolsMergePreviewTest {

    private static final String NS = "http://example.org/mp#";
    private static final String ACTIVE_IRI = "http://example.org/mp/active";
    private static final String SOURCE_IRI = "http://example.org/mp/source";
    private static final String DOC_IRI = "http://example.org/mp/doc";

    // ------------------------------------------------------------------ fixtures / reflection

    private OWLOntology activeOntology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        return m.createOntology(IRI.create(ACTIVE_IRI));
    }

    private static OWLClass cls(OWLDataFactory df, String name) {
        return df.getOWLClass(IRI.create(NS + name));
    }

    /** The private static inner value class {@code OntologyDocumentTools.LoadedOntology}. */
    private static Class<?> loadedOntologyClass() {
        for (Class<?> c : OntologyDocumentTools.class.getDeclaredClasses()) {
            if ("LoadedOntology".equals(c.getSimpleName())) {
                return c;
            }
        }
        throw new IllegalStateException("OntologyDocumentTools.LoadedOntology inner class not found");
    }

    /** Reflectively build a LoadedOntology from hand-assembled document parts (strategy A). */
    private static Object newLoaded(String source, OWLOntologyID id, Set<OWLAxiom> axioms,
            Set<OWLImportsDeclaration> importsDeclarations, Set<OWLAnnotation> annotations,
            List<String> unresolvedImports) throws Exception {
        Constructor<?> ctor = loadedOntologyClass().getDeclaredConstructor(String.class,
                OWLOntologyID.class, Set.class, Set.class, Set.class, List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(source, id, axioms, importsDeclarations, annotations,
                unresolvedImports);
    }

    private static Method applyMethod() throws NoSuchMethodException {
        Method m = OntologyDocumentTools.class.getDeclaredMethod("apply", OWLModelManager.class,
                loadedOntologyClass(), boolean.class, boolean.class, boolean.class);
        m.setAccessible(true);
        return m;
    }

    private static Method loadMethod() throws NoSuchMethodException {
        Method m = OntologyDocumentTools.class.getDeclaredMethod("load", String.class, int.class);
        m.setAccessible(true);
        return m;
    }

    /** Unwrap the InvocationTargetException so assertions see the real cause. */
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String key) {
        assertTrue(m.containsKey(key), "expected key '" + key + "' in " + m.keySet());
        return (Map<String, Object>) m.get(key);
    }

    private static int asInt(Object v) {
        return ((Number) v).intValue();
    }

    // ================================================================== plain merge preview

    @Test
    void plainMergePreviewReportsCountsWithoutChangingActive() throws Throwable {
        OWLOntology active = activeOntology();
        OWLOntologyManager om = active.getOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLAxiom shared = df.getOWLDeclarationAxiom(a);
        om.addAxiom(active, shared);
        assertEquals(1, active.getAxiomCount(), "fixture: active starts with exactly one axiom");

        // Source document: 3 axioms, one of which is already asserted in the active ontology.
        Set<OWLAxiom> sourceAxioms = new LinkedHashSet<>(Arrays.asList(
                shared, df.getOWLDeclarationAxiom(b), df.getOWLSubClassOfAxiom(b, a)));
        Object loaded = newLoaded("/tmp/source.ofn", new OWLOntologyID(IRI.create(SOURCE_IRI)),
                sourceAxioms, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyList());

        CallToolResult r = (CallToolResult) invoke(applyMethod(),
                FakeModelManager.over(active), loaded, false, false, true);
        Map<String, Object> m = structured(r);

        assertEquals(Boolean.FALSE, r.isError(), "a preview is a success result");
        assertEquals(Boolean.TRUE, m.get("preview"), "preview flag is present and true");
        assertEquals("/tmp/source.ofn", m.get("merged_document"), "document source echoed");
        assertEquals(SOURCE_IRI, m.get("source_ontology"), "source ontology id echoed");
        assertEquals(Boolean.FALSE, m.get("replace_active"), "plain merge, not a replace");
        assertFalse(m.containsKey("would_remove"), "no would_remove without replace_active");

        Map<String, Object> wouldCopy = sub(m, "would_copy");
        assertEquals(3, asInt(wouldCopy.get("axioms")), "all 3 document axioms would be copied");
        assertEquals(0, asInt(wouldCopy.get("imports")), "no import declarations in the document");
        assertEquals(0, asInt(wouldCopy.get("ontology_annotations")),
                "no ontology annotations in the document");
        assertEquals(Boolean.FALSE, wouldCopy.get("ontology_id"),
                "copy_ontology_id was not requested");

        assertEquals(1, asInt(m.get("already_present_axioms")),
                "one document axiom is already asserted in the active ontology");
        assertEquals(3, asInt(m.get("total_changes")), "3 axiom adds and nothing else queued");
        assertTrue(String.valueOf(m.get("note")).contains("Nothing was changed"),
                "the preview note must say nothing was changed: " + m.get("note"));

        assertFalse(m.containsKey("copied"), "preview has no post-apply 'copied' key");
        assertFalse(m.containsKey("removed"), "preview has no post-apply 'removed' key");
        assertFalse(m.containsKey("active"), "preview skips the post-apply 'active' counts");
        assertEquals(1, active.getAxiomCount(), "the active ontology was NOT mutated");
        assertTrue(active.containsAxiom(shared), "the pre-existing axiom is untouched");
    }

    // ================================================================== replace_active preview

    @Test
    void replaceActivePreviewReportsWouldRemoveWithoutChangingActive() throws Throwable {
        OWLOntology active = activeOntology();
        OWLOntologyManager om = active.getOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();
        om.addAxiom(active, df.getOWLDeclarationAxiom(cls(df, "A")));
        om.addAxiom(active, df.getOWLDeclarationAxiom(cls(df, "C")));
        om.applyChange(new AddImport(active,
                df.getOWLImportsDeclaration(IRI.create("http://example.org/mp/imported"))));
        om.applyChange(new AddOntologyAnnotation(active,
                df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("active note"))));
        assertEquals(2, active.getAxiomCount(), "fixture: two active axioms");

        Set<OWLAxiom> sourceAxioms = new LinkedHashSet<>(Arrays.asList(
                df.getOWLDeclarationAxiom(cls(df, "X")),
                df.getOWLDeclarationAxiom(cls(df, "Y")),
                df.getOWLSubClassOfAxiom(cls(df, "X"), cls(df, "Y"))));
        Object loaded = newLoaded("/tmp/replacement.owl", new OWLOntologyID(IRI.create(SOURCE_IRI)),
                sourceAxioms, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyList());

        // copy_ontology_id defaults to replace_active in the tool handler, so mirror that here.
        CallToolResult r = (CallToolResult) invoke(applyMethod(),
                FakeModelManager.over(active), loaded, true, true, true);
        Map<String, Object> m = structured(r);

        assertEquals(Boolean.TRUE, m.get("preview"), "preview flag is present and true");
        assertEquals(Boolean.TRUE, m.get("replace_active"), "replace mode echoed");

        Map<String, Object> wouldRemove = sub(m, "would_remove");
        assertEquals(2, asInt(wouldRemove.get("axioms")),
                "would remove exactly the active ontology's axiom count");
        assertEquals(1, asInt(wouldRemove.get("imports")), "the one active import would go");
        assertEquals(1, asInt(wouldRemove.get("ontology_annotations")),
                "the one active ontology annotation would go");

        Map<String, Object> wouldCopy = sub(m, "would_copy");
        assertEquals(3, asInt(wouldCopy.get("axioms")), "all document axioms would be copied");
        assertEquals(Boolean.TRUE, wouldCopy.get("ontology_id"),
                "no id collision, so the source ontology id would be copied");
        assertFalse(m.containsKey("skipped_ontology_id"), "no collision, no skip explanation");
        assertFalse(m.containsKey("already_present_axioms"),
                "already-present counting is irrelevant under replace_active");

        // 4 removes (2 axioms + 1 import + 1 annotation) + 1 SetOntologyID + 3 axiom adds.
        assertEquals(8, asInt(m.get("total_changes")), "removes + id change + adds all queued");
        assertTrue(String.valueOf(m.get("note")).contains("Nothing was changed"),
                "the preview note must say nothing was changed: " + m.get("note"));
        assertFalse(m.containsKey("active"), "preview skips the post-apply 'active' counts");

        assertEquals(2, active.getAxiomCount(), "active axioms were NOT removed");
        assertEquals(1, active.getImportsDeclarations().size(), "active import was NOT removed");
        assertEquals(1, active.getAnnotations().size(), "active annotation was NOT removed");
        assertEquals(ACTIVE_IRI, active.getOntologyID().getOntologyIRI().get().toString(),
                "the active ontology id was NOT changed by the preview");
    }

    // ================================================================== preview=false control

    @Test
    void nonPreviewApplyMergesAxiomsAndReportsCopiedAndActive() throws Throwable {
        OWLOntology active = activeOntology();
        OWLOntologyManager om = active.getOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();
        OWLClass a = cls(df, "A");
        OWLClass b = cls(df, "B");
        OWLAxiom shared = df.getOWLDeclarationAxiom(a);
        OWLAxiom subClassOf = df.getOWLSubClassOfAxiom(b, a);
        om.addAxiom(active, shared);

        Set<OWLAxiom> sourceAxioms = new LinkedHashSet<>(Arrays.asList(
                shared, df.getOWLDeclarationAxiom(b), subClassOf));
        Object loaded = newLoaded("/tmp/source.ofn", new OWLOntologyID(IRI.create(SOURCE_IRI)),
                sourceAxioms, Collections.emptySet(), Collections.emptySet(),
                Collections.emptyList());

        CallToolResult r = (CallToolResult) invoke(applyMethod(),
                FakeModelManager.over(active), loaded, false, false, false);
        Map<String, Object> m = structured(r);

        assertFalse(m.containsKey("preview"), "no preview flag on a real apply");
        assertFalse(m.containsKey("would_copy"), "real apply reports 'copied', not 'would_copy'");
        assertFalse(m.containsKey("would_remove"), "no would_remove on a real apply");
        assertFalse(m.containsKey("already_present_axioms"),
                "already-present counting is preview-only");
        assertFalse(m.containsKey("total_changes"), "total_changes is preview-only");
        assertFalse(m.containsKey("note"), "the nothing-was-changed note is preview-only");

        Map<String, Object> copied = sub(m, "copied");
        assertEquals(3, asInt(copied.get("axioms")), "all 3 document axioms were applied");
        assertEquals(Boolean.FALSE, copied.get("ontology_id"), "id copy was not requested");

        Map<String, Object> activeCounts = sub(m, "active");
        assertEquals(3, asInt(activeCounts.get("axioms")),
                "1 existing + 2 new (the shared add is a no-op)");
        assertEquals(1, asInt(activeCounts.get("logical_axioms")),
                "only the SubClassOf axiom is logical");
        assertEquals(0, asInt(activeCounts.get("direct_imports")), "no imports were merged");
        assertEquals(0, asInt(activeCounts.get("ontology_annotations")),
                "no ontology annotations were merged");

        assertEquals(3, active.getAxiomCount(), "the merge really mutated the active ontology");
        assertTrue(active.containsAxiom(subClassOf), "the new SubClassOf axiom is now asserted");
    }

    // ================================================================== load() + preview (strategy B)

    @Test
    void loadedDocumentPreviewReportsDocumentCountsAndLeavesActiveEmpty(@TempDir Path dir)
            throws Throwable {
        Path doc = dir.resolve("doc.rdf");
        Files.write(doc, ("<?xml version=\"1.0\"?>\n"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n"
                + "         xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                + "  <owl:Ontology rdf:about=\"" + DOC_IRI + "\"/>\n"
                + "  <owl:Class rdf:about=\"" + DOC_IRI + "#Y\"/>\n"
                + "  <owl:Class rdf:about=\"" + DOC_IRI + "#X\">\n"
                + "    <rdfs:subClassOf rdf:resource=\"" + DOC_IRI + "#Y\"/>\n"
                + "  </owl:Class>\n"
                + "</rdf:RDF>\n").getBytes());

        // The private load(source, timeoutMs) parses the local file into a LoadedOntology snapshot.
        Object loaded = invoke(loadMethod(), doc.toString(), 15_000);
        OWLOntology active = activeOntology();

        CallToolResult r = (CallToolResult) invoke(applyMethod(),
                FakeModelManager.over(active), loaded, false, false, true);
        Map<String, Object> m = structured(r);

        assertEquals(Boolean.TRUE, m.get("preview"), "preview flag is present and true");
        assertEquals(doc.toString(), m.get("merged_document"),
                "the normalized local path is echoed as the merged document");
        assertEquals(DOC_IRI, m.get("source_ontology"), "the parsed document's ontology id");

        Map<String, Object> wouldCopy = sub(m, "would_copy");
        assertEquals(3, asInt(wouldCopy.get("axioms")),
                "2 declarations + 1 SubClassOf parsed from the document");
        assertEquals(0, asInt(m.get("already_present_axioms")),
                "the active ontology is empty, nothing overlaps");
        assertEquals(3, asInt(m.get("total_changes")), "3 axiom adds queued, nothing else");
        assertEquals(Collections.emptyList(), m.get("unresolved_imports"),
                "the document has no imports, so none are unresolved");
        assertTrue(String.valueOf(m.get("note")).contains("Nothing was changed"),
                "the preview note must say nothing was changed: " + m.get("note"));

        assertEquals(0, active.getAxiomCount(), "the active ontology stays empty after a preview");
    }
}
