package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
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

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Method-level tests for the Protégé-free helper cores of {@link EntityRefactorTools}: the private
 * statics ({@code typeKey}, {@code byIri}, {@code resolveTargets}, {@code describe}, invoked
 * reflectively) and the package-private tool cores {@code renameEntity}/{@code deleteEntity} (called
 * directly), in both preview and apply mode. Entity resolution and rendering are driven through the
 * shared {@link FakeModelManager} harness over hand-built in-memory ontologies; the fake's
 * {@code applyChanges} routes to the real OWL API manager, so apply-mode mutation is observable.
 *
 * <p>The {@code register(ToolRegistry, ToolContext)} wiring and its handler lambdas are intentionally
 * not exercised here: they route through {@code WriteTools.write} and the access gate against the
 * live Protégé runtime, which is out of scope for a headless unit test.
 */
class EntityRefactorToolsTest {

    private static final String NS = "http://example.org/ref#";

    // ------------------------------------------------------------------ reflection plumbing

    private static Method priv(String name, Class<?>... params) {
        try {
            Method m = EntityRefactorTools.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing private method " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<OWLEntity> resolveTargets(OWLModelManager mm, String ref, String type) {
        try {
            return (Set<OWLEntity>) priv("resolveTargets", OWLModelManager.class, String.class,
                    String.class).invoke(null, mm, ref, type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e.getCause() != null ? e.getCause() : e);
        }
    }

    private static String typeKey(OWLEntity e) {
        try {
            return (String) priv("typeKey", OWLEntity.class).invoke(null, e);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex.getCause() != null ? ex.getCause() : ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Comparator<OWLEntity> byIri() {
        try {
            return (Comparator<OWLEntity>) priv("byIri").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e.getCause() != null ? e.getCause() : e);
        }
    }

    private static String describe(OWLModelManager mm, Set<OWLEntity> targets) {
        try {
            return (String) priv("describe", OWLModelManager.class, Set.class).invoke(null, mm, targets);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e.getCause() != null ? e.getCause() : e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult r) {
        return (Map<String, Object>) r.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    private static int intOf(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).intValue();
    }

    // ------------------------------------------------------------------ fixtures

    private OWLDataFactory df;
    private OWLOntologyManager m;

    private OWLOntology fresh() throws OWLOntologyCreationException {
        m = OWLManager.createOWLOntologyManager();
        df = m.getOWLDataFactory();
        return m.createOntology(IRI.create("http://example.org/ref"));
    }

    /** Declare an entity in the ontology signature so the finder can resolve it. */
    private void declare(OWLOntology o, OWLEntity e) {
        m.addAxiom(o, df.getOWLDeclarationAxiom(e));
    }

    /**
     * Rename fixture: {@code Widget} referenced by three axioms (its declaration, a SubClassOf to
     * {@code Device}, and an rdfs:label), so a rename rewrites 3 axioms = 6 changes (remove + add
     * per axiom). {@code Device} is declared too, giving a collision target for merge previews.
     */
    private OWLOntology renameFixture() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLClass widget = df.getOWLClass(IRI.create(NS + "Widget"));
        OWLClass device = df.getOWLClass(IRI.create(NS + "Device"));
        declare(o, widget);
        declare(o, device);
        m.addAxiom(o, df.getOWLSubClassOfAxiom(widget, device));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), widget.getIRI(),
                df.getOWLLiteral("widget", "en")));
        return o;
    }

    /**
     * Delete fixture: class {@code A} referenced by three axioms (declaration, rdfs:label, and a
     * SubClassOf to the declared class {@code B}), so deleting {@code A} removes exactly 3 axioms
     * and leaves only {@code B}'s declaration behind.
     */
    private OWLOntology deleteFixture() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        declare(o, a);
        declare(o, b);
        m.addAxiom(o, df.getOWLSubClassOfAxiom(a, b));
        m.addAxiom(o, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), a.getIRI(),
                df.getOWLLiteral("a label")));
        return o;
    }

    /**
     * Pun fixture: one IRI {@code Pun} declared both as a class (with a SubClassOf to {@code Other})
     * and as a named individual (with a ClassAssertion), so an {@code entity_type} filter can carve
     * off exactly one of the two puns. 5 axioms in total; 2 reference only the class pun.
     */
    private OWLOntology punFixture() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        IRI pun = IRI.create(NS + "Pun");
        OWLClass punCls = df.getOWLClass(pun);
        OWLNamedIndividual punInd = df.getOWLNamedIndividual(pun);
        OWLClass other = df.getOWLClass(IRI.create(NS + "Other"));
        declare(o, punCls);
        declare(o, punInd);
        declare(o, other);
        m.addAxiom(o, df.getOWLSubClassOfAxiom(punCls, other));
        m.addAxiom(o, df.getOWLClassAssertionAxiom(other, punInd));
        return o;
    }

    // ------------------------------------------------------------------ typeKey

    @Test
    void typeKeyClass() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        assertEquals("class", typeKey(f.getOWLClass(IRI.create(NS + "C"))));
    }

    @Test
    void typeKeyObjectProperty() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        assertEquals("object_property", typeKey(f.getOWLObjectProperty(IRI.create(NS + "op"))));
    }

    @Test
    void typeKeyDataProperty() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        assertEquals("data_property", typeKey(f.getOWLDataProperty(IRI.create(NS + "dp"))));
    }

    @Test
    void typeKeyAnnotationProperty() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        assertEquals("annotation_property", typeKey(f.getOWLAnnotationProperty(IRI.create(NS + "ap"))));
    }

    @Test
    void typeKeyIndividual() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        assertEquals("individual", typeKey(f.getOWLNamedIndividual(IRI.create(NS + "i"))));
    }

    @Test
    void typeKeyDatatype() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        // A custom datatype IRI (not a built-in); still an OWLDatatype instance.
        assertEquals("datatype", typeKey(f.getOWLDatatype(IRI.create(NS + "myType"))));
    }

    // ------------------------------------------------------------------ byIri

    @Test
    void byIriOrdersByEntityTypeNameThenIri() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        Comparator<OWLEntity> cmp = byIri();
        OWLClass a = f.getOWLClass(IRI.create(NS + "Aaa"));
        OWLClass b = f.getOWLClass(IRI.create(NS + "Bbb"));
        // Same entity type (Class): ordering falls through to the IRI, Aaa < Bbb.
        assertTrue(cmp.compare(a, b) < 0, "same type sorts by IRI ascending");
        assertTrue(cmp.compare(b, a) > 0, "reverse of the pair is positive");
    }

    @Test
    void byIriIsReflexiveOnEqualEntities() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        Comparator<OWLEntity> cmp = byIri();
        OWLClass a = f.getOWLClass(IRI.create(NS + "Aaa"));
        assertEquals(0, cmp.compare(a, a), "an entity compares equal to itself");
    }

    @Test
    void byIriDedupesInTreeSetAndSortsMixedTypes() {
        OWLDataFactory f = OWLManager.createOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = f.getOWLClass(IRI.create(NS + "Z"));
        OWLObjectProperty op = f.getOWLObjectProperty(IRI.create(NS + "A"));
        OWLNamedIndividual ind = f.getOWLNamedIndividual(IRI.create(NS + "M"));
        TreeSet<OWLEntity> set = new TreeSet<>(byIri());
        set.add(cls);
        set.add(op);
        set.add(ind);
        set.add(cls); // duplicate: no-op
        assertEquals(3, set.size(), "distinct entity/type pairs are retained");
        // Type-name order: Class < NamedIndividual < ObjectProperty (lexicographic on the enum name).
        List<OWLEntity> ordered = new ArrayList<>(set);
        assertSame(cls, ordered.get(0), "Class sorts first by type name");
        assertSame(ind, ordered.get(1), "NamedIndividual sorts second");
        assertSame(op, ordered.get(2), "ObjectProperty sorts last");
    }

    // ------------------------------------------------------------------ resolveTargets

    @Test
    void resolveTargetsNullTypeReturnsAllMatches() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLClass c = df.getOWLClass(IRI.create(NS + "Widget"));
        declare(o, c);
        OWLModelManager mm = FakeModelManager.over(o);

        Set<OWLEntity> got = resolveTargets(mm, NS + "Widget", null);
        assertEquals(1, got.size(), "the single declared class is returned");
        assertTrue(got.contains(c));
    }

    @Test
    void resolveTargetsNoMatchReturnsEmpty() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        declare(o, df.getOWLClass(IRI.create(NS + "Present")));
        OWLModelManager mm = FakeModelManager.over(o);

        assertTrue(resolveTargets(mm, NS + "Absent", null).isEmpty(),
                "an IRI with no signature entity yields the empty set");
    }

    @Test
    void resolveTargetsNoMatchWithTypeReturnsEmpty() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLModelManager mm = FakeModelManager.over(o);
        assertTrue(resolveTargets(mm, NS + "Nothing", "class").isEmpty(),
                "type filter over an empty match set is still empty");
    }

    @Test
    void resolveTargetsFiltersPunToRequestedType() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        IRI shared = IRI.create(NS + "Pun");
        OWLClass cls = df.getOWLClass(shared);
        OWLObjectProperty op = df.getOWLObjectProperty(shared);
        declare(o, cls);
        declare(o, op);
        OWLModelManager mm = FakeModelManager.over(o);

        // No filter: both puns come back.
        assertEquals(2, resolveTargets(mm, shared.toString(), null).size(),
                "unfiltered pun resolution returns every entity at the IRI");

        Set<OWLEntity> onlyClass = resolveTargets(mm, shared.toString(), "class");
        assertEquals(1, onlyClass.size(), "type=class narrows the pun to the class");
        assertTrue(onlyClass.contains(cls));
        assertFalse(onlyClass.contains(op), "the object-property pun is excluded");

        Set<OWLEntity> onlyOp = resolveTargets(mm, shared.toString(), "object_property");
        assertEquals(1, onlyOp.size(), "type=object_property narrows the pun to the property");
        assertTrue(onlyOp.contains(op));
    }

    @Test
    void resolveTargetsTypeFilterIsCaseInsensitive() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLClass cls = df.getOWLClass(IRI.create(NS + "Thing1"));
        declare(o, cls);
        OWLModelManager mm = FakeModelManager.over(o);

        assertEquals(1, resolveTargets(mm, NS + "Thing1", "CLASS").size(),
                "an upper-case type is lower-cased before matching");
        assertEquals(1, resolveTargets(mm, NS + "Thing1", "Class").size(),
                "a mixed-case type is lower-cased before matching");
    }

    @Test
    void resolveTargetsUnknownTypeYieldsEmpty() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        declare(o, df.getOWLClass(IRI.create(NS + "Thing2")));
        OWLModelManager mm = FakeModelManager.over(o);
        assertTrue(resolveTargets(mm, NS + "Thing2", "bogus_type").isEmpty(),
                "a type that matches no entity's typeKey filters everything out");
    }

    @Test
    void resolveTargetsMatchesEachEntityTypeExactly() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        // Six distinct IRIs, one per entity kind, so each filter has a unique target.
        OWLClass cls = df.getOWLClass(IRI.create(NS + "K_class"));
        OWLObjectProperty op = df.getOWLObjectProperty(IRI.create(NS + "K_op"));
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "K_dp"));
        OWLAnnotationProperty ap = df.getOWLAnnotationProperty(IRI.create(NS + "K_ap"));
        OWLNamedIndividual ind = df.getOWLNamedIndividual(IRI.create(NS + "K_ind"));
        OWLDatatype dt = df.getOWLDatatype(IRI.create(NS + "K_dt"));
        for (OWLEntity e : new OWLEntity[] { cls, op, dp, ap, ind, dt }) {
            declare(o, e);
        }
        OWLModelManager mm = FakeModelManager.over(o);

        assertOnly(resolveTargets(mm, NS + "K_class", "class"), cls);
        assertOnly(resolveTargets(mm, NS + "K_op", "object_property"), op);
        assertOnly(resolveTargets(mm, NS + "K_dp", "data_property"), dp);
        assertOnly(resolveTargets(mm, NS + "K_ap", "annotation_property"), ap);
        assertOnly(resolveTargets(mm, NS + "K_ind", "individual"), ind);
        assertOnly(resolveTargets(mm, NS + "K_dt", "datatype"), dt);
    }

    @Test
    void resolveTargetsReturnsTreeSetSortedByTypeThenIri() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        IRI shared = IRI.create(NS + "MultiPun");
        // Declare three puns at ONE IRI so all survive a null filter and are sorted by type name.
        OWLObjectProperty op = df.getOWLObjectProperty(shared);
        OWLClass cls = df.getOWLClass(shared);
        OWLNamedIndividual ind = df.getOWLNamedIndividual(shared);
        declare(o, op);
        declare(o, cls);
        declare(o, ind);
        OWLModelManager mm = FakeModelManager.over(o);

        // With a matching-but-not-narrowing scenario we still exercise the TreeSet branch: pass a
        // type that keeps more than one? Each pun has a distinct type, so use null-filter path is
        // the LinkedHashSet ("all") path. To hit the TreeSet branch we filter to a real type.
        Set<OWLEntity> filtered = resolveTargets(mm, shared.toString(), "class");
        assertTrue(filtered instanceof TreeSet, "the type-filtered result is the sorted TreeSet");
        assertEquals(1, filtered.size());
        assertTrue(filtered.contains(cls));
    }

    // ------------------------------------------------------------------ describe

    @Test
    void describeSingleEntityUsesTypeAndRendering() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLClass cls = df.getOWLClass(IRI.create(NS + "Gadget"));
        declare(o, cls);
        OWLModelManager mm = FakeModelManager.over(o);

        Set<OWLEntity> one = new LinkedHashSet<>();
        one.add(cls);
        String d = describe(mm, one);
        // "Class " + renderEntity, where renderEntity = rendering + "  <iri>".
        assertEquals("Class Gadget  <" + NS + "Gadget>", d,
                "single-entity describe is 'EntityType renderEntity'");
    }

    @Test
    void describeSingleObjectPropertyUsesItsTypeName() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLObjectProperty op = df.getOWLObjectProperty(IRI.create(NS + "hasPart"));
        declare(o, op);
        OWLModelManager mm = FakeModelManager.over(o);

        Set<OWLEntity> one = new LinkedHashSet<>();
        one.add(op);
        assertEquals("ObjectProperty hasPart  <" + NS + "hasPart>", describe(mm, one),
                "the entity type name comes from getEntityType().getName()");
    }

    @Test
    void describeMultipleEntitiesSummarisesWithCountAndIri() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        IRI shared = IRI.create(NS + "Punned");
        OWLClass cls = df.getOWLClass(shared);
        OWLObjectProperty op = df.getOWLObjectProperty(shared);
        OWLModelManager mm = FakeModelManager.over(o);

        // Use the sorted TreeSet so the "first" element's IRI is deterministic.
        Set<OWLEntity> two = new TreeSet<>(byIri());
        two.add(cls);
        two.add(op);
        String d = describe(mm, two);
        assertEquals("2 entities at <" + shared + ">", d,
                "multi-entity describe reports the count and the shared IRI");
    }

    // ------------------------------------------------------------------ renameEntity: preview

    @Test
    void renamePreviewReportsChangesWithoutMutating() throws OWLOntologyCreationException {
        OWLOntology o = renameFixture();
        OWLModelManager mm = FakeModelManager.over(o);
        int axiomsBefore = o.getAxiomCount();

        CallToolResult r = EntityRefactorTools.renameEntity(mm, NS + "Widget", NS + "Gizmo", true);
        Map<String, Object> res = structured(r);

        assertEquals(Boolean.TRUE, res.get("preview"), "preview mode is reported");
        assertEquals(NS + "Widget", res.get("old_iri"), "old IRI is echoed");
        assertEquals(NS + "Gizmo", res.get("new_iri"), "new IRI is echoed");
        assertEquals(6, intOf(res, "changes"), "3 referencing axioms -> remove+add each = 6 changes");
        assertEquals(Boolean.FALSE, res.get("new_iri_already_in_signature"),
                "Gizmo does not exist yet, so no merge collision");
        List<Map<String, Object>> sample = mapList(res, "rewritten_axioms_sample");
        assertEquals(3, sample.size(), "one sample entry per rewritten (add) axiom");
        for (Map<String, Object> ax : sample) {
            String rendering = String.valueOf(ax.get("rendering"));
            assertTrue(rendering.contains(NS + "Gizmo"),
                    "sample shows the axiom as it would read AFTER the rename: " + rendering);
            assertFalse(rendering.contains(NS + "Widget"),
                    "the old IRI no longer appears in the rewritten axiom: " + rendering);
        }
        assertTrue(String.valueOf(res.get("note")).contains("Nothing was changed"),
                "the note says the preview applied nothing");

        // And indeed nothing changed.
        assertEquals(axiomsBefore, o.getAxiomCount(), "axiom count is untouched by the preview");
        assertTrue(o.containsEntityInSignature(IRI.create(NS + "Widget")),
                "the old IRI is still in the signature");
        assertFalse(o.containsEntityInSignature(IRI.create(NS + "Gizmo")),
                "the new IRI was not introduced");
    }

    @Test
    void renamePreviewFlagsSignatureCollisionAsMerge() throws OWLOntologyCreationException {
        OWLOntology o = renameFixture();
        OWLModelManager mm = FakeModelManager.over(o);
        int axiomsBefore = o.getAxiomCount();

        // Device is already declared: renaming Widget onto it would merge the two classes.
        CallToolResult r = EntityRefactorTools.renameEntity(mm, NS + "Widget", NS + "Device", true);
        Map<String, Object> res = structured(r);

        assertEquals(Boolean.TRUE, res.get("preview"));
        assertEquals(Boolean.TRUE, res.get("new_iri_already_in_signature"),
                "the existing Device IRI is flagged as a collision");
        assertTrue(String.valueOf(res.get("note")).contains("merge"),
                "the note warns that the rename would merge the two entities");
        assertEquals(axiomsBefore, o.getAxiomCount(), "the collision preview changed nothing");
        assertTrue(o.containsEntityInSignature(IRI.create(NS + "Widget")),
                "Widget survives the preview");
    }

    // ------------------------------------------------------------------ renameEntity: apply

    @Test
    void renameApplyRewritesReferencesAndMatchesPreviewCount() throws OWLOntologyCreationException {
        // Preview on one fixture...
        OWLModelManager previewMm = FakeModelManager.over(renameFixture());
        int previewedChanges = intOf(structured(
                EntityRefactorTools.renameEntity(previewMm, NS + "Widget", NS + "Gizmo", true)),
                "changes");

        // ...apply on an identical fixture: same change count, but this time it mutates.
        OWLOntology o = renameFixture();
        OWLModelManager mm = FakeModelManager.over(o);
        int axiomsBefore = o.getAxiomCount();
        CallToolResult r = EntityRefactorTools.renameEntity(mm, NS + "Widget", NS + "Gizmo", false);
        Map<String, Object> res = structured(r);

        assertEquals(Boolean.TRUE, res.get("renamed"), "apply mode reports renamed=true");
        assertEquals(NS + "Widget", res.get("old_iri"));
        assertEquals(NS + "Gizmo", res.get("new_iri"));
        assertEquals(previewedChanges, intOf(res, "changes"),
                "apply performs exactly the change list the preview reported");
        assertFalse(o.containsEntityInSignature(IRI.create(NS + "Widget")),
                "the old IRI is gone from the signature");
        assertTrue(o.containsEntityInSignature(IRI.create(NS + "Gizmo")),
                "the new IRI took its place");
        assertEquals(axiomsBefore, o.getAxiomCount(),
                "a rename rewrites axioms without changing their number");
    }

    // ------------------------------------------------------------------ deleteEntity: preview

    @Test
    void deletePreviewReportsBlastRadiusWithoutMutating() throws OWLOntologyCreationException {
        OWLOntology o = deleteFixture();
        OWLModelManager mm = FakeModelManager.over(o);
        int axiomsBefore = o.getAxiomCount();

        CallToolResult r = EntityRefactorTools.deleteEntity(mm, NS + "A", null, true);
        Map<String, Object> res = structured(r);

        assertEquals(Boolean.TRUE, res.get("preview"), "preview mode is reported");
        List<Map<String, Object>> wouldDelete = mapList(res, "would_delete");
        assertEquals(1, wouldDelete.size(), "exactly the one resolved entity would be deleted");
        assertEquals(NS + "A", wouldDelete.get(0).get("iri"));
        assertEquals("Class", wouldDelete.get(0).get("type"));
        assertEquals(3, intOf(res, "removed_axioms"),
                "declaration + label + SubClassOf all reference A");
        assertEquals(3, mapList(res, "removed_axioms_sample").size(),
                "every removed axiom fits in the sample");
        assertTrue(String.valueOf(res.get("note")).contains("Nothing was deleted"),
                "the note says the preview applied nothing");

        assertEquals(axiomsBefore, o.getAxiomCount(), "axiom count is untouched by the preview");
        assertTrue(o.containsEntityInSignature(IRI.create(NS + "A")),
                "A is still in the signature");
    }

    @Test
    void deletePreviewSampleIsCappedAtLimit() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        OWLClass hub = df.getOWLClass(IRI.create(NS + "Hub"));
        declare(o, hub);
        // 25 subclass axioms + the declaration = 26 referencing axioms, above the 20-axiom cap.
        for (int i = 0; i < 25; i++) {
            OWLClass spoke = df.getOWLClass(IRI.create(NS + "Spoke" + i));
            declare(o, spoke);
            m.addAxiom(o, df.getOWLSubClassOfAxiom(spoke, hub));
        }
        OWLModelManager mm = FakeModelManager.over(o);

        Map<String, Object> res = structured(
                EntityRefactorTools.deleteEntity(mm, NS + "Hub", null, true));
        assertEquals(26, intOf(res, "removed_axioms"), "the count reports the full blast radius");
        assertEquals(EntityRefactorTools.PREVIEW_SAMPLE_LIMIT,
                mapList(res, "removed_axioms_sample").size(),
                "the rendered sample stops at PREVIEW_SAMPLE_LIMIT");
    }

    // ------------------------------------------------------------------ deleteEntity: apply

    @Test
    void deleteApplyRemovesEntityAndReferencingAxioms() throws OWLOntologyCreationException {
        OWLOntology o = deleteFixture();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        OWLModelManager mm = FakeModelManager.over(o);

        CallToolResult r = EntityRefactorTools.deleteEntity(mm, NS + "A", null, false);
        Map<String, Object> res = structured(r);

        List<Map<String, Object>> deleted = mapList(res, "deleted");
        assertEquals(1, deleted.size(), "the one deleted entity is rendered back");
        assertEquals(NS + "A", deleted.get(0).get("iri"));
        assertEquals("A", deleted.get(0).get("display"), "display uses the manager rendering");
        assertEquals(3, intOf(res, "removed_axioms"));

        assertFalse(o.containsEntityInSignature(a.getIRI()), "A is gone from the signature");
        assertFalse(o.containsAxiom(df.getOWLSubClassOfAxiom(a, b)),
                "the SubClassOf referencing A was removed");
        assertTrue(o.containsAxiom(df.getOWLDeclarationAxiom(b)),
                "the unrelated declaration of B survives");
        assertEquals(1, o.getAxiomCount(), "only B's declaration is left");
    }

    @Test
    void deletePunnedIriNarrowedByEntityTypePreviewThenApply() throws OWLOntologyCreationException {
        OWLOntology o = punFixture();
        IRI pun = IRI.create(NS + "Pun");
        OWLClass punCls = df.getOWLClass(pun);
        OWLNamedIndividual punInd = df.getOWLNamedIndividual(pun);
        OWLClass other = df.getOWLClass(IRI.create(NS + "Other"));
        OWLModelManager mm = FakeModelManager.over(o);

        // Preview first: only the class pun and its 2 referencing axioms are in scope.
        Map<String, Object> preview = structured(
                EntityRefactorTools.deleteEntity(mm, pun.toString(), "class", true));
        assertEquals(Boolean.TRUE, preview.get("preview"));
        List<Map<String, Object>> wouldDelete = mapList(preview, "would_delete");
        assertEquals(1, wouldDelete.size(), "entity_type=class narrows the pun to one entity");
        assertEquals("Class", wouldDelete.get(0).get("type"));
        assertEquals(2, intOf(preview, "removed_axioms"),
                "only the class declaration and its SubClassOf are counted");
        assertEquals(5, o.getAxiomCount(), "the preview left all 5 axioms in place");

        // Then apply on the same (untouched) ontology.
        Map<String, Object> res = structured(
                EntityRefactorTools.deleteEntity(mm, pun.toString(), "class", false));
        assertEquals(1, mapList(res, "deleted").size());
        assertEquals("Class", mapList(res, "deleted").get(0).get("type"));
        assertEquals(2, intOf(res, "removed_axioms"));

        assertFalse(o.containsAxiom(df.getOWLDeclarationAxiom(punCls)),
                "the class pun's declaration was removed");
        assertFalse(o.containsAxiom(df.getOWLSubClassOfAxiom(punCls, other)),
                "the class pun's SubClassOf was removed");
        assertTrue(o.containsAxiom(df.getOWLDeclarationAxiom(punInd)),
                "the individual pun's declaration survives");
        assertTrue(o.containsAxiom(df.getOWLClassAssertionAxiom(other, punInd)),
                "the individual pun's ClassAssertion survives");
        assertTrue(o.containsEntityInSignature(pun),
                "the IRI stays in the signature through the surviving individual");
    }

    // ------------------------------------------------------------------ error branches

    @Test
    void renameUnknownEntityIsErrorInBothModes() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.over(renameFixture());
        for (boolean preview : new boolean[] { true, false }) {
            CallToolResult r = EntityRefactorTools.renameEntity(
                    mm, NS + "Missing", NS + "Anywhere", preview);
            assertEquals(Boolean.TRUE, r.isError(), "unknown entity is an error (preview=" + preview + ")");
            assertTrue(String.valueOf(structured(r).get("error")).contains("Entity not found"),
                    "the error names the failure");
        }
    }

    @Test
    void renameRelativeNewIriIsErrorInBothModes() throws OWLOntologyCreationException {
        OWLOntology o = renameFixture();
        OWLModelManager mm = FakeModelManager.over(o);
        int axiomsBefore = o.getAxiomCount();
        for (boolean preview : new boolean[] { true, false }) {
            CallToolResult r = EntityRefactorTools.renameEntity(mm, NS + "Widget", "JustAName", preview);
            assertEquals(Boolean.TRUE, r.isError(), "a relative new_iri is refused (preview=" + preview + ")");
            assertTrue(String.valueOf(structured(r).get("error"))
                    .contains("new_iri must be a full absolute IRI"), "the error explains the requirement");
        }
        assertEquals(axiomsBefore, o.getAxiomCount(), "nothing was applied on the error path");
    }

    @Test
    void renameToIdenticalIriIsError() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.over(renameFixture());
        CallToolResult r = EntityRefactorTools.renameEntity(mm, NS + "Widget", NS + "Widget", false);
        assertEquals(Boolean.TRUE, r.isError(), "renaming to the same IRI is refused");
        assertTrue(String.valueOf(structured(r).get("error")).contains("already has IRI"),
                "the error says the entity already carries that IRI");
    }

    @Test
    void deleteUnknownEntityIsErrorAndNamesTheTypeFilter() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.over(deleteFixture());
        CallToolResult r = EntityRefactorTools.deleteEntity(mm, NS + "Missing", null, true);
        assertEquals(Boolean.TRUE, r.isError(), "unknown entity is an error");
        assertTrue(String.valueOf(structured(r).get("error")).contains("Entity not found"));

        // A pun narrowed to a type with no entity of that type also fails, and says which type.
        CallToolResult typed = EntityRefactorTools.deleteEntity(mm, NS + "A", "individual", false);
        assertEquals(Boolean.TRUE, typed.isError(), "no pun of the requested type -> error");
        assertTrue(String.valueOf(structured(typed).get("error")).contains("of type individual"),
                "the error repeats the entity_type filter");
    }

    @Test
    void deleteEntityWithNoReferencingAxiomsIsError() throws OWLOntologyCreationException {
        OWLOntology o = fresh();
        // An annotation property used ONLY in an ontology annotation: it is in the signature (so it
        // resolves), but no axiom references it, so the remover has nothing to remove.
        OWLAnnotationProperty note = df.getOWLAnnotationProperty(IRI.create(NS + "note"));
        m.applyChange(new AddOntologyAnnotation(o,
                df.getOWLAnnotation(note, df.getOWLLiteral("scratch"))));
        OWLModelManager mm = FakeModelManager.over(o);

        for (boolean preview : new boolean[] { true, false }) {
            CallToolResult r = EntityRefactorTools.deleteEntity(mm, NS + "note", null, preview);
            assertEquals(Boolean.TRUE, r.isError(), "no referencing axioms -> error (preview=" + preview + ")");
            assertTrue(String.valueOf(structured(r).get("error")).contains("Nothing to delete"),
                    "the error explains that no axiom references the entity");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assertOnly(Set<OWLEntity> got, OWLEntity expected) {
        assertEquals(1, got.size(), "exactly one entity expected for " + expected);
        assertTrue(got.contains(expected), "expected " + expected + " in " + got);
    }
}
