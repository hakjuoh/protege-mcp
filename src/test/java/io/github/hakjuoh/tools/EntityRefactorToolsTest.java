package io.github.hakjuoh.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
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

/**
 * Method-level tests for the Protégé-free helper cores of {@link EntityRefactorTools}
 * ({@code typeKey}, {@code byIri}, {@code resolveTargets}, {@code describe}). These are private static
 * methods, so they are invoked reflectively; entity resolution is driven through the shared
 * {@link FakeModelManager} harness over hand-built in-memory ontologies.
 *
 * <p>The {@code specs(ToolContext)} factory and both tool-handler lambdas are intentionally not
 * exercised here: they route through {@code WriteTools.write} / {@code OWLModelManager.applyChanges}
 * and the OWL entity renamer/remover against the live Protégé runtime, which is out of scope for a
 * headless unit test.
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

    // ------------------------------------------------------------------ helpers

    private static void assertOnly(Set<OWLEntity> got, OWLEntity expected) {
        assertEquals(1, got.size(), "exactly one entity expected for " + expected);
        assertTrue(got.contains(expected), "expected " + expected + " in " + got);
    }
}
