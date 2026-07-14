package io.github.hakjuoh.protege_mcp.tools;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A headless test double for {@link OWLModelManager}, built as a dynamic {@link Proxy} backed by a
 * real in-memory {@link OWLOntology}. It implements only the handful of manager/finder methods the
 * pure tool cores actually call — {@code getOWLDataFactory}, {@code getActiveOntology(ies)},
 * {@code getOWLOntologyManager}, {@code getOWLEntityFinder} (signature-backed), and
 * {@code getRendering} (short form) — and throws {@link UnsupportedOperationException} for anything
 * else, so a test that strays into Protégé-runtime-only territory (renderers, expression checkers,
 * the workspace) fails loudly instead of NPE-ing on a null collaborator. Tests that deliberately
 * exercise a Protégé "None" reasoner selection opt into {@link #withNoReasonerSelected}.
 *
 * <p>This unlocks method-level tests for tool helpers that take an {@code OWLModelManager} but are
 * otherwise pure: entity resolution by full IRI or by short name, axiom construction from full-IRI
 * operands, and the arity/unsupported-type error branches. Name resolution matches an entity when
 * the requested reference equals the entity's IRI short form (the part after the last {@code #} or
 * {@code /}); Manchester-syntax parsing and label-based rendering are intentionally NOT supported
 * (they need the live Protégé checker/renderer).
 */
final class FakeModelManager {

    private FakeModelManager() {
    }

    /** A manager over a fresh, empty ontology (IRI {@code http://example.org/fake}). */
    static OWLModelManager empty() {
        try {
            OWLOntologyManager m = OWLManager.createOWLOntologyManager();
            return over(m.createOntology(IRI.create("http://example.org/fake")));
        } catch (OWLOntologyCreationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A manager whose active ontology is {@code ontology}. */
    static OWLModelManager over(OWLOntology ontology) {
        OWLEntityFinder finder = (OWLEntityFinder) Proxy.newProxyInstance(
                FakeModelManager.class.getClassLoader(),
                new Class<?>[] { OWLEntityFinder.class },
                new FinderHandler(ontology));
        return (OWLModelManager) Proxy.newProxyInstance(
                FakeModelManager.class.getClassLoader(),
                new Class<?>[] { OWLModelManager.class },
                new ManagerHandler(ontology, finder));
    }

    /** A purpose-built variant with a real reasoner-manager seam whose current factory is None. */
    static OWLModelManager withNoReasonerSelected(OWLOntology ontology) {
        OWLReasonerManager reasoners = (OWLReasonerManager) Proxy.newProxyInstance(
                FakeModelManager.class.getClassLoader(), new Class<?>[] {OWLReasonerManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getReasonerStatus" -> ReasonerStatus.REASONER_NOT_INITIALIZED;
                    case "getCurrentReasonerFactory", "getCurrentReasonerFactoryId" -> null;
                    case "getCurrentReasonerName" -> "None";
                    case "getInstalledReasonerFactories" -> Set.of();
                    case "toString" -> "NoReasonerSelectedManager";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OWLModelManager base = over(ontology);
        return (OWLModelManager) Proxy.newProxyInstance(FakeModelManager.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class}, (proxy, method, args) -> {
                    if ("getOWLReasonerManager".equals(method.getName())) {
                        return reasoners;
                    }
                    try {
                        return method.invoke(base, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** The IRI short form: everything after the last {@code '#'} or {@code '/'} (else the whole IRI). */
    static String shortForm(IRI iri) {
        String s = iri.toString();
        int hash = s.lastIndexOf('#');
        int slash = s.lastIndexOf('/');
        int cut = Math.max(hash, slash);
        return (cut >= 0 && cut < s.length() - 1) ? s.substring(cut + 1) : s;
    }

    private static final class ManagerHandler implements InvocationHandler {
        private final OWLOntology ontology;
        private final OWLEntityFinder finder;

        ManagerHandler(OWLOntology ontology, OWLEntityFinder finder) {
            this.ontology = ontology;
            this.finder = finder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getOWLDataFactory":
                    return ontology.getOWLOntologyManager().getOWLDataFactory();
                case "getActiveOntology":
                    return ontology;
                case "getActiveOntologies":
                    return ontology.getImportsClosure();
                case "getOWLOntologyManager":
                    return ontology.getOWLOntologyManager();
                case "getOWLEntityFinder":
                    return finder;
                case "getRendering":
                    return render(args[0]);
                case "applyChange":
                    // Route straight to the backing OWL API manager (no HistoryManager/undo in the
                    // fake) so store cores that mutate the ontology (e.g. CQ annotations) are testable.
                    ontology.getOWLOntologyManager()
                            .applyChange((org.semanticweb.owlapi.model.OWLOntologyChange) args[0]);
                    return null;
                case "applyChanges":
                    ontology.getOWLOntologyManager().applyChanges(
                            (java.util.List<? extends org.semanticweb.owlapi.model.OWLOntologyChange>)
                                    args[0]);
                    return null;
                case "addListener":
                case "removeListener":
                    // OWLModelManagerListener (de)registration is a no-op in the fake — model events
                    // (active-ontology switch, classification, …) are not fired headless. The ontology
                    // CHANGE listener the SPARQL cache also installs goes to the real ontology manager
                    // (via getOWLOntologyManager above), so a real addAxiom still exercises invalidation.
                    return null;
                case "toString":
                    return "FakeModelManager[" + ontology.getOntologyID() + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(
                            "FakeModelManager does not implement " + method.getName()
                                    + " — this code path needs the live Protégé runtime.");
            }
        }

        private static String render(Object o) {
            if (o instanceof OWLEntity) {
                return shortForm(((OWLEntity) o).getIRI());
            }
            return String.valueOf(o);
        }
    }

    private static final class FinderHandler implements InvocationHandler {
        private final OWLOntology ontology;

        FinderHandler(OWLOntology ontology) {
            this.ontology = ontology;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getEntities":
                    // getEntities(IRI) — every entity in the signature with that IRI (punning aware).
                    if (args != null && args.length == 1 && args[0] instanceof IRI) {
                        IRI iri = (IRI) args[0];
                        Set<OWLEntity> out = new LinkedHashSet<>();
                        for (OWLEntity e : ontology.getSignature()) {
                            if (e.getIRI().equals(iri)) {
                                out.add(e);
                            }
                        }
                        return out;
                    }
                    throw new UnsupportedOperationException(
                            "FakeModelManager finder only supports getEntities(IRI); got "
                                    + Arrays.toString(args));
                case "getOWLEntity":
                    // getOWLEntity(String) — first signature entity whose short form matches.
                    String ref = (String) args[0];
                    for (OWLEntity e : ontology.getSignature()) {
                        if (shortForm(e.getIRI()).equals(ref)) {
                            return e;
                        }
                    }
                    return null;
                case "toString":
                    return "FakeEntityFinder";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(
                            "FakeModelManager finder does not implement " + method.getName());
            }
        }
    }
}
