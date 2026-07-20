package io.github.hakjuoh.protege_mcp.tools;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprint;
import io.github.hakjuoh.protege_mcp.contracts.OntologyFingerprints;
import io.github.hakjuoh.protege_mcp.core.workspace.WorkspaceFingerprints;

/**
 * Immutable-by-convention, private OWLAPI copy used by every non-reasoner QC stage.
 *
 * <p>The active ontology and every already-loaded import are copied into one private manager while the
 * caller holds the Protégé model-thread boundary. Import declarations are deliberately recorded as source
 * coordinates but are not replayed into the private manager: replaying an unresolved declaration can make
 * OWLAPI perform network I/O, while the validators in this slice consume the explicitly captured closure.
 * This also makes the isolation boundary fail-safe for a project with a missing remote import.
 *
 * <p>A small read-only {@link OWLModelManager} adapter preserves live entity renderings captured at that
 * instant and uses stable OWLAPI text for axioms. It exists only for legacy validation cores whose output
 * shape accepts an OWLModelManager; it exposes no write, workspace, reasoner, or GUI service.
 */
final class IsolatedValidationSnapshot {

    private final OWLOntology active;
    private final Set<OWLOntology> closure;
    private final Map<String, List<String>> importCoordinates;
    private final OWLModelManager modelManager;
    private final SuiteSnapshot queries;
    private final OntologyFingerprint fingerprint;
    private final String closureFingerprint;
    private final Map<String, String> prefixes;

    private IsolatedValidationSnapshot(OWLOntology active, Set<OWLOntology> closure,
            Map<String, List<String>> importCoordinates, OWLModelManager modelManager,
            SuiteSnapshot queries, OntologyFingerprint fingerprint, String closureFingerprint,
            Map<String, String> prefixes) {
        this.active = active;
        this.closure = Collections.unmodifiableSet(new LinkedHashSet<>(closure));
        Map<String, List<String>> coordinates = new LinkedHashMap<>();
        importCoordinates.forEach((key, value) -> coordinates.put(key, List.copyOf(value)));
        this.importCoordinates = Collections.unmodifiableMap(coordinates);
        this.modelManager = modelManager;
        this.queries = queries;
        this.fingerprint = fingerprint;
        this.closureFingerprint = closureFingerprint;
        this.prefixes = Collections.unmodifiableMap(new LinkedHashMap<>(prefixes));
    }

    /** Capture the loaded closure, renderings, prefixes, fingerprint, and asserted query graph. */
    static IsolatedValidationSnapshot capture(OWLModelManager live) {
        OWLOntology sourceActive = live.getActiveOntology();
        OntologyFingerprint fingerprint = OntologyFingerprints.compute(sourceActive);
        Map<String, String> prefixes = SparqlTools.prefixMap(live, sourceActive);

        List<OWLOntology> sources = new ArrayList<>(sourceActive.getImportsClosure());
        if (!sources.contains(sourceActive)) {
            sources.add(sourceActive);
        }
        sources.sort(Comparator.comparing(IsolatedValidationSnapshot::ontologyKey));
        String closureFingerprint = closureFingerprint(new LinkedHashSet<>(sources));

        Map<OWLObject, String> renderings = captureRenderings(live, sourceActive);
        OWLOntologyManager isolatedManager = OwlManagers.create();
        Map<OWLOntology, OWLOntology> copies = new LinkedHashMap<>();
        for (OWLOntology source : sources) {
            OWLOntology copy = create(isolatedManager, source.getOntologyID());
            isolatedManager.addAxioms(copy, source.getAxioms());
            for (OWLAnnotation annotation : source.getAnnotations()) {
                isolatedManager.applyChange(new AddOntologyAnnotation(copy, annotation));
            }
            copies.put(source, copy);
        }

        OWLOntology activeCopy = copies.get(sourceActive);
        if (activeCopy == null) {
            throw new ToolArgException("Could not identify the active ontology in the isolated QC snapshot.");
        }
        TurtleDocumentFormat format = new TurtleDocumentFormat();
        prefixes.forEach(format::setPrefix);
        isolatedManager.setOntologyFormat(activeCopy, format);

        Set<OWLOntology> closureCopies = new LinkedHashSet<>();
        for (OWLOntology source : sources) {
            closureCopies.add(copies.get(source));
        }
        OWLModelManager adapter = adapter(activeCopy, closureCopies, renderings);
        SuiteSnapshot queries = SuiteSnapshot.captureIsolated(activeCopy, closureCopies, prefixes);

        Map<String, List<String>> imports = new LinkedHashMap<>();
        for (OWLOntology source : sources) {
            List<String> declared = source.getImportsDeclarations().stream()
                    .map(declaration -> declaration.getIRI().toString()).sorted().toList();
            imports.put(ontologyKey(source), declared);
        }
        return new IsolatedValidationSnapshot(activeCopy, closureCopies, imports, adapter, queries,
                fingerprint, closureFingerprint, prefixes);
    }

