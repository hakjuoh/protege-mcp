package io.github.hakjuoh.protege_mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Method-level tests for the package-private seam {@link Tools#tryManchesterClassExpression}, which
 * parses a reference with the OWL API Manchester parser wired to a Protégé entity checker, returning
 * {@code null} on any {@link RuntimeException}.
 *
 * <p>Two harnesses are used, each pinning down a distinct, empirically verified behavior:
 * <ul>
 *   <li>{@link FakeModelManager} — its entity finder implements only {@code getEntities(IRI)} and
 *       {@code getOWLEntity(String)}. The prefix/fragment-aware checker resolves {@code <IRI>} tokens
 *       through {@code getEntities(IRI)} and bare IRI fragments through the signature scan, and GUARDS
 *       the finder's throwing by-rendering probes, so a well-formed full-IRI or bare-fragment
 *       expression now parses under this harness too.</li>
 *   <li>A local {@link #nullLookupManager} whose finder returns {@code null} (rather than throwing)
 *       for every {@code getOWL*} probe — matching how a real Protégé finder answers "no such
 *       entity". This lets the OWL API parser resolve full {@code <IRI>} tokens natively and
 *       exercises the real success paths.</li>
 * </ul>
 */
class ManchesterClassExpressionSeamTest {

    private static final String NS = "http://ex.org/";

    /** Build an ontology declaring classes A and B (used by the full-IRI expressions). */
    private static OWLOntology ontologyWithAB() {
        try {
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            OWLOntology o = m.createOntology(IRI.create("http://ex.org/o"));
            OWLDataFactory df = m.getOWLDataFactory();
            m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "A"))));
            m.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "B"))));
            return o;
        } catch (OWLOntologyCreationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A manager over {@code o} whose finder answers {@code getEntities(IRI)} from the signature but
     * returns {@code null} (never throws) for every {@code getOWL*} lookup — the way a live Protégé
     * finder reports "unknown short name". This is what lets the Manchester parser resolve full
     * {@code <IRI>} tokens without a probe throw masking the parse result.
     */
    private static OWLModelManager nullLookupManager(OWLOntology o) {
        OWLEntityFinder finder = (OWLEntityFinder) Proxy.newProxyInstance(
                ManchesterClassExpressionSeamTest.class.getClassLoader(),
                new Class<?>[] { OWLEntityFinder.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getEntities":
                            if (args != null && args.length == 1 && args[0] instanceof IRI) {
                                Set<OWLEntity> out = new LinkedHashSet<>();
                                for (OWLEntity e : o.getSignature()) {
                                    if (e.getIRI().equals(args[0])) {
                                        out.add(e);
                                    }
                                }
                                return out;
                            }
                            return new LinkedHashSet<OWLEntity>();
                        case "toString":
                            return "nullLookupFinder";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            // getOWLClass/getOWLObjectProperty/getOWLDataProperty/... => no match.
                            return null;
                    }
                });
        return (OWLModelManager) Proxy.newProxyInstance(
                ManchesterClassExpressionSeamTest.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOWLDataFactory":
                            return o.getOWLOntologyManager().getOWLDataFactory();
                        case "getActiveOntology":
                            return o;
                        case "getOWLEntityFinder":
                            return finder;
                        case "toString":
                            return "nullLookupManager";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    // ---- Real parse-success paths (finder returns null for probes, so parsing proceeds) ----

    @Test
    void fullIriCompoundParsesToIntersectionOfAAndB() {
        OWLOntology o = ontologyWithAB();
        OWLModelManager mm = nullLookupManager(o);
        OWLClassExpression ce = Tools.tryManchesterClassExpression(
                mm, "<" + NS + "A> and <" + NS + "B>");

        assertTrue(ce instanceof OWLObjectIntersectionOf,
                "expected an ObjectIntersectionOf, got " + (ce == null ? "null" : ce.getClass()));
        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        OWLClass a = df.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = df.getOWLClass(IRI.create(NS + "B"));
        Set<OWLClassExpression> operands = ((OWLObjectIntersectionOf) ce).getOperands();
        assertEquals(new LinkedHashSet<>(java.util.Arrays.asList(a, b)), operands);
    }

    @Test
    void fullIriSingleParsesToNamedClass() {
        OWLOntology o = ontologyWithAB();
        OWLModelManager mm = nullLookupManager(o);
        OWLClassExpression ce = Tools.tryManchesterClassExpression(mm, "<" + NS + "A>");

        OWLDataFactory df = o.getOWLOntologyManager().getOWLDataFactory();
        assertEquals(df.getOWLClass(IRI.create(NS + "A")), ce);
    }

    @Test
    void garbageReturnsNullWhenRuntimeExceptionSwallowed() {
        OWLModelManager mm = nullLookupManager(ontologyWithAB());
        assertNull(Tools.tryManchesterClassExpression(mm, "&&&((("),
                "unparseable input must be caught and returned as null");
    }

    @Test
    void bareNameReturnsNullWhenFinderReportsNoSuchEntity() {
        // With a finder that returns null for getOWLClass("A"), the Manchester parser cannot
        // resolve the bare short name and throws a parse error, which the seam swallows to null.
        OWLModelManager mm = nullLookupManager(ontologyWithAB());
        assertNull(Tools.tryManchesterClassExpression(mm, "A and B"));
    }

    // ---- FakeModelManager harness: the prefix/fragment-aware checker now resolves via the signature ----

    @Test
    void fakeModelManagerResolvesFullIriExpressionViaSignature() {
        // The prefix/fragment-aware checker resolves <IRI> tokens through the finder's getEntities(IRI)
        // and guards the finder's throwing by-rendering probes, so a well-formed full-IRI expression
        // now parses under FakeModelManager (it returned null before the CURIE/fragment seam fix).
        OWLModelManager mm = FakeModelManager.over(ontologyWithAB());
        OWLClassExpression ce = Tools.tryManchesterClassExpression(mm, "<" + NS + "A> and <" + NS + "B>");
        assertTrue(ce instanceof OWLObjectIntersectionOf,
                "expected an ObjectIntersectionOf, got " + (ce == null ? "null" : ce.getClass()));
    }

    @Test
    void fakeModelManagerResolvesBareFragmentExpression() {
        // A bare local name (IRI fragment) resolves through the signature scan of the prefix/fragment
        // -aware checker (previously the finder's probe throw masked it as null).
        OWLModelManager mm = FakeModelManager.over(ontologyWithAB());
        OWLClassExpression ce = Tools.tryManchesterClassExpression(mm, "A and B");
        assertTrue(ce instanceof OWLObjectIntersectionOf,
                "bare IRI fragments resolve to an ObjectIntersectionOf, got "
                        + (ce == null ? "null" : ce.getClass()));
    }
}
