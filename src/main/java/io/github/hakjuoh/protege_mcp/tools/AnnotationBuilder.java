package io.github.hakjuoh.protege_mcp.tools;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;

/**
 * Builds OWL annotations from tool operands (subject/property/value maps). Depends on
 * {@link EntityResolver} (subject/property/datatype resolution) and {@link ToolArgs} (operand reading).
 * Split out of {@link Tools} as a focused unit; {@code Tools} keeps thin delegators.
 */
public final class AnnotationBuilder {

    private AnnotationBuilder() {
    }

    /** Resolve the subject of an annotation assertion: an existing entity's IRI, or an absolute IRI. */
    public static IRI annotationSubject(OWLModelManager mm, String ref) {
        OWLEntity e = EntityResolver.findEntity(mm, ref);
        if (e != null) {
            return e.getIRI();
        }
        IRI iri = EntityResolver.asIri(ref);
        if (iri != null) {
            return iri;
        }
        throw new ToolArgException("Entity not found: '" + ref + "'. Pass a full IRI to annotate it.");
    }

    /** Resolve an annotation property; {@code null}/'rdfs:label' → label, 'rdfs:comment' → comment. */
    public static OWLAnnotationProperty annotationProperty(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        if (ref == null || "rdfs:label".equalsIgnoreCase(ref) || "label".equalsIgnoreCase(ref)) {
            return df.getRDFSLabel();
        }
        if ("rdfs:comment".equalsIgnoreCase(ref) || "comment".equalsIgnoreCase(ref)) {
            return df.getRDFSComment();
        }
        return EntityResolver.resolveAnnotationProperty(mm, ref);
    }

    /**
     * Build an annotation value from {@code a}: {@code value_iri} → an IRI value; otherwise
     * {@code value} as a literal, typed by {@code datatype} or tagged by {@code lang} if present.
     */
    public static OWLAnnotationValue annotationValue(OWLModelManager mm, Map<String, Object> a) {
        OWLDataFactory df = mm.getOWLDataFactory();
        String iriValue = ToolArgs.optString(a, "value_iri");
        if (iriValue != null) {
            return EntityResolver.iriRef(mm, iriValue);
        }
        String value = ToolArgs.reqString(a, "value");
        String datatype = ToolArgs.optString(a, "datatype");
        String lang = ToolArgs.optString(a, "lang");
        if (datatype != null) {
            return df.getOWLLiteral(value, EntityResolver.resolveDatatype(mm, datatype));
        }
        if (lang != null) {
            return df.getOWLLiteral(value, lang);
        }
        return df.getOWLLiteral(value);
    }

    /** Build a single annotation from a map {@code {property, value | value_iri, lang, datatype}}. */
    public static OWLAnnotation buildAnnotation(OWLModelManager mm, Map<String, Object> a) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return df.getOWLAnnotation(annotationProperty(mm, ToolArgs.optString(a, "property")),
                annotationValue(mm, a));
    }

    /** Build the (possibly empty) set of axiom/ontology annotations from the {@code key} array operand. */
    public static Set<OWLAnnotation> annotationSet(OWLModelManager mm, Map<String, Object> a, String key) {
        Set<OWLAnnotation> set = new LinkedHashSet<>();
        for (Map<String, Object> item : ToolArgs.objList(a, key)) {
            set.add(buildAnnotation(mm, item));
        }
        return set;
    }
}