    OWLOntology active() {
        return active;
    }

    Set<OWLOntology> closure() {
        return closure;
    }

    Map<String, List<String>> importCoordinates() {
        return importCoordinates;
    }

    /** Direct import IRIs captured from the live active ontology before declarations were stripped. */
    List<String> activeImportIris() {
        return importCoordinates.getOrDefault(ontologyKey(active), Collections.emptyList());
    }

    OWLModelManager modelManager() {
        return modelManager;
    }

    SuiteSnapshot queries() {
        return queries;
    }

    /** Return the same captured ontology state with inferred query data from its private reasoner. */
    IsolatedValidationSnapshot withInferences(org.semanticweb.owlapi.reasoner.OWLReasoner reasoner) {
        return withQueries(queries.withInferences(reasoner));
    }

    /** Return the same captured ontology state with a fail-closed inferred-data diagnostic. */
    IsolatedValidationSnapshot withInferenceError(String error) {
        return withQueries(queries.withInferenceError(error));
    }

    /** Apply an exact cached axiom delta only to this private copy and rebuild its query snapshot. */
    IsolatedValidationSnapshot withChanges(List<NormalizedChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return this;
        }
        List<org.semanticweb.owlapi.model.OWLOntologyChange> bound = changes.stream()
                .map(change -> change.bind(active)).toList();
        active.getOWLOntologyManager().applyChanges(bound);
        SuiteSnapshot updatedQueries = SuiteSnapshot.captureIsolated(active, closure, prefixes);
        // The private copies deliberately carry no import declarations; fingerprint them with the
        // imports captured from the live sources so the delta-preflight fingerprint matches the real
        // (import-bearing) live revision instead of a phantom import-free one.
        OntologyFingerprint updatedFingerprint = OntologyFingerprints.compute(active, null,
                importCoordinates.get(ontologyKey(active)));
        String updatedClosure = closureFingerprint(closure, importCoordinates);
        return new IsolatedValidationSnapshot(active, closure, importCoordinates, modelManager,
                updatedQueries, updatedFingerprint, updatedClosure, prefixes);
    }

    OntologyFingerprint fingerprint() {
        return fingerprint;
    }

    String closureFingerprint() {
        return closureFingerprint;
    }

    byte[] assertedTurtle() {
        return queries.assertedTurtle();
    }

    private IsolatedValidationSnapshot withQueries(SuiteSnapshot replacement) {
        return new IsolatedValidationSnapshot(active, closure, importCoordinates, modelManager,
                replacement, fingerprint, closureFingerprint, prefixes);
    }

    private static OWLOntology create(OWLOntologyManager manager, OWLOntologyID id) {
        try {
            return manager.createOntology(id == null ? new OWLOntologyID() : id);
        } catch (OWLOntologyCreationException e) {
            throw new ToolArgException("Could not prepare an isolated validation workspace: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static String ontologyKey(OWLOntology ontology) {
        return WorkspaceFingerprints.ontologyKey(ontology);
    }

    /** Same-session digest of every loaded ontology's semantic fingerprint, including imported content. */
    static String closureFingerprint(Set<OWLOntology> closure) {
        return closureFingerprint(closure, null);
    }

    /**
     * As {@link #closureFingerprint(Set)}, but with each member's direct imports supplied from the
     * captured coordinates keyed by {@link #ontologyKey}. Isolated copies carry no import declarations,
     * so a null map (live ontologies) reads them from the ontology; a supplied map restores them.
     */
    static String closureFingerprint(Set<OWLOntology> closure, Map<String, List<String>> importCoordinates) {
        return WorkspaceFingerprints.closure(closure, importCoordinates);
    }

    private static Map<OWLObject, String> captureRenderings(OWLModelManager live, OWLOntology active) {
        Map<OWLObject, String> out = new HashMap<>();
        // Only entities referenced by the active module can appear in structural/governance findings. Avoid
        // invoking Protégé's renderer for every axiom and every otherwise-unreferenced import term while the
        // EDT is held; isolated axiom rendering safely falls back to OWLAPI text.
        for (OWLEntity entity : active.getSignature()) {
            out.put(entity, live.getRendering(entity));
        }
        return out;
    }

    private static OWLModelManager adapter(OWLOntology active, Set<OWLOntology> closure,
            Map<OWLObject, String> renderings) {
        OWLEntityFinder finder = (OWLEntityFinder) Proxy.newProxyInstance(
                IsolatedValidationSnapshot.class.getClassLoader(),
                new Class<?>[] {OWLEntityFinder.class}, new FinderHandler(closure, renderings));
        return (OWLModelManager) Proxy.newProxyInstance(IsolatedValidationSnapshot.class.getClassLoader(),
                new Class<?>[] {OWLModelManager.class},
                new ModelManagerHandler(active, closure, renderings, finder));
    }

    private static final class ModelManagerHandler implements InvocationHandler {
        private final OWLOntology active;
        private final Set<OWLOntology> closure;
        private final Map<OWLObject, String> renderings;
        private final OWLEntityFinder finder;

        ModelManagerHandler(OWLOntology active, Set<OWLOntology> closure,
                Map<OWLObject, String> renderings, OWLEntityFinder finder) {
            this.active = active;
            this.closure = closure;
            this.renderings = renderings;
            this.finder = finder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getActiveOntology":
                    return active;
                case "getActiveOntologies":
                    return closure;
                case "getOWLOntologyManager":
                    return active.getOWLOntologyManager();
                case "getOWLDataFactory":
                    return active.getOWLOntologyManager().getOWLDataFactory();
                case "getOWLEntityFinder":
                    return finder;
                case "getRendering":
                    return rendering(args[0], renderings);
                case "toString":
                    return "IsolatedValidationModelManager[" + active.getOntologyID() + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("The isolated validation adapter does not expose "
                            + method.getName() + ".");
            }
        }
    }

    private static final class FinderHandler implements InvocationHandler {
        private final Set<OWLOntology> closure;
        private final Map<OWLObject, String> renderings;

        FinderHandler(Set<OWLOntology> closure, Map<OWLObject, String> renderings) {
            this.closure = closure;
            this.renderings = renderings;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getEntities":
                    if (args != null && args.length == 1 && args[0] instanceof IRI) {
                        Set<OWLEntity> matches = new LinkedHashSet<>();
                        for (OWLOntology ontology : closure) {
                            for (OWLEntity entity : ontology.getSignature()) {
                                if (entity.getIRI().equals(args[0])) {
                                    matches.add(entity);
                                }
                            }
                        }
                        return matches;
                    }
                    break;
                case "getOWLEntity":
                    if (args != null && args.length >= 1 && args[0] instanceof String) {
                        String ref = (String) args[0];
                        List<OWLEntity> matches = new ArrayList<>();
                        for (OWLOntology ontology : closure) {
                            for (OWLEntity entity : ontology.getSignature()) {
                                String display = rendering(entity, renderings);
                                if (ref.equals(display) || ref.equals(shortForm(entity.getIRI()))) {
                                    matches.add(entity);
                                }
                            }
                        }
                        matches.sort(Comparator.comparing((OWLEntity entity) ->
                                entity.getEntityType().getName().toLowerCase(Locale.ROOT))
                                .thenComparing(entity -> entity.getIRI().toString()));
                        return matches.isEmpty() ? null : matches.get(0);
                    }
                    break;
                case "toString":
                    return "IsolatedValidationEntityFinder";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    break;
            }
            throw new UnsupportedOperationException("The isolated validation entity finder does not expose "
                    + method.getName() + ".");
        }
    }

    private static String rendering(Object object, Map<OWLObject, String> captured) {
        if (object instanceof OWLObject) {
            String value = captured.get(object);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        if (object instanceof OWLEntity) {
            return shortForm(((OWLEntity) object).getIRI());
        }
        return String.valueOf(object);
    }

    private static String shortForm(IRI iri) {
        String value = iri.toString();
        int cut = Math.max(value.lastIndexOf('#'), value.lastIndexOf('/'));
        return cut >= 0 && cut < value.length() - 1 ? value.substring(cut + 1) : value;
    }
}
