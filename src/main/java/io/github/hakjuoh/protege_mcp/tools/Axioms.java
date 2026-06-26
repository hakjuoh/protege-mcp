package io.github.hakjuoh.protege_mcp.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;

/**
 * Builds an {@link OWLAxiom} from a structured {@code axiom_type} + operand arguments. Used by both
 * {@code add_axiom} (wrap in {@code AddAxiom}) and {@code remove_axiom} (wrap in {@code RemoveAxiom})
 * so the two are exactly symmetric. Entity operands are IRIs or display names; class operands may
 * additionally be Manchester-syntax class expressions (e.g. {@code Animal and (hasOwner some Person)}).
 */
public final class Axioms {

    private Axioms() {
    }

    public static final String SUPPORTED =
            "subclass_of, equivalent_classes, disjoint_classes, class_assertion, "
                    + "object_property_assertion, data_property_assertion, "
                    + "object_property_domain, object_property_range, "
                    + "data_property_domain, data_property_range";

    public static OWLAxiom build(OWLModelManager mm, Map<String, Object> a) {
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
                        Tools.resolveDatatype(mm, Tools.reqString(a, "range")));
            default:
                throw new ToolArgException("Unsupported axiom_type '" + type + "'. Supported: " + SUPPORTED + ".");
        }
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
