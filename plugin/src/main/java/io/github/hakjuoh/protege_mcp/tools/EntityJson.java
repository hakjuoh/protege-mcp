package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

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
        List<OWLEntity> sorted = sortedEntities(mm, entities);
        int max = Math.max(0, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < max; i++) {
            items.add(entityJson(mm, sorted.get(i)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", entities.size());
        m.put("items", items);
        if (entities.size() > items.size()) {
            m.put("truncated", entities.size() - items.size());
        }
        return m;
    }

    /** A capped, sorted list of axioms as {@code {count, items:[axiomJson...], truncated?}}. */
    public static Map<String, Object> axiomList(OWLModelManager mm,
            Collection<? extends OWLAxiom> axioms, int limit) {
        List<OWLAxiom> sorted = sortedAxioms(mm, axioms);
        int max = Math.max(0, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < max; i++) {
            items.add(axiomJson(mm, sorted.get(i)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", axioms.size());
        m.put("items", items);
        if (axioms.size() > items.size()) {
            m.put("truncated", axioms.size() - items.size());
        }
        return m;
    }

    /**
     * A paginated, display-sorted window of entities as
     * {@code {count, offset, returned, items:[entityJson...], next_offset?}}. {@code count} is the full
     * size; {@code offset} is where this page starts; {@code next_offset} (when present) is the offset to
     * pass to fetch the following page. The sort is stable (display then IRI), so paging never drops or
     * repeats an entity across page boundaries.
     */
    public static Map<String, Object> entityPage(OWLModelManager mm,
            Collection<? extends OWLEntity> entities, int offset, int limit) {
        return page(sortedEntities(mm, entities), offset, limit, e -> entityJson(mm, e));
    }

    /**
     * A paginated, sorted window of axioms as
     * {@code {count, offset, returned, items:[axiomJson...], next_offset?}}. The sort (rendering, then the
     * axiom's own natural order as a stable tiebreak) is total, so two axioms that render identically keep
     * a fixed relative order and paging never drops or repeats one across page boundaries.
     */
    public static Map<String, Object> axiomPage(OWLModelManager mm,
            Collection<? extends OWLAxiom> axioms, int offset, int limit) {
        return page(sortedAxioms(mm, axioms), offset, limit, ax -> axiomJson(mm, ax));
    }

    /** The display-then-IRI sort shared by {@link #entityList} and {@link #entityPage} (fully stable). */
    private static List<OWLEntity> sortedEntities(OWLModelManager mm,
            Collection<? extends OWLEntity> entities) {
        List<OWLEntity> sorted = new ArrayList<>(entities);
        // Locale.ROOT so the case-fold (hence the page order) is locale-independent — a Turkish-locale
        // JVM must not reorder pages (matches EntitySearch's ranking tiebreak).
        sorted.sort(Comparator.comparing((OWLEntity e) -> mm.getRendering(e).toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.getIRI().toString()));
        return sorted;
    }

    /**
     * The rendering-then-natural-order sort shared by {@link #axiomList} and {@link #axiomPage}. Each axiom
     * is rendered exactly once (into a lookup map) rather than on every comparison, and the axiom's own
     * {@link OWLAxiom natural order} breaks ties so equal-rendering axioms keep a fixed, total order.
     */
    private static List<OWLAxiom> sortedAxioms(OWLModelManager mm,
            Collection<? extends OWLAxiom> axioms) {
        List<OWLAxiom> sorted = new ArrayList<>(axioms);
        Map<OWLAxiom, String> rendering = new HashMap<>();
        for (OWLAxiom ax : sorted) {
            rendering.put(ax, EntityRendering.renderAxiom(mm, ax));
        }
        sorted.sort(Comparator.comparing((OWLAxiom ax) -> rendering.get(ax))
                .thenComparing(Comparator.<OWLAxiom>naturalOrder()));
        return sorted;
    }

    /**
     * Window {@code sorted} to {@code [offset, offset+limit)} and render each shown element with
     * {@code toJson}, returning {@code {count, offset, returned, items, next_offset?}}. A negative offset
     * or limit is clamped to 0; an offset past the end yields an empty page (with the true {@code count}).
     */
    private static <T> Map<String, Object> page(List<T> sorted, int offset, int limit,
            Function<T, Map<String, Object>> toJson) {
        int total = sorted.size();
        int off = Math.min(Math.max(0, offset), total);
        int max = Math.max(0, limit);
        // Compute the window end in long arithmetic so a near-Integer.MAX_VALUE limit ("return
        // everything") cannot overflow off+max into a negative end that drops every item.
        int end = (int) Math.min((long) total, (long) off + max);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = off; i < end; i++) {
            items.add(toJson.apply(sorted.get(i)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", total);
        m.put("offset", off);
        m.put("returned", items.size());
        m.put("items", items);
        // Only advertise a next page when THIS page made forward progress — otherwise a limit<=0 (or an
        // overflowed window) would emit next_offset == off, a self-referential cursor a pager loops on.
        if (!items.isEmpty() && off + items.size() < total) {
            m.put("next_offset", off + items.size());
        }
        return m;
    }
}
