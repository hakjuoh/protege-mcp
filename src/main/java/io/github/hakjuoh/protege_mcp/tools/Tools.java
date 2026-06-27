package io.github.hakjuoh.protege_mcp.tools;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.classexpression.OWLExpressionParserException;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.editor.owl.model.parser.ProtegeOWLEntityChecker;
import io.github.hakjuoh.protege_mcp.server.McpAccessException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

import com.fasterxml.jackson.databind.ObjectMapper;

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

    /**
     * Every tool returns a JSON object. The object is carried both as MCP {@code structuredContent}
     * (for clients that consume structured tool output) and, serialized, as the text content (so every
     * client — and a human reading the transcript — sees the same JSON). {@link #serialize} is the
     * single place results turn into a string.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Build a {@link CallToolResult} from a result object (success). */
    public static CallToolResult ok(Map<String, Object> data) {
        Map<String, Object> body = data == null ? new LinkedHashMap<>() : data;
        return CallToolResult.builder()
                .structuredContent(body)
                .addTextContent(serialize(body))
                .isError(false)
                .build();
    }

    /**
     * A plain confirmation/message result: {@code {"message": s}}. Kept so trivial confirmations stay
     * one-liners and any not-yet-restructured handler still emits valid JSON.
     */
    public static CallToolResult text(String s) {
        return json().put("message", s == null ? "" : s).result();
    }

    /** An error result: {@code {"error": message}} with {@code isError=true}. */
    public static CallToolResult error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null ? "error" : message);
        return CallToolResult.builder()
                .structuredContent(body)
                .addTextContent(serialize(body))
                .isError(true)
                .build();
    }

    /** Serialize a result object to pretty JSON; never throws (falls back to {@code toString}). */
    public static String serialize(Object data) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(data);
        }
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
        try {
            return body.get();
        } catch (ToolArgException e) {
            return error(e.getMessage());
        } catch (McpAccessException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            return error(e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
        }
    }

    // ------------------------------------------------------------------ arguments

    public static Map<String, Object> args(CallToolRequest req) {
        Map<String, Object> a = req.arguments();
        return a == null ? Collections.emptyMap() : a;
    }

    public static String optString(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    public static String reqString(Map<String, Object> a, String key) {
        String s = optString(a, key);
        if (s == null) {
            throw new ToolArgException("Missing required argument '" + key + "'.");
        }
        return s;
    }

    public static int optInt(Map<String, Object> a, String key, int def) {
        Object v = a.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }

    public static boolean optBool(Map<String, Object> a, String key, boolean def) {
        Object v = a.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v != null) {
            return Boolean.parseBoolean(String.valueOf(v).trim());
        }
        return def;
    }

    public static List<String> stringList(Map<String, Object> a, String key) {
        Object v = a.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v != null) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    /** Read {@code key} as a list of object maps (e.g. the {@code annotations} operand). */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objList(Map<String, Object> a, String key) {
        Object v = a.get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
        } else if (v instanceof Map) {
            out.add((Map<String, Object>) v);
        }
        return out;
    }

    // ------------------------------------------------------------------ schema builder

    public static SchemaBuilder schema() {
        return new SchemaBuilder();
    }

    public static Map<String, Object> emptySchema() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("additionalProperties", false);
        return m;
    }

    /** A bare {@code {type:string, description}} property map (for hand-assembled schemas). */
    public static Map<String, Object> stringProperty(String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        if (desc != null) {
            p.put("description", desc);
        }
        return p;
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
        if (ref == null) {
            return null;
        }
        try {
            URI u = new URI(ref);
            if (u.isAbsolute()) {
                return IRI.create(ref);
            }
        } catch (Exception ignored) {
            // not an IRI
        }
        return null;
    }

    /** Resolve a single entity by IRI (preferred) or by Protégé display rendering. Null if none. */
    public static OWLEntity findEntity(OWLModelManager mm, String ref) {
        OWLEntityFinder finder = mm.getOWLEntityFinder();
        IRI iri = asIri(ref);
        if (iri != null) {
            Set<OWLEntity> es = finder.getEntities(iri);
            if (es != null && !es.isEmpty()) {
                return es.iterator().next();
            }
        }
        OWLEntity byRendering = finder.getOWLEntity(ref);
        if (byRendering != null) {
            return byRendering;
        }
        return null;
    }

    /** All entities matching {@code ref} (IRI may be "punned" across several entity types). */
    public static Set<OWLEntity> findEntities(OWLModelManager mm, String ref) {
        OWLEntityFinder finder = mm.getOWLEntityFinder();
        IRI iri = asIri(ref);
        if (iri != null) {
            Set<OWLEntity> es = finder.getEntities(iri);
            if (es != null && !es.isEmpty()) {
                return es;
            }
        }
        OWLEntity e = finder.getOWLEntity(ref);
        return e == null ? Collections.<OWLEntity>emptySet() : Collections.singleton(e);
    }

    private static <T extends OWLEntity> T resolve(OWLModelManager mm, String ref, Class<T> cls,
            Function<IRI, T> mint, String typeLabel) {
        OWLEntity e = findEntity(mm, ref);
        if (cls.isInstance(e)) {
            return cls.cast(e);
        }
        if (e != null) {
            throw new ToolArgException(
                    "'" + ref + "' is a " + e.getEntityType().getName() + ", not a " + typeLabel + ".");
        }
        IRI iri = asIri(ref);
        if (iri != null) {
            return mint.apply(iri);
        }
        throw new ToolArgException(
                typeLabel + " not found: '" + ref + "'. Pass a full IRI to reference a new one.");
    }

    public static OWLClass resolveClass(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return resolve(mm, ref, OWLClass.class, df::getOWLClass, "class");
    }

    /**
     * Resolve {@code ref} to a class expression. A bare name or full IRI of an existing class
     * resolves to that named class (fast path); a full IRI for a not-yet-declared class mints a new
     * named class (as {@link #resolveClass} does); anything else is parsed as a Manchester OWL
     * syntax class expression, e.g. {@code "Animal and (hasOwner some Person)"}. Parsing uses
     * Protégé's own class-expression checker, so names resolve exactly as they render in the GUI.
     * Must run on the EDT (the checker touches Protégé renderers/entity finders).
     */
    public static OWLClassExpression resolveClassExpression(OWLModelManager mm, String ref) {
        OWLEntity e = findEntity(mm, ref);
        if (e instanceof OWLClass) {
            return (OWLClass) e;
        }
        try {
            return mm.getOWLExpressionCheckerFactory().getOWLClassExpressionChecker().createObject(ref);
        } catch (OWLExpressionParserException parseError) {
            if (e != null) {
                throw new ToolArgException("'" + ref + "' is a " + e.getEntityType().getName()
                        + ", not a class expression.");
            }
            IRI iri = asIri(ref);
            if (iri != null) {
                return mm.getOWLDataFactory().getOWLClass(iri);
            }
            throw new ToolArgException("Could not resolve class expression '" + ref + "': "
                    + parseError.getMessage() + " — pass a class name, a full IRI, or a "
                    + "Manchester-syntax class expression such as \"Animal and (hasOwner some Person)\".");
        }
    }

    public static OWLNamedIndividual resolveIndividual(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return resolve(mm, ref, OWLNamedIndividual.class, df::getOWLNamedIndividual, "individual");
    }

    public static OWLObjectProperty resolveObjectProperty(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return resolve(mm, ref, OWLObjectProperty.class, df::getOWLObjectProperty, "object property");
    }

    public static OWLDataProperty resolveDataProperty(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return resolve(mm, ref, OWLDataProperty.class, df::getOWLDataProperty, "data property");
    }

    public static OWLAnnotationProperty resolveAnnotationProperty(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return resolve(mm, ref, OWLAnnotationProperty.class, df::getOWLAnnotationProperty,
                "annotation property");
    }

    public static OWLDatatype resolveDatatype(OWLModelManager mm, String ref) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return resolve(mm, ref, OWLDatatype.class, df::getOWLDatatype, "datatype");
    }

    /**
     * Resolve {@code ref} to a data range. A named datatype (existing or a full IRI) resolves
     * directly (fast path); anything else is parsed as a Manchester OWL syntax data range, e.g.
     * {@code "xsd:integer[>= 0 , <= 130]"}, {@code "{1, 2, 3}"}, or {@code "xsd:string and not {\"\"}"}.
     * Parsing uses the OWL API Manchester parser with a Protégé entity checker, so datatype names
     * resolve exactly as they render in the GUI. Must run on the EDT (the checker touches Protégé
     * renderers/entity finders).
     */
    public static OWLDataRange resolveDataRange(OWLModelManager mm, String ref) {
        OWLEntity e = findEntity(mm, ref);
        if (e instanceof OWLDatatype) {
            return (OWLDatatype) e;
        }
        try {
            ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
            parser.setOWLEntityChecker(new ProtegeOWLEntityChecker(mm.getOWLEntityFinder()));
            parser.setDefaultOntology(mm.getActiveOntology());
            parser.setStringToParse(ref);
            return parser.parseDataRange();
        } catch (RuntimeException parseError) {
            if (e != null) {
                throw new ToolArgException("'" + ref + "' is a " + e.getEntityType().getName()
                        + ", not a data range.");
            }
            IRI iri = asIri(ref);
            if (iri != null) {
                return mm.getOWLDataFactory().getOWLDatatype(iri);
            }
            throw new ToolArgException("Could not resolve data range '" + ref + "': "
                    + parseError.getMessage() + " — pass a datatype name, a full IRI, or a "
                    + "Manchester-syntax data range such as \"xsd:integer[>= 0]\" or \"{1, 2, 3}\".");
        }
    }

    /** Resolve {@code ref} to a bare IRI: an existing entity's IRI, or an absolute IRI string. */
    public static IRI iriRef(OWLModelManager mm, String ref) {
        OWLEntity e = findEntity(mm, ref);
        if (e != null) {
            return e.getIRI();
        }
        IRI iri = asIri(ref);
        if (iri != null) {
            return iri;
        }
        throw new ToolArgException("Expected an entity name/IRI or an absolute IRI: '" + ref + "'.");
    }

    // ------------------------------------------------------------------ annotations

    /** Resolve the subject of an annotation assertion: an existing entity's IRI, or an absolute IRI. */
    public static IRI annotationSubject(OWLModelManager mm, String ref) {
        OWLEntity e = findEntity(mm, ref);
        if (e != null) {
            return e.getIRI();
        }
        IRI iri = asIri(ref);
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
        return resolveAnnotationProperty(mm, ref);
    }

    /**
     * Build an annotation value from {@code a}: {@code value_iri} → an IRI value; otherwise
     * {@code value} as a literal, typed by {@code datatype} or tagged by {@code lang} if present.
     */
    public static OWLAnnotationValue annotationValue(OWLModelManager mm, Map<String, Object> a) {
        OWLDataFactory df = mm.getOWLDataFactory();
        String iriValue = optString(a, "value_iri");
        if (iriValue != null) {
            return iriRef(mm, iriValue);
        }
        String value = reqString(a, "value");
        String datatype = optString(a, "datatype");
        String lang = optString(a, "lang");
        if (datatype != null) {
            return df.getOWLLiteral(value, resolveDatatype(mm, datatype));
        }
        if (lang != null) {
            return df.getOWLLiteral(value, lang);
        }
        return df.getOWLLiteral(value);
    }

    /** Build a single annotation from a map {@code {property, value | value_iri, lang, datatype}}. */
    public static OWLAnnotation buildAnnotation(OWLModelManager mm, Map<String, Object> a) {
        OWLDataFactory df = mm.getOWLDataFactory();
        return df.getOWLAnnotation(annotationProperty(mm, optString(a, "property")),
                annotationValue(mm, a));
    }

    /** Build the (possibly empty) set of axiom/ontology annotations from the {@code key} array operand. */
    public static Set<OWLAnnotation> annotationSet(OWLModelManager mm, Map<String, Object> a, String key) {
        Set<OWLAnnotation> set = new LinkedHashSet<>();
        for (Map<String, Object> item : objList(a, key)) {
            set.add(buildAnnotation(mm, item));
        }
        return set;
    }

    // ------------------------------------------------------------------ rendering

    public static String renderEntity(OWLModelManager mm, OWLEntity e) {
        return mm.getRendering(e) + "  <" + e.getIRI() + ">";
    }

    public static String renderAxiom(OWLModelManager mm, OWLAxiom ax) {
        String r = null;
        try {
            r = mm.getRendering(ax);
        } catch (RuntimeException ignored) {
            // some renderers only handle entities; fall back to toString
        }
        if (r == null || r.isEmpty()) {
            r = ax.toString();
        }
        return r;
    }

    /** Render any OWL object (class expression, axiom, ...) via Protégé, falling back to toString. */
    public static String renderObject(OWLModelManager mm, OWLObject o) {
        try {
            String r = mm.getRendering(o);
            if (r != null && !r.isEmpty()) {
                return r;
            }
        } catch (RuntimeException ignored) {
            // some renderers only handle entities; fall back to toString
        }
        return o.toString();
    }

    // ------------------------------------------------------------------ JSON serializers

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
        m.put("rendering", renderAxiom(mm, ax));
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
            } else if (!lit.isRDFPlainLiteral()) {
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
