package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Supplementary coverage for {@link PreviewTools}, expanding on {@link PreviewToolsTest} (which only
 * covers two {@link PreviewTools#newEntities} cases). Focuses on the remaining pure branches of
 * {@code newEntities} (built-in filtering, empty signature, declared-only vs signature-only
 * knownness, multi-ontology closures, fully-new axioms) and the full structure produced by
 * {@link PreviewTools#operationsSchema}.
 */
class PreviewToolsCoverageTest {

    private static final String NS = "http://example.org/cov#";

    private static OWLOntologyManager mgr() {
        return OWLManager.createOWLOntologyManager();
    }

    // ------------------------------------------------------------------ newEntities

    @Test
    void builtInEntitiesInSignatureAreFilteredOut() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        // A ⊑ owl:Thing — A is new, owl:Thing is built-in and must be filtered.
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        Set<OWLEntity> introduced =
                PreviewTools.newEntities(o.getImportsClosure(), df.getOWLSubClassOfAxiom(a, df.getOWLThing()));
        assertTrue(introduced.contains(a), "A is a fresh non-built-in entity");
        assertFalse(introduced.contains(df.getOWLThing()), "owl:Thing is built-in and filtered");
    }

    @Test
    void axiomWhoseSignatureIsAllBuiltInReturnsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        // owl:Thing ⊑ owl:Thing — every signature entity is built-in.
        Set<OWLEntity> introduced = PreviewTools.newEntities(o.getImportsClosure(),
                df.getOWLSubClassOfAxiom(df.getOWLThing(), df.getOWLThing()));
        assertTrue(introduced.isEmpty(), "all-built-in signature yields no new entities");
    }

    @Test
    void entityDeclaredButNotOtherwiseUsedIsKnown() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass fresh = df.getOWLClass(IRI.create(NS + "Fresh"));
        // A is known only via a declaration axiom (isDeclared branch).
        m.addAxiom(o, df.getOWLDeclarationAxiom(a));
        Set<OWLEntity> introduced =
                PreviewTools.newEntities(o.getImportsClosure(), df.getOWLSubClassOfAxiom(a, fresh));
        assertFalse(introduced.contains(a), "A is declared, therefore known");
        assertTrue(introduced.contains(fresh), "Fresh is neither declared nor in any signature");
    }

    @Test
    void entityPresentOnlyInAnAxiomSignatureIsKnown() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLClass fresh = df.getOWLClass(IRI.create(NS + "Fresh"));
        // A and B are known only because they appear in a subclass axiom's signature (no declaration).
        m.addAxiom(o, df.getOWLSubClassOfAxiom(a, b));
        assertTrue(o.containsEntityInSignature(a), "A is in the ontology signature");
        Set<OWLEntity> introduced =
                PreviewTools.newEntities(o.getImportsClosure(), df.getOWLSubClassOfAxiom(a, fresh));
        assertFalse(introduced.contains(a), "A appears in a signature, therefore known");
        assertTrue(introduced.contains(fresh), "Fresh is genuinely new");
    }

    @Test
    void emptyClosureMarksEveryNonBuiltInEntityNew() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        // The ontology is empty: nothing declared, nothing in any signature.
        Set<OWLEntity> introduced =
                PreviewTools.newEntities(o.getImportsClosure(), df.getOWLSubClassOfAxiom(a, b));
        assertTrue(introduced.contains(a), "A is new against an empty closure");
        assertTrue(introduced.contains(b), "B is new against an empty closure");
        assertEquals(2, introduced.size(), "exactly the two named classes are new");
    }

    @Test
    void axiomWithEmptySignatureReturnsEmpty() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        // Thing ⊑ Thing has no user entities in signature (all built-in) — a proxy for "empty" set.
        Set<OWLEntity> introduced = PreviewTools.newEntities(o.getImportsClosure(),
                df.getOWLSubClassOfAxiom(df.getOWLThing(), df.getOWLNothing()));
        assertTrue(introduced.isEmpty(), "no user entities to introduce");
    }

    @Test
    void multiOntologyClosureTreatsEntityKnownInEitherOntologyAsKnown()
            throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology base = m.createOntology(IRI.create("http://example.org/cov/base"));
        OWLOntology dep = m.createOntology(IRI.create("http://example.org/cov/dep"));
        OWLClass shared = df.getOWLClass(IRI.create(NS + "Shared"));
        OWLClass fresh = df.getOWLClass(IRI.create(NS + "Fresh"));
        // Shared is declared in the dependency ontology, not the base.
        m.addAxiom(dep, df.getOWLDeclarationAxiom(shared));
        // A hand-built two-ontology closure (as import-closures reach across ontologies).
        Set<OWLOntology> closure = new java.util.LinkedHashSet<>(Arrays.asList(base, dep));
        Set<OWLEntity> introduced =
                PreviewTools.newEntities(closure, df.getOWLSubClassOfAxiom(shared, fresh));
        assertFalse(introduced.contains(shared), "Shared is known via the dep ontology in the closure");
        assertTrue(introduced.contains(fresh), "Fresh is unknown across the whole closure");
    }

    @Test
    void mixedAxiomSeparatesKnownFromNewAcrossEntityTypes() throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        OWLObjectProperty knownProp = df.getOWLObjectProperty(IRI.create(NS + "knownProp"));
        OWLNamedIndividual knownInd = df.getOWLNamedIndividual(IRI.create(NS + "knownInd"));
        OWLNamedIndividual freshInd = df.getOWLNamedIndividual(IRI.create(NS + "freshInd"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(knownProp));
        m.addAxiom(o, df.getOWLDeclarationAxiom(knownInd));
        // knownProp(knownInd, freshInd): only freshInd is new.
        Set<OWLEntity> introduced = PreviewTools.newEntities(o.getImportsClosure(),
                df.getOWLObjectPropertyAssertionAxiom(knownProp, knownInd, freshInd));
        assertTrue(introduced.contains(freshInd), "freshInd is the only new entity");
        assertFalse(introduced.contains(knownProp), "knownProp is declared");
        assertFalse(introduced.contains(knownInd), "knownInd is declared");
        assertEquals(1, introduced.size(), "exactly one new entity");
    }

    @Test
    void resultPreservesInsertionOrderAndReflectsSignatureContents()
            throws OWLOntologyCreationException {
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        OWLDataProperty p = df.getOWLDataProperty(IRI.create(NS + "age"));
        OWLNamedIndividual i = df.getOWLNamedIndividual(IRI.create(NS + "bob"));
        // age(bob, "3"^^xsd:integer): p and i are new; the literal/datatype contributes no named entity
        // that should be reported beyond the (built-in) datatype which is filtered.
        Set<OWLEntity> introduced = PreviewTools.newEntities(o.getImportsClosure(),
                df.getOWLDataPropertyAssertionAxiom(p, i, df.getOWLLiteral(3)));
        assertTrue(introduced.contains(p), "data property is new");
        assertTrue(introduced.contains(i), "individual is new");
        for (OWLEntity e : introduced) {
            assertFalse(e.isBuiltIn(), "no built-in entity should leak into the result: " + e);
        }
    }

    // ------------------------------------------------------------------ operationsSchema

    @Test
    void operationsSchemaTopLevelObjectStructure() {
        Map<String, Object> schema = PreviewTools.operationsSchema();
        assertEquals("object", schema.get("type"), "top-level schema is an object");
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"),
                "additionalProperties is false");
        assertEquals(Arrays.asList("operations"), schema.get("required"),
                "operations is the single required field");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationsSchemaHasOperationsArrayField() {
        Map<String, Object> schema = PreviewTools.operationsSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull(props, "properties present");
        assertTrue(props.containsKey("operations"), "operations property present");
        Map<String, Object> operations = (Map<String, Object>) props.get("operations");
        assertEquals("array", operations.get("type"), "operations is an array");
        assertInstanceOf(String.class, operations.get("description"), "operations has a description");
        assertTrue(((String) operations.get("description")).startsWith("Axiom changes to preview"),
                "operations description matches source text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationsSchemaItemSchemaIsAxiomSchemaWithOpProperty() {
        Map<String, Object> schema = PreviewTools.operationsSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> operations = (Map<String, Object>) props.get("operations");
        Map<String, Object> item = (Map<String, Object>) operations.get("items");
        assertEquals("object", item.get("type"), "each item is an object schema");
        Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
        // 'op' is injected on top of the base Axioms.schema() properties.
        assertTrue(itemProps.containsKey("op"), "op property injected into item schema");
        Map<String, Object> op = (Map<String, Object>) itemProps.get("op");
        assertEquals("string", op.get("type"), "op is a string property");
        assertEquals("add (default) or remove.", op.get("description"),
                "op carries the documented description");
        // The base axiom operands are still present (proving it reuses Axioms.schema()).
        assertTrue(itemProps.containsKey("axiom_type"), "axiom_type carried over from Axioms.schema()");
        assertTrue(itemProps.containsKey("sub"), "sub carried over from Axioms.schema()");
    }

    @Test
    void operationsSchemaIsFreshEachCallSoAxiomsSchemaIsNotMutatedAcrossCalls() {
        // Each call reconstructs the schema from Axioms.schema(); mutating one result's map must not
        // corrupt a second independent call.
        Map<String, Object> first = PreviewTools.operationsSchema();
        first.put("injected", "x");
        Map<String, Object> second = PreviewTools.operationsSchema();
        assertFalse(second.containsKey("injected"), "a second call is independent of the first");
    }

    // ------------------------------------------------------------------ preview via Axioms.build (fake mm)

    @Test
    void axiomsBuildFromFullIriOperandsDrivesNewEntityDetection() {
        // Exercises the same Axioms.build + newEntities path that preview() runs, but headlessly.
        // object_property_assertion resolves operands by IRI (no Manchester/expression-checker path,
        // which the fake manager does not support), so it works fully headless: all three minted
        // entities over an empty ontology are new.
        org.protege.editor.owl.model.OWLModelManager mm = FakeModelManager.empty();
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("axiom_type", "object_property_assertion");
        item.put("property", NS + "likes");
        item.put("subject", NS + "alice");
        item.put("object", NS + "bob");
        var ax = Axioms.build(mm, item);
        OWLOntology active = mm.getActiveOntology();
        Set<OWLEntity> introduced = PreviewTools.newEntities(active.getImportsClosure(), ax);
        assertEquals(3, introduced.size(),
                "the minted property + two individuals are all new over an empty ontology");
    }

    @Test
    void axiomsBuildOverDeclaredEntitiesReportsNoNewEntities()
            throws OWLOntologyCreationException {
        // Declare the property and both individuals, then the assertion introduces nothing new.
        OWLOntologyManager m = mgr();
        OWLDataFactory df = m.getOWLDataFactory();
        OWLOntology o = m.createOntology(IRI.create("http://example.org/cov"));
        OWLObjectProperty likes = df.getOWLObjectProperty(IRI.create(NS + "likes"));
        OWLNamedIndividual alice = df.getOWLNamedIndividual(IRI.create(NS + "alice"));
        OWLNamedIndividual bob = df.getOWLNamedIndividual(IRI.create(NS + "bob"));
        m.addAxiom(o, df.getOWLDeclarationAxiom(likes));
        m.addAxiom(o, df.getOWLDeclarationAxiom(alice));
        m.addAxiom(o, df.getOWLDeclarationAxiom(bob));
        org.protege.editor.owl.model.OWLModelManager mm = FakeModelManager.over(o);
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("axiom_type", "object_property_assertion");
        item.put("property", NS + "likes");
        item.put("subject", NS + "alice");
        item.put("object", NS + "bob");
        var ax = Axioms.build(mm, item);
        Set<OWLEntity> introduced = PreviewTools.newEntities(o.getImportsClosure(), ax);
        assertTrue(introduced.isEmpty(), "all operands already declared, so nothing is introduced");
    }

    @Test
    void axiomsBuildRejectsUnsupportedAxiomTypeWithMessage() {
        // preview() catches this RuntimeException and records its message; verify Axioms.build throws it.
        org.protege.editor.owl.model.OWLModelManager mm = FakeModelManager.empty();
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("axiom_type", "not_a_real_type");
        ToolArgException ex = org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, item));
        assertTrue(ex.getMessage().contains("Unsupported axiom_type"),
                "message names the unsupported type problem: " + ex.getMessage());
    }

    @Test
    void axiomsBuildMissingRequiredOperandThrows() {
        // subclass_of with no 'sub'/'super' — preview() would surface this as a per-op error row.
        org.protege.editor.owl.model.OWLModelManager mm = FakeModelManager.empty();
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("axiom_type", "subclass_of");
        ToolArgException ex = org.junit.jupiter.api.Assertions.assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, item));
        assertTrue(ex.getMessage().contains("Missing required argument"),
                "message flags the missing operand: " + ex.getMessage());
    }

    @Test
    void newEntitiesDetectsMintedAnnotationAndDataPropertyKinds() {
        // A declaration axiom over a full-IRI annotation property mints a fresh annotation property.
        org.protege.editor.owl.model.OWLModelManager mm = FakeModelManager.empty();
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("axiom_type", "declaration");
        item.put("entity", NS + "note");
        item.put("entity_type", "annotation_property");
        var ax = Axioms.build(mm, item);
        Set<OWLEntity> introduced =
                PreviewTools.newEntities(mm.getActiveOntology().getImportsClosure(), ax);
        assertEquals(1, introduced.size(), "the minted annotation property is new");
        OWLEntity only = introduced.iterator().next();
        assertInstanceOf(OWLAnnotationProperty.class, only, "declared entity is an annotation property");
    }
}
