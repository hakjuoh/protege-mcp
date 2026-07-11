package io.github.hakjuoh.protege_mcp.tools;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.classexpression.OWLExpressionParserException;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.editor.owl.model.parser.ProtegeOWLEntityChecker;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

/**
 * Resolves textual references (display names or IRIs) to OWL entities / class expressions / data ranges,
 * minting from a full IRI when not yet declared and parsing Manchester syntax where applicable. Split out
 * of {@link Tools} as a focused unit; {@code Tools} keeps thin delegators for source compatibility.
 */
public final class EntityResolver {

    private EntityResolver() {
    }

    /** A CURIE token {@code prefix:local} outside angle brackets. The local part must start with an
     * alphanumeric/underscore, so a full IRI's {@code http://…} (colon followed by '/') never matches. */
    private static final Pattern CURIE_TOKEN =
            Pattern.compile("([A-Za-z][A-Za-z0-9_.-]*):([A-Za-z0-9_][A-Za-z0-9_.\\-]*)");

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
        } catch (URISyntaxException ignored) {
            // not a syntactically valid absolute IRI (e.g. a display name)
        }
        return null;
    }

    /**
     * Expand a registered-prefix CURIE ({@code prefix:local}) to its full IRI using the active ontology's
     * prefix map, or return null when {@code ref} is not a CURIE against a known prefix. This is what makes
     * {@code ex:Thing_0001} resolve to the imported class instead of being (mis)read by {@link #asIri}
     * as an absolute IRI whose scheme happens to be {@code ex} — which would silently mint a junk entity
     * whose IRI is the literal string "ex:Thing_0001". Guards: a real hierarchical IRI ({@code scheme://…})
     * and an empty or whitespace-bearing local part are never treated as CURIEs, so full IRIs and display
     * phrases keep their existing behaviour.
     */
    static IRI expandCurie(OWLModelManager mm, String ref) {
        if (ref == null) {
            return null;
        }
        int colon = ref.indexOf(':');
        if (colon < 0) {
            return null;                                   // a bare display name, not a CURIE
        }
        String local = ref.substring(colon + 1);
        if (local.isEmpty() || local.startsWith("//") || local.indexOf(' ') >= 0) {
            return null;                                   // scheme://… , empty, or a phrase — not a CURIE
        }
        String prefix = ref.substring(0, colon + 1);       // keep the trailing ':' (prefix map keys carry it)
        String namespace;
        try {
            namespace = SparqlTools.prefixMap(mm, mm.getActiveOntology()).get(prefix);
        } catch (RuntimeException ignored) {
            return null;                                   // no active ontology / no prefix map available
        }
        return namespace == null ? null : IRI.create(namespace + local);
    }

    /** A registered-prefix CURIE expanded to its IRI, else {@code ref} parsed as an absolute IRI (or null). */
    static IRI iriFor(OWLModelManager mm, String ref) {
        IRI curie = expandCurie(mm, ref);
        return curie != null ? curie : asIri(ref);
    }

    /** Resolve a single entity by IRI (preferred) or by Protégé display rendering. Null if none. */
    public static OWLEntity findEntity(OWLModelManager mm, String ref) {
        Set<OWLEntity> es = findEntities(mm, ref);
        return es.isEmpty() ? null : es.iterator().next();
    }

    /** All entities matching {@code ref} (IRI may be "punned" across several entity types). */
    public static Set<OWLEntity> findEntities(OWLModelManager mm, String ref) {
        OWLEntityFinder finder = mm.getOWLEntityFinder();
        IRI iri = iriFor(mm, ref);
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
        IRI iri = iriFor(mm, ref);
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
            // Fallback: the OWL API Manchester parser natively accepts full <IRI> tokens that Protégé's
            // class-expression checker rejects, while still resolving short names via a Protégé entity
            // checker — so a compound expression can reference terms by full IRI, e.g.
            // "<http://…/Identifier> and (<http://…/designates> some <http://…/Agent>)".
            OWLClassExpression viaApi = tryManchesterClassExpression(mm, ref);
            if (viaApi != null) {
                return viaApi;
            }
            if (e != null) {
                throw new ToolArgException("'" + ref + "' is a " + e.getEntityType().getName()
                        + ", not a class expression.");
            }
            IRI iri = iriFor(mm, ref);
            if (iri != null) {
                return mm.getOWLDataFactory().getOWLClass(iri);
            }
            throw new ToolArgException("Could not resolve class expression '" + ref + "': "
                    + parseError.getMessage() + " — pass a class name, a full IRI, or a "
                    + "Manchester-syntax class expression such as \"Animal and (hasOwner some Person)\".");
        }
    }

    /** Parse {@code ref} with the OWL API Manchester parser (full-IRI-, CURIE- and fragment-aware); null
     * if it cannot. */
    static OWLClassExpression tryManchesterClassExpression(OWLModelManager mm, String ref) {
        try {
            ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
            parser.setOWLEntityChecker(prefixAwareChecker(mm));
            parser.setDefaultOntology(mm.getActiveOntology());
            parser.setStringToParse(expandCuriesForManchester(mm, ref));
            return parser.parseClassExpression();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * A Manchester entity checker that resolves a token first as a registered-prefix CURIE or full IRI
     * (via {@link #iriFor}, looked up in the signature), then as an unambiguous bare IRI local name
     * (fragment), and finally delegates to Protégé's rendering/label checker. This lets COMPOUND
     * Manchester expressions accept {@code ex:X} CURIEs and bare fragments — matching what
     * single-entity operands (sub, chain items, create_* parents) already accept — while quoted-label,
     * single-word-label and {@code <IRI>} forms keep working through the delegate. Returns null for
     * unknowns (never mints inside a compound), preserving the "typo → parse error" behaviour.
     */
    static OWLEntityChecker prefixAwareChecker(OWLModelManager mm) {
        OWLEntityChecker delegate = new ProtegeOWLEntityChecker(mm.getOWLEntityFinder());
        return new OWLEntityChecker() {
            private <T extends OWLEntity> T lookup(String name, Class<T> cls) {
                if (name == null) {
                    return null;
                }
                try {
                    IRI iri = iriFor(mm, name);   // CURIE→IRI, or an absolute IRI; null for a bare name/label
                    if (iri != null) {
                        for (OWLEntity ent : mm.getOWLEntityFinder().getEntities(iri)) {
                            if (cls.isInstance(ent)) {
                                return cls.cast(ent);
                            }
                        }
                    }
                    if (name.indexOf(':') < 0 && name.indexOf(' ') < 0) {   // a bare local name / fragment
                        T match = null;
                        for (OWLOntology o : mm.getActiveOntologies()) {
                            for (OWLEntity ent : o.getSignature()) {
                                if (cls.isInstance(ent) && iriLocalName(ent.getIRI()).equals(name)) {
                                    if (match != null && !match.equals(ent)) {
                                        return null;   // ambiguous fragment → defer to the label delegate
                                    }
                                    match = cls.cast(ent);
                                }
                            }
                        }
                        if (match != null) {
                            return match;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // A partial manager (e.g. a minimal test mock without a prefix map / imports closure)
                    // may not support an accessor used here; treat as "not resolved by this checker" and
                    // let the delegate (and the parser's native <IRI> handling) proceed.
                }
                return null;
            }

            @Override
            public OWLClass getOWLClass(String name) {
                return viaDelegate(lookup(name, OWLClass.class), () -> delegate.getOWLClass(name));
            }

            @Override
            public OWLObjectProperty getOWLObjectProperty(String name) {
                return viaDelegate(lookup(name, OWLObjectProperty.class),
                        () -> delegate.getOWLObjectProperty(name));
            }

            @Override
            public OWLDataProperty getOWLDataProperty(String name) {
                return viaDelegate(lookup(name, OWLDataProperty.class),
                        () -> delegate.getOWLDataProperty(name));
            }

            @Override
            public OWLNamedIndividual getOWLIndividual(String name) {
                return viaDelegate(lookup(name, OWLNamedIndividual.class),
                        () -> delegate.getOWLIndividual(name));
            }

            @Override
            public OWLDatatype getOWLDatatype(String name) {
                return viaDelegate(lookup(name, OWLDatatype.class), () -> delegate.getOWLDatatype(name));
            }

            @Override
            public OWLAnnotationProperty getOWLAnnotationProperty(String name) {
                return viaDelegate(lookup(name, OWLAnnotationProperty.class),
                        () -> delegate.getOWLAnnotationProperty(name));
            }
        };
    }

    /** Return {@code resolved} if non-null, else the delegate's answer — treating a delegate that throws
     * (e.g. an entity finder that does not implement a by-rendering lookup) as "not found" rather than
     * letting it abort the parse. The OWL API parser probes several checker methods to type a token, so a
     * throwing delegate for the non-matching types would otherwise sink an otherwise-resolvable name. */
    private static <T> T viaDelegate(T resolved, java.util.function.Supplier<T> delegateLookup) {
        if (resolved != null) {
            return resolved;
        }
        try {
            return delegateLookup.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** IRI local part: the substring after the last '#' or '/'. */
    private static String iriLocalName(IRI iri) {
        String s = iri.toString();
        int cut = Math.max(s.lastIndexOf('#'), s.lastIndexOf('/'));
        return (cut >= 0 && cut < s.length() - 1) ? s.substring(cut + 1) : s;
    }

    /**
     * Rewrite every registered-prefix CURIE ({@code ex:Agent}) in a Manchester string to a full
     * {@code <IRI>}, which the OWL API Manchester parser handles natively. The parser resolves prefixed
     * names through its own prefix manager (defaults only: owl/rdf/rdfs/xsd) and does NOT consult the
     * entity checker for them — so a custom checker can add bare-fragment support but not CURIE support;
     * pre-expansion is what makes {@code ex:X} work inside a COMPOUND expression, matching what a
     * single-entity operand already accepts. Content inside {@code <…>} is untouched (a full IRI's only
     * colon is {@code http:}/{@code https:}, whose local part starts with '/', so it never matches).
     */
    static String expandCuriesForManchester(OWLModelManager mm, String ref) {
        if (ref == null || ref.indexOf(':') < 0) {
            return ref;
        }
        Map<String, String> prefixes;
        try {
            prefixes = SparqlTools.prefixMap(mm, mm.getActiveOntology());
        } catch (RuntimeException e) {
            return ref;   // a manager without a prefix map — skip expansion; labels/<IRI> still parse
        }
        if (prefixes == null || prefixes.isEmpty()) {
            return ref;
        }
        Matcher matcher = CURIE_TOKEN.matcher(ref);
        StringBuffer out = new StringBuffer(ref.length());
        while (matcher.find()) {
            String ns = prefixes.get(matcher.group(1) + ":");
            String replacement = ns != null ? "<" + ns + matcher.group(2) + ">" : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
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
            parser.setOWLEntityChecker(prefixAwareChecker(mm));
            parser.setDefaultOntology(mm.getActiveOntology());
            parser.setStringToParse(expandCuriesForManchester(mm, ref));
            return parser.parseDataRange();
        } catch (RuntimeException parseError) {
            if (e != null) {
                throw new ToolArgException("'" + ref + "' is a " + e.getEntityType().getName()
                        + ", not a data range.");
            }
            IRI iri = iriFor(mm, ref);
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
        IRI iri = iriFor(mm, ref);
        if (iri != null) {
            return iri;
        }
        throw new ToolArgException("Expected an entity name/IRI or an absolute IRI: '" + ref + "'.");
    }
}
