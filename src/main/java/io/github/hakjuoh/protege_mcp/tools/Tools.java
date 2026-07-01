package io.github.hakjuoh.protege_mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Shared helpers for the tool handlers: argument extraction, result/error construction, a small
 * JSON-schema builder, exception-to-error guarding, and entity/IRI resolution + rendering.
 */
public final class Tools {

    private Tools() {
    }

    // ------------------------------------------------------------------ results (structured JSON)

    /** Build a {@link CallToolResult} from a result object (success). */
    public static CallToolResult ok(Map<String, Object> data) {
        return ToolResults.ok(data);
    }

    /**
     * A plain confirmation/message result: {@code {"message": s}}. Kept so trivial confirmations stay
     * one-liners and any not-yet-restructured handler still emits valid JSON.
     */
    public static CallToolResult text(String s) {
        return ToolResults.text(s);
    }

    /** An error result: {@code {"error": message}} with {@code isError=true}. */
    public static CallToolResult error(String message) {
        return ToolResults.error(message);
    }

    /** Serialize a result object to pretty JSON; never throws (falls back to {@code toString}). */
    public static String serialize(Object data) {
        return ToolResults.serialize(data);
    }

    /** A small fluent builder for an ordered JSON object result. */
    public static Json json() {
        return new Json();
    }

    /** Fluent builder over a {@link LinkedHashMap} (stable key order) that yields a result. */
    public static final class Json {
        private final Map<String, Object> m = new LinkedHashMap<>();

        /** Put {@code k=v} (a null {@code v} becomes JSON {@code null}). */
        public Json put(String k, Object v) {
            m.put(k, v);
            return this;
        }

        /** Put {@code k=v} only when {@code v} is non-null. */
        public Json putIfNotNull(String k, Object v) {
            if (v != null) {
                m.put(k, v);
            }
            return this;
        }

        public Map<String, Object> map() {
            return m;
        }

        public CallToolResult result() {
            return ok(m);
        }
    }

    /** Run a handler body, converting expected exceptions into a non-fatal MCP error result. */
    public static CallToolResult guard(Supplier<CallToolResult> body) {
        return ToolResults.guard(body);
    }

    // ------------------------------------------------------------------ arguments

    public static Map<String, Object> args(CallToolRequest req) {
        return ToolArgs.args(req);
    }

    public static String optString(Map<String, Object> a, String key) {
        return ToolArgs.optString(a, key);
    }

    public static String reqString(Map<String, Object> a, String key) {
        return ToolArgs.reqString(a, key);
    }

    public static int optInt(Map<String, Object> a, String key, int def) {
        return ToolArgs.optInt(a, key, def);
    }

    public static boolean optBool(Map<String, Object> a, String key, boolean def) {
        return ToolArgs.optBool(a, key, def);
    }

    public static List<String> stringList(Map<String, Object> a, String key) {
        return ToolArgs.stringList(a, key);
    }

    /** Read {@code key} as a list of object maps (e.g. the {@code annotations} operand). */
    public static List<Map<String, Object>> objList(Map<String, Object> a, String key) {
        return ToolArgs.objList(a, key);
    }

    // ------------------------------------------------------------------ schema builder

    public static SchemaBuilder schema() {
        return new SchemaBuilder();
    }

    public static Map<String, Object> emptySchema() {
        return ToolSchemas.emptySchema();
    }

    /** A bare {@code {type:string, description}} property map (for hand-assembled schemas). */
    public static Map<String, Object> stringProperty(String desc) {
        return ToolSchemas.stringProperty(desc);
    }

    /** A bare {@code {type:boolean, description}} property map (for hand-assembled schemas). */
    public static Map<String, Object> boolProperty(String desc) {
        return ToolSchemas.boolProperty(desc);
    }

    /** Builds a JSON-schema {@code object} as a plain {@code Map} (no Jackson needed). */
    public static final class SchemaBuilder {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public SchemaBuilder str(String name, String desc) {
            return prop(name, "string", desc, false);
        }

        public SchemaBuilder strReq(String name, String desc) {
            return prop(name, "string", desc, true);
        }

        public SchemaBuilder integer(String name, String desc) {
            return prop(name, "integer", desc, false);
        }

        public SchemaBuilder bool(String name, String desc) {
            return prop(name, "boolean", desc, false);
        }

