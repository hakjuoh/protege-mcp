package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLPropertyExpression;

/**
 * Builds an {@link OWLAxiom} from a structured {@code axiom_type} + operand arguments. Used by both
 * {@code add_axiom} (wrap in {@code AddAxiom}) and {@code remove_axiom} (wrap in {@code RemoveAxiom})
 * so the two are exactly symmetric. Entity operands are IRIs or display names; class operands may
 * additionally be Manchester-syntax class expressions (e.g. {@code Animal and (hasOwner some Person)}).
 *
 * <p>Every axiom may carry an optional {@code annotations} operand (an array of
 * {@code {property, value | value_iri, lang, datatype}}); these become axiom annotations via
 * {@link OWLAxiom#getAnnotatedAxiom}, so reified {@code owl:Axiom} blocks are reconstructable.
 */
public final class Axioms {

    private Axioms() {
    }

    public static final String SUPPORTED =
            "subclass_of, equivalent_classes, disjoint_classes, disjoint_union, class_assertion, "
                    + "object_property_assertion, data_property_assertion, "
                    + "negative_object_property_assertion, negative_data_property_assertion, "
                    + "same_individual, different_individuals, "
                    + "sub_object_property_of, sub_data_property_of, sub_property_chain_of, "
                    + "equivalent_object_properties, equivalent_data_properties, "
                    + "disjoint_object_properties, disjoint_data_properties, "
                    + "inverse_object_properties, transitive_object_property, "
                    + "functional_object_property, inverse_functional_object_property, "
                    + "symmetric_object_property, asymmetric_object_property, "
                    + "reflexive_object_property, irreflexive_object_property, "
                    + "functional_data_property, has_key, "
                    + "object_property_domain, object_property_range, "
                    + "data_property_domain, data_property_range, "
                    + "annotation_assertion, sub_annotation_property_of, "
                    + "annotation_property_domain, annotation_property_range, "
                    + "declaration, datatype_definition";

    public static OWLAxiom build(OWLModelManager mm, Map<String, Object> a) {
        OWLAxiom base = buildBase(mm, a);
        Set<OWLAnnotation> annotations = Tools.annotationSet(mm, a, "annotations");
        return annotations.isEmpty() ? base : base.getAnnotatedAxiom(annotations);
    }

