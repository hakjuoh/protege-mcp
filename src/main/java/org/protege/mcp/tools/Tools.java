package org.protege.mcp.tools;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.classexpression.OWLExpressionParserException;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.mcp.server.McpAccessException;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
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

    // ------------------------------------------------------------------ results

    public static CallToolResult text(String s) {
        return CallToolResult.builder().addTextContent(s == null ? "" : s).isError(false).build();
    }

    public static CallToolResult error(String s) {
        return CallToolResult.builder().addTextContent(s == null ? "error" : s).isError(true).build();
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
}
