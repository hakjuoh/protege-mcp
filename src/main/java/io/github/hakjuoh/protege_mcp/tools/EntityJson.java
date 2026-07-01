package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;

/**
 * The canonical JSON shapes for entities, axioms and annotations, plus capped/sorted list wrappers. These
 * maps are the MCP wire output, so key order (via {@link LinkedHashMap}) is behavioral. Split out of
 * {@link Tools} as a focused unit; {@code Tools} keeps thin delegators for source compatibility.
 */
public final class EntityJson {

    private EntityJson() {
    }

    /** The standard JSON shape for an entity: {@code {iri, display, type}}. */
    public static Map<String, Object> entityJson(OWLModelManager mm, OWLEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iri", e.getIRI().toString());
        m.put("display", mm.getRendering(e));
        m.put("type", e.getEntityType().getName());
        return m;
    }

    /** The standard JSON shape for an axiom: {@code {axiom_type, rendering}}. */
    public static Map<String, Object> axiomJson(OWLModelManager mm, OWLAxiom ax) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("axiom_type", ax.getAxiomType().getName());
        m.put("rendering", EntityRendering.renderAxiom(mm, ax));
        return m;
    }

    /**
     * The canonical JSON shape for an annotation, shared by every annotation-producing tool (read and
     * write) so they never drift: {@code {property, property_iri, value | value_iri, lang?, datatype?}}.
     */
    public static Map<String, Object> annotationJson(OWLModelManager mm, OWLAnnotation ann) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("property", mm.getRendering(ann.getProperty()));
        m.put("property_iri", ann.getProperty().getIRI().toString());
        if (ann.getValue().asLiteral().isPresent()) {
            OWLLiteral lit = ann.getValue().asLiteral().get();
            m.put("value", lit.getLiteral());
            if (lit.hasLang()) {
                m.put("lang", lit.getLang());
            } else if (!lit.isRDFPlainLiteral() && !lit.getDatatype().isString()) {
                // xsd:string is the implicit type of a plain string in RDF 1.1 (and OWL API treats
                // the two as equal), so reporting it would be noise that also makes a tool-built label
                // look different from the same label parsed from a document. Only surface a genuinely
                // non-string datatype (e.g. xsd:integer, xsd:dateTime).
                m.put("datatype", lit.getDatatype().getIRI().toString());
            }
        } else {
            m.put("value_iri", ann.getValue().toString());
        }
        return m;
    }

    /**
     * A capped, display-sorted list of entities as {@code {count, items:[entityJson...], truncated?}}.
     * {@code count} is the full size; {@code truncated} (when present) is how many were omitted.
     */
    public static Map<String, Object> entityList(OWLModelManager mm,
            Collection<? extends OWLEntity> entities, int limit) {
        List<OWLEntity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparing((OWLEntity e) -> mm.getRendering(e).toLowerCase())
                .thenComparing(e -> e.getIRI().toString()));
        int max = Math.max(0, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        int shown = 0;
        for (OWLEntity e : sorted) {
            if (shown >= max) {
                break;
            }
            items.add(entityJson(mm, e));
            shown++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", entities.size());
        m.put("items", items);
        if (entities.size() > shown) {
            m.put("truncated", entities.size() - shown);
        }
        return m;
    }

    /** A capped, sorted list of axioms as {@code {count, items:[axiomJson...], truncated?}}. */
    public static Map<String, Object> axiomList(OWLModelManager mm,
            Collection<? extends OWLAxiom> axioms, int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (OWLAxiom ax : axioms) {
            all.add(axiomJson(mm, ax));
        }
        all.sort(Comparator.comparing(a -> String.valueOf(a.get("rendering"))));
        int max = Math.max(0, limit);
        List<Map<String, Object>> items = all.size() > max ? all.subList(0, max) : all;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", axioms.size());
        m.put("items", new ArrayList<>(items));
        if (axioms.size() > items.size()) {
            m.put("truncated", axioms.size() - items.size());
        }
        return m;
    }
}
