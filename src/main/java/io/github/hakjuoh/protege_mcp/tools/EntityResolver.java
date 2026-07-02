package io.github.hakjuoh.protege_mcp.tools;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.classexpression.OWLExpressionParserException;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.editor.owl.model.parser.ProtegeOWLEntityChecker;
import org.semanticweb.owlapi.apibinding.OWLManager;
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
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

/**
 * Resolves textual references (display names or IRIs) to OWL entities / class expressions / data ranges,
 * minting from a full IRI when not yet declared and parsing Manchester syntax where applicable. Split out
 * of {@link Tools} as a focused unit; {@code Tools} keeps thin delegators for source compatibility.
 */
public final class EntityResolver {

    private EntityResolver() {
    }

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
            IRI iri = asIri(ref);
            if (iri != null) {
                return mm.getOWLDataFactory().getOWLClass(iri);
            }
            throw new ToolArgException("Could not resolve class expression '" + ref + "': "
                    + parseError.getMessage() + " — pass a class name, a full IRI, or a "
                    + "Manchester-syntax class expression such as \"Animal and (hasOwner some Person)\".");
        }
    }

    /** Parse {@code ref} with the OWL API Manchester parser (full-IRI aware); null if it cannot. */
    static OWLClassExpression tryManchesterClassExpression(OWLModelManager mm, String ref) {
        try {
            ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
            parser.setOWLEntityChecker(new ProtegeOWLEntityChecker(mm.getOWLEntityFinder()));
            parser.setDefaultOntology(mm.getActiveOntology());
            parser.setStringToParse(ref);
            return parser.parseClassExpression();
        } catch (RuntimeException ignored) {
            return null;
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
}