        public SchemaBuilder strArray(String name, String desc) {
            return arrayProp(name, desc, false);
        }

        public SchemaBuilder strArrayReq(String name, String desc) {
            return arrayProp(name, desc, true);
        }

        /**
         * An array of annotation objects, each {@code {property, value | value_iri, lang, datatype}},
         * used as the optional axiom-annotation operand. Mirrors how a single annotation value is
         * resolved by {@link Tools#buildAnnotation}.
         */
        public SchemaBuilder annotationArray(String name, String desc) {
            Map<String, Object> itemProps = new LinkedHashMap<>();
            itemProps.put("property", strProp("Annotation property: 'rdfs:label', 'rdfs:comment', "
                    + "or an IRI/name (default rdfs:label)."));
            itemProps.put("value", strProp("Literal text value (omit if value_iri is given)."));
            itemProps.put("value_iri", strProp("IRI-valued annotation: an entity name/IRI or absolute "
                    + "IRI (alternative to value)."));
            itemProps.put("lang", strProp("Optional language tag for a literal value, e.g. 'en'."));
            itemProps.put("datatype", strProp("Optional datatype IRI/name for a typed literal value."));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "object");
            item.put("properties", itemProps);
            item.put("additionalProperties", false);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "array");
            p.put("items", item);
            if (desc != null) {
                p.put("description", desc);
            }
            properties.put(name, p);
            return this;
        }

        private static Map<String, Object> strProp(String desc) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "string");
            if (desc != null) {
                p.put("description", desc);
            }
            return p;
        }

        private SchemaBuilder arrayProp(String name, String desc, boolean req) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "array");
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "string");
            p.put("items", items);
            if (desc != null) {
                p.put("description", desc);
            }
            properties.put(name, p);
            if (req) {
                required.add(name);
            }
            return this;
        }

        private SchemaBuilder prop(String name, String type, String desc, boolean req) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            if (desc != null) {
                p.put("description", desc);
            }
            properties.put(name, p);
            if (req) {
                required.add(name);
            }
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "object");
            m.put("properties", properties);
            if (!required.isEmpty()) {
                m.put("required", required);
            }
            m.put("additionalProperties", false);
            return m;
        }
    }

    // ------------------------------------------------------------------ entity resolution

    /** Parse {@code ref} as an absolute IRI, or return null if it is not one (e.g. a display name). */
    public static IRI asIri(String ref) {
        return EntityResolver.asIri(ref);
    }

    /** Resolve a single entity by IRI (preferred) or by Protégé display rendering. Null if none. */
    public static OWLEntity findEntity(OWLModelManager mm, String ref) {
        return EntityResolver.findEntity(mm, ref);
    }

    /** All entities matching {@code ref} (IRI may be "punned" across several entity types). */
    public static Set<OWLEntity> findEntities(OWLModelManager mm, String ref) {
        return EntityResolver.findEntities(mm, ref);
    }

    public static OWLClass resolveClass(OWLModelManager mm, String ref) {
        return EntityResolver.resolveClass(mm, ref);
    }

    /**
     * Resolve {@code ref} to a class expression (existing class, minted from a full IRI, or Manchester
     * syntax). See {@link EntityResolver#resolveClassExpression}. Must run on the EDT.
     */
    public static OWLClassExpression resolveClassExpression(OWLModelManager mm, String ref) {
        return EntityResolver.resolveClassExpression(mm, ref);
    }

    /** Parse {@code ref} with the OWL API Manchester parser (full-IRI aware); null if it cannot. */
    static OWLClassExpression tryManchesterClassExpression(OWLModelManager mm, String ref) {
        return EntityResolver.tryManchesterClassExpression(mm, ref);
    }

    public static OWLNamedIndividual resolveIndividual(OWLModelManager mm, String ref) {
        return EntityResolver.resolveIndividual(mm, ref);
    }

    public static OWLObjectProperty resolveObjectProperty(OWLModelManager mm, String ref) {
        return EntityResolver.resolveObjectProperty(mm, ref);
    }

    public static OWLDataProperty resolveDataProperty(OWLModelManager mm, String ref) {
        return EntityResolver.resolveDataProperty(mm, ref);
    }

    public static OWLAnnotationProperty resolveAnnotationProperty(OWLModelManager mm, String ref) {
        return EntityResolver.resolveAnnotationProperty(mm, ref);
    }

    public static OWLDatatype resolveDatatype(OWLModelManager mm, String ref) {
        return EntityResolver.resolveDatatype(mm, ref);
    }

    /**
     * Resolve {@code ref} to a data range (named datatype, minted from a full IRI, or Manchester
     * syntax). See {@link EntityResolver#resolveDataRange}. Must run on the EDT.
     */
    public static OWLDataRange resolveDataRange(OWLModelManager mm, String ref) {
        return EntityResolver.resolveDataRange(mm, ref);
    }

    /** Resolve {@code ref} to a bare IRI: an existing entity's IRI, or an absolute IRI string. */
    public static IRI iriRef(OWLModelManager mm, String ref) {
        return EntityResolver.iriRef(mm, ref);
    }

    // ------------------------------------------------------------------ annotations

    /** Resolve the subject of an annotation assertion: an existing entity's IRI, or an absolute IRI. */
    public static IRI annotationSubject(OWLModelManager mm, String ref) {
        return AnnotationBuilder.annotationSubject(mm, ref);
    }

    /** Resolve an annotation property; {@code null}/'rdfs:label' → label, 'rdfs:comment' → comment. */
    public static OWLAnnotationProperty annotationProperty(OWLModelManager mm, String ref) {
        return AnnotationBuilder.annotationProperty(mm, ref);
    }

    /** Build an annotation value from {@code a}. See {@link AnnotationBuilder#annotationValue}. */
    public static OWLAnnotationValue annotationValue(OWLModelManager mm, Map<String, Object> a) {
        return AnnotationBuilder.annotationValue(mm, a);
    }

    /** Build a single annotation from a map {@code {property, value | value_iri, lang, datatype}}. */
    public static OWLAnnotation buildAnnotation(OWLModelManager mm, Map<String, Object> a) {
        return AnnotationBuilder.buildAnnotation(mm, a);
    }

    /** Build the (possibly empty) set of axiom/ontology annotations from the {@code key} array operand. */
    public static Set<OWLAnnotation> annotationSet(OWLModelManager mm, Map<String, Object> a, String key) {
        return AnnotationBuilder.annotationSet(mm, a, key);
    }

    // ------------------------------------------------------------------ rendering

    public static String renderEntity(OWLModelManager mm, OWLEntity e) {
        return EntityRendering.renderEntity(mm, e);
    }

    public static String renderAxiom(OWLModelManager mm, OWLAxiom ax) {
        return EntityRendering.renderAxiom(mm, ax);
    }

    /** Render any OWL object (class expression, axiom, ...) via Protégé, falling back to toString. */
    public static String renderObject(OWLModelManager mm, OWLObject o) {
        return EntityRendering.renderObject(mm, o);
    }

    // ------------------------------------------------------------------ JSON serializers

    /** The standard JSON shape for an entity: {@code {iri, display, type}}. */
    public static Map<String, Object> entityJson(OWLModelManager mm, OWLEntity e) {
        return EntityJson.entityJson(mm, e);
    }

    /** The standard JSON shape for an axiom: {@code {axiom_type, rendering}}. */
    public static Map<String, Object> axiomJson(OWLModelManager mm, OWLAxiom ax) {
        return EntityJson.axiomJson(mm, ax);
    }

    /** The canonical JSON shape for an annotation. See {@link EntityJson#annotationJson}. */
    public static Map<String, Object> annotationJson(OWLModelManager mm, OWLAnnotation ann) {
        return EntityJson.annotationJson(mm, ann);
    }

    /** A capped, display-sorted entity list {@code {count, items, truncated?}}. See {@link EntityJson#entityList}. */
    public static Map<String, Object> entityList(OWLModelManager mm,
            Collection<? extends OWLEntity> entities, int limit) {
        return EntityJson.entityList(mm, entities, limit);
    }

    /** A capped, sorted axiom list {@code {count, items, truncated?}}. See {@link EntityJson#axiomList}. */
    public static Map<String, Object> axiomList(OWLModelManager mm,
            Collection<? extends OWLAxiom> axioms, int limit) {
        return EntityJson.axiomList(mm, axioms, limit);
    }
}
