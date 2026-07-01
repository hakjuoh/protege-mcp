package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;

/**
 * Method-level tests for {@link Axioms}.
 *
 * <p>Two sets of methods are covered. The pure static helpers {@code SUPPORTED} and {@code schema()}
 * need no runtime. The {@code build(OWLModelManager, Map)} / {@code literal(OWLModelManager, Map)}
 * paths are driven through the headless {@link FakeModelManager} harness: operands are given as full
 * absolute IRIs (which mint fresh entities) or as short names of entities pre-declared in the backing
 * ontology (so the signature-backed finder resolves them). Manchester-syntax class/data-range paths,
 * which need the live Protégé expression checker, are intentionally out of scope — the fake throws
 * {@link UnsupportedOperationException} there, so those exact refs are avoided.
 */
class AxiomsTest {

    private static final String NS = "http://example.org/ax#";

    // ------------------------------------------------------------------ fixtures

    private OWLOntology emptyOntology() throws OWLOntologyCreationException {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        return m.createOntology(IRI.create("http://example.org/ax"));
    }

    /** A fresh manager over an ontology that already declares the given entities (so names resolve). */
    private OWLModelManager managerDeclaring(OWLEntity... entities) throws OWLOntologyCreationException {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        for (OWLEntity e : entities) {
            o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(e));
        }
        return FakeModelManager.over(o);
    }

    private OWLDataFactory df(OWLModelManager mm) {
        return mm.getOWLDataFactory();
    }

    /**
     * A manager whose ontology declares classes with the given short names, referenced by short name.
     * Class-expression operands (sub/super/class/domain/object-range) only take the checker-free fast
     * path when the referenced class is already in the signature, so class operands must be declared.
     */
    private OWLModelManager managerWithClasses(String... shortNames) throws OWLOntologyCreationException {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        for (String name : shortNames) {
            o.getOWLOntologyManager().addAxiom(o,
                    df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + name))));
        }
        return FakeModelManager.over(o);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ================================================================== SUPPORTED constant

    @Test
    void supportedIsNonEmptyString() {
        assertNotNull(Axioms.SUPPORTED, "SUPPORTED must not be null");
        assertFalse(Axioms.SUPPORTED.isBlank(), "SUPPORTED must not be blank");
    }

    @Test
    void supportedListsEveryKnownAxiomType() {
        String[] expected = {
                "subclass_of", "equivalent_classes", "disjoint_classes", "disjoint_union",
                "class_assertion", "object_property_assertion", "data_property_assertion",
                "negative_object_property_assertion", "negative_data_property_assertion",
                "same_individual", "different_individuals", "sub_object_property_of",
                "sub_data_property_of", "sub_property_chain_of", "equivalent_object_properties",
                "equivalent_data_properties", "disjoint_object_properties", "disjoint_data_properties",
                "inverse_object_properties", "transitive_object_property", "functional_object_property",
                "inverse_functional_object_property", "symmetric_object_property",
                "asymmetric_object_property", "reflexive_object_property", "irreflexive_object_property",
                "functional_data_property", "has_key", "object_property_domain", "object_property_range",
                "data_property_domain", "data_property_range", "annotation_assertion",
                "sub_annotation_property_of", "annotation_property_domain", "annotation_property_range",
                "declaration", "datatype_definition" };
        for (String t : expected) {
            assertTrue(Axioms.SUPPORTED.contains(t), "SUPPORTED should mention '" + t + "'");
        }
    }

    @Test
    void supportedIsCommaSeparatedWithoutTrailingSeparator() {
        List<String> tokens = new ArrayList<>();
        for (String t : Axioms.SUPPORTED.split(",")) {
            String trimmed = t.trim();
            assertFalse(trimmed.isEmpty(), "no empty token in SUPPORTED list");
            tokens.add(trimmed);
        }
        assertEquals(38, tokens.size(), "SUPPORTED should list 38 axiom types");
        assertFalse(Axioms.SUPPORTED.endsWith(","), "no trailing comma");
    }

    // ================================================================== schema()

    @Test
    void schemaIsAnObjectSchemaWithProperties() {
        Map<String, Object> schema = Axioms.schema();
        assertNotNull(schema, "schema() must not return null");
        assertEquals("object", schema.get("type"), "top-level schema type is object");
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"),
                "schema forbids additional properties");
        assertTrue(schema.get("properties") instanceof Map, "schema exposes a properties map");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaRequiresAxiomTypeWithSupportedValues() {
        Map<String, Object> schema = Axioms.schema();
        List<String> required = (List<String>) schema.get("required");
        assertNotNull(required, "schema must declare required fields");
        assertTrue(required.contains("axiom_type"), "axiom_type is required");

        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> axiomType = (Map<String, Object>) props.get("axiom_type");
        assertEquals("string", axiomType.get("type"), "axiom_type is a string field");
        assertEquals(Axioms.SUPPORTED, axiomType.get("description"),
                "axiom_type description carries the SUPPORTED list");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaDocumentsAllOperandKeys() {
        Map<String, Object> props =
                (Map<String, Object>) Axioms.schema().get("properties");
        for (String key : Arrays.asList("sub", "super", "classes", "class", "individual", "individuals",
                "property", "properties", "super_property", "chain", "inverse_property", "subject",
                "object", "value", "value_iri", "lang", "datatype", "entity", "entity_type", "domain",
                "range", "annotations")) {
            assertTrue(props.containsKey(key), "schema should document operand '" + key + "'");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaAnnotationsIsAnArrayOfObjects() {
        Map<String, Object> props =
                (Map<String, Object>) Axioms.schema().get("properties");
        Map<String, Object> annotations = (Map<String, Object>) props.get("annotations");
        assertEquals("array", annotations.get("type"), "annotations is an array");
        Map<String, Object> item = (Map<String, Object>) annotations.get("items");
        assertEquals("object", item.get("type"), "each annotation entry is an object");
        Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
        for (String key : Arrays.asList("property", "value", "value_iri", "lang", "datatype")) {
            assertTrue(itemProps.containsKey(key), "annotation item should document '" + key + "'");
        }
    }

    // ================================================================== build(): class axioms

    @Test
    void buildSubclassOfFromDeclaredClasses() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Dog", "Animal");
        OWLDataFactory df = df(mm);
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "subclass_of",
                "sub", "Dog",
                "super", "Animal"));
        assertInstanceOf(OWLSubClassOfAxiom.class, ax, "subclass_of builds a SubClassOf axiom");
        OWLSubClassOfAxiom sc = (OWLSubClassOfAxiom) ax;
        assertEquals(df.getOWLClass(IRI.create(NS + "Dog")), sc.getSubClass(), "sub operand");
        assertEquals(df.getOWLClass(IRI.create(NS + "Animal")), sc.getSuperClass(), "super operand");
    }

    @Test
    void buildIsCaseInsensitiveOnAxiomType() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Dog", "Animal");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "SubClass_Of",
                "sub", "Dog",
                "super", "Animal"));
        assertInstanceOf(OWLSubClassOfAxiom.class, ax, "axiom_type is lower-cased before dispatch");
    }

    @Test
    void buildEquivalentClasses() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("A", "B");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "equivalent_classes",
                "classes", Arrays.asList("A", "B")));
        assertInstanceOf(OWLEquivalentClassesAxiom.class, ax);
    }

    @Test
    void buildDisjointClasses() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("A", "B", "C");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "disjoint_classes",
                "classes", Arrays.asList("A", "B", "C")));
        assertInstanceOf(OWLDisjointClassesAxiom.class, ax);
    }

    @Test
    void buildDisjointUnion() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Parent", "A", "B");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "disjoint_union",
                "class", "Parent",
                "classes", Arrays.asList("A", "B")));
        assertInstanceOf(OWLDisjointUnionAxiom.class, ax);
    }

    @Test
    void buildClassAssertion() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Dog");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "class_assertion",
                "class", "Dog",
                "individual", NS + "rex"));
        assertInstanceOf(OWLClassAssertionAxiom.class, ax);
    }

    // ================================================================== build(): assertions

    @Test
    void buildObjectPropertyAssertion() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "object_property_assertion",
                "property", NS + "owns",
                "subject", NS + "alice",
                "object", NS + "rex"));
        assertNotNull(ax, "object property assertion built");
        assertEquals("ObjectPropertyAssertion", ax.getAxiomType().getName());
    }

    @Test
    void buildDataPropertyAssertionWithPlainLiteral() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = df(mm);
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "data_property_assertion",
                "property", NS + "age",
                "subject", NS + "alice",
                "value", "42"));
        assertInstanceOf(OWLDataPropertyAssertionAxiom.class, ax);
        assertEquals(df.getOWLLiteral("42"), ((OWLDataPropertyAssertionAxiom) ax).getObject(),
                "value builds a plain literal");
    }

    @Test
    void buildNegativeObjectPropertyAssertion() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "negative_object_property_assertion",
                "property", NS + "owns",
                "subject", NS + "alice",
                "object", NS + "rex"));
        assertEquals("NegativeObjectPropertyAssertion", ax.getAxiomType().getName());
    }

    @Test
    void buildNegativeDataPropertyAssertion() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "negative_data_property_assertion",
                "property", NS + "age",
                "subject", NS + "alice",
                "value", "7"));
        assertEquals("NegativeDataPropertyAssertion", ax.getAxiomType().getName());
    }

    @Test
    void buildSameIndividual() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "same_individual",
                "individuals", Arrays.asList(NS + "a", NS + "b")));
        assertInstanceOf(OWLSameIndividualAxiom.class, ax);
    }

    @Test
    void buildDifferentIndividuals() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "different_individuals",
                "individuals", Arrays.asList(NS + "a", NS + "b", NS + "c")));
        assertEquals("DifferentIndividuals", ax.getAxiomType().getName());
    }

    // ================================================================== build(): property axioms

    @Test
    void buildSubObjectPropertyOf() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "sub_object_property_of",
                "property", NS + "p",
                "super_property", NS + "q"));
        assertInstanceOf(OWLSubObjectPropertyOfAxiom.class, ax);
    }

    @Test
    void buildSubDataPropertyOf() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "sub_data_property_of",
                "property", NS + "p",
                "super_property", NS + "q"));
        assertEquals("SubDataPropertyOf", ax.getAxiomType().getName());
    }

    @Test
    void buildSubPropertyChainOf() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "sub_property_chain_of",
                "chain", Arrays.asList(NS + "p", NS + "q"),
                "super_property", NS + "r"));
        assertInstanceOf(OWLSubPropertyChainOfAxiom.class, ax);
        assertEquals(2, ((OWLSubPropertyChainOfAxiom) ax).getPropertyChain().size(),
                "chain preserves both properties");
    }

    @Test
    void buildEquivalentObjectProperties() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "equivalent_object_properties",
                "properties", Arrays.asList(NS + "p", NS + "q")));
        assertEquals("EquivalentObjectProperties", ax.getAxiomType().getName());
    }

    @Test
    void buildDisjointObjectProperties() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "disjoint_object_properties",
                "properties", Arrays.asList(NS + "p", NS + "q")));
        assertEquals("DisjointObjectProperties", ax.getAxiomType().getName());
    }

    @Test
    void buildEquivalentDataProperties() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "equivalent_data_properties",
                "properties", Arrays.asList(NS + "p", NS + "q")));
        assertEquals("EquivalentDataProperties", ax.getAxiomType().getName());
    }

    @Test
    void buildDisjointDataProperties() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "disjoint_data_properties",
                "properties", Arrays.asList(NS + "p", NS + "q")));
        assertEquals("DisjointDataProperties", ax.getAxiomType().getName());
    }

    @Test
    void buildInverseObjectProperties() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "inverse_object_properties",
                "property", NS + "p",
                "inverse_property", NS + "q"));
        assertInstanceOf(OWLInverseObjectPropertiesAxiom.class, ax);
    }

    @Test
    void buildTransitiveObjectProperty() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "transitive_object_property",
                "property", NS + "p"));
        assertInstanceOf(OWLTransitiveObjectPropertyAxiom.class, ax);
    }

    @Test
    void buildFunctionalObjectProperty() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "functional_object_property",
                "property", NS + "p"));
        assertInstanceOf(OWLFunctionalObjectPropertyAxiom.class, ax);
    }

    @Test
    void buildRemainingObjectPropertyCharacteristics() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        Map<String, String> expected = new HashMap<>();
        expected.put("inverse_functional_object_property", "InverseFunctionalObjectProperty");
        expected.put("symmetric_object_property", "SymmetricObjectProperty");
        expected.put("asymmetric_object_property", "AsymmetricObjectProperty");
        expected.put("reflexive_object_property", "ReflexiveObjectProperty");
        // NB: the OWL API axiom-type name is spelled "IrrefexiveObjectProperty" (no second 'l').
        expected.put("irreflexive_object_property", "IrrefexiveObjectProperty");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            OWLAxiom ax = Axioms.build(mm, map("axiom_type", e.getKey(), "property", NS + "p"));
            assertEquals(e.getValue(), ax.getAxiomType().getName(),
                    e.getKey() + " maps to the right characteristic axiom");
        }
    }

    @Test
    void buildFunctionalDataProperty() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "functional_data_property",
                "property", NS + "p"));
        assertEquals("FunctionalDataProperty", ax.getAxiomType().getName());
    }

    // ================================================================== build(): has_key

    @Test
    void buildHasKeyWithDeclaredDataAndUndeclaredObjectProperty() throws OWLOntologyCreationException {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLDataProperty dp = df.getOWLDataProperty(IRI.create(NS + "ssn"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(dp));
        o.getOWLOntologyManager().addAxiom(o,
                df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "Person"))));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "has_key",
                "class", "Person",
                // "ssn" is a declared data property (resolved as data), the full IRI mints an object property
                "properties", Arrays.asList("ssn", NS + "hasId")));
        assertInstanceOf(OWLHasKeyAxiom.class, ax);
        assertEquals(2, ((OWLHasKeyAxiom) ax).getPropertyExpressions().size(),
                "both key properties are captured");
    }

    @Test
    void hasKeyWithEmptyPropertiesThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Person");
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "has_key",
                        "class", "Person",
                        "properties", new ArrayList<String>())));
        assertTrue(ex.getMessage().contains("at least one key property"),
                "empty key-property list is rejected: " + ex.getMessage());
    }

    // ================================================================== build(): domain/range

    @Test
    void buildObjectPropertyRange() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Pet");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "object_property_range",
                "property", NS + "owns",
                "range", "Pet"));
        assertInstanceOf(OWLObjectPropertyRangeAxiom.class, ax);
    }

    @Test
    void buildObjectPropertyDomain() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Person");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "object_property_domain",
                "property", NS + "owns",
                "domain", "Person"));
        assertEquals("ObjectPropertyDomain", ax.getAxiomType().getName());
    }

    @Test
    void buildDataPropertyDomain() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Person");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "data_property_domain",
                "property", NS + "age",
                "domain", "Person"));
        assertEquals("DataPropertyDomain", ax.getAxiomType().getName());
    }

    @Test
    void buildDataPropertyRangeWithDeclaredDatatype() throws OWLOntologyCreationException {
        // range resolves through resolveDataRange; a declared datatype takes the fast path
        // (Manchester data-range parsing would need the live checker, so we declare the datatype).
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLDatatype dt = df.getOWLDatatype(IRI.create(NS + "myType"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(dt));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "data_property_range",
                "property", NS + "age",
                "range", "myType"));
        assertEquals("DataPropertyRange", ax.getAxiomType().getName());
    }

    // ================================================================== build(): annotations

    @Test
    void buildAnnotationAssertionDefaultsToRdfsLabel() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = df(mm);
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "annotation_assertion",
                "subject", NS + "Dog",
                "value", "A dog"));
        assertInstanceOf(OWLAnnotationAssertionAxiom.class, ax);
        assertEquals(df.getRDFSLabel(), ((OWLAnnotationAssertionAxiom) ax).getProperty(),
                "missing property defaults to rdfs:label");
    }

    @Test
    void buildSubAnnotationPropertyOf() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "sub_annotation_property_of",
                "property", NS + "childAnn",
                "super_property", NS + "parentAnn"));
        assertEquals("SubAnnotationPropertyOf", ax.getAxiomType().getName());
    }

    @Test
    void buildAnnotationPropertyDomain() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "annotation_property_domain",
                "property", NS + "ann",
                "domain", NS + "SomeClass"));
        assertEquals("AnnotationPropertyDomain", ax.getAxiomType().getName());
    }

    @Test
    void buildAnnotationPropertyRange() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "annotation_property_range",
                "property", NS + "ann",
                "range", NS + "SomeClass"));
        assertEquals("AnnotationPropertyRangeOf", ax.getAxiomType().getName());
    }

    // ================================================================== build(): declaration

    @Test
    void buildDeclarationForEachEntityType() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        Map<String, String> byType = new LinkedHashMap<>();
        byType.put("class", "Class");
        byType.put("object_property", "ObjectProperty");
        byType.put("data_property", "DataProperty");
        byType.put("annotation_property", "AnnotationProperty");
        byType.put("individual", "NamedIndividual");
        byType.put("datatype", "Datatype");
        for (Map.Entry<String, String> e : byType.entrySet()) {
            OWLAxiom ax = Axioms.build(mm, map(
                    "axiom_type", "declaration",
                    "entity", NS + "e_" + e.getKey(),
                    "entity_type", e.getValue().toLowerCase().equals("namedindividual") ? "individual"
                            : e.getKey()));
            assertInstanceOf(OWLDeclarationAxiom.class, ax, e.getKey() + " builds a declaration");
            assertEquals(e.getValue(),
                    ((OWLDeclarationAxiom) ax).getEntity().getEntityType().getName(),
                    e.getKey() + " declares the right entity type");
        }
    }

    @Test
    void declarationEntityTypeIsCaseInsensitive() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "declaration",
                "entity", NS + "C",
                "entity_type", "CLASS"));
        assertInstanceOf(OWLDeclarationAxiom.class, ax);
    }

    @Test
    void declarationWithUnknownEntityTypeThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "declaration",
                        "entity", NS + "C",
                        "entity_type", "bogus")));
        assertTrue(ex.getMessage().contains("entity_type must be one of"),
                "unknown entity_type is rejected: " + ex.getMessage());
    }

    @Test
    void declarationMissingEntityTypeThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "declaration",
                        "entity", NS + "C")));
        assertTrue(ex.getMessage().contains("entity_type"),
                "missing entity_type surfaces: " + ex.getMessage());
    }

    // ================================================================== build(): datatype_definition

    @Test
    void buildDatatypeDefinitionWithDeclaredTypes() throws OWLOntologyCreationException {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLDatatype defined = df.getOWLDatatype(IRI.create(NS + "PositiveInt"));
        OWLDatatype rangeType = df.getOWLDatatype(IRI.create(NS + "baseType"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(defined));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(rangeType));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "datatype_definition",
                "datatype", "PositiveInt",
                "range", "baseType"));
        assertEquals("DatatypeDefinition", ax.getAxiomType().getName());
    }

    // ================================================================== build(): annotations operand

    @Test
    void buildAttachesAxiomAnnotations() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Dog", "Animal");
        List<Map<String, Object>> annotations = new ArrayList<>();
        annotations.add(map("property", "rdfs:comment", "value", "generated"));
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "subclass_of",
                "sub", "Dog",
                "super", "Animal",
                "annotations", annotations));
        assertTrue(ax.isAnnotated(), "axiom carries the supplied annotation");
        assertEquals(1, ax.getAnnotations().size(), "exactly one annotation attached");
    }

    @Test
    void buildWithoutAnnotationsIsNotAnnotated() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Dog", "Animal");
        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "subclass_of",
                "sub", "Dog",
                "super", "Animal"));
        assertFalse(ax.isAnnotated(), "no annotations operand means a bare axiom");
    }

    // ================================================================== build(): error branches

    @Test
    void buildWithUnsupportedAxiomTypeThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map("axiom_type", "not_a_real_type")));
        assertTrue(ex.getMessage().contains("Unsupported axiom_type 'not_a_real_type'"),
                "unsupported type is reported: " + ex.getMessage());
    }

    @Test
    void buildWithMissingAxiomTypeThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map("sub", NS + "Dog", "super", NS + "Animal")));
        assertTrue(ex.getMessage().contains("axiom_type"),
                "missing axiom_type surfaces: " + ex.getMessage());
    }

    @Test
    void buildSubclassMissingSuperThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = managerWithClasses("Dog");
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "subclass_of",
                        "sub", "Dog")));
        assertTrue(ex.getMessage().contains("super"),
                "missing 'super' operand surfaces: " + ex.getMessage());
    }

    @Test
    void equivalentClassesWithSingleClassThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "equivalent_classes",
                        "classes", Arrays.asList(NS + "OnlyOne"))));
        assertTrue(ex.getMessage().contains("at least two classes"),
                "single-class equivalent set rejected: " + ex.getMessage());
    }

    @Test
    void sameIndividualWithSingleIndividualThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "same_individual",
                        "individuals", Arrays.asList(NS + "solo"))));
        assertTrue(ex.getMessage().contains("at least two individuals"),
                "single-individual set rejected: " + ex.getMessage());
    }

    @Test
    void objectPropertySetWithSinglePropertyThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "equivalent_object_properties",
                        "properties", Arrays.asList(NS + "onlyP"))));
        assertTrue(ex.getMessage().contains("at least two object properties"),
                "single object property set rejected: " + ex.getMessage());
    }

    @Test
    void dataPropertySetWithSinglePropertyThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "disjoint_data_properties",
                        "properties", Arrays.asList(NS + "onlyP"))));
        assertTrue(ex.getMessage().contains("at least two data properties"),
                "single data property set rejected: " + ex.getMessage());
    }

    @Test
    void propertyChainWithSinglePropertyThrows() throws OWLOntologyCreationException {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "sub_property_chain_of",
                        "chain", Arrays.asList(NS + "p"),
                        "super_property", NS + "r")));
        assertTrue(ex.getMessage().contains("at least two object properties"),
                "single-element chain rejected: " + ex.getMessage());
    }

    @Test
    void resolvingWrongEntityTypeThrows() throws OWLOntologyCreationException {
        // "Dog" is declared as a class; requesting it as an object property must fail.
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(dog));
        OWLModelManager mm = FakeModelManager.over(o);

        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "transitive_object_property",
                        "property", "Dog")));
        assertTrue(ex.getMessage().contains("not a object property"),
                "type mismatch reported: " + ex.getMessage());
    }

    @Test
    void resolvingUnknownBareNameThrows() throws OWLOntologyCreationException {
        // A bare name (not an IRI) that is not declared cannot be minted.
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.build(mm, map(
                        "axiom_type", "transitive_object_property",
                        "property", "Undeclared")));
        assertTrue(ex.getMessage().contains("not found"),
                "an unknown bare name is rejected: " + ex.getMessage());
    }

    // ================================================================== declared-name resolution

    @Test
    void buildResolvesDeclaredEntitiesByShortName() throws OWLOntologyCreationException {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(dog));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(animal));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLAxiom ax = Axioms.build(mm, map(
                "axiom_type", "subclass_of",
                "sub", "Dog",
                "super", "Animal"));
        assertEquals(df.getOWLSubClassOfAxiom(dog, animal), ax,
                "short names resolve to the declared classes");
    }

    // ================================================================== literal()

    @Test
    void literalPlainFromValueOnly() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLDataFactory df = df(mm);
        OWLLiteral lit = Axioms.literal(mm, map("value", "hello"));
        assertEquals(df.getOWLLiteral("hello"), lit, "plain literal from bare value");
        assertFalse(lit.hasLang(), "no language tag on a plain literal");
    }

    @Test
    void literalWithLanguageTag() {
        OWLModelManager mm = FakeModelManager.empty();
        OWLLiteral lit = Axioms.literal(mm, map("value", "chien", "lang", "fr"));
        assertTrue(lit.hasLang(), "language-tagged literal");
        assertEquals("fr", lit.getLang(), "language tag preserved");
        assertEquals("chien", lit.getLiteral(), "lexical value preserved");
    }

    @Test
    void literalWithDeclaredDatatype() throws OWLOntologyCreationException {
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLDatatype dt = df.getOWLDatatype(IRI.create(NS + "myType"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(dt));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLLiteral lit = Axioms.literal(mm, map("value", "42", "datatype", "myType"));
        assertEquals(dt, lit.getDatatype(), "typed literal carries the declared datatype");
        assertEquals("42", lit.getLiteral(), "lexical value preserved");
    }

    @Test
    void literalPrefersDatatypeOverLang() throws OWLOntologyCreationException {
        // When both datatype and lang are supplied, the datatype branch wins (checked first).
        OWLOntology o = emptyOntology();
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLDatatype dt = df.getOWLDatatype(IRI.create(NS + "myType"));
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(dt));
        OWLModelManager mm = FakeModelManager.over(o);

        OWLLiteral lit = Axioms.literal(mm, map("value", "42", "datatype", "myType", "lang", "en"));
        assertFalse(lit.hasLang(), "datatype takes precedence, so no language tag");
        assertEquals(dt, lit.getDatatype());
    }

    @Test
    void literalMissingValueThrows() {
        OWLModelManager mm = FakeModelManager.empty();
        ToolArgException ex = assertThrows(ToolArgException.class,
                () -> Axioms.literal(mm, map("lang", "en")));
        assertTrue(ex.getMessage().contains("value"),
                "missing 'value' is reported: " + ex.getMessage());
    }

    // ================================================================== determinism / uniqueness

    @Test
    void buildIsDeterministicAcrossCalls() throws OWLOntologyCreationException {
        OWLModelManager mm1 = managerWithClasses("Dog", "Animal");
        OWLModelManager mm2 = managerWithClasses("Dog", "Animal");
        OWLAxiom a1 = Axioms.build(mm1, map(
                "axiom_type", "subclass_of", "sub", "Dog", "super", "Animal"));
        OWLAxiom a2 = Axioms.build(mm2, map(
                "axiom_type", "subclass_of", "sub", "Dog", "super", "Animal"));
        assertEquals(a1, a2, "identical operands over distinct managers build equal axioms");
        assertNotSame(a1, a2, "but they are distinct instances");
    }
}