    private static OWLAxiom buildBase(OWLModelManager mm, Map<String, Object> a) {
        OWLDataFactory df = mm.getOWLDataFactory();
        String type = Tools.reqString(a, "axiom_type").toLowerCase();
        switch (type) {
            case "subclass_of":
                return df.getOWLSubClassOfAxiom(
                        Tools.resolveClassExpression(mm, Tools.reqString(a, "sub")),
                        Tools.resolveClassExpression(mm, Tools.reqString(a, "super")));
            case "equivalent_classes":
                return df.getOWLEquivalentClassesAxiom(classSet(mm, a));
            case "disjoint_classes":
                return df.getOWLDisjointClassesAxiom(classSet(mm, a));
            case "disjoint_union":
                return df.getOWLDisjointUnionAxiom(
                        Tools.resolveClass(mm, Tools.reqString(a, "class")), classSet(mm, a));
            case "class_assertion":
                return df.getOWLClassAssertionAxiom(
                        Tools.resolveClassExpression(mm, Tools.reqString(a, "class")),
                        Tools.resolveIndividual(mm, Tools.reqString(a, "individual")));
            case "object_property_assertion":
                return df.getOWLObjectPropertyAssertionAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveIndividual(mm, Tools.reqString(a, "subject")),
                        Tools.resolveIndividual(mm, Tools.reqString(a, "object")));
            case "data_property_assertion":
                return df.getOWLDataPropertyAssertionAxiom(
                        Tools.resolveDataProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveIndividual(mm, Tools.reqString(a, "subject")),
                        literal(mm, a));
            case "negative_object_property_assertion":
                return df.getOWLNegativeObjectPropertyAssertionAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveIndividual(mm, Tools.reqString(a, "subject")),
                        Tools.resolveIndividual(mm, Tools.reqString(a, "object")));
            case "negative_data_property_assertion":
                return df.getOWLNegativeDataPropertyAssertionAxiom(
                        Tools.resolveDataProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveIndividual(mm, Tools.reqString(a, "subject")),
                        literal(mm, a));
            case "same_individual":
                return df.getOWLSameIndividualAxiom(individualSet(mm, a));
            case "different_individuals":
                return df.getOWLDifferentIndividualsAxiom(individualSet(mm, a));
            case "sub_object_property_of":
                return df.getOWLSubObjectPropertyOfAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "super_property")));
            case "sub_data_property_of":
                return df.getOWLSubDataPropertyOfAxiom(
                        Tools.resolveDataProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveDataProperty(mm, Tools.reqString(a, "super_property")));
            case "sub_property_chain_of":
                return df.getOWLSubPropertyChainOfAxiom(
                        objectPropertyChain(mm, a),
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "super_property")));
            case "equivalent_object_properties":
                return df.getOWLEquivalentObjectPropertiesAxiom(objectPropertySet(mm, a));
            case "disjoint_object_properties":
                return df.getOWLDisjointObjectPropertiesAxiom(objectPropertySet(mm, a));
            case "equivalent_data_properties":
                return df.getOWLEquivalentDataPropertiesAxiom(dataPropertySet(mm, a));
            case "disjoint_data_properties":
                return df.getOWLDisjointDataPropertiesAxiom(dataPropertySet(mm, a));
            case "inverse_object_properties":
                return df.getOWLInverseObjectPropertiesAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "inverse_property")));
            case "transitive_object_property":
                return df.getOWLTransitiveObjectPropertyAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")));
            case "functional_object_property":
                return df.getOWLFunctionalObjectPropertyAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")));
            case "inverse_functional_object_property":
                return df.getOWLInverseFunctionalObjectPropertyAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")));
            case "symmetric_object_property":
                return df.getOWLSymmetricObjectPropertyAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")));
            case "asymmetric_object_property":
                return df.getOWLAsymmetricObjectPropertyAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")));
            case "reflexive_object_property":
                return df.getOWLReflexiveObjectPropertyAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")));
            case "irreflexive_object_property":
                return df.getOWLIrreflexiveObjectPropertyAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")));
            case "functional_data_property":
                return df.getOWLFunctionalDataPropertyAxiom(
                        Tools.resolveDataProperty(mm, Tools.reqString(a, "property")));
            case "has_key":
                return df.getOWLHasKeyAxiom(
                        Tools.resolveClassExpression(mm, Tools.reqString(a, "class")),
                        propertyExpressionSet(mm, a));
            case "object_property_domain":
                return df.getOWLObjectPropertyDomainAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveClassExpression(mm, Tools.reqString(a, "domain")));
            case "object_property_range":
                return df.getOWLObjectPropertyRangeAxiom(
                        Tools.resolveObjectProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveClassExpression(mm, Tools.reqString(a, "range")));
            case "data_property_domain":
                return df.getOWLDataPropertyDomainAxiom(
                        Tools.resolveDataProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveClassExpression(mm, Tools.reqString(a, "domain")));
            case "data_property_range":
                return df.getOWLDataPropertyRangeAxiom(
                        Tools.resolveDataProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveDataRange(mm, Tools.reqString(a, "range")));
            case "annotation_assertion":
                return df.getOWLAnnotationAssertionAxiom(
                        Tools.annotationProperty(mm, Tools.optString(a, "property")),
                        Tools.annotationSubject(mm, Tools.reqString(a, "subject")),
                        Tools.annotationValue(mm, a));
            case "sub_annotation_property_of":
                return df.getOWLSubAnnotationPropertyOfAxiom(
                        Tools.resolveAnnotationProperty(mm, Tools.reqString(a, "property")),
                        Tools.resolveAnnotationProperty(mm, Tools.reqString(a, "super_property")));
            case "annotation_property_domain":
                return df.getOWLAnnotationPropertyDomainAxiom(
                        Tools.resolveAnnotationProperty(mm, Tools.reqString(a, "property")),
                        Tools.iriRef(mm, Tools.reqString(a, "domain")));
            case "annotation_property_range":
                return df.getOWLAnnotationPropertyRangeAxiom(
                        Tools.resolveAnnotationProperty(mm, Tools.reqString(a, "property")),
                        Tools.iriRef(mm, Tools.reqString(a, "range")));
            case "declaration":
                return df.getOWLDeclarationAxiom(declarationEntity(mm, a));
            case "datatype_definition":
                return df.getOWLDatatypeDefinitionAxiom(
                        Tools.resolveDatatype(mm, Tools.reqString(a, "datatype")),
                        Tools.resolveDataRange(mm, Tools.reqString(a, "range")));
            default:
                throw new ToolArgException("Unsupported axiom_type '" + type + "'. Supported: " + SUPPORTED + ".");
        }
    }

    private static OWLEntity declarationEntity(OWLModelManager mm, Map<String, Object> a) {
        String ref = Tools.reqString(a, "entity");
        String entityType = Tools.reqString(a, "entity_type").toLowerCase();
        switch (entityType) {
            case "class":
                return Tools.resolveClass(mm, ref);
            case "object_property":
                return Tools.resolveObjectProperty(mm, ref);
            case "data_property":
                return Tools.resolveDataProperty(mm, ref);
            case "annotation_property":
                return Tools.resolveAnnotationProperty(mm, ref);
            case "individual":
                return Tools.resolveIndividual(mm, ref);
            case "datatype":
                return Tools.resolveDatatype(mm, ref);
            default:
                throw new ToolArgException("declaration: entity_type must be one of class, "
                        + "object_property, data_property, annotation_property, individual, datatype.");
        }
    }

    private static List<OWLObjectPropertyExpression> objectPropertyChain(OWLModelManager mm,
            Map<String, Object> a) {
        List<String> refs = Tools.stringList(a, "chain");
        if (refs.size() < 2) {
            throw new ToolArgException("'chain' must list at least two object properties.");
        }
        List<OWLObjectPropertyExpression> chain = new ArrayList<>();
        for (String ref : refs) {
            chain.add(chainLink(mm, ref));
        }
        return chain;
    }

    /** A chain link: a named object property, or an inverse expression inverse(P) / "inverse P" /
     * ObjectInverseOf(P) → OWLObjectInverseOf (a SubPropertyChainOf link may be any object property
     * expression, so inverse links are legal — some ontologies' temporal chains rely on them). */
    private static OWLObjectPropertyExpression chainLink(OWLModelManager mm, String ref) {
        String inner = inverseOperand(ref);
        if (inner != null) {
            return mm.getOWLDataFactory().getOWLObjectInverseOf(Tools.resolveObjectProperty(mm, inner));
        }
        return Tools.resolveObjectProperty(mm, ref);
    }

    /** If {@code ref} is inverse(X) / "inverse X" / ObjectInverseOf(X), return X (trimmed); else null. */
    private static String inverseOperand(String ref) {
        String s = ref.trim();
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("inverse(") && s.endsWith(")")) {
            return s.substring("inverse(".length(), s.length() - 1).trim();
        }
        if (lower.startsWith("objectinverseof(") && s.endsWith(")")) {
            return s.substring("objectinverseof(".length(), s.length() - 1).trim();
        }
        if (lower.startsWith("inverse ")) {
            return s.substring("inverse ".length()).trim();
        }
        return null;
    }

    private static Set<OWLClassExpression> classSet(OWLModelManager mm, Map<String, Object> a) {
        List<String> refs = Tools.stringList(a, "classes");
        if (refs.size() < 2) {
            throw new ToolArgException("'classes' must list at least two classes (names, IRIs or "
                    + "Manchester-syntax class expressions).");
        }
        Set<OWLClassExpression> set = new LinkedHashSet<>();
        for (String ref : refs) {
            set.add(Tools.resolveClassExpression(mm, ref));
        }
        return set;
    }

    private static Set<OWLIndividual> individualSet(OWLModelManager mm, Map<String, Object> a) {
        List<String> refs = Tools.stringList(a, "individuals");
        if (refs.size() < 2) {
            throw new ToolArgException("'individuals' must list at least two individuals (names or IRIs).");
        }
        Set<OWLIndividual> set = new LinkedHashSet<>();
        for (String ref : refs) {
            set.add(Tools.resolveIndividual(mm, ref));
        }
        return set;
    }

    private static Set<OWLObjectPropertyExpression> objectPropertySet(OWLModelManager mm,
            Map<String, Object> a) {
        List<String> refs = Tools.stringList(a, "properties");
        if (refs.size() < 2) {
            throw new ToolArgException("'properties' must list at least two object properties.");
        }
        Set<OWLObjectPropertyExpression> set = new LinkedHashSet<>();
        for (String ref : refs) {
            set.add(Tools.resolveObjectProperty(mm, ref));
        }
        return set;
    }

    private static Set<OWLDataPropertyExpression> dataPropertySet(OWLModelManager mm,
            Map<String, Object> a) {
        List<String> refs = Tools.stringList(a, "properties");
        if (refs.size() < 2) {
            throw new ToolArgException("'properties' must list at least two data properties.");
        }
        Set<OWLDataPropertyExpression> set = new LinkedHashSet<>();
        for (String ref : refs) {
            set.add(Tools.resolveDataProperty(mm, ref));
        }
        return set;
    }

    /** has_key key properties: each resolved as an object property, falling back to a data property. */
    private static Set<OWLPropertyExpression> propertyExpressionSet(OWLModelManager mm,
            Map<String, Object> a) {
        List<String> refs = Tools.stringList(a, "properties");
        if (refs.isEmpty()) {
            throw new ToolArgException("'properties' must list at least one key property.");
        }
        Set<OWLPropertyExpression> set = new LinkedHashSet<>();
        for (String ref : refs) {
            set.add(resolveObjectOrDataProperty(mm, ref));
        }
        return set;
    }

    private static OWLPropertyExpression resolveObjectOrDataProperty(OWLModelManager mm, String ref) {
        OWLEntity e = Tools.findEntity(mm, ref);
        if (e instanceof OWLDataPropertyExpression) {
            return (OWLPropertyExpression) e;
        }
        // Default to object property (and let it mint from an IRI when not yet declared).
        return Tools.resolveObjectProperty(mm, ref);
    }

    /** Build a literal from {@code value} + optional {@code lang} / {@code datatype}. */
    public static OWLLiteral literal(OWLModelManager mm, Map<String, Object> a) {
        OWLDataFactory df = mm.getOWLDataFactory();
        String value = Tools.reqString(a, "value");
        String datatype = Tools.optString(a, "datatype");
        String lang = Tools.optString(a, "lang");
        if (datatype != null) {
            return df.getOWLLiteral(value, Tools.resolveDatatype(mm, datatype));
        }
        if (lang != null) {
            return df.getOWLLiteral(value, lang);
        }
        return df.getOWLLiteral(value);
    }
}
